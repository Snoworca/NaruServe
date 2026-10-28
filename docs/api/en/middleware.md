# Middleware and error handling

[한국어](../middleware.md) | [API index](README.md)

This is the common HTTP stack in the 0.10.0 checkout. Callbacks, responses and errors work on Java 8/API 21 without a mandatory Promise or coroutine library.

## Middleware

The retained core signatures are below. The actual `Next` also has default `route()`/`router()` methods described later.

```java
@FunctionalInterface
public interface Middleware {
    void handle(Request req, Response res, Next next) throws Exception;
}

public interface Next {
    void run();                 // run the next middleware or handler
    void error(Throwable error); // enter error handling (Express: next(err))
}
```

### Registration

| Call | Behavior |
|---|---|
| `app.use(mw)` | Append global middleware at the current position |
| `app.use("/api", mw)` | Run on matching prefixes, removing the consumed prefix during the callback |
| `app.get(path, mw1, mw2, ...)` | Append three-argument callbacks in order |
| `app.get(path, mw, handler)` | Existing middleware plus two-argument terminal-handler convenience |
| `app.route(path).use(mw).get(handler)` | Append callbacks to one Route |

`get(path, Middleware...)` and fixed-arity overloads also support Kotlin trailing lambdas. Null/empty callback arrays are rejected before registration. A Route's `use` applies across its methods but keeps its registration position. It does not automatically run after an earlier handler ends the request.

```java
Middleware adminOnly = (req, res, next) -> next.run();   // placeholder adminOnly middleware for this example

app.route("/api/admin")
   .use(auth)
   .use(adminOnly)
   .get((req, res) -> res.send("admin"));
```

```kotlin
app.route("/api/admin")
    .use(auth)
    .use(adminOnly)
    .get { req, res -> res.send("admin") }
```

### Request ID and authentication

The application supplies `auth`, `admin` and `User`. After responding, return without `next.run()` unless continuation is intentional.

```java
app.use((req, res, next) -> {
    req.attribute("requestId", UUID.randomUUID().toString());
    next.run();
});

app.use("/api", (req, res, next) -> {
    if (!auth.isAuthorized(req)) {
        res.status(401).json("Unauthorized");
        return;                  // return without next.run() to stop the chain
    }
    next.run();
});

Middleware adminOnly = (req, res, next) -> {
    User user = req.attributeAs("user", User.class);
    if (user == null || !user.isAdmin()) {
        res.status(403).json("Forbidden");
        return;
    }
    next.run();
};
app.get("/api/admin", adminOnly, (req, res) -> res.json(admin.status()));
```

```kotlin
app.use("/api") { req, res, next ->
    if (!auth.isAuthorized(req)) {
        res.status(401).json("Unauthorized")
        return@use
    }
    next.run()
}
```

### HTTP CORS

Register CORS before authentication so browser preflight can complete first.

```java
app.cors(cors -> cors
    .allowedOrigins("https://app.example")
    .allowedMethods(Method.GET, Method.POST)
    .allowedHeaders("Content-Type", "Authorization")
    .credentials(true)
    .maxAge(600));
app.use(auth);
```

- `allowedOrigins` contains exact HTTP(S) origins without paths/trailing slashes. Default: none. `*` allows every origin; the string `null` allows opaque origins.
- Default methods are GET, HEAD, POST. `allowedMethods` replaces the set; `allowedHeaders` replaces a case-insensitive allowlist, empty by default.
- `credentials` defaults false. Combining `credentials(true)` and `*` fails registration. `maxAge` uses nonnegative seconds; default 0 omits caching metadata.
- Allowed origins receive `Access-Control-Allow-Origin` and, when enabled, `Access-Control-Allow-Credentials: true`. Disallowed ordinary origins continue without grants. CORS does not authenticate.
- OPTIONS with `Origin` and `Access-Control-Request-Method` is preflight. Allowed method/headers produce empty 204 before authentication. Invalid/disallowed preflight produces 403. Distinguish this from ordinary automatic OPTIONS 200.
- Requests without Origin still set `Vary: Origin`. Ordinary response Vary values, including `*`, are merged; preflight adds method/request-header dimensions.
- **Error fallback with this explicit CORS extension** clears ordinary application headers and restores only tracked framework CORS grants/Vary. Credentials survive; app/`HttpException` headers cannot override the grant. Common HTTP errors without installed CORS retain ordinary app headers.
- Options are copied, and `cors` is configured before listen. WS Upgrade uses the separate `app.webSocket(o -> o.allowedOrigins(...))` policy.

### Route groups

`group(prefix, configurer)` retains safe string-prefix joining and common middleware. Distinguish this convenience from remountable independent Router/Pattern APIs.

