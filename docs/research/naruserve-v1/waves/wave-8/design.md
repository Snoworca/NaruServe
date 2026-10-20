# Wave 8 design — 8. Kotlin, Servlet, Android, release

Source: frozen run design `docs/research/naruserve-v1/design/00.design.md` lines 607–667 (design lock `docs/research/naruserve-v1/design/00.design.lock.json`). GitHub issues: #16, #19, #17, #18. Depends on waves: wave-1, wave-2, wave-3, wave-4, wave-5, wave-6, wave-7.

The items below are the frozen design items of this wave, verbatim and with their run ids; this wave implements every one of them. Integration items I-001..I-021 of the run design also hold at this wave's merge.


Covers issues #16, #19, #17 and #18 in that order; acceptance tests T10, T11, T12, T47, T50, with the device parts of T26 and T49 recorded as manual checks. Depends on sections: 1, 2, 3, 4, 5, 6, 7.

#### Kotlin compatibility

- [D-348] An automated check SHALL verify that every public method of `NaruServe-api` has a nullability annotation on its reference-typed return value and parameters.
- [D-349] Every Kotlin example in `docs/api/` SHALL exist as a Kotlin test that compiles and runs (T11).
- [D-350] Kotlin lambdas SHALL be accepted by every callback parameter without a DSL, such as `app.get("/x") { req, res -> }` (T11).
- [D-351] A Kotlin test SHALL use each nullable accessor of the `docs/api/kotlin.md` table with safe-call syntax and each non-null one without it (T11).
- [D-352] Public API method names MUST NOT be Kotlin hard keywords (`in`, `is`, `object`, `fun`, `when`, `val`, `var`).
- [D-353] Value objects such as `Cookie` SHALL follow `getX`/`setX`/`isX` naming so Kotlin property syntax (`cookie.maxAge = 60`, `res.isCommitted`) compiles.
- [D-354] The Kotlin extension module SHALL provide `inline fun <reified T : Any> Request.body(): T`.
- [D-355] A Kotlin test SHALL show that the default Gson codec cannot decode a data class without a no-argument constructor, matching `docs/api/json.md` (T11).
- [D-356] The framework MUST NOT use `runBlocking` as an asynchronous adapter.
- [D-357] Wave 8 SHALL verify that `docs/api/kotlin.md` names the Kotlin module exactly as wave 1 fixed it.

#### Servlet adapter

Answers Q-4: javax Servlet 3.1 only, late wave.

- [D-358] `NaruServe-servlet` SHALL implement the `HttpAdapter` SPI on `javax.servlet` 3.1, with no `jakarta.servlet` module in v1.
- [D-359] The Servlet adapter SHALL run the app's `onStart` when the container initialises it and `shutdown` when the container destroys it.
- [D-360] Application code on the Servlet adapter SHALL use the same routes, middleware, `Request`, `Response` and `Cookie` types as on the embedded engine.
- [D-361] SSE on the Servlet adapter SHALL run the D-106 drain loop under `AsyncContext`, releasing the container request thread after the handler returned.
- [D-362] Servlet adapter tests SHALL run on an embedded javax Servlet 3.1 container added in test scope only.
- [D-363] Wave 8 SHALL decide container registration and class names, path handling relative to context path and servlet mapping, multipart and WebSocket support, and update `docs/api/servlet.md`.

#### Android sample build

Answers Q-3 (automated builds, manual device run) and Q-5 (separate sample build).

- [D-364] The Android sample SHALL be a standalone Gradle build under `samples/android` with `minSdk 21`, Java and Kotlin sources, consuming the root build through `includeBuild`.
- [D-365] The CLAUDE.md exception for the per-project build files of `samples/android` SHALL be recorded by the wave that creates the sample.
- [D-366] Automation SHALL build the sample in Debug and Release with R8 minify on and off (T12).
- [D-367] The fixed Gradle 8.14.3, AGP 8.x and JDK 17 combination SHALL be written to the CI documentation.
- [D-368] The sample `Service` SHALL call `listen` and `shutdown` on a worker thread, never on the main thread (T47).
- [D-369] The sample Debug build SHALL enable a StrictMode thread policy that detects network and disk access on the main thread (T47).
- [D-370] The sample SHALL include a check, run in the R8-minified Release build, that an `@SerializedName` model encodes and decodes to identical JSON and that a JSON array of that model decodes through `new TypeRef<List<Model>>() {}` into a `List` whose elements have the model type (T12).
- [D-412] The sample's Kotlin sources SHALL compile in Debug and Release and exercise a SAM-lambda route, a nullable accessor with safe-call syntax, a multipart upload, a WebSocket endpoint and `req.service` in the device run.
- [D-371] The Android sample SHALL take its logger from `RestApp.create()` and show NaruServe log lines in Logcat during the device run, confirming the default of D-402.

