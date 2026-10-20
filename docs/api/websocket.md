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

`app.ws(path, handler)`는 다른 라우트 메서드처럼 `RestApp`을 돌려주므로 이어서 호출할 수 있습니다. 핸들러는 `WebSocketHandler`(`void handle(Request req, WebSocketSession ws) throws Exception`)이며 `@FunctionalInterface`라 Java·Kotlin 람다로 씁니다.
핸들러는 `101` 응답을 보낸 뒤 연결마다 한 번 실행됩니다(서버 종료 중에 열린 연결에서는 실행되지 않습니다). 그래서 핸들러가 실행될 때는 이미 연결이 열려 있고, 핸들러에서 응답 코드를 바꿀 수 없습니다. 여기서 리스너를 등록하고 반환합니다. 연결은 반환한 뒤에도 유지됩니다.
연결 거부는 핸들러가 아니라 `app.use` 미들웨어에서 하세요. 미들웨어는 `101` 전에 실행되고, 핸들러는 `101` 응답 뒤에 실행됩니다.
Express(express-ws)의 `app.ws('/ws', (ws, req) => ...)`에 대응합니다.

- `req`는 Upgrade 요청입니다. `req.param`, `req.query`, `req.header`, `req.cookie`, `req.service`, `req.attribute`를 쓸 수 있습니다.
- `req`는 핸들러 본문에서만 쓰세요. `onText` 같은 리스너는 핸들러가 반환한 뒤 메시지가 올 때 실행됩니다. 리스너에서 필요한 값(서비스, 파라미터)은 핸들러에서 미리 변수로 꺼내 두거나 `ws.attribute`에 저장하세요.
- 경로 패턴은 HTTP 라우트와 같습니다(`/ws/rooms/:room`).
- 같은 경로에 일반 `app.get`을 함께 둘 수 있습니다. `Upgrade: websocket` 요청만 `app.ws`로 갑니다.
- `app.ws` 라우트만 있는 경로에 `Upgrade` 없는 요청이 오면 `426 Upgrade Required`로 답합니다. 코어는 `Upgrade: websocket`과 `Connection: Upgrade` 헤더, 표준 JSON 오류 본문을 씁니다.
  TinyHttpd 한계: over the TinyHttpd adapter the `426` answer carries `Upgrade: websocket` but not `Connection: Upgrade`, because TinyHttpd's `ExchangeResponse` replaces the `Connection` header on non-`101` responses; this is a deferred TinyHttpd limitation (TinyHttpd가 고칠 때까지 남습니다).

```java
app.ws("/ws/rooms/:room", (req, ws) -> {
    String room = req.param("room");
    ws.attribute("room", room);
    ws.onText(message -> ws.sendText(room + ": " + message));
});
```

```kotlin
app.ws("/ws") { req, ws -> ws.onText { m -> ws.sendText("Echo: $m") } }
```

## WebSocketSession

핸들러의 두 번째 인자 `ws`입니다. 메서드는 보내기, 받기, 연결 관리로 나뉩니다.

### 보내기

| 메서드 | 설명 |
|---|---|
| `sendText(String)` | 텍스트 메시지 |
| `sendBinary(byte[])` | 바이너리 메시지 |
| `sendJson(Object)` | `JsonCodec`으로 직렬화한 텍스트 메시지 |
| `ping()` | Ping 프레임 |

Express(express-ws)의 `ws.send(text)`는 `ws.sendText(text)`, 바이너리 `ws.send(buffer)`는 `ws.sendBinary(bytes)`입니다.
보내기 메서드는 **여러 스레드에서 동시에 불러도 안전**하고, 부른 순서대로 나갑니다. 메시지는 연결별 송신 큐(기본 1 MiB, `sendQueueSize`)에 쌓였다가 앱 전체가 함께 쓰는 송신 스레드 풀이 보냅니다. 그래서 `send*`는 상대가 느려도 기다리지 않고 바로 돌아옵니다.
상대가 느려서 큐가 가득 차면 예외를 던지지 않고, 그 메시지와 큐에 남은 메시지를 버린 뒤 close `1008`(이유 `send queue overflow`)로 연결을 닫습니다. 보내기 호출 자체는 정상으로 돌아오며, 닫힌 뒤의 `send*`는 아무것도 보내지 않습니다. `onClose`로 끝난 것을 알 수 있습니다.

```java
app.ws("/notify", (req, ws) -> {
    ws.sendText("hello");
    ws.sendBinary(new byte[] {1, 2, 3});
    ws.sendJson(Collections.singletonMap("type", "welcome"));
});
```

```kotlin
app.ws("/notify") { req, ws ->
    ws.sendText("hello")
    ws.sendJson(mapOf("type" to "welcome"))
}
```

