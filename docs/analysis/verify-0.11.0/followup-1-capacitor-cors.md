# 후속 항목 1: iOS Capacitor의 `capacitor://localhost`와 CORS

## 판정: 타당함 (실제 증상은 주장보다 심합니다. JSON 로그인은 preflight 403으로 서버에 닿지 못하고, 고치는 데는 `CorsValues.isOrigin`의 스킴 조건 하나만 풀면 됩니다)

주장의 범위 한 가지만 좁힙니다. "앱 코드 변경이 필요할 수 있다"는 맞지만, 앱 쪽 우회로는 CapacitorHttp 설정 한 줄이 있습니다. 다만 이 설정은 기본값이 꺼짐이고 문서에 안내가 없습니다.

## 주장 확인

jshell 실측은 현재 빌드 클래스(`NaruServe-api/build/classes`)를 `TestApp`으로 돌린 결과입니다. 실제 미들웨어 파이프라인을 통과했습니다.

| # | 주장 | 근거 | 결과 |
|---|---|---|---|
| 1 | `allowedOrigins("*")`여도 `capacitor://localhost`에 ACAO를 보내지 않는다 | `DefaultCors.java:49`가 목록을 보기 전에 `CorsValues.isOrigin`을 검사합니다. `CorsValues.java:14`는 http/https만 받습니다. 실측: `*`에서 `GET`·`POST` 200, ACAO 없음 | 확인 |
| 2 | 브라우저 맥락 클라이언트가 본문 전달 응답을 읽지 못한다 | 실측: `*`에서 `capacitor://`·`ionic://` preflight **403**, `https://localhost` preflight 204. `DefaultCors.java:61` | 확인, **더 심함**. auth 본문은 JSON만 받으므로(SEC-FLOW-005) 로그인은 항상 preflight를 거칩니다. "응답을 못 읽는다"가 아니라 POST가 **아예 전송되지 않습니다**. Bearer를 싣는 요청도 같습니다 |
| 3 | 목록에 넣을 수 없다 | `DefaultCorsOptions.java:26`, 실측 `IllegalArgumentException`, `Wave8LoginTest.java:1188` | 확인 |
| 4 | 문서가 `*`도 실패한다고 말하지 않는다 | `docs/api/auth.md:1536`, `docs/api/en/auth.md:1536`은 "목록에 못 넣는다"까지만 씁니다 | 확인, **더 심함**. `docs/api/middleware.md:104`와 영문판은 "`*`는 모든 origin을 허용"이라고 씁니다. 이것은 사실과 다릅니다. `CorsOptions.java:6` Javadoc도 이 예외를 말하지 않습니다 |
| 5 | FR-FLOW-002 AC-5는 서버에서 성립한다 | `Wave8LoginTest.java:785`, `:1177`은 CORS를 거치지 않고 POST를 직접 보냅니다 | 확인. 다만 AC는 WKWebView가 실제로 그 POST를 보낼 수 있는지 말하지 않습니다 |
| 6 | 실제 앱에 코드 변경이 필요할 수 있다 | 아래 외부 사실 표 | 일부 확인. 서버를 고치지 않으면 앱이 CapacitorHttp를 켜거나 서버 앱이 CORS를 직접 써야 합니다 |

주장에 없던 사실을 추가로 찾았습니다.