#### Android device verification

- [D-372] The run SHALL attempt an API 21 emulator or device run of the sample, and when none boots, write a manual verification checklist for T10, T12 runtime and T47.
- [D-373] T10, the runtime part of T12 and T47 MUST NOT be marked verified without recorded API 21 device or emulator evidence.
- [D-374] The latest-Android regression run SHALL be recorded with the same automated-or-manual-pending status rule.

#### Release preparation

- [D-375] The release build SHALL generate a CycloneDX SBOM covering every published module (T50).
- [D-376] A dependency vulnerability scan SHALL run, and when network access is unavailable the scan is recorded as pending with the full dependency list (T50).
- [D-377] Release SHALL be blocked while a High or Critical dependency vulnerability remains without a recorded exception (T50).
- [D-378] A license report SHALL confirm that every shipped dependency is under a non-GPL license and list them in a third-party license file (T50).
- [D-379] The repository SHALL contain a README with installation coordinates and a quick start that matches `docs/api/README.md`.
- [D-380] Every (미확정) marker still in `docs/api/` SHALL be either resolved or rewritten as an explicit post-v1 item with its reason.
- [D-381] A release test results document SHALL list T01–T50 with automated, manual-pending or verified status and evidence links.
- [D-382] The release documentation SHALL record the Java 8 runtime row of the platform matrix as pending when no Java 8 runtime ran, citing `--release 8` and animal-sniffer as compile-time evidence.
- [D-383] The release documentation SHALL state how consumers obtain TinyHttpd, since the composite build gives no published POM dependency.
- [D-434] The release documentation and `docs/api/defaults.md` SHALL record TLS/WSS on the TinyHttpd adapter as an explicit post-v1 item, citing as reasons that no sub-issue of epic #20 covers it and that object.md §14.3 and §20.3 require separate API 21 TLS provider and certificate testing, which Q-3 leaves to manual device runs.



## Carried from loop D

Loop D ended at its round cap; the committee chose fix-and-freeze. The fixes below landed in the frozen design and this wave re-checks them in loop W:

No loop D residual is owned by this wave.

## Carried from earlier waves

- Out of scope by user decision (gate out-of-scope-user-consent, answered by the user on 2026-10-20): the Android sample build and Android device verification are removed from v1. The out-of-scope items are D-364, D-365, D-366, D-367, D-368, D-369, D-370, D-371, D-372, D-373, D-374 and D-412: the `samples/android` build, the AGP/R8 Debug and Release builds, StrictMode, Logcat, and API 21 emulator or device runs. Wave 8 authors no requirement for them. T10, T12 and T47, and the device parts of T26 and T49, are listed in the release test results document (D-381) as "out of v1 scope (user decision)", not as manual-pending.
- Kept: Android 5.0 (API 21) compatible source and bytecode. This means Java 8 `--release 8` and the animal-sniffer `android-api-level-21` check on the seven API 21 modules, unchanged from wave 1. It also means the constraints that no module has a compile-time `android.*` reference (D-385: the logcat module reaches `android.util.Log` by reflection only) and that Core uses no API above 21. Wave 8 keeps release preparation (D-375 to D-383, D-434), Kotlin compatibility (D-348 to D-357) and the Servlet adapter (D-358 to D-363). The release documentation says the Android sample and device verification are not part of v1.

## Wave decisions

Settled from loop W round 1 (`rounds/r1-merged.json`). These decisions bind SRS authoring, the SDS and the worker.

