# Wave 4 design — 4. App assembly, middleware, lifecycle, logging

Source: frozen run design `docs/research/naruserve-v1/design/00.design.md` lines 213–313 (design lock `docs/research/naruserve-v1/design/00.design.lock.json`). GitHub issues: #5, #6, #7. Depends on waves: wave-1, wave-2, wave-3.

The items below are the frozen design items of this wave, verbatim and with their run ids; this wave implements every one of them. Integration items I-001..I-021 of the run design also hold at this wave's merge.


Covers issues #5, #6 and #7; acceptance tests T35 (dispatcher part), T36, T37, T38 (HTTP part), T39, T40, T41, T42, T43, T45, T46. Depends on sections: 1, 2, 3.

#### RestApp assembly

- [D-110] `RestApp.create()` SHALL be a static interface method that returns an app assembled by the bootstrap module (section 5).
- [D-111] `NaruServe-api` SHALL offer an explicit initialisation entry point that accepts an `HttpAdapter` and a `JsonCodec`, with its name and methods decided in #5.
- [D-112] Wave 4 SHALL create the `JsonCodec` SPI interface in `NaruServe-api` with the signatures of D-182, so the explicit entry point compiles, while wave 5 adds `TypeRef`, the JSON request and response methods and the Gson implementation.
- [D-182] `JsonCodec` SHALL declare `<T> T decode(Reader reader, Type type) throws IOException` and `void encode(Object value, Writer writer) throws IOException`, with no Gson type in its signatures.
- [D-113] Each `RestApp` instance SHALL own its configuration, registry and context, with no static state shared between instances.
- [D-114] Every registration and configuration method of `RestApp` (routes, `use`, `accessLog`, `logger(l)`, `onStart`, `onStop`, `onError`, and `ws`, `staticFiles`, `multipart` and `jsonCodec` once later waves add them) SHALL throw an `IllegalStateException` when the app state is not `NEW`, through one state guard that wave 4 provides and later waves call.

#### Middleware chain

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

#### Error handling and standard error JSON

- [D-129] `app.onError((error, req, res) -> ...)` SHALL receive every unhandled exception of a request that reached the dispatcher, including framework errors for `400`, `413` and `415`.
- [D-130] Framework-raised errors SHALL expose their HTTP status to `onError` through a mechanism decided in #5.
- [D-131] When the response is already committed, the framework MUST NOT write an error body and aborts the connection instead (T37).
- [D-132] Without a registered `onError`, an error of a request that reached the dispatcher SHALL be answered with the body `{"error":{"code":...,"message":...,"requestId":...}}` and `Content-Type: application/json; charset=UTF-8`.
- [D-133] Error responses MUST NOT contain stack traces or exception class names.
- [D-134] The default status mapping SHALL be: parse failure `400`, validation failure `400` or an explicit `422`, unmatched path `404`, unmatched method `405`, size limit `413`, unsupported media type `415`, request arriving during `STOPPING` `503`, any other exception `500`, with transport-level `503` rejections left to D-400.
- [D-135] The `requestId` field of a standard error body SHALL hold the same request identifier that the access log records for that request.
- [D-136] An exception during `res.json` serialisation SHALL turn into an error response only while the response is uncommitted, asserted in wave 5 once `res.json` exists.
- [D-137] Wave 4 SHALL decide the (미확정) items owned by #5 (entry point name and methods, multi-middleware overloads, `RouteBuilder.use(middleware)`, `onError` chaining, `code` values per status, custom `404`/`405` bodies, callback interface names and `throws Exception`) and update `docs/api/middleware.md`, `docs/api/app.md` and `docs/api/express-migration.md`.

#### ServiceRegistry and ApplicationContext

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

#### Lifecycle state machine

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

#### Logging SPI and access log

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



## Carried from loop D

Loop D ended at its round cap; the committee chose fix-and-freeze. The fixes below landed in the frozen design and this wave re-checks them in loop W:

- V1-001: D-166 now limits the 503 + Connection: close guarantee to requests that reach the dispatcher; once the adapter stop begins, TinyHttpd may close idle keep-alive connections unanswered (Connection.onServerStopping/closeIdle/lingerClose, persistentAtCommit isStopping), and the T44 test accepts either outcome. (items D-166)

## Carried from earlier waves

