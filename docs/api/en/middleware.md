# Middleware and error handling

[한국어](../middleware.md) | [API index](README.md)

## Middleware

This corresponds to Express's (req, res, next) callback. Call next.run() instead of next(). Examples are fragments; auth, services and DTO types stand for application code.

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

| Registration | Scope |
|---|---|
| `app.use(mw)` | Every request |
| `app.use("/api", mw)` | /api and descendants such as /api/users; excludes /apix |
| `app.get("/admin", mw, handler)` | One middleware for this route |
| `app.route("/admin").use(m1).use(m2).get(h)` | In use order, for methods registered after use |

RouteBuilder.use is the way to attach multiple middleware. A later use does not modify an earlier get registration. This avoids Java's requirement that varargs come last and keeps Kotlin trailing-lambda syntax usable.

```java
Middleware adminOnly = (req, res, next) -> next.run();   // placeholder adminOnly middleware for this example

app.route("/api/admin")
   .use(auth)
   .use(adminOnly)
   .get((req, res) -> res.send("admin"));
```

Kotlin:

```kotlin
app.route("/api/admin")
    .use(auth)
    .use(adminOnly)
    .get { req, res -> res.send("admin") }
```

### Request ID and authentication

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

Use a labelled return to leave a Kotlin middleware lambda early:

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

Register CORS before authentication to allow browser requests from another origin:

```java
app.cors(cors -> cors
    .allowedOrigins("https://app.example")
    .allowedMethods(Method.GET, Method.POST)
    .allowedHeaders("Content-Type", "Authorization")
    .credentials(true)
    .maxAge(600));
app.use(auth);
```

- allowedOrigins takes exact HTTP(S) origins without paths or trailing slashes, such as https://app.example. Default: no origins. `*` allows any origin; literal `null` allows opaque origins.
- Default methods are GET, HEAD and POST. allowedMethods replaces the set; allowedHeaders replaces a case-insensitive list, empty by default.
- credentials defaults false. Combining credentials(true) with wildcard origin throws during registration. maxAge uses nonnegative seconds; default zero omits caching metadata.
- Allowed origin requests receive Access-Control-Allow-Origin and merge Origin into Vary. Disallowed ordinary requests continue without CORS allow headers. CORS does not authenticate requests.
- OPTIONS with Origin and Access-Control-Request-Method is preflight. An allowed origin, method and header list yields empty 204 before auth; malformed or disallowed preflight yields 403. Existing Vary values, including `*`, remain, with preflight method/header dimensions added.
- Even requests without Origin add Vary: Origin for cache separation, while body and routing stay unchanged. Ordinary non-preflight OPTIONS proceeds. Framework-generated 400/401/500 responses retain framework CORS and Vary metadata for allowed origins. Options are copied at registration, so later mutation of a retained options object does not change policy. Configure before listen.
- WebSocket Upgrade is excluded: configure its separate Origin policy with app.webSocket(o -> o.allowedOrigins(...)).

### Route groups

group(prefix, configurer) removes repeated prefixes. A group is not another server and has no listen/lifecycle methods.

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

GET /api/admin/reports runs app middleware, auth, adminOnly, audit, then handler. Groups support GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS.

- Group use applies to every route in that group and descendants, regardless of its position inside the callback. Parent middleware precedes child middleware.
- A separately registered route outside the group does not inherit group middleware even with the same prefix. Use app.use(prefix, mw) for all matching requests.
- group.route(path).use(mw), like RouteBuilder, affects only later method registrations.
- Paths must start with /. Boundary slashes join: /api/ + /users becomes /api/users; /api + / becomes /api. Dot/dot-dot, query, fragment, backslash and encoded path separators are rejected.
- Use groups/builders only within their configuration callback and call app.group before listen. A throwing callback does not register its group routes.

### Execution order

1. Global and matching prefix middleware in one shared registration order.
2. Route middleware in registration order.
3. Route handler.

Global and prefix middleware follow the same app.use order, as in Express. Only nonmatching prefixes are skipped. /api matches /api/users but not /apian. Route middleware follows all applicable app.use middleware.

```java
Middleware requestId = (req, res, next) -> next.run();   // placeholder global request-ID middleware
app.use(requestId);          // runs first
app.use("/api", auth);       // runs second for /api paths
```

If there is no explicit route, step 3 checks static files then 404. App middleware still executes before 404/405. Call app.use only before listen.

### next.run rules

- Call it once. Repeated calls, calls after the response has finished, or calls after the request has ended throw.
- It is synchronous: downstream middleware and the handler finish before it returns, allowing before/after work.