- **사양이 스스로 어긋납니다.** OPS-FLOW-001의 Requirement 문장(`docs/spec/19.auth-transport-and-endpoints.srs.md:1153`)은 "Capacitor 같은 본문 전달 출처를 `allowedOrigins`에 넣으라"고 합니다. 같은 요구의 AC-3(`:1163`)은 넣을 수 없다고 합니다. FR-FLOW-002 AC-5의 원문도 `allowedOrigins`에 `capacitor://localhost`를 두었고, 2026-11-05에 코드에 맞춰 `http://localhost`로 바뀌었습니다(`final-12.md:74-76`). 설계 근거(`docs/research/auth-rbac/00.overview.md:303`, `:801` 부근, SRS `:871`)는 Capacitor 지원을 BOTH 본문 전달의 이유로 듭니다. 즉 **의도는 지원이고, 제한은 나중에 코드에서 온 것입니다.** 제한의 출처인 FR-DX-010의 AC 어디에도 "http(s)만"은 없습니다(`docs/spec/16.developer-experience.srs.md:651-662`). "validated origins"뿐입니다.
- **검증이 표준보다 좁습니다.** Fetch 표준의 `serialized-origin`은 `scheme "://" host [":" port]`입니다. `capacitor://localhost`는 문법상 유효한 출처입니다.
- **Android 안내가 낡았습니다.** `docs/api/auth.md:1532`, `:1536`, 예제 `:1541`, `:1552`는 Android Capacitor를 `http://localhost`로 씁니다. Capacitor 설정 문서의 `androidScheme` 기본값은 `https`이고, 소스도 `CAPACITOR_HTTPS_SCHEME`입니다. 비교가 문자열 일치(`DefaultCors.java:49`)라서 문서대로 `http://localhost`만 넣으면 기본 설정의 Capacitor 6+ Android 앱은 preflight 403을 받습니다. 이쪽은 코드 문제가 아니고 `https://localhost`를 넣으면 지금도 됩니다. 문서 결함입니다.
- **서버 앱이 직접 쓰는 우회로에 함정이 있습니다.** `res.header`로 ACAO를 직접 넣는 미들웨어를 실측했습니다.
  - `app.cors` 없이 쓰면 preflight 204, GET 200, 오류 500 모두 ACAO가 남습니다. 동작합니다.
  - `app.cors`와 같이 쓰면 오류 응답(500, 404)에서 ACAO가 **사라집니다**. `corsVary`가 `corsApplied`를 켜고(`DefaultResponse.java:404`), 오류 경로가 헤더를 지우기 때문입니다(`:789`). 클라이언트는 오류 본문을 읽지 못합니다. auth 401이 같은 경로로 가는지는 실측하지 않았습니다.
  - `app.cors` 뒤에 두면 preflight가 먼저 403으로 끝납니다.

외부 사실은 다음과 같습니다.

| 질문 | 답 | 출처 | 상태 |
|---|---|---|---|
| iOS Capacitor의 Origin | `capacitor://localhost`. `server.iosScheme`로 바꿀 수 있고 http/https로는 못 바꿉니다 | Ionic CORS 문서, Capacitor 설정 문서, `CAPInstanceDescriptor.swift`의 기본값 | 문서 확인, 기기 실측 아님 |
| WKWebView가 CORS를 강제하는가 | 강제합니다. "Modern webviews like iOS WKWebView or Android WebView (both used by Capacitor) do enforce CORS" | Ionic CORS 문서 | 문서 확인 |
| `ionic://localhost` | Cordova iOS(Ionic WebView 3.x)의 출처이고, `cordova-plugin-ionic-webview`에서 옮긴 Capacitor 앱도 `iosScheme`로 씁니다. 코드에서는 `capacitor://`와 똑같이 거부됩니다(실측) | Ionic CORS 문서, Capacitor 설정 문서 | 확인 |
| Android | 설정 문서 기본값은 `https`이므로 `https://localhost`입니다. Ionic CORS 문서 표는 아직 `http://localhost`입니다. 두 공식 문서가 다르며, 현재 소스와 맞는 쪽은 `https`입니다. 옛 앱은 `androidScheme: "http"`로 남아 있을 수 있어서 둘 다 실재합니다 | Capacitor 설정 문서, 6.0 업데이트 안내, `CapConfig.java` | 확인. 단 `CAPACITOR_HTTPS_SCHEME`의 값은 소스에서 직접 보지 못했습니다 |
| CapacitorHttp 경로 | 기본은 꺼져 있습니다(`plugins.CapacitorHttp.enabled`). 켜면 `fetch`·XHR이 패치됩니다. POST 등은 네이티브로 나가고, GET 계열은 웹뷰 안의 로컬 인터셉터 URL을 거쳐 네이티브가 대신 보냅니다. `native-bridge.ts`와 `HttpRequestHandler.swift`는 `Origin`을 더하지 않습니다. 그러면 BOTH에서 Origin 없음이므로 본문 전달이 되고(FR-FLOW-002 AC-5 첫 조건), CORS를 거치지 않습니다 | Capacitor HTTP 문서, `native-bridge.ts`, `HttpRequestHandler.swift`, `WebViewAssetHandler.swift` | 소스로 확인. `CapacitorUrlRequest` 내부는 못 봤고 실기기에서도 안 봤습니다 |
| CapacitorHttp의 한계 | PWA나 `ionic serve`에는 여전히 CORS가 필요하고, 대용량 전송은 문제가 될 수 있습니다 | Ionic CORS 문서, Capacitor HTTP 문서 | 확인 |
| Express는 어떤가 | `cors` 패키지는 스킴을 검사하지 않습니다. 배열은 문자열 일치, `*`는 무조건 `*`를 보냅니다. Ionic 문서 예제가 바로 이 목록에 `capacitor://localhost`, `ionic://localhost`를 넣습니다 | `expressjs/cors` `lib/index.js`, Ionic CORS 문서 | 확인 |

