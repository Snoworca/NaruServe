# WebSocket

[한국어](../websocket.md) | [English](websocket.md)

Serve WebSocket (RFC 6455) on **the same port** as HTTP, much like Express's `express-ws`:

```java
app.ws("/ws", (req, ws) -> {
    ws.onText(message -> ws.sendText("Echo: " + message));
    ws.onClose((code, reason) -> app.logger().info("closed " + code));
});
```

With express-ws:

```javascript
app.ws('/ws', (ws, req) => {
  ws.on('message', msg => ws.send('Echo: ' + msg));
  ws.on('close', () => console.log('closed'));
});
```

**The handler parameter order is reversed:** express-ws uses `(ws, req)`; NaruServe uses `(req, ws)`.

Kotlin:

```kotlin
app.ws("/ws") { req, ws ->
    ws.onText { message -> ws.sendText("Echo: $message") }
    ws.onClose { code, reason -> app.logger().info("closed $code") }
}
```

## Handler

`app.ws(path, handler)` returns `RestApp` for chaining. `WebSocketHandler` is a Java functional interface with `void handle(Request req, WebSocketSession ws) throws Exception`, so Java and Kotlin lambdas work directly.

The handler runs once per connection after sending `101` (except for a connection opened while shutdown is in progress). It therefore cannot change the HTTP response status. Register listeners and return; the connection remains open. Reject connections in `app.use` middleware, which runs before `101`.

This corresponds to express-ws's `app.ws('/ws', (ws, req) => ...)`.

- `req` describes the Upgrade request. Use `req.param`, `req.query`, `req.header`, `req.cookie`, `req.service` and `req.attribute` there.
- Use `req` only in the handler body. Listeners such as `onText` run later, after the handler returns. Extract services and parameters into variables or `ws.attribute` first.
- Path patterns match HTTP routes, for example `/ws/rooms/:room`.
- An ordinary `app.get` can share the path; only WebSocket Upgrade requests select `app.ws`.
- A path with only an `app.ws` route returns `426 Upgrade Required` to ordinary non-upgrade GET/HEAD requests. The core and TinyHttpd adapter send `Upgrade: websocket`, `Connection: Upgrade` and the standard JSON error body.
- Automatic OPTIONS returns `204` with `Allow`; disabled extension methods and unknown methods receive `501` before routing.

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

The second handler argument, `ws`, supports sending, receiving and connection management.

### Sending

| Method | Meaning |
|---|---|
| `sendText(String)` | Text message |
| `sendBinary(byte[])` | Binary message |
| `sendJson(Object)` | Text serialized with `JsonCodec` |
| `ping()` | Ping frame |

Express's `ws.send(text)` becomes `ws.sendText(text)`; binary `ws.send(buffer)` becomes `ws.sendBinary(bytes)`.

Sending methods are safe to call concurrently from multiple threads and deliver queued work in order. An application-wide sender pool drains each connection's bounded queue (default 1 MiB), so `send*` returns without waiting for a slow peer.

If the peer cannot keep up and the queue fills, NaruServe drops the overflowing message and queued messages and closes with code `1008`, reason `send queue overflow`. The send call returns normally. A separate cap allows at most 1024 unfinished send tasks per connection, including the currently transmitting task. Empty text, empty binary and Ping count as tasks, preventing an unbounded backlog of zero-byte messages. This cap uses the same `1008` policy. A close frame replaces waiting messages. Sends after closure do nothing; use `onClose` to observe termination.

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

### Receiving

| Method | Listener |
|---|---|
| `onText(listener)` | `message -> ...` (`String`) |
| `onBinary(listener)` | `bytes -> ...` (`byte[]`) |
| `onJson(Class<T>, listener)` | `value -> ...` (`T`); parses text as JSON |
| `onClose(listener)` | `(code, reason) -> ...` |
| `onError(listener)` | `error -> ...` (`Throwable`) |

Express's `ws.on('message', ...)` maps to `onText`, `onBinary` or `onJson`; `ws.on('close', ...)` maps to `onClose`; `ws.on('error', ...)` maps to `onError`.

```java
app.ws("/chat", (req, ws) -> {
    ChatService chat = req.service(ChatService.class);
    ws.onJson(ChatMessage.class, msg -> chat.broadcast(msg));
    ws.onError(error -> app.logger().error("ws failed", error));
});
```

