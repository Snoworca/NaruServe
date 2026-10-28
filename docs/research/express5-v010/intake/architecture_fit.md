# Architecture-fit intake: Express 5 common HTTP routing

## Conclusion and scope

The goal is implementable. It requires a coordinated routing, request-execution, adapter and upstream-transport change. A matcher-only patch cannot deliver the requested behavior over the default HTTP engine.

This report applies the latest user direction: Express 5 common HTTP routing parity, preservation of existing NaruServe extensions, and a separate WebSocket extension profile. It does not use the currently unrelated `docs/next/request.md`. No Express 4 regex extension is inferred from obsolete request material. Java API names may differ from JavaScript names, but corresponding observable matching, continuation, parameter, error and response behavior must remain testable against the chosen Express oracle.

The researcher confirmed SpecKiwi workspace `/Users/beom/work/git/NaruServe`, root source `server-cwd-discovery`, mode `sdd`, and active target `central-0.9.0` through the recovered MCP stdio server. No repository, SRS or external-checkout mutations were performed. Only the two artifacts in this temporary directory were written.

## Registered SRS impact

`architecture_fit.json` contains the exact registered scope names, prefixes, requirement IDs, current metadata and source locations. The affected registered scopes are Routing, HTTP Core, Application, Engine Adapter, Developer Experience, Static Files, Server-Sent Events, WebSocket, Servlet Adapter, Kotlin Compatibility, Build and Release. There is no need to invent a parallel requirements source.

Core changes touch FR-ROUTE-003 through FR-ROUTE-009; FR-HTTP-004/009/023; IR-HTTP-003/007; FR-APP-003/004/005 and lifetime guarantees; FR-DX-008/009; FR-ENGINE-011; Servlet lifecycle requirements; and associated documentation and release obligations. Existing verified evidence must not be treated as verification of revised behavior. Preserve unaffected assertions and establish failing tests for each approved changed contract.

## Transport prerequisite

TinyHttpd's parser stores the raw URI and immediately derives a decoded path (`core/.../RequestParser.java:90-94`). Its parser rejects a null decoded path before calling the handler (`:30-31`). `PercentCodec.java:39-41` returns null for encoded slash, NUL, dot segments and decoding failures. NaruServe's raw-path accessor cannot recover a request the engine has already rejected.

The inspected dependency checkout is at `e638586`. A read-only diff confirms these parser/codec files are unchanged from the verified source pin `165f365`.

Create a dedicated upstream package: an explicit opt-in raw-routing request-target policy, unchanged legacy strict defaults, retained HTTP framing/Host/size validation, and tests proving legacy TinyHttpd router/webserver consumers remain safe. Keep static-file containment in the serving component rather than relying on a global route-path rejection. Produce a consumable binary and update NaruServe's pin; verify source-composite and binary-only builds. In-memory-only parity or a source checkout that hides unavailable binaries is insufficient.

## Routing and extension architecture

The current dispatcher selects one route before assembling middleware (`Dispatcher.java:184`). Params are initially fixed from that selection, and the request constructor decodes the complete path (`DefaultRequest.java:103-104`). Replace this with immutable ordered layers and explicit Route identities. Per-request state needs a layer cursor, route-handler cursor, mount path/base state, current params and active error.

Match common HTTP route syntax on the raw pathname, then decode matched captures. Do not eagerly reject every malformed escape during request construction when the oracle would never decode that unmatched part. Keep original raw target information for logs, routing and static confinement without repeated decoding.

Preserve group APIs, RouteBuilder, MethodSet, RouterPolicy overrides, CORS, static mounts, JSON, uploads, SSE, logging and lifecycle facilities. Their extension behavior must be deliberately mapped onto the new execution model. Current group callbacks defer registration (`DefaultRouteGroup.java:34-38`); preserve their documented configuration scope and define deterministic final stack positions. Static mounts currently run after explicit routes; preserve the extension contract unless the approved SRS explicitly changes it.

WebSocket parity is excluded, but WebSocket regressions are not. Keep a distinct profile and test its middleware/authentication, params, CORS exclusion, handshake, close and shutdown paths after shared core changes.

## Asynchronous execution and API 21

Removing the current owner-thread guard is insufficient. `ChainNext.java:90-99` rejects off-thread/late continuations; `MiddlewarePipeline.java:104-106` ends continuations when its initial call returns; `DefaultResponse.java:777-793` automatically completes a NEW response on handler return. Together these would finish a request before a deferred callback can continue it.

