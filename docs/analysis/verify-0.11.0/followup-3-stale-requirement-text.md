# 후속 항목 3: Requirement 본문이 AC보다 낡은 문제

## 판정: 일부 타당함 (낡은 문장은 있지만 지목된 곳과 심각도가 다르고, "후속 요구로만 고칠 수 있다"는 틀렸다)

- **"verified라서 후속 요구로만 고칠 수 있다"는 틀렸다.** `verified -> in_progress`는 규칙이 인정하는 전이이고, 이 저장소에서 이미 여러 번 썼다.
- **주장이 지목한 세 문장 가운데 진짜 모순은 없다.** OPS-REL-010은 지난 규칙이 남은 정도이고, FR-AUTH-005는 맞는 문장이며, SEC-AUTH-002의 `dev`는 표현이 느슨한 정도다.
- **주장이 놓친 낡은 문장이 더 중요하다.** SEC-AUTH-002의 "DEV는 정확히 세 가지만 완화"는 코드, 다른 요구의 AC, 가이드와 모순된다. 같은 문장이 공개 Javadoc에도 있다.
- **`dev` 대소문자 무시는 안전하다.** 바꿀 이유가 없다.

## 주장 확인 (claim, evidence file:line, confirmed or refuted)

### 공통

| 주장 | 근거 | 결과 |
|---|---|---|
| verified는 후속 요구로만 고칠 수 있다 | `docs/rule/SRS-MD-Rules-v2.5.0.md:735`가 `verified -> in_progress`를 관례 전이로 둔다. `update_status` dry run이 SEC-AUTH-002에서 통과했다. 2026-10-23, 2026-10-26 Change Notes에 같은 절차가 있고, 그중 하나는 "the statement and AC-1..AC-3 are re-worded and re-verified"다 | **반박됨** |
| verified 상태에서는 본문을 바로 고칠 수 없다 | `edit_requirement_fields` dry run이 `MUTATION_DENIED`를 냈다: "change status through a guarded status mutation first" | **확인됨** |
| Requirement 절은 현재 사실을 적는 곳이다 | 규칙 §18.1(`SRS-MD-Rules-v2.5.0.md:876-881`)이 Requirement와 AC를 "what is true now"로 분류한다 | **확인됨.** 그래서 Rationale 메모는 본문을 바로잡지 못한다 |
| 낡은 본문이 도구를 오도한다 | SRS-W073은 도구가 내장한 값만 비교한다(§18.1). 0.11.0 `checked_compatible` 엣지는 0개다. `validate_spec`은 오류 0개다. 본문과 AC를 비교하는 도구는 찾지 못했다 | **반박됨.** 영향받는 쪽은 사람과 에이전트 독자다 |

### OPS-REL-010

| 주장 | 근거 | 결과 |
|---|---|---|
| 본문이 "gate keeps 0.11.0 pending"을 말한다 | `docs/spec/15.release.srs.md:1467`: "keeps 0.11.0 pending only while none of its records exist" | **확인됨** |
| 그 말이 현재 동작과 모순된다 | 기록 두 개가 `docs/release/`에 있으므로 실제 저장소에서는 "pending이 아니다"로 읽혀 맞다. 다만 기록이 없는 경우 게이트는 pending이 아니라 거부한다(`ReleaseEvidenceSnapshotsTest.java:148`, `ReleaseEvidenceSnapshots.java:97-99`) | **일부 확인.** 현재 상태에서는 참이고, 규칙이 사라졌다는 점에서 낡았다 |
| 독자가 오해한다 | 같은 블록의 AC-3이 과거형으로 "OPS-REL-011 AC-4 replaced this rule"이라고 적는다 | **약함.** 스무 줄 아래에서 바로 풀린다 |

### FR-AUTH-005

| 주장 | 근거 | 결과 |
|---|---|---|
| 본문은 ROUTER_MOUNT에서 install이 던진다고만 쓰고 second-Auth 메시지가 없다 | `docs/spec/17.auth.srs.md:1136`은 맞는 설명이다. 메시지는 원래 본문이 아니라 AC의 몫이다 | **사실이지만 결함이 아니다** |
| 그 문장이 코드와 모순된다 | `Auth.java:596-611`: ROUTER_MOUNT가 있으면 루프가 던지거나, `otherAuth`로 건너뛴 뒤 609행이 던진다. 어느 쪽이든 IllegalStateException이다 | **반박됨.** 본문은 모든 경로에서 참이다 |
| (주장에 없음) 다른 낡은 문장 | 본문 끝 "Whether the admin mount follows a configured mount path ... is left open"과 AC-8 끝 "(open: 00 does not say)". 코드는 이미 정했다(`Auth.java:341`, `:648`). 문서도 같다(`docs/api/auth.md:146`, `:203`). `RealJwtPathTest.java:222,227`이 `/api/auth/admin`을 호출한다 | **새로 확인.** CLAUDE.md는 정한 뒤 미확정 표시를 지우라고 한다 |
| (주장에 없음) 낡은 Trace 행 | `DefaultRestApp.java:L840-L856`의 시작 줄은 이제 종료 처리 코드다 | **새로 확인.** 줄 번호가 밀렸다 |

