# Runnable Java and Kotlin examples

English | [한국어](README.ko.md)

Both applications use the default Gson codec and TinyHttpd. They include full imports and entrypoints, a thread-safe in-memory user service, nullable request DTO validation, file uploads and an owned stream response. Data is lost when the process exits; these are learning examples.

## Run from the source checkout

Use JDK 17 and the Gradle 8.14.3 wrapper in this checkout. Library bytecode targets Java 8. From the NaruServe root, run one example using the released TinyHttpd JARs:

```bash
./gradlew -p examples/java -PtinyHttpdFromMaven=true run
# Alternatively, stop Java and run Kotlin on the same port:
./gradlew -p examples/kotlin -PtinyHttpdFromMaven=true run
```

Each example's `settings.gradle` includes the NaruServe root with `includeBuild('../..')`. Gradle substitutes the source modules for the Maven coordinates. The engine-selection properties reach the included source build. No local publication is required for this source path. To work with TinyHttpd source instead, replace `-PtinyHttpdFromMaven=true` with `-PtinyHttpdDir=/absolute/path/to/TinyHttpd`.

The default port is 3000 and the bind address is 127.0.0.1. To change port and upload directory:

```bash
./gradlew -p examples/java -PtinyHttpdFromMaven=true run --args='3001 /tmp/naruserve-uploads'
```

The app creates the upload directory at startup and fails early if it cannot create it. Paths are relative to the application's working directory unless absolute. Do not use client filenames as storage paths.

## Try the routes

```bash
curl http://127.0.0.1:3000/hello
curl 'http://127.0.0.1:3000/page?page=2'
curl -i -H 'Content-Type: application/json' -d '{"name":"Ada"}' http://127.0.0.1:3000/users
curl http://127.0.0.1:3000/users/1
curl -i -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:3000/users
curl -i -F note=missing-file http://127.0.0.1:3000/upload
curl -i -F file=@README.md http://127.0.0.1:3000/upload
curl http://127.0.0.1:3000/stream
```

`GET /hello` returns `Hello World`. Creating a user returns 201; read the returned ID for subsequent requests. Missing, null or blank names answer 400. JSON `null` also answers 400. `page` defaults to 1 only when absent; malformed numbers answer 400. Missing or empty uploads answer 400; valid uploads are saved under a server-generated name before the request ends. NaruServe takes ownership of the response stream and closes it after transmission: do not wrap it in Java try-with-resources or Kotlin `use` at the call site.

Kotlin's request DTO deliberately uses nullable properties and a no-argument constructor compatible with the default Unsafe-free Gson. An immutable data class without a no-argument constructor needs another explicitly configured codec; enabling Unsafe bypasses constructors and is not the solution.

## Separate consumer using Maven coordinates

For a separate consumer, copy the example sources and build file, and remove `includeBuild('../..')` from `settings.gradle`. Keep `mavenCentral()` and use `com.snoworca.naruserve:naruserve:0.10.0` for Java or `com.snoworca.naruserve:naruserve-kotlin:0.10.0` for Kotlin when those versions are available from Maven Central. Check the [installation guide](../README.md#install) for availability and module choices.

If the version does not resolve, publish this checkout locally from the NaruServe root:

```bash
./gradlew -PtinyHttpdFromMaven=true publishToMavenLocal
```

Then add `mavenLocal()` before `mavenCentral()` in the separate consumer. NaruServe publishes nine JAR modules and, when an Android SDK is present, the Android AAR. It does not publish TinyHttpd; the TinyHttpd 3.1.1 core and websocket dependencies resolve from Maven Central without a source checkout. See the [root README](../README.md#tinyhttpd) for the verified TinyHttpd commit and limitations.

## Troubleshooting

| Symptom | Cause and action |
|---|---|
| `tinyHttpdDir: TinyHttpd checkout not found` | Select binary dependencies with `-PtinyHttpdFromMaven=true`, or pass a valid TinyHttpd source directory. |
| The server cannot bind port 3000 | Stop the other example, or pass a different port with `--args` as shown above. |
| Another device cannot reach the server | The examples bind loopback only. Change the app's server configuration deliberately before testing over the LAN. |
| The upload directory cannot be created | Pass a writable absolute directory as the second application argument. |

See the [API guide](../docs/api/en/README.md) for the response, upload and lifecycle contracts.