```java
Middleware adminOnly = (req, res, next) -> {
    if (!"admin".equals(req.header("X-Role"))) {
        res.status(403).end();
        return;
    }
    next.run();
};
Middleware audit = (req, res, next) -> {
    req.application().logger().info("audit " + req.path());
    next.run();
};
app.group("/api", api -> {
    api.use(auth);
    api.get("/users", (req, res) -> res.send("users"));
    api.group("/admin", admin -> {
        admin.use(adminOnly);
        admin.route("/reports").use(audit).get((req, res) -> res.send("reports"));
    });
});
```

The example `/api/admin/reports` runs group auth, adminOnly, audit, then handler. Group `use` applies before routes in the group and descendants regardless of where it appears in the configuration callback. Parent middleware precedes child middleware. Separate routes outside the group do not inherit it merely by sharing a prefix.

A group's `route(path)` also has one persistent Route builder; its `.use`/method callbacks retain order. Paths start with `/`; boundary slashes join (`/api/` + `/users` → `/api/users`, `/api` + `/` → `/api`). Dot/dot-dot, query, fragment, backslash and encoded separators are rejected. Call `group` before listen and use groups/group builders only inside their configuration callbacks. A throwing callback does not partially publish that group's routes.

### Execution order

Common HTTP global/prefix `use`, Routes, mounted Routers, static files and error layers follow **one registration order**. Later global middleware is not moved ahead of earlier routes. The next layer runs only when the previous one continues. Static misses and final 404 use this same sequence.

```java
Middleware requestId = (req, res, next) -> next.run();   // placeholder global request-ID middleware
app.use(requestId);          // runs first
app.use("/api", auth);       // runs second for /api paths
```

### next.run rules

On the execution thread, `next.run()` usually invokes downstream callbacks before returning, allowing before/after work. Deep chains may yield for bounded stack use; calls from other threads enter a safe queue. User callbacks never execute under a request-wide monitor. A callback that joins a child thread invoking Next does not deadlock merely for that reason.

```java
app.use((req, res, next) -> {
    long start = System.nanoTime();
    next.run();
    long ms = (System.nanoTime() - start) / 1_000_000;
    app.logger().debug(req.path() + " " + ms + "ms");
});
```

This measures callback work rather than total network transmission. Streams, SSE and WS can continue afterward. Repeated next and explicit next after a response are not categorically rejected. They advance the actual cursor; do not use repeated invocation as a deduplication mechanism. Calls after terminal abort/cancellation/shutdown cannot resurrect closed resources or application services.

| Call | Common HTTP meaning |
|---|---|
| `next.run()` | Continue normal flow; in error middleware, clear the error and resume normal flow |
| `next.error(error)` | Pass to later error layers; null is rejected |
| `next.route()` | Skip the rest of the current Route; ordinary use continues normally |
| `next.router()` | Exit the nearest Router; at top level, leave the app stack |

Captures after continuing in the same router are not blindly reset to earlier values. Exiting a mounted child restores parent path/url/baseUrl/params views. `next.route()` does not rewrite the URL or select a named route.

Returning without response or next **leaves the request pending**, rather than logging a warning and automatically ending empty 200. A saved callback can later next, send, stream or start SSE. The application must provide completion, timeout and cancellation paths.

### WebSocket

Existing `app.use("/ws", auth)` authenticates the HTTP Upgrade, not every message afterward. Existing valid global/string-prefix middleware and strict WS path/Origin rules remain. Do not generalize advanced HTTP paths or HTTP layers appended after listen to WS behavior. See [WebSocket](websocket.md).

## Error handling

### Throw an exception

Synchronous exceptions, `next.error(e)` and rejection of a returned Deferred proceed to error layers **after the current position**, skipping normal layers without restarting earlier error handlers.

```java
app.get("/users/:id", (req, res) -> {
    User user = users.findById(req.param("id"));
    if (user == null) throw new NotFoundException();   // application-defined exception
    res.json(user);
});
```

### app.onError

The retained three-argument `ErrorHandler` permits checked exceptions.

```java
@FunctionalInterface
public interface ErrorHandler {
    void handle(Throwable error, Request req, Response res) throws Exception;
}
```

```java
app.onError((error, req, res) -> {
    if (res.isCommitted()) {   // abort if the response has already started
        res.abort(error);
        return;
    }
    if (error instanceof HttpException) {   // framework 4xx errors and application HttpException errors
        HttpException http = (HttpException) error;
        res.status(http.status()).send(http.code());
    } else {
        app.logger().error("Unhandled", error);
        res.status(500).send("Internal Server Error");
    }
});
```

Configure `onError` before listen. It occupies **its registration position** in the error stack. A response stops automatic propagation; if it leaves the response unstarted, the same error continues. A new exception thrown by the callback advances to later error layers. The example's explicit application logging is additional to the framework's once-only 500 logging.

Alternatively register four-argument `ErrorMiddleware` with `app.use((error, req, res, next) -> ...)` or `app.use(path, errorMiddleware)`. `next.error(e)` propagates/replaces; `next.run()` resumes normal flow. A committed response never receives a second error body. Late errors enter logging/abort handling without a second commit.

