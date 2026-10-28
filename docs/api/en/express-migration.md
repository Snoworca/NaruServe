# Coming from Express

[한국어](../express-migration.md) | [API index](README.md)

The **common HTTP routing profile in the 0.10.0 checkout** provides common Express5 string paths, ordering, Router, param, next and error flow through Java APIs. It does not claim all of Express, the Node runtime, every middleware package or JavaScript regex compatibility. Typed bodies, text/plain string responses and retained WS/SSE/file/security extensions remain NaruServe contracts. See [installation](README.md) for current artifacts.

## API mapping

### Application and routing

| Express | NaruServe | Boundary |
|---|---|---|
| `express()` | `RestApp.create()` | Default TinyHttpd 3.1.1 RAW, Gson 2.14.0 and console logger |
| `app.listen(port, callback)` | `app.listen(port)` / `RestApp.start(port, routes)` | Returns after binding, failure throws; default 127.0.0.1 |
| `server.close()` | `app.shutdown(timeout, unit)` | Active work/resource cleanup and deadline |
| `app.get/post/...` | Same-named two-argument handler or three-argument Middleware callbacks | Vararg callbacks and Kotlin fixed-arity overloads |
| `app.route(path)` | `app.route(path)` | One Route identity/position created at the call |
| `express.Router(options)` | `com.snoworca.naruserve.Router.create(mergeParams, caseSensitive, strict)` | Defaults false,false,false |
| `app.use('/api', router)` | `app.use("/api", router)` | Prefix trimming, baseUrl accumulation/restoration and remounting |
| `app.param(name, callback)` | `app.param(name, ParamHandler)` / `router.param` | Original decoded captures arrive as List<String> |
| `app.all`/extended-method registration | Register tokens with `app.method("COPY", path, callbacks...)` | `Method.any()` is the retained seven-method set, not every method |
| Path array | Nested Object[]/List of strings, Pattern and RegexPathPattern | Validate the whole tree, then select first match |
| Express5 `/:id`, `/*parts`, `{/:id}` | Same string forms | Raw match followed by one decode of participating captures |
| JavaScript RegExp | Java Pattern / RegexPathPattern | Search matching and Java flags; no ECMAScript/lastIndex emulation |
| `app.use('/api', mw)` | `app.use("/api", mw)` | path/url trimmed inside callback |
| `next()`, `next(err)` | `next.run()`, `next.error(err)` | Java rejects null errors |
| `next('route')`, `next('router')` | `next.route()`, `next.router()` | Skip Route / exit nearest Router |
| Error middleware | Four-argument ErrorMiddleware through `use` | Positioned propagation/replacement/normal resumption |
| Retained error convenience | `onError(error, req, res)` | Three arguments, configured before listen |

### Request and response

| Express | NaruServe | Difference |
|---|---|---|
| `req.params.id` | `param`, `paramOrNull`, `paramValues`, `pathParams` | Map scalar is String; wildcard is immutable List |
| `req.method` | `methodToken()` | Retained enum `method()` throws for extended tokens |
| `req.path`, `req.url`, `req.originalUrl`, `req.baseUrl` | `path()`, `url()`, `originalUrl()`, `baseUrl()` | Raw views; original rawPath remains separately available |
| `req.query.page/tag` | `query`, `queries`, `queryInt`, `requireQuery` | Flat strings/repeated values, explicit validation |
| `req.get` | `header`, `headers`, `requireHeader` | Case-insensitive names; repeated values preserved |
| `req.body` | `body(User.class)` / `body(TypeRef)` | Typed, on-demand parsing; may return null |
| Forms/multer files | `form`, `file`, `files`, `parts`, `requireFile` | Built-in multipart/temp-file/size bounds |
| `req.cookies` | `cookie`, `getCookies` | Defensive Cookie copies; no signed cookies |
| `req.user` / `res.locals` | `attribute`, `attributeAs`, `removeAttribute` | Request scope |
| `res.send('text')` | `res.send("text")` | **text/plain**, unlike Express's default HTML |
| `res.send('<p>hi</p>')` | `res.html("<p>hi</p>")` | HTML is explicit |
| Object/list/number/boolean send | `send(Object)` / `json(Object)` | Null send is empty; json(null) sends JSON null |
| `status`, `end`, `set`, `headersSent` | `status`, `end`, `header`, `isCommitted` | Retained CR/LF/framing safety |
| `sendStatus`, `redirect`, `type`, `location` | Same-named conveniences | Default redirect302; choose explicit status |
| `cookie`, `clearCookie` | Same names | Deletion must match original Path/Domain |
| `sendFile`, `download`, stream pipe | `file`, `download`, `stream` | java.io.File/InputStream ownership/safety rules |

