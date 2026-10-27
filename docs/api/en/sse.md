# SSE (Server-Sent Events)

[한국어](../sse.md) | [English](sse.md)

SSE is a one-way server-to-client stream consumed by the browser's `EventSource`. Express requires you to write the headers and frame `res.write` data yourself; NaruServe starts it with `res.sse()`.

```java
app.get("/api/events", (req, res) -> {
    SseClient client = res.sse();
    client.send("connected", "ready");
    client.onClose(() -> app.logger().info("SSE closed"));
});
```

The Express equivalent:

```javascript
app.get('/api/events', (req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
  res.write('event: connected\ndata: ready\n\n');
  req.on('close', () => console.log('SSE closed'));
});
```

## res.sse()

This corresponds to Express's `res.writeHead(200, { 'Content-Type': 'text/event-stream' })` plus `res.write(...)`.

- Sets `Content-Type: text/event-stream; charset=UTF-8` and `Cache-Control: no-cache, no-transform`, then switches to streaming mode.
- **The connection stays open after the handler returns.** Give the `SseClient` to a service and call `send` from another thread.
- `onClose` runs when the stream ends. Unlike Express's `req.on('close')`, it may not run immediately when the peer leaves: the core detects disconnection only during writes. Detection may take up to twice the heartbeat interval plus one second (about 31 seconds by default). Unsubscribe and release resources there, but do not assume immediate execution. Lower `heartbeatInterval` when faster detection matters. A listener added after closure notifications have finished runs immediately. A listener added during pending closure, before notifications run, runs with the other listeners at termination.
- Events sent by the handler thread before it returns write directly to the socket, outside the send queue limit. Any events already queued by other threads are written first. After the handler returns, `send` queues events for ordered framework delivery.
- A `HEAD` request sends headers only and returns an already-closed client. Its `send` returns `false`, and `onClose` runs immediately.

```java
app.get("/api/progress", (req, res) -> {
    SseClient client = res.sse();
    ProgressService progress = req.service(ProgressService.class);
    progress.subscribe(client);                         // Calls client.send(...) from another thread
    client.onClose(() -> progress.unsubscribe(client)); // Runs immediately if already closed
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

This covers Express's `res.write(...)`, `res.end()` and `req.on('close', ...)`. All methods may be called from any thread, including after the handler returns.

| Method | Meaning |
|---|---|
| `send(event, data)` | Event with `event:` and `data:`; CR, LF or NUL in the event name throws `IllegalArgumentException` |
| `send(data)` | Only `data:`; delivered as a browser `message` event |
| `send(SseEvent)` | Event with optional `id` and `retry` |
| `close()` | Ends the stream after draining queued events; repeated calls are safe |
| `isOpen()` | `true` while the stream is open |
| `onClose(listener)` | Runs `() -> ...` once at termination; runs immediately when closure notifications already finished |

`send` returns `true` when written or queued, and `false` when already closed or when that send terminates the stream. If another thread's send overflows the queue, the stream ends and `send` returns `false`. Remove subscriptions when you receive `false`.

Newlines in `data` become separate `data:` lines. After a heartbeat interval with no writes, the framework sends a `: ping` comment to keep idle proxies from closing the connection. `EventSource` ignores comments.

```java
app.get("/api/ticks", (req, res) -> {
    SseClient client = res.sse();
    if (!client.send("tick", "1")) {   // false: stream already ended
        return;
    }
    client.send("plain message");      // data only: EventSource message event
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

Build an event object rather than manually writing `id: 100\nevent: progress\ndata: ...\n\n` as in Express:

```java
client.send(SseEvent.event("progress")
    .id("100")
    .data("{\"progress\":50}")
    .retry(3000));
```

```kotlin
client.send(SseEvent.event("progress").id("100").data("{\"progress\":50}").retry(3000))
```

Wire output always uses `event`, `id`, `retry`, `data` order, regardless of builder-call order:

```text
event: progress
id: 100
retry: 3000
data: {"progress":50}

```

| Method | Field |
|---|---|
| `SseEvent.event(name)` | `event:` |
| `.id(String)` | `id:`; returned in `Last-Event-ID` on reconnect |
| `.data(String)` | `data:` |
| `.retry(long millis)` | `retry:`; reconnection delay |

## Configuration

Express has no SSE settings, so you implement heartbeats with code such as `setInterval(() => res.write(': ping\n\n'), 15000)`. Configure NaruServe through `app.sse(...)` before `listen()`.

| Setting | Default | Range | On overflow |
|---|---|---|---|
| `heartbeatInterval` | 15 seconds | 1 second–1 hour | — |
| `sendQueueSize` | 256 KiB | 1 byte–16 MiB | Ends the stream; `send` returns `false` |

```java
app.sse(o -> o.heartbeatInterval(30, TimeUnit.SECONDS).sendQueueSize(512 * 1024));
```

```kotlin
app.sse { o -> o.heartbeatInterval(30, TimeUnit.SECONDS).sendQueueSize(512 * 1024) }
```

Out-of-range values throw `IllegalArgumentException` naming the setting and allowed range.

## Reconnection and Last-Event-ID

When reconnecting, `EventSource` sends the last received event ID in `Last-Event-ID`. Express's `req.get('Last-Event-ID')` becomes `req.lastEventId()`:

```java
app.get("/api/events", (req, res) -> {
    String lastId = req.lastEventId();   // null on the first connection
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

The framework does not store events. Your application must retain them to replay missed events.

## Limits and shutdown

- Each connection has a 256 KiB send queue by default. A slow client that fills it terminates the stream; remaining queued events are discarded and the overflowing `send` returns `false`.
- `app.server(options -> options.maxSseStreams(256))` sets simultaneous SSE capacity: default 128, maximum 1024. Requests beyond capacity receive `503`. See [server settings](app.md).
- `app.shutdown(...)` closes open SSE streams and runs `onClose`. It sends a final `event: shutdown` / `data: shutdown` notification. Clients that do not read it are forcibly disconnected at the timeout. See [shutdown](app.md#shutdown).
- With `NaruServe-servlet`, forced termination depends on the container's write timeout. See [Servlet shutdown](servlet.md#shutdown).

## SSE compared with WebSocket

| | SSE | WebSocket |
|---|---|---|
| Direction | Server → client | Bidirectional |
| Protocol | Ordinary HTTP response | Separate protocol after Upgrade |
| Browser API | `EventSource`, automatic reconnection | `WebSocket` |
| Messages | Text | Text and binary |
| Typical use | Progress, notifications, log streams | Chat and bidirectional control |
