# TinyHttpd 3.1.0 integration verification — 2026-10-24

## English

TinyHttpd source commit: `165f365`, tag `v3.1.0`. NaruServe pins `com.snoworca:tinyhttpd-core:3.1.0` and `com.snoworca:tinyhttpd-websocket:3.1.0`. Published 3.0.0 did not contain the ordered-header or transport-abort API and is not overwritten.

Requirements: IR-HTTP-003, FR-ENGINE-011, CON-BUILD-006, CON-BUILD-009, CON-BUILD-010, OPS-BUILD-001, OPS-BUILD-003, FR-REL-004, FR-REL-005, OPS-REL-005. TinyHttpd requirements: FR-HTTP-001, FR-HTTP-002, FR-HTTP-003, OPS-HTTP-001.

### TDD evidence

| Regression | RED | GREEN evidence |
|---|---|---|
| GET/HEAD abort | Old adapter manufactured a complete HEAD 500 response; new empty-wire assertion failed | `AbortSocketTest#FR_ENGINE_011_AC2_IR_HTTP_003_AC2_getAndHeadAbortSendNoResponse` |
| Blocked writer | Old adapter did not wake the socket writer before its timeout | `AbortSocketTest#IR_HTTP_003_AC2_abortUnblocksAStalledWriter`; positive progress, continuous native socket-write samples and no progress for 300 ms precede abort |
| Shrinking file | New empty-wire assertion failed against the old adapter's fabricated response | `SendFileSocketTest#sdsAc2_FR_ENGINE_011_AC2_fileShrinkingDuringTheCopyThrowsIOExceptionAndAborts` |
| Missing source checkout | New Maven-mode test failed while the old settings always required a checkout | `BuildChecksTest#OPS_BUILD_001_AC4_mavenModeDoesNotRequireSourceCheckout` |
| Nested Maven mode | Missing mode property and Git exit 128 reproduced with a nonexistent checkout | `BuildChecksTest#OPS_BUILD_001_AC4_nestedBuildsPreserveDependencyMode`; composite mode still checks source cleanliness |
| Pinned publication | POM/build assertions failed before version declarations were pinned | `PublishedPomTest`; `PublishedTinyHttpdCompatibilityTest` checks both core and websocket JAR origins |
| Writer / SSE cleanup in TinyHttpd | Independent review found buffered Writer acceptance and an SSE owner left waiting; each new test failed before its fix | `ResponseAbortTest#fr_HTTP_003_AC4_writerRejectsEveryWriteAndFlushAfterAbort`, `#fr_HTTP_003_AC6_abortClosesSseAndReleasesItsOwnerAndSlot`, plus TLS subclasses |

The asynchronous abort test now waits for the handler to observe its IOException before asserting the result. Socket closure can precede that handler observation. It asserts the exception type promised by the API rather than an implementation-specific message.

### Completed runs

- TinyHttpd: `python3 .publish/publish.py build` on JDK 17 — full build, 381 tests, zero failures/errors, 12 pre-existing skips; Android API 21 API checks and release packaging passed.
- TinyHttpd: response-abort and header-contract tests on the actual Java 8 runtime — 24 tests, zero failures/errors/skips.
- NaruServe: `./gradlew -PtinyHttpdFromMaven=true -PtinyHttpdDir=/tmp/tinyhttpd-checkout-does-not-exist :NaruServe-tinyhttpd:test` with the local staged Maven repository — 135 tests, zero failures/errors/skips. No source composite participated.
- NaruServe: selected root build, POM, license inventory and documentation checks passed in composite mode; Maven-mode checkout and nested-mode regressions passed.
- `./gradlew -Prelease releaseLicenses publishToMavenLocal` — passed; generated the 3.1.0 license inventory and published all nine NaruServe modules locally.
- Temporary consumer of `com.snoworca.naruserve:NaruServe-tinyhttpd:0.1.0-SNAPSHOT` from `mavenLocal()` — both source-composite and staged-binary runs passed ordered headers and GET/HEAD cancellation.

Two independent review agents inspected the TinyHttpd cancellation implementation and NaruServe integration. Writer and SSE findings were fixed with failing tests first and re-reviewed. The final NaruServe review covered all changed code/build/test files and independently ran 14 tests against the actual 3.1.0 JARs. No further confirmed runtime defect or test weakening remained.

The refreshed 3.1.0 vulnerability scan and release gate passed on 2026-10-24: High/Critical and unscored findings are zero; the existing Medium association remains visible. See [scan evidence](../audit-2026-10-24/release-check.log). Public Maven Central verification completed: all 20 artifacts, 20 signatures and 80 checksum files match staged output. See the [TinyHttpd publication receipt](https://github.com/Snoworca/TinyHttpd/blob/e638586/docs/release/3.1.0-verification.json).

Final builds passed:

```bash
# Source composite, JDK 17: 1,718 tests, no failures/errors/skips.
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew build

# Published Maven JARs, no source checkout, actual Java 8: 1,720 tests, no failures/errors/skips.
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew \
  -PtinyHttpdFromMaven=true -PtinyHttpdDir=/tmp/tinyhttpd-checkout-does-not-exist \
  build -I gradle/java8-tests.gradle \
  -Dnaruserve.java8.home=/Users/beom/Library/Java/JavaVirtualMachines/zulu8.96.0.205-ca-jdk8.0.504-macosx_aarch64/Contents/Home
```

The full forced Java 8 run exposed a versionless negative-test fixture: Maven resolution stopped before the intended dependency-policy violation. Pinning its two deliberately forbidden dependencies to 3.1.0 preserved both diagnostic assertions. The source-mode targeted test and complete Java 8 Maven-mode build then passed; the independent reviewer found no weakening.

The public-only temporary consumer compiled and passed both JAR-origin checks, ordered headers and GET/HEAD abort with no init script. The optional source consumer passed the same checks. Both English/Korean TinyHttpd guide programs also passed against the public 3.1.0 modules, including streaming, WebSocket, sessions and TLS. Physical Android verification remains separate.

SRS validation returned zero errors. The 22 existing SRS-W015 warnings concern other requirements in the read-only Completed Work Log; the nine requirements changed for this integration have per-criterion evidence and are verified.

## 한국어

기존 3.0.0의 누락 API와 HEAD 취소 문제를 실패 테스트로 재현한 뒤 수정했습니다. 독립 리뷰에서 추가로 발견한 Writer·SSE 종료 문제와 Maven 모드의 소스 디렉토리 의존도 같은 방식으로 해결하고 재검토했습니다. 실제 소켓 쓰기 정체를 확인한 후 취소 시간을 검사하며, core·websocket 모두 지정한 JAR에서 로드되는지 확인합니다. 위 표와 실행 기록이 요구사항별 검증 근거입니다. Android 실기기 검증과 과거 취약점 스캔의 범위는 별도로 유지합니다.
