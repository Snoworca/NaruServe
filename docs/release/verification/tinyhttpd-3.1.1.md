# TinyHttpd 3.1.1 integration verification

Requirements: FR-ENGINE-012, CON-BUILD-018. Recorded 2026-10-29.

Final 0.10.0 source integration: Java 17 and actual Java 8 each pass 2,183 tests with zero failures/errors/skips, including 218 TinyHttpd tests. The initial failures documented below are historical diagnostics resolved before this final run. See [current results](../0.10.0-test-results.md) and [release verification](central-0.10.0.md) for publication status.

Historical integration diagnostics: the bounded dependency/adapter checks below passed at `62f1e12`. Subsequent common-core integration tests at `8c5630a` passed four tests covering 27 socket requests, including typed framework decoding errors and exact scalar, wildcard, mount and query views. This completes the focused AC-2 observation. The subsequent full Tiny run reported 182 tests and 11 failures; WebSocket invalid-path status and explicit merge-slash policy are retained-contract regressions under repair. The other failures require explicit versioned-checkpoint review. This record does not establish a green integrated release.

## Scope and provenance

The existing `com.snoworca:tinyhttpd-core` and `com.snoworca:tinyhttpd-websocket` dependencies move from 3.1.0 to 3.1.1, both still implementation dependencies (runtime scope in the published adapter POM). No dependency is added; all other versions and scopes remain unchanged, including `javax.servlet-api:3.1.0`.

TinyHttpd tag `v3.1.1` resolves to commit `e73cd5d6b3f1ab6f4a7d301049eee26d22e15b3b`. Source verification used an isolated clone at that commit and did not change the shared TinyHttpd checkout. Maven-only runs set `tinyHttpdDir` to a nonexistent path. The published-binary test checks both loaded JAR origins and the public RAW policy API; the standalone upstream default remains STRICT.

| Published artifact | SHA-256 |
|---|---|
| tinyhttpd-core-3.1.1.jar | `53cdfdf16427d65ed50257d1f5d880ab6781160d2caf551b9c6150266971351c` |
| tinyhttpd-core-3.1.1.pom | `0aad68587e46dfd5b53baaff3c1b304d811e0472e8fdccc5d918e54418e0fe6c` |
| tinyhttpd-websocket-3.1.1.jar | `3634d26a4f18cada39135cb25f443d2bd27807f19955222b56d1f8309cba87b9` |
| tinyhttpd-websocket-3.1.1.pom | `ddd635d0067ee93b9c93a87fa37ae91d3c2321a7904cccf0ef6ba7d5ce9def30` |

The three `META-INF` license/notice files have identical contents in both published JARs. Android consumers retain the existing `packaging.resources.pickFirsts` configuration. `releaseLicenses` regenerated the license inventory successfully: seven shipped dependencies and three provided entries.

## Behavior verified

The adapter explicitly selects `RequestTargetPolicy.RAW`, reads `Request.getRequestURI()` and `getQueryString()`, and does not call the rejecting/normalizing `getPath()` accessor. Thirty-six actual socket cases cover encoded slash, NUL, dot segments, malformed escapes and UTF8, repeated slashes, double encoding, plus absent, empty and exact encoded/malformed queries. These adapter-only tests deliberately answer 200 from an SPI probe; they do not assert framework acceptance.

Request syntax, Host, conflicting framing and rejected-request pipeline isolation remain tested. Static traversal cannot reveal an outside sentinel. Invalid decoded WebSocket paths still receive 400, while a valid upgrade receives 101. The full module run also retains its existing security, WebSocket, SSE, shutdown and load regressions.

FR-ENGINE-012 AC-2's common-dispatcher integration remains a separate gate: malformed escapes must reach framework decoding and produce the framework's 400/error middleware result. This bounded run uses the earlier core and does not claim that gate complete. A new full-release vulnerability scan and release validation also remain pending; the dated 3.1.0 scan evidence has not been relabeled.

## TDD and verification

Tests-first commit: `ae40850`. Before implementation, Maven-only adapter tests reported 42 tests / 32 failures; build/POM/provenance tests reported 40 tests / eight failures. Independent test-sufficiency review passed round 1; no further sufficiency round ran.

| Check | Result | Local evidence |
|---|---|---|
| Published adapter RAW/published API tests, JDK 17 | 42 passed | `/tmp/naru-v010-tiny-raw-green.log`, matching `-results/` |
| Resolved dependency graph, POM scopes/versions, license inventory and version docs | 40 passed | `/tmp/naru-v010-tiny-pins-green.log`, matching `-results/` |
| Full published Tiny adapter, JDK 17 | 175 normal + 3 isolated passed | `/tmp/naru-v010-tiny-full-green.log`, matching `-results/` |
| Full published Tiny adapter, actual Zulu Java 8u504 | 175 normal + 3 isolated passed | `/tmp/naru-v010-tiny-java8-green.log`, matching `-results/` |
| Android API 21 animal-sniffer and class references | Passed in module check | `/tmp/naru-v010-tiny-full-green.log` |
| Source composite at the exact v3.1.1 commit | 40 RAW/guard tests passed | `/tmp/naru-v010-tiny-source-green.log`, matching `-results/` |
| Regenerated license inventory / dependency rules | Passed | `/tmp/naru-v010-tiny-licenses-green.log` |

Every listed test run has zero errors and skipped tests. Artifact/notice hashes are also recorded in `/tmp/naru-v010-tiny-published-provenance.json`; the exact two-pin build diff is recorded in `/tmp/naru-v010-tiny-pins-diff-proof.json`.

Run the published checks with JDK 17 and the existing Android SDK configuration:

```bash
./gradlew -PtinyHttpdFromMaven=true -PtinyHttpdDir=/tmp/nonexistent-tiny-checkout \
  :NaruServe-tinyhttpd:check
./gradlew -PtinyHttpdFromMaven=true -PtinyHttpdDir=/tmp/nonexistent-tiny-checkout \
  :test --tests '*BuildLayoutTest' --tests '*PublishedPomTest' --tests '*ThirdPartyLicensesTest'
./gradlew -PtinyHttpdFromMaven=true -PtinyHttpdDir=/tmp/nonexistent-tiny-checkout \
  -I gradle/java8-tests.gradle -Dnaruserve.java8.home=/path/to/jdk8 \
  :NaruServe-tinyhttpd:check
./gradlew -PtinyHttpdFromMaven=true -Prelease releaseLicenses
```

For the source check, point `tinyHttpdDir` at a clean checkout of tag `v3.1.1`:

```bash
./gradlew -PtinyHttpdDir=/path/to/TinyHttpd-v3.1.1 \
  :NaruServe-tinyhttpd:test --tests '*RawPolicy*'
```

한국어: 기존 TinyHttpd 두 의존성만 3.1.1로 올렸습니다. 앞선 코어를 사용한 범위 검증에서 RAW 전달, 보안·WebSocket, JDK 17·실제 Java 8·API 21 및 JAR/POM·라이선스 검사가 통과했습니다. 이후 공통 디스패처의 오류 미들웨어까지 확인하는 AC-2 검사도 4개 테스트·27개 요청으로 통과했습니다. 새 코어 전체 통합 검사에는 182개 중 11개 실패가 남아 있으며, WebSocket 오류 상태와 명시적 슬래시 병합 설정의 회귀를 수정 중입니다. 최종 릴리스 검증은 아직 완료되지 않았습니다.
