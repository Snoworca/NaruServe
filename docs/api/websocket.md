# WebSocket

HTTP와 **같은 포트**에서 WebSocket(RFC 6455)을 받습니다. Express의 `express-ws`와 비슷합니다.

```java
app.ws("/ws", (req, ws) -> {
    ws.onText(message -> ws.sendText("Echo: " + message));
    ws.onClose((code, reason) -> app.logger().info("closed " + code));
});
```

express-ws라면:

```javascript
app.ws('/ws', (ws, req) => {
  ws.on('message', msg => ws.send('Echo: ' + msg));
  ws.on('close', () => console.log('closed'));
});
```

**핸들러 인자 순서가 반대입니다.** express-ws는 `(ws, req)`, NaruServe는 `(req, ws)`입니다.

Kotlin:

```kotlin
app.ws("/ws") { req, ws ->
    ws.onText { message -> ws.sendText("Echo: $message") }
    ws.onClose { code, reason -> app.logger().info("closed $code") }
}
```

## 핸들러

`app.ws(path, handler)`의 핸들러는 Upgrade 요청마다 한 번 실행됩니다(`101` 응답 전/후 실행 시점은 **(미확정)**). 여기서 리스너를 등록하고 반환합니다. 연결은 반환한 뒤에도 유지됩니다.
연결 거부는 핸들러가 아니라 `app.use` 미들웨어에서 하세요.

- `req`는 Upgrade 요청입니다. `req.param`, `req.query`, `req.header`, `req.cookie`, `req.service`, `req.attribute`를 쓸 수 있습니다.
- `req`는 핸들러 본문에서만 쓰세요. `onText` 같은 리스너는 핸드셰이크가 끝난 뒤에 실행됩니다. 리스너에서 필요한 값(서비스, 파라미터)은 핸들러에서 미리 변수로 꺼내 두거나 `ws.attribute`에 저장하세요.
- 경로 패턴은 HTTP 라우트와 같습니다(`/ws/rooms/:room`).
- 같은 경로에 일반 `app.get`을 함께 둘 수 있습니다. `Upgrade: websocket` 요청만 `app.ws`로 갑니다.
- `app.ws` 라우트만 있는 경로에 `Upgrade` 없는 요청이 오면 `426 Upgrade Required`로 답합니다. 코어는 `Upgrade: websocket`과 `Connection: Upgrade` 헤더, 표준 JSON 오류 본문을 씁니다.
  TinyHttpd 한계: over the TinyHttpd adapter the `426` answer carries `Upgrade: websocket` but not `Connection: Upgrade`, because TinyHttpd's `ExchangeResponse` replaces the `Connection` header on non-`101` responses; this is a deferred TinyHttpd limitation (TinyHttpd가 고칠 때까지 남습니다).

## WebSocketSession

### 보내기

| 메서드 | 설명 |
|---|---|
| `sendText(String)` | 텍스트 메시지 |
| `sendBinary(byte[])` | 바이너리 메시지 |
| `sendJson(Object)` | `JsonCodec`으로 직렬화한 텍스트 메시지 |
| `ping()` | Ping 프레임 |

보내기 메서드는 **여러 스레드에서 동시에 불러도 안전**하고, 부른 순서대로 나갑니다. 메시지는 연결별 송신 큐(기본 1 MiB)에 쌓였다가 전송됩니다.
상대가 느려서 큐가 가득 차면 예외를 던지거나 연결을 닫습니다(정확한 정책 **(미확정)**).

### 받기

| 메서드 | 리스너 |
|---|---|
| `onText(listener)` | `message -> ...` (`String`) |
| `onBinary(listener)` | `bytes -> ...` (`byte[]`) |
| `onJson(Class<T>, listener)` | `value -> ...` (`T`). 텍스트 메시지를 JSON으로 읽습니다. |
| `onClose(listener)` | `(code, reason) -> ...` |
| `onError(listener)` | `error -> ...` (`Throwable`) |

```java
app.ws("/chat", (req, ws) -> {
    ChatService chat = req.service(ChatService.class);
    ws.onJson(ChatMessage.class, msg -> chat.broadcast(msg));
    ws.onError(error -> app.logger().error("ws failed", error));
});
```

`onJson`에서 JSON 파싱이 실패하면 `onError`로 알리거나 연결을 닫습니다(**(미확정)**).
리스너는 모두 Java 함수형 인터페이스라 Java·Kotlin 람다로 씁니다.

### 연결 관리

| 메서드 | 설명 |
|---|---|
| `id()` | 세션 ID |
| `isOpen()` | 열려 있는지 |
| `close()` | 정상 종료 |
| `close(code, reason)` | 코드와 이유를 지정해 종료 (`ws.close(1000, "bye")`) |
| `attribute(key, value)` | 이 연결에만 쓰는 값 저장 |
| `attribute(key, Class<T>)` | 값 읽기. 없으면 `null` |

세션 속성은 요청 속성(`req.attribute`)과 별개이며 연결이 끝날 때까지 유지됩니다.

서버가 `app.shutdown(...)`으로 종료되면 열린 연결을 close 코드 `1001 Going Away`로 닫습니다.

## 인증과 Origin

브라우저 WebSocket은 **CORS로 보호되지 않습니다.** 다른 사이트의 페이지도 쿠키를 실어 연결할 수 있습니다.

- `app.use("/ws", auth)` 미들웨어는 Upgrade 요청에 실행됩니다. 실패하면 Upgrade 전에 응답으로 거부합니다.
- 프레임워크가 Upgrade 전에 `Origin` 허용 목록을 검사합니다. 목록은 앱이 정합니다(설정 API와 목록을 설정하지 않았을 때의 기본 동작(모두 거부/모두 허용)은 **(미확정)**). 단, 브라우저가 아닌 클라이언트는 `Origin`을 마음대로 보낼 수 있으므로 인증을 대신하지 않습니다.
- 연결된 뒤의 메시지에는 HTTP 미들웨어가 실행되지 않습니다.

```java
app.use("/ws", (req, res, next) -> {
    if (!auth.isAuthorized(req)) {
        res.status(401).end();
        return;
    }
    next.run();
});
```

## 한도

| 항목 | 기본값 |
|---|---|
| 메시지 크기(텍스트·바이너리) | 1 MiB |
| 연결별 송신 큐 | 1 MiB |
| 최대 연결 수 | 128. `app.server(options -> options.maxUpgradedConnections(256))`처럼 `maxUpgradedConnections`로 바꿉니다(최대 65536). 넘치면 `503`. |
| idle timeout, Ping 주기, handshake timeout | **(미확정)** |

프로토콜 위반(마스킹 안 된 클라이언트 프레임, 잘못된 UTF-8, 한도 초과 프레임)은 연결을 닫습니다.
대용량 바이너리 스트리밍은 v1에서 지원하지 않습니다.