```java
app.use((req, res, next) -> {
    long start = System.nanoTime();
    next.run();
    long ms = (System.nanoTime() - start) / 1_000_000;
    app.logger().debug(req.path() + " " + ms + "ms");
});
```

SSE, WebSocket and file transfers can continue after next.run returns. This measures handler execution, not completed transmission.

Returning without either a response or next.run logs one warning containing the method and path, such as `response not finished by the handler: GET /x`, then ends with the selected status and headers. This avoids retaining a connection until timeout while exposing the mistake. Asynchronous middleware that saves next for later is unsupported in v1.

### WebSocket

app.use("/ws", auth) runs on the HTTP Upgrade request, not every later WebSocket message. Authenticate the upgrade there. See [WebSocket](websocket.md).

## Error handling

### Throw an exception

Thrown middleware/handler exceptions and next.error(e) enter the error path:

```java
app.get("/users/:id", (req, res) -> {
    User user = users.findById(req.param("id"));
    if (user == null) throw new NotFoundException();   // application-defined exception
    res.json(user);
});
```

### app.onError

This corresponds to Express error middleware with the same argument order but no next: (error, req, res). ErrorHandler permits checked exceptions without wrappers.

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

- Once committed, the framework never appends a 500 JSON body. A custom handler must abort instead of overwriting it.
- Without a registered handler, the standard format below applies.
- Framework errors use unchecked HttpException: status() is 400..599 and code() is a standard code. Applications may throw new HttpException(404, "User not found"), similar to Express err.status.
- UnsupportedMediaTypeException is the 415 subclass. If a handler treats every exception as 500 without checking HttpException first, it converts those 4xx errors too.
- Multiple onError callbacks form a registration-order chain. The first always runs; later callbacks run only until a response begins. This replaces Express next(err) with the simpler rule that a response stops the chain.
- If no callback starts a response, standard JSON answers. A callback exception stops the chain and produces standard 500 if still uncommitted. A callback that starts a stream on an initially uncommitted response supplies the response and that stream completes normally. Errors on an already-started response abort the connection.

### Rejection headers

HttpException.withHeader(name, value) adds a header and returns the same exception. headers() returns an insertion-ordered, read-only list, empty by default. It corresponds to Express err.headers propagated through finalhandler.

```java
app.get("/api/busy", (req, res) -> {
    throw new HttpException(503).withHeader("Retry-After", "1");
});
```

Kotlin:

```kotlin
app.get("/api/busy") { req, res ->
    throw HttpException(503).withHeader("Retry-After", "1")
}
```

- The error path resets prior application response headers. If onError does not respond, standard errors add exception headers. A responding custom onError controls its own headers. Framework-owned CORS metadata is preserved as described above.
- Invalid names/values, including CR/LF, throw IllegalArgumentException as in res.header. Content-Length and Transfer-Encoding are controlled by the body writer rather than copied from exception metadata.
- Repeated same-name additions are sent separately. Create a fresh exception for every throw.

### Standard error format

```json
{"error":{"code":"BAD_REQUEST","message":"Invalid JSON request","requestId":"..."}}
```

This is a 400 example. No stack trace reaches the client. message uses the HttpException message, falling back to the reason phrase; 500 always says Internal Server Error. The framework writes this JSON without invoking the app codec.

v1 does not offer a custom 404/405 body setting, keeping a consistent format across apps/codecs. Global/prefix middleware still runs before those responses, and its headers remain.

| Situation | Status | code |
|---|---|---|
| JSON/form parse failure, malformed path/query reaching the app | 400 | BAD_REQUEST |
| App validation failure | 400 or explicitly 422 | BAD_REQUEST / UNPROCESSABLE_ENTITY |
| No route | 404 | NOT_FOUND |
| No method | 405 | METHOD_NOT_ALLOWED |
| Body/upload above limit | 413 | PAYLOAD_TOO_LARGE |
| Unsupported Content-Type | 415 | UNSUPPORTED_MEDIA_TYPE |
| Ordinary request on WebSocket-only path | 426 | UPGRADE_REQUIRED |
| Other exception | 500 | INTERNAL_ERROR |
| Unsupported method | 501 | NOT_IMPLEMENTED |
| STOPPING or failed startup | 503, Connection: close | SERVICE_UNAVAILABLE |
| Unlisted status, such as HttpException(418) | That status | HTTP_<status>, for example HTTP_418 |

A full transport queue also yields 503, but TinyHttpd writes it as text/plain without standard JSON or onError: no app worker is available. Likewise, transport validation can reject requests before the app; see [defaults](defaults.md). application/problem+json (RFC 9457) is not a v1 response option.
