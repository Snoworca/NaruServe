# Wave 8 pending checks (I-018, I-020)

Two release checks have not run, so the requirements that cover them stay `implemented` and are not promoted to `verified` (I-020: a requirement covering a manual-pending check MUST NOT be promoted). Their acceptance criteria test how the pending state is recorded, which passes; the underlying check is still open.

| requirement | check | why it is pending | what closes it |
|---|---|---|---|
| FR-REL-002 | T50 dependency vulnerability scan | `./gradlew -Prelease releaseScan` ended with `Status: pending, Reason: MISSING_API_KEY` because the build machine has no NVD API key (`docs/release/vulnerability-scan.md`) | run the scan with `NVD_API_KEY` set; a finished scan with a High or Critical finding blocks the release (D-377) until it is fixed or excepted |
| OPS-REL-004 | Java 8 runtime row of the platform matrix | no Java 8 runtime ran the suite; the build machine has only JDK 17 (`docs/release/release-notes.md`), and `--release 8` plus the animal-sniffer `android-api-level-21` check are the compile-time evidence | run `./gradlew build` on a Java 8 runtime and record the row |

Out of v1 scope by the user's decision (not pending): the Android sample build and API 21 device or emulator verification (T10, T12, T47 and the device parts of T26 and T49), which the release test results document lists as `out-of-v1-scope`.
