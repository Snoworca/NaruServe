# NaruServe 0.1.0-SNAPSHOT release notes

Test results: [test-results.md](test-results.md).

## Android scope

The Android sample build and API 21 device or emulator verification are not part of v1.
The Android sample build, the AGP and R8 builds, StrictMode, Logcat on a device and any API 21 device or emulator run are not part of v1 by the user decision of 2026-10-20; issue #17 is closed by that decision (2026-10-20) and has no remaining deliverable.
API 21 compatibility rests on three parts: compiling with `--release 8`, the animal-sniffer `android-api-level-21` check of the seven API 21 modules, and the D-385 rule that no module has a compile-time `android.*` reference (see [API 21 checks](#api-21-checks)).

Update 2026-10-26 (target android-module, FR-BUILD-001): `NaruServe` no longer brings the Logcat logger and exposes `NaruServe-gson` as `api`; `NaruServe-kotlin` includes `NaruServe`; the new `NaruServe-android` AAR (no code of its own, `minSdk` 21) adds the Logcat logger and `android.permission.INTERNET`. The root build makes it with AGP 8.13.2 only when an Android SDK is found. A one-off consumer app built a debug APK against it (`docs/release/verification/android-module.md`), and Android apps must keep one copy of the identical TinyHttpd 3.1.0 notice files (`packaging.resources.pickFirsts`, see `docs/api/README.md`). R8 release builds, StrictMode, Logcat on a device and device or emulator runs remain not done.

Update 2026-10-27 (target central-0.9.0, FR-REL-005): the version is `0.9.0` and every module is published under its lowercase artifactId (`com.snoworca.naruserve:naruserve:0.9.0`, `naruserve-api`, ..., `naruserve-android`) with sources and javadoc JARs and full POM metadata. `./gradlew checkCentralStaging` stages all ten modules in `build/central-staging` and fails without the Android module; Java and Android consumers of that directory alone built and ran (`docs/release/verification/central-0.9.0.md`). The missing-provider message of `RestApp.create()` now names `com.snoworca.naruserve:naruserve`. Signing and the Maven Central upload are not done yet.

The Android-only design items are out of v1 scope; none of them is a manual release check:

- D-364: the standalone `samples/android` Gradle build: out-of-v1-scope
- D-365: the CLAUDE.md exception for the build files of `samples/android`: out-of-v1-scope
- D-366: the Debug and Release sample builds with R8 minify on and off (T12): out-of-v1-scope
- D-367: the Gradle 8.14.3, AGP 8.x and JDK 17 combination in the CI documentation: out-of-v1-scope
- D-368: the sample `Service` calling `listen` and `shutdown` off the main thread (T47): out-of-v1-scope
- D-369: the StrictMode thread policy of the sample Debug build (T47): out-of-v1-scope
- D-370: the `@SerializedName` and `TypeRef` round trip in the minified Release build: out-of-v1-scope
- D-371: NaruServe log lines in Logcat during the device run: out-of-v1-scope
- D-372: the API 21 emulator or device run of the sample: out-of-v1-scope
- D-373: the API 21 device evidence for T10, T12 and T47: out-of-v1-scope
- D-374: the latest-Android regression run: out-of-v1-scope
- D-412: the Kotlin sources of the sample in Debug and Release: out-of-v1-scope

## Recorded deviations

These entries record the original v1 run. The D-380 marker exception was resolved by the API usability update in epic #21 below.

Each entry names the frozen design wording, the deviation and the requirement that records it.

- D-408: frozen: a non-upgrade request to a WebSocket-only path gets `426` with `Upgrade: websocket` and `Connection: Upgrade`; deviation: resolved in TinyHttpd 3.1.0; both headers are asserted over the TinyHttpd adapter and FR-WS-004 is verified; recorded in FR-WS-004
- D-416: frozen: the threads of one app are bounded to 451; deviation: the bounded total is 452 because the non-daemon `naruserve-keeper` thread runs from bind to stop; recorded in PERF-APP-001
- D-406: frozen: `SseClient.send` from any thread fills the outbound queue; deviation: until the drain loop starts, a send on the handler's own thread is written straight to the stream under the exchange lock; recorded in FR-SSE-002
- D-323: frozen: the WebSocket handshake has a configurable timeout; deviation: on TinyHttpd the handshake is bounded only by `headerTimeout` and `bodyReadTimeout` and no separate setting exists; recorded in FR-WS-008
- D-340: frozen: shutdown closes every open WebSocket session with `1001`; deviation: a session whose `onOpen` runs after the close wait ended is aborted at once without a close frame; recorded in FR-WS-010
- D-106: frozen: the upgrade extension point works without changing the SPI; deviation: the SPI gained add-only types and `default` methods, read as without breaking it; recorded in IR-HTTP-007
- Servlet SSE blocked-write bound: frozen: the Servlet adapter cuts the remaining streams at the shutdown timeout; deviation: a drain thread blocked in a write to a client that stopped reading is freed only by the container's own write timeout, which the adapter does not control; recorded in FR-SVL-006
- D-380: frozen: every undecided marker of `docs/api` is resolved or rewritten; deviation: the three `express-migration.md` rows `express.Router()`, `res.redirect(url)` and `cors()` keep their marker because verified tests pin them; recorded in OPS-REL-002

## API 21 checks

- check animal-sniffer: command=./gradlew build --rerun-tasks; commit=f357caf; tasks=:NaruServe-api:animalsnifferMain, :NaruServe:animalsnifferMain, :NaruServe-tinyhttpd:animalsnifferMain, :NaruServe-gson:animalsnifferMain, :NaruServe-kotlin:animalsnifferMain, :NaruServe-logcat:animalsnifferMain, :NaruServe-filelog:animalsnifferMain against `net.sf.androidscents.signature:android-api-level-21:5.0.1_r2`; result=pass (each task ran with --rerun-tasks on 2026-10-21; build exit=0)
- check class references: command=./gradlew build --rerun-tasks; commit=f357caf; tasks=:NaruServe-api:checkClassReferences, :NaruServe:checkClassReferences, :NaruServe-tinyhttpd:checkClassReferences, :NaruServe-gson:checkClassReferences, :NaruServe-kotlin:checkClassReferences, :NaruServe-logcat:checkClassReferences, :NaruServe-filelog:checkClassReferences (each forbids `javax/servlet/`; `NaruServe-api` and `NaruServe-logcat` also forbid `android/`, and no module has an Android artifact on its compile classpath); result=pass (each task ran with --rerun-tasks on 2026-10-21; build exit=0)
- check logcat: command=./gradlew build --rerun-tasks; commit=f357caf; task=:NaruServe-logcat:checkClassReferences, which fails on any `android/` class reference in the main classes, so `NaruServe-logcat` has no compile-time `android.*` reference (it reaches `android.util.Log` by reflection); result=pass (each task ran with --rerun-tasks on 2026-10-21; build exit=0)

## Platform matrix

The five targets of `object.md` §17.1.

| target | status | notes |
|---|---|---|
| JVM Java 8 | verified | Zulu OpenJDK 1.8.0_504 macOS aarch64 ran all 1,677 tests with failures/errors/skipped=0: [run](test-results.md#run-java8). The default/build JDK 17 remains unchanged. Compile-time checks: `--release 8` and animal-sniffer `android-api-level-21`; actual Java 8 runtime evidence is separate. |
| Android 5.0 API 21 | out-of-v1-scope | user decision 2026-10-20: no device or emulator run in v1 |
| Latest Android API | out-of-v1-scope | user decision 2026-10-20: no regression run in v1 |
| Kotlin Android project | out-of-v1-scope | user decision 2026-10-20: no Android sample in v1; Kotlin compile and run on the JVM are covered by T11 |
| Servlet container (optional module) | automated | embedded Jetty 9.4 (Servlet 3.1): HTTP, multipart, async and SSE tested; WebSocket not supported (`app.ws` routes answer `501`); other containers not tested |

## Open items

- The TinyHttpd 3.1.0 scan and release gate passed on 2026-10-24; High/Critical and unscored findings are zero. The existing Medium association remains visible. See the [current scan](vulnerability-scan.md) and [raw evidence](audit-2026-10-24/release-check.log).
- Historical scan: the vulnerability scan completed on 2026-10-23 (rerun against TinyHttpd 3.0.0, `com.snoworca:tinyhttpd-*`): [vulnerability-scan.md](vulnerability-scan.md) records `Status: completed`; High/Critical and unscored findings are zero. `releaseCheck` passed without risk-acceptance exceptions. One Medium association remains visible: CVE-2002-1819 on the `NaruServe-tinyhttpd` module jar (version `0.1.0-SNAPSHOT`); the TinyHttpd 3.0.0 jars carry no finding. The exact Kotlin standard-library false positive and build tooling scope are documented in [security review](security-review.md).

## API usability update — epic #21

The previous undecided rows are resolved: `app.group` provides nested route groups, `app.cors` handles HTTP CORS and preflight, and `res.redirect`, `res.type`, `res.location`, `res.sendStatus`, `res.cookie` and `res.clearCookie` are available. Independent Express Router objects and arbitrary router remounting remain unsupported.

This update changes the public API: typed attribute reads use `attributeAs`; `attribute(key, null)` deletes without a cast; `send(String)` and `send(byte[])` become one nullable `send(Object)` with runtime dispatch. Recompile applications and update direct interface implementations. Global and prefix middleware now execute in combined registration order. Same-type JSON body reads reuse a successful cached result. Kotlin reified body reads preserve nested generic arguments. See [migration](../api/express-migration.md) and [runnable examples](../../examples/README.md).

## Post-v1 items

- `res.render`: excluded from v1 because the specification has no template engine; `res.html(...)` sends HTML.
- Low-end Android tuning of `maxSseStreams`, `maxUpgradedConnections` and `sendQueueSize` (D-347): post-v1 because
  Android device verification is outside the v1 scope, so it is not a manual release check.
- TLS/WSS on the TinyHttpd adapter: post-v1 because no sub-issue of epic #20 covers it, and because `object.md`
  §14.3 and §20.3 require separate API 21 TLS provider and certificate testing, which v1 does not run since Android
  device verification is outside the v1 scope; run behind a TLS-terminating reverse proxy with `app.trustProxy(true)`.
- Kotlin `suspend` handlers: post-v1 because a non-blocking coroutine adapter needs its own design and v1 handlers are
  synchronous.
- A `jakarta.servlet` module: post-v1 because v1 ships only the `javax.servlet` 3.1 adapter.
- WebSocket on the Servlet adapter: post-v1 because the adapter answers `app.ws` routes with `501` in v1; the built-in
  engine (`app.listen`) serves WebSocket.

## TinyHttpd for consumers

TinyHttpd is not published by this repository. The adapter uses
`com.snoworca:tinyhttpd-core:3.1.0` and `com.snoworca:tinyhttpd-websocket:3.1.0` from Maven Central.
Published 3.0.0 lacks `Request.getHeaderEntries()` and `Response.abort()` and cannot support this adapter.
The repository defaults to a source composite at TinyHttpd commit `165f365` (3.1.0); use
`./gradlew -PtinyHttpdFromMaven=true build` to test the published JARs without a source checkout.
Consumers of the locally published NaruServe modules need `mavenLocal()` and `mavenCentral()`.
They may optionally substitute TinyHttpd sources in their `settings.gradle`:

```groovy
includeBuild('/path/to/TinyHttpd')
```

Cancellation now closes the actual transport for GET and HEAD without a fabricated 500 response.
Blocked writers wake promptly, and the adapter waits for their buffer ownership to end before returning to the engine.

한국어: TinyHttpd 3.1.0을 명시적으로 사용하며 Maven Central 바이너리 빌드와 선택형 소스 빌드를 지원합니다.
GET·HEAD 취소는 오류 응답을 만들어 보내는 대신 실제 연결을 닫고 막힌 쓰기를 깨웁니다.

- consumer run: command=./gradlew -p /tmp/naruserve-tinyhttpd-310-consumer run (composite: -Pcomposite=true; binary: no includeBuild and no init script); date=2026-10-24; with includeBuild=resolved tinyhttpd-core and tinyhttpd-websocket 3.1.0 source JARs and passed ordered-header/GET/HEAD cancellation; without includeBuild=resolved tinyhttpd-core and tinyhttpd-websocket 3.1.0 published JARs from Maven Central and passed the same checks

### Historical consumer run (2026-10-20)

Recorded consumer run: after `./gradlew publishToMavenLocal`, a Gradle build in a temporary directory declared
`mavenLocal()` and `implementation 'com.snoworca.naruserve:NaruServe-tinyhttpd:0.1.0-SNAPSHOT'` and resolved its
`runtimeClasspath`, once with `includeBuild('/Users/beom/work/git/TinyHttpd')` in `settings.gradle` and once without.
The TinyHttpd checkout was at `38051b6` during this run (TinyHttpd 0.1.0-SNAPSHOT, group `com.snoworca.tinyhttpd`), not at the
commit that was then verified (`46b973e`); the build warns about that difference and does not fail. The consumer build was:

```groovy
// settings.gradle (the run without includeBuild omits the second line)
rootProject.name = 'consumer'
includeBuild('/Users/beom/work/git/TinyHttpd')

// build.gradle
plugins { id 'java' }
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation 'com.snoworca.naruserve:NaruServe-tinyhttpd:0.1.0-SNAPSHOT'
}
tasks.register('resolveRuntime') {
    doLast { configurations.runtimeClasspath.files.each { println it.name } }
}
```

- historical consumer run: command=./gradlew -p <consumer dir> resolveRuntime (prints configurations.runtimeClasspath.files); date=2026-10-20; with includeBuild=resolved NaruServe-tinyhttpd, NaruServe-api, tinyhttpd-core and tinyhttpd-websocket 0.1.0-SNAPSHOT from the included build; without includeBuild=failed: Could not find com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT (and tinyhttpd-websocket)
