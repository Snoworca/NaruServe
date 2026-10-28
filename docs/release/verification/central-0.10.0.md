# Maven Central 0.10.0 verification

Status: **PUBLISHED_AND_PUBLICLY_VERIFIED**. This record supports **OPS-REL-009**. Central published deployment `402c85ed-99e2-4dad-bd20-311b880eb973`; [the explicit status receipt](0.10.0/central-published-status.json) preserves that observation. All public artifacts and all three fresh Central-only consumers passed verification.

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

Release source `ad1a40df235b68509d8dcf42b8fa89f1fab6104f` and annotated tag `v0.10.0` were pushed before the one upload. [Source traceability](0.10.0/source-traceability.json) confirms every artifact build input is unchanged from the frozen build source. [Upload helper evidence](0.10.0/central-helper-evidence.json) records durable upload-once handling and pre-upload absence checks. [Actual publication evidence](0.10.0/central-publication-evidence.json) records the one authenticated upload and completed public verification.

## Public Maven verification

Central reported `PUBLISHED` at `2026-10-28T21:38:35Z`. [The public verification manifest](0.10.0/public-verification.json) records all 300 public files: 50 primary artifacts, 50 detached signatures and 200 checksums. Every file is byte-identical to the frozen signed bundle; all checksum values were recomputed. Public-certificate-only signature verification passed for all 50 primaries and rejected tampered bytes.

[Fresh public consumers](0.10.0/public-consumers/public-consumer-evidence.json) all pass after publication: Java actual 8u504 HTTP/runtime, Kotlin 2.2.0 strict JSpecify/actual Java 8 runtime, and Android minSdk 21 clean R8 APK build/static inspection. Each uses a separate fresh Gradle home and `publicConsumer=true --refresh-dependencies`, excluding private staging, Maven Local and composite builds. All 15 resolved project-artifact links match the signed manifest. [The consumer evidence manifest](0.10.0/public-consumers/evidence-manifest.json) hashes 40 archived public-safe proof files. No Android device/emulator execution is claimed.

Public coordinates: `com.snoworca.naruserve:naruserve:0.10.0`; [POM](https://repo.maven.apache.org/maven2/com/snoworca/naruserve/naruserve/0.10.0/naruserve-0.10.0.pom), [JAR](https://repo.maven.apache.org/maven2/com/snoworca/naruserve/naruserve/0.10.0/naruserve-0.10.0.jar). The same publication covers all ten modules. Existing 0.9.0 and upstream TinyHttpd 3.1.0 were not overwritten.

## Requirements and historical evidence

The [contract matrix](../../plan/0.10.0-contract-matrix.md) links approved old/new common HTTP requirements and retained profiles. Requirement-specific tests, reviews and acceptance checks remain authoritative under `docs/spec/`. SRS validation has zero errors; 22 pre-existing SRS-W015 historical completed-log warnings remain unchanged. They are not missing verification evidence for target 0.10.0.

Earlier archived reports retain their original prepublication statuses and local paths as historical observations. The current receipt, public-byte manifest and fresh-consumer record above establish delivery. The signed manifest still records the frozen pre-upload state of the immutable bundle.

Final [independent publication review, round 1](0.10.0/final-publication-review-round1.json): PASS. The reviewer independently downloaded all 300 public files again, checked all fresh-consumer proofs and verified source/tag identity. The review stopped at its first PASS.
