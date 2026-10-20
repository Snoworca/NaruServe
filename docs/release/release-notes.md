# NaruServe 0.1.0-SNAPSHOT release notes

## Open items

- The vulnerability scan is pending: [vulnerability-scan.md](vulnerability-scan.md) records `Status: pending`, so the
  vulnerability part of T50 is not cleared.

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