## 사용자에게 미치는 영향

- **누가:** iOS Capacitor(또는 Ionic WebView) 앱이 웹뷰의 기본 `fetch`·XHR로 NaruServe 서버를 부르는 경우입니다. CapacitorHttp가 기본값이 꺼짐이므로, 설정을 건드리지 않은 앱이 여기에 듭니다. auth를 쓰지 않아도 해당합니다. 문제는 Core CORS에 있습니다.
- **얼마나 자주:** 그런 앱이라면 JSON이나 `Authorization`을 싣는 요청마다 100% 실패합니다. 간헐적이지 않습니다. 다만 그런 앱의 수는 모릅니다. NaruServe에서 Capacitor는 설계 문서가 이름을 들어 지원한다고 한 사용 사례이지, 측정된 사용량이 있는 것은 아닙니다.
- **얼마나 나쁜가:** 해당 앱은 로그인을 못 합니다. 설정으로 고칠 길이 없고, 문서의 "`*`는 모든 origin"을 믿은 개발자는 `*`까지 시도하고도 원인을 찾기 어렵습니다. 서버에는 `OPTIONS 403`만 남고 auth 로그는 비어 있습니다. 데이터 손실이나 보안 문제는 없습니다. 지금의 동작은 닫히는 쪽으로 실패합니다.
- **영향 없음:** CapacitorHttp를 켠 앱, 네이티브 앱, 서버 간 호출(Origin 없음), http(s) 출처 SPA, 웹뷰가 아닌 클라이언트입니다.
- **같은 원인으로 번지는 곳:** 스킴이 http(s)가 아닌 다른 브라우저 맥락 출처(`chrome-extension://…`)도 `*`에서 같은 403을 받습니다(`isOrigin` false는 실측, 403은 같은 코드 경로).

## 해결안 비교

| 안 | 크기 | 위험 | 깨지는 것 | 닿는 요구·검증된 AC |
|---|---|---|---|---|
| A. 그대로 둠 | 0 | 문서 거짓(`*`)과 사양 모순이 남습니다 | 없음 | OPS-FLOW-001 Requirement와 AC-3의 모순이 계속됩니다. **권장하지 않습니다** |
| B. 문서만 고침 (`*`도 실패, CapacitorHttp 안내, Android https) | 문서 4곳, 문서 테스트 약간 | 낮음 | 없음 | OPS-FLOW-001 AC-3 보강. 사양의 의도(Capacitor를 `allowedOrigins`에)와 Express 적응 목표는 계속 못 지킵니다. 앱에 플러그인을 강제합니다 |
| C. `*`일 때만 `isOrigin`을 건너뜀 | 한 줄 | `Origin: foo`, 잘못된 값에도 `*`가 나갑니다(Express와 같고 해는 없습니다). 그러나 `*`는 auth 서버에 권장할 설정이 아닙니다 | 없음 | 목록 등록은 안 풀립니다. 절반짜리입니다 |
| **D. `CorsValues.isOrigin`을 `scheme://host[:port]` 일반 규칙으로 넓힘** | 코드 한 조건 + 메시지·Javadoc. 테스트·문서 동반 | 낮음(아래 안전성) | `Wave8LoginTest#OPS_FLOW_001_AC3_capacitorIsRefusedByAllowedOrigins`, `Wave7DocsTest#OPS_FLOW_001_AC3_capacitorCannotBeListed`. 다른 테스트는 커스텀 스킴 거부를 고정하지 않습니다(`CorsTest`는 경로 붙은 출처만 고정) | FR-DX-010(AC 추가), OPS-FLOW-001 AC-3, FR-FLOW-002 AC-5. 공개 API 시그니처 변경 없음 |
| E. `capacitor`, `ionic`을 코드에 허용 목록으로 박음 | 작음 | 앱 생태계별 이름이 프레임워크에 박힙니다. `app-defined-semantics-not-framework` 규칙에 어긋나고, `iosScheme`을 바꾼 앱은 또 막힙니다 | 위와 같음 | **거부** |
| F. Express식 `origin(fn)` 콜백·정규식 API 추가 | 공개 API 증가, Java·Kotlin 문서와 테스트 | 함수가 전체 허용으로 잘못 쓰일 수 있습니다 | 없음 | 이 문제에 비해 과합니다. 요구가 생기면 별도로 다룹니다 |

