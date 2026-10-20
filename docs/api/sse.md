# SSE (Server-Sent Events)

브라우저 `EventSource`로 받는 서버 → 클라이언트 단방향 스트림입니다. Express에서는 헤더를 직접 쓰고 `res.write`로 형식을 맞춰야 했지만, NaruServe는 `res.sse()` 하나로 시작합니다.

```java
app.get("/api/events", (req, res) -> {
    SseClient client = res.sse();
    client.send("connected", "ready");
    client.onClose(() -> app.logger().info("SSE closed"));
});
```

Express라면:

```javascript
app.get('/api/events', (req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
  res.write('event: connected\ndata: ready\n\n');
  req.on('close', () => console.log('SSE closed'));
});
```

## res.sse()

Express의 `res.writeHead(200, { 'Content-Type': 'text/event-stream' })` + `res.write(...)`에 해당합니다.

- `Content-Type: text/event-stream; charset=UTF-8`, `Cache-Control: no-cache, no-transform`을 보내고 스트리밍 모드로 바꿉니다.
- **핸들러가 반환해도 연결은 열려 있습니다.** `SseClient`를 서비스에 넘겨 두고 다른 스레드에서 `send`하면 됩니다.
- 연결이 끝나면 `onClose` 리스너가 실행됩니다. 이미 끝난 뒤 등록한 `onClose`는 즉시 실행됩니다. `close()`나 송신 큐 초과로 끝나는 중이라 리스너가 아직 실행되지 않았다면, 새 리스너도 끝나는 순간 다른 리스너와 함께 실행됩니다.
- 핸들러 스레드가 반환 전에 보낸 이벤트는 소켓에 바로 쓰며 송신 큐 한도를 받지 않습니다. 이때 그 전에 다른 스레드가 큐에 넣은 이벤트를 먼저 씁니다. 반환한 뒤의 `send`는 송신 큐에 넣고 프레임워크가 순서대로 씁니다.
- `HEAD` 요청에는 헤더만 보내고 본문 없이 이미 끝난 클라이언트를 돌려줍니다. 그 클라이언트의 `send`는 `false`이고 `onClose`는 바로 실행됩니다.

```java
app.get("/api/progress", (req, res) -> {
    SseClient client = res.sse();
    ProgressService progress = req.service(ProgressService.class);
    progress.subscribe(client);                         // 다른 스레드에서 client.send(...)
    client.onClose(() -> progress.unsubscribe(client)); // 이미 끝났으면 바로 실행됩니다
});
```

Kotlin:

```kotlin
app.get("/api/events") { req, res ->
    val c = res.sse()
    c.send("connected", "ready")
    c.onClose { }
}
```

## SseClient

Express의 `res.write(...)`, `res.end()`, `req.on('close', ...)`에 해당합니다. 모든 메서드는 어느 스레드에서나, 핸들러가 반환한 뒤에도 부를 수 있습니다.

| 메서드 | 설명 |
|---|---|
| `send(event, data)` | `event:`와 `data:`가 있는 이벤트. 이벤트 이름에 CR, LF, NUL이 있으면 `IllegalArgumentException` |
| `send(data)` | `data:`만 있는 이벤트. `EventSource`에서는 `message` 이벤트입니다 |
| `send(SseEvent)` | `id`, `retry`까지 지정한 이벤트 |
| `close()` | 이미 큐에 넣은 이벤트를 다 쓴 뒤 스트림을 끝냅니다. 여러 번 불러도 됩니다 |
| `isOpen()` | 스트림이 아직 끝나지 않았으면 `true` |
| `onClose(listener)` | 스트림이 끝날 때 한 번 `() -> ...`. 이미 끝나 리스너가 실행된 뒤라면 바로 실행 |

`send`는 이벤트를 쓰거나 큐에 넣었으면 `true`, 스트림이 이미 끝났거나 이번 `send`가 스트림을 끝냈으면 `false`를 돌려줍니다. 다른 스레드의 `send`가 송신 큐를 넘치게 하면 스트림을 끝내고 `false`를 돌려줍니다. `false`를 받으면 구독을 지우세요.

`data`에 줄바꿈이 있으면 여러 `data:` 줄로 나눠 보냅니다. 아무것도 쓰지 않은 채 heartbeat 주기가 지나면 `: ping` 주석을 보냅니다. 프록시가 놀고 있는 연결을 끊지 않게 하려는 것이며 `EventSource`는 주석을 무시합니다.

```java
app.get("/api/ticks", (req, res) -> {
    SseClient client = res.sse();
    if (!client.send("tick", "1")) {   // false: 스트림이 이미 끝났습니다
        return;
    }
    client.send("plain message");      // event: 없이 data:만 → EventSource의 message
    client.close();
});
```