### SEC-AUTH-002

| 주장 | 근거 | 결과 |
|---|---|---|
| 본문이 "only when NARU_ENV is dev"라고 쓴다 | `docs/spec/17.auth.srs.md:844` | **확인됨** |
| 그 말이 AC-1과 모순된다 | AC-1은 "dev in any letter case"다. "only when ... dev"는 다른 단어를 막는 뜻으로는 여전히 맞고, 대소문자에 대해 말하지 않을 뿐이다. `docs/api/auth.md:123`도 같은 축약을 쓰고 `:352`에서 풀어 쓴다 | **약함.** 표현이 느슨한 수준이다 |
| (주장에 없음) "relax exactly three things" | 아래 표 참고 | **새로 확인. 진짜 모순이다** |
| (주장에 없음) 낡은 Trace 행 | `17.auth.srs.md:873`: `AuthMode.java:L1-L14`, "DEV/PROD enum only; fromEnv() withheld". `fromEnv()`는 `AuthMode.java:27`에 있다 | **새로 확인.** 사실과 반대다 |

"정확히 세 가지"와 어긋나는 DEV 차이는 다음과 같다. 1~3은 이미 있는 목록이고, 4~7이 넘치는 항목이다.

| # | DEV가 완화하는 것 | 코드 | 소유 AC |
|---|---|---|---|
| 1 | 비밀키가 없으면 임시 키 | `Auth.java:4816-4822` | SEC-AUTH-001 AC-5 |
| 2 | R33 보안 연결 검사 | `Auth.java:2020` | SEC-AUTH-002 AC-2 |
| 3 | 해시 비용, 키 강도를 WARN으로 | `Auth.java:1002`, `:4843` | SEC-AUTH-002 AC-3 |
| 4 | 정책을 어긴 `.user` 비밀번호로 계정 생성 | `Auth.java:4635` | FR-AUTH-004 AC-7, OPS-AUTH-017 AC-3 |
| 5 | 비밀번호 없는 admin `.user`에 임시 비밀번호를 만들어 로그에 기록 | `Auth.java:1406`, `:1440` | FR-AUTH-004 AC-6 |
| 6 | 암호화 키 없는 파일 저장소의 build 거부를 WARN으로 | `Auth.java:4551`, `:4603` | FR-IAM-015 AC-4 |
| 7 | 옛 `trustProxy(true)`의 doctor FAIL 없음 | `Auth.java:929` | `17.auth.srs.md:1969` AC-3, 00 R32 |

세 곳의 표현이 서로 다르고, 전부 실제보다 적다.

- SEC-AUTH-002 본문과 `AuthMode.java:7` Javadoc은 "exactly three"라고 쓴다.
- `docs/api/auth.md:350`과 `docs/api/en/auth.md:350`은 "네 가지", "exactly four"라고 쓴다.
- `AuthGuideRound6DocsTest.java:74`는 가이드에 "exactly three"가 있으면 실패한다. 프로젝트가 이미 "세 가지"를 틀린 말로 판정했는데, 본문과 Javadoc에만 남아 있다.

항목 5~7은 다른 AC나 00이 허용한 동작이다. 잘못된 것은 동작이 아니라 "닫힌 목록"이라는 설명이다. 권한 판정 완화는 찾지 못했으므로 "Authorization is never relaxed"는 유지된다.

### `dev` 대소문자 무시의 안전성

