# 0.11.0 후속 항목 적용 결과

세 평가 문서(`followup-1-capacitor-cors.md`, `followup-2-apikey-revoke-audit.md`, `followup-3-stale-requirement-text.md`)의 권장 해결안을 적용했습니다. 커밋은 하지 않았습니다.

## 결과 요약

| 항목 | 값 |
|---|---|
| 빌드 | `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline` 종료 코드 0 |
| 테스트 합계 | 3784건(기준 3775 + 9), 실패 0, 오류 0, 건너뜀 0, 결과 파일 485개 |
| `speckiwi coverage --tests --target 0.11.0 --json` | `passed: true`, 요구 192건, 빈틈 0 |
| `validate_spec` | 오류 0, 경고 47(전부 SRS-W015, 작업 전과 같음) |
| 요구 상태 | 일곱 요구 모두 `verified`. 각각 `in_progress` → `implemented` → `verified`로 한 건씩 올렸습니다 |

위 실행은 코드와 문서가 끝난 뒤, 상태를 되돌리기 전에 돌렸습니다. 그 뒤의 변경은 `docs/spec` 안의 증거 행과 상태뿐입니다.

## 항목 1: Capacitor와 CORS

**코드**

- `CorsValues.isOrigin`: 스킴 조건을 `uri.getScheme() != null`로 넓혔습니다. host, userinfo, 경로, 쿼리, 조각, 포트 조건은 그대로입니다.
- `DefaultCorsOptions` 오류 메시지와 `CorsOptions` Javadoc을 `scheme://host[:port]`로 고쳤습니다.
- `trustedOrigins`는 바꾸지 않았습니다. http(s)만 받습니다.

**문서(ko·en)**

- `docs/api/middleware.md`: 출처 형식과 `*`의 범위를 고쳤습니다.
- `docs/api/auth.md`: 불가 문장을 지우고 iOS `capacitor://localhost`, Android 기본 `https://localhost`, 옛 앱 `http://localhost`, 앱 신원이 아니라는 점, `trustedOrigins`에 넣을 수 없다는 점, CapacitorHttp 안내를 적었습니다. Java·Kotlin 예제의 목록도 고쳤습니다.

**요구 문구**

FR-DX-010 AC-7(추가):

- 옛 문구: 없음
- 새 문구: An origin of the form scheme://host[:port] can be listed in allowedOrigins whatever its scheme (for example capacitor://localhost and ionic://localhost), and * grants every such origin; a value with a path, userinfo, query or fragment, or without a host (capacitor://localhost/, capacitor://u@localhost, capacitor:x, file://), is refused at configuration and gets no grant on a request.

OPS-FLOW-001 AC-3 마지막 절:

- 옛 문구: and that a non-http(s) origin such as Capacitor's capacitor://localhost cannot be listed in CorsOptions.allowedOrigins.
- 새 문구: and that a custom-scheme origin such as Capacitor's capacitor://localhost can be listed in CorsOptions.allowedOrigins but not in trustedOrigins.

FR-FLOW-002 AC-5:

- 옛 문구: app.cors allowedOrigins holding https://spa.example and http://localhost: ... the same login with Origin http://localhost (in allowedOrigins, not in trustedOrigins) or Origin capacitor://localhost (not in trustedOrigins and not listable in allowedOrigins, OPS-FLOW-001 AC-3) -> 200 body delivery ... Decision (2026-11-05): CorsOptions.allowedOrigins takes http(s) origins only, so capacitor://localhost cannot be listed there (OPS-FLOW-001 AC-3); the delivery rule reads trustedOrigins and not allowedOrigins, so that origin gets body delivery and a trusted http(s) origin gets cookies.
- 새 문구: app.cors allowedOrigins holding https://spa.example, http://localhost and capacitor://localhost: ... the same login with Origin http://localhost or Origin capacitor://localhost (in allowedOrigins, not in trustedOrigins) -> 200 body delivery with accessToken and no Set-Cookie (...). Decision 문장은 지웠습니다.

**빨강 증거(구현 전 실행)**