- Status of the removed Android tests (r1 W1-001, W2-001, W1-004; D-381, D-347, I-018, I-020, Q-3): the release test results document of D-381 uses a fourth status, `out-of-v1-scope`, with the reason "user decision 2026-10-20", for T10, T12 and T47 and for the Android device parts of T26 and T49. I-018 holds for every other test; for these five it is narrowed by the user's decision, which the SRS states as a deviation note. I-020 does not apply, because they are not manual-pending, and no requirement may call them verified. The header's "device parts of T26 and T49 recorded as manual checks" and the Q-3 "manual device run" wording are superseded by the carried section above. D-347's low-end Android tuning follow-up becomes an explicit post-v1 item (D-380 rewrite): `docs/api/defaults.md` retitles its section "저사양 Android 튜닝(수동 후속 작업)" as a post-v1 item and the release results document lists it as post-v1.
- Examples and Gson without the sample (r1 W1-002, W2-002; I-005, I-009): I-005 holds for the dependency resolution of every module. I-009 holds for the JVM Java examples. The Android-only `ServerService` example of `docs/api/lifecycle.md` stays a documented example that the root docs test skips with the reason "absent type `android.app.Service`" (its compile check belonged to the removed sample), and `lifecycle.md` states that it is not compiled in the v1 build.
- TLS reason (r1 W1-003, W2-003; D-434): the release text and `docs/api/defaults.md` give two reasons: no sub-issue of epic #20 covers TLS/WSS, and object.md §14.3 and §20.3 require separate API 21 TLS provider and certificate testing, which v1 does not run because Android device verification is out of v1 scope. They no longer cite Q-3's manual device runs.
- Release documentation statement (r1 W1-005; D-375 to D-383): the README (D-379) and the release test results document (D-381) each state that the Android sample build and API 21 device or emulator verification are not part of v1, and that API 21 compatibility rests on `--release 8` and the animal-sniffer `android-api-level-21` check. Issue #17 has no remaining deliverable; the release documentation lists it as closed by the user's decision.
- Publishing (r1 W2-004; D-375, D-379, D-383): v1 publishes to `mavenLocal` only, through `maven-publish` configured in the root `build.gradle` for the published modules, with no remote repository. README coordinates are `com.snoworca.naruserve:<module>:0.1.0-SNAPSHOT` with the `publishToMavenLocal` instruction. D-383 states that the TinyHttpd checkout is not published: consumers take it through `includeBuild` or publish it to `mavenLocal` themselves, and the `NaruServe-tinyhttpd` POM declares the TinyHttpd dependency with the version the composite build resolves. SRS authoring settles the exact POM mechanism inside these bounds. The CycloneDX SBOM covers the published modules.
- Nullability check (r1 W2-005; D-348): "has a nullability annotation" is satisfied by the package-level `@NullMarked` default plus `@Nullable` on every reference-typed return or parameter that may be null; no `@NonNull` exists. The automated check fails when a package of the public API lacks `@NullMarked` or when a public method of a public-API package declares a null-capable type without `@Nullable`. The public API packages are `com.snoworca.naruserve`, `com.snoworca.naruserve.spi` and `com.snoworca.naruserve.routing`; `com.snoworca.naruserve.internal` is outside the method-level check but keeps its `@NullMarked`.
- Kotlin keywords (r1 W2-006; D-352): the check's list of Kotlin hard keywords that are legal Java identifiers is `as`, `fun`, `in`, `is`, `object`, `typealias`, `typeof`, `val`, `var` and `when`.
- Servlet adapter decisions (r1 W2-007; D-363): wave 8 decides these and records them in `docs/api/servlet.md`: `listen()` and the host/port bind overload do not open a socket (the container owns it) and fail with `IllegalStateException` and a message that names the container; the container's destroy callback runs `shutdown`; the request path is the raw `getRequestURI()` minus the context path and the servlet mapping, kept percent-encoded as D-399 and D-052 require; multipart uses the core parser over the request input stream, not the Servlet `Part` API; Servlet 3.1 has no WebSocket API, so a route registered with `app.ws` answers `501` with the standard JSON error body and `docs/api/servlet.md` says WebSocket is not supported on the Servlet adapter in v1; SSE runs under `AsyncContext` (D-361).
- Logcat constraint (r1 W2-008): the carried line is corrected above to the D-385 reading: no module has a compile-time `android.*` reference.

Settled from loop W round 2 (`rounds/r2-merged.json`):

