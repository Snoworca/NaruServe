## 7. WebSocket, SSE, long-connection shutdown

Covers issues #13, #14 and #15; acceptance tests T19, T20, T21, T22, T23, T24, T25, T26, T35 (upgrade part), T38 (WebSocket and SSE parts), T44. Depends on sections: 1, 2, 3, 4, 5, 6.

### WebSocket registration and upgrade

- [D-294] `app.ws(path, handler)` SHALL take a functional handler `(req, ws) -> ...` and use the same pattern syntax as HTTP routes.
- [D-295] The WebSocket handler SHALL run once per accepted upgrade, and the connection stays open after it returns.
- [D-296] Global and prefix middleware SHALL run on the upgrade request before the handshake, and a middleware response rejects the upgrade (T22).
- [D-429] For an upgrade request the dispatcher SHALL run the global and prefix middleware chain to completion, with every `next.run()` returned, and only then, when no middleware responded, hand the request to the upgrade extension point of D-106, whose TinyHttpd class calls `WebSocketHandler.handle` on the same worker thread, which sends `101` and then runs the connection's read loop on that thread.
- [D-430] The access-log line of D-173 for an accepted upgrade request SHALL be the D-325 handshake line with status `101`, written in TinyHttpd's `WebSocketListener.onOpen`, which runs on the read thread after the `101` was written and before the first frame is read, while an upgrade rejected before `onOpen` gets its ordinary D-173 line with its rejection status.
- [D-444] The TinyHttpd upgrade class SHALL catch every `HttpException` that `WebSocketHandler.handle` or the `res.upgrade` call inside it throws before the `101` is written (`400` for a malformed handshake, `426` with `Sec-WebSocket-Version: 13`, `403` for a refused `Origin` or a `null` listener, `413` when the upgrade request body exceeds `maxDrainBytes`, `503` with `Retry-After` when `maxUpgradedConnections` is reached) and answer it through the dispatcher's error handling of that request with the exception's status and its headers set on the response, the request's id in the standard error body of D-132 and the access-log line of D-430 with the rejection status, as a T19 test sending `Sec-WebSocket-Version: 12` asserts by checking the `426`, the `Sec-WebSocket-Version: 13` header and the access-log line.
- [D-297] After the upgrade, HTTP middleware MUST NOT run per WebSocket message.
- [D-298] A valid RFC 6455 handshake (`Upgrade`, `Connection`, `Sec-WebSocket-Key`, version 13) SHALL receive `101` with the correct `Sec-WebSocket-Accept` (T19).
- [D-299] A malformed handshake SHALL be rejected without upgrading the connection (T19).
- [D-300] An upgrade whose `Origin` is not on the configured allowlist SHALL be rejected with `403` before the handshake (T22).
- [D-301] Upgrade requests arriving after `STOPPING` began SHALL be rejected without upgrading.
- [D-302] RFC 6455 framing SHALL come from TinyHttpd's websocket module, with NaruServe adding no own frame codec.
- [D-408] A request without `Upgrade` to a path that has only an `app.ws` route SHALL receive `426 Upgrade Required` with `Upgrade: websocket` and `Connection: Upgrade` in an end-to-end test over the TinyHttpd adapter.
- [D-303] Wave 7 SHALL decide the Origin allowlist API and its default when unset, and the handler timing relative to `101`, and update `docs/api/websocket.md` and `docs/api/defaults.md`.

### WebSocket protocol enforcement

- [D-304] An unmasked client frame SHALL close the connection with a protocol error (T20).
- [D-305] A text message with invalid UTF-8 SHALL close the connection with code 1007 (T20).
- [D-306] A message larger than the message limit (default 1 MiB) SHALL close the connection with code 1009 (T20).
- [D-307] Fragmented messages SHALL be reassembled before delivery, with the message limit applied to the reassembled size (T20).
- [D-308] A control frame longer than 125 bytes or fragmented SHALL close the connection with a protocol error.
- [D-309] A Ping from the client SHALL be answered with a Pong, and `ws.ping()` sends a Ping (T21).
- [D-310] `ws.close(code, reason)` SHALL perform the close handshake, after which `isOpen()` returns `false`.

### WebSocketSession

