## 4. App assembly, middleware, lifecycle, logging

Covers issues #5, #6 and #7; acceptance tests T35 (dispatcher part), T36, T37, T38 (HTTP part), T39, T40, T41, T42, T43, T45, T46. Depends on sections: 1, 2, 3.

### RestApp assembly

- [D-110] `RestApp.create()` SHALL be a static interface method that returns an app assembled by the bootstrap module (section 5).
- [D-111] `NaruServe-api` SHALL offer an explicit initialisation entry point that accepts an `HttpAdapter` and a `JsonCodec`, with its name and methods decided in #5.
- [D-112] Wave 4 SHALL create the `JsonCodec` SPI interface in `NaruServe-api` with the signatures of D-182, so the explicit entry point compiles, while wave 5 adds `TypeRef`, the JSON request and response methods and the Gson implementation.
- [D-182] `JsonCodec` SHALL declare `<T> T decode(Reader reader, Type type) throws IOException` and `void encode(Object value, Writer writer) throws IOException`, with no Gson type in its signatures.
- [D-113] Each `RestApp` instance SHALL own its configuration, registry and context, with no static state shared between instances.
- [D-114] Every registration and configuration method of `RestApp` (routes, `use`, `accessLog`, `logger(l)`, `onStart`, `onStop`, `onError`, and `ws`, `staticFiles`, `multipart` and `jsonCodec` once later waves add them) SHALL throw an `IllegalStateException` when the app state is not `NEW`, through one state guard that wave 4 provides and later waves call.

### Middleware chain

- [D-115] `Middleware` SHALL be a `@FunctionalInterface` with `void handle(Request req, Response res, Next next) throws Exception`.
- [D-116] `Next` SHALL provide `run()` to continue the chain and `error(Throwable)` to pass an error to error handling.
- [D-117] `app.use(mw)` SHALL apply the middleware to every request that reaches the dispatcher, including requests that end in `404` or `405`, with the static-file and WebSocket-upgrade cases asserted in waves 6 and 7 (T35).
- [D-118] `app.use(prefix, mw)` SHALL apply the middleware to the prefix path and paths below it on a segment boundary, so `/api` covers `/api` and `/api/users` but not `/apix` (T35).
- [D-119] Each route registration method SHALL have an overload that takes one route middleware before the handler, such as `app.get(path, mw, handler)` (T35).
- [D-120] The pipeline SHALL run all global middleware in registration order, then all matching prefix middleware in registration order, then route middleware in registration order, then the handler (T35).
- [D-121] A request with no explicit route SHALL reach the dispatcher's fallback point after global and prefix middleware ran, which answers `404` until wave 6 adds static mounts in front of it.
- [D-122] A second `next.run()` call within one middleware invocation SHALL throw an exception (T35).
- [D-123] Calling `next.run()` after the response completed or after the request ended SHALL throw an exception.
- [D-124] `next.run()` SHALL return only after every downstream middleware and the handler returned.
- [D-125] A middleware that stops the chain by responding without calling `next.run()` SHALL have its response delivered with no later middleware or handler run (T36).
- [D-126] When the chain returns with no response and no `next.run()`, the framework SHALL log a developer warning through the app logger and finish the request without waiting for a timeout, with the finishing behaviour decided in #5.
- [D-127] An exception thrown from a middleware or handler, and an error passed to `next.error(e)`, SHALL reach the same error handling path (T36).
- [D-128] Middleware execution in v1 SHALL be synchronous, with no API for continuing the chain from another thread.

### Error handling and standard error JSON

- [D-129] `app.onError((error, req, res) -> ...)` SHALL receive every unhandled exception of a request that reached the dispatcher, including framework errors for `400`, `413` and `415`.
- [D-130] Framework-raised errors SHALL expose their HTTP status to `onError` through a mechanism decided in #5.
- [D-131] When the response is already committed, the framework MUST NOT write an error body and aborts the connection instead (T37).
- [D-132] Without a registered `onError`, an error of a request that reached the dispatcher SHALL be answered with the body `{"error":{"code":...,"message":...,"requestId":...}}` and `Content-Type: application/json; charset=UTF-8`.
- [D-133] Error responses MUST NOT contain stack traces or exception class names.
- [D-134] The default status mapping SHALL be: parse failure `400`, validation failure `400` or an explicit `422`, unmatched path `404`, unmatched method `405`, size limit `413`, unsupported media type `415`, request arriving during `STOPPING` `503`, any other exception `500`, with transport-level `503` rejections left to D-400.
- [D-135] The `requestId` field of a standard error body SHALL hold the same request identifier that the access log records for that request.
- [D-136] An exception during `res.json` serialisation SHALL turn into an error response only while the response is uncommitted, asserted in wave 5 once `res.json` exists.
- [D-137] Wave 4 SHALL decide the (미확정) items owned by #5 (entry point name and methods, multi-middleware overloads, `RouteBuilder.use(middleware)`, `onError` chaining, `code` values per status, custom `404`/`405` bodies, callback interface names and `throws Exception`) and update `docs/api/middleware.md`, `docs/api/app.md` and `docs/api/express-migration.md`.

