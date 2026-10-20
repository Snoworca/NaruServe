# API usability verification

Requirements: CON-DX-001, FR-DX-001 through FR-DX-011, OPS-DX-001, OPS-DX-002.

Epic: https://github.com/Snoworca/NaruServe/issues/21

## Issue evidence

| Issue | Requirement | RED before implementation | Implementation and verification |
|---|---|---|---|
| [#22](https://github.com/Snoworca/NaruServe/issues/22) | CON-DX-001 | CommonReusePolicyTest failed on absent reuse policy. | 통합 브랜치를 main에 병합하고 공통 코드 재사용 원칙 기록; regression passed before closure. |
| [#23](https://github.com/Snoworca/NaruServe/issues/23) | FR-DX-001 | Direct JUnit execution confirmed bare null produces standard 500 and typed lookup name is absent. | 속성 조회·저장 분리와 null 삭제 함정 제거; regression passed before closure. |
| [#24](https://github.com/Snoworca/NaruServe/issues/24) | FR-DX-002 | Desired Object and bare-null calls failed compilation against the old send overloads. | send 객체·목록·null 자동 응답 지원; regression passed before closure. |
| [#25](https://github.com/Snoworca/NaruServe/issues/25) | FR-DX-003 | Input convenience calls failed compilation before the API existed. | 필수값·숫자·업로드 입력 편의 함수 제공; regression passed before closure. |
| [#26](https://github.com/Snoworca/NaruServe/issues/26) | FR-DX-004 | Response helper calls failed compilation before implementation. | 리다이렉트와 상태·MIME 응답 편의 함수 제공; regression passed before closure. |
| [#27](https://github.com/Snoworca/NaruServe/issues/27) | FR-DX-005 | Cookie convenience calls failed compilation before implementation. | 쿠키 설정·옵션·삭제 편의 함수 제공; regression passed before closure. |
| [#28](https://github.com/Snoworca/NaruServe/issues/28) | FR-DX-006 | Real Gson generic tests failed for List and nested Map element types. | Kotlin body 제네릭 타입 보존; regression passed before closure. |
| [#29](https://github.com/Snoworca/NaruServe/issues/29) | FR-DX-007 | Three request-cache assertions failed before caching was implemented. | JSON 본문을 미들웨어와 핸들러에서 안전하게 재사용; regression passed before closure. |
| [#30](https://github.com/Snoworca/NaruServe/issues/30) | FR-DX-008 | Two end-to-end middleware chronology assertions failed. | 미들웨어를 전체 등록 순서로 실행; regression passed before closure. |
| [#31](https://github.com/Snoworca/NaruServe/issues/31) | FR-DX-009 | Group acceptance tests failed compilation before the API existed. | 라우트 그룹으로 중복 prefix 제거; regression passed before closure. |
| [#32](https://github.com/Snoworca/NaruServe/issues/32) | FR-DX-010 | CORS calls failed compilation; subsequent review tests failed for no-Origin Vary and error grant preservation. | CORS 허용 출처·preflight 편의 설정 제공; regression passed before closure. |
| [#33](https://github.com/Snoworca/NaruServe/issues/33) | OPS-DX-001 | Actual-source example contract failed with the Kotlin example entrypoint absent. | 실행 가능한 시작·DTO·업로드·스트림 예제 제공; regression passed before closure. |
| [#34](https://github.com/Snoworca/NaruServe/issues/34) | OPS-DX-002 | Documentation tests failed on old error-body claim and undecided convenience rows. | 오류·Origin·기본값 문서의 모순과 오해 제거; regression passed before closure. |
| [#35](https://github.com/Snoworca/NaruServe/issues/35) | FR-DX-011 | Four recursive unresolved-type assertions failed. | TypeRef 중첩 미확정 타입 변수를 생성 시 거부; regression passed before closure. |

## Review and platform verification

Independent API review identified and resolved two CORS integration defects: cache variation without Origin, and CORS grants on standard 400/401/500 error responses. No remaining HIGH or MEDIUM findings were reported. Shared validation and conversion live in the common package; cookie serialization, MIME values and status reason phrases reuse existing implementations.

Java and Kotlin standalone example builds passed. The examples are also compiled as actual source and exercised through real HTTP from a fresh upload directory. DTO null/missing validation, upload persistence and stream lifetime are verified. Android API 21 animal-sniffer and class-reference checks passed for the revised API.

## Compatibility

Typed attribute reads were renamed to attributeAs. send(String)/send(byte[]) were replaced by send(Object); direct implementations must migrate and consumers must recompile. Middleware chronology and repeated same-type JSON reads changed as documented in docs/api/express-migration.md.

## Existing project limitations

No Java 8 runtime or Android device run was added. TinyHttpd remains a separate checkout; the current checkout differs from the original documented verification commit, which the build warns about. Existing unrelated release vulnerability-scan pending state remains. SRS validation reports historical completed-work warnings and one broken trace on discarded OPS-HTTP-001; these predate this usability work.

## Final regression

`./gradlew build --console=plain`: BUILD SUCCESSFUL in 1m 48s (79 tasks, 30 executed and 49 up-to-date). Test-result XML totals: 1624 tests, 0 failures, 0 errors, 0 skipped. Standalone Java/Kotlin assemble and focused CORS platform/review checks also passed.
