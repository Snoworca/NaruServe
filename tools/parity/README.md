# Express reference corpus

Requirement: `OPS-ROUTE-004` (issue #48). This is test infrastructure, not a second requirements source.

The committed corpus records **30 actual Express executions**. Java `Pattern` is an explicitly approved language boundary: no ECMAScript regex engine, JNI, `g/y` state emulation, or additional NaruServe dependencies are introduced.

The normal Gradle build reads committed resources using existing JUnit/Gson/test-fixture facilities. It does not invoke Node, npm, the network, or the reference generator. Gson is already available on the root test classpath through `NaruServe-gson`; it is not added to API-module tests.

## Reproduce the reference

Use an existing installation with Node **22.22.0**, Express **5.1.0**, router **2.2.0**, and path-to-regexp **8.4.2**. No package installation is performed by this tool. `--reference-root` names a directory whose existing package.json resolves Express; router and path-to-regexp are resolved from the actual consuming package, so a different top-level package cannot hide a stale transitive version.

```sh
/path/to/node-22.22.0 tools/parity/generate-express-corpus.cjs \
  --reference-root /path/to/existing/reference-install
/path/to/node-22.22.0 tools/parity/generate-express-corpus.cjs \
  --reference-root /path/to/existing/reference-install --out /tmp/express5-second.json
cmp src/test/resources/parity/express5.json /tmp/express5-second.json
```

`reference-lock.json` records the selected package versions and SHA-256 hashes of package manifests, module entries, and routing/compiler sources. The generator rejects a different installation. `--write-lock` is reserved for an intentional reviewed reference update; it does not install dependencies. The corpus also records the generator's SHA-256, checked during ordinary Java tests. A changed generator needs a deliberate fixture regeneration.

## Comparison and normalization

`ParityCorpus.compare` reports the case ID and every differing JSON field. Object member order is immaterial; array/trace order and list shape, absent fields, explicit null, registration errors, request method/raw path, status, selected headers, body, scope, completion and errors remain significant. The corpus names the exact essential-header projection. Date/connection/keep-alive, ETag and X-Powered-By are excluded before reference recording; ports, timestamps, stacks and filesystem paths are never recorded. Unquoted UTF-8 charset parameter tokens are lowercased on both reference and Java capture because [RFC 9110 sections 8.3.1/8.3.2](https://www.rfc-editor.org/rfc/rfc9110.html#section-8.3.2) defines charset names as case-insensitive. Other charset values, header values, body bytes decoded as UTF-8, and error names/messages remain exact. A dedicated negative control prevents this normalization from hiding a different charset or unrelated header.

Java-specific regex controls are in `JavaPatternBoundaryTest`. A mismatch elsewhere cannot be relabeled as the Java regex exception after a failing comparison.

## Narrow language error counterparts

`LanguageErrorCounterparts` compares a copy of the reference expectation and retains both raw records in failure diagnostics. It does not change `ParityCorpus` or the committed Express observations.

| Scenario | Reference error | Accepted Java error |
|---|---|---|
| The two invalid route registrations | `TypeError` | Exact `RoutingConfigException` |
| `raw.malformed-escape` | `URIError` | Decode-specific `HttpException` with status 400 |
| `error.thrown`, `async.rejected` | `Error` | The scenario's deliberately thrown exact `RuntimeException` |

Only the terminal `; visit https://git.new/pathToRegexpError for info` suffix may be absent from Java registration messages. Reason, source, index, raw malformed value, response status/body/headers and every trace field remain exact. Captured Java class/message metadata must agree with the observed Throwable. Unrelated subclasses, other HTTP statuses, changed error messages and fabricated names fail the comparison. Negative controls verify these boundaries and preserve raw input records.

## Runtime coverage is explicit

`FullRuntimeParityTest` executes all **30** committed reference scenarios using real Java registrations, request handlers and the NaruServe in-memory adapter. It reads only scenario IDs/features and request inputs from the fixture; handlers compute actual response and request-view observations. Registration-error scenarios never manufacture a request.

The Stage 2 baseline (`d84ca92`) passes **25 of 30** runtime comparisons. Five tests remain real failures while later core features are implemented: `head.order-false`, `options.automatic`, `error.thrown`, `async.rejected` and `raw.malformed-escape`. The first two expose response/trace mismatches; the latter three report the missing future error/async API. This is integration work in progress, not evidence of completed parity. No test is ignored, disabled, assumed or marked expected-failure. Existing basic runtime and deliberate mismatch controls remain active.

Every observation uses actual `path()`, `url()`, `baseUrl()`, `originalUrl()` and `pathParams()` values. Java's single-callback prefix `use` overload maps the Express two-callback `use` scenario to adjacent prefix layers. The async rejection scenario invokes the public Deferred API through reflection until its implementation is integrated; API absence fails the test. Ordinary delayed `next()` uses an owned callback worker. The runner waits for the initial exchange callback and joins every owned continuation before collecting the trace, including middleware after response completion. Bounded latches/futures replace timing sleeps.

Transport normalization and socket behavior require separate actual TinyHttpd/Servlet evidence. Passing this in-memory replay cannot establish those claims.

Targeted root invocation (the leading colon avoids applying `--tests` to unrelated module tasks):

```sh
./gradlew -PtinyHttpdFromMaven=true :test --tests 'com.snoworca.naruserve.parity.*'
```

Keep Gradle on JDK 17. Actual Java 8 verification can use the existing `gradle/java8-tests.gradle` init script and `naruserve.java8.home` option. An available Android SDK keeps the ten-module staging prerequisite included.