| 테스트 | 실패 |
|---|---|
| `CorsTest#listedCustomSchemeOriginsGetPreflightRequestAndErrorGrants` | `IllegalArgumentException` at `CorsTest.java:112` |
| `CorsTest#unlistedCustomSchemeOriginIsDenied` | `IllegalArgumentException` at `CorsTest.java:130` |
| `CorsTest#wildcardGrantsCustomSchemeOrigins` | `AssertionError` at `CorsTest.java:148` |
| `Wave8LoginTest#FR_FLOW_002_AC5_trustedOriginBesideCors` | `IllegalArgumentException` at `Wave8LoginTest.java:1164` |
| `Wave8LoginTest#OPS_FLOW_001_AC3_capacitorIsListedAndLogsInThroughPreflight` | `IllegalArgumentException` at `Wave8LoginTest.java:1192` |
| `Wave7DocsTest#OPS_FLOW_001_AC3_customSchemeOriginsGoInAllowedOriginsOnly` | `AssertionError`(문서에 문장 없음) |
| `Wave7DocsTest#FR_DX_010_AC7_middlewarePageStatesTheOriginForm` | `AssertionError`(문서에 문장 없음) |

`CorsTest#malformedCustomSchemeValuesAreRefusedAndGetNoGrant`와 `Wave8LoginTest#OPS_FLOW_001_AC3_capacitorIsRefusedByTrustedOrigins`는 구현 전에도 통과했습니다. 이미 맞는 동작을 고정하는 테스트입니다.

**바뀐 요구가 대체한 테스트(둘 다 예전 동작을 고정했습니다)**

- `Wave8LoginTest#OPS_FLOW_001_AC3_capacitorIsRefusedByAllowedOrigins` → `OPS_FLOW_001_AC3_capacitorIsListedAndLogsInThroughPreflight`
- `Wave7DocsTest#OPS_FLOW_001_AC3_capacitorCannotBeListed` → `OPS_FLOW_001_AC3_customSchemeOriginsGoInAllowedOriginsOnly`

## 항목 2: API 키 철회 감사

**코드**

- `DefaultAuthApiKeys.revoke`: 지우기 전에 `rowBeforeRevoke`로 키를 읽습니다. 읽기 실패는 삼키고 철회를 계속합니다. 이벤트에 소유자를 `targetId`로, 기록된 발급자를 `issuedByUserId`로 넣습니다.
- `revokedThrough`: `issuedBy` 인자를 받습니다. `AdminEndpoints.deleteKey`는 이미 읽은 행의 값을 넘기고, `AccountEndpoints.deleteOwnKey`는 지우기 전에 같은 best-effort 읽기를 합니다.
- 공개 API와 SPI는 바뀌지 않았습니다.

**문서:** `docs/api/auth.md`, `docs/api/en/auth.md`의 1994행 부근 문단(행 번호는 DEV 표 추가로 밀렸습니다).

**요구 문구(FR-AUTH-009 AC-2)**

- 옛 문구: ... API key issue (carrying the key id, and issuedByUserId when a key endpoint issued the key; auth.apiKeys().issue has no issuer) and API key revoke (carrying the revoked key id). Every audit event ... Decision (2026-11-05): issuedByUserId is recorded on the key issue event only, as the key issuance steps of docs/api/auth.md and 00 §6.4 row "R47 × 발급자 기록" state; revoke events carry the key id as docs/api/auth.md states near line 1994, and the issuer stays on the key row until the revoke.
- 새 문구: ... API key issue and API key revoke. Issue and revoke events carry the key id and issuedByUserId (a key with no recorded issuer, such as one from auth.apiKeys().issue, omits it), and a revoke event names the key's owner as target. auth.apiKeys().revoke and DELETE /auth/me/apikeys/:id read the key row best effort before they delete: when that read fails, the revoke still succeeds and its event has no issuedByUserId, and the event of auth.apiKeys().revoke has no target either. Every audit event ... (뒷부분은 그대로이고 Decision 문장은 지웠습니다)

**빨강 증거**

