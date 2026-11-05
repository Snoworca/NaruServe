# 후속 항목 2: auth.apiKeys().revoke 감사 이벤트의 대상(target)

## 판정: 타당함 (문서와 코드가 실제로 어긋나고 틀린 쪽은 코드다. 소유자는 `findById` 한 번으로 알 수 있고 고치는 데 몇 줄이면 된다. 심각도는 낮다)

주장 가운데 "`deleteById`가 소유자를 돌려주지 않아서"라는 이유만 고칠 수 없는 제약이 아니다. 관리 엔드포인트가 이미 지우기 전에 읽어서 해결하고 있다.

## 주장 확인

| 주장 | 근거 | 결과 |
|---|---|---|
| 문서가 `auth.apiKeys().revoke`의 이벤트 대상을 소유자라고 한다 | `docs/api/auth.md:1994`, `docs/api/en/auth.md:1994`. 한 문장이 두 엔드포인트와 `revoke`를 함께 주어로 삼는다 | 확인 |
| 앱 호출은 `targetId`를 넣지 않는다 | `DefaultAuthApiKeys.java:260-261`에 `.targetId(...)` 호출이 없다. `AuditTrail.event`(`AuditTrail.java:40-68`)도 넣지 않고 `Builder.targetId` 기본값은 null이다(`AuditEvent.java:138`) | 확인 |
| 엔드포인트 경로는 넣는다 | `DefaultAuthApiKeys.java:287-288`. 호출 쪽은 `AdminEndpoints.java:1203-1204`, `AccountEndpoints.java:1335` | 확인 |
| AC가 이것을 다루지 않는다 | FR-AUTH-009 AC-2는 revoke에 "carrying the revoked key id"만 요구한다. FR-APIKEY-004 AC-6과 IR-APIKEY-002 AC-5에도 대상 언급이 없다 | 확인. 범위는 더 넓다. 엔드포인트의 대상도 어떤 AC도 요구하지 않는다 |
| `deleteById`가 소유자를 돌려주지 않는다 | `ApiKeyStore.java:48`, 반환형 `boolean` | 사실이지만 막는 이유는 아니다 |
| 호출 시점에 소유자를 알 수 없다 (함의) | `findById`는 필수 메서드다(`ApiKeyStore.java:32`). `AdminEndpoints.java:1191,1199,1203-1204`가 같은 "읽고, 지우고, 소유자를 넘기기" 순서를 이미 쓴다 | **반박됨** |
| 대상이 없는 것은 의도한 설계다 (반증 시도) | w10 리뷰 `prickly_review_iter2.json`의 R2-6 처리 기록이 "fixed (app revoke: revokedApiKeyId only)"다. 다만 지적 본문이 남아 있지 않다. 00 R47에는 철회 이벤트의 대상을 정한 규칙이 없다 | 판단 불가. 아래 "확인하지 못한 것" 참고. 설령 의도였어도 문서는 그 뒤로 고쳐지지 않았다 |

테스트 공백도 있다. 대상을 확인하는 단언은 관리 삭제 한 건뿐이다(`Wave10PatTest.java:1390`). `/auth/me` 삭제(`:1388` 부근), 키 행위자 삭제(`:1392` 부근), 앱 호출(`:1418`)은 대상을 단언하지 않는다. 그래서 `FR_AUTH_009_AC2_issueAndRevokeEventsCarryTheKeyId`가 통과해도 이 불일치는 드러나지 않았다. `Wave10PatTest`와 `Wave8ApiKeysTest`는 지금 코드에서 실패 없이 끝났다.

**어느 쪽이 틀렸나: 코드.** 근거는 셋이다.
- 문서 문장이 `revoke`를 명시한다.
- 짝이 되는 앱 호출 `issue`는 대상을 소유자로 넣는다(`DefaultAuthApiKeys.java:108-109`). 발급과 철회의 대상이 달라야 할 이유가 없다.
- 이 이벤트에는 행위자, 요청 id, IP가 모두 없어서(`null, null, null, false`) 대상까지 빠지면 키 id만 남는다.

## 사용자에게 미치는 영향

- **누가:** `.audit(sink)`를 설정하고 앱 코드에서 `auth.apiKeys().revoke`를 부르는 앱이다. 기본 sink는 `AuditSinks.none()`이라(`Auth.java:358`) 감사를 켜지 않은 앱은 영향이 없다. 이 파사드는 운영 스크립트와 서비스 계정용이고, 만료 없는 키를 만드는 유일한 길이라 그런 키의 철회가 이 경로를 탄다.
- **얼마나 자주:** 호출 빈도는 낮지만, 대상이 빠지는 것은 호출할 때마다 항상이다.
- **얼마나 나쁜가:** 낮음에서 중간. 철회 자체는 정상으로 동작하고 보안 구멍은 아니다.
- **감사 소비자가 잃는 정보:** 키 행은 지워지므로 `store`와 `list`에서는 소유자를 다시 찾을 수 없다. 남은 연결고리는 발급 이벤트뿐이다. `api_key_issued`의 `apiKeyId`와 `targetId`로 조인하면 소유자가 나온다. 다음 경우에는 조인이 실패해 소유자를 잃는다.
  - 감사 sink를 켜기 전에 발급한 키
  - 발급 이벤트가 보관 기간을 지난 키. 만료 없는 키일수록 그럴 가능성이 크다
  - store를 이전해 들여온 키