- [D-311] `WebSocketSession` SHALL provide `id`, `sendText`, `sendBinary`, `sendJson`, `ping`, `close()`, `close(code, reason)`, `isOpen`, `onText`, `onBinary`, `onJson`, `onClose`, `onError` and `attribute`, with listeners as `@FunctionalInterface` types.
- [D-312] Session ids SHALL be unique among the sessions of one app.
- [D-313] Session attributes SHALL be stored per connection in a concurrent map, separate from request attributes, and released when the connection closes (T21).
- [D-314] `onClose` listeners SHALL run exactly once per connection with the close code and reason.
- [D-315] `sendJson` and `onJson` SHALL encode and decode through the configured `JsonCodec`.
- [D-316] Services read in the WebSocket handler through `req.service` SHALL be the instances registered in `onStart` (T21).
- [D-317] Wave 7 SHALL decide how an `onJson` parse failure is reported (error callback or close) and update `docs/api/websocket.md`.

### WebSocket send queue

- [D-318] Each session SHALL own a bounded outbound queue (default 1 MiB) because TinyHttpd's `WebSocket` send is synchronous.
- [D-417] WebSocket outbound queues SHALL be drained by one app-wide sender executor whose thread limit equals the WebSocket connection limit of D-404 (default 128), running at most one write task per session at a time and never queueing one session's task behind another session's in-flight write, so a stalled session holds only its own sender thread until TinyHttpd's write timeout (default 30 s) aborts its socket, and idle sender threads end after a keep-alive time.
- [D-319] Messages from concurrent senders SHALL be sent whole and in queue-entry order, with no interleaved frames (T23).
- [D-320] A slow or stalled client MUST NOT delay sends to other sessions, as a T23 test with at least 20 stalled sessions, more than any fixed pool of 16, next to one healthy session asserts by receiving the healthy session's message within 1 s.
- [D-321] When the outbound queue overflows, the session SHALL apply the overflow policy decided in #13 (exception or close) and release its queue (T23).
- [D-322] A closed session SHALL leave no queue, thread or attribute behind (T23).

### WebSocket limits and logging

- [D-323] The idle timeout, ping interval and handshake timeout of WebSocket connections SHALL be configurable through APIs and defaults decided in #13, with the ping interval and idle timeout mapped as D-423 and D-425 state and the maximum connection count taken from D-404.
- [D-419] The WebSocket message size limit (default 1 MiB, D-306), the WebSocket outbound queue size (default 1 MiB, D-318) and the SSE outbound queue size (default 256 KiB, D-406) SHALL be configurable through APIs whose names wave 7 decides and records in `docs/api/websocket.md`, `docs/api/sse.md` and `docs/api/defaults.md`, with the pong timeout fixed by D-424 instead of configured and the configured WebSocket message limit passed to both `WebSocketHandler.maxFramePayloadBytes` and `WebSocketHandler.maxMessageBytes`, so one unfragmented frame and a fragmented message meet the same limit, as a T20 test with a non-default limit asserts by sending a message above it once as one frame and once fragmented and expecting close 1009 both times.
- [D-423] The WebSocket ping interval setting SHALL be passed to TinyHttpd's `WebSocketHandler.pingInterval`, whose read loop sends a Ping after one interval without an inbound frame and closes with 1001 after a second such interval, so NaruServe runs no ping scheduler of its own.
- [D-424] The WebSocket pong timeout SHALL equal the ping interval in v1, because TinyHttpd's read loop treats any inbound frame as the answer and its `WebSocketListener` has no Pong callback, with `docs/api/websocket.md` and `docs/api/defaults.md` stating this fixed relationship, a separate pong timeout recorded as a deferred TinyHttpd gap under D-020, and a T21 test asserting that a silent client is closed with 1001 within two ping intervals plus 1 s.
- [D-425] The WebSocket idle timeout SHALL be enforced by NaruServe's one scheduler thread, which detects a session that has neither received nor sent a text or binary message for that timeout, hands close 1001 to that session's sender slot of D-417 and schedules a check 1 s later that aborts the session's socket through the `UpgradedConnection` captured as D-435 states when the close task has not returned by then, so the scheduler thread never writes to a socket or waits on one session, because TinyHttpd counts Ping and Pong frames as activity and never idles out a peer that answers its pings.
- [D-324] WebSocket upgrades SHALL count against the WebSocket connection limit of D-404, whose mapping onto TinyHttpd's `maxUpgradedConnections` wave 5 owns, and an upgrade beyond it is answered `503`.
- [D-325] The WebSocket access log SHALL write one line at the `101` handshake and a separate line at close with close code and duration (T38).

