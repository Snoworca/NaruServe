# android-module verification, 2026-10-26

Target `android-module`: `NaruServe` = API + TinyHttpd + Gson (`api`) + console logger for any JDK; `NaruServe-kotlin`
includes `NaruServe`; the new `NaruServe-android` AAR adds the Logcat logger and `android.permission.INTERNET`.
Requirements: FR-BUILD-001 (new), CON-BUILD-002, CON-BUILD-006, FR-ENGINE-009, FR-REL-001, FR-REL-005, OPS-BUILD-003,
OPS-KT-001, OPS-REL-001.

Environment: macOS, JDK 17.0.20.1 (Gradle and compilers), Zulu 8.0.504 (Java 8 test runtime), Gradle 8.14.3,
AGP 8.13.2, Android SDK at `ANDROID_HOME` with platform `android-36` and build-tools `36.0.0`. TinyHttpd checkout
`/Users/beom/work/git/TinyHttpd` at `e638586` (clean; differs from the verified `165f365` only by documentation, so
`settings.gradle` printed its warning).

## Feasibility spike (before the SRS)

A throw-away AGP 8.13.2 library with only an `AndroidManifest.xml` built `bundleReleaseAar` and its POM: the AAR held
`AndroidManifest.xml` (package, `minSdkVersion` 21, `INTERNET`), an empty `classes.jar` and `R.txt`; the POM had
packaging `aar`. AGP 8.7.3 (the cached version) was rejected because it does not support compileSdk 36 without a
suppression flag.

## RED before the implementation

Tests were changed or added first and run against the unchanged build:

```
$ ./gradlew :NaruServe:test --tests '*DefaultLoggerTest*' --tests '*DefaultAppFactoryTest*' --continue
DefaultAppFactoryTest > sdsAc7_FR_ENGINE_009_AC4_jarHoldsTheConsumerRulesAndTheServicesFile FAILED
DefaultLoggerTest > fr_ENGINE_009_AC3_withAFakeAndroidLogButNoLogcatModuleTheAppLoggerIsConsole FAILED
6 tests completed, 2 failed

$ ./gradlew :test --tests '*BuildLayoutTest*' --tests '*PublishedPomTest*' --tests '*BuildChecksTest.fr_*' --continue
37 tests completed, 15 failed   (settings, classpaths, AAR, POM, docs, SDK detection, logcat class reference)
```

Later in the run the same order held for `BuildLayoutTest.fr_BUILD_001_AC6_*` (pickFirsts docs),
`ReleaseScriptTextTest.fr_REL_001_AC1_*` (AAR SBOM configuration) and
`ReleaseRecordsDocsTest.fr_REL_001_AC2_*` / `fr_REL_005_AC1_*` (records name the AAR): each failed before its
change. Two existing docs tests failed in the first full build against the re-worded docs
(`ReadmeDocsTest.ops_REL_001_AC1_*` counted 9 table rows, `WaveEightKotlinDocsTest.sdsAc12_*` pinned the old
"depends on NaruServe-api only" sentence); OPS-REL-001 AC-1 and OPS-KT-001 AC-3 were re-worded first, then the two
tests. One test of my own was wrong and was corrected, not weakened: the bootstrap itself must compile against
`NaruServe-tinyhttpd`, so the "no NaruServe-tinyhttpd on the compile classpath" check applies to the caller
`NaruServe-kotlin` only (CON-BUILD-006 AC-9).

## GREEN full builds

```
$ ./gradlew build --rerun-tasks --continue
BUILD SUCCESSFUL, exit=0; tests=1728 failures=0 errors=0 skipped=0

$ ./gradlew build --rerun-tasks --continue -I gradle/java8-tests.gradle -Dnaruserve.java8.home=<Zulu 8>
BUILD SUCCESSFUL, exit=0; tests=1728 failures=0 errors=0 skipped=0; JAVA8_TEST_RUNTIME lines: 11

$ ./gradlew build --rerun-tasks --continue -PtinyHttpdFromMaven=true -I gradle/java8-tests.gradle -Dnaruserve.java8.home=<Zulu 8>
BUILD SUCCESSFUL, exit=0; tests=1730 failures=0 errors=0 skipped=0 (adds PublishedTinyHttpdCompatibilityTest)
```

The counts sum `build/test-results/*/TEST-*.xml` of every module. The Java 8 init script found no test sources in
`NaruServe-android`, so its two unit-test tasks printed no runtime line. AGP lint is disabled for the module
because its tasks take the module build file as an input, which CON-BUILD-017 forbids; the module has no source.

## Release AAR and POM (FR-BUILD-001 AC-3, AC-4; FR-REL-005 AC-2)