- `target`으로 필터링하는 소비자는 이 철회를 아예 놓친다. 기본 줄 형식도 null 대상을 생략한다(`AuditSinks.java:62`, `:73-76`). 사용자별 이력 조회에서 빠지는 것이 실질적 손해다.

## 해결안 비교

| 안 | 크기 | 위험 | 깨는 것 | 건드리는 요구와 AC |
|---|---|---|---|---|
| A. 아무것도 안 함 | 0 | 문서가 계속 거짓이고 소비자가 소유자를 놓친다 | 없음 | 없음. 공개 문서 불일치가 남으므로 받아들일 수 없다 |
| B. 문서만 고침 (ko/en 1994: 앱 호출은 대상 없음) | 2줄 | 낮음 | 없음 | 없음. 불일치는 없어지지만 정보 손실을 계약으로 굳히고, `issue`와 비대칭이 남는다 |
| **C. `revoke`가 지우기 전에 `findById`로 소유자를 읽고 `.targetId`에 넣음 (읽기 실패는 대상 없이 진행)** | 코드 약 7줄, 테스트 2건, 문서 2줄 | 낮음. 철회 한 번에 읽기 한 번이 늘지만 핫패스가 아니다 | 없음. 공개 API와 SPI가 그대로다. 테스트 store는 모두 `findById`를 구현한다 | FR-AUTH-009 AC-2 문구, 재검증 필요 |
| D. C와 같되 읽기 실패를 `call()`로 503 처리 | 코드 3줄 | 감사 보강용 읽기가 철회라는 보안 동작을 막을 수 있다 | `findById`만 실패할 때 `revoke`가 예외가 된다 | C와 같음 |
| E. SPI 추가 (지운 행을 돌려주는 default 메서드) | SPI, 계약 테스트, 문서 | 원자성을 얻지만 여기서는 필요 없다 | 사용자 구현 store가 override해야 이득이 있다 | IR-APIKEY-004. 과하다 |
| F. `revoke(keyId, req)` 같은 오버로드로 행위자와 요청 id까지 채움 | 공개 API 추가 | 범위가 바뀐다 | 없음 | IR-APIKEY-002. 이 항목에는 과하다 |

C의 경쟁 조건은 무해하다.
- 키의 소유자를 바꾸는 store 메서드가 없고 id는 난수다(`DefaultAuthApiKeys.java:193`).
- 두 호출이 동시에 철회하면 `deleted`가 참인 쪽만 이벤트를 낸다.
- 읽기와 삭제 사이에 다른 경로가 지우면 `deleted`가 거짓이라 이벤트가 없다.

C와 D의 차이는 판단의 문제다. 관리 DELETE는 `findById` 실패가 503이지만(`Wave12ApiKeyTest.java:238-241`), 거기서 읽기는 권한 판정에 필요하다. 여기서 읽기는 감사 내용을 채울 뿐이다. 감사 sink 실패가 응답을 바꾸지 않는다는 FR-AUTH-009 AC-4의 원칙과 같은 이유로 C를 택한다. 같은 파일의 `:118`과 `:284`도 부가 정보 읽기 실패를 삼킨다.

## 권장 해결안

안 C. 코드를 문서에 맞춘다.

**코드:** `NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/DefaultAuthApiKeys.java`의 `revoke`.

```java
String ownerId = null;
try {
    StoredApiKey k = keys.findById(keyId);
    ownerId = k == null ? null : k.getOwnerUserId();
} catch (Exception unreadable) {
    // the target is best effort: reading it never stops a revoke
}
boolean deleted = call(() -> keys.deleteById(keyId));
// ...
auth.audit().event(AuditEvent.API_KEY_REVOKED, "auth.apiKeys().revoke", null, null, null, false)
        .targetId(ownerId).detail("revokedApiKeyId", keyId).build()
```

**문서:** `docs/api/auth.md:1994`와 `docs/api/en/auth.md:1994`에 같은 줄에서 한 구절을 더한다. 내용은 "`auth.apiKeys().revoke`가 소유자를 읽지 못하면 대상 없이 남고, 이 호출의 이벤트에는 행위자·요청 id·IP가 없다"이다. 나머지 문장은 그대로 맞아진다.