### ServiceRegistry and ApplicationContext

- [D-138] `ServiceRegistry` SHALL provide `register(Class<T>, T)`, `get(Class<T>)`, `getOrNull(Class<T>)` and `contains(Class<?>)`, with `get` throwing `ServiceNotFoundException` for a missing type.
- [D-139] `ctx.register` SHALL succeed only inside `onStart` callbacks while the app is `STARTING`, and throws otherwise (T42).
- [D-140] Registering the same `Class` key twice SHALL fail with a configuration error without replacing the first instance (T42).
- [D-141] The registry SHALL be frozen into an immutable snapshot before the app enters `RUNNING`, so request threads read it without locking.
- [D-142] `req.service(T)` SHALL return the same instance as `req.application().get(T)`, which is the instance registered in `onStart`, for HTTP, WebSocket and SSE handlers alike (T41).
- [D-143] Each `onStart` callback SHALL run exactly once per successful `listen()` (T41).
- [D-144] `ApplicationContext.attribute` SHALL be readable and writable in every state from `STARTING` to `STOPPING` and safe for concurrent access.
- [D-145] `ctx.logger()` SHALL return the same `Logger` as `app.logger()`.
- [D-146] There SHALL be exactly one `ApplicationContext` per `RestApp`.
- [D-147] `ctx.own(resource)` SHALL make the framework close the resource on shutdown and on start failure.
- [D-148] An object owned or registered several times, including under an interface key and an implementation key, SHALL be closed exactly once, compared by identity (T45).
- [D-149] Owned and registered `AutoCloseable` objects SHALL be closed in reverse order of their first `own` or `register` call (T43, T45).
- [D-150] An exception from one `close()` SHALL be collected while the remaining resources are still closed.
- [D-151] Wave 4 SHALL decide how an externally owned object is registered without automatic close (`registerExternal` or `Ownership.EXTERNAL` are the candidates) and update `docs/api/lifecycle.md`.

### Lifecycle state machine

