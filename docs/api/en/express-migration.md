# Coming from Express

[한국어](../express-migration.md) | [English](express-migration.md)

Most Express route, middleware and `req`/`res` structure transfers directly. Read the differences below before migrating an application.

## API mapping

### Application

| Express | NaruServe (Java) | Notes |
|---|---|---|
| `const app = express()` | `RestApp app = RestApp.create();` | |
| `app.listen(3000, () => ...)` | `ServerHandle h = app.listen(3000);` or `RestApp.start(3000, app -> ...)` | Returns after binding; the next statement means started. Failure throws. `start` also runs the configuration callback, logs startup and adds a JVM shutdown hook. Both default to `127.0.0.1`. See [start](app.md#start) |
| `server.close()` | `app.shutdown(10, TimeUnit.SECONDS);` | Waits for active requests, then stops |
| `app.locals.x = v` | `ctx.attribute("x", v)` in `onStart` | [Lifecycle](lifecycle.md) |
| `app.set('trust proxy', ...)` | `app.trustProxy(true)` | Proxy headers are untrusted by default. Enabling uses `X-Forwarded-For` / `X-Forwarded-Proto` in `req.remoteAddress()` / `req.isSecure()`. See [server settings](app.md) |
| `app.listen(3000, '0.0.0.0')` | `app.server(o -> o.bindAddress("0.0.0.0"))`, then `app.listen(3000)` | Default is loopback. See [binding](#4-default-binding-is-localhost) |

### Routing

| Express | NaruServe (Java) | Notes |
|---|---|---|
| `app.get('/u/:id', h)` | `app.get("/u/:id", h)` | Also `post`, `put`, `patch`, `delete`, `head`, `options` |
| `app.all('/x', h)` | `app.route(Method.any(), "/x", h)` | |
| `app.route('/b').get(h1).post(h2)` | `app.route("/b").get(h1).post(h2)` | Same shape |
| No equivalent | `app.route(Method.get().post(), "/x", h)` | Several methods, one handler |
| No equivalent | `app.route(Method.any().except(Method.DELETE), "/x", h)` | Exclude selected methods |
| `app.get('/x', auth, h)` | `app.get("/x", auth, h)` | One route middleware. For several, use `app.route("/x").use(auth).use(admin).get(h)`. Java varargs must be last, so middleware chains use `RouteBuilder.use` |
| `express.Router()` plus `app.use('/api', router)` | `app.group("/api", api -> { ... })` | Prefix grouping, not a standalone Express Router object. Group `use` applies to all routes in that group |
| `'/ab?cd'`, `'/a*'`, regex (Express 4); `{/:opt}`, `/*splat` (Express 5) | Unsupported | Only fixed segments and `:name` variables |

### Request `req`

| Express | NaruServe (Java) | Notes |
|---|---|---|
| `req.params.id` | `req.param("id")` | |
| `req.query.page` | `req.query("page")` | `null` if absent |
| `req.query.tag` (array) | `req.queries("tag")` | `List<String>`; empty if absent |
| `req.body` | `req.body(User.class)` | Specify the type |
| `req.body` (array) | `req.body(new TypeRef<List<User>>() {})` | Generic type |
| `req.body.name` (form) | `req.form("name")` | Urlencoded and multipart text fields |
| `req.get('Authorization')` | `req.header("Authorization")` | Case-insensitive name |
| `req.cookies.session` | `req.cookie("session")` | Returns `Cookie`; read `.getValue()` |
| `req.file` (multer) | `req.file("avatar")` | `UploadFile` |
| `req.files` (multer) | `req.files("attachments")` | `List<UploadFile>` |
| `req.method` | `req.method()` | `Method` enum rather than a string |
| `req.path` (undecoded) | `req.rawPath()` | Original path without query text, matching Express's path behavior |
| No equivalent | `req.path()` | NaruServe's percent-decoded path; Express `req.path` does not decode |
| `req.originalUrl` | No direct equivalent | Read the path through `req.rawPath()` and parsed query values through `req.query(...)` / `req.queries(...)`. These values do not preserve enough information to reconstruct the exact original URL |
| `req.ip` | `req.remoteAddress()` | |
| `req.secure` | `req.isSecure()` | |
| `req.user = u` / `res.locals.user = u` | `req.attribute("user", u)` | Request scope |
| `req.app.locals.x` | `req.application().attributeAs("x", String.class)` | |

### Response `res`

| Express | NaruServe (Java) | Notes |
|---|---|---|
| `res.status(201).json(o)` | `res.status(201).json(o)` | Same shape |
| `res.send('text')` | `res.send("text")` | **`text/plain`**, whereas Express defaults to HTML |
| `res.send('<p>hi</p>')` | `res.html("<p>hi</p>")` | Send HTML explicitly |
| `res.send({a: 1})` | `res.send(map)` | Objects/lists/numbers/booleans use JSON; null sends no body. Use `res.json(null)` for JSON null |
| `res.send(buffer)` | `res.send(bytes)` | `byte[]` |
| `res.sendStatus(204)` | `res.status(204).end()` | |
| `res.end()` | `res.end()` | |
| `res.set('X-A', 'b')` | `res.header("X-A", "b")` | |
| `res.cookie('s', 'v', {httpOnly: true})` | `res.cookie("s", "v", o -> o.httpOnly(true))` | [Cookie](cookie.md) |
| `res.clearCookie('s')` | `res.clearCookie("s")` | Match the original Path and Domain options if used |
| `res.sendFile(path)` | `res.file(file)` | `java.io.File`; no `Content-Disposition`, allowing inline browser display |
| `res.download(path, name)` | `res.download(file, name)` | Adds `Content-Disposition: attachment`; symlinks and non-regular files receive `404` |
| `stream.pipe(res)` | `res.stream(in, "video/mp4")` | |
| `res.headersSent` | `res.isCommitted()` | |
| `res.redirect(url)` | `res.redirect(url)` | Default 302; use `res.redirect(307, url)` to choose status |
| `res.type('png')` | `res.type("png")` | Built-in MIME table |
| `res.render(view)` | Not in v1 | Send HTML with `res.html(...)`; the v1 specification has no template engine or view rendering |

Previous `send(String)` / `send(byte[])` overloads are now one `send(@Nullable Object)` method. Recompile ordinary callers. Custom `Response` implementations must implement `send(Object)`; existing binaries must be rebuilt.

### Middleware and errors

| Express | NaruServe (Java) | Notes |
|---|---|---|
| `app.use((req, res, next) => { ...; next() })` | `app.use((req, res, next) -> { ...; next.run(); })` | |
| `app.use('/api', mw)` | `app.use("/api", mw)` | Express strips the mount prefix inside mounted middleware (`req.baseUrl`); NaruServe keeps the complete `req.path()`, such as `/api/users`, so middleware and handlers see the same path |
| `next(err)` | `next.error(err)` or `throw` | |
| `app.use((err, req, res, next) => ...)` | `app.onError((err, req, res) -> ...)` | Three arguments; no `next` |
| Final 404 middleware | Not needed | Standard JSON 404/405; their default body is not customizable in v1. See [errors](middleware.md) |
| `express.json()`, `express.urlencoded()` | Not needed | `req.body(...)` / `req.form(...)` parse on demand. Configure the JSON limit with `app.jsonLimit(bytes)` |
| `express.urlencoded({ limit })` | `app.formLimit(bytes)` | Default 1 MiB; also caps total multipart text-field contents because text fields stay in memory |
| `express.raw()`, `express.text()` | Unsupported | No v1 raw/text request-body API; use JSON, form or upload APIs |
| `cors()` | `app.cors(o -> o.allowedOrigins("https://app.example.com"))` | Register before authentication; handles HTTP preflight separately from WebSocket Origin policy |
| `cookie-parser` | Not needed | Built in. No signed cookies (`secret`) or automatic value encoding in v1; values are read/written as supplied. See [Cookie](cookie.md) |
| `multer` | `app.multipart(config -> ...)` | Configures limits; read `req.file(...)` in the handler. Application-wide configuration lets parsing happen once under consistent limits |
| `multer({ limits: { fileSize, files, parts } })` | `app.multipart(c -> c.maxFileSize(...).maxParts(...))` | `maxFileSize`, `maxRequestSize`, `maxParts`, `memoryThreshold`; defaults 32 MiB, 64 MiB, 20, 64 KiB. Bounded defaults limit storage use; unlimited values are rejected |
| `multer({ dest })` | `c.tempDirectory(dir)` plus `file.save(...)` | Default `java.io.tmpdir` (app cache directory on Android). Temporary files are deleted when the request ends |
| `express.static('public')`, `serve-static` | `app.staticFiles("/", new File("public"))` | Searched after explicit routes. Directory requests missing a slash get 301 |
| `serve-static` `setHeaders` for caching | `options.cache(pattern, policy)` | Example: `options.cache("/assets/**", CachePolicy.publicFor(365, TimeUnit.DAYS).immutable())`. First registered matching pattern wins; declarative patterns cover common cache policies |
| `morgan('combined')` | `app.accessLog(o -> o.enabled(true))` | |

### WebSocket and SSE

| Express | NaruServe (Java) | Notes |
|---|---|---|
| `app.ws('/ws', (ws, req) => ...)` (express-ws) | `app.ws("/ws", (req, ws) -> ...)` | **Reversed argument order** |
| `ws.on('message', m => ...)` | `ws.onText(m -> ...)` | `onBinary` for binary; `onJson` for JSON |
| `ws.send(text)` | `ws.sendText(text)` | |
| `ws.on('close', ...)` | `ws.onClose((code, reason) -> ...)` | |
| `ws.close(code, reason)` | `ws.close(code, reason)` | No-argument `close()` means `close(1000, "")` |
| `ws.on('error', ...)` | `ws.onError(error -> ...)` | |
| `new WebSocket.Server({ maxPayload })` | `app.webSocket(o -> o.maxMessageSize(bytes))` | Also sets allowed Origins, send queue, ping interval and idle timeout. See [WebSocket](websocket.md#configuration) |
| SSE response headers plus `res.write(...)` | `SseClient sse = res.sse(); sse.send("event", "data");` | |

## Migrating older NaruServe code

Typed attribute reads changed from `attribute(key, Type.class)` to `attributeAs(key, Type.class)` across request, application and WebSocket stores. Writes/removals still use `attribute(key, value)` / `attribute(key, null)`; explicit `removeAttribute(key)` is also available. Rename methods in custom implementations and recompile.

Successful JSON body reads are cached for the same type, so middleware validation and handler lookup do not parse twice. Global and prefix middleware now share one registration-order sequence; check authentication and CORS ordering when migrating.

## Differences to understand

### 1. Handlers run concurrently on multiple threads

Node.js schedules callbacks on one event loop. NaruServe handles requests concurrently in a thread pool.

- Shared services, caches and counters must be thread-safe. Use `ConcurrentHashMap`, `AtomicLong` or synchronization.
- Blocking calls such as database queries are allowed in handlers; no callback or `await` is required. Avoid very long work because pool capacity is finite.

```java
// A shared Java counter needs synchronization, unlike a simple Express event-loop counter.
AtomicLong count = new AtomicLong();
app.get("/count", (req, res) -> res.json(count.incrementAndGet()));
```

### 2. Finish the response before returning

Express can respond later from a callback or Promise. NaruServe v1 handlers/middleware are synchronous: finish responding before return. Explicit framework-managed SSE, WebSocket and file/stream responses are the exceptions that continue delivery afterward.

### 3. Successful JSON results are reused for the same type

The two lines below are alternatives for different request-body shapes. Do not call both consecutively on one request.

```java
User user = req.body(User.class);                         // Object
List<User> users = req.body(new TypeRef<List<User>>() {}); // Generic
```

- Only a JSON `Content-Type` is accepted; others receive `415`.
- The stream is read once; repeat reads of the same successful type reuse the result. A different type or mixing JSON/form reads throws `BodyAlreadyConsumedException`.
- The default JSON limit is 1 MiB, compared with Express `express.json()`'s 100 KB default.

### 4. Default binding is localhost

Express's `app.listen(3000)` accepts connections on all interfaces. NaruServe's binds only to localhost (`127.0.0.1`). To accept other LAN devices, explicitly configure `app.server(o -> o.bindAddress("0.0.0.0"))` before listening. For `RestApp.start`, put that configuration inside its lambda. See [start](app.md#start).

Add authentication middleware for LAN exposure. A non-loopback bind always emits a security warning, regardless of authentication configuration.

### 5. Path matching is strict

| Item | Express default | NaruServe default |
|---|---|---|
| Case | Insensitive (`/Users` = `/users`) | Sensitive |
| Trailing slash | Ignored (`/users/` = `/users`) | Significant |
| Patterns | `?`, `*`, `+`, regex in Express 4; `{}` optional parts and `*name` in Express 5 | Fixed segments and `:name` |
| Priority | Registration order | Fixed segments before variables: `/users/me` before `/users/:id` |
| Duplicate route | First registered wins | Configuration error at startup |

### 6. Automatic 404, 405, HEAD and OPTIONS

- No path yields `404`; a path without the requested method yields `405` with `Allow`.
- Registering `GET` also handles bodyless `HEAD`.
- Automatic `OPTIONS` returns `204` with `Allow` (Express uses `200`).
- Explicit routes are searched before static mounts, then 404.

### 7. Errors have a standard JSON body

Unhandled exceptions and parsing failures omit stack traces. A JSON parse failure (`400`) looks like this; `500` uses code `INTERNAL_ERROR`:

```json
{"error":{"code":"BAD_REQUEST","message":"Invalid JSON request","requestId":"..."}}
```

Customize error handling with `app.onError`. See [middleware and errors](middleware.md).

### 8. Complete configuration before listen

Register routes, middleware and static mounts before `listen()`. Register services with `ctx.register` only inside `onStart` during startup. Routes cannot be added while running. A terminated app cannot restart; create a new one.

### 9. Register services explicitly

Register shared objects in `onStart` and retrieve them through `req.service(...)`:

```java
app.onStart(ctx -> ctx.register(UserService.class, new UserService()));
app.get("/users", (req, res) -> res.json(req.service(UserService.class).findAll()));
```

See [lifecycle and services](lifecycle.md).

### 10. Global and prefix middleware share registration order

Like Express, matching global and prefix middleware follow one `app.use` order. Route middleware and the handler follow. Register request IDs and CORS before authentication when they must run first:

```java
Middleware requestId = (req, res, next) -> next.run();   // Example global request-ID middleware
app.use(requestId);      // First
app.use("/api", auth);   // Second on /api paths
```

See [middleware](middleware.md).

## Example: migrating an Express app

These snippets compare route configuration. Your application supplies domain types/objects such as `Todo`, `TodoService`, `todos` and `auth`. See [running the examples](../../../examples/README.md) for complete imports and executable entry points.

Express:

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

NaruServe (Java):

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

NaruServe (Kotlin):

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

## Express features outside v1

These are post-v1 items because no v1 implementation wave owns them. See the tables above and [release notes](../../release/release-notes.md) for details and alternatives:

- Standalone remountable `express.Router()` objects are unsupported; split prefixes with `app.group(...)`.
- HTTP CORS is provided by `app.cors(...)`; WebSocket Origins are configured separately.
- `res.format` is unsupported; `redirect`, `type`, `location` and `sendStatus` are available.
- No `req.hostname`, `req.is(type)` or `req.accepts(...)`.
- No `app.param(...)`.
- No asynchronous Promise/`async` handlers. Kotlin `suspend` handlers are also post-v1; see [Kotlin](kotlin.md).