`NaruServe-android/build/outputs/aar/NaruServe-android-release.aar` holds `R.txt`, `AndroidManifest.xml` (package
`com.snoworca.naruserve.android`, `minSdkVersion` 21, one `uses-permission` `android.permission.INTERNET`), an empty
`classes.jar` and `aar-metadata.properties`. Its POM has packaging `aar` and two compile-scope dependencies,
`com.snoworca.naruserve:NaruServe:0.1.0-SNAPSHOT` and `com.snoworca.naruserve:NaruServe-logcat:0.1.0-SNAPSHOT`.
The `NaruServe` POM now has `NaruServe-api` and `NaruServe-gson` in compile scope, `NaruServe-tinyhttpd` in runtime
scope and no `NaruServe-logcat`; `NaruServe-kotlin` has `NaruServe` in compile scope.

## publish-local (FR-REL-005 AC-1)

```
$ ./gradlew publishToMavenLocal
BUILD SUCCESSFUL; ~/.m2/repository/com/snoworca/naruserve/ holds NaruServe, NaruServe-android, NaruServe-api,
NaruServe-filelog, NaruServe-gson, NaruServe-kotlin, NaruServe-logcat, NaruServe-servlet, NaruServe-slf4j,
NaruServe-tinyhttpd (each 0.1.0-SNAPSHOT); NaruServe-android/0.1.0-SNAPSHOT holds the .aar, .pom and .module
```

## Consumer Android app (FR-BUILD-001 AC-5, AC-6)

A throw-away app (AGP 8.13.2 `com.android.application`, compileSdk 36, minSdk 21, Java 8) with repositories
`mavenLocal()`, `google()`, `mavenCentral()` declared only `implementation 'com.snoworca.naruserve:NaruServe-android:0.1.0-SNAPSHOT'`
and one class:

```java
RestApp app = RestApp.create();
app.jsonCodec(new GsonJsonCodec(new Gson()));
app.get("/hello", (req, res) -> res.send("Hello"));
```

First run: `:app:mergeDebugJavaResource FAILED` — "2 files found with path 'META-INF/NOTICE-tinyhttpd.txt'" from
`tinyhttpd-websocket-3.1.0.jar` and `tinyhttpd-core-3.1.0.jar`. Both JARs carry `META-INF/LICENSE-nanohttpd.txt`,
`META-INF/LICENSE-tinyhttpd.txt` and `META-INF/NOTICE-tinyhttpd.txt` with identical SHA-1 (and `MANIFEST.MF`, which
AGP excludes). The same failure applies to any Android app that uses `NaruServe` or `NaruServe-tinyhttpd`, not only
the AAR.

With `packaging { resources { pickFirsts += ['META-INF/LICENSE-tinyhttpd.txt', 'META-INF/LICENSE-nanohttpd.txt', 'META-INF/NOTICE-tinyhttpd.txt'] } }`
(now shown in `docs/api/README.md` and `README.md`):

```
$ ./gradlew :app:assembleDebug :app:dependencies --configuration releaseRuntimeClasspath
BUILD SUCCESSFUL; app-debug.apk 346152 bytes (classes.dex, classes2.dex, classes3.dex; one copy of each notice)
releaseRuntimeClasspath: NaruServe-android -> NaruServe -> (NaruServe-tinyhttpd -> tinyhttpd-core:3.1.0,
tinyhttpd-websocket:3.1.0; NaruServe-api; NaruServe-gson -> gson:2.14.0 -> error_prone_annotations:2.48.0),
NaruServe-logcat
merged manifest: android:minSdkVersion="21", <uses-permission android:name="android.permission.INTERNET" />
```

Not done: an R8 (minified release) build, running the APK on a device or emulator, Logcat output on a device.

## Release gate (FR-REL-001 AC-1)

```
$ ./gradlew -Prelease '-PnvdDatafeedUrl=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz' releaseSbom releaseScan releaseLicenses releaseCheck
vulnerability scan cleared: no unexcepted High or Critical finding
BUILD SUCCESSFUL, exit=0
SBOM components: NaruServe-android 9, NaruServe-api 0, NaruServe-filelog 1, NaruServe-gson 3, NaruServe-kotlin 10,
NaruServe-logcat 1, NaruServe-servlet 1, NaruServe-slf4j 2, NaruServe-tinyhttpd 3, NaruServe 7
```

The AAR SBOM reads `releaseRuntimeClasspath`; the first SBOM run read `runtimeClasspath` and wrote an empty file,
which `ReleaseScriptTextTest` now guards. No third-party dependency was added; the scanned set is unchanged.