Request caches successful body reads for the same type. Different types, mixed JSON/form access and retries after parse failure throw `BodyAlreadyConsumedException`. Default UTF-8 JSON keeps its 1 MiB limit, depth 64 and strict syntax/trailing-data checks. See [Request](request.md), [JSON](json.md) and [response](response.md).

### Built-in extensions and security

- Instead of installing `express.json()`/`urlencoded()`, `body(...)`/`form(...)` reads when needed. Configure `jsonLimit`, `formLimit` and `multipart`. There is no separate public raw/text body parser corresponding to `express.raw()`/`text()`. A JSON string is available through `body(String.class)`.
- Explicit `app.cors(...)` handles CORS. Distinguish preflight 204 from ordinary OPTIONS 200; WS Origin is separate. CORS error privacy retains tracked grants/Vary and drops ordinary application headers.
- `staticFiles` replaces `express.static` and now participates in common HTTP registration order. Canonical containment, symlink rejection, Range and conditional responses remain. Cache(pattern, policy) uses the first matching policy.
- Multipart defaults remain 32 MiB/file, 64 MiB/request, 20 parts and 64 KiB memory threshold, without an unlimited setting. Temporary files not persisted through `file.save(...)` are removed after owned request work ends.
- `accessLog` and service logging/shutdown ownership remain. See [logging](logging.md) and [lifecycle](lifecycle.md).
- WS `(req, ws)` reverses express-ws `(ws, req)`. onText/onBinary/onJson, close, Origin, queue/size, Ping and idle limits remain. Common HTTP advanced path grammar is not applied to WS registrations. SSE uses `res.sse()` and `SseEvent`.

## Migration behavior checks

### 1. Requests execute on several threads

Unlike Node's single event loop, requests run concurrently. Shared services, counters and caches need synchronization. Worker capacity is bounded.

```java
// A shared Java counter needs synchronization, unlike a simple Express event-loop counter.
AtomicLong count = new AtomicLong();
app.get("/count", (req, res) -> res.json(count.incrementAndGet()));
```

### 2. Response and next may happen later