An `onJson` parse failure invokes `onError` and skips that message while keeping the connection open. If the handler or an `onText`, `onBinary` or `onJson` listener throws, the error is delivered to `onError`, logged at ERROR with the request ID, and closes the connection with `1011`. A JSON parse failure is distinct from a listener exception and does not close it. Exceptions from `onClose` and `onError` are only logged at ERROR. `onClose` runs exactly once per connection. All listeners are Java functional interfaces usable from Java/Kotlin lambdas.

```kotlin
app.ws("/chat") { req, ws ->
    ws.onBinary { bytes -> ws.sendBinary(bytes) }
    ws.onClose { code, reason -> app.logger().info("closed $code $reason") }
    ws.onError { error -> app.logger().error("ws failed", error) }
}
```

### Connection management

| Method | Meaning |
|---|---|
| `id()` | Session ID |
| `isOpen()` | Whether still open |
| `close()` | Normal close: `close(1000, "")` |
| `close(code, reason)` | Close with code/reason, for example `ws.close(1000, "bye")` |
| `attribute(key, value)` | Store a value; literal `null` removes it without a cast |
| `attributeAs(key, Class<T>)` | Read a value; `null` when absent |
| `removeAttribute(key)` | Remove a connection attribute; absent keys are harmless |

Express's `ws.close(code, reason)` has the same spelling. Session attributes are separate from request attributes and last until the connection ends.

`app.shutdown(...)` closes open connections with `1001 Going Away`. Sessions opened after the shutdown wait expires are disconnected without a close frame.

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

## Authentication and Origin

Browser WebSockets are **not protected by HTTP CORS**. A page on another site can initiate a connection with cookies.

- `app.use("/ws", auth)` runs on Upgrade requests and can reject them before the upgrade.
- Before Upgrade, the framework accepts requests without `Origin` or with a same-origin header by default, rejecting others with `403`. Configure `app.webSocket(o -> o.allowedOrigins("https://app.example.com"))` to add origins using `scheme://host[:port]`. Missing and same-origin headers remain accepted even with a configured list. `allowedOrigins("*")` accepts all Origins. Same-origin comparison uses `Host` and `req.isSecure()`. Behind a TLS-terminating reverse proxy, enable `app.trustProxy(true)` and forward `Host` and `X-Forwarded-Proto` so an HTTPS browser Origin matches. Calling `allowedOrigins()` without arguments restores defaults.
- Nonbrowser clients can forge `Origin`; this check does not replace authentication.
- HTTP middleware does not run for messages after connection establishment.

HTTP CORS configuration does not replace WebSocket Origin checks. Configure allowed WebSocket origins separately with `app.webSocket(o -> o.allowedOrigins(...))`.

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

## Configuration

Call `app.webSocket(o -> ...)` before `listen` to change application-wide settings. Invalid values throw `IllegalArgumentException` with the setting name and allowed range; unlimited values are unsupported. These correspond to express-ws / `ws` server options such as `maxPayload`.

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

| Setting | Item | Default | Maximum | Range | When exceeded |
|---|---|---:|---:|---|---|
| `maxMessageSize` | Incoming text/binary message | 1 MiB | 16 MiB | 1 B–16 MiB | Close `1009` |
| `sendQueueSize` | Per-connection send queue | 1 MiB | 64 MiB | 1 B–64 MiB | Close `1008`, reason `send queue overflow` |
| `pingInterval` | Ping period | 30 seconds | 1 hour | 1 second–1 hour | After one interval with no received frame, send Ping; another idle interval closes with `1001` |
| `idleTimeout` | Message inactivity | 5 minutes | 24 hours | 1 second–24 hours | Close `1001` after no sent/received text or binary message; Ping/Pong do not count |

Message size applies both to individual frames and assembled fragmented messages. Pong timeout follows the ping interval and is not separately configurable because TinyHttpd's `WebSocketListener` has no Pong callback. Any incoming frame, not only Pong, therefore counts as proof of liveness.

## Limits

See [configuration](#configuration) for message size, queue, ping (30 seconds) and idle timeout (5 minutes).

| Item | Default |
|---|---|
| Maximum connections | 128; configure `app.server(options -> options.maxUpgradedConnections(256))`, maximum 65536. Excess upgrades receive `503` |
| Handshake timeout | No dedicated upgrade deadline; only `headerTimeout` and `bodyReadTimeout` bound the handshake |

Protocol violations such as unmasked client frames, invalid UTF-8 and oversized frames close the connection. Large binary streaming is not supported in v1.

Two adapter limitations remain until TinyHttpd changes: no separate Pong timeout, and no dedicated handshake/upgrade deadline.
