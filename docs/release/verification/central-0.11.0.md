# Maven Central 0.11.0 verification

Status: **PUBLISHED_AND_PUBLICLY_VERIFIED**. Central published deployment `2557e827-c60b-4b6a-9e92-551c5cab6bca`; [the status receipt](0.11.0/central-published-status.json) preserves that observation.

| Item | Evidence and result |
|---|---|
| Release source | `37ddee033e98b03bfe4dbaca94a0be0c8e581b2e`, annotated tag `v0.11.0`, both pushed to `origin` before the upload. The commits after the tested commit `b0511b3` change only release records, agent instructions and rule documents, none of them a build input. |
| Tests | Java 17 and actual Java 8 (Zulu 8u504) full builds at `b0511b3676fd79ccee6c98f80d4097de80ed9875`, 3,798 tests each, zero failures, errors or skips ([test results](../0.11.0-test-results.md)). |
| Staging | `checkCentralStaging` at the release source staged ten modules: `naruserve`, `naruserve-android`, `naruserve-api`, `naruserve-filelog`, `naruserve-gson`, `naruserve-kotlin`, `naruserve-logcat`, `naruserve-servlet`, `naruserve-slf4j`, `naruserve-tinyhttpd`. |
| Pre-upload absence | All ten 0.11.0 POMs answered HTTP 404 on `repo1.maven.org` before the upload. |
| Signed bundle | `naruserve-0.11.0.zip`, 3,615,895 bytes, SHA-256 `71f0f9d846dbeba488c082b32692005b5b256992d038ff5518e661088767b4be`: 50 primary artifacts, 50 detached signatures and 200 checksums (300 entries). Every signature was self-verified after signing; the [signed manifest](0.11.0/signed-manifest.json) records the SHA-256 of each primary artifact. |
| POM edges | `naruserve-tinyhttpd` depends on TinyHttpd 3.1.1, `naruserve-gson` on Gson 2.14.0, `naruserve-kotlin` on Kotlin stdlib 2.2.0 and `naruserve-slf4j` on SLF4J API 1.7.36. No artifact holds a key, keystore or secrets file. |
| Upload | One authenticated upload with automatic publication; Central reported `PUBLISHED`. |
| Public bytes | All 100 public files (50 primaries and their 50 signatures) on `repo1.maven.org` are byte-identical to the signed bundle. |

The signing key is the one of 0.10.0 (fingerprint ending `F5B75792328DC16B`). No private key, passphrase or credential is part of this record.

## After publication

| Check | Evidence and result |
|---|---|
| Fresh public consumers | [Java 8, Kotlin and Android minSdk 21 consumers](0.11.0/public-consumers/) built with separate empty Gradle homes, `-PpublicConsumer=true --refresh-dependencies`, Maven Central only for NaruServe. Java and Kotlin ran on actual Java 8u504 (6 and 7 runtime checks passed); the Android release APK passed the manifest, service-provider, R8 and notice inspection. All 15 resolved NaruServe artifacts (4, 5 and 6) equal the signed manifest. |
| Independent publication review | One reviewer, read-only, round 1: PASS on all eight items. It downloaded the 50 primaries again (0 mismatches), checked their published `.sha1` and `.md5`, matched the bundle, verified all 50 signatures against the [0.10.0 public certificate](0.10.0/public-signing-key.asc) with a tampered byte rejected, and checked the tag, the POM edges, the consumer proofs and the absence of key material. It noted that the `naruserve-android` sources and javadoc JARs are the same placeholder JAR. |
| GitHub release | [v0.11.0](https://github.com/Snoworca/NaruServe/releases/tag/v0.11.0) on the pushed tag, with the ten main artifacts, their signatures, `SHA256SUMS` and `LICENSE`; every artifact equals the signed manifest. |

Not done: any Android device or emulator run, and rendered Kotlin or Android API documentation.

Public coordinates: `com.snoworca.naruserve:naruserve:0.11.0`; [POM](https://repo.maven.apache.org/maven2/com/snoworca/naruserve/naruserve/0.11.0/naruserve-0.11.0.pom).