### 받기

| 메서드 | 리스너 |
|---|---|
| `onText(listener)` | `message -> ...` (`String`) |
| `onBinary(listener)` | `bytes -> ...` (`byte[]`) |
| `onJson(Class<T>, listener)` | `value -> ...` (`T`). 텍스트 메시지를 JSON으로 읽습니다. |
| `onClose(listener)` | `(code, reason) -> ...` |
| `onError(listener)` | `error -> ...` (`Throwable`) |

Express(express-ws)의 `ws.on('message', m => ...)`는 `ws.onText(m -> ...)`(바이너리는 `onBinary`, JSON은 `onJson`), `ws.on('close', ...)`는 `ws.onClose((code, reason) -> ...)`, `ws.on('error', ...)`는 `ws.onError(error -> ...)`입니다.

```java
app.ws("/chat", (req, ws) -> {
    ChatService chat = req.service(ChatService.class);
    ws.onJson(ChatMessage.class, msg -> chat.broadcast(msg));
    ws.onError(error -> app.logger().error("ws failed", error));
});
```

`onJson`의 JSON 파싱 실패는 `onError`로 전달되고 연결은 열린 채로 남습니다. 그 메시지만 건너뛰므로 다음 메시지는 정상으로 받습니다.
핸들러나 `onText`·`onBinary`·`onJson` 리스너가 예외를 던지면 그 예외가 `onError`로 전달되고 요청 ID와 함께 ERROR 로그가 남은 뒤 close `1011`로 연결을 닫습니다. JSON 파싱 실패는 리스너의 예외가 아니므로 연결을 닫지 않습니다. `onClose`·`onError` 리스너가 던진 예외는 ERROR 로그만 남깁니다. `onClose`는 연결마다 정확히 한 번 실행됩니다.
리스너는 모두 Java 함수형 인터페이스라 Java·Kotlin 람다로 씁니다.

```kotlin
app.ws("/chat") { req, ws ->
    ws.onBinary { bytes -> ws.sendBinary(bytes) }
    ws.onClose { code, reason -> app.logger().info("closed $code $reason") }
    ws.onError { error -> app.logger().error("ws failed", error) }
}
```

### 연결 관리

| 메서드 | 설명 |
|---|---|
| `id()` | 세션 ID |
| `isOpen()` | 열려 있는지 |
| `close()` | 정상 종료 (`close(1000, "")`와 같습니다) |
| `close(code, reason)` | 코드와 이유를 지정해 종료 (`ws.close(1000, "bye")`) |
| `attribute(key, value)` | 이 연결에만 쓰는 값 저장. `null`은 지웁니다. |
| `attribute(key, Class<T>)` | 값 읽기. 없으면 `null` |

Express(express-ws)의 `ws.close(code, reason)`는 `ws.close(code, reason)` 그대로입니다.
세션 속성은 요청 속성(`req.attribute`)과 별개이며 연결이 끝날 때까지 유지됩니다.

서버가 `app.shutdown(...)`으로 종료되면 열린 연결을 close 코드 `1001 Going Away`로 닫습니다. 종료 대기가 끝난 뒤 열린 세션은 close 프레임 없이 끊습니다.

```java
app.ws("/session", (req, ws) -> {
    ws.attribute("user", req.header("X-User"));
    ws.onText(message -> {
        if ("bye".equals(message)) {
            ws.close(1000, "bye");
        }
    });
});
```

```kotlin
app.ws("/session") { req, ws ->
    ws.attribute("user", req.header("X-User"))
    ws.onText { m -> if (m == "bye") ws.close(1000, "bye") }
}
```

## 인증과 Origin

브라우저 WebSocket은 **CORS로 보호되지 않습니다.** 다른 사이트의 페이지도 쿠키를 실어 연결할 수 있습니다.

- `app.use("/ws", auth)` 미들웨어는 Upgrade 요청에 실행됩니다. 실패하면 Upgrade 전에 응답으로 거부합니다.
- 프레임워크가 Upgrade 전에 `Origin`을 검사합니다. 기본값은 `Origin`이 없거나 같은 출처인 요청만 허용하고 나머지는 `403`으로 거부합니다. 다른 출처를 허용하려면 `app.webSocket(o -> o.allowedOrigins("https://app.example.com"))`처럼 목록을 정합니다. 목록은 `scheme://host[:port]` 형식이며, 목록을 정해도 `Origin`이 없는 요청과 같은 출처 요청은 계속 허용됩니다. `allowedOrigins("*")`는 모든 `Origin`을 허용합니다. 같은 출처는 요청의 `Host`와 `req.isSecure()`로 정하므로, TLS를 끝내는 리버스 프록시 뒤에서는 `app.trustProxy(true)`를 켜고 프록시가 `Host`와 `X-Forwarded-Proto`를 넘겨야 브라우저의 `https://` `Origin`이 같은 출처로 맞습니다. 인자 없이 `allowedOrigins()`를 부르면 기본값으로 돌아갑니다.
- 단, 브라우저가 아닌 클라이언트는 `Origin`을 마음대로 보낼 수 있으므로 `Origin` 검사는 인증을 대신하지 않습니다.
- 연결된 뒤의 메시지에는 HTTP 미들웨어가 실행되지 않습니다.