| 확인 | 근거 | 결과 |
|---|---|---|
| 다른 단어로 넓어지는가 | 전체 유니코드를 jshell로 훑었다. `toLowerCase(Locale.ROOT)`가 `d`, `e`, `v`가 되는 코드 포인트는 `D d E e V v` 여섯뿐이다. 켜지는 표기는 ASCII 8가지뿐이고, 유사 문자는 PROD로 남는다 | **안전** |
| 공백, 유사 단어 | `AuthMode.java:34`는 trim 없이 `equals`다. ` dev`, `develop`은 PROD다(`AuthBuilderTest.java:266`) | **안전** |
| 터키어 로케일 | `"DEV"`에는 `I`가 없어 `tr` 로케일에서도 `dev`다 | final-12의 안전 근거는 이 경우에 해당이 없다. `Locale.ROOT`는 그래도 맞는 선택이다 |
| 값을 누가 정하는가 | 프로세스 환경 변수다. 빌더는 스스로 읽지 않고 앱이 호출할 때만 읽는다(`AuthMode.java:23-24`) | 환경을 바꿀 수 있는 공격자는 이미 더 큰 권한을 가진다 |
| 대소문자 구분과 비교한 위험 차이 | 정확 일치라면 `NARU_ENV=DEV`는 PROD로 떨어져 안전 쪽으로 실패한다. 무시하면 DEV가 된다. 하지만 `DEV`라고 적은 사람은 개발 모드를 뜻하며, 운영 서버에 잘못 복사하는 사고는 소문자 `dev`로도 똑같이 난다 | 차이는 작다 |
| 잘못 켜졌을 때의 피해 | 임시 키는 `SecureRandom` 32바이트라서 위조할 수 없다(`Auth.java:4817-4818`). 외부 요청이 오면 WARN이 한 번 남는다(`:2016`). 권한 규칙은 그대로다(AC-4) | 피해가 제한된다 |

결론: 안전하다. 00 R41은 `dev`만 적고 대소문자를 말하지 않으므로 AC 문구 변경이 00과 충돌하지도 않는다. 오히려 DEV가 켜졌을 때의 판단을 흐리는 것은 대소문자가 아니라 위의 축소된 완화 목록이다.

## 사용자에게 미치는 영향 (who hits it, how often, how bad)

| 대상 | 누가 만나나 | 빈도 | 심각도 |
|---|---|---|---|
| OPS-REL-010 본문 | 릴리스 요구를 읽는 유지보수자, 에이전트 | 드묾 | 낮음. 실행 영향 없고 같은 블록 AC-3이 바로잡는다 |
| FR-AUTH-005 ROUTER_MOUNT 문장 | 없음 | 없음 | 없음. 맞는 문장이다 |
| FR-AUTH-005 "left open" | 관리 엔드포인트를 고치는 사람, 에이전트 | 드묾 | 낮음에서 중간. 정해진 동작을 바꿔도 된다고 오해할 수 있다 |
| SEC-AUTH-002 `dev` 표현 | 요구를 읽는 사람 | 드묾 | 낮음. AC-1이 풀어 준다 |
| SEC-AUTH-002 "세 가지"와 Javadoc | **앱 개발자.** 공유 서버에서 DEV를 켜도 되는지 Javadoc으로 판단하는 사람 | 가끔 | **중간.** 약한 비밀번호, 평문 파일, 로그에 찍히는 임시 비밀번호를 모른 채 결정할 수 있다. 각각 WARN이 있고 가이드 다른 곳에도 일부 적혀 있어 높음은 아니다 |
| Trace 행 `fromEnv() withheld` | 코드 추적을 쓰는 에이전트 | 드묾 | 낮음에서 중간. 있는 메서드를 없다고 적었다 |

어느 것도 실행 동작이나 0.11.0 빌드를 막지 않는다. 릴리스 차단 사유는 아니다.

## 해결안 비교 (including "do nothing"; size, risk, what it breaks, which requirements and verified ACs it touches)