### SSE stream

- [D-326] `res.sse()` SHALL commit status `200` with `Content-Type: text/event-stream; charset=UTF-8` and `Cache-Control: no-cache, no-transform`, return an `SseClient` and put the response in `STREAMING` (T24).
- [D-327] An SSE connection SHALL stay open after the handler returns until the client disconnects, the app closes it or shutdown runs (T25).
- [D-328] `client.send(event, data)` SHALL write an `event:` line and the data as `data:` lines followed by a blank line (T24).
- [D-329] Data containing CR, LF or CRLF SHALL be split into one `data:` line per line (T24).
- [D-330] `SseEvent.event(name).id(...).data(...).retry(millis)` SHALL produce the `event`, `id`, `retry` and `data` fields in the order shown in `docs/api/sse.md` (T24).
- [D-331] An SSE event name or id containing CR, LF or `NUL` SHALL be rejected with an exception.
- [D-332] The framework SHALL send `: ping` comment heartbeats at an interval decided in #14 (T25).
- [D-333] `SseClient.send` SHALL be safe to call from several threads, writing whole events in call order.
- [D-334] `onClose` listeners SHALL run exactly once when the stream ends by client disconnect, error or shutdown (T25).
- [D-335] On the TinyHttpd adapter, SSE SHALL be written as a chunked streaming body through TinyHttpd's `Response.setHeader`, `flushBuffer()` and `getOutputStream()` on the request's worker thread, never through `startEventStream()`, whose fixed `Cache-Control: no-cache` and separate `retry` block cannot meet D-326 and D-330.
- [D-406] Each SSE connection SHALL own a NaruServe bounded outbound queue (default 256 KiB) that `SseClient.send` from any thread fills with whole serialised events and that the D-106 drain loop empties to the socket on the thread the adapter chose, sending heartbeats when idle, with the overflow policy decided in #14.
- [D-336] Each open SSE stream on the TinyHttpd adapter SHALL hold exactly one TinyHttpd worker thread and count against a NaruServe counter in the event-stream adapter class, never TinyHttpd's `maxEventStreams`, bounded by the SSE stream limit of D-404 with `503` beyond it, so the SSE thread count never exceeds that limit and open streams never take the HTTP worker budget.
- [D-337] `req.lastEventId()` SHALL return the `Last-Event-ID` sent by a reconnecting `EventSource` (T24).
- [D-338] The SSE access log SHALL write one line when the stream ends, covering start time, duration, bytes and the closing cause (T38).
- [D-339] Wave 7 SHALL decide the heartbeat interval, data-only send and explicit close APIs and the queue overflow policy, using the SSE stream limit of D-404, and update `docs/api/sse.md` and `docs/api/defaults.md`.

### Shutdown with long connections