| 테스트 | 실패 |
|---|---|
| `Wave10PatTest#FR_AUTH_009_AC2_issueAndRevokeEventsCarryTheKeyId` | `AssertionError` at `Wave10PatTest.java:1423`(앱 호출 `targetId` null) |
| `Wave10PatTest#FR_AUTH_009_AC2_revokeEventsCarryTheRecordedIssuer` | `AssertionError` at `Wave10PatTest.java:1451` |
| `Wave12ApiKeyTest#FR_AUTH_009_AC2_anUnreadableKeyIsStillRevoked` | `AssertionError` at `Wave12ApiKeyTest.java:257` |

`Wave10PatTest#FR_APIKEY_004_AC6_revokesThroughTheEndpointsAreAudited`의 대상 단언 추가는 공백을 메운 것으로 처음부터 통과했습니다. 약화하거나 지운 단언은 없습니다.

## 항목 3: 낡은 요구 문구

supersede는 쓰지 않았습니다. 세 요구 모두 ID와 AC 번호를 유지했습니다.

**SEC-AUTH-002**

- Requirement 옛 문구: AuthMode.fromEnv() shall return DEV only when NARU_ENV is dev, and DEV shall relax exactly three things: temporary key for a missing secret, the R33 secure-transport check, and the hash-cost doctor FAIL and the key-strength rejection (SEC-AUTH-001) downgraded to WARN.
- Requirement 새 문구: ... only when NARU_ENV is dev in any letter case, and DEV shall relax exactly this closed list of eight things: (1) a temporary key for a missing secret (SEC-AUTH-001 AC-5); (2) the R33 secure-transport check (AC-2); (3) the hash-cost doctor FAIL and the key-strength rejection (SEC-AUTH-001) downgraded to WARN (AC-3); (4) a .user(...) password that breaks the password policy, which creates the account with a WARN (FR-AUTH-004 AC-7, OPS-AUTH-017 AC-3); (5) a password-less admin .user(...), which gets a temporary password written to the log (FR-AUTH-004 AC-6); (6) the build() refusal of a file store without a file key downgraded to WARN (FR-IAM-015 AC-4); (7) no doctor FAIL for legacy trustProxy(true) (OPS-AUTH-001 AC-3, 00 R32); (8) no final auth error layer, so an app exception no earlier layer answered goes on to Core (OPS-AUTH-033 AC-1).
- AC-6(추가): The AuthMode type Javadoc and the AuthMode.DEV row and DEV table of docs/api/auth.md and docs/api/en/auth.md state the same closed list of eight relaxations as the Requirement, and none of them states a smaller count.
- `AuthMode` Javadoc과 두 가이드의 `AuthMode.DEV` 행, DEV 표를 고쳤습니다. 표 아래 문장은 "auth 자신의 JSON 오류 본문"으로 좁혔습니다.
- 낡은 Trace 행(`AuthMode.java:L1-L14`, `fromEnv() withheld`)을 지웠습니다.
- 빨강 증거: `AuthGuideRound6DocsTest#SEC_AUTH_002_AC6_javadocAndGuidesStateTheSameDevList`, `AssertionError` at `AuthGuideRound6DocsTest.java:83`(Javadoc의 "exactly three").

**평가 문서와 다른 점: 일곱이 아니라 여덟입니다.** 평가는 DEV 분기를 다시 훑으라고 했고 `prodFinalError`는 판단하지 못했다고 적었습니다. `Auth.java:662`와 OPS-AUTH-033 AC-1("DEV appends no such layer")을 확인해 여덟째로 넣었습니다. 일곱으로 두면 닫힌 목록이 또 거짓이 됩니다. 다시 훑은 나머지 DEV 분기는 넣지 않았습니다. `Auth.java:1096`(열린 가입 doctor WARN이 PROD에서만 나옴)은 규칙이 아니라 진단 메시지이고, `Auth.java:1875`와 `AccountEndpoints.java:319`는 DEV에서 안내 WARN을 더하는 쪽입니다.

**FR-AUTH-005**