- Servlet start path (r2 W2-001; D-359, D-363): this supersedes the `IllegalStateException` statement of the round-1 Servlet decision. The container integration class (a `ServletContainerInitializer` or `ServletContextListener`; its name is decided at SRS authoring and recorded in `docs/api/servlet.md`) is the only caller of the start path. It installs the Servlet `HttpAdapter`, whose `bind(host, port, handler)` stores the handler and returns without opening a socket (host and port are ignored), and then calls `app.listen(...)`, so `onStart` runs and the state becomes RUNNING exactly as on the embedded engine. `boundPort()` and `ServerHandle.port()` report the local port of the first request (`ServletRequest.getLocalPort()`) and `0` before any request; `docs/api/servlet.md` says so. A user call of `listen(int)` on an app that holds the Servlet adapter takes the same path. The container's destroy callback runs `shutdown` (D-359); `HttpAdapter.stop` and `release` detach the handler.
- Pinned heading (r2 W2-002; D-347, D-380): this supersedes "retitles" in the round-1 first bullet. `docs/api/defaults.md` keeps its heading "저사양 Android 튜닝(수동 후속 작업)" unchanged, because the verified wave-7 AC-4 and `WaveSevenDocsTest` pin that exact heading. A sentence directly under it states that the item is a post-v1 item, and the release results document lists it as post-v1.
- Servlet path edge cases (r2 W2-003; D-363, D-399, D-052): an empty remainder after stripping the context path and the servlet mapping is the path `/`. The prefix match runs on the raw, percent-encoded request URI at a segment boundary. Path parameters (`;name=value`) stay in the path unchanged, as in Express. `docs/api/servlet.md` records all three.
- Nullability check, second reading (r2 W2-004; D-348, D-351): the check has three parts. (a) Every public-API package carries `@NullMarked` in its `package-info.java`. (b) Every method named in the nullable-accessor table of `docs/api/kotlin.md` has `@Nullable` on its return value. (c) Reflection finds no `@NonNull` in the public API, because under `@NullMarked` an unannotated reference type is non-null by contract. `docs/api/kotlin.md` line 59 is corrected to name `@NullMarked` (non-null by default) and `@Nullable`; it no longer mentions `@NonNull`. This supersedes the "null-capable type without `@Nullable`" test of the round-1 decision, which cannot be derived from signatures.
- Status literal (r2 W2-005; D-381): the literal status in the release test results document is `out-of-v1-scope`. "Out of v1 scope (user decision)" is its prose description. The document and its test use the literal only.
- Servlet adapter semantics left open (r2 W2-006; D-363): `abort()` on a Servlet exchange marks it aborted, and later writes throw `IOException`, because the container owns the socket. Response header order is the container's. `ServerOptions` settings that configure the embedded engine (`allowedHosts`, worker and connection limits, timeouts, header size, bind address) have no effect on the Servlet adapter, and `docs/api/servlet.md` lists them as ignored; the body limit and the JSON limit still apply in the core.

Settled from loop W round 3 (`rounds/r3-merged.json`):

- Port on the Servlet adapter (r3 W2-001; D-359, D-363, D-107): this supersedes the port statement of the round-2 start-path decision. The core reads `HttpAdapter.boundPort()` once, right after `bind`, and `ServerHandle.port()` returns that captured value, so on the Servlet adapter both are `0`; the container owns the socket. No core change. `docs/api/servlet.md` says the port is the container's and tells the reader to take it from the container.
- SSE bound and thread owner on the Servlet adapter (r3 W2-002; D-361, I-012): the Servlet adapter's event-stream class enforces its own stream counter with the same `maxSseStreams` limit (default 128) and answers `503` beyond it before the exchange is marked started. Each drain loop runs on a dedicated bounded executor that the adapter owns (daemon threads named `naruserve-servlet-sse-<n>`, at most `maxSseStreams`, shut down at `stop`), never on the container's pool threads; the `AsyncContext` completes when the drain ends.
- Servlet stop (r3 W2-003; D-359, D-107): `HttpAdapter.stop(timeout, unit)` on the Servlet adapter first refuses new requests with `503` and `Connection: close`, then waits for the in-flight exchanges until the deadline, as the core's shutdown sequence relies on; `release` detaches the handler. This supersedes "detach the handler" in the round-2 decision.
- Servlet path and WebSocket answer (r3 W2-004; D-363, D-399): the path remainder is the request URI minus the context path minus the url-pattern prefix the integration registered (`/*` and the default `/` mapping both work), not `getServletPath()`. The Servlet exchange's `webSocket` throws `HttpException(501)` because the SPI default would lead to `500`; this applies only to real upgrade requests, while other requests to an `app.ws` path keep the core's `426`.
- Release scan and result rows (r3 W2-005; D-376, D-377, D-381): the vulnerability scan is recorded `pending`, with the full dependency list and the failure reason, whenever the scanner cannot finish for any reason (no network, a missing API key, a rate limit or a tool error), not only for a missing network; a finished scan with a High or Critical finding still blocks the release (D-377). The release results document lists T26 and T49 as two lines each: the JVM part with its own status and the device part with `out-of-v1-scope`.

Settled from loop W round 4 (`rounds/r4-merged.json`):