- [D-340] Shutdown SHALL close every open WebSocket session with close code 1001 Going Away (T21, T44).
- [D-341] Shutdown SHALL close every open SSE stream and run its `onClose` listeners (T44).
- [D-342] In-flight short HTTP requests SHALL complete before `onStop` runs when they finish within the timeout (T44).
- [D-343] WebSocket and SSE connections that do not close within the shutdown timeout SHALL be force-closed.
- [D-435] A before-wait shutdown participant of D-401 SHALL stop the WebSocket sender executor from taking new messages, discard every session's queued messages, hand close 1001 to each open session's sender slot of D-417 as its last task, wait until every close task returned or until 1 s or the time left to the D-162 deadline passed, whichever comes first, and then abort through `UpgradedConnection.close()` the socket of every session whose close task has not returned, whatever the age of its earlier writes, where the TinyHttpd upgrade class captures that connection by passing `WebSocketHandler.handle` a `ResponseWrapper` whose `upgrade` keeps the returned connection, and the bound rests on that abort alone with no write tracking: closing the socket makes a blocked write throw, including TinyHttpd's read-loop Ping and Pong writes and a close frame blocked on a full receive window, which all hold the same write lock, so TinyHttpd's later stop callback `close(1001)` finds `closeSent` set or the socket closed and returns at once instead of waiting up to the write timeout on the stopping thread.
- [D-436] A before-wait shutdown participant of D-401 SHALL, without waiting, append to every open SSE stream's outbound queue of D-406 a closing notice exempt from the queue limit, whose form wave 7 decides and records in `docs/api/sse.md` under D-339, and mark the stream ended, so its D-106 drain loop wakes, writes the queued events and the notice, ends the chunked body, runs the `onClose` listeners of D-334 once and returns its worker, because TinyHttpd's stop closes only its own event streams and upgraded connections and otherwise waits for a D-335 stream until the deadline, leaving the adapter stop to wait only for short HTTP exchanges and for streams stalled on a full socket, which TinyHttpd aborts at the deadline (D-343).
- [D-445] Each closing participant of D-435 and D-436 SHALL first set a closed flag under the lock of the session or stream registry it closes and then take its snapshot of open sessions or streams, after which `res.sse()` returns a stream that is ended at once with the closing notice of D-436 and its `onClose` listeners run, and an upgrade that reaches the upgrade extension point is answered `503` with `Connection: close` before any `101` instead of being registered, while every session or stream registered before the flag is in the snapshot.
- [D-446] The before-wait participants of D-435 and D-436 SHALL run while TinyHttpd still accepts new connections, for at most the 1 s close wait of D-435, with every request that reaches the dispatcher in that phase answered `503` with `Connection: close` under D-166 and every upgrade refused under D-445, which `docs/api/lifecycle.md` records as a deviation from object.md §11.4, whose step 2 stops accepting new connections and upgrades before the WebSocket and SSE goodbye of step 3.
- [D-344] `shutdown(timeout, unit)` SHALL return within the timeout plus a fixed margin recorded in `docs/api/lifecycle.md` and used as the T44 tolerance, where the core and adapter waits share the one deadline of D-162 and the margin covers TinyHttpd's `stop(grace)` overrun of up to 1.5 s after the grace (an acceptor join of up to 1000 ms and a worker wait of up to 500 ms) plus the adapter release wait of D-107, with the WebSocket close wait of D-435 and the SSE closing of D-436 counted inside the deadline and T44 using `onStop` callbacks and owned resources that return at once.
- [D-439] A T44 test SHALL open at least two WebSocket sessions whose clients stop reading while the server sends to them until their writes block, one healthy WebSocket session and one SSE stream, call `shutdown` with a short timeout and assert that it returns within the timeout plus the margin of D-344, that the healthy session receives close 1001, that the SSE client receives the closing notice of D-436 and that the stalled sessions' sockets are closed.
- [D-345] After `shutdown` returns, no NaruServe or TinyHttpd thread of that app, no WebSocket or SSE queue and no temporary upload file SHALL remain (T44).

### Load bounds

- [D-346] A JVM test SHALL hold 100 or more concurrent WebSocket and SSE connections from the loopback address under the default limits of D-404 and assert that thread count and heap growth stay below bounds written in the test (T26).
- [D-416] The defaults of D-404 and D-417 SHALL bound the threads of one app to 451: 192 TinyHttpd request workers (64 HTTP plus 128 SSE), 128 WebSocket read threads kept by upgraded connections, 128 WebSocket sender threads, one NaruServe scheduler thread for WebSocket idle timeouts (D-425), and TinyHttpd's acceptor and watchdog threads, where the request workers and read threads take TinyHttpd's `workerStackBytes` of 256 KiB, the NaruServe sender and scheduler threads request 256 KiB explicitly and the acceptor and watchdog use the platform default stack, so 449 threads request 112.25 MiB of stack in total, with the thread total and that stack figure recorded in `docs/api/defaults.md` for API 21 devices and the thread total used as the T26 thread bound.
- [D-347] Device-specific tuning of long-connection limits for low-end Android SHALL be recorded as a manual follow-up in the release test results.