- `listen()` runs the same route-table freeze that the wave 3 harness runs on the internal `RestApp` implementation (`DefaultRestApp`), and the public entry point of D-111 constructs that implementation; wave 3 added no public factory.
- The wave 3 rule that a registration or `routerPolicy` call after the freeze throws `IllegalStateException` becomes the `NEW`-state rule of D-114 and D-152.
- Wave 4 replaces the wave 3 interim failure handling: a handler exception gave `500` with an empty body before commit and aborted the response after commit; a handler that returned without finishing a non-streaming response had it finished by the dispatcher. D-126..D-134 behaviour replaces both.
- Wave 4 gives the error statuses (`400`, `404`, `405`, `426`, `501`, and `500`) the standard error body; in wave 3 they carry an empty body with `Content-Length: 0`.
- The pre-request rejections (unrecognised method `501`, raw path not starting with `/` `400`, path-validator rejection `400`, `OPTIONS *` `204`) are answered before a `Request` exists, so middleware and `onError` never see them; D-117 and D-129 apply to every request for which a `Request` is built, including a malformed query (`400`).
- The SPI exchange outlives `ExchangeHandler#handle` until the core calls `finish` or `abort`; the adapter may force-close only at its stop deadline (wave 3 `HttpExchange` javadoc). Shutdown (D-162 and the stop path) uses that deadline.

## Wave decisions

These choices settle behaviour that the frozen items leave open between waves and to the implementer under the standing delegation rule. They add no new requirement; each line names the frozen items it applies to. Between equally valid options the decision follows HTTP semantics (RFC 9110) and the behaviour an Express user expects.