Use a logical request lifetime independent of the first Java stack frame. Serialize continuation, route skip, error propagation, response finish, abort and shutdown transitions. Distinguish a pending request from a completed response and from an active asynchronous callback. Successful promise-like completion must not implicitly mean `next()`. Rejection must enter the appropriate error flow; post-commit failures must not write a second response. Preserve tested synchronous before/after ordering where the oracle does so.

Android's official references mark both CompletionStage and CompletableFuture as API 24 additions. They cannot become mandatory API-21 core types under the current platform promise. An API-21-safe callback/custom completion contract can represent the required resolution/rejection semantics; optional JVM boundary adapters can translate CompletionStage. This is a platform representation choice, not permission to omit rejection semantics. Do not use `runBlocking` or introduce kotlinx.coroutines through this implementation: CON-KT-001 and build checks forbid that graph today.

References: https://developer.android.com/reference/java/util/concurrent/CompletionStage and https://developer.android.com/reference/java/util/concurrent/CompletableFuture.

## Adapter and lifetime seams

The HttpExchange SPI already says an exchange may outlive ExchangeHandler.handle (`spi/HttpExchange.java:12-16`). The TinyHttpd bridge waits on a completion latch (`TinyHttpdExchange.java:94` onward), so callback-based semantics are possible. It still consumes a transport worker while waiting. Separate correctness of deferred continuation from a claim of nonblocking transport scalability; measure pending requests, worker occupancy, admission bounds and shutdown behavior. Its current late-SSE restriction also needs integration when a deferred callback starts a stream.

Servlet normal requests currently finish with `service()` return, and their in-flight count decreases then (`NaruServeServlet.java:59-69`). AsyncContext is used primarily for SSE; `ServletExchange.finish()` flushes the response. Generalize lifecycle handling for deferred ordinary requests, with exactly-once completion, error, timeout, abort and in-flight accounting. Do not charge ordinary pending requests against the SSE drain-slot budget.

Uploads currently release after both response completion and handler-chain return. Replace the initial dispatch-return signal with logical request-execution completion so a suspended request retains valid resources. Streams, body reads and SSE transitions need the same ownership audit.

`GatedExchangeHandler.java:46-50` marks the initial dispatch as a request thread through AppLifecycle's ThreadLocal. Enter and exit equivalent request context around resumed work too: shutdown invoked from a deferred callback must not wait for that callback's own request and deadlock. Preserve deadline, onStop, owned-resource and logger cleanup order.

## Work decomposition and dependencies

1. P0: freeze exact common-routing and extension contracts, revise affected SRS and oracle comparison fields.
2. P1 after P0: pinned Express oracle, raw-request corpus and committed Java-only replay fixtures.
3. P2 after P0: upstream TinyHttpd raw-routing mode and consumable release prerequisite.
4. P3 after P0/P1: matcher, capture decoding and layer parameter representation.
5. P4 after P0/P3: ordered layers, Route identity, continuation/skip/error and mount-state restoration.
6. P5 after P0/P4: asynchronous completion and logical request lifetime.
7. P6 after P2/P5: actual TinyHttpd and Servlet adapter integration.
8. P7 after P4/P5/P6: existing extension and shutdown integration.
9. P8 after P1/P6/P7: real-transport conformance, runtime, concurrency and resource gates.
10. P9 after P8: bilingual migration, licensing, dependency and release evidence.

P2 can proceed in parallel with matcher work. P4/P5 share Dispatcher and execution state; freeze their interface before assigning separate worktree lanes. Avoid concurrent independent redesigns of the same core files.

## Decisions and actual gates

The latest scope already resolves preservation of existing extensions and WebSocket separation. Type names, immutable representations, worktree layout, matcher internals, test isolation and compatible overload adapters are routine engineering decisions; do not ask the user to approve each one.

The genuine unresolved execution boundary is ownership/authorization of required TinyHttpd repository work and delivery of its consumable version. Do not silently modify an adjacent checkout. If independent publishing requires signing or a public upload, apply the explicit publishing gate at that concrete action after artifacts are ready. These are action/dependency decisions, not reasons to silently narrow the Express goal.

Escalate only if implementation would require raising minSdk, introducing a prohibited coroutine dependency, deleting existing extensions, or excluding common routing behavior. Current evidence supports a path without those reductions. Match the full approved contract rather than substituting a synchronous-only or test-adapter-only result.

## Completion evidence

Require actual raw HTTP comparison through the new binary engine plus source-composite builds; Java 8 runtime and JDK 17 suites; API 21 animal-sniffer/dependency/class-reference checks; Java/Kotlin source and nullability tests; delayed next/rejection and race cases; disconnect/abort/shutdown; exactly-once upload/stream/SSE/WS/log cleanup; Servlet normal async and existing SSE paths; extension preservation; and bilingual documentation and license metadata matching the shipped dependency graph.