- Markers pinned by earlier tests (r4 W2-001; D-380): D-380's rule that no `(미확정)` marker stays in `docs/api/` has one recorded exception, a deviation from the frozen wording. The three rows of `docs/api/express-migration.md` for `express.Router()`, `cors()` and `res.redirect(url)` keep their `(미확정)` text, because verified acceptance criteria of earlier waves (asserted by `AppDocsTest`, `HttpDocsTest` and `WaveFiveDocsTest`) pin them. The release documentation lists those three as open Express-compat items for after v1, and the D-380 check allows exactly those three. Every other marker is resolved or rewritten as a post-v1 item with its reason. `docs/api/servlet.md` keeps the word "jakarta", which `BuildLayoutTest` requires, by stating that no `jakarta.servlet` module exists in v1.
- TinyHttpd for consumers (r4 W2-002; D-383): this supersedes "publish it to `mavenLocal` themselves" in the round-1 Publishing decision. This repository does not publish TinyHttpd, and the checkout is read-only (I-006) with no publishing configuration. The README and the release documentation name Gradle `includeBuild` of the TinyHttpd checkout as the one verified way to obtain it, and say that the `NaruServe-tinyhttpd` POM dependency resolves only when an artifact with those coordinates is available from the consumer's own build of TinyHttpd.
- Who builds the app on the Servlet adapter (r4 W2-003; D-359, D-363): a built `RestApp` has no adapter setter, so the container integration uses `RestApp.builder()`. The user names a class that implements a Servlet-module configurer interface (its name is decided at SRS authoring) in a context init-parameter. The integration hands that configurer a `RestApp.Builder` that already holds the Servlet `HttpAdapter` and, when `NaruServe-gson` is on the classpath, the Gson codec; a logger comes from the same defaults as `RestApp.create()`. The configurer returns the built app, and the integration calls `app.listen(...)` on it. `docs/api/servlet.md` records the init-parameter and the interface.
- Release plugins (r4 W2-004; D-375, D-376, I-019): the CycloneDX SBOM and vulnerability scan plugins are applied only when the Gradle property `-Prelease` is set, with a conditional apply in the root `build.gradle`, so a default `./gradlew build`, online or offline, never resolves them. Their tasks are not wired into `check` or `build`. A `-Prelease` run whose scanner fails records the scan as pending under the round-3 rule.
- Servlet SSE registration (r4 W2-005; D-361): the integration registers the servlet with `asyncSupported=true`, and every SSE stream calls `AsyncContext.setTimeout(0)` so the container's default async timeout never ends a long stream. `docs/api/servlet.md` records both.
- Servlet bind (r4 W2-006; D-359): the core calls `bind(ServerSettings, ExchangeHandler)`, whose default drops the settings. The Servlet adapter overrides that two-argument method to keep the settings (`maxSseStreams`, the error renderer); its `bind(host, port, handler)` overload only stores the handler. This replaces "bind(host, port, handler) stores the handler" in the round-2 decision.

## Residual carried into SRS authoring

Loop W ended at its round cap (5) with no critical or high finding in any round. In every round, verifier 1 found all 38 items covered, none missing and none altered. Findings fell from 13 to 6, 5, 6 and 3. The three round-5 findings are settled below. No loop W round re-verified these lines, so SRS authoring and the feasibility check confirm them.

- Blocked writes of a Servlet SSE stream (r5 W2-001; D-361, D-359, D-107, I-012, I-015): the Servlet adapter's `stop` calls `shutdownNow()` on the SSE executor and, once the shutdown deadline has passed, `AsyncContext.complete()` on every stream still open; `abort()` on an SSE exchange also calls `AsyncContext.complete()`. A drain thread stuck in a socket write to a client that stopped reading is bounded by the container's own write timeout, which the adapter does not control. `docs/api/servlet.md` says so and says that the "cut at timeout" promise of `docs/api/sse.md` holds on the Servlet adapter up to that container bound. This is a recorded deviation under I-012; the SRS states it as a deviation note, and the release documentation lists it.
- Gson on the Servlet adapter (r5 W2-002; D-359, D-363, I-005): the integration finds the Gson codec by reflection (`Class.forName("com.snoworca.naruserve.gson.GsonJsonCodec")`), because the Servlet module's `runtimeClasspath` is pinned to `NaruServe-api` only, and the configurer may always set its own codec through `RestApp.Builder.jsonCodec`. When neither exists, startup fails with a `ServletException` that names the missing codec. `docs/api/servlet.md` records this.
- Header wording (r5 W2-003): the header's sentence that this wave "implements every one" of the frozen items is superseded, for the twelve out-of-scope Android items only, by the carried section above.