- Requirement 끝 옛 문구: Whether the admin mount follows a configured mount path is not fixed by 00 and is left open.
- Requirement 끝 새 문구: The admin mount follows a configured mount path, at the mount path plus /admin.
- AC-8 끝 옛 문구: Where auth.adminRoutes() is mounted in this configuration is not asserted (open: 00 does not say).
- AC-8 끝 새 문구: auth.adminRoutes() follows the mount path, at the mount path plus /admin: /api/auth/admin in this configuration.
- `RealJwtPathTest#SEC_AUTH_006_AC1_protectOnlyKeepsTheAuthMountsInRange`를 AC-8 증거로 더했습니다. 새 테스트는 없습니다. 이미 코드가 그렇게 동작하므로 빨강 단계가 없습니다.
- `DefaultRestApp.java` Trace 행 두 개를 `L1018-L1062`(`use`), `L1307-L1314`(업그레이드 파이프라인)로 고쳤습니다.
- 지시의 괄호에는 "left open" 문장만 있었지만, AC-8과 Trace 행은 평가의 권장 해결안에 있고 AC-8을 두면 같은 블록이 스스로 어긋나므로 함께 고쳤습니다.

**OPS-REL-010**

- 끝 절 옛 문구: and the release-evidence gate keeps 0.11.0 pending only while none of its records exist.
- 끝 절 새 문구: and the release-evidence gate checks version 0.11.0 against its own records (OPS-REL-011 AC-4).
- 문구만 바뀌어 빨강 단계가 없습니다. 기존 증거를 빌드로 다시 돌렸습니다.

## 하지 않은 것과 남은 일

- **WebSocket `OriginCheck`는 이 후속 수정에서 고치지 않았습니다**(지시대로). 당시 `OriginCheck.tuple`은 http(s)만 받아서 `webSocket(o -> o.allowedOrigins("capacitor://localhost"))`는 기동 경고만 내고 일치하지 않았습니다. **2026-11-06 사용자 지시로 해결했습니다**: FR-WS-003 AC-8을 더했고, 이제 `allowedOrigins`에 나열한 사용자 정의 스킴 origin이 일치합니다. 사용자 정의 스킴은 기본 포트가 없고 same-origin이 되지 않습니다.
- **릴리스 기록을 다시 찍어야 합니다.** `docs/release/0.11.0-test-results.md`는 이전 커밋과 3775건을 적고 있습니다. 코드가 바뀌었으므로 태그 전에 새 커밋으로 다시 남겨야 합니다. 게이트는 이를 막지 않습니다.
- **FR-AUTH-009 VE-13의 Notes가 낡았습니다.** "issuedByUserId is on no revoke event"는 그 테스트의 키(발급자 없음)에만 맞습니다. 해당 행은 `verified`에서 고칠 수 없어 같은 테스트를 가리키는 VE-16을 더했고 "supersedes VE-13's note"라고 적었습니다. 고치려면 다시 내려야 합니다.
- **FR-DX-010 VE-1은 파일 단위 참조로 남겼습니다**(`covers: all`, 2026-10-21 기록). VE-2~VE-11은 전부 `path#method`입니다.
- **IR-APIKEY-004 AC-6 문구:** `findById`를 "응답이 의존하는 호출"로 나열합니다. `/me` 삭제의 새 읽기는 응답이 의존하지 않고 삼키므로 그 AC의 뜻과 맞고, 해당 테스트도 통과했습니다. 문구는 고치지 않았습니다.
- **FR-AUTH-005 Research 메모:** "whether adminRoutes moves ... is open"이라는 2026-10-30 기록은 당시 기록으로 두었습니다.
- **평가 문서의 "범위 밖" 발견은 처리하지 않았습니다:** 요청 안에서 파사드를 부르는 경우의 `apiKeyId`(항목 2), AC-7 과장과 오진 메시지(항목 3).
- **실기기 확인은 없습니다.** WKWebView가 실제로 `Origin: capacitor://localhost`를 보내는지는 문서 근거이고, 평가가 권한 기기 확인 한 번은 아직 하지 않았습니다.