Returning alone does not send an automatic empty 200. Resume saved Next or respond later. Pending Deferred returned by a three-argument AsyncRouteHandler retains a request work lease after an early response. Successful resolve does not implicitly next/end; rejection enters later error layers. Creating a thread or saving Request alone does not create a lease. No body-prefetch guarantee is made. See [Deferred](middleware.md#deferred-and-late-work) and the [Kotlin example](kotlin.md#deferred-work-and-request-retention).

### 3. JSON requires a target type

The following are **alternatives** for different body shapes, not consecutive reads on one request.

```java
User user = req.body(User.class);                         // Object
List<User> users = req.body(new TypeRef<List<User>>() {}); // Generic
```

Java body(Class/TypeRef) returns null for JSON null. Optional Kotlin `body<T>()` requires non-null T and therefore answers 400 for JSON null. Gson does not understand Kotlin default arguments/null constraints; the default codec disables Unsafe allocation. Validate nullable fields on a DTO with a default constructor.

### 4. Binding and validation defaults remain

Default binding is127.0.0.1. For LAN, explicitly configure `app.server(o -> o.bindAddress("0.0.0.0"))` and authentication. Non-loopback binding logs a security warning. Proxy trust defaults false; `trustProxy(true)` uses unvalidated first X-Forwarded-For/Proto values, so enable only behind a trusted proxy overwriting those headers. Host, request-line and framing limits remain separate.

### 5. String defaults are case-insensitive, non-strict and ordered

Duplicate/overlapping routes are valid; more specific paths do not jump ahead. Distinguish a HEAD callback inside one Route from GET fallback in an earlier Route. Live HTTP registrations and existing builder additions are allowed while RUNNING, but server/codecs/CORS/groups/static/onError/WS configuration still requires NEW. A terminated app cannot restart or accept additions.

### 6. Common final responses use 404, HTML and OPTIONS 200

An unhandled method on an otherwise matched path also yields 404. Automatic OPTIONS collects methods actually traversed in the current router, then sends 200, Allow and the same plain-text body, with Content-Type text/plain and no charset. Distinguish CORS preflight 204, OPTIONS * and the WS legacy profile. HEAD preserves GET length and sends no body bytes.

### 7. Default errors are HTML and may expose Java stacks

Errors proceed to ErrorMiddleware/onError after their registration position. Unhandled errors use an HTML-escaped Java exception representation, potentially exposing internal stack information. Explicitly send safe JSON/plain-text application errors when needed. A response already sent never receives a second error-body commit.

The following illustrates the **retained standard-JSON extension** format. It is not the common HTTP default; pre-request, lifecycle and WS/legacy paths can retain this profile.

```json
{"error":{"code":"BAD_REQUEST","message":"Invalid JSON request","requestId":"..."}}
```

### 8. Register services explicitly

Register services in onStart and retrieve the same objects per request. Owned AutoCloseables close in reverse order at shutdown.

```java
app.onStart(ctx -> ctx.register(UserService.class, new UserService()));
app.get("/users", (req, res) -> res.json(req.service(UserService.class).findAll()));
```

### 9. Review authentication, CORS and static order

Use, routes, mounts and error layers share one order. A static mount before authentication may respond without it. Later global middleware does not move before earlier routes.

```java
Middleware requestId = (req, res, next) -> next.run();   // Example global request-ID middleware
app.use(requestId);      // First
app.use("/api", auth);   // Second on /api paths
```

## Example: migrating an Express app

These are configuration fragments. The application supplies Todo, TodoService, todos, auth and imports. [Runnable examples](../../../examples/README.md) provide full entry points and real services. Also note Java/Kotlin body-null handling differences.

```javascript
const express = require('express');
const app = express();
app.use(express.json());
app.use(express.static('public'));

app.use((req, res, next) => { req.requestId = crypto.randomUUID(); next(); });
app.use('/api', (req, res, next) => {
  if (!req.get('Authorization')) return res.status(401).json('Unauthorized');
  next();
});

app.get('/api/todos', (req, res) => res.json(todos.findAll()));
app.post('/api/todos', (req, res) => res.status(201).json(todos.create(req.body)));
app.delete('/api/todos/:id', (req, res) => { todos.delete(req.params.id); res.sendStatus(204); });

app.use((err, req, res, next) => res.status(500).json('Internal Server Error'));
app.listen(3000);
```

```java
RestApp app = RestApp.create();

app.onStart(ctx -> ctx.register(TodoService.class, new TodoService()));

app.staticFiles("/", new File("public"));

app.use((req, res, next) -> {
    req.attribute("requestId", UUID.randomUUID().toString());
    next.run();
});
app.use("/api", (req, res, next) -> {
    if (req.header("Authorization") == null) {
        res.status(401).json("Unauthorized");
        return;
    }
    next.run();
});

app.get("/api/todos", (req, res) -> res.json(req.service(TodoService.class).findAll()));
app.post("/api/todos", (req, res) -> {
    Todo todo = req.body(Todo.class);
    res.status(201).json(req.service(TodoService.class).create(todo));
});
app.delete("/api/todos/:id", (req, res) -> {
    req.service(TodoService.class).delete(req.param("id"));
    res.status(204).end();
});

app.onError((err, req, res) -> {
    if (!res.isCommitted()) res.status(500).json("Internal Server Error");
    else res.abort(err);
});

app.listen(3000);
```

```kotlin
val app = RestApp.create()

app.onStart { ctx -> ctx.register(TodoService::class.java, TodoService()) }

app.staticFiles("/", File("public"))

app.use { req, res, next ->
    req.attribute("requestId", UUID.randomUUID().toString())
    next.run()
}
app.use("/api") { req, res, next ->
    if (req.header("Authorization") == null) {
        res.status(401).json("Unauthorized")
        return@use
    }
    next.run()
}

app.get("/api/todos") { req, res -> res.json(req.service(TodoService::class.java).findAll()) }
app.post("/api/todos") { req, res ->
    val todo = req.body(Todo::class.java) ?: throw HttpException(400, "JSON body is null")
    res.status(201).json(req.service(TodoService::class.java).create(todo))
}
app.delete("/api/todos/:id") { req, res ->
    req.service(TodoService::class.java).delete(req.param("id"))
    res.status(204).end()
}

app.onError { err, req, res ->
    if (!res.isCommitted) res.status(500).json("Internal Server Error")
    else res.abort(err)
}

app.listen(3000)
```

## Features outside this profile

There is no template engine/`res.render`, `res.format`, `req.hostname`/`req.is`/`req.accepts`, signed cookie support, separate raw/text parser or native Kotlin suspend handler. Compare the declared public APIs and retained extensions. Java Pattern has Java semantics and WS keeps its strict profile; string send, typed bodies and security defaults are not claimed identical to Express.
