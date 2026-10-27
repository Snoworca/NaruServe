# NaruServe

![NaruServe: embedded HTTP for Java and Kotlin on JVM and Android](docs/assets/naruserve-banner-features.png)

English | [한국어](README.ko.md)

NaruServe is a lightweight HTTP server library for Java 8 and Kotlin that you use like Express.js.
The same API runs on Android 5.0 (API 21) and later and on any Java 8+ JVM.

Read the [English API guide](docs/api/en/README.md) or the [한국어 API 문서](docs/api/README.md).
Start with installation and the runnable example below; use the API guides for signatures, defaults and error contracts.

## Install

NaruServe 0.9.0 is published on Maven Central, so Gradle downloads it from `mavenCentral()` with no extra
repository or source checkout:

- Central page: [central.sonatype.com/artifact/com.snoworca.naruserve/naruserve](https://central.sonatype.com/artifact/com.snoworca.naruserve/naruserve)
  (all modules: [namespace com.snoworca.naruserve](https://central.sonatype.com/namespace/com.snoworca.naruserve))
- Repository path: [repo.maven.apache.org/maven2/com/snoworca/naruserve/](https://repo.maven.apache.org/maven2/com/snoworca/naruserve/)

`build.gradle` (Groovy DSL):

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.snoworca.naruserve:naruserve:0.9.0'
}
```

`build.gradle.kts` (Kotlin DSL):

```kts
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.snoworca.naruserve:naruserve:0.9.0")
}
```

Pick the one line that matches the project; each brings the rest (TinyHttpd engine, Gson codec, logger) with it:

| Project | Gradle dependency |
|---|---|
| Java application on any JDK 8+ | `implementation 'com.snoworca.naruserve:naruserve:0.9.0'` |
| Kotlin application | `implementation 'com.snoworca.naruserve:naruserve-kotlin:0.9.0'` |
| Android app (API 21+) | `implementation 'com.snoworca.naruserve:naruserve-android:0.9.0'` plus the `pickFirsts` setting below |

An Android project that was created by Android Studio already lists `google()` and `mavenCentral()` in the
`dependencyResolutionManagement` block of `settings.gradle`; nothing else is needed there.

`NaruServe` resolves the TinyHttpd 3.1.0 engine from Maven Central; see
[TinyHttpd](#tinyhttpd) below. Kotlin projects depend on `NaruServe-kotlin`, and Android apps on
`NaruServe-android`, each of which includes `NaruServe`.

An Android app also needs the following, because the TinyHttpd 3.1.0 core and websocket JARs carry identical
license notices at the same paths, which otherwise fails the app's resource merge; one copy keeps the notices:

```groovy
android {
    packaging {
        resources {
            pickFirsts += ['META-INF/LICENSE-tinyhttpd.txt', 'META-INF/LICENSE-nanohttpd.txt', 'META-INF/NOTICE-tinyhttpd.txt']
        }
    }
}
```

Each artifactId is the module name in lower case (`NaruServe-api` is published as `naruserve-api`).

| Module | Coordinates | Contents |
|---|---|---|
| `NaruServe-api` | `com.snoworca.naruserve:naruserve-api:0.9.0` | Public API and SPI, no external runtime dependency (JSpecify annotations are compile-only) |
| `NaruServe` | `com.snoworca.naruserve:naruserve:0.9.0` | `RestApp.create()` bootstrap: TinyHttpd, Gson (`GsonJsonCodec` at compile time) and the console logger, for any JDK |
| `NaruServe-tinyhttpd` | `com.snoworca.naruserve:naruserve-tinyhttpd:0.9.0` | TinyHttpd `HttpAdapter` |
| `NaruServe-gson` | `com.snoworca.naruserve:naruserve-gson:0.9.0` | `GsonJsonCodec` (Gson 2.14.0) |
| `NaruServe-kotlin` | `com.snoworca.naruserve:naruserve-kotlin:0.9.0` | `NaruServe` plus Kotlin-only extensions; the only module that uses `kotlin-stdlib` |
| `NaruServe-android` | `com.snoworca.naruserve:naruserve-android:0.9.0` | Android library (AAR): `NaruServe`, the Logcat logger and the `INTERNET` permission; built only when an Android SDK is found |
| `NaruServe-logcat` | `com.snoworca.naruserve:naruserve-logcat:0.9.0` | Logcat `Logger` |
| `NaruServe-filelog` | `com.snoworca.naruserve:naruserve-filelog:0.9.0` | Rotating file `Logger` |
| `NaruServe-slf4j` | `com.snoworca.naruserve:naruserve-slf4j:0.9.0` | SLF4J `Logger` adapter (JVM only) |
| `NaruServe-servlet` | `com.snoworca.naruserve:naruserve-servlet:0.9.0` | Runs a NaruServe app on a Servlet 3.1+ container (JVM only) |

### Build from a source checkout

Build with **JDK 17** and the supplied **Gradle 8.14.3** wrapper. Library bytecode targets Java 8; the build JDK and runtime minimum differ. Check `java -version` and `./gradlew --version`.

The following commands use released TinyHttpd JARs, so no TinyHttpd checkout is needed:

```bash
./gradlew -PtinyHttpdFromMaven=true build
./gradlew -PtinyHttpdFromMaven=true publishToMavenLocal
```

For a separate consumer of this local build, add `mavenLocal()` before `mavenCentral()`:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}
```

The Android AAR is included only when an SDK directory is found in `local.properties` (`sdk.dir`), `ANDROID_HOME`, or `ANDROID_SDK_ROOT`, in that order. It uses Android Gradle Plugin 8.13.2, `compileSdk 36` and `minSdk 21`. Without an SDK, the nine JAR modules still build.

To work on TinyHttpd source, replace `-PtinyHttpdFromMaven=true` with `-PtinyHttpdDir=/absolute/path/to/TinyHttpd`. The default source path is specific to the original development machine; set one of these properties explicitly on a new checkout.

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

Run one source example from this checkout with JDK 17:

```bash
./gradlew -p examples/java -PtinyHttpdFromMaven=true run
# Or Kotlin:
./gradlew -p examples/kotlin -PtinyHttpdFromMaven=true run
```

In another terminal: `curl http://127.0.0.1:3000/hello`. `RestApp.start` listens on `127.0.0.1` only (this device), writes one `Listening on ...` line and stops on Ctrl+C; open the LAN inside the lambda with `app.server(o -> o.bindAddress("0.0.0.0"))`. `RestApp.create()` plus `app.listen(...)` remains the lower-level path.
The examples use a Gradle composite build of this source checkout; they do not require local Maven publication.
A separate consumer project uses the coordinates in [Install](#install); use the source-checkout installation if the release is not available in your repository.

## Coming from Express

NaruServe uses a similar route-and-middleware API: register `app.get`/`app.post` handlers, read the request and write the response. Compare these complete servers; both set `X-Example: demo` and return `{"id":"42"}` for `GET /users/42`.

### Express 5 (JavaScript)

Requires Node.js 18 or later and npm ([Express prerequisites](https://expressjs.com/en/starter/installing/)). Save as `server.cjs`, run `npm install express@5`, then `node server.cjs`:

```javascript
const express = require('express');
const app = express();

app.use((req, res, next) => {
  res.set('X-Example', 'demo');
  next();
});

app.get('/users/:id', (req, res) => {
  res.status(200).json({ id: req.params.id });
});

app.listen(3000, '127.0.0.1');
```

### NaruServe (Java)

With the `naruserve` dependency from Install, save as `ExpressStyleServer.java` in your application's Java source directory and run its `main`:

```java
import com.snoworca.naruserve.RestApp;
import java.util.Collections;

public class ExpressStyleServer {
    public static void main(String[] args) {
        RestApp app = RestApp.create();

        app.use((req, res, next) -> {
            res.header("X-Example", "demo");
            next.run();
        });

        app.get("/users/:id", (req, res) -> {
            res.status(200).json(Collections.singletonMap("id", req.param("id")));
        });

        app.listen(3000);
    }
}
```

Run one server at a time, then request the same loopback URL:

```bash
curl -i http://127.0.0.1:3000/users/42
```

Both return HTTP 200, the custom header and the JSON body above. The examples bind `127.0.0.1` explicitly in Express to match NaruServe's default.

| Task | Express | NaruServe |
|---|---|---|
| Create an app | `express()` | `RestApp.create()` |
| Register a GET route | `app.get('/users/:id', handler)` | `app.get("/users/:id", handler)` |
| Continue middleware | `next()` | `next.run()` |
| Read a path parameter | `req.params.id` | `req.param("id")` |
| Set status and send JSON | `res.status(200).json(value)` | `res.status(200).json(value)` |

The similarity is in the programming style, with important differences: NaruServe handlers and middleware run synchronously on request threads; ordinary responses must finish before the handler returns, and shared mutable state must be thread-safe. NaruServe's `res.send("text")` defaults to plain text, while Express uses HTML for strings. JSON input uses typed `req.body(Type.class)` reads instead of Express's `express.json()` middleware and `req.body` property. See the [full migration guide](docs/api/en/express-migration.md) for routing, errors and lifecycle differences.

Compared against the official Express 5 [routing guide](https://expressjs.com/en/guide/routing/), [middleware guide](https://expressjs.com/en/guide/using-middleware/) and [response API](https://expressjs.com/en/5x/api/response/).

## API map

NaruServe is an embedded server library. You define your application endpoints; this repository does not provide a fixed REST service.

| Task | Public API | Guide |
|---|---|---|
| Start or configure an app | `RestApp.start(int, Configurer<RestApp>)`, `create()`, `builder()` | [Application](docs/api/en/app.md) |
| Register routes and groups | `get`, `post`, `route`, `group` | [Routing](docs/api/en/routing.md) |
| Read and validate input | `Request.param`, `queryInt`, `requireQuery`, `body`, `requireFile` | [Request](docs/api/en/request.md) |
| Send a response | `Response.status`, `send`, `json`, `file`, `stream` | [Response](docs/api/en/response.md) |
| Handle cross-cutting behavior | `use`, `cors`, `onError`, `next.run()` | [Middleware](docs/api/en/middleware.md) |
| Keep a connection open | `ws`, `Response.sse` | [WebSocket](docs/api/en/websocket.md), [SSE](docs/api/en/sse.md) |
| Own application services | `onStart`, `onStop`, `ApplicationContext` | [Lifecycle](docs/api/en/lifecycle.md) |

Register routes and settings before listening. Handlers run concurrently; synchronize shared mutable state. `next.run()` is synchronous and may be called at most once. After `res.stream(...)`, NaruServe owns and closes the stream; upload temporary files must be saved before the request ends. See the linked guides for the complete contracts.

## Repository structure

```text
NaruServe-api/          Public interfaces, routing, common helpers, internal implementation and SPI
NaruServe/              Default app factory (TinyHttpd + Gson)
NaruServe-tinyhttpd/    Embedded HTTP/WebSocket engine adapter
NaruServe-gson/         Default JSON codec
NaruServe-kotlin/       Reified Kotlin extensions
NaruServe-android/      Manifest-only Android AAR
NaruServe-servlet/      Servlet 3.1 adapter
NaruServe-{filelog,slf4j,logcat}/  Optional logger adapters
examples/{java,kotlin}/ Runnable applications
src/test/              Build, publication and documentation checks
docs/api/              Korean guides; en/ contains their English counterparts
docs/spec/             Requirements (source of truth)
build.gradle           Shared configuration of all modules
settings.gradle        Module inclusion and optional TinyHttpd composite
```

## Troubleshooting

| Symptom | Cause and action |
|---|---|
| `tinyHttpdDir: TinyHttpd checkout not found` | Select published engine JARs with `-PtinyHttpdFromMaven=true`, or pass a valid `-PtinyHttpdDir` path. |
| `Could not find com.snoworca.naruserve:...:0.9.0` | Check release availability; publish this checkout locally and add `mavenLocal()` to the consumer. |
| `No com.snoworca.naruserve.spi.AppFactory provider found` | Add `naruserve` (or `naruserve-kotlin` / `naruserve-android`), or use `RestApp.builder()` with explicit `httpAdapter` and `jsonCodec`. `naruserve-api` alone supplies interfaces and implementation primitives. |
| Other devices cannot connect | The default bind is `127.0.0.1`. Set `bindAddress("0.0.0.0")` inside the configuration lambda and check device network/firewall settings. |
| Android resource merge reports duplicate TinyHttpd notices | Apply the `packaging.resources.pickFirsts` configuration from Install. |

## Scope of v1

The following records the **initial v1 verification scope decided on 2026-10-20**, before the Android AAR module was added. Current source builds can build the AGP-based AAR as described above; that build and local staging checks do not establish API 21 device or emulator verification. The compile-classpath statements below describe the original Java-module baseline.

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


## License

NaruServe is under the BSD 3-Clause License ([LICENSE](LICENSE)). The licenses of the shipped dependencies are listed
in [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md).
