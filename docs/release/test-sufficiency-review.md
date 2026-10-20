# Test sufficiency and open-issue closure review

Requirements: CON-DX-001, FR-DX-001 through FR-DX-011, OPS-DX-001, OPS-DX-002; original issue-linked SRS scopes under docs/spec.

## Result

`./gradlew build --console=plain` passed after reinforcement: **1,643 tests, zero failures/errors/skips** (35 s, 79 tasks: 22 executed and 57 up-to-date). The only later test-source change adds an IR-KT-002 AC-3 citation to existing Kotlin tests; the focused real-Gson suite passed again. No production code changed during this review.

Nineteen new tests reinforce actual WebSocket store semantics (2), response/cookie boundaries (4), integer and required-input boundaries (2), real TinyHttpd/bootstrap send/CORS/Gson-body-cache behavior (5), and real Jetty Servlet CORS behavior (6). Test-only HTTP helpers are reused under a common package.

## Automated acceptance trace audit

| Issue group | Requirements | ACs | Missing citations |
|---|---:|---:|---:|
| #1–#7 | 91 | 390 | 0 |
| #8–#14 | 66 | 402 | 0 |
| #15, #16, #19 | 25 | 132 | 0 |

After deduplication these cover **181 requirements and 917 ACs**. MCP check_test_sufficiency scanned only `**/src/test/**/*.java` and `**/src/test/**/*.kt`; generated classes, XML, HTML and binary reports were excluded. Citations alone are not behavioral proof: the independent reviewers also inspected assertion meaning, real transport use and unresolved release conditions, and the complete suite was run.

The API-usability target has 52 ACs, 50 with direct source-test citations. CON-DX-001 AC-1 (Git integration provenance) and AC-3 (unrelated local files preserved) are manual repository-state checks: `git merge-base --is-ancestor 54a92fd main` succeeds; the pre-existing untracked workflow files stay untracked and no diff from the integration baseline includes them. These two conditions are not misrepresented as automated runtime tests.

## Closure decisions

| Issue | Decision | Reason |
|---|---|---|
| [#1](https://github.com/Snoworca/NaruServe/issues/1) [기반] 모듈 구성·TinyHttpd 연결·테스트 기반 | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#2](https://github.com/Snoworca/NaruServe/issues/2) [P0] Method · MethodSet | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#3](https://github.com/Snoworca/NaruServe/issues/3) [P0] Router: 경로 패턴·우선순위·자동 응답 | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#4](https://github.com/Snoworca/NaruServe/issues/4) [P0] Request · Response 계약과 HttpAdapter SPI | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#5](https://github.com/Snoworca/NaruServe/issues/5) [P0] RestApp 조립 · Middleware 체인 · onError · 표준 오류 JSON | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#6](https://github.com/Snoworca/NaruServe/issues/6) [P0] ApplicationContext · ServiceRegistry · Lifecycle | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#7](https://github.com/Snoworca/NaruServe/issues/7) [P0] Logging SPI · AccessLog | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#8](https://github.com/Snoworca/NaruServe/issues/8) [P1] TinyHttpd HttpAdapter · RestApp.create() | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#9](https://github.com/Snoworca/NaruServe/issues/9) [P1] JSON: JsonCodec · TypeRef · Gson 2.14.0 | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#10](https://github.com/Snoworca/NaruServe/issues/10) [P1] Cookie · SameSite | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#11](https://github.com/Snoworca/NaruServe/issues/11) [P2] Multipart 파일 업로드 | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#12](https://github.com/Snoworca/NaruServe/issues/12) [P2] 정적 파일 · MIME · CachePolicy · Range | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#13](https://github.com/Snoworca/NaruServe/issues/13) [P3] WebSocket | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#14](https://github.com/Snoworca/NaruServe/issues/14) [P3] SSE | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#15](https://github.com/Snoworca/NaruServe/issues/15) [P3] 장기 연결 종료 · backpressure · 부하 | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#16](https://github.com/Snoworca/NaruServe/issues/16) [P4] Kotlin 호환 · NaruServe-kotlin | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#17](https://github.com/Snoworca/NaruServe/issues/17) [P4] Android API 21 샘플 앱 · 실기기 QA · R8 | not_planned | Explicit 2026-10-20 user decision removed Android sample/device/R8 QA from v1. This closure does not claim those tests ran. |
| [#18](https://github.com/Snoworca/NaruServe/issues/18) [P4] 릴리스 준비: SBOM · 라이선스 · 문서 정리 | keep open | FR-REL-002 and T50 vulnerability check remain pending: no NVD_API_KEY is configured; no completed dependency vulnerability scan proves the release gate. Java8 runtime matrix remains pending. |
| [#19](https://github.com/Snoworca/NaruServe/issues/19) [P5 선택] Servlet 어댑터 (javax, Servlet 3.1+) | completed after sufficient tests | Current SRS ACs and source tests reviewed; reinforced whole-suite regression passed. |
| [#20](https://github.com/Snoworca/NaruServe/issues/20) [Epic] NaruServe v1: docs/api 계약 구현 | keep open | Release gate and child #18 remain unresolved. Compile release8/API21 signatures do not prove Java8 runtime or Android execution. |

## Added behavioral evidence

- `NaruServe-api/src/test/java/com/snoworca/naruserve/AttributeStoreContractTest.java`: actual Request/Application/WebSocket attributes, bare-null/removal/missing/wrong-type/primitive reads.
- `NaruServe-api/src/test/java/com/snoworca/naruserve/ResponseBoundaryTest.java`: Location and sendStatus invalid-argument atomicity, detached cookie options and omitted Path/Domain.
- `NaruServe-api/src/test/java/com/snoworca/naruserve/InputBoundaryTest.java`: min/max integers, both overflow directions, signs, whitespace, default-only-on-absence and required-header/form blanks.
- `src/test/java/com/snoworca/naruserve/docs/RuntimeHttpUsabilityTest.java`: actual bootstrap HTTP runtime-type send matrix, payload/content type/status, CORS grants/preflight/Vary/error responses and partial-codec failure.
- `src/test/java/com/snoworca/naruserve/docs/RealGsonBodyCacheTest.java`: real Gson decoding over actual HTTP, equal DTO/list/nested/null cache identity, one decode, incompatible type/format and parsing failures/limits.
- `NaruServe-servlet/src/test/java/com/snoworca/naruserve/servlet/CorsServletTest.java`: actual Jetty allowed/denied/preflight/auth400/401/500, Vary, metadata isolation, configuration validation and unsupported WebSocket.

## Limits

No statement of line/branch coverage percentage or mutation-test completeness is made. The check establishes requirement traceability plus inspected scenario coverage, not absence of all possible bugs. No Java8 runtime or Android device run was fabricated. Vulnerability-scan release conditions remain open. Existing historical completed-work-log warnings and the discarded OPS-HTTP-001 broken trace predate this audit.
