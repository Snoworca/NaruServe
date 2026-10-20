# NaruServe

NaruServe is a lightweight HTTP server library for Java 8 and Kotlin that you use like Express.js.
The same API runs on Android 5.0 (API 21) and later and on any Java 8+ JVM.

The full API documentation (Korean) is in [docs/api/README.md](docs/api/README.md).

## Install

v1 is published to the local Maven repository only. Build and publish the nine modules from this checkout:

```sh
./gradlew publishToMavenLocal
```

Then add `mavenLocal()` to the consumer build and depend on the modules you need:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'com.snoworca.naruserve:NaruServe:0.1.0-SNAPSHOT'
}
```

`NaruServe` depends on the TinyHttpd engine, so the consumer build also needs the TinyHttpd checkout; see
[TinyHttpd](#tinyhttpd) below.

| Module | Coordinates | Contents |
|---|---|---|
| `NaruServe-api` | `com.snoworca.naruserve:NaruServe-api:0.1.0-SNAPSHOT` | Public API and SPI, no external dependency |
| `NaruServe` | `com.snoworca.naruserve:NaruServe:0.1.0-SNAPSHOT` | `RestApp.create()` bootstrap: TinyHttpd, Gson and the Logcat logger |
| `NaruServe-tinyhttpd` | `com.snoworca.naruserve:NaruServe-tinyhttpd:0.1.0-SNAPSHOT` | TinyHttpd `HttpAdapter` |
| `NaruServe-gson` | `com.snoworca.naruserve:NaruServe-gson:0.1.0-SNAPSHOT` | `GsonJsonCodec` (Gson 2.14.0) |
| `NaruServe-kotlin` | `com.snoworca.naruserve:NaruServe-kotlin:0.1.0-SNAPSHOT` | Kotlin-only extensions; the only module that uses `kotlin-stdlib` |
| `NaruServe-logcat` | `com.snoworca.naruserve:NaruServe-logcat:0.1.0-SNAPSHOT` | Logcat `Logger` |
| `NaruServe-filelog` | `com.snoworca.naruserve:NaruServe-filelog:0.1.0-SNAPSHOT` | Rotating file `Logger` |
| `NaruServe-slf4j` | `com.snoworca.naruserve:NaruServe-slf4j:0.1.0-SNAPSHOT` | SLF4J `Logger` adapter (JVM only) |
| `NaruServe-servlet` | `com.snoworca.naruserve:NaruServe-servlet:0.1.0-SNAPSHOT` | Runs a NaruServe app on a Servlet 3.1+ container (JVM only) |

## Quick start

### Java

```java
RestApp app = RestApp.create();

app.get("/hello", (req, res) -> res.send("Hello World"));

app.get("/users/:id", (req, res) -> {
    res.json(userService.findById(req.param("id")));
});

app.post("/users", (req, res) -> {
    User user = req.body(User.class);
    res.status(201).json(userService.create(user));
});

app.listen(3000);
```

### Kotlin

```kotlin
val app = RestApp.create()

app.get("/hello") { req, res -> res.send("Hello World") }

app.get("/users/:id") { req, res ->
    res.json(userService.findById(req.param("id")))
}

app.post("/users") { req, res ->
    val user = req.body(User::class.java) ?: throw HttpException(400, "JSON body is null")
    res.status(201).json(userService.create(user))
}

app.listen(3000)
```

`User` and `userService` stand for your own application types.

## Scope of v1

The Android sample build and API 21 device or emulator verification are not part of v1.
API 21 compatibility rests on compiling with `--release 8` and on the animal-sniffer `android-api-level-21` check that the build runs over every module marked for Android in the API documentation.
The Android sample build, the AGP and R8 builds, StrictMode, Logcat on a device and any API 21 device or emulator run are not part of v1 by the user decision of 2026-10-20; issue #17 is closed by that decision (2026-10-20) and has no remaining deliverable.
The third part of the API 21 basis is the D-385 rule: no module has a compile-time `android.*` reference; the build's `checkClassReferences` check fails on an `android/` class reference in `NaruServe-api` and `NaruServe-logcat`, and no module has an Android artifact on its compile classpath.

- [API documentation](docs/api/README.md)
- [Release notes](docs/release/release-notes.md)
- [Test results](docs/release/test-results.md)
- [Third-party licenses](THIRD-PARTY-LICENSES.md)

## TinyHttpd

TinyHttpd is not published by this repository. The POM of `NaruServe-tinyhttpd` depends on
`com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT` and `com.snoworca.tinyhttpd:tinyhttpd-websocket:0.1.0-SNAPSHOT`,
which resolve only from your own build of TinyHttpd. The one verified way is Gradle `includeBuild` of a TinyHttpd
checkout in the consumer's `settings.gradle`:

```groovy
includeBuild('/path/to/TinyHttpd')
```

NaruServe was verified against TinyHttpd commit `46b973e`. Without the `includeBuild`, a build that takes
`NaruServe-tinyhttpd` (or `NaruServe`) from `mavenLocal()` fails with an unresolved `tinyhttpd-core`.

## License

NaruServe is under the BSD 3-Clause License ([LICENSE](LICENSE)). The licenses of the shipped dependencies are listed
in [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md).
