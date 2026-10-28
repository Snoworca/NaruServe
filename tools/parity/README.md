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

## Runtime coverage is explicit

`BasicRuntimeParityTest` currently executes **three** cases against the actual NaruServe in-memory adapter: `literal.get`, `literal.post`, and `param.basic`. The other **27 reference observations are not a claim of current runtime support**. They supply expected results for feature owners to integrate as ordered routing, grammar, mounts, hooks, HEAD/OPTIONS, errors and async behavior land. There are no ignored, assumed, expected-failure, or disabled parity tests. Future owners add real runtime adapters/tests before claiming those features complete.

Transport normalization and socket behavior need separate actual TinyHttpd/Servlet evidence; passing the in-memory adapter cannot establish those claims. The supplied basic fixtures are unmounted and query-free, so their empty base URL and original path map directly to the current request. Mounted/query views require actual new runtime accessors rather than inferred fallback values.

Targeted root invocation (the leading colon avoids applying `--tests` to unrelated module tasks):

```sh
./gradlew -PtinyHttpdFromMaven=true :test --tests 'com.snoworca.naruserve.parity.*'
```

Keep Gradle on JDK 17. Actual Java 8 verification can use the existing `gradle/java8-tests.gradle` init script and `naruserve.java8.home` option. An available Android SDK keeps the ten-module staging prerequisite included.