```kotlin
app.get("/api/ticks") { req, res ->
    val client = res.sse()
    if (client.send("tick", "1")) {
        client.send("plain message")
    }
    client.close()
}
```

## SseEvent

Express에서는 `res.write('id: 100\nevent: progress\ndata: ...\n\n')`처럼 손으로 맞추던 이벤트를 객체로 만듭니다.

```java
client.send(SseEvent.event("progress")
    .id("100")
    .data("{\"progress\":50}")
    .retry(3000));
```

```kotlin
client.send(SseEvent.event("progress").id("100").data("{\"progress\":50}").retry(3000))
```

보내는 내용(설정한 순서와 상관없이 `event`, `id`, `retry`, `data` 순서):

```text
event: progress
id: 100
retry: 3000
data: {"progress":50}

```

| 메서드 | 필드 |
|---|---|
| `SseEvent.event(name)` | `event:` |
| `.id(String)` | `id:` — 재접속할 때 `Last-Event-ID`로 돌아옵니다 |
| `.data(String)` | `data:` |
| `.retry(long millis)` | `retry:` — 재접속 대기 시간 |

## 설정

Express에는 SSE 설정이 없어 heartbeat를 `setInterval(() => res.write(': ping\n\n'), 15000)`처럼 직접 씁니다. NaruServe는 `app.sse(...)`로 정하며, 다른 설정처럼 `listen()` 전에만 바꿀 수 있습니다.

| 설정 | 기본값 | 범위 | 넘으면 |
|---|---|---|---|
| `heartbeatInterval` | 15초 | 1초~1시간 | — |
| `sendQueueSize` | 256 KiB | 1바이트~16 MiB | 스트림 종료(`send`가 `false`) |

```java
app.sse(o -> o.heartbeatInterval(30, TimeUnit.SECONDS).sendQueueSize(512 * 1024));
```

```kotlin
app.sse { o -> o.heartbeatInterval(30, TimeUnit.SECONDS).sendQueueSize(512 * 1024) }
```

범위를 벗어난 값은 설정 이름과 범위를 담은 `IllegalArgumentException`으로 거부합니다.

## 재접속과 Last-Event-ID

`EventSource`는 연결이 끊기면 마지막으로 받은 `id`를 `Last-Event-ID` 헤더로 보내며 다시 접속합니다. Express의 `req.get('Last-Event-ID')`는 `req.lastEventId()`입니다.

```java
app.get("/api/events", (req, res) -> {
    String lastId = req.lastEventId();   // 처음 접속이면 null
    SseClient client = res.sse();
    for (Event e : eventStore.after(lastId)) {
        client.send(SseEvent.event(e.type()).id(e.id()).data(e.json()));
    }
});
```

```kotlin
app.get("/api/events") { req, res ->
    val lastId: String? = req.lastEventId()
    val client = res.sse()
    for (e in eventStore.after(lastId)) {
        client.send(SseEvent.event(e.type()).id(e.id()).data(e.json()))
    }
}
```

프레임워크는 이벤트를 저장하지 않습니다. 놓친 이벤트를 다시 보내려면 앱이 저장해 두어야 합니다.

## 한도와 종료

- 연결별 송신 큐 기본값은 256 KiB입니다(`sendQueueSize`). 클라이언트가 느려서 큐가 가득 차면 스트림을 끝냅니다. 큐에 남은 이벤트는 버리고 넘치게 한 `send`는 `false`를 돌려줍니다.
- 최대 동시 SSE 연결 수는 `app.server(options -> options.maxSseStreams(256))`처럼 `maxSseStreams`로 정합니다(기본 128, 최대 1024). 넘치면 `503`으로 답합니다. [서버 설정](app.md#서버-설정)
- `app.shutdown(...)` 때 열린 SSE 연결을 닫고 `onClose`를 실행합니다. 종료할 때 `event: shutdown`과 `data: shutdown`으로 된 종료 알림을 보냅니다. 알림을 읽지 않는 클라이언트는 timeout 때 강제로 끊습니다. [shutdown](app.md#shutdown)

## WebSocket과 비교

| | SSE | WebSocket |
|---|---|---|
| 방향 | 서버 → 클라이언트 | 양방향 |
| 프로토콜 | 일반 HTTP 응답 | Upgrade 후 별도 프로토콜 |
| 브라우저 API | `EventSource` (자동 재접속) | `WebSocket` |
| 메시지 | 텍스트 | 텍스트·바이너리 |
| 쓸 곳 | 진행률, 알림, 로그 스트림 | 채팅, 양방향 제어 |
