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

- `Content-Type: text/event-stream; charset=UTF-8`, `Cache-Control: no-cache, no-transform`을 보내고 스트리밍 모드로 바꿉니다.
- **핸들러가 반환해도 연결은 열려 있습니다.** `SseClient`를 서비스에 넘겨 두고 다른 스레드에서 `send`하면 됩니다.
- 연결이 끝나면 `onClose` 리스너가 실행됩니다.

```java
app.get("/api/progress", (req, res) -> {
    SseClient client = res.sse();
    ProgressService progress = req.service(ProgressService.class);
    progress.subscribe(client);                         // 다른 스레드에서 client.send(...)
    client.onClose(() -> progress.unsubscribe(client));
    // 이미 끊긴 뒤에 등록한 onClose가 즉시 호출되는지는 (미확정)입니다. 서비스는 send 실패 때도 구독을 지우세요.
});
```

## SseClient

| 메서드 | 설명 |
|---|---|
| `send(event, data)` | `event:`와 `data:`가 있는 이벤트 |
| `send(SseEvent)` | `id`, `retry`까지 지정한 이벤트 |
| `onClose(listener)` | 연결이 끝날 때 `() -> ...` |

`data`에 줄바꿈이 있으면 여러 `data:` 줄로 나눠 보냅니다. 연결 유지용 heartbeat 주석(`: ping`)은 프레임워크가 보냅니다(주기 **(미확정)**).

`data`만 보내기, 직접 닫기 같은 메서드는 **(미확정)** 입니다.

## SseEvent

```java
client.send(SseEvent.event("progress")
    .id("100")
    .data("{\"progress\":50}")
    .retry(3000));
```

보내는 내용:

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

## 재접속과 Last-Event-ID

`EventSource`는 연결이 끊기면 마지막으로 받은 `id`를 `Last-Event-ID` 헤더로 보내며 다시 접속합니다.

```java
app.get("/api/events", (req, res) -> {
    String lastId = req.lastEventId();   // 처음 접속이면 null
    SseClient client = res.sse();
    for (Event e : eventStore.after(lastId)) {
        client.send(SseEvent.event(e.type()).id(e.id()).data(e.json()));
    }
});
```

프레임워크는 이벤트를 저장하지 않습니다. 놓친 이벤트를 다시 보내려면 앱이 저장해 두어야 합니다.

## 한도와 종료

- 연결별 송신 큐 기본값은 256 KiB입니다. 클라이언트가 느려서 큐가 가득 차면 연결을 닫습니다(**(미확정)**).
- 최대 동시 SSE 연결 수를 설정할 수 있습니다(설정 API **(미확정)**).
- `app.shutdown(...)` 때 열린 SSE 연결을 닫고 `onClose`를 실행합니다.

## WebSocket과 비교

| | SSE | WebSocket |
|---|---|---|
| 방향 | 서버 → 클라이언트 | 양방향 |
| 프로토콜 | 일반 HTTP 응답 | Upgrade 후 별도 프로토콜 |
| 브라우저 API | `EventSource` (자동 재접속) | `WebSocket` |
| 메시지 | 텍스트 | 텍스트·바이너리 |
| 쓸 곳 | 진행률, 알림, 로그 스트림 | 채팅, 양방향 제어 |
