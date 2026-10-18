## 1. Build foundation

Covers issue #1; its gate is the root `./gradlew build` with one Java and one Kotlin smoke test, and it has no acceptance test ID of its own. Depends on sections: none.

### Gradle wrapper and module layout

- [D-001] The build SHALL use only the Gradle 8.14.3 wrapper, with no Maven or other build tool files added to the repository.
- [D-002] The root `settings.gradle` SHALL include, next to `NaruServe-api` and `NaruServe-servlet`, one module each for the TinyHttpd `HttpAdapter`, the Gson `JsonCodec`, the default bootstrap that backs `RestApp.create()`, the Kotlin extensions, the SLF4J `Logger` adapter, the rotating file `Logger` and the Logcat `Logger`, with final names decided in #1 (the issue proposes `NaruServe-tinyhttpd`, `NaruServe-gson`, `NaruServe`, `NaruServe-kotlin`, and the logger modules take the same `NaruServe-` prefix).
- [D-003] Every module of the root build SHALL be configured from the root `build.gradle`.
- [D-004] Modules of the root build MUST NOT carry their own per-module build files.
- [D-005] Wave 1 SHALL declare every module's main dependencies up front, so later waves add only test-scope dependencies or the release plugins owned by #18.
- [D-006] Java compilation in every module SHALL use `options.release = 8`.
- [D-007] Kotlin compilation in every module and test source set SHALL use `jvmTarget = 1.8`.
- [D-008] The Maven group and the Java package root SHALL be fixed once in #1 (temporary value `com.snoworca.naruserve`) and used by every module.
- [D-009] `NaruServe-api` MUST NOT declare any runtime dependency outside the JDK.
- [D-010] The Gson module SHALL depend on exactly `com.google.code.gson:gson:2.14.0`.
- [D-011] `kotlin-stdlib` SHALL be a runtime dependency only of the Kotlin extension module, with other modules using it at most in test scope.
- [D-012] `NaruServe-servlet` SHALL depend on `javax.servlet:javax.servlet-api:3.1.0` and `javax.websocket:javax.websocket-api:1.1`, both in `compileOnly` scope only and declared in wave 1, so a later decision for container WebSocket support needs no new main dependency.
- [D-384] The SLF4J adapter module SHALL have `org.slf4j:slf4j-api:1.7.36` as its only main dependency besides `NaruServe-api`, declared in wave 1.
- [D-385] The Logcat `Logger` module SHALL reach `android.util.Log` only through reflection at runtime, with no compile-time Android dependency, so the root build compiles and tests it without an Android SDK.
- [D-386] The bootstrap module SHALL declare in wave 1 a main dependency on the Logcat `Logger` module, so wave 5 selects Logcat as the Android default without a later build change.

### TinyHttpd composite build

Answers Q-1 (composite build through a Gradle property) and Q-2 (TinyHttpd is read-only).

- [D-013] `settings.gradle` SHALL include TinyHttpd with `includeBuild`, reading its directory from a Gradle property whose default is `/Users/beom/work/git/TinyHttpd`.
- [D-014] The TinyHttpd `includeBuild` path MUST NOT be a hard-coded relative path, so builds inside git worktrees resolve it.
- [D-015] When the configured TinyHttpd directory is missing, the build SHALL fail during configuration with a message naming the property and the path it tried.
- [D-016] NaruServe modules SHALL reference TinyHttpd only through its Maven coordinates (`com.snoworca.tinyhttpd:tinyhttpd-core`, `com.snoworca.tinyhttpd:tinyhttpd-websocket`) so Gradle substitutes the included projects.
- [D-017] Only the TinyHttpd adapter module SHALL depend on TinyHttpd artifacts.
- [D-018] NaruServe modules MUST NOT depend on `tinyhttpd-router` or `tinyhttpd-session`.
- [D-019] No wave of this run SHALL modify any file inside the TinyHttpd checkout.
- [D-020] A TinyHttpd gap that the NaruServe adapter cannot fill SHALL be recorded as a deferred issue instead of being patched in TinyHttpd.
- [D-021] Project documentation SHALL record the TinyHttpd commit the run builds against (46b973e at investigation time).

### Test infrastructure

- [D-022] Every Java module SHALL use JUnit 4.13.2 for tests.
- [D-023] The build SHALL apply Kotlin Gradle Plugin 2.x so Kotlin test sources compile and run inside `./gradlew build`.
- [D-024] Wave 1 SHALL add one Java smoke test and one Kotlin smoke test, and both execute during `./gradlew build`.
- [D-025] The root `./gradlew build` SHALL pass on the local JDK 17 without an Android SDK.
- [D-387] `gradle.properties` SHALL set `kotlin.stdlib.default.dependency=false`, so applying the Kotlin Gradle Plugin adds `kotlin-stdlib` to no main configuration.
- [D-388] A build check SHALL fail when any module other than the Kotlin extension module has `kotlin-stdlib` on its `runtimeClasspath`.

### Nullability and API 21 checks

- [D-026] Wave 1 SHALL add a nullability annotation library that the Kotlin compiler recognises, in `compileOnly` scope with no runtime dependency, chosen in #1.
- [D-027] animal-sniffer with the `android-api-level-21` signature SHALL run on the main source set of `NaruServe-api`, the TinyHttpd adapter module, the Gson module, the bootstrap module, the Kotlin extension module, the Logcat `Logger` module and the rotating file `Logger` module as part of `check`.
- [D-028] The animal-sniffer API 21 check MUST NOT be applied to `NaruServe-servlet`, which is JVM-only.

### Wave 1 documentation

- [D-029] `docs/api/README.md`, `docs/api/app.md`, `docs/api/json.md`, `docs/api/kotlin.md` and `docs/api/logging.md` SHALL name the final module names and dependency coordinates fixed in #1, so wave 1 removes every (미확정) module-name marker in `docs/api/`.
- [D-448] Wave 1 SHALL update the module line of `CLAUDE.md` to list every module fixed in #1, mark the modules under the animal-sniffer API 21 check of D-027 as API 21 check targets and keep `NaruServe-servlet` marked JVM-only.


## Wave 1 additions from loop W

Wave decisions (non-normative, recorded in waves/wave-1/design.md): root project renamed `NaruServe-build`; bootstrap module named `NaruServe`; TinyHttpd declared with `implementation` scope in the adapter module only; the `jakarta` marker in docs/api/servlet.md stays for wave 8; the provisional `rest-kotlin` name is replaced in CLAUDE.md and docs/api/kotlin.md.

Loop W residual (low, carried into this wave's SRS): wave 1 adds the automated build checks behind integration items I-002 (NaruServe-api has no android/servlet/Gson/TinyHttpd references) and I-005 (only Gson 2.14.0 on any classpath), since both subjects exist after this wave.