**D의 안전성**

- `Origin`은 금지 요청 헤더입니다(MDN). 웹 페이지 스크립트가 `capacitor://localhost`를 사칭할 수 없습니다. 네이티브 클라이언트는 무엇이든 보낼 수 있지만, 그것은 지금도 같고 CORS가 막는 대상도 아닙니다.
- 목록 판정은 여전히 정확한 문자열 일치입니다. 앱이 넣지 않은 출처는 넓어지지 않습니다. 동작이 바뀌는 곳은 `*` 설정뿐이고, 그것은 문서가 이미 약속한 "모든 origin"입니다.
- 응답에 되돌리는 값은 `URI.create`를 통과해 목록과 일치한 값뿐이라 헤더 주입 경로가 새로 생기지 않습니다.
- `*`와 `credentials(true)`의 조합 거부(`DefaultCorsOptions.java:64`)는 그대로입니다.
- **유일한 실제 주의점:** `capacitor://localhost`는 앱의 신원이 아닙니다. 기본 설정의 Capacitor iOS 앱은 모두 같은 출처입니다. 그래서 목록에 넣으면 다른 Capacitor 앱의 웹뷰도 응답을 읽을 수 있습니다. 그 앱은 사용자의 토큰을 갖고 있지 않으므로 공개 응답 이상을 얻지 못합니다(앱 간 저장소 분리는 아래 미확인 항목). 쿠키 신뢰는 `trustedOrigins`가 정하고, 이쪽은 http(s)만 받습니다(`Auth.java:4186-4191`, `OriginsTest.java:32`). 이 경계를 **풀면 안 됩니다.** 그대로 두면 커스텀 스킴 출처는 언제나 본문 전달이고, FR-FLOW-002 AC-5와도 맞습니다.

## 권장 해결안

**D + 문서 수정**입니다. 0.11.0은 `v0.11.0` 태그가 아직 없습니다. 태그 전에 넣으면 릴리스 기록(`docs/release/0.11.0-test-results.md`)을 새 커밋으로 다시 찍어야 합니다.

코드(`NaruServe-api`):

- `common/CorsValues.java:14`: 스킴 조건을 `uri.getScheme() != null`로 바꾸고 나머지 조건(host 있음, userinfo·경로·쿼리·조각 없음, 포트 범위)은 그대로 둡니다. 실측으로 `capacitor://localhost`는 host가 잡히고, `capacitor:/x`, `capacitor:x`, `capacitor://localhost/`, `file:///a`는 host나 경로 조건에서 걸립니다.
- `internal/DefaultCorsOptions.java:27`: 메시지를 "serialized origins (scheme://host[:port]), null or *"로 바꿉니다.
- `CorsOptions.java:6`: Javadoc의 "HTTP(S)"를 고칩니다.
- `DefaultCors`는 고치지 않습니다. `isOrigin`이 넓어지면 `*` 문제도 함께 풀립니다.

문서(ko·en 같이):