**요구(새 요구 없음, sdd 모드, 지금 `Mode: sdd`):** FR-AUTH-009는 `verified`라서 `replace_acceptance_criteria`가 거부된다. 순서는 다음과 같다.
1. MCP로 `verified -> in_progress`.
2. AC 7개를 순서 그대로 넘기며 AC-2의 revoke 괄호만 바꾼다. 예: "API key revoke (carrying the revoked key id and, when the key row could be read, its owner as target)". 끝의 Decision 문장은 유지한다. AC 번호가 바뀌면 근거 행이 어긋나므로 순서를 바꾸지 않는다.
3. 아래 테스트로 빨강을 확인한 뒤 구현한다.
4. `add_verification_evidence`로 근거를 더하고 이 요구 하나만 `implemented -> verified`로 올린다. Change Notes에 이유를 남긴다.

IR-APIKEY-002 AC-5, SEC-APIKEY-011 AC-4, REL-APIKEY-001 AC-2의 동작은 바뀌지 않는다. `./gradlew build` 전체 회귀로 확인한다. HEAD `804c994`를 기준으로 적은 `docs/release/0.11.0-test-results.md`의 실행 기록은 코드가 바뀌면 다시 돌려 고쳐야 한다.

**테스트 (`NaruServe-api/src/test/java/com/snoworca/naruserve/utils/auth/`):**
- `Wave10PatTest#FR_AUTH_009_AC2_issueAndRevokeEventsCarryTheKeyId`: 두 revoke 이벤트의 `getTargetId()`가 `u1`임을 단언한다. 앱 호출 쪽이 빨강이 된다.
- `Wave10PatTest#FR_APIKEY_004_AC6_revokesThroughTheEndpointsAreAudited`: `revoked.get(0)`과 `get(2)`의 대상 단언을 더한다. 이미 통과하는 공백 메우기다.
- `Wave12ApiKeyTest`의 `Flaky`로 새 테스트 한 건: `findById`만 실패해도 `revoke`가 true이고 키가 지워지며 대상 없는 이벤트가 하나 나온다.

**이 항목과 묶지 말고 따로 결정할 것:**
1. **AC-2 Decision의 근거가 00과 어긋난다.** Decision은 `issuedByUserId`가 발급 이벤트에만 남는다고 하며 00 §6.4 "R47 × 발급자 기록" 행을 근거로 든다. 그 행은 발급 상황만 다룬다. 반면 00 §6.3 R47 (3)과 (6)(`docs/research/auth-rbac/00.overview.md:321`)은 "발급·철회 감사 이벤트에 issuedByUserId를 남긴다"고 두 번 적었다. FR-AUTH-009의 Rationale과 SEC-APIKEY-010 Implementation Notes(`docs/spec/21.auth-api-keys.srs.md:1445`)도 아직 같은 내용이다. 그래서 문서 사이의 모순이 남아 있다. 코드를 바꾸려면 `deleteOwnKey`에도 읽기가 필요하고, 지금 테스트(`Wave10PatTest.java:1420-1422`)가 없음을 단언한다. 발급 이벤트에 이미 값이 있으므로 코드 변경까지 필요하다고 보지는 않는다. 다만 Rationale, 1445행, 00 가운데 어느 것을 고칠지는 사람이 정해야 한다.
2. **요청 안에서 파사드를 부르는 경우.** IR-APIKEY-002 AC-8은 라우트 안의 호출을 인정한다. 이때 `revoke`와 `issue`는 `req`가 null이라 이벤트에 `apiKeyId`와 요청 id가 없다. FR-AUTH-009 AC-2의 "Every audit event raised during a request authenticated by an API key"와 AC-3을 문자 그대로 읽으면 어긋난다. 파사드에 요청 인자가 없으므로 AC 범위를 좁히는 것이 맞아 보이지만, 이 평가의 범위 밖이다.

## 확인하지 못한 것

- **R2-6의 원래 지적:** 처리 기록 한 줄만 남아 있다. 앱 호출의 세부 필드 이름을 고친 것인지, 대상을 일부러 뺀 것인지 알 수 없다. 저장소 기록도 도움이 되지 않는다. 문서 문장과 앱 호출 코드가 같은 커밋 `42e4f8c`로 한꺼번에 들어와 순서를 가릴 수 없다.
- **실제 앱의 사용 빈도:** 이 저장소에는 `revoke`를 호출하는 앱 코드나 감사 소비자 구현이 없어서 영향 평가는 코드 구조에서 추정한 것이다.
- **권장 코드는 실행하지 않았다:** 읽기 전용 평가라 위 코드와 새 테스트를 돌려 보지 않았다. `targetId`가 null인 것도 런타임 단언이 아니라 코드 읽기로 확인했다.
- **외부 사실:** 이 항목은 브라우저나 외부 표준 동작에 기대지 않아 1차 문서를 확인할 일이 없었다.
