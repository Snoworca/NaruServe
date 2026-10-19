# Wave 4 SDS shared design sheet (authoring input for all fragment authors)

SDS id: 2026-10-19.naruserve.naruserve-v1-wave-4, fragments `docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-<k>.sds.md`, Target wave-4, Status draft, Date 2026-10-19. Format: docs/rule/SDS-MD-Rules-v2.6.0.md §9 (lite). Each fragment ≤ 100 lines, English, no narrative. Previous wave example: `git show fb4e488^:docs/sds/2026-10-19.naruserve.naruserve-v1-wave-3-5.sds.md`.
Root package path P = `NaruServe-api/src/main/java/com/snoworca/naruserve/`. Test root T = `NaruServe-api/src/test/java/com/snoworca/naruserve/`. Test fixtures F = `NaruServe-api/src/testFixtures/java/com/snoworca/naruserve/testing/`. Kotlin tests K = `NaruServe-api/src/test/kotlin/com/snoworca/naruserve/`.
Never list paths under docs/spec, docs/sds, docs/analysis, docs/research, kiwi, .kiwi in Files or Test Plan. docs/api/*.md, build.gradle and src/test/java/... root tests are allowed.

## Fixed names (use exactly these; do not invent other public names)

Public, package com.snoworca.naruserve (all @NullMarked by package-info; `@Nullable` from JSpecify):
- `RestApp` additions: `static RestApp create()`, `static RestApp.Builder builder()`, nested `final class Builder` (`httpAdapter(HttpAdapter)`, `jsonCodec(JsonCodec)`, `logger(Logger)`, `build(): RestApp`); `use(Middleware)`, `use(String prefix, Middleware)`, `get|post|put|patch|delete|head|options(String, Middleware, RouteHandler)`, `route(Method, String, Middleware, RouteHandler)`, `route(MethodSet, String, Middleware, RouteHandler)`, `onError(ErrorHandler)`, `onStart(LifecycleCallback)`, `onStop(LifecycleCallback)`, `listen(int port): ServerHandle`, `shutdown(long timeout, TimeUnit unit): void`, `state(): AppState`, `logger(): Logger`, `logger(Logger): RestApp`, `accessLog(Configurer<AccessLogOptions>): RestApp`.
- `RouteBuilder.use(Middleware): RouteBuilder`.
- `Request` additions: `application(): ApplicationContext`, `<T> service(Class<T> type): T`.
- `@FunctionalInterface Middleware { void handle(Request, Response, Next) throws Exception }`, `Next { void run(); void error(Throwable) }`.
- `@FunctionalInterface ErrorHandler { void handle(Throwable, Request, Response) throws Exception }`.
- `class HttpException extends RuntimeException` (`status()`, `code()`), `class UnsupportedMediaTypeException extends HttpException`.
- `ServiceRegistry`, `ApplicationContext extends ServiceRegistry`, `class ServiceNotFoundException extends RuntimeException`.
- `enum AppState { NEW, STARTING, RUNNING, STOPPING, FAILED, TERMINATED }`, `@FunctionalInterface LifecycleCallback { void handle(ApplicationContext) throws Exception }`, `interface ServerHandle { port(); address(); state() }`, `class AppStartException extends RuntimeException`.
- `Logger` (6 methods), `enum LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }`, `final class ConsoleLogger implements Logger, Flushable, AutoCloseable`.
- `@FunctionalInterface Configurer<T> { void configure(T target) }`, `interface AccessLogOptions` (enabled, level, includeRequestId, includeDuration, includeBytes, exclude — each returns AccessLogOptions).
Public SPI, package com.snoworca.naruserve.spi: `JsonCodec`, `AppFactory { RestApp create() }`.
Logger modules: `NaruServe-logcat/src/main/java/com/snoworca/naruserve/logcat/LogcatLogger.java`, `NaruServe-filelog/src/main/java/com/snoworca/naruserve/filelog/RotatingFileLogger.java` (nested `Builder`), `NaruServe-slf4j/src/main/java/com/snoworca/naruserve/slf4j/Slf4jLogger.java`.

Internal, package com.snoworca.naruserve.internal (new classes; existing ones are Dispatcher, DefaultRestApp, DefaultRequest, DefaultResponse, BodyState, LimitedInputStream, PercentDecoder, ResponseState, BodyTooLargeException):
- `AppLifecycle` — the single state machine and the one guard: `AtomicReference<AppState>`, `requireConfigurable(String method)` (throws IllegalStateException when state != NEW or the route table is frozen), pending-stop flag, `awaitTerminated(long, TimeUnit)`.
- `AppFactoryLoader` — `static RestApp create(ClassLoader loader)` (ServiceLoader lookup, missing / multiple provider messages).
- `GatedExchangeHandler implements ExchangeHandler` — what `listen()` passes to `HttpAdapter.bind`; first check is the lifecycle gate (hold before RUNNING on a start gate, 503 + Connection: close on start failure / pending stop / STOPPING), then delegates to `Dispatcher`. `Dispatcher(Router)` and `DefaultRestApp.dispatcher()` stay ungated for wave 3 unit tests.
- `MiddlewarePipeline` — registrations of global, prefix (with `PrefixMatcher` honouring RouterPolicy case/slash normalisation) and route middleware; `run(DefaultRequest, DefaultResponse, terminal)`.
- `PrefixMatcher` — segment-boundary prefix test.
- `ChainNext implements Next` — one continuation per invocation, IllegalStateException on second / after completion / after request end.
- `ErrorPath` — runs the `onError` chain, falls back to the standard error response, aborts when committed, logs 500s.
- `StandardErrors` — status→code/reason table, standard error JSON writer (core-built JSON, RFC 8259 escaping), used by the dispatcher automatic responses, the gates and ErrorPath.
- `RequestIds` — 16 lowercase hex id per request.
- `DefaultApplicationContext implements ApplicationContext` — registry map (frozen into an unmodifiable snapshot before RUNNING), ConcurrentHashMap attributes, own().
- `OwnedResources` — identity-deduplicated, first-call order, reverse close, collects failures.
- `ShutdownParticipants` — nested `enum Phase { BEFORE_WAIT, FORCE_CLOSE, AFTER_ON_STOP }`, `add(Phase, Runnable)`, `run(Phase, Logger)`.
- `SafeLogger implements Logger` — wraps the app logger, swallows RuntimeException; also `flushAndClose(Logger)`.
- `DefaultAccessLogOptions implements AccessLogOptions`, `AccessLog` — formats and writes one line (`<method> <path> <status>[ aborted][ <n>ms][ in=<i> out=<o>][ rid=<id>]`), `AccessLog.escape(String)`.
- Byte counting: `BodyState` gains a counting wrapper of the request body stream (`bytesRead()`); `DefaultResponse` counts body bytes written (`bytesWritten()`) and exposes a completion/abort callback for the access log.
Test fixtures: `TestApp` moves to `RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())` + `listen(0)`; new `StubJsonCodec` and `RecordingLogger` in F.

## Fragment allocation (k → requirements)
1 IR-APP-001, IR-APP-002, IR-APP-003, FR-APP-001 — public API A + SPI + create() lookup
2 IR-APP-004, IR-APP-005, IR-APP-006, IR-APP-007 — public API B
3 FR-APP-002, FR-APP-009 — DefaultRestApp guard, isolation, listen() startup, start gate, TestApp migration
4 FR-APP-003, FR-APP-004, FR-HTTP-021, FR-HTTP-022, FR-HTTP-023 — middleware pipeline, next contract, dispatcher order
5 FR-APP-005, FR-APP-006, FR-HTTP-024, FR-HTTP-025 — error path and standard error body
6 FR-APP-007, FR-APP-008 — services, context, owned resources
7 FR-APP-010, FR-APP-011, FR-APP-012, FR-APP-013 — start failure, shutdown sequence, other states, STOPPING 503
8 FR-APP-014, FR-APP-015, SEC-APP-001 — logger isolation, access log
9 FR-APP-016, FR-APP-017, FR-APP-018, CON-APP-001 — logger modules and core isolation
10 OPS-APP-001, OPS-APP-002, OPS-HTTP-003, OPS-HTTP-004, OPS-ROUTE-003 — docs/api decisions and docs compile gate

Each fragment: metadata `Requirements` row = exactly its ids; every id named by an `@req`; one SDS-AC per AC interpretation the implementer needs (AC-m must exist on that requirement); every SDS-AC has a Test Plan row with real test file paths (new test files allowed, e.g. T + `internal/MiddlewarePipelineTest.java`); every `→` target is a symbol declared in the same fragment. Tests re-pointed from superseded requirements (FR-HTTP-002/003/005/006/010, OPS-HTTP-001/002, OPS-ROUTE-002) are named in the Test Plan of the fragment owning the successor.
