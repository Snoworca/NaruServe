# RestApp

[한국어](../app.md) | [API index](README.md)

`RestApp` corresponds to Express's `app`. Use it to register routes, middleware and configuration, then start and stop a server. Examples below are API fragments; use the [quick start](README.md#quick-start) for imports and an executable entry point. Application-specific types such as `Database` and `UserService` stand for your own implementations.

This page describes common HTTP in **NaruServe 0.11.0**. Follow the [installation guide](README.md) for source/artifact setup. TinyHttpd is pinned to 3.1.1 with raw request-target delivery; default Gson remains 2.14.0. Core APIs target Java 8/API 21; this is not a device performance claim.

## Create an app

```java
RestApp app = RestApp.create();
```

`create()` assembles TinyHttpd, Gson 2.14.0 and a console logger through the bootstrap module `NaruServe`. Add that module, or use `NaruServe-kotlin` or `NaruServe-android`, which include it. On Android, `NaruServe-android` selects the Logcat logger.

```groovy
implementation 'com.snoworca.naruserve:naruserve:0.11.0'
```

Use the builder to select implementations yourself, including when depending only on `NaruServe-api`. Supply `new GsonJsonCodec()` as the codec to use Gson. `NaruServe` includes this type; when assembling without that module, add `com.snoworca.naruserve:naruserve-gson` yourself. See [Gson configuration](json.md#default-gson-configuration).

```java
RestApp app = RestApp.builder()
    .httpAdapter(myAdapter)   // HttpAdapter implementation
    .jsonCodec(myCodec)       // JsonCodec implementation
    .build();
```

`httpAdapter(HttpAdapter)` and `jsonCodec(JsonCodec)` are required; `build()` throws `IllegalStateException` if either is missing. `logger(Logger)` is optional and defaults to a new `ConsoleLogger`. Setters return the builder. Every `build()` creates a new app in `NEW` state. The name follows the usual Java and Kotlin builder convention and makes explicit assembly easy to find alongside `create()`.

`create()` discovers one `com.snoworca.naruserve.spi.AppFactory` with `ServiceLoader`. The bootstrap module provides the factory and engine dependencies. No provider produces the following `IllegalStateException`; multiple providers produce an `IllegalStateException` naming their classes.

```text
No com.snoworca.naruserve.spi.AppFactory provider found. Add the com.snoworca.naruserve:naruserve module to use RestApp.create(), or assemble the app with RestApp.builder().httpAdapter(...).jsonCodec(...).build()
```

The factory SPI lets the API expose `create()` without compiling against TinyHttpd or Gson:

```java
public interface AppFactory {   // com.snoworca.naruserve.spi
    RestApp create();
}
```

One `RestApp` represents one server. Multiple apps in a process do not share framework state.

## Method summary

### Routes

| Method | Purpose |
|---|---|
| `get(path, handler)` | GET route, with automatic HEAD handling |
| `post(path, handler)` | POST route |
| `put(path, handler)` | PUT route |
| `patch(path, handler)` | PATCH route |
| `delete(path, handler)` | DELETE route |
| `head(path, handler)` | Explicit HEAD; separately registered Routes still follow order |
| `options(path, handler)` | Explicit OPTIONS handler, before automatic OPTIONS |
| `get(path, middleware, handler)` and peers | One route-specific middleware |
| `route(Method, path, handler)` | One method |
| `route(MethodSet, path, handler)` | Several methods with one handler |
| `route(path)` | `RouteBuilder` for `.get(h).delete(h2)` |
| `ws(path, handler)` | [WebSocket endpoint](websocket.md) |

Route methods, including middleware overloads and `ws`, return `RestApp`. Only `route(path)` returns `RouteBuilder`. Chain several route middleware with `route(path).use(m1).use(m2).get(handler)`. See [routing](routing.md) and [registration](middleware.md#registration).

`RouteHandler` is a functional interface. Its checked exceptions enter [error handling](middleware.md#error-handling):

```java
@FunctionalInterface
public interface RouteHandler {
    void handle(Request req, Response res) throws Exception;
}
```

### Middleware and errors

| Method | Purpose |
|---|---|
| `use(middleware)` | Every request. A middleware that is an `Installable` (such as auth) is not added as middleware: `middleware.install(app)` runs instead and registers what it needs |
| `use(prefix, middleware)` | Prefix itself and descendants: `/api` and `/api/users`, excluding `/apix` |
| `onError(handler)` | Error callback `(error, req, res) -> ...` |

See [middleware and errors](middleware.md).

`Installable` is an interface with one method, `void install(RestApp app)`. When `x` is a `Middleware` and an `Installable`, `app.use(x)` calls `x.install(app)` once and returns the app, leaving no registration record of its own (only what `install` registers). A path use `use(prefix, x)` with any path other than `""` or `"/"` throws `IllegalArgumentException`, because an `Installable` attaches itself to the whole app; `use("/", x)` installs it like `use(x)` (the Express habit `app.use("/", mw)`); `use("", x)` and a router's `use(x)` add it as plain middleware. Java and Kotlin both write it as `app.use(auth)` (the same as `auth.install(app)`, see [auth](auth.md#install-order)). Express counterpart: a package whose `app.use(pkg)` also adds its own routes.

### Configuration

| Method | Purpose | Reference |
|---|---|---|
| `jsonCodec(codec)` | Replace the JSON engine | [JSON](json.md) |
| `jsonLimit(bytes)` | JSON body limit; default 1 MiB, maximum 1 GiB | [Limits](json.md#limits) |
| `formLimit(bytes)` | Urlencoded body and aggregate multipart text limit; default 1 MiB, maximum 1 GiB | [Forms](request.md#forms-and-files) |
| `cors(options -> ...)` | HTTP allowed origins and preflight | [CORS](middleware.md#http-cors) |
| `group(prefix, configurer)` | Routes sharing a prefix and middleware | [Groups](middleware.md#route-groups) |
| `server(options -> ...)` | Bind address, allowed hosts, size, time and connection limits | [Server options](#server-options) |
| `trustProxy(boolean)` | Trust X-Forwarded-For/Proto; default false. Uses the left-most value, which can be forged | [Server options](#server-options) |
| `trustProxyHops(n)` | Use the nth `X-Forwarded-For` entry from the right as the client address (hop mode) | [Proxy trust](#proxy-trust) |
| `trustProxyCidrs(cidr...)` | Trust only proxies inside trusted CIDRs and use the first address from the right outside them (CIDR mode) | [Proxy trust](#proxy-trust) |
| `onRegistration(listener)` | Registration watcher called before each later registration takes effect | [Registration list and watcher](#registration-list-and-watcher) |
| `registrations()` | The registrations so far (read-only, readable in every state) | [Registration list and watcher](#registration-list-and-watcher) |
| `multipart(config -> ...)` | Upload limits and temporary directory | [Uploads](multipart.md) |
| `webSocket(options -> ...)` | WebSocket origins, message/queue limits, ping and idle timeout | [WebSocket](websocket.md) |
| `sse(options -> ...)` | Heartbeat and send queue options; send reconnect delays per event with `SseEvent.retry(millis)` | [SSE](sse.md) |
| `staticFiles(prefix, dir[, options])` | Mount a static directory | [Static files](static.md) |
| `mimeTypes()` | App-wide MIME registry | [MIME](static.md#mime) |
| `defaultCache(policy)` | Policy for responses without Cache-Control; null omits it | [Default policy](static.md#default-server-policy) |
| `accessLog(options -> ...)` | Access logging | [Logging](logging.md) |
| `logger(logger)` / `logger()` | Replace / retrieve the app logger | [Logging](logging.md) |
| `routerPolicy(RouterPolicy)` | Explicit string slash/case policy; retained WS TRACE/CONNECT gates | [Router policy](routing.md#router-policy) |

### Server options

`server(configurer)` executes a `Configurer<ServerOptions>` immediately and returns `RestApp`. Calls apply in order, and option setters return `ServerOptions`. This gathers the settings that Express spreads across `app.listen(port, host)` and `http.Server` properties such as `headersTimeout`.

`server` and `accessLog` mutate the app's options directly: changes made before a callback throws remain applied. In contrast, `multipart`, `webSocket` and `sse` apply a copy only after a successful callback. Validate values before setting them.

| Option | Default | Range |
|---|---|---|
| `bindAddress(address)` | `127.0.0.1` | Non-loopback binding logs a security warning at listen |
| `allowedHosts(names...)` | None | Additional Host names beyond the bind address, localhost and IP literals |
| `maxHeaderSize(bytes)` | 16384 | 1..1048576 |
| `maxHeaderCount(count)` | 100 | 1..10000 |
| `maxRequestLineLength(bytes)` | 8190 | 1..65534 |
| `headerTimeout(timeout, unit)` | 10 s | 1 ms..1 h |
| `bodyReadTimeout(timeout, unit)` | 30 s | 1 ms..1 h |
| `writeTimeout(timeout, unit)` | 30 s | 1 ms..1 h |
| `minBodyRate(bytesPerSecond)` | 240 | 1..104857600 |
| `maxWorkers(workers)` | 64 | 1..1024 |
| `maxSseStreams(streams)` | 128 | 1..1024 |
| `maxConnections(connections)` | 512 | 1..65536 |
| `maxUpgradedConnections(connections)` | 128 | 1..65536 |
| `maxConnectionsPerAddress(connections)` | 256 | 1..65536 |

`maxWorkers` reserves capacity for ordinary HTTP. The engine pool has `maxWorkers + maxSseStreams` threads, and SSE uses at most `maxSseStreams`. With no SSE streams, ordinary requests can therefore use up to 192 threads by default. Reduce both settings to reduce the pool.

Out-of-range options throw `IllegalArgumentException` naming the setting and range. No unlimited value exists. `listen()` checks combinations and throws `IllegalStateException` if `maxConnections < maxWorkers + maxSseStreams`, so setters may be called in any order. See [defaults and security](defaults.md) for limit responses.

For LAN access, bind all interfaces and allow a hostname if clients use one:

```java
app.server(options -> options.bindAddress("0.0.0.0").allowedHosts("myphone.local"));
app.server(options -> options.headerTimeout(5, TimeUnit.SECONDS).maxWorkers(16));
```

Kotlin:

```kotlin
app.server { options -> options.bindAddress("0.0.0.0").allowedHosts("myphone.local") }
app.server { options -> options.headerTimeout(5, TimeUnit.SECONDS).maxWorkers(16) }
```

Non-loopback binding logs a warning regardless of authentication. Add authentication through [middleware](middleware.md).

Behind a proxy, `trustProxy(true)` replaces `remoteAddress()` with the trimmed leftmost entry of the first X-Forwarded-For header, and `isSecure()` with the first X-Forwarded-Proto entry (`https`, case-insensitive, means true). Missing or blank entries keep transport values. Default false; `Forwarded` is never read. Enable this only behind a proxy that overwrites these unvalidated headers, as with Express's `app.set('trust proxy', true)`.

The left-most value that `trustProxy(true)` uses is unverified and client-controlled, so anyone can claim any address. It is kept for compatibility with 0.10.0 apps, and the auth module's doctor reports it as FAIL in `PROD` (R32). Behind a proxy use [`trustProxyHops` or `trustProxyCidrs`](#proxy-trust).

```java
app.trustProxy(true);
```

Kotlin:

```kotlin
app.trustProxy(true)
```

### Proxy trust

To get the client address safely behind a proxy, use one of the two modes that count from the right. Both may be called only in `NEW`, and the last of `trustProxy`, `trustProxyHops` and `trustProxyCidrs` replaces the earlier setting. `req.socketAddress()` stays the real connection address under every setting ([Request](request.md#request-information)). WebSocket upgrade requests use the same setting as ordinary requests.

| Express | NaruServe | Meaning |
|---|---|---|
| `app.set('trust proxy', 1)` | `app.trustProxyHops(1)` | trust the socket peer (the load balancer); the first `X-Forwarded-For` entry from the right is the client |
| `app.set('trust proxy', 2)` | `app.trustProxyHops(2)` | two proxy hops |
| `app.set('trust proxy', 'loopback, 10.0.0.0/8')` | `app.trustProxyCidrs("127.0.0.0/8", "::1", "10.0.0.0/8")` | trust only a socket peer inside a trusted CIDR; the first entry from the right outside every trusted CIDR is the client |

- **Hop mode** `trustProxyHops(n)`: with `n` of 1 or more the socket peer is trusted and `req.remoteAddress()` is the `n`th entry from the right; with fewer than `n` entries it is the left-most entry (as Express). `0` trusts nothing and keeps the adapter values; a negative count throws `IllegalArgumentException`. Use it only when the node is reachable only through the load balancer: a client that connects directly can forge both headers.
- **CIDR mode** `trustProxyCidrs(cidr...)`: the socket peer is trusted only inside a trusted CIDR, and `req.remoteAddress()` is the first entry from the right outside every trusted CIDR; the left-most entry when all are inside, and the socket peer when there is no `X-Forwarded-For`. An untrusted peer's forwarding headers are all ignored. This is the mode to use for multi-node deployments that cannot prevent connections bypassing the load balancer.
- A CIDR is an IPv4 or IPv6 literal with an optional `/prefix` (`10.0.0.0/8`, `fd00::/8`); without a prefix it is that one address. A `null` array or element throws `NullPointerException`; any other value (`10.0.0.0/33`, `abc/8`, a host name) throws `IllegalArgumentException` naming it, and no name is looked up in DNS. An empty array trusts nothing. An IPv4-mapped IPv6 address such as `::ffff:10.0.0.1` is compared with IPv4 CIDRs.
- Both modes join the `X-Forwarded-For` header lines in arrival order and skip blank entries. An entry that is not an IP is treated as outside every trusted CIDR and returned as is. The `Forwarded` header is never read.
- `X-Forwarded-Proto` is read only when the socket peer is trusted. One value is used as is (`https` case-insensitive); several values are matched by the position, counted from the right, of the `X-Forwarded-For` entry chosen as the client. With two or more values whose count differs from `X-Forwarded-For`, or a missing position, `isSecure()` is `false`. A trusted value replaces the adapter's TLS flag both ways (`http` gives `false` even on TLS); without the header the adapter value stays.

Have nginx pass both headers like this. A proxy that passes `X-Forwarded-Proto` through without overwriting it is an unsupported setup, because its value cannot be told apart from a client's.

```nginx
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
```

Read the current setting with `trustProxy()` (`boolean`), `trustProxyHops()` (`int`, `0` outside hop mode) and `trustProxyCidrs()` (an unmodifiable `List<String>`, empty outside CIDR mode). None is `null`, and all can be read after `listen`. With no trust at all they read `false`, `0` and `[]`.

### Router policy

`routerPolicy(RouterPolicy)` returns `RestApp`. Without an explicit policy, common HTTP is case-insensitive and non-strict, with meaningful repeated slashes. An explicit policy applies its own case/trailing/slash settings, including all-false strict values. The retained strict router/WS method gates remain separate. See [router policy](routing.md#router-policy).

```java
app.routerPolicy(RouterPolicy.builder().ignoreTrailingSlash(true).build())
   .get("/users", (req, res) -> res.send("users"));   // also matches /users/
```

Kotlin:

```kotlin
app.routerPolicy(RouterPolicy.builder().ignoreTrailingSlash(true).build())
    .get("/users") { req, res -> res.send("users") }
```

### Lifecycle

| Method | Purpose |
|---|---|
| `onStart(ctx -> ...)` | Runs once before requests; create and register services |
| `onStop(ctx -> ...)` | Runs once during normal shutdown, while services are still available |
| `listen(port)` | Start and return a ServerHandle |
| `RestApp.start(port, routes)` | Create, configure, listen, log and install a JVM shutdown hook; return a ServerHandle |
| `shutdown(timeout, unit)` | Graceful shutdown |
| `state()` | NEW, STARTING, RUNNING, STOPPING, FAILED or TERMINATED |

`LifecycleCallback` has `void handle(ApplicationContext ctx) throws Exception`; `ErrorHandler` has `void handle(Throwable error, Request req, Response res) throws Exception`. `WebSocketHandler` has `void handle(Request req, WebSocketSession ws) throws Exception`. All are functional interfaces, allowing I/O exceptions without wrappers, like RouteHandler and Middleware. See [lifecycle and services](lifecycle.md).

## listen

```java
ServerHandle server = app.listen(8080);
```

- Startup is synchronous: onStart, route validation and binding finish before return. Unlike Express, it accepts no callback. Requests are not dispatched to application handlers until startup completes; connections accepted during startup wait for that transition.
- After return, the process remains alive even after main ends. The TinyHttpd adapter holds one non-daemon keeper thread until shutdown; the engine's other threads remain daemon threads. This gives the usual standalone-server behavior without making worker threads prevent JVM exit.
- Binding failure, onStart exceptions and invalid configuration throw exceptions, with no open port and cleanup of resources already created. Common HTTP duplicate/overlapping routes are valid ordered registrations; retained WS conflicts still fail startup.
- The default bind address is **127.0.0.1 only**. Set `bindAddress("0.0.0.0")` for LAN access.
- `ServerHandle.port()` returns the actual port, including a selected ephemeral port for `listen(0)`. `address()` returns the configured bind address; `state()` reads the current app state. These short names follow other accessors such as req.path(). `url()` returns a browsable address, substitutes localhost for wildcard binds, and brackets IPv6. `shutdown(timeout, unit)` follows app shutdown: repeated calls are safe, negative timeout throws IllegalArgumentException, and null unit throws NullPointerException.
- On Android, call from a worker thread.

## shutdown

```java
app.shutdown(10, TimeUnit.SECONDS);
```

1. Enter STOPPING; new requests and upgrades reaching the app receive 503 and Connection: close.
2. While still accepting connections, spend at most one second notifying SSE clients and sending WebSocket close 1001.
3. Stop accepting and wait for in-flight requests.
4. Abort remaining connections when the timeout expires.
5. Run onStop callbacks.
6. Close owned or registered AutoCloseable objects in reverse creation/registration order.
7. Flush and close the logger, then release the HTTP adapter. See [logging](logging.md).

Repeated shutdown is safe; a terminated app cannot listen again. On Android, call from a worker thread. Steps 2–4 share one timeout; goodbye uses at most one second or the remaining time. onStop, resource closing and logger flush have no time limit and can extend the total duration. See [lifecycle](lifecycle.md) for the rationale and the three-second transport return allowance.

## Registration and configuration lifecycle

Common HTTP route/middleware/Router registration and existing RouteBuilder callbacks may be appended while RUNNING. Their position/order remains stable; a terminated app rejects them. Configuration such as server, codecs, multipart, typed CORS, groups, onError, static mounts and WS registrations still requires NEW. Validate a complete alternative/callback tree before publication. Common HTTP duplicate paths are not startup conflicts; direct legacy/WS rules remain strict.

## Example

```java
RestApp app = RestApp.create();

app.onStart(ctx -> {
    Database db = new Database();
    ctx.own(db);            // register ownership before initialization, so failures also close it
    db.connect();
    ctx.register(UserService.class, new UserService(db));
});

app.use((req, res, next) -> {
    req.attribute("requestId", UUID.randomUUID().toString());
    next.run();
});

app.get("/api/users/:id", (req, res) -> {
    res.json(req.service(UserService.class).findById(req.param("id")));
});

app.staticFiles("/", new File("./www"));

ServerHandle server = app.listen(8080);
Runtime.getRuntime().addShutdownHook(new Thread(() -> app.shutdown(10, TimeUnit.SECONDS)));
```

## start

Like Express's `app.listen(3000, () => ...)`, this static entry point starts a server in one statement. Register routes in a lambda instead of holding a separate app variable.

```java
ServerHandle handle = RestApp.start(3000, app -> {
    app.get("/hello", (req, res) -> res.send("Hello World"));
});
System.out.println(handle.url());
```

Kotlin:

```kotlin
val handle = RestApp.start(3000) { app ->
    app.get("/hello") { _, res -> res.send("Hello World") }
}
println(handle.url())
```

`start(int port, Configurer<RestApp> routes)` returns a ServerHandle after:

1. Calling create; a missing bootstrap module produces create's IllegalStateException.
2. Executing routes once in NEW state. Register get, use, server, onStart, onStop and trustProxy here. Kotlin lambda return values are ignored.
3. Calling listen(port).
4. Logging one start line, for example `Listening on http://127.0.0.1:3000 (this device only). Press Ctrl+C to stop.` Port zero prints the actual port. Wildcard binds show localhost and guidance for opening the device IP.
5. Installing a JVM shutdown hook. Ctrl+C/SIGTERM logs `Stopping (grace 5s)` and calls shutdown(5, SECONDS), including onStop. Explicit app/handle shutdown removes the hook. Failed startup installs no hook; hook-removal failure during JVM shutdown is ignored. Android gets no hook and no Ctrl+C message.

Use the handle's url and shutdown methods when start creates the app. Startup returns after binding; it does not block for the server lifetime. The keeper thread explains why main can return without exiting. If configuration or listen throws, start cleans the app with shutdown and rethrows the original exception.

| Behavior | `start(port, routes)` | `create()` + `listen(port)` |
|---|---|---|
| Configuration | In routes while NEW | Any code before listen |
| Start log | One Listening line | None |
| JVM shutdown hook | Automatic except on Android; runs onStop | Register Runtime.addShutdownHook yourself |
| Wait for server lifetime | No | No |
| Default bind | 127.0.0.1 | 127.0.0.1 |
| LAN | server configuration inside routes | server configuration before listen |
| Exceptions | Propagates listen errors | Same |

There is no default port, PORT environment lookup or bind-address argument. Express listens on all interfaces by default; NaruServe keeps loopback unless you explicitly configure otherwise. Non-loopback binding retains the same warning.

On Android, start includes synchronous binding and onStart work, so call it on a worker thread and call handle.shutdown from your Service lifecycle. Use create/listen for tests or embedding when you do not want an automatic JVM hook.

## Common HTTP registration surface

`RestApp` and independent `com.snoworca.naruserve.Router` share `HttpRoutes<T>`. `route(Object)` accepts strings, Java Pattern, `RegexPathPattern` and nested array/list alternatives. `method(String, Object, Middleware...)` registers extended method tokens. Fixed-arity three-argument overloads support Kotlin trailing lambdas while retained two-argument `RouteHandler` calls remain usable.

`use(path, router)` mounts a live independent Router; `param(name, ParamHandler)` preprocesses captures; four-argument `ErrorMiddleware` participates at its registration position. `getAsync`/`methodAsync`/`useAsync` take three-argument `AsyncRouteHandler` returning Deferred. Resolving does not implicitly next/end, and a returned pending result retains request resources after an early response. See [routing](routing.md), [request views](request.md#mounts-and-path-views) and [Deferred](middleware.md#deferred-and-late-work).

`send("text")` stays text/plain, JSON request bodies stay typed/on-demand, and static/WS/Host/framing security remains enforced. This common HTTP profile does not claim every Express or third-party middleware API. Hand-written RestApp implementations must account for the expanded registration interface; default Request/Next/exchange hooks do not add mandatory abstract methods.

## Registration list and watcher

`registrations()` is a read-only `List<Registration>` of every registration so far, in registration order. It resembles Express's `app._router.stack`, but each layer carries its kind. It is a call-time snapshot: iterating it never throws `ConcurrentModificationException` while other threads register after `listen`, and later registrations appear only in a new call. It is readable in every state; `add` throws `UnsupportedOperationException`.

`onRegistration(listener)` adds a registration watcher; Express has no counterpart. It may be called only in `NEW` (`IllegalStateException` after `listen`), and `null` throws `NullPointerException`. One registration runs **validate → watcher → apply → record**. An invalid registration throws the same exception as in 0.10.0 and never reaches the watcher. The watcher runs synchronously on the registering thread before the registration takes effect: the registration is not yet in the list and no request sees it. If the watcher throws, the registration is neither applied nor recorded, and the exception reaches the caller of `get`, `use` and so on unchanged; later registrations on a mounted Router behave the same. Several watchers run in the order they were added, and the first one that throws stops the rest. Registering, `listen` or `shutdown` from inside a watcher throws `IllegalStateException`. A watcher runs holding a registration lock shared by every app, so it must not wait for other threads, and calling a method of another app from inside a watcher throws `IllegalStateException` at once instead of deadlocking. When a later app's watcher refuses a registration on a Router mounted in several apps, earlier apps' watchers have already seen the record; there is no rollback call. A `TERMINATED` app no longer sees later registrations of the Routers it mounted. Registrations still accepted after `listen` (routes, `use`, Router mounts, `param`, `use(ErrorMiddleware)`) pass the watcher and are recorded; `cors`, `ws`, `staticFiles`, `group`, `onError` and `trustProxy` stay `NEW`-only.

| Kind | Made by |
|---|---|
| `ROUTE` | `get`, `post`, `put`, `patch`, `delete`, `head`, `options` (every overload), `route(Method, ...)`, `route(MethodSet, ...)`, `method(...)`, `methodAsync`, `getAsync`, and one per method of a `route(path)` builder (its `use(mw)` included) |
| `WEBSOCKET` | `ws(path, handler)` |
| `STATIC` | `staticFiles(...)`; it uses middleware inside but is not also recorded as `MIDDLEWARE` |
| `ROUTER_MOUNT` | `use(path, router)`, `use(router)` |
| `GROUP` | `group(prefix, configurer)`; recorded after the callback returned normally and before its routes |
| `CORS` | `cors(...)`; not also recorded as `MIDDLEWARE` |
| `MIDDLEWARE` | middleware whose path is `""` or `"/"`: `use(mw)`, `use("/", mw)`, `useAsync(handler)` |
| `PATH_MIDDLEWARE` | `use(path, mw)` and `useAsync(path, handler)` with any other path, a `Pattern` or an array |
| `ERROR_MIDDLEWARE` | `use(ErrorMiddleware)`, `use(path, ErrorMiddleware)`, `onError(handler)` |
| `PARAM` | `param(name, handler)` |

| Accessor | Value |
|---|---|
| `getKind()` | the kind above (`RegistrationKind`) |
| `getMethod()` | the upper-case method token of a `ROUTE` (`"GET"`, `"PURGE"`); one `route(MethodSet, ...)` call joins its tokens with commas, as `"GET,POST"`. `null` for a builder `use(mw)` and for other kinds |
| `getPath()` | the full string path with the mount paths in front (`"/api/users"`). A registration without a path (`use(mw)`, `cors`, `onError`, `param`) has `""`, or the mount path inside a mount. `null` when this path or an enclosing mount path is a `Pattern` or an array |
| `isStringPath()` | `true` when `getPath()` is not `null` |
| `getPathSource()` | the path object this call passed, as given (a string, `Pattern` or array); `""` when it passed none |
| `getTarget()` | the object the caller registered: the middleware, Router, `ErrorMiddleware`, the `onError` handler itself, the `AsyncRouteHandler` of `useAsync`, the `WebSocketHandler` of `ws`, the `param` handler, the configurer of `cors` and `group`, the directory of `staticFiles`. A route has its last callback (the handler). Never a wrapper the framework made |
| `getMount()` | the `ROUTER_MOUNT` record this record was expanded under; `null` at app level |

Registrations inside mounted Routers and groups are expanded with the mount path. `app.use("/api", r)` records `ROUTER_MOUNT /api` followed by the registrations of `r` in `r`'s order as `/api/...`; nested Routers get both prefixes. Mounting a Router that already holds registrations applies the mount only after the mount record and every expanded record passed the watchers, so if one is refused, none of that Router's routes opens. A registration added to a Router after it was mounted (also after `listen`) is appended to the end of the list and points through `getMount()` at the Router's mount record. A Router mounted twice is expanded once per mount. Mounting a Router inside itself throws `IllegalArgumentException`. The `use(mw)` of a `route(path)` builder is a builder method too and is recorded as one `ROUTE` whose `getMethod()` is `null` (it runs for every method of that route).

```java
app.onRegistration(r -> {
    if (r.getKind() == RegistrationKind.ROUTE && !r.isStringPath()) {
        throw new IllegalStateException("regex routes are not allowed: " + r.getPathSource());
    }
});
app.get("/users", h);
for (Registration r : app.registrations()) {
    System.out.println(r.getKind() + " " + r.getMethod() + " " + r.getPath());
}
```

Kotlin:

```kotlin
app.onRegistration { r ->
    if (r.kind == RegistrationKind.ROUTE && !r.isStringPath) {
        throw IllegalStateException("regex routes are not allowed: ${r.pathSource}")
    }
}
app.get("/users") { _, res -> res.send("users") }
for (r in app.registrations()) println("${r.kind} ${r.method} ${r.path}")
```