- `docs/api/middleware.md:104`: "HTTP(S) origin"을 "`scheme://host[:port]` 출처"로 고치고 `capacitor://localhost` 예를 듭니다.
- `docs/api/auth.md:1532`, `:1536`, 예제 `:1541`, `:1552`: 불가 문장을 지웁니다. iOS `capacitor://localhost`, Android 기본 `https://localhost`(옛 앱은 `http://localhost`)로 고칩니다. 출처는 앱 신원이 아니고 `trustedOrigins`에는 못 넣으며 언제나 본문 전달이라고 씁니다. CapacitorHttp로 보내는 요청은 Origin이 없어 같은 본문 전달이고 CORS 설정이 필요 없다는 한 문장을 더합니다.
- Express 대응은 `cors({origin: ['capacitor://localhost', ...]})`로 그대로 맞습니다.

요구 처리(모드 `sdd`, 대상 요구는 모두 `Stability=evolving`이라 게이트 없음. ID를 손대지 않고 MCP로만 변경합니다):

1. FR-DX-010: AC-7을 추가합니다. "origin이 스킴과 무관하게 `scheme://host[:port]`이면 등록할 수 있고, `*`는 그런 출처 모두에 grant를 주며, 경로·userinfo가 있거나 host가 없는 값은 설정에서 거부하고 요청에서는 grant를 주지 않는다."
2. OPS-FLOW-001 AC-3: 마지막 절을 "커스텀 스킴 출처는 `allowedOrigins`에는 넣을 수 있고 `trustedOrigins`에는 넣을 수 없다"로 바꿉니다. Requirement 문장은 이미 맞으니 그대로 둡니다.
3. FR-FLOW-002 AC-5: 2026-11-05의 Decision 문장을 지우고 목록을 원래 의도대로 `capacitor://localhost`를 포함하게 고칩니다.
4. 세 요구는 `verified`이므로 AC를 바꾼 뒤 상태를 되돌리고, 테스트가 통과한 뒤 AC별 증거를 붙여 한 건씩 다시 `verified`로 올립니다. 일괄 승급은 하지 않습니다.

테스트(먼저 실패를 확인합니다):

- `CorsTest`
  - 목록의 `capacitor://localhost`, `ionic://localhost`: preflight 204, ACAO 되돌림, 오류 응답의 grant 유지(FR-DX-010 AC-6과 함께).
  - 목록에 없는 커스텀 스킴: 403, grant 없음.
  - `*`: 커스텀 스킴에 `*` grant.
  - 거부 값: `capacitor://localhost/`, `capacitor:x`, `file://`, `capacitor://u@localhost`를 설정과 요청 양쪽에서 확인.
- `Wave8LoginTest`
  - `OPS_FLOW_001_AC3_capacitorIsRefusedByAllowedOrigins`를 "등록되고 preflight 뒤 BOTH 로그인이 ACAO와 함께 본문 전달, Set-Cookie 없음"으로 바꿉니다. 지금까지 없던 "preflight까지 포함한 끝에서 끝" 검사입니다.
  - `FR_FLOW_002_AC5_trustedOriginBesideCors`의 목록에 `capacitor://localhost`를 더합니다.
  - `.trustedOrigins("capacitor://localhost")`가 설정 위반인지 고정합니다. 현재 이 값을 직접 쓴 테스트는 없습니다.
- `Wave7DocsTest`: `OPS_FLOW_001_AC3_capacitorCannotBeListed`를 새 문장으로 바꾸고, `https://localhost` 문장을 고정합니다.
- Kotlin 테스트는 시그니처가 바뀌지 않아 추가하지 않습니다. `CorsServletTest`는 같은 미들웨어를 쓰므로 `./gradlew build`로 함께 돌립니다.

범위 밖이며 별도 항목으로 남길 것:

- WebSocket의 `OriginCheck.tuple`도 http(s)만 받습니다(`OriginCheck.java:65-68`). `webSocket(o -> o.allowedOrigins("capacitor://localhost"))`는 기동 경고만 내고 절대 일치하지 않으며(`WebSocketUpgrade.java:73-75`), `*`만 통과합니다. WS는 CORS와 별개라는 FR-DX-010 AC-5, 별개 요구 FR-WS-003 소관이므로 이번 수정에 넣지 않습니다. 다만 iOS Capacitor가 WebSocket을 쓰면 같은 문제에 부딪힐 수 있습니다.

