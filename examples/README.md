# Runnable Java and Kotlin examples

Both applications use the default Gson codec and TinyHttpd. They include full imports and entrypoints, a thread-safe in-memory user service, nullable request DTO validation, file uploads and an owned stream response. Data is lost when the process exits; these are learning examples.

## Run from the source checkout

Requires a JDK (the build compiles Java 8 bytecode), this checkout and the TinyHttpd checkout. From the NaruServe root:

```sh
./gradlew -p examples/java -PtinyHttpdDir=/absolute/path/to/TinyHttpd run
# Alternatively, stop Java and run Kotlin on the same port:
./gradlew -p examples/kotlin -PtinyHttpdDir=/absolute/path/to/TinyHttpd run
```

Each example's `settings.gradle` includes the NaruServe root with `includeBuild('../..')`. Gradle substitutes the source modules for the Maven coordinates. `tinyHttpdDir` is forwarded as a Gradle project property to the included source build. No local publication is required for this source path.

The default port is 3000 and the bind address is 127.0.0.1. To change port and upload directory:

```sh
./gradlew -p examples/java -PtinyHttpdDir=/absolute/path/to/TinyHttpd run --args='3001 /tmp/naruserve-uploads'
```

The app creates the upload directory at startup and fails early if it cannot create it. Paths are relative to the application's working directory unless absolute. Do not use client filenames as storage paths.

## Try the routes

```sh
curl http://127.0.0.1:3000/hello
curl 'http://127.0.0.1:3000/page?page=2'
curl -i -H 'Content-Type: application/json' -d '{"name":"Ada"}' http://127.0.0.1:3000/users
curl http://127.0.0.1:3000/users/1
curl -i -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:3000/users
curl -i -F note=missing-file http://127.0.0.1:3000/upload
curl -i -F file=@README.md http://127.0.0.1:3000/upload
curl http://127.0.0.1:3000/stream
```

Missing, null or blank names answer 400. JSON `null` also answers 400. `page` defaults to 1 only when absent; malformed numbers answer 400. Missing or empty uploads answer 400; valid uploads are saved under a server-generated name before the request ends. NaruServe takes ownership of the response stream and closes it after transmission: do not wrap it in Java try-with-resources or Kotlin `use` at the call site.

Kotlin's request DTO deliberately uses nullable properties and a no-argument constructor compatible with the default Unsafe-free Gson. An immutable data class without a no-argument constructor needs another explicitly configured codec; enabling Unsafe bypasses constructors and is not the solution.

## Separate consumer using locally published artifacts

This repository does not publish artifacts to Maven Central. From the NaruServe root, run `./gradlew -PtinyHttpdDir=/absolute/path/to/TinyHttpd publishToMavenLocal`. In a separate consumer, copy the example sources and build file, add `mavenLocal()` alongside `mavenCentral()`, and replace `includeBuild('../..')` in settings with `includeBuild('/absolute/path/to/TinyHttpd')`. NaruServe publishes its nine modules; it does not publish TinyHttpd. The TinyHttpd included build supplies `tinyhttpd-core` and `tinyhttpd-websocket` dependencies. See the root README for the verified TinyHttpd commit and limitations.