| 해결안 | 크기 | 위험 | 망가뜨리는 것 | 건드리는 요구 | 판단 |
|---|---|---|---|---|---|
| A. 아무것도 안 함 | 0 | 공개 Javadoc의 틀린 설명이 남고, §18.1을 어긴 상태가 이어진다 | 없음 | 없음 | OPS-REL-010만 놓고 보면 받아들일 수 있지만, SEC-AUTH-002에는 부족하다 |
| B. `append_section_note` | 요구당 1회 | 틀린 본문은 그대로이고 메모가 현재 사실을 주장하게 되어 §18.1이 금지한 형태에 가까워진다 | 없음 | Rationale만 | verified에서 통과하지만(dry run 확인) 고치는 것이 아니라 덧붙이는 것이다. Javadoc도 못 고친다 |
| **C. 단계 내림 후 수정, 재검증** | 요구당 mutation 3~6회. 코드 변경은 SEC-AUTH-002의 Javadoc과 가이드 두 곳뿐 | 재검증을 형식적으로 하기 쉽다. 커밋을 나누면 중간에 verified가 아닌 상태가 남고, FR-AUTH-005와 SEC-AUTH-002는 완료 로그에 있어 SRS-W015가 일시적으로 늘 수 있다 | ID, 증거, 참조 모두 유지 | 세 요구. AC 번호 변화 없음. SEC-AUTH-002 AC-6 추가, FR-AUTH-005 AC-8 끝 문장 수정 | **채택** |
| D. `supersede_requirement` | 새 ID 3개, AC 16개 재기재, 증거 20행 재등록, 테스트 메서드 이름 참조 14곳, 다른 요구의 참조 정리 | 원본이 discarded가 되어 참조가 폐기된 ID를 가리킨다. 완료 로그 SRS-W015가 최소 3건 영구히 늘어난다(지금 47건, 표본 4건은 모두 폐기 ID였다) | ID 추적, 테스트 이름과 요구의 대응 | 세 요구와 이를 참조하는 요구 | **과하다.** 요구의 뜻이 바뀌지 않는데 정체성을 바꾼다. 문장 하나 때문에 쓸 도구가 아니다 |
| E. `dev`를 대소문자 구분으로 변경 | 코드 1줄, 테스트, 가이드 두 곳, AC | 문서화된 동작이 바뀐다 | `NARU_ENV=DEV`를 쓰는 환경 | SEC-AUTH-002 AC-1 | 얻는 안전이 거의 없어 **기각** |

참조 규모 측정값은 다음과 같다. Requirement ID가 나오는 SRS 줄 수는 자기 블록을 포함한다.

| 요구 | SRS 줄 | 테스트 이름 참조 | 증거 행 |
|---|---:|---:|---:|
| OPS-REL-010 | 1 | 1 | 6 |
| FR-AUTH-005 | 24 | 8 | 9 |
| SEC-AUTH-002 | 5 | 5 | 5 |

## 권장 해결안 (the smallest correct one: exact files, requirement handling via SRS rules, tests to add) or why no change is warranted

**해결안 C를 쓴다. 후속 요구는 만들지 않는다.** 기존 본문 요구의 수정이므로 sdd 모드로 진행한다. 세 요구를 한 커밋으로 끝내서 verified가 아닌 상태가 커밋에 남지 않게 한다. 요구당 절차는 아래 순서다.

1. `update_status`로 `in_progress`. reason에 이유를 적는다. 규칙 §26이 요구하는 Change Note가 된다.
2. `edit_requirement_fields`로 statement를 고친다. 필요하면 AC와 Trace를 함께 고친다.
3. 해당 테스트를 다시 돌린다.
4. `update_status`로 `implemented`를 거쳐 `verified`. 바로 `verified`로 가도 도구는 막지 않고 2026-10-26 선례도 그랬지만, 규칙의 관례 목록은 `implemented`를 거친다.

### SEC-AUTH-002 (꼭 고칠 것)

- **테스트 먼저:** AC-6을 맨 뒤에 추가한다(`replace_acceptance_criteria`로 AC-1~5는 체크된 채 그대로 두어 번호가 바뀌지 않게 한다). 내용은 "AuthMode 타입 Javadoc과 두 가이드의 DEV 행이 같은 완화 목록을 말한다"이다.
- **새 테스트:** `FR_APIKEY_006_AC6_userStoreJavadocRequiresTheFlag`처럼 Javadoc을 읽고, `AuthGuideRound6DocsTest`처럼 가이드를 읽는다. 고치기 전에 실패해야 한다.
- **본문:** "only when NARU_ENV is dev"를 "only when NARU_ENV is dev in any letter case"로 바꾼다. "exactly three things"는 위 표의 일곱 항목을 소유 AC와 함께 적은 닫힌 목록으로 바꾼다.
- **코드와 문서:** `NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/AuthMode.java:7`, `docs/api/auth.md:350`, `docs/api/en/auth.md:350`.
- **Trace:** `17.auth.srs.md:873` 행을 `edit_requirement_table_rows`로 지운다. 872행이 이미 같은 파일을 가리킨다.

### FR-AUTH-005