## 확인하지 못한 것

- 실제 iOS 기기·시뮬레이터 실측은 없습니다. WKWebView가 POST와 preflight에 정확히 `Origin: capacitor://localhost`를 보낸다는 것은 Ionic 공식 문서와 2019–2023년 포럼 보고에 근거합니다. 최신 iOS와 Capacitor 버전에서 직접 보지는 못했습니다. 권장안을 구현하면 기기 한 번 확인을 권합니다.
- CapacitorHttp가 네이티브 쪽에서 `Origin`을 더하지 않는다는 것은 `HttpRequestHandler.swift`까지 확인했습니다. `CapacitorUrlRequest` 내부, Android 쪽 구현, GET 프록시에서 웹뷰가 넣는 헤더는 보지 못했습니다. Capacitor HTTP 문서 자체에는 CORS라는 말이 없고, CORS 우회 설명은 Ionic CORS 문서에 있습니다.
- `CAPACITOR_HTTPS_SCHEME`의 실제 값은 직접 읽지 못했습니다. 설정 문서의 기본값 `https`로 판단했습니다.
- 앱 간 저장소 분리(다른 Capacitor 앱이 우리 앱의 쿠키나 토큰에 닿지 못함)는 iOS 앱 샌드박스 지식에 따른 판단이고, Apple 문서로 확인하지 않았습니다.
- `.trustedOrigins("capacitor://localhost")`의 거부는 코드와 `Origins.of("ftp://x")` 테스트, 오류 메시지로 판단했고, 이 값으로 실행하지는 않았습니다.
- auth의 401이 `app.cors`와 직접 쓴 CORS 미들웨어를 같이 쓸 때 ACAO를 잃는지는 실행하지 않았습니다. 500과 404만 실측했습니다.
- Fetch 표준의 ABNF는 요약 도구를 거쳐 읽었습니다. CORS check 알고리즘 본문은 읽지 못했습니다.
- iOS ATS가 평문 `http://` 서버 호출을 막는지 등 CORS 밖의 제약은 살피지 않았습니다.
- iOS Capacitor를 실제로 쓰는 NaruServe 사용자 수는 알 수 없습니다.

출처:
- [Ionic CORS 문서](https://ionicframework.com/docs/troubleshooting/cors)
- [Capacitor 설정 문서](https://capacitorjs.com/docs/config)
- [Capacitor HTTP 문서](https://capacitorjs.com/docs/apis/http)
- [Capacitor 6.0 업데이트 안내](https://capacitorjs.com/docs/updating/6-0)
- [Capacitor native-bridge.ts](https://raw.githubusercontent.com/ionic-team/capacitor/main/core/native-bridge.ts)
- [HttpRequestHandler.swift](https://raw.githubusercontent.com/ionic-team/capacitor/main/ios/Capacitor/Capacitor/Plugins/HttpRequestHandler.swift)
- [WebViewAssetHandler.swift](https://raw.githubusercontent.com/ionic-team/capacitor/main/ios/Capacitor/Capacitor/WebViewAssetHandler.swift)
- [CAPInstanceDescriptor.swift](https://raw.githubusercontent.com/ionic-team/capacitor/main/ios/Capacitor/Capacitor/CAPInstanceDescriptor.swift)
- [CapConfig.java](https://raw.githubusercontent.com/ionic-team/capacitor/main/android/capacitor/src/main/java/com/getcapacitor/CapConfig.java)
- [expressjs/cors](https://raw.githubusercontent.com/expressjs/cors/master/lib/index.js)
- [WHATWG Fetch](https://fetch.spec.whatwg.org/)
- [WHATWG HTML 출처 직렬화](https://html.spec.whatwg.org/multipage/browsers.html#ascii-serialisation-of-an-origin)
- [MDN 금지 요청 헤더](https://developer.mozilla.org/en-US/docs/Glossary/Forbidden_request_header)
- [Ionic 포럼: Capacitor iOS preflight](https://forum.ionicframework.com/t/preflight-response-is-not-successful-in-capacitor-ios-platform/168433)
