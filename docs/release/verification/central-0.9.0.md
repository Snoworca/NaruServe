# central-0.9.0 verification, 2026-10-27

Target `central-0.9.0`: the ten modules are Maven Central ready at version 0.9.0 with lowercase artifactIds
(`naruserve`, `naruserve-api`, ..., `naruserve-android`), sources and javadoc JARs, full POM metadata and a verified
local staging repository `build/central-staging`. Signing and the Central Portal upload are a separate,
user-confirmed step and were not run. Requirements: FR-REL-005 (rewritten), FR-BUILD-001 AC-4, FR-APP-001 AC-3,
FR-DX-012 AC-7, OPS-ENGINE-002 AC-5, OPS-KT-001 AC-1, OPS-REL-001 AC-1.

Environment: macOS, JDK 17.0.20.1 (Gradle and compilers), Zulu 8.0.504 (Java 8 test runtime), Gradle 8.14.3,
AGP 8.13.2, Android SDK at `ANDROID_HOME`.

## Feasibility spike (before the SRS)

`java.withSourcesJar()`/`withJavadocJar()`, AGP `singleVariant('release') { withSourcesJar(); withJavadocJar() }`,
`artifactId = p.name.toLowerCase()` and a file repository wrote, offline, main, sources, javadoc, `.pom` and `.module`
for all ten modules with md5, sha1, sha256 and sha512 files. Project dependencies map to the lowercase coordinates in
both the POM and the `.module` file (`naruserve` declares `naruserve-api`, `naruserve-gson` compile and
`naruserve-tinyhttpd` runtime, all 0.9.0). The spike change was reverted before the tests were written.

## RED before the implementation

```
$ ./gradlew :test --tests '*PublishedPomTest*' --tests '*CentralStagingTest*' ... :NaruServe-api:test --tests '*AppFactoryLoaderTest*' ... --continue
19 tests completed, 15 failed   (root: POM coordinates, metadata, staging, records, docs, checkCentralStaging)
30 tests completed, 5 failed    (NaruServe-api: the missing-provider message and the docs quote)
```

## GREEN full builds

```
$ ./gradlew build --rerun-tasks --continue
BUILD SUCCESSFUL, exit=0; tests=1734 failures=0 errors=0 skipped=0

$ ./gradlew build --rerun-tasks --continue -I gradle/java8-tests.gradle -Dnaruserve.java8.home=<Zulu 8>
BUILD SUCCESSFUL, exit=0; tests=1734 failures=0 errors=0 skipped=0; JAVA8_TEST_RUNTIME lines: 11

$ ./gradlew build --rerun-tasks --continue -PtinyHttpdFromMaven=true -I gradle/java8-tests.gradle -Dnaruserve.java8.home=<Zulu 8>
BUILD SUCCESSFUL, exit=0; tests=1736 failures=0 errors=0 skipped=0
```

One defect was found and fixed on the way: in the POM closure a local variable named `id` captured the assignment
`developer { id = 'ice3x2' }`, so the POM had no developer id; `PublishedPomTest#fr_REL_005_AC5_*` caught it.

## Staging and mavenLocal (FR-REL-005 AC-1, AC-6, AC-7)

```
$ ./gradlew checkCentralStaging
central-staging: 10 artifactIds at 0.9.0: naruserve, naruserve-android, naruserve-api, naruserve-filelog, naruserve-gson,
naruserve-kotlin, naruserve-logcat, naruserve-servlet, naruserve-slf4j, naruserve-tinyhttpd
BUILD SUCCESSFUL; 300 files under build/central-staging

$ ./gradlew publishToMavenLocal
exit=0; ~/.m2/repository/com/snoworca/naruserve/<artifactId>/0.9.0/ for the same ten artifactIds
```

