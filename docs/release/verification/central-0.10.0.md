# Maven Central 0.10.0 prepublication verification

Status: **SIGNED_VERIFIED_NOT_UPLOADED**. This record supports **OPS-REL-009**; it does not establish Maven Central publication.

The frozen build source is `daf908cdc4468d67609f8e5cceaaad0174ce9a8b`. The signed `naruserve-0.10.0.zip` is 1,965,911 bytes with SHA-256 `50573182ec0efe671189969afa4c7a30ef5767b40d1c84875faf9a28cbe2a57c`. It contains ten modules, 50 primary artifacts, 50 detached signatures and 200 checksums (300 entries). The [signed manifest](0.10.0/signed-manifest.json) records every entry's size and hash. The bundle hash was recomputed before archiving this record.

| Gate | Evidence and result |
|---|---|
| Java 17 | [2,183 tests; zero failures, errors or skips](0.10.0/final-full-j17-counts.json). The corrected full build reran the root suite and reused unchanged module outputs; it was not a second uncached run. [Log](0.10.0/final-full-j17-corrected.log). |
| Actual Java 8 | [2,183 tests; zero failures, errors or skips](0.10.0/final-full-java8-counts.json), with all 186 tasks rerun through the official Java 8 test initialization script. API 21 and class-reference checks passed. [Log](0.10.0/final-full-java8.log). |
| Independent review | [Overall test sufficiency](0.10.0/overall-test-sufficiency-review-round1.json), [final integration](0.10.0/final-integrated-review-round1.json) and [final root documentation](0.10.0/root-docs-review-round2.json): PASS within their recorded scopes. Earlier reports retain their original source pins and then-pending gates. |
| Dependency scan | [PASS with declared scope limits](0.10.0/release-scan-review.json); no claim of authenticated OSS Index or Android device coverage. |
| Immutable staging consumers | [Java 8 and Kotlin actual HTTP/runtime consumers passed](0.10.0/staging-consumer-evidence.json). Android minSdk 21 minified APK, manifest, service, R8 and notice inspection passed; the APK is unsigned and no device/emulator run occurred. These consumers resolved private staged artifacts, not public Central artifacts. |
| Signed artifact audit | [Independent bundle review](0.10.0/signed-bundle-review.json) and [structural audit](0.10.0/signed-bundle-structural-audit.json): no findings. All 50 signatures verified, a tamper control was rejected, and all 200 checksums were recomputed. Sources/resources match the build commit; POM/module edges retain approved pins/scopes, including TinyHttpd 3.1.1. |

The signing certificate fingerprint is `DD2AFA2B544F2249C97984DBF5B75792328DC16B`; only the [public certificate](0.10.0/public-signing-key.asc) is archived. Certificate inspection and signature verification do not establish online revocation status. Kotlin and Android javadoc JARs are manifest-only under the existing build configuration; this record does not claim rendered Kotlin API documentation.

The [evidence manifest](0.10.0/evidence-manifest.json) records hashes for the archived evidence. Original local paths inside copied records remain provenance references. No private key, credential, release ZIP or consumer APK is included in this evidence directory.

Before completing OPS-REL-009, record the committed/pushed release source and tag, prove any later documentation-only commit leaves build inputs unchanged, upload the exact frozen bundle once, persist its deployment ID, obtain actual `PUBLISHED` status, and compare all public artifacts/signatures/checksums against the manifest. Then run fresh Central-only consumers without private staging, Maven Local or composite builds. No deployment ID, public-byte verification or public consumer success is claimed here.