- `RestApp.create()` (W2-001; D-110, D-169, D-226, D-227, D-228, D-111): wave 4 declares the static `RestApp.create()` and implements its runtime lookup of the bootstrap factory through `ServiceLoader` of an engine-free factory interface in `NaruServe-api`, plus the missing-module exception whose message names `com.snoworca.naruserve:NaruServe` (D-226, D-227), tested in `NaruServe-api`'s own test source set (not its shared test fixtures, so later modules never see a fake provider) through a dedicated class loader with exactly one fake provider and with none. `RestApp.create()` looks providers up with the class loader that loaded `RestApp` (`ServiceLoader.load(AppFactory.class, RestApp.class.getClassLoader())`), which on Android is the app's class loader, never the thread context loader; an internal overload taking a class loader serves the tests. The factory interface is `com.snoworca.naruserve.spi.AppFactory`, registered at `META-INF/services/com.snoworca.naruserve.spi.AppFactory`; more than one provider makes `RestApp.create()` throw an exception naming all of them. The factory interface is an SPI type documented in `docs/api/app.md` beside `RestApp.builder()`-style explicit initialisation, so I-008 and I-013 hold. Wave 5 provides the bootstrap factory that assembles TinyHttpd and Gson (D-228) and the `create()` half of D-169; wave 4 delivers the console logger as the default of the explicit entry point of D-111.
- Pre-request rejections (W1-001 of round 1, W2-001/W2-004 of round 3; D-117, D-129, D-135, D-173, D-400, D-422, I-014): middleware and `onError` run for every request whose method token is a `Method` value and whose raw path decodes, because a `Request` can be built for it. That covers `404`, `405`, `426`, the automatic `OPTIONS` reply and the `501` for a disabled `TRACE` or `CONNECT` (including `CONNECT /` of D-422). From wave 4 the dispatcher builds the `Request` and runs the middleware chain before writing those automatic responses, as Express runs middleware before its `404`. Five cases are answered without middleware and `onError`: the two lifecycle-gate `503`s of the "Lifecycle gate" decision below, and three pre-request cases: an unrecognised method token (`501`), because no `Method` value exists for it (I-014 fixes `req.method()` as a non-null `Method`); a raw path that does not start with `/` or that the path validator rejects (`400`), because no decoded `req.path()` exists (I-014); and `OPTIONS *` (`204`). These three still get exactly one access-log line (D-173, D-422), and the two error statuses carry the standard error body whose `requestId` equals the logged one (D-135). The SRS records this narrowing of D-117, D-129 and D-134 for all five cases in their acceptance criteria. Only TinyHttpd's own transport-level rejections (D-400) have no access-log line.
- Logger shutdown (W2-002 of round 1, W1-002 of round 2, W1-001/W2-002/W2-004 of round 3, W1-002/W2-002 of round 4; D-148, D-149, D-150, D-162, I-015): the `Logger` interface keeps its six methods. In D-162's own step "flushing and closing log writers", which comes after the owned and registered resources are closed in reverse order (D-149) and their `close()` failures are logged (D-150), and before the adapter release and the move to `TERMINATED`, shutdown flushes the app logger when it implements `java.io.Flushable` and closes it when it implements `AutoCloseable`, whoever created it (D-162 closes log writers). Closing the console logger leaves `System.out` and `System.err` open; the Logcat logger has nothing to close. A logger that is also owned or registered is skipped at its D-149 position and closed once at this step, compared by identity (D-148). The rotating file logger implements `AutoCloseable` and stops its writer thread in `close()`, so no writer thread outlives shutdown (I-015). `docs/api/logging.md` records this and says that a logger shared by several apps must be wrapped in a delegate whose `close()` does nothing.
- Keep-alive at shutdown (W2-003; D-166, T44): wave 4 delivers the `503` with `Connection: close` for a request that reaches the dispatcher after `STOPPING`, tested through the in-memory adapter. The TinyHttpd keep-alive socket case and T44 move to wave 5 (`carried-into-wave-5.md`).
- Services for long connections (W2-004; D-142, T41): wave 4 tests `req.service(T)` and `req.application().get(T)` for HTTP handlers; the WebSocket and SSE cases are asserted in wave 7 (`carried-into-wave-7.md`).
- Overload `503` (W2-005; D-134, D-400, D-137): a full request queue is a TinyHttpd transport-level `503` with its fixed plain-text response, not a standard error JSON case. Wave 4 corrects that row of `docs/api/middleware.md` and adds the row "request arriving during `STOPPING`" → `503` with the standard error body (D-134).
- `STARTING` (W2-006; D-428): `docs/api/lifecycle.md` states that a request arriving in `STARTING` is held by the start gate until `RUNNING` and answered `503` with `Connection: close` if the start fails.
- JVM lifetime (W2-007; D-167): server threads keep the JVM alive after `main` returns, as a listening Node server keeps its process alive; `shutdown` lets it exit. Wave 4 documents this in `docs/api/app.md`; the adapters enforce it, so the TinyHttpd adapter of wave 5 (TinyHttpd's threads are daemon by default) holds a non-daemon thread from bind until stop (`carried-into-wave-5.md`).
- `415` (W2-008; D-134): wave 4 adds the framework exception type that maps to `415` and tests the mapping by throwing it from a handler; the body readers of waves 5 and 6 raise it.
- `426` and `501` (W2-009; D-134, D-137): these are dispatcher automatic responses, not exception mappings; from wave 4 they carry the standard error body with the `code` values that D-137 decides, like `404` and `405`.
- File logger directory (W2-011 of round 1, W1-001/W2-001 of round 2; D-180, `docs/api/logging.md`): the rotating file logger keeps the D-180 defaults (`./logs`, 5 MiB per file, 5 files). Because `./logs` is not writable on Android, `docs/api/logging.md` tells Android apps to pass a writable directory (for example `context.getFilesDir()`), and a directory that cannot be created or written makes the logger drop records and count them, without stopping the HTTP service.
- Lifecycle gate (W2-005 of round 3; D-166, D-428, D-162): a request answered by the start gate (`503` when the start fails) or arriving during `STOPPING` (`503`, `Connection: close`) is answered at the gate, before the pipeline that D-428 releases requests into, so middleware and `onError` do not run for it; it gets one access-log line and the standard error body.

## Residual carried into SRS authoring

Loop W ended at its round cap (5) with no critical or high finding. The three remaining findings of round 5 are settled below; SRS authoring and the feasibility check confirm them, since no loop W round re-verified these lines.

- Logger close on every path into `TERMINATED` (r5 W2-001; D-147, D-158, D-159, D-164, D-426, I-015): the final log-writer step of the "Logger shutdown" decision runs on every path that ends in `TERMINATED` — normal shutdown, failed start, a stop requested while starting and shutdown before start — after that path's resource closes, so a logger that is also owned or registered is still closed exactly once. A test asserts that no writer thread of the rotating file logger remains after a failed `listen()`.
- Class-loader overload (r5 W2-002; I-008, I-014): the `ServiceLoader` lookup lives in a helper in the `internal` package that `RestApp.create()` and the tests both call; `RestApp` gains no overload, since a static interface method is always public.
- `onError` scope (r5 W2-003; D-127, D-129, D-137): middleware runs for the automatic `404`, `405`, `426`, `501` and `OPTIONS` responses; `onError` receives only exceptions thrown by middleware or a handler, or passed to `next.error(e)`, never an automatic response. Custom `404`/`405` bodies are settled under D-137.