macOS has a case-insensitive file system: the first `publishToMavenLocal` wrote the lowercase files into the old
`NaruServe-*` directories of the 0.1.0-SNAPSHOT publication. The `com/snoworca/naruserve` directory of `~/.m2` (this
build's own output only) was removed and the publication rerun, so the recorded directories are lowercase. The staging
directory is deleted before every staging run, so it never mixes cases.

## Consumers of the staging repository only

Both consumers declare `maven { url = uri('build/central-staging') }` and `mavenCentral()` (plus `google()` for
Android), with no `mavenLocal()` and no `includeBuild`.

- Java (application plugin, Java 8 target): `implementation 'com.snoworca.naruserve:naruserve:0.9.0'`;
  `RestApp.start(0, ...)` served `GET /hello` with `status=200 body=Hello 0.9.0`; the runtime classpath resolved
  `naruserve`, `naruserve-api`, `naruserve-gson`, `naruserve-tinyhttpd`, TinyHttpd 3.1.0 and Gson 2.14.0.
- Android (AGP 8.13.2, minSdk 21, the `pickFirsts` of FR-BUILD-001 AC-6):
  `implementation 'com.snoworca.naruserve:naruserve-android:0.9.0'`; `assembleDebug` built `app-debug.apk`; the
  release runtime classpath resolved the same graph plus `naruserve-logcat`.

The source examples keep working: `examples/*/settings.gradle` substitute the lowercase coordinates with the source
projects, because a composite build matches `group:projectName` only (without the substitution the Java example's
`naruserve:0.9.0` failed to resolve).

## Pre-publication R8 check (FR-BUILD-001 AC-7, FR-ENGINE-009 AC-4)

An Android app (AGP 8.13.2, minSdk 21, `minifyEnabled = true`, `proguard-android-optimize.txt`) on the staging
repository only, whose one class calls `RestApp.create()`, `app.jsonCodec(new GsonJsonCodec(new Gson()))`,
`app.get(...)` and `listen(0)`, with no app-side rule for NaruServe (only `-keep class com.example.naruapp.Server`):

1. With the original rules `:app:minifyReleaseWithR8` failed: `Missing class org.jspecify.annotations.NullMarked
   (referenced from: com.snoworca.naruserve.logcat.LogcatLogger)`. The jspecify annotations are `compileOnly`.
2. With that warning silenced the APK built, but its dex held `DefaultAppFactory` with `<init>` only and no
   `create()`, while `ServiceLoader` and `META-INF/services/com.snoworca.naruserve.spi.AppFactory` stayed: R8 sees no
   instantiation of a class that ServiceLoader creates by name, so `RestApp.create()` would fail at run time.
3. Fix: `naruserve-api.pro` gained `-dontwarn org.jspecify.annotations.**`; `naruserve-bootstrap.pro` keeps
   `DefaultAppFactory { public <init>(); public com.snoworca.naruserve.RestApp create(); }` and
   `LogcatLogger { public <init>(); public <methods>; }`. `ConsumerRulesTest` and `DefaultAppFactoryTest` were RED
   before the rule changes.
4. After restaging (`-PtinyHttpdFromMaven=true checkCentralStaging`): `assembleRelease` exit 0, `app-release.apk`
   99370 bytes; the dex holds `DefaultAppFactory.<init>` and `create`, `AppFactory.create`, `LogcatLogger.<init>` and
   seven logger methods (renamed together with the `Logger` interface), and the mapping keeps 42 TinyHttpd engine
   classes and 9 `naruserve-tinyhttpd` classes.

The APK was not run on a device or emulator.

## Maven Central publication

From the tagged commit `v0.9.0` (`2498eda`, clean tree): `./gradlew -PtinyHttpdFromMaven=true checkCentralStaging
--rerun-tasks` restaged the ten modules (300 files). A one-off script outside the repository copied the 50 primary
files (main, sources, javadoc, `.pom`, `.module` per module), signed each with the TinyHttpd PGP key (fingerprint
`DD2AFA2B544F2249C97984DBF5B75792328DC16B`, no expiration, not revoked; each signature self-verified), added md5,
sha1, sha256 and sha512, and uploaded the 300-entry bundle once to the Central Portal with `publishingType=AUTOMATIC`.

- deploymentId `582f3a49-ac6f-4793-9bdb-6228b1315d73`; validation passed with no errors; state `PUBLISHED` at
  2026-10-27 15:33:42 +0900.
- Public check at 15:34: all 50 primary files downloaded from `https://repo.maven.apache.org/maven2/` have the
  SHA-256 of the uploaded bytes, and their `.asc` files equal the uploaded signatures (50 identical, 0 missing,
  0 mismatched).
- Consumers with `mavenCentral()` only (the staging directory moved away): the Java consumer of
  `com.snoworca.naruserve:naruserve:0.9.0` answered `status=200 body=Hello 0.9.0`; the Android consumer of
  `naruserve-android:0.9.0` built the minified release APK (99370 bytes).

## Not done and open points

- The JARs carry no `LICENSE` file (the POMs carry the license); Central does not require one.
- The javadoc JARs of `naruserve-kotlin` (Kotlin sources only) and `naruserve-android` (no sources) hold only a
  manifest; Central accepts them.
- The release gate (`-Prelease ... releaseCheck`) was not rerun for 0.9.0.
