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

`NaruServe` resolves the TinyHttpd 3.1.0 engine from Maven Central; see
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
import com.snoworca.naruserve.RestApp;

public class HelloServer {
    public static void main(String[] args) {
        RestApp.start(3000, app -> app.get("/hello", (req, res) -> res.send("Hello World")));
    }
}
```

### Kotlin

```kotlin
import com.snoworca.naruserve.RestApp

fun main() {
    RestApp.start(3000) { app -> app.get("/hello") { _, res -> res.send("Hello World") } }
}
```

The complete [Java](examples/java) and [Kotlin](examples/kotlin) applications include DTO validation, a thread-safe user service, uploads and streaming. See [running the examples](examples/README.md).

Run the source examples from this checkout (requires a JDK and the TinyHttpd checkout):

```sh
./gradlew -p examples/java -PtinyHttpdDir=/absolute/path/to/TinyHttpd run
# Or Kotlin:
./gradlew -p examples/kotlin -PtinyHttpdDir=/absolute/path/to/TinyHttpd run
```

In another terminal: `curl http://127.0.0.1:3000/hello`. `RestApp.start` listens on `127.0.0.1` only (this device), writes one `Listening on ...` line and stops on Ctrl+C; open the LAN inside the lambda with `app.server(o -> o.bindAddress("0.0.0.0"))`. `RestApp.create()` plus `app.listen(...)` remains the lower-level path.
The examples use a Gradle composite build of this source checkout; they do not require local Maven publication.
For a separate consumer project using Maven coordinates, follow Install and TinyHttpd above/below: publish NaruServe locally, add `mavenLocal()` and `mavenCentral()`. NaruServe itself is not published to Maven Central.

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

TinyHttpd is not published by this repository. The adapter pins
`com.snoworca:tinyhttpd-core:3.1.0` and `com.snoworca:tinyhttpd-websocket:3.1.0` from Maven Central.
Published TinyHttpd 3.0.0 lacks `Request.getHeaderEntries()` and `Response.abort()`; use 3.1.0.

For a separate consumer of the locally published NaruServe modules, configure both `mavenLocal()` and
`mavenCentral()`. No TinyHttpd source checkout is required. To build this repository using the published JARs:

```bash
./gradlew -PtinyHttpdFromMaven=true build
```

The default repository build uses a source composite at `tinyHttpdDir`. Override it with
`-PtinyHttpdDir=/path/to/TinyHttpd`. NaruServe was verified against TinyHttpd commit `165f365` (3.1.0).
A consumer may also opt into that source build in `settings.gradle`:

```groovy
includeBuild('/path/to/TinyHttpd')
```

한국어: TinyHttpd 3.1.0의 core·websocket JAR를 Maven Central에서 가져옵니다. 저장소를 바이너리만으로
빌드하려면 `-PtinyHttpdFromMaven=true`를 사용하십시오. 기본 빌드는 `tinyHttpdDir`의 소스를 사용합니다.
기존 배포 버전 3.0.0에는 순서 보존 헤더와 연결 중단 API가 없으므로 사용할 수 없습니다.

## License

NaruServe is under the BSD 3-Clause License ([LICENSE](LICENSE)). The licenses of the shipped dependencies are listed
in [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md).