Express에서 `cors({ origin: [...] })`로 정하던 허용 출처 목록에 해당합니다.

```java
app.use("/ws", (req, res, next) -> {
    if (!auth.isAuthorized(req)) {
        res.status(401).end();
        return;
    }
    next.run();
});
```

```java
app.webSocket(o -> o.allowedOrigins("https://app.example.com", "http://localhost:3000"));
```

```kotlin
app.webSocket { o -> o.allowedOrigins("https://app.example.com") }
```

## 설정

`app.webSocket(o -> ...)`로 앱 전체의 WebSocket 설정을 바꿉니다. `listen` 전에 부르며, 범위를 벗어난 값은 설정 이름과 범위를 담은 `IllegalArgumentException`으로 거부합니다. 무제한 값은 없습니다. Express(express-ws)에서 `ws` 서버 옵션(`maxPayload` 등)으로 정하던 값입니다.

```java
app.webSocket(o -> o.allowedOrigins("https://app.example.com")
        .maxMessageSize(64 * 1024)
        .sendQueueSize(256 * 1024)
        .pingInterval(15, TimeUnit.SECONDS)
        .idleTimeout(1, TimeUnit.MINUTES));
```

```kotlin
app.webSocket { o -> o.allowedOrigins("https://app.example.com").idleTimeout(1, TimeUnit.MINUTES) }
```

| 설정 | 항목 | 기본값 | 최대값 | 범위 | 넘으면 |
|---|---|---:|---:|---|---|
| `maxMessageSize` | 받는 메시지 하나(텍스트·바이너리) | 1 MiB | 16 MiB | 1 B~16 MiB | 연결 종료(1009) |
| `sendQueueSize` | 연결별 송신 큐 | 1 MiB | 64 MiB | 1 B~64 MiB | 연결 종료(1008), 이유 `send queue overflow` |
| `pingInterval` | Ping 주기 | 30초 | 1시간 | 1초~1시간 | 한 주기 동안 받은 프레임이 없으면 Ping을 보내고, 다음 주기에도 없으면 연결 종료(1001) |
| `idleTimeout` | idle timeout | 5분 | 24시간 | 1초~24시간 | 텍스트·바이너리 메시지를 주고받지 않은 채 지나면 연결 종료(1001). Ping·Pong은 세지 않습니다. |

메시지 크기 한도는 프레임 하나와 조각난(fragmented) 메시지 전체에 똑같이 걸립니다.
Pong timeout은 Ping 주기와 같습니다. TinyHttpd의 `WebSocketListener`에 Pong 콜백이 없어 별도 Pong timeout은 지원하지 않습니다. 그래서 Pong이 아니라도 어떤 프레임이든 받으면 연결이 살아 있는 것으로 봅니다.

## 한도

메시지 크기, 송신 큐, Ping 주기(30초), idle timeout(5분) 한도는 [설정](#설정)의 표에 있습니다.

| 항목 | 기본값 |
|---|---|
| 최대 연결 수 | 128. `app.server(options -> options.maxUpgradedConnections(256))`처럼 `maxUpgradedConnections`로 바꿉니다(최대 65536). 넘치면 `503`. |
| handshake timeout | 따로 없습니다. handshake는 `headerTimeout`과 `bodyReadTimeout`으로만 제한되고 전용 upgrade 기한은 지원하지 않습니다. |

프로토콜 위반(마스킹 안 된 클라이언트 프레임, 잘못된 UTF-8, 한도 초과 프레임)은 연결을 닫습니다.
대용량 바이너리 스트리밍은 v1에서 지원하지 않습니다.

TinyHttpd 어댑터의 한계 세 가지(TinyHttpd가 고칠 때까지 남습니다):

- `426` 응답에 `Connection: Upgrade`가 실리지 않습니다([핸들러](#핸들러) 참고).
- 별도 Pong timeout이 없습니다. Pong timeout은 Ping 주기와 같습니다.
- 전용 handshake(upgrade) 기한이 없습니다. `headerTimeout`과 `bodyReadTimeout`이 handshake를 제한합니다.
