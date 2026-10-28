# NaruServe API guide

English | [한국어](../README.md)

NaruServe is a lightweight HTTP server library for Java 8 and Kotlin with an Express.js-style API.
Use the same API on Android 5.0 (API 21) and later and on a regular JVM.

> **Documentation status:** These guides describe the 0.10.0 common HTTP profile and retained extensions. Until Maven publication is verified, use a source build for the new APIs; the 0.9.0 coordinates below describe the previous public release. See [Coming from Express](express-migration.md) for routing, errors, asynchronous ownership and the Java Pattern boundary.

If you have used Express, start with [Coming from Express](express-migration.md).

## Reading order

These guides are for Java/Kotlin developers using the library. You register application routes yourself; NaruServe does not supply a fixed set of REST endpoints.

1. Read [installation and project structure](../../../README.md), then run the minimal server below.
2. Learn app assembly and route registration in [RestApp](app.md) and [Routing](routing.md).
3. Read [Request](request.md), [Response](response.md) and [Middleware](middleware.md) for input validation, responses and error handling.
4. Choose the JSON, upload, static files, WebSocket, SSE, Kotlin or Servlet guide as needed.

Snippets that use `app`, `req` or `res` belong inside an existing app or handler. Interface declarations are excerpts that describe the API. For complete applications with imports and entrypoints, use the example links below.

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

