# Maven Central 0.12.0 verification

Status: **PUBLISHED_AND_PUBLICLY_VERIFIED**. Central published deployment `f4fc0e76-0887-40d9-aaec-3def9a2d432e`; [the status receipt](0.12.0/central-published-status.json) preserves that observation.

| Item | Evidence and result |
|---|---|
| Release source | `20d8674affa4a9d35dbfd1b200857821b21a4789`, annotated tag `v0.12.0` (tag object `540d11b04ac418b0fc7e1b9da489b47c4bb89424`), both pushed to `origin` before the upload. The commits after the tested commit `09ed3f8` change only tests, release records and SRS documents, none of them a build input of a published artifact. |
| Tests | Java 17 and actual Java 8 (Zulu 8u504) full builds at `09ed3f887f850e845750dd31da1a6c8e8d132235`, 4,848 tests each, zero failures, errors or skips ([test results](../0.12.0-test-results.md)). |
| Staging | `checkCentralStaging` at the release source staged eleven modules: `naruserve`, `naruserve-android`, `naruserve-api`, `naruserve-filelog`, `naruserve-gson`, `naruserve-kotlin`, `naruserve-logcat`, `naruserve-proxy`, `naruserve-servlet`, `naruserve-slf4j`, `naruserve-tinyhttpd`. |
| Pre-upload absence | All eleven 0.12.0 POMs answered HTTP 404 on `repo1.maven.org` before the upload. |
| Signed bundle | `naruserve-0.12.0.zip`, 3,893,747 bytes, SHA-256 `c965a5eee00344e4f9584260617c0b242e1980906e5f810e4d00a51780468fca`: 55 primary artifacts, 55 detached signatures and 220 checksums (330 entries). Every signature was self-verified after signing; the [signed manifest](0.12.0/signed-manifest.json) records the SHA-256 and size of each primary artifact and the SHA-256 of its signature. |
| POM edges | `naruserve-proxy` depends on `naruserve-api` 0.12.0 alone. `naruserve-tinyhttpd` depends on `naruserve-api` 0.12.0 and, in runtime scope, on TinyHttpd 3.1.1 (`tinyhttpd-core`, `tinyhttpd-websocket`), `naruserve-gson` on Gson 2.14.0, `naruserve-kotlin` on Kotlin stdlib 2.2.0 and `naruserve-slf4j` on SLF4J API 1.7.36. No artifact holds a key, keystore or secrets file. The `naruserve-android` sources and javadoc JARs are the same 261-byte placeholder that holds only a manifest, and the AAR's `classes.jar` is empty, because the AAR only aggregates `naruserve` and `naruserve-logcat`. |
| Upload | One authenticated upload with automatic publication; Central reported `VALIDATING`, `PUBLISHING`, then `PUBLISHED`. |
| Public bytes | Checked 2026-11-26: all 110 public files (55 primaries and their 55 signatures) on `repo1.maven.org` are byte-identical to the signed manifest, and the published `.sha1` and `.md5` of every primary (another 110 files) match its bytes. |

The signing key is the one of 0.10.0 and 0.11.0 (fingerprint ending `F5B75792328DC16B`, no declared expiration, no revocation in the [committed public certificate](0.10.0/public-signing-key.asc); no keyserver was queried). No private key, passphrase or credential is part of this record.

## After publication

| Check | Evidence and result |
|---|---|
| Fresh public consumers | [Java 8, Kotlin and Android minSdk 21 consumers](0.12.0/public-consumers/) built with separate new, empty Gradle homes, `-PpublicConsumer=true --refresh-dependencies`, with no mavenLocal or staging repository in public mode; every NaruServe artifact was resolved from Maven Central (the Gradle cache records the Maven Central repository id, and the bytes equal the signed manifest). Java and Kotlin ran on actual Java 8u504 and passed 12 and 13 runtime checks, among them a real proxied GET and POST through `Proxy.to` to a second NaruServe app, `stripPrefix`, the forwarding headers, `req.bodyStream()` with `res.relay(...)` and a `502` after the backend stops. The Android release APK passed the manifest, service-provider, R8 and notice inspection, and holds `naruserve-proxy-0.12.0.jar` and the `Proxy` classes. All 18 resolved NaruServe artifacts (5, 6 and 7) equal the signed manifest. Proxy traffic did not run on Android. `inspect_android.py` resolves paths for the run layout `build/consumer-runs/android/app/...`, so it runs from a copy of the projects, not in place. |
| Independent publication review | One reviewer, read-only, round 1: PASS on all eight items. It downloaded the 55 primaries and their signatures, `.sha1` and `.md5` again (220 requests, 0 mismatches), verified all 55 signatures against the [committed public certificate](0.10.0/public-signing-key.asc) with Bouncy Castle and saw every one-bit tamper rejected (0 of 55 verified), checked the annotated tag on `origin`, the eleven artifactIds and POM edges, the absence of key material in all 33 archives (1,721 entries), the 24 GitHub release assets (11 artifacts and 11 signatures equal the manifest, 23 `SHA256SUMS` lines match), the consumer projects and proofs, and the bundle numbers (330 entries, 220 correct checksums). Its wording corrections are applied above. |
| GitHub release | [v0.12.0](https://github.com/Snoworca/NaruServe/releases/tag/v0.12.0) on the pushed tag, with the eleven main artifacts (ten JARs and the AAR), their signatures, `SHA256SUMS` and `LICENSE` (24 assets). Downloaded again: all 11 artifacts and 11 signatures equal the signed manifest, every `SHA256SUMS` line matches, and `LICENSE` equals the repository file. |

Not done: any Android device or emulator run, and rendered Kotlin or Android API documentation.

Public coordinates: `com.snoworca.naruserve:naruserve:0.12.0` and `com.snoworca.naruserve:naruserve-proxy:0.12.0`; [POM](https://repo.maven.apache.org/maven2/com/snoworca/naruserve/naruserve/0.12.0/naruserve-0.12.0.pom).
