# 기본값과 보안

NaruServe는 저사양 Android 5.0 기기에서도 안전하게 돌도록 **모든 입력에 한도를 켠 채로** 시작합니다. 무제한을 기본값으로 쓰지 않습니다(무제한 값 허용 여부는 **(미확정)**). 필요하면 명시적으로 올리세요.

## 크기·개수 한도

아래 값은 사양서의 **초안 기본값**입니다. 실기기 시험 후 바뀔 수 있습니다.

| 항목 | 기본값 | 넘으면 | 설정 |
|---|---:|---|---|
| JSON 요청 본문 | 1 MiB | `413` | **(미확정)** |
| JSON 중첩 깊이 | 64 | `400` | **(미확정)** |
| 업로드 파일 하나 | 32 MiB | `413` | `app.multipart(c -> c.maxFileSize(...))` |
| 업로드 요청 전체 | 64 MiB | `413` | `c.maxRequestSize(...)` |
| 업로드 파트 수 | 20 | `413` | `c.maxParts(...)` |
| 업로드 메모리 임계값 | 64 KiB | 디스크로 전환 | `c.memoryThreshold(...)` |
| HTTP 헤더 전체 | 16 KiB | `431` | **(미확정)** |
| 요청 URL 길이 | 8 KiB | `414` | **(미확정)** |
| WebSocket 메시지 | 1 MiB | 연결 종료 | **(미확정)** |
| WebSocket 송신 큐(연결당) | 1 MiB | 예외 또는 연결 종료 | **(미확정)** |
| SSE 송신 큐(연결당) | 256 KiB | 연결 종료 (**(미확정)**) | **(미확정)** |
| `shutdown` 대기 | 호출 인자(예: 10초) | 강제 종료 | `app.shutdown(timeout, unit)` |

헤더·URL 초과 때의 `431`/`414`는 표준 상태 코드를 적은 것이며 최종 확정 전입니다.

시간·연결 수 한도도 있습니다. 소켓 읽기/쓰기 timeout, 헤더 개수, 대기 큐 길이, 최대 동시 SSE·WebSocket 연결 수를 설정할 수 있습니다(기본값과 설정 API **(미확정)**). 요청이 대기 큐를 넘으면 `503`입니다.

## 기본 보안 정책

| 항목 | 기본 | 바꾸려면 |
|---|---|---|
| 바인딩 주소 | localhost만 | `0.0.0.0` 명시 바인딩 (**(미확정)**). 전역 인증 없이 LAN에 열면 경고 로그 |
| 정적 폴더 목록 | 꺼짐 | `options.directoryListing(true)` |
| 숨김 파일(`.env`, `.git`) | 차단 | — |
| 심볼릭 링크 | 차단 | 명시 허용 (**(미확정)**) |
| `X-Content-Type-Options` | `nosniff` | — |
| 오류 응답의 stack trace | 보내지 않음 | — |
| 접근 로그의 민감 값 | 기록하지 않음 | 바꾸지 않는 것을 권장 |
| 프록시 헤더(`X-Forwarded-*`) | 믿지 않음 | 명시 신뢰 설정 (**(미확정)**) |
| `TRACE`, `CONNECT` 메서드 | 라우팅 안 함 | `app.routerPolicy(...)`에서 `enableTrace`/`enableConnect`를 켭니다. [라우터 정책](routing.md#라우터-정책) |
| JSON 다형성 자동 바인딩 | 끔 | — |

## HTTP 프로토콜 방어

- `Content-Length`와 `Transfer-Encoding`이 충돌하는 요청(HTTP Request Smuggling)을 거부합니다.
- 헤더·응답 분할(CR/LF 삽입)을 막습니다. `res.header`, `res.addCookie`, `res.download`의 파일 이름도 검사합니다.
- 헤더나 본문을 아주 천천히 보내는 공격(slowloris)을 timeout으로 끊습니다.

## 앱이 할 일

프레임워크가 대신하지 않는 것입니다.

- **인증과 권한**: 미들웨어로 구현하세요. [미들웨어](middleware.md)
- **CSRF**: 쿠키로 인증하는 API라면 미들웨어로 막으세요.
- **WebSocket 인증**: 미들웨어로 구현하세요. `Origin` 허용 목록은 프레임워크가 Upgrade 전에 검사하지만, 목록은 앱이 정해야 합니다(설정 API·미설정 때 기본 동작 **(미확정)**). [WebSocket](websocket.md#인증과-origin)
- **업로드 파일 이름·내용 검증**: [파일 업로드](multipart.md#보안)
- **TLS**: 인터넷에 노출하는 평문 서버는 안전하지 않습니다. TLS/WSS 지원은 HTTP 어댑터마다 다르며 Android 5.0의 오래된 TLS 구현은 따로 시험해야 합니다.
- **서비스의 스레드 안전성**: [수명주기와 서비스](lifecycle.md#저장소-범위)