### Rejection headers

`HttpException.status()` is 400..599; `code()` retains standard-code convenience values. `UnsupportedMediaTypeException` is the 415 subclass. Do not turn every error into 500 without inspecting status. `withHeader(name, value)` appends to the same exception; `headers()` is an ordered read-only list.

```java
app.get("/api/busy", (req, res) -> {
    throw new HttpException(503).withHeader("Retry-After", "1");
});
```

```kotlin
app.get("/api/busy") { req, res ->
    throw HttpException(503).withHeader("Retry-After", "1")
}
```

Common HTTP final handling applies exception headers and removes Content-Encoding, Content-Language and Content-Range to suit the generated body. Explicit CORS retains its privacy rules above. Invalid headers, including CR/LF, throw `IllegalArgumentException`; the response writer controls framing headers. Duplicate names can be appended, so create a new exception for each request.

### Standard error format

The common HTTP default is **HTML**. A 404 uses `Cannot METHOD /raw/path`. Errors use the `HttpException` status, otherwise an already-selected valid 400..599 response status, otherwise 500, with an HTML-escaped Java exception/stack representation. Responses include `Content-Type: text/html; charset=utf-8`, `Content-Security-Policy: default-src 'none'`, `X-Content-Type-Options: nosniff` and the UTF-8 byte length. HEAD retains that length without transmitting body bytes. Default errors can expose stack traces to clients; public services that need to hide internals should send a safe application error response explicitly.

The following JSON is an **example of the retained standard-error extension format**, not the common HTTP default. Pre-request validation, lifecycle rejection and retained WS/legacy paths may still use it. `HttpException.code()` does not make the default HTML become JSON. Do not assume an unspecified public standard-JSON renderer exists; send `res.json(...)` explicitly in your `onError`/`ErrorMiddleware`.

```json
{"error":{"code":"BAD_REQUEST","message":"Invalid JSON request","requestId":"..."}}
```

| Cause | Status |
|---|---|
| Invalid participating capture/query/JSON/form | 400 |
| Body/upload over limit | 413 |
| Unsupported Content-Type/charset | 415 |
| Common HTTP final path/method miss | 404 |
| Unhandled server exception | 500 |
| App lifecycle rejection | 503 with `Connection: close`, retained error profile |

Host, framing and overload rejections before application dispatch can instead be transport plain text or connection closure. Not every 400 is generated by this HTML handler or application `onError`. See [defaults and security](defaults.md).

## Deferred and late work

`AsyncRouteHandler` is `Deferred handle(Request, Response, Next) throws Exception`. Use `getAsync(path, handler)`, `methodAsync(token, path, handler)` or `useAsync(path, handler)`. A returned pending Deferred holds an explicit request work lease even if the response finishes first. Merely saving Request or starting an arbitrary thread does not create this lease.

| Deferred API | Contract |
|---|---|
| `new Deferred()` / `Deferred.pending()` | Pending result |
| `Deferred.resolved()` / `Deferred.rejected(error)` | Already settled result |
| `resolve()` / `reject(error)` | First outcome returns true; later attempts false; null errors rejected |
| `onRejected(FailureHandler)` | Observe original failure, including late registration |
| `onSettled(Runnable)` | Observe successful/failed settlement, including late registration |
| `isDone()` | Whether settled |

Resolution **never automatically calls next or ends a response**. Settle after the producer's last request/upload access. Multiple leases delay cleanup until all settle or terminal cancellation occurs. Observers run outside internal locks; one throwing observer cannot prevent others. Settlement observers registered before rejection run after the captured rejection observers; late rejection observers may run concurrently.

Built-in adapters and core use `HttpExchange.retainRequest()` to separate wire-response finish from request input/context release. This does not promise body prefetch. Work needing its first `req.body(...)` after an early response must remain inside a **returned pending Deferred** and settle after last use. A custom exchange's default retainRequest is a compatibility no-op; its implementation must supply physical input/context retention. Forced cancellation/timeouts may revoke holds, and closed input is never reopened. See [lifecycle](lifecycle.md) and the [Kotlin example](kotlin.md#deferred-work-and-request-retention).

## First body read after an early response

POST the JSON string "hello" to /work. The demonstration Thread first reads the body after early 202 and settles after last request access. Use an application-managed bounded executor and cancellation policy for production load. A rejection after commitment passes onward without writing a second JSON response.

```java
app.methodAsync("POST", "/work", (request, response, next) -> {
    Deferred work = Deferred.pending();
    response.status(202).send("accepted");
    new Thread(() -> {
        try {
            String value = request.body(String.class);
            request.attribute("processed", value);
            work.resolve();
        } catch (Throwable error) {
            work.reject(error);
        }
    }, "retained-work-example").start();
    return work;
});
app.use((error, request, response, next) -> {
    if (response.isCommitted()) {
        next.error(error);
        return;
    }
    response.status(500).json(Collections.singletonMap("error", "Request failed"));
});
```
