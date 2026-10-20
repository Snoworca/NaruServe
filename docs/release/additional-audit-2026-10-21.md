# Additional functional and test audit — 2026-10-21

## Current result

Eight of nine child issues are fixed and closed. Upstream issue #45 and epic #36 remain open. No external TinyHttpd source was changed without authorization.

- Default JDK17 and actual OpenJDK8 full regressions each passed **1,677 tests; failures=0, errors=0, skipped=0**. This adds 28 tests to the prior 1,649-test baseline.
- All 11 Gradle Test tasks, including isolated socket tests and root tests, actually ran on Java8. Gradle/compilation and nested TestKit builds retained JDK17.
- Fixed scope covers 17 requirements and 99 acceptance criteria with no source-test citation gaps. Citations support the actual runtime runs; they are not treated as proof by themselves.
- Real NVD-backed scanner, nine SBOMs, seven shipped/three provided license records and releaseCheck passed. High/Critical/unscored=0. Three Medium TinyHttpd associations remain visible; the exact Kotlin standard-library false positive remains narrowly scoped. Sonatype authenticated scanning/build-tool vulnerabilities are not claimed as covered.

[Java17 results](audit-2026-10-21/full17.json), [Java17 log](audit-2026-10-21/full17.log), [Java8 results](audit-2026-10-21/full8.json), [Java8 log](audit-2026-10-21/full8.log), [AC citations](audit-2026-10-21/test-sufficiency.json), [release gate](audit-2026-10-21/release-check.log), [raw scan](audit-2026-10-21/scan.csv).

## Audit scope

Three independent agents and the parent examined HTTP Request/Response and conveniences; routing/groups/prefix middleware; JSON/TypeRef/Gson/cookies; multipart/form/static/cache/ranges; services/lifecycle/logging; WebSocket/SSE limits/concurrency/shutdown; TinyHttpd/Servlet/Kotlin/bootstrap; and build/release tools. Concrete counterexamples were confirmed before issues were registered. Android device/emulator/R8 execution remains outside v1 by the prior user decision.

The investigation covered both under-tested valid inputs and invalid/boundary/cancellation cases. This finite audit does not establish that every possible input or scheduling interleaving is defect-free.

## Issues

| issue | state | evidence |
|---|---|---|
| [#37](https://github.com/Snoworca/NaruServe/issues/37) 불완전한 취약점 보고서와 잘못된 CVSS 점수의 릴리스 통과 방지 | closed | [37/evidence.json](audit-2026-10-21/37/evidence.json) |
| [#38](https://github.com/Snoworca/NaruServe/issues/38) Unicode 경로 대소문자 매칭과 중복 충돌 검사를 일치시키기 | closed | [38/evidence.json](audit-2026-10-21/38/evidence.json) |
| [#39](https://github.com/Snoworca/NaruServe/issues/39) 필수 입력 편의 함수에서 Unicode 공백만 있는 값 거부 | closed | [39/evidence.json](audit-2026-10-21/39/evidence.json) |
| [#40](https://github.com/Snoworca/NaruServe/issues/40) JSON Content-Type의 토큰과 따옴표 파라미터를 올바르게 해석 | closed | [40/evidence.json](audit-2026-10-21/40/evidence.json) |
| [#41](https://github.com/Snoworca/NaruServe/issues/41) Servlet SSE drain 시작 전 중단 시 세션과 종료 콜백 누수 방지 | closed | [41/evidence.json](audit-2026-10-21/41/evidence.json) |
| [#42](https://github.com/Snoworca/NaruServe/issues/42) 빈 WebSocket 메시지와 Ping으로 송신 큐가 무제한 증가하는 문제 방지 | closed | [42/evidence.json](audit-2026-10-21/42/evidence.json) |
| [#43](https://github.com/Snoworca/NaruServe/issues/43) multipart 종료 boundary 뒤 epilogue의 요청 크기 한도 우회 방지 | closed | [43/evidence.json](audit-2026-10-21/43/evidence.json) |
| [#44](https://github.com/Snoworca/NaruServe/issues/44) 정적 파일의 반복 조건부 헤더와 Range 필드를 일관되게 처리 | closed | [44/evidence.json](audit-2026-10-21/44/evidence.json) |
| [#45](https://github.com/Snoworca/NaruServe/issues/45) TinyHttpd의 요청 헤더 순서와 426 Upgrade 응답 헤더 보존 | open — external authorization pending | [GitHub blocker](https://github.com/Snoworca/NaruServe/issues/45) |

## TDD and independent review

Every fixed child has durable RED/GREEN17/GREEN8 logs. Review found an additional retained SSE queue and a malformed report line; both received new failing regressions and fixes. A quoted-empty CSV record exposed another boundary and #37 was reopened and reverified. The full regression caught a conflict between the original blanket SPI freeze and the explicitly added default cancellation hook; #41 was reopened and its exception was limited to that one empty default method, with negative guards rejecting unrelated/mandatory/behavioral SPI changes. Other SPI sources and the external engine cleanliness check remain protected.

The WebSocket limit now caps pending sender tasks at 1024 independently of payload bytes. The old 4,000-message/eight-producer ordering checks remain, with a 512-outstanding window respecting the new documented cap. Separate overflow and healthy-peer socket tests cover overload behavior.

## Remaining external blocker

Actual TinyHttpd socket tests reproduce interleaved header-order loss, and the pre-existing426 Connection: Upgrade gap remains. The supported upstream API does not expose ordered occurrences. IR-HTTP-003 AC-1 and FR-ENGINE-011 AC-7 were reset to blocked, and FR-WS-004 remains below verified.

CON-BUILD-010 requires the TinyHttpd checkout to stay read-only. That repository's AGENTS.md also forbids implementation before a target and matching SRS exist; its Active Target and requirements are currently empty. User input was requested to authorize a `naruserve-header-contracts` target/SRS plus external branch changes and remote integration. Reflection/raw-socket workarounds were not introduced, and neither #45 nor #36 is closed while this is outstanding.

The Java8 static symlink tests retain the documented `-Dsun.io.useCanonCaches=false` setting required by FR-STATIC-006 AC-6. The existing java.io descriptor/TOCTOU limitation and runtime guidance remain documented in `docs/api/static.md`; this audit does not claim those platform constraints were removed.