- [D-152] `app.state()` SHALL return one of `NEW`, `STARTING`, `RUNNING`, `STOPPING`, `FAILED`, `TERMINATED`.
- [D-153] `listen()` SHALL move the app from `NEW` to `STARTING` atomically, and a second or concurrent `listen()` throws an exception.
- [D-154] `listen()` SHALL run `onStart` callbacks in registration order, then freeze and validate router, registry and options, then bind the socket and start accepting through the adapter in one call, then enter `RUNNING`, with requests accepted before `RUNNING` held by the start gate of D-428.
- [D-155] `listen()` SHALL return only after binding succeeded, giving a `ServerHandle` that reports the actual port, address and state through accessors named in #6.
- [D-156] No request SHALL be dispatched to the pipeline before the app is `RUNNING`.
- [D-428] The dispatcher SHALL hold every request that reaches it before the app is `RUNNING` on a start gate, releasing it into the pipeline once `RUNNING` is set and answering it with `503` and `Connection: close` when the start fails or a pending stop of D-426 ends it, because TinyHttpd's `start()` binds and starts its acceptor in one call with no hook in between.
- [D-157] When `onStart`, validation or binding fails, no listening socket SHALL remain open after `listen()` throws (T43).
- [D-158] On start failure, already owned and registered resources SHALL be closed in reverse order before `listen()` throws (T43).
- [D-159] On start failure the state SHALL pass through `FAILED` to `TERMINATED`, and `listen()` throws the cause, wrapping a checked cause in an unchecked exception whose type is decided in #6.
- [D-160] `onStop` MUST NOT run for an app that never reached `RUNNING`.
- [D-161] The first `shutdown(timeout, unit)` call SHALL move a `RUNNING` app to `STOPPING` atomically, and later calls return without side effects (T46).
- [D-162] Shutdown SHALL compute one deadline from the timeout and run in order: the before-wait participants, the adapter stop of D-107 up to that deadline (stop accepting, wait for in-flight requests, force-close what remains), the force-close participants, `onStop` once while services are still registered, the after-`onStop` participants, closing owned resources in reverse order, flushing and closing log writers, the adapter release of D-107 and the move to `TERMINATED`.
- [D-401] Wave 4 SHALL provide an internal shutdown participant registry with the fixed phases of the D-162 sequence (before-wait participants running before the adapter stop, force-close participants running after the adapter stop returned, and after-`onStop` participants), into which wave 6 adds temporary-file cleanup (D-255) as an after-`onStop` participant, so spool files of uploads still in flight during the adapter stop stay intact, and wave 7 adds WebSocket closing (D-435) and SSE closing (D-436) as before-wait participants, without editing the sequence.
- [D-163] `onStop` callbacks SHALL be able to read every registered service (T45).
- [D-164] `shutdown` on an app in `NEW` SHALL move it to `TERMINATED` without running `onStart` or `onStop`.
- [D-426] A `shutdown` call while the app is `STARTING` SHALL record a pending stop and wait up to its timeout for `listen()` to end, and `listen()` then finishes the step in progress, closes any bound socket, never enters `RUNNING`, never runs `onStop`, closes owned and registered resources in reverse order, passes through `FAILED` to `TERMINATED` as D-159 and the object.md §11.3 failure path require and throws an exception whose type #6 decides, as a test that runs `listen()` with an `onStart` blocked on a latch and calls `shutdown` from another thread asserts (T46).
- [D-427] A `shutdown` call on an app in `FAILED` SHALL wait up to its timeout until the failing `listen()` reached `TERMINATED` and return, while a call on a `TERMINATED` app returns at once with no side effects.
- [D-438] When the timeout of a `shutdown` call made during `STARTING` passes before `listen()` ended, for example with `onStart` still blocked, `shutdown` SHALL return normally without an exception, leaving `state()` at `STARTING` or `FAILED` and the pending stop of D-426 in force, so the blocked `listen()` still passes through `FAILED` to `TERMINATED` and throws once `onStart` returns, with `docs/api/lifecycle.md` recording this result and the T46 test of D-426 asserting it with a `shutdown` timeout shorter than the latch release.
- [D-165] `listen()` on a `TERMINATED` app SHALL throw an exception (T46).
- [D-166] A request that reaches the dispatcher after `STOPPING` began SHALL receive `503` with `Connection: close`, while once the adapter stop of D-162 has begun TinyHttpd closes an idle keep-alive connection without processing a request that races the close and commits in-flight responses with `Connection: close`, so the T44 test of this item accepts for a request sent on a keep-alive connection after `STOPPING` began either a `503` with `Connection: close` or a connection closed without a response.
- [D-167] Wave 4 SHALL decide whether server threads keep the JVM alive after `main` returns and document it in `docs/api/app.md`.

### Logging SPI and access log

- [D-168] `Logger` SHALL provide `trace`, `debug`, `info`, `warn`, `error(String)` and `error(String, Throwable)`.
- [D-169] `NaruServe-api` SHALL ship a console `Logger` that is the default of the explicit entry point and of `RestApp.create()` on the JVM.
- [D-403] The Logcat `Logger` SHALL map each level to the matching `android.util.Log` method looked up by reflection, verified by a JVM test against a test-scope fake `android.util.Log` class, with the log tag decided in #7.
- [D-170] `app.logger(l)` SHALL replace the app logger, and `app.logger()` returns the current one.
- [D-171] An exception thrown by a `Logger` or log writer MUST NOT interrupt request processing or server operation.
- [D-172] `app.accessLog(options -> ...)` SHALL support `enabled`, `level`, `includeRequestId`, `includeDuration`, `includeBytes` and `exclude(path)`.
- [D-173] The access log SHALL write exactly one line per HTTP request that reached the dispatcher, when the response completes or is aborted, holding method, path, status, and, when enabled, latency, bytes and request id (T38).
- [D-174] Requests to a path passed to `exclude` SHALL produce no access log line.
- [D-175] The access log MUST NOT record `Authorization` or `Cookie` header values, passwords, tokens, request or response bodies, multipart content, or the query string (T39).
- [D-176] CR, LF and other control characters in logged values SHALL be escaped (T39).
- [D-177] Access log byte fields SHALL report body bytes actually read from and written to the connection, as documented in `docs/api/logging.md`.
- [D-178] `NaruServe-api` MUST NOT depend on SLF4J or `android.*` (T40).
- [D-179] The SLF4J adapter and the rotating file logger SHALL live in the optional modules that wave 1 declares outside `NaruServe-api` (T40).
- [D-180] The rotating file logger SHALL use a bounded queue with a drop counter and default to a `./logs` directory, 5 MiB per file and 5 files.
- [D-181] Wave 4 SHALL decide the access log line format, the option defaults and the Logcat tag, and update `docs/api/logging.md`.