These minimal servers include imports and executable entrypoints. Check the response with `curl http://127.0.0.1:3000/hello`. `RestApp.start` listens only on `127.0.0.1` (this device), writes one startup log line and stops on Ctrl+C. To accept connections from other devices, call `app.server(o -> o.bindAddress("0.0.0.0"))` inside the configuration lambda. See [start](app.md#start) for the differences; separate calls to `RestApp.create()` and `app.listen(...)` remain available.

The complete [Java example](../../../examples/java) and [Kotlin example](../../../examples/kotlin) cover DTO validation, a thread-safe user service, uploads and stream responses. Follow the [run instructions](../../../examples/README.md). These examples use this source checkout as a Gradle composite build. See the [root README](../../../README.md#install) for consumer coordinates and source-build alternatives. If the version does not resolve from Maven Central, publish the source checkout locally.

## Guides

| Guide | Express counterpart | Contents |
|---|---|---|
| [Coming from Express](express-migration.md) | — | Express ↔ NaruServe map and essential differences |
| [RestApp](app.md) | `express()`, `app` | App creation, route registration, configuration, `listen` and shutdown |
| [Routing](routing.md) | `app.METHOD`, `app.route`, `app.all` | Path patterns, `Method`/`MethodSet`, 404/405/HEAD/OPTIONS |
| [Request](request.md) | `req` | Path parameters, query, headers, body, attributes and service lookup |
| [Response](response.md) | `res` | Status, JSON, text, files, streams and cache |
| [Middleware and error handling](middleware.md) | `app.use`, `next`, error middleware | Execution order, `next.run()`, `app.onError` and error JSON |
| [JSON](json.md) | `express.json()` | `JsonCodec`, `TypeRef`, Gson configuration and replacement |
| [File uploads](multipart.md) | `multer` | `app.multipart`, `req.file`, `UploadFile` |
| [Static files and cache](static.md) | `express.static`, `res.sendFile` | `app.staticFiles`, MIME, `CachePolicy` and Range |
| [Cookie](cookie.md) | `res.cookie`, `cookie-parser` | `Cookie`, `SameSite` |
| [WebSocket](websocket.md) | `express-ws`, `ws` | `app.ws`, `WebSocketSession` |
| [SSE](sse.md) | Direct `res.write` | `res.sse()`, `SseClient`, `SseEvent` |
| [Lifecycle and services](lifecycle.md) | `app.locals` | `onStart`/`onStop`, `ApplicationContext`, `ServiceRegistry` |
| [Logging](logging.md) | `morgan` | `app.accessLog`, `Logger` |
| [Defaults and security](defaults.md) | — | Size and timeout defaults, default security policies |
| [Kotlin](kotlin.md) | — | Kotlin usage, null handling and JSON considerations |
| [Servlet module](servlet.md) | — | Run on an application server with `NaruServe-servlet` |

## Modules

All modules use Maven group `com.snoworca.naruserve` and version `0.9.0`. The artifactId is the module name in lowercase (`NaruServe-api` → `naruserve-api`).

| Module | Coordinates | Contents | Android |
|---|---|---|---|
| `NaruServe-api` | `com.snoworca.naruserve:naruserve-api` | Public API (`RestApp`, `Request`, `Response`, …) and SPI (`HttpAdapter`, `JsonCodec`, `Logger`). No external runtime dependency (JSpecify is compile-only) | Supported (API 21+) |
| `NaruServe` | `com.snoworca.naruserve:naruserve` | Default assembly for `RestApp.create()` (TinyHttpd + Gson + console logger). Usable on a regular JDK and Android | Supported (API 21+) |
| `NaruServe-tinyhttpd` | `com.snoworca.naruserve:naruserve-tinyhttpd` | TinyHttpd `HttpAdapter` | Supported (API 21+) |
| `NaruServe-gson` | `com.snoworca.naruserve:naruserve-gson` | `GsonJsonCodec` (`com.google.code.gson:gson:2.14.0`) | Supported (API 21+) |
| `NaruServe-kotlin` | `com.snoworca.naruserve:naruserve-kotlin` | `NaruServe` + Kotlin extensions such as `reified`. Only this module uses `kotlin-stdlib` | Supported (API 21+) |
| `NaruServe-android` | `com.snoworca.naruserve:naruserve-android` | Android library (AAR) with `NaruServe`, the Logcat logger and `android.permission.INTERNET` | Android-only (API 21+) |
| `NaruServe-logcat` | `com.snoworca.naruserve:naruserve-logcat` | Logcat `Logger`; builds without an Android SDK and calls `android.util.Log` through reflection | Supported (API 21+) |
| `NaruServe-filelog` | `com.snoworca.naruserve:naruserve-filelog` | Rotating file `Logger` | Supported (API 21+) |
| `NaruServe-slf4j` | `com.snoworca.naruserve:naruserve-slf4j` | SLF4J `Logger` adapter (`org.slf4j:slf4j-api:1.7.36`) | Unsupported (JVM only) |
| `NaruServe-servlet` | `com.snoworca.naruserve:naruserve-servlet` | Adapter that runs a NaruServe app on a Servlet 3.1+ container | Unsupported (JVM only) |

Modules marked “Supported (API 21+)” undergo the animal-sniffer `android-api-level-21` check at build time.
`NaruServe-android` is an Android Gradle Plugin AAR and builds only when an Android SDK is available. The build searches `local.properties` `sdk.dir`, `ANDROID_HOME` and `ANDROID_SDK_ROOT`, in that order. Without an SDK, it warns and builds the other nine modules. This module has no code of its own and uses `minSdk 21`.
The default HTTP engine build uses a TinyHttpd checkout, verified at commit `e73cd5d` (TinyHttpd 3.1.1, tag `v3.1.1`: RAW request-target policy). A different checkout HEAD produces a warning rather than a build failure. Set `-PtinyHttpdFromMaven=true` to use the 3.1.1 JARs from Maven Central without a checkout. The adapter selects `RequestTargetPolicy.RAW` to preserve encoded paths and raw queries; HTTP syntax, Host, framing and WebSocket path checks remain active.

`RestApp.create()` uses `NaruServe` to assemble the default HTTP engine (TinyHttpd) and Gson.
To choose the engine and JSON implementation explicitly, set the required `httpAdapter` and `jsonCodec` and optional `logger` on `RestApp.builder()`, then call `build()` (see [RestApp](app.md)).

`NaruServe` alone also exposes `GsonJsonCodec` and Gson at compile time. The TinyHttpd engine is available at runtime, and the default logger writes to the console.
`NaruServe-kotlin` includes `NaruServe`; Kotlin projects need only that module.
Android apps use `NaruServe-android`, which adds the Logcat logger and `android.permission.INTERNET` to `NaruServe`.

```groovy
// Regular JDK (Java 8+): start immediately with RestApp.create().
implementation 'com.snoworca.naruserve:naruserve:0.9.0'

// Kotlin: NaruServe and reified extensions.
implementation 'com.snoworca.naruserve:naruserve-kotlin:0.9.0'

// Android app: NaruServe, the Logcat logger and INTERNET permission.
implementation 'com.snoworca.naruserve:naruserve-android:0.9.0'
```

Also add the following to the Android app module's `build.gradle`. The TinyHttpd 3.1.1 core and websocket JARs contain identical license notices at the same paths. Without this configuration, the app's resource merge (`mergeDebugJavaResource`) fails on duplicate files. Keeping one copy preserves the notices in the APK because both copies have the same contents.

```groovy
android {
    packaging {
        resources {
            pickFirsts += ['META-INF/LICENSE-tinyhttpd.txt', 'META-INF/LICENSE-nanohttpd.txt', 'META-INF/NOTICE-tinyhttpd.txt']
        }
    }
}
```

## Supported environments

- Java 8 or later JVM
- Android 5.0 (API 21) or later
- Kotlin (use ordinary Java lambdas; no separate DSL is required)

## Execution contracts and troubleshooting

- Register routes and settings before `listen`. Handlers run concurrently, so protect shared mutable state.
- `next.run()` is synchronous and may be called only once.
- The framework closes streams passed to `res.stream` after transmission. Save upload temporary files before the request ends.
- Calling `RestApp.create()` with only `naruserve-api` supplies no default app factory. Add the default assembly module or use the [explicit builder](app.md).
- Build the source with JDK 17 and the Gradle 8.14.3 wrapper. Distinguish the Java 8 runtime target from the build JDK. See [README troubleshooting](../../../README.md#troubleshooting) for installation, engine resolution and Android resource-merge problems.
