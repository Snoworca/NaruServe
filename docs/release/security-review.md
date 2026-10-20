# Runtime dependency false-positive review

Requirement: FR-REL-002 AC-4. Reviewed 2026-10-21.

The real OWASP Dependency-Check 13.0.0 scan identified CVE-2026-53914 against
`pkg:maven/org.jetbrains.kotlin/kotlin-stdlib@2.2.0`. This association is a false positive for this runtime
artifact. The scan result is retained; the repeatable scanner configuration suppresses only this exact
artifact/version and this exact CVE together in `gradle/dependency-check-suppressions.xml`.

## Affected code and inspected runtime artifact

The [GitHub reviewed advisory](https://github.com/advisories/GHSA-r937-wjx7-w2jp) identifies the affected Maven
package as `org.jetbrains.kotlin:kotlin-gradle-plugin`, before `2.4.20-Beta1`. Its description concerns unsafe
deserialization of build cache metadata. The
[JetBrains fix](https://github.com/JetBrains/kotlin/commit/bf51df665b458fda7c3eaf436c4d88dc119d7ec6) changes
`plugins/kapt/kapt-base/src/org/jetbrains/kotlin/kapt/base/incremental/cache.kt`: `JavaClassCacheManager` reads
`apt-cache.bin` and `java-cache.bin`, and the fix restricts the permitted deserialized classes.

Inspection of the actual cached `kotlin-stdlib-2.2.0.jar` found 1,033 ZIP entries. No class name or class-file
constant references `org/jetbrains/kotlin/kapt/base/incremental`, `JavaClassCacheManager`, `apt-cache.bin` or
`java-cache.bin`. SHA-256:

```text
65d12d85a3b865c160db9147851712a64b10dadd68b22eea22a95bf8a8670dca
```

The standard library does contain ordinary Java serialization hooks. This review does not claim that every
use of deserialization is absent. It establishes that the specific KAPT cache code implicated by this advisory
is absent from the shipped standard library.

The scanner maintainer's [discussion of this association](https://github.com/dependency-check/DependencyCheck/issues/8749)
explains that NVD's product-level Kotlin CPE attaches the issue to unrelated Kotlin artifacts and recommends
managing a CVE-specific suppression. Its broad example is deliberately not used here.

## Build tooling is a separate scope

This repository uses Kotlin Gradle plugin 2.2.0, which is within the advisory's affected version range.
The standard-library suppression does not match `kotlin-gradle-plugin`, any other artifact or version, or any
other vulnerability. It is not a claim that the build plugin is patched.

The current build does not apply KAPT or declare annotation processors or remote build caches. Inspection of
the cached Kotlin 2.2.0 JARs found no `JavaClassCacheManager` class. These observations support the absence of
the specific vulnerable KAPT execution path in the current build; they do not remove the build tooling risk
when configuration changes. Do not consume untrusted KAPT/build cache entries. Before enabling KAPT or accepting
such cache inputs, update and revalidate the affected build tooling. The release scan covers published module
runtime dependencies, as FR-REL-002 specifies.

## Other findings remain visible

CVE-2002-1819 findings associated with TinyHttpd artifacts are not suppressed. No generic Kotlin CPE,
product-wide rule, CVE-only rule, regex package URL or risk-acceptance exception is introduced. Tests verify
the exact two selectors and reject broad or extra selectors. The release gate continues to evaluate all
unsuppressed findings.

## Reproducible NVD data source

A keyless NVD REST update failed in Dependency-Check 13.0.0 with `Invalid API Key, length of 0`.
[Upstream issue 8715](https://github.com/dependency-check/DependencyCheck/issues/8715) records that regression.
The [official Dependency-Check mirror documentation](https://dependency-check.github.io/DependencyCheck/data/mirrornvd.html)
provides a JSON 2.0 mirror. Select it without a user-home init script:

```sh
./gradlew -Prelease '-PnvdDatafeedUrl=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz' releaseScan
```

The property is forwarded to the child scanner. The mirror is updated daily on a best-effort basis; record
its actual metadata timestamp with the scan rather than treating the scan execution time as data freshness.
A scanner failure remains pending and cannot establish release clearance.

The recorded scanner log reports that the optional Sonatype OSS Index analyzer was disabled because its
credentials were absent. The completed result and release clearance refer to the configured NVD-backed
runtime scan; they do not claim a Sonatype authenticated scan or a scan of build-tool dependencies.
Raw findings before and after the exact suppression are retained in
[verification/scan-before-suppression.csv](verification/scan-before-suppression.csv) and
[verification/scan.csv](verification/scan.csv).