- **주장이 지목한 문장은 고치지 않는다.** 맞는 문장이고, 메시지는 AC-7의 몫이다.
- **본문 마지막 문장:** "the admin mount follows a configured mount path, at the mount path plus /admin"로 바꾼다.
- **AC-8 끝 문장:** 같은 내용으로 바꾸고 `RealJwtPathTest.java#SEC_AUTH_006_AC1_protectOnlyKeepsTheAuthMountsInRange`를 증거로 추가한다. 새 테스트는 없다.
- **Trace:** `DefaultRestApp.java` 두 행의 줄 범위를 현재 코드에 맞춘다.

### OPS-REL-010

- **본문 끝 절:** "and the release-evidence gate checks version 0.11.0 against its own records (OPS-REL-011 AC-4)"로 바꾼다. 테스트 변경은 없다.
- 가장 급하지 않다. 범위를 줄여야 한다면 이 요구는 그대로 둬도 된다.

### 함께 지킬 것

- `dev` 대소문자 무시는 바꾸지 않는다. 안전 근거에서 터키어 언급은 빼도 된다.
- 재검증은 실제 실행으로 한다. 끝에 `validate_spec`과 `speckiwi coverage --tests --target 0.11.0 --json`을 돌려 SRS-W015가 47건 그대로인지 본다.
- `docs/release/0.11.0-test-results.md`는 fb4c8c3을 릴리스 커밋으로 적었다. Javadoc을 고치면 게시 전에 릴리스 실행 기록을 새 커밋으로 다시 남겨야 한다. 게이트가 이를 막지는 않아서 놓치기 쉽다.

### 범위 밖의 별도 발견

이번 수정에 넣지 말고 따로 판단할 것. 둘 다 IllegalStateException은 그대로 나고 메시지만 틀리며, 코드를 읽어 얻은 결과다.

1. **AC-7 과장:** 두 번째 Auth의 `mountPath`가 다르면 `Auth.java:592`가 첫 Auth를 찾지 못해 "another Auth" 대신 before-routes 메시지가 나온다. FR-AUTH-005 AC-7과 OPS-AUTH-024 AC-7은 앱 라우트가 없으면 "another Auth"라고 쓴다. 테스트는 같은 기본 경로만 다룬다.
2. **오진 메시지:** Auth가 없는 앱에서 `app.use("/auth", myRouter)` 뒤에 install하면 앱 라우터를 다른 Auth로 오인해 "another Auth is already installed"가 나온다. FR-AUTH-005 AC-1의 "message names that registration"과 어긋난다. 자기 인증 라우터를 만들던 사용자가 만날 수 있는 경우다.

## 확인하지 못한 것

- **단계 내림 뒤의 도구 동작은 실행하지 않았다.** 이번 평가는 읽기 전용이라 `update_status`는 dry run만 했고 SEC-AUTH-002 한 건만 시험했다. 내려간 뒤 statement 수정이 된다는 것은 도구의 거부 메시지와 2026-10-23 Change Note로 판단했다. dry run 뒤 `git status`에 `docs/spec` 변경은 없었다.
- **`supersede_requirement` dry run은 새 ID(`SEC-AUTH-009`)만 돌려줬다.** 후속 요구의 초기 Status와 Stability는 보지 못했다.
- **SRS-W015 증가는 추정이다.** 경고 문구("not completed")와 표본 4건(CON-BUILD-003, FR-HTTP-002, DR-IAM-003, REL-APIKEY-005, 전부 폐기 ID)에서 추론했고, 47건 전부를 대조하지는 않았다.
- **테스트는 다시 돌리지 않았다.** 범위 밖 발견 두 건은 코드 읽기로만 확인했다.
- **DEV 차이 목록이 완전하다고 보장하지 못한다.** `utils/auth`에서 `AuthMode.DEV`, `== DEV`, `isDev`, `mode ==`를 검색했다. 다른 형태의 분기는 놓쳤을 수 있고, 권장안을 구현할 때 다시 훑어야 한다.
- **`prodFinalError`는 판단하지 못했다.** `Auth.java:662`의 PROD 전용 오류 계층이 DEV 완화인지, AC-4의 "JSON error bodies"와 맞는지 확인하지 않았다. 일곱 항목에 넣지 않았다.
- **가이드 전체를 대조하지 않았다.** 파일 저장소 항목은 `docs/api/en/auth.md:2019`에 있지만, 나머지 항목이 DEV 설명 밖에서 어떻게 적혔는지는 다 보지 않았다.
- **외부 사실은 쓰지 않았다.** 이 항목의 판단은 저장소 규칙과 코드, 로컬 JDK의 `toLowerCase` 실행 결과에만 기댄다. 유니코드 결과는 이 JDK 기준이며 Android 런타임에서는 실행하지 않았다.
