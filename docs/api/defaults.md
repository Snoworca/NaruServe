# 기본값과 보안

NaruServe는 저사양 Android 5.0 기기에서도 안전하게 돌도록 **모든 입력에 한도를 켠 채로** 시작합니다. 무제한을 기본값으로 쓰지 않고, 어떤 설정도 무제한 값을 받지 않습니다. 모든 설정에 최대값이 있고, 범위를 벗어난 값은 설정 이름과 범위를 담은 `IllegalArgumentException`으로 거부합니다. 무제한을 막은 이유는 설정 실수 하나로 저사양 기기의 메모리와 스레드가 바닥나지 않게 하려는 것입니다. 필요하면 최대값 안에서 명시적으로 올리세요.

## 크기·개수 한도

아래 기본값은 Android 5.0 저사양 기기를 기준으로 잡았습니다. `maxHeaderSize`처럼 이름만 적은 설정은 `app.server(options -> options.maxHeaderSize(32768))`로 바꾸는 `ServerOptions` 설정입니다([RestApp](app.md#서버-설정)).

| 항목 | 기본값 | 최대값 | 넘으면 | 설정 |
|---|---:|---:|---|---|
| JSON 요청 본문 | 1 MiB | 1 GiB | `413` | `app.jsonLimit(bytes)` |
| JSON 중첩 깊이 | 64 | 255 | `400` | `new GsonJsonCodec(gson, maxDepth)` |
| 폼 본문 | 1 MiB | 1 GiB | `413` | `app.formLimit(bytes)`. urlencoded 본문의 크기이자 한 요청의 multipart 텍스트 필드 내용 합계입니다. |
| 업로드 파일 하나 | 32 MiB | 4 GiB | `413` | `app.multipart(c -> c.maxFileSize(...))` |
| 업로드 요청 전체 | 64 MiB | 4 GiB | `413` | `c.maxRequestSize(...)` |
| 업로드 파트 수 | 20 | 10000 | `413` | `c.maxParts(...)` |
| 업로드 메모리 임계값 | 64 KiB | 16 MiB | 디스크로 전환 | `c.memoryThreshold(...)` |
| 파트 헤더 한 줄 | 8192 B | — | `413` | 따로 없습니다(고정). |
| 파트 헤더 전체 | 16 KiB | — | `413` | 따로 없습니다(고정). 파트 하나의 헤더 줄 합계입니다. |
| `UploadFile.bytes()` | 8 MiB | — | `IllegalStateException` | 따로 없습니다(고정). 더 큰 파트는 `inputStream()`, `transferTo`, `save`로 읽습니다. |
| HTTP 헤더 전체 | 16 KiB | 1 MiB | `431` | `maxHeaderSize` |
| 헤더 한 줄 | 8190 B | — | `431` | 따로 없습니다. TinyHttpd 입력 버퍼의 `inputBufferBytes - 2`이며, 어댑터가 버퍼를 `max(8192, maxRequestLineLength + 2)`로 잡으므로 `maxRequestLineLength`를 8190보다 크게 할 때만 함께 커집니다. `maxHeaderSize`로는 바뀌지 않습니다. |
| 헤더 개수 | 100 | 10000 | `431` | `maxHeaderCount` |
| 요청 줄 길이 | 8190 B | 65534 B | `414` | `maxRequestLineLength` |
| WebSocket 메시지 | 1 MiB | 16 MiB | 연결 종료(1009) | `app.webSocket(o -> o.maxMessageSize(bytes))`. 프레임 하나와 조각난 메시지 전체에 똑같이 걸립니다. |
| WebSocket 송신 큐(연결당) | 1 MiB | 64 MiB | 연결 종료(1008) | `app.webSocket(o -> o.sendQueueSize(bytes))`. 넘친 메시지와 큐에 남은 메시지를 버리고 이유 `send queue overflow`로 닫으며, 보내기 호출은 정상으로 돌아옵니다. |
| SSE 송신 큐(연결당) | 256 KiB | 16 MiB | 스트림 종료 | `app.sse(o -> o.sendQueueSize(bytes))` |
| `shutdown` 대기 | 호출 인자(예: 10초) | — | 강제 종료 | `app.shutdown(timeout, unit)` |

업로드 임시 폴더의 기본값은 `java.io.tmpdir`(Android에서는 앱 캐시 폴더)이며 `c.tempDirectory(dir)`로 바꿉니다. 업로드 한도는 모두 1 이상이고 최대값을 넘는 값은 받지 않습니다. [파일 업로드](multipart.md#한도-설정)

요청 줄 길이는 URL만이 아니라 `GET /path?query HTTP/1.1` 한 줄 전체의 바이트 수입니다. URL 길이 대신 요청 줄을 재는 이유는 TinyHttpd가 그 줄을 한 번에 읽어 한도를 검사하기 때문입니다.

## 시간·연결 수 한도

소켓 시간 한도는 `headerTimeout`, `bodyReadTimeout`, `writeTimeout`으로, 헤더 개수는 `maxHeaderCount`로, 동시 연결은 `maxWorkers`, `maxSseStreams`, `maxConnections`, `maxUpgradedConnections`, `maxConnectionsPerAddress`로 정하며, 대기 큐 길이는 따로 설정하지 않고 이 값들에서 계산됩니다. 모두 `app.server(options -> ...)`의 `ServerOptions` 설정입니다.

| 항목 | 기본값 | 최대값 | 넘으면 | 설정 |
|---|---:|---:|---|---|
| 헤더 수신 시간 | 10초 | 1시간 | `408` | `headerTimeout(timeout, unit)` |
| 본문 읽기 timeout | 30초 | 1시간 | `408` | `bodyReadTimeout(timeout, unit)` |
| 쓰기 timeout | 30초 | 1시간 | 연결 종료 | `writeTimeout(timeout, unit)` |
| 최소 본문 전송 속도 | 240 B/s | 104857600 B/s | `408` | `minBodyRate(bytesPerSecond)` |
| HTTP 작업 스레드 | 64 | 1024 | 대기 큐에서 기다림 | `maxWorkers(workers)`. 엔진 스레드 풀은 `maxWorkers + maxSseStreams`(기본 192)이고, SSE 스트림은 그중 `maxSseStreams`개까지만 쓰므로 일반 요청에는 항상 `maxWorkers`개 이상이 남습니다. 열린 SSE 스트림이 없으면 일반 요청이 풀 전체를 쓸 수 있어, `maxWorkers`는 일반 요청 스레드의 상한이 아니라 보장된 몫입니다. |
| 동시 SSE 스트림 | 128 | 1024 | `503` | `maxSseStreams(streams)` |
| 열린 연결 | 512 | 65536 | 소켓 수준 `503` | `maxConnections(connections)`. `maxWorkers + maxSseStreams` 이상이어야 하며, 작으면 `listen()`이 `IllegalStateException`을 던집니다. |
| 대기 큐 | 320 | — | 소켓 수준 `503` | 따로 없습니다. `maxConnections - maxWorkers - maxSseStreams`로 정해집니다. |
| 업그레이드 연결 | 128 | 65536 | `503` | `maxUpgradedConnections(connections)` (WebSocket 연결 수) |
| 주소 하나의 연결 | 256 | 65536 | 소켓 수준 `503` | `maxConnectionsPerAddress(connections)` |

시간 설정의 최소값은 1 ms, 나머지 설정의 최소값은 1입니다.

WebSocket 시간 한도는 `ServerOptions`가 아니라 `app.webSocket(o -> ...)`로, SSE heartbeat는 `app.sse(o -> ...)`로 정합니다([WebSocket](websocket.md#설정), [SSE](sse.md#설정)).

| 항목 | 기본값 | 최대값 | 넘으면 | 설정 |
|---|---:|---:|---|---|
| WebSocket Ping 주기 | 30초 | 1시간 | 한 주기 동안 받은 프레임이 없으면 Ping을 보내고, 다음 주기에도 없으면 연결 종료(1001) | `pingInterval(time, unit)`. 최소 1초 |
| WebSocket idle timeout | 5분 | 24시간 | 연결 종료(1001) | `idleTimeout(time, unit)`. 최소 1초. 텍스트·바이너리 메시지만 셉니다. |
| SSE heartbeat 주기 | 15초 | 1시간 | 한 주기 동안 쓴 것이 없으면 `: ping` 주석을 보냄 | `heartbeatInterval(time, unit)`. 최소 1초 |

Pong timeout은 Ping 주기와 같습니다. TinyHttpd의 `WebSocketListener`에 Pong 콜백이 없어 별도 Pong timeout은 지원하지 않습니다. WebSocket handshake는 `headerTimeout`과 `bodyReadTimeout`으로만 제한되고 전용 upgrade 기한은 지원하지 않습니다.

### 스레드 수와 stack

기본 한도에서 앱 하나가 띄울 수 있는 스레드 수입니다. 어느 설정도 무제한이 아니므로 스레드 수도 위 한도에서 정해집니다.

| 항목 | 기본값 | 최대값 | 넘으면 | 설정 |
|---|---:|---:|---|---|
| 앱 하나의 스레드 합계 | 452 | — | — | 따로 없습니다. 엔진 스레드 풀 `maxWorkers + maxSseStreams`(192) + 업그레이드 연결 읽기 스레드 `maxUpgradedConnections`(128) + WebSocket 송신 스레드 `maxUpgradedConnections`(128) + WebSocket idle 스케줄러 1 + TinyHttpd acceptor·watchdog 2 + `naruserve-keeper` 1입니다. 설계에서 정한 451에 `listen()`부터 `shutdown`까지 도는 daemon이 아닌 `naruserve-keeper` 하나를 더한 값입니다. |
| 요청 경로 스레드 stack | 256 KiB | — | — | 따로 없습니다. 위 스레드 중 acceptor·watchdog·keeper를 뺀 요청 경로의 449개(엔진 풀 192, 업그레이드 연결 읽기 128, WebSocket 송신 128, idle 스케줄러 1)가 스레드마다 256 KiB stack을 요청하므로, API 21 기기에서 모두 띄우면 112.25 MiB입니다. |

## 저사양 Android 튜닝(수동 후속 작업)

위 기본값은 JVM 부하 테스트(WebSocket 60개와 SSE 60개를 함께 연 T26)로 확인한 값입니다. 저사양 Android 5.0 기기에 맞춘 `maxSseStreams`, `maxUpgradedConnections`, WebSocket과 SSE의 `sendQueueSize` 조정은 v1 이후 항목입니다. Android 기기 검증이 v1 범위 밖이므로 이 조정은 v1 릴리스의 수동 후속 작업이 아니며, 위 기본값은 자동 테스트가 JVM에서 확인한 값이며 기기에 맞춘 값이 아닙니다. 메모리가 작은 기기에서는 이 값들을 직접 낮춰 보세요.

## 기본 보안 정책

| 항목 | 기본 | 바꾸려면 |
|---|---|---|
| 바인딩 주소 | `127.0.0.1`(IPv4 루프백) | `app.server(options -> ...)`의 `bindAddress("0.0.0.0")`. 루프백이 아닌 주소면 `listen()`이 인증 여부와 상관없이 항상 보안 경고 로그를 남기며, 인증은 앱이 미들웨어로 직접 붙여야 합니다. 프레임워크는 앱에 인증이 있는지 알 수 없어 경고를 끄지 않습니다. |
| 정적 폴더 목록 | 꺼짐 | `options.directoryListing(true)` |
| 정적 폴더 index 파일 | `index.html` | `options.index(name)` |
| SPA fallback | 꺼짐 | `options.spaFallback(true)`. 켜도 `/api/`, `/ws/` 아래에는 동작하지 않습니다. |
| 숨김 파일(`.env`, `.git`) | 차단 | — |
| 심볼릭 링크 | 차단 | `options.allowSymlinks(true)`로 허용합니다. 허용해도 링크의 실제 대상이 마운트 루트 밖이면 `404`입니다. |
| `Cache-Control` | 붙이지 않음 | `app.defaultCache(policy)`로 정하면 `Cache-Control`이 없는 응답에 붙습니다. `res.cache`, `res.header`, `options.cache`가 정한 값은 그대로 둡니다. |
| `X-Content-Type-Options` | `nosniff` | — |
| 오류 응답의 stack trace | 보내지 않음 | — |
| 접근 로그의 민감 값 | 기록하지 않음 | 바꾸지 않는 것을 권장 |
| 프록시 헤더(`X-Forwarded-*`) | 믿지 않음 | `app.trustProxy(true)`. 첫 `X-Forwarded-For` 헤더의 맨 왼쪽 값과 첫 `X-Forwarded-Proto` 값을 `req.remoteAddress()`와 `req.isSecure()`에 씁니다. 값은 검증하지 않고 그대로 쓰므로 그 헤더를 덮어쓰는 리버스 프록시 뒤에서만 켜세요. `Forwarded` 헤더는 읽지 않습니다. |
| `Host` 헤더 | 바인딩 주소, `localhost`, IP 리터럴만 받음 | `app.server(options -> options.allowedHosts("myphone.local"))`. 목록에 없는 `Host`는 `421`과 표준 오류 본문으로 거부합니다(DNS rebinding 방어). |
| `TRACE`, `CONNECT` 메서드 | 라우팅 안 함 | `app.routerPolicy(...)`에서 `enableTrace`/`enableConnect`를 켭니다. [라우터 정책](routing.md#라우터-정책) |
| JSON 다형성 자동 바인딩 | 끔 | — |

## HTTP 프로토콜 방어

- `Content-Length`와 `Transfer-Encoding`이 충돌하는 요청(HTTP Request Smuggling)을 거부합니다.
- 헤더·응답 분할(CR/LF 삽입)을 막습니다. `res.header`, `res.addCookie`, `res.download`의 파일 이름도 검사합니다.
- 헤더나 본문을 아주 천천히 보내는 공격(slowloris)을 timeout으로 끊습니다.

### 전송 수준 거부

TinyHttpd가 요청을 해석하는 단계에서 거부하는 `400`(잘못된 요청 형식), `408`(헤더 수신 시간 초과), `414`(요청 줄이 김), `417`(지원하지 않는 `Expect`), `431`(헤더가 큼), `501`(지원하지 않는 `Transfer-Encoding`), `505`(지원하지 않는 HTTP 버전)와 작업 스레드·연결이 모자랄 때의 소켓 수준 `503`은 짧은 평문 응답으로 답하며 `onError`, 표준 오류 JSON, 접근 로그를 거치지 않습니다. 이 응답이 앱을 거치지 않는 이유는 요청을 아직 해석하지 못해 라우트·미들웨어에 넘길 `Request`가 없기 때문입니다. 본문을 읽는 중의 `408`(`bodyReadTimeout`, `minBodyRate`)은 핸들러가 본문을 읽을 때 `HttpException`으로 나므로 `onError`와 표준 오류 본문을 거칩니다. 요청 경로가 잘못된 퍼센트 인코딩, 인코딩된 `/`(`%2F`), `NUL`, `..`·`.` 구간 때문에 거부되는 `400`도 기본 TinyHttpd 어댑터에서는 엔진이 경로를 먼저 검사하므로 같은 짧은 평문 응답이고 `onError`, 표준 오류 JSON, 접근 로그를 거치지 않습니다.
`Host` 허용 목록에 걸린 `421`은 요청을 해석한 뒤에 정해지므로 [표준 오류 형식](middleware.md#표준-오류-형식)의 본문으로 답합니다.

## 앱이 할 일

프레임워크가 대신하지 않는 것입니다.

- **인증과 권한**: 미들웨어로 구현하세요. [미들웨어](middleware.md)
- **CSRF**: 쿠키로 인증하는 API라면 미들웨어로 막으세요.
- **WebSocket 인증**: 미들웨어로 구현하세요. 프레임워크는 Upgrade 전에 `Origin`을 검사하며, 기본값은 `Origin`이 없거나 같은 출처인 요청만 허용하고 나머지는 `403`으로 거부합니다. 다른 출처는 `app.webSocket(o -> o.allowedOrigins("https://app.example.com"))`로 허용하며, `allowedOrigins("*")`는 모든 `Origin`을 허용합니다. `Origin` 검사는 인증을 대신하지 않습니다. [WebSocket](websocket.md#인증과-origin)
- **업로드 파일 이름·내용 검증**: [파일 업로드](multipart.md#보안)
- **TLS**: 기본 TinyHttpd 어댑터(`NaruServe-tinyhttpd`)는 v1에서 TLS 리스너를 열지 않습니다. 인터넷에 노출하는 평문 서버는 안전하지 않으므로 TLS를 끝내는 리버스 프록시 뒤에 두고 `app.trustProxy(true)`를 켜세요. TinyHttpd 어댑터의 TLS/WSS는 v1 이후 항목이며, 이유는 둘입니다. v1 개발 범위를 정한 epic #20의 하위 이슈 어디에도 이 작업이 없고, `object.md` §14.3과 §20.3이 요구하는 API 21 TLS provider와 인증서 시험을 따로 해야 하는데 Android 기기 검증이 v1 범위 밖이라 v1은 이 시험을 하지 않습니다.
- **서비스의 스레드 안전성**: [수명주기와 서비스](lifecycle.md#저장소-범위)
