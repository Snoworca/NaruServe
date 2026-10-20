## 8. Kotlin, Servlet, Android, release

Covers issues #16, #19, #17 and #18 in that order; acceptance tests T10, T11, T12, T47, T50, with the device parts of T26 and T49 recorded as manual checks. Depends on sections: 1, 2, 3, 4, 5, 6, 7.

### Kotlin compatibility

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

### Servlet adapter

Answers Q-4: javax Servlet 3.1 only, late wave.

- [D-358] `NaruServe-servlet` SHALL implement the `HttpAdapter` SPI on `javax.servlet` 3.1, with no `jakarta.servlet` module in v1.
- [D-359] The Servlet adapter SHALL run the app's `onStart` when the container initialises it and `shutdown` when the container destroys it.
- [D-360] Application code on the Servlet adapter SHALL use the same routes, middleware, `Request`, `Response` and `Cookie` types as on the embedded engine.
- [D-361] SSE on the Servlet adapter SHALL run the D-106 drain loop under `AsyncContext`, releasing the container request thread after the handler returned.
- [D-362] Servlet adapter tests SHALL run on an embedded javax Servlet 3.1 container added in test scope only.
- [D-363] Wave 8 SHALL decide container registration and class names, path handling relative to context path and servlet mapping, multipart and WebSocket support, and update `docs/api/servlet.md`.

### Android sample build

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

### Android device verification

- [D-372] The run SHALL attempt an API 21 emulator or device run of the sample, and when none boots, write a manual verification checklist for T10, T12 runtime and T47.
- [D-373] T10, the runtime part of T12 and T47 MUST NOT be marked verified without recorded API 21 device or emulator evidence.
- [D-374] The latest-Android regression run SHALL be recorded with the same automated-or-manual-pending status rule.

### Release preparation

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

