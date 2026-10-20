# NaruServe 0.1.0-SNAPSHOT release notes

## Open items

- The vulnerability scan is pending: [vulnerability-scan.md](vulnerability-scan.md) records `Status: pending`, so the
  vulnerability part of T50 is not cleared.

## Post-v1 items

- `express.Router()` and router mounting: an open Express-compatibility row of `docs/api/express-migration.md`,
  left for after v1 because no wave of the v1 run owns it; `app.use("/api", mw)` with full route paths replaces it.
- `res.redirect(url)`: an open Express-compatibility row, left for after v1 because Express-style helpers are outside
  the v1 scope and no wave owns it; `res.status(302).header("Location", url).end()` replaces it.
- `cors()`: an open Express-compatibility row, left for after v1 because no wave owns a CORS middleware; a global
  `app.use` that sets the `Access-Control-Allow-*` headers replaces it.
- `res.type`: excluded from v1 because Express-style helpers are outside the v1 scope;
  `res.header("Content-Type", ...)` does the same.
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

TinyHttpd is not published by this repository. The POM of `NaruServe-tinyhttpd` names
`com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT` and `com.snoworca.tinyhttpd:tinyhttpd-websocket:0.1.0-SNAPSHOT`;
they resolve only from the consumer's own build of TinyHttpd. The one verified way is Gradle `includeBuild` of the
TinyHttpd checkout in the consumer's `settings.gradle`:

```groovy
includeBuild('/path/to/TinyHttpd')
```

NaruServe was verified against TinyHttpd commit `46b973e`.

Recorded consumer run: after `./gradlew publishToMavenLocal`, a Gradle build in a temporary directory declared
`mavenLocal()` and `implementation 'com.snoworca.naruserve:NaruServe-tinyhttpd:0.1.0-SNAPSHOT'` and resolved its
`runtimeClasspath`, once with `includeBuild('/Users/beom/work/git/TinyHttpd')` in `settings.gradle` and once without.
The TinyHttpd checkout was at `38051b6` during this run, not at the verified commit `46b973e`; the build warns about
that difference and does not fail. The consumer build was:

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

- consumer run: command=./gradlew -p <consumer dir> resolveRuntime (prints configurations.runtimeClasspath.files); date=2026-10-20; with includeBuild=resolved NaruServe-tinyhttpd, NaruServe-api, tinyhttpd-core and tinyhttpd-websocket 0.1.0-SNAPSHOT from the included build; without includeBuild=failed: Could not find com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT (and tinyhttpd-websocket)
