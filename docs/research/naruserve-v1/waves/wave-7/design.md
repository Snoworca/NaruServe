# Wave 7 design — 7. WebSocket, SSE, long-connection shutdown

Source: frozen run design `docs/research/naruserve-v1/design/00.design.md` lines 509–606 (design lock `docs/research/naruserve-v1/design/00.design.lock.json`). GitHub issues: #13, #14, #15. Depends on waves: wave-1, wave-2, wave-3, wave-4, wave-5, wave-6.

The items below are the frozen design items of this wave, verbatim and with their run ids; this wave implements every one of them. Integration items I-001..I-021 of the run design also hold at this wave's merge.


Covers issues #13, #14 and #15; acceptance tests T19, T20, T21, T22, T23, T24, T25, T26, T35 (upgrade part), T38 (WebSocket and SSE parts), T44. Depends on sections: 1, 2, 3, 4, 5, 6.

#### WebSocket registration and upgrade

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

#### WebSocket protocol enforcement

- [D-304] An unmasked client frame SHALL close the connection with a protocol error (T20).
- [D-305] A text message with invalid UTF-8 SHALL close the connection with code 1007 (T20).
- [D-306] A message larger than the message limit (default 1 MiB) SHALL close the connection with code 1009 (T20).
- [D-307] Fragmented messages SHALL be reassembled before delivery, with the message limit applied to the reassembled size (T20).
- [D-308] A control frame longer than 125 bytes or fragmented SHALL close the connection with a protocol error.
- [D-309] A Ping from the client SHALL be answered with a Pong, and `ws.ping()` sends a Ping (T21).
- [D-310] `ws.close(code, reason)` SHALL perform the close handshake, after which `isOpen()` returns `false`.

#### WebSocketSession

- [D-311] `WebSocketSession` SHALL provide `id`, `sendText`, `sendBinary`, `sendJson`, `ping`, `close()`, `close(code, reason)`, `isOpen`, `onText`, `onBinary`, `onJson`, `onClose`, `onError` and `attribute`, with listeners as `@FunctionalInterface` types.
- [D-312] Session ids SHALL be unique among the sessions of one app.
- [D-313] Session attributes SHALL be stored per connection in a concurrent map, separate from request attributes, and released when the connection closes (T21).
- [D-314] `onClose` listeners SHALL run exactly once per connection with the close code and reason.
- [D-315] `sendJson` and `onJson` SHALL encode and decode through the configured `JsonCodec`.
- [D-316] Services read in the WebSocket handler through `req.service` SHALL be the instances registered in `onStart` (T21).
- [D-317] Wave 7 SHALL decide how an `onJson` parse failure is reported (error callback or close) and update `docs/api/websocket.md`.

#### WebSocket send queue

- [D-318] Each session SHALL own a bounded outbound queue (default 1 MiB) because TinyHttpd's `WebSocket` send is synchronous.
- [D-417] WebSocket outbound queues SHALL be drained by one app-wide sender executor whose thread limit equals the WebSocket connection limit of D-404 (default 128), running at most one write task per session at a time and never queueing one session's task behind another session's in-flight write, so a stalled session holds only its own sender thread until TinyHttpd's write timeout (default 30 s) aborts its socket, and idle sender threads end after a keep-alive time.
- [D-319] Messages from concurrent senders SHALL be sent whole and in queue-entry order, with no interleaved frames (T23).
- [D-320] A slow or stalled client MUST NOT delay sends to other sessions, as a T23 test with at least 20 stalled sessions, more than any fixed pool of 16, next to one healthy session asserts by receiving the healthy session's message within 1 s.
- [D-321] When the outbound queue overflows, the session SHALL apply the overflow policy decided in #13 (exception or close) and release its queue (T23).
- [D-322] A closed session SHALL leave no queue, thread or attribute behind (T23).

#### WebSocket limits and logging

- [D-323] The idle timeout, ping interval and handshake timeout of WebSocket connections SHALL be configurable through APIs and defaults decided in #13, with the ping interval and idle timeout mapped as D-423 and D-425 state and the maximum connection count taken from D-404.
- [D-419] The WebSocket message size limit (default 1 MiB, D-306), the WebSocket outbound queue size (default 1 MiB, D-318) and the SSE outbound queue size (default 256 KiB, D-406) SHALL be configurable through APIs whose names wave 7 decides and records in `docs/api/websocket.md`, `docs/api/sse.md` and `docs/api/defaults.md`, with the pong timeout fixed by D-424 instead of configured and the configured WebSocket message limit passed to both `WebSocketHandler.maxFramePayloadBytes` and `WebSocketHandler.maxMessageBytes`, so one unfragmented frame and a fragmented message meet the same limit, as a T20 test with a non-default limit asserts by sending a message above it once as one frame and once fragmented and expecting close 1009 both times.
- [D-423] The WebSocket ping interval setting SHALL be passed to TinyHttpd's `WebSocketHandler.pingInterval`, whose read loop sends a Ping after one interval without an inbound frame and closes with 1001 after a second such interval, so NaruServe runs no ping scheduler of its own.
- [D-424] The WebSocket pong timeout SHALL equal the ping interval in v1, because TinyHttpd's read loop treats any inbound frame as the answer and its `WebSocketListener` has no Pong callback, with `docs/api/websocket.md` and `docs/api/defaults.md` stating this fixed relationship, a separate pong timeout recorded as a deferred TinyHttpd gap under D-020, and a T21 test asserting that a silent client is closed with 1001 within two ping intervals plus 1 s.
- [D-425] The WebSocket idle timeout SHALL be enforced by NaruServe's one scheduler thread, which detects a session that has neither received nor sent a text or binary message for that timeout, hands close 1001 to that session's sender slot of D-417 and schedules a check 1 s later that aborts the session's socket through the `UpgradedConnection` captured as D-435 states when the close task has not returned by then, so the scheduler thread never writes to a socket or waits on one session, because TinyHttpd counts Ping and Pong frames as activity and never idles out a peer that answers its pings.
- [D-324] WebSocket upgrades SHALL count against the WebSocket connection limit of D-404, whose mapping onto TinyHttpd's `maxUpgradedConnections` wave 5 owns, and an upgrade beyond it is answered `503`.
- [D-325] The WebSocket access log SHALL write one line at the `101` handshake and a separate line at close with close code and duration (T38).

#### SSE stream

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

#### Shutdown with long connections

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

#### Load bounds

- [D-346] A JVM test SHALL hold 100 or more concurrent WebSocket and SSE connections from the loopback address under the default limits of D-404 and assert that thread count and heap growth stay below bounds written in the test (T26).
- [D-416] The defaults of D-404 and D-417 SHALL bound the threads of one app to 451: 192 TinyHttpd request workers (64 HTTP plus 128 SSE), 128 WebSocket read threads kept by upgraded connections, 128 WebSocket sender threads, one NaruServe scheduler thread for WebSocket idle timeouts (D-425), and TinyHttpd's acceptor and watchdog threads, where the request workers and read threads take TinyHttpd's `workerStackBytes` of 256 KiB, the NaruServe sender and scheduler threads request 256 KiB explicitly and the acceptor and watchdog use the platform default stack, so 449 threads request 112.25 MiB of stack in total, with the thread total and that stack figure recorded in `docs/api/defaults.md` for API 21 devices and the thread total used as the T26 thread bound.
- [D-347] Device-specific tuning of long-connection limits for low-end Android SHALL be recorded as a manual follow-up in the release test results.



## Carried from loop D

Loop D ended at its round cap; the committee chose fix-and-freeze. The fixes below landed in the frozen design and this wave re-checks them in loop W:

- V1-004: D-419 passes the configured WebSocket message limit to both WebSocketHandler.maxFramePayloadBytes and maxMessageBytes, with a T20 test at a non-default limit (single frame and fragmented). (items D-419)
- V2-001: New D-444: the TinyHttpd upgrade class catches every pre-101 HttpException from WebSocketHandler.handle/res.upgrade (400, 426 + Sec-WebSocket-Version, 403, 413, 503 + Retry-After) and answers it through the dispatcher's error handling with the exception's headers, the request id and the D-430 access-log line; T19 test with version 12. D-405 narrowed to errors outside any NaruServe dispatch. (items D-444, D-405)
- V2-002: New D-445: each closing participant sets a closed flag under its registry lock before its snapshot; later res.sse() returns a stream ended at once with the closing notice, later upgrades get 503 + Connection: close before 101. New D-446: before-wait participants run while TinyHttpd still accepts connections (at most 1 s), requests answered 503 under D-166, recorded in lifecycle.md as a deviation from object.md 11.4 step 2/3 ordering. (items D-445, D-446)
- V2-003: Same fix as V1-004: D-419 maps the configured limit to both maxFramePayloadBytes and maxMessageBytes, with the T20 single-frame and fragmented case. (items D-419)

## Carried from earlier waves

- D-408 over TinyHttpd: TinyHttpd at `46b973e` keeps only `Connection: close`/`keep-alive` on a non-`101` response (`ExchangeResponse.java:172-177`, `:466-470`), so the `426` answer's `Connection: Upgrade` cannot pass through the TinyHttpd adapter. Record it as a deferred TinyHttpd-gap issue (D-020) without patching TinyHttpd; assert both headers of D-408 through the in-memory adapter and `426` with `Upgrade: websocket` over TinyHttpd; open the D-020 issue in the wave 7 issue ledger; D-408 is not promoted to `verified` on the TinyHttpd test alone.
- From wave 4: the WebSocket and SSE cases of D-142 (T41): `req.service(T)` returns the instance registered in `onStart` inside WebSocket and SSE handlers.
- From wave 5 (w5-P2-002, commit 387db8c): TinyHttpdExchange.eventStream/sendFile/upgrade now take the exchange lock, abort check and single-start bookkeeping of commit(); writes the event-stream drain makes later from another thread must also synchronize on the exchange (code comment in TinyHttpdExchange).

## Wave decisions

Settled from loop W round 1 (findings in `rounds/r1-merged.json`). These decisions bind SRS authoring, the SDS and the worker.

- WebSocket upgrade SPI (W2-001; D-106, D-302, D-429, D-435, D-444, D-419, D-423): the SPI gains add-only types and one `default` method; no existing signature changes. D-106's "without changing the SPI" is read as "without breaking it", and `docs/api` records this. New `spi` types: `WebSocketEndpoint` (core-owned callbacks `onOpen(WebSocketChannel)`, `onText`, `onBinary`, `onClose(code, reason)`), `WebSocketChannel` (`sendText`, `sendBinary`, `close(code, reason)`, `abort()`, `isOpen`), and `WebSocketSettings` (message limit, ping interval). New `HttpExchange.webSocket(WebSocketSettings, WebSocketEndpoint)` defaults to `UnsupportedOperationException`; the existing `upgrade(headers, UpgradeHandler)` stays for raw upgrades. On TinyHttpd, the upgrade class builds a `WebSocketHandler` with `allowAnyOrigin()`, passes the message limit to `maxFramePayloadBytes` and `maxMessageBytes` and the ping interval to `pingInterval`, and calls `handle` on the request worker. The call goes through a `ResponseWrapper` whose `upgrade` keeps the returned `UpgradedConnection`, and `WebSocketChannel.abort()` closes that connection. The method returns when the connection has closed. Every pre-`101` rejection reaches the core as a NaruServe `HttpException` that carries its headers, with nothing committed. No NaruServe frame codec exists.
- Rejection headers (W2-002; D-444, D-445): NaruServe `HttpException` gains add-only `withHeader(String name, String value)`, which returns `this`, and `headers()`, an unmodifiable ordered list. The dispatcher's error path sets those headers on the error response before rendering, the way Express `finalhandler` applies `err.headers`. The headers are validated like `res.header` when added. This carries `Sec-WebSocket-Version: 13`, `Retry-After` and `Connection: close`.
- TinyHttpd feature hooks (W2-003; D-210): the package-private `TinyHttpdFeatures` methods and the `TinyHttpdExchange` bridge may change, because D-210 protects only the adapter core (`TinyHttpdAdapter` limit mapping). The feature methods receive the exchange, which holds both the TinyHttpd `Request` and `Response`.
  - The exchange lock is held only to check abort and mark the response started. The WebSocket read loop and the SSE drain run outside it.
  - `eventStream` commits the headers and registers the drain. `TinyHttpdExchange.run` runs that drain on the same worker after the handler returns, until the core reports the stream ended.
  - Drain writes synchronize on the exchange, as carried from wave 5.
- Handler timing (W2-004; D-303, D-295): the `app.ws` handler runs once, from the endpoint's `onOpen`, after `101` is written. The TinyHttpd factory always returns NaruServe's listener and never runs user code. A `413` or `503` from `res.upgrade` therefore never runs the handler. `docs/api/websocket.md` records this, matching the `ws` library's `connection` event.
- Origin check (W2-005; D-300, D-303): NaruServe checks `Origin` in the core, before the upgrade extension point, because TinyHttpd's check always admits a missing or same-origin `Origin`. When no allowlist is configured, the default admits a request with no `Origin` header and a same-origin `Origin` (scheme host and port equal to the request's `Host`), and rejects every other `Origin` with `403`. A configured allowlist admits its listed origins as well; the entry `*` admits any origin. SRS authoring names the API in `docs/api/websocket.md` and `docs/api/defaults.md`.
- Thread total (W2-006; D-404, T26): the app's bounded thread total counts the non-daemon `naruserve-keeper` thread, so it is 452. `docs/api/defaults.md` and the T26 bound use 452.
- Sender slot release (W2-007; D-417): after `WebSocketHandler.handle` returns, the upgrade class waits for that session's in-flight sender task to end before returning, so TinyHttpd frees the connection slot only after it. The socket is already closed, so the task's write fails at once. The sender pool never needs more than the WebSocket connection limit.
- Handshake headers on rejections (W2-008; D-444): an error response for a pre-`101` rejection SHALL NOT carry `Sec-WebSocket-Accept` or `Sec-WebSocket-Protocol`. The upgrade class drops them before the error path renders; the SDS picks the mechanism the TinyHttpd `Response` API allows.
- `maxEventStreams` (W2-009; D-336): the adapter core keeps setting TinyHttpd's `maxEventStreams` to the SSE stream limit as an inert bound, because NaruServe never uses TinyHttpd event streams. D-336 means that the NaruServe counter is the bound that answers `503`. No change.
- Shutdown bound (W2-010; D-344): the bound of D-344 covers the core and adapter waits of D-162 while `onStop`, resource close and logger flush return promptly. `docs/api/lifecycle.md` keeps its statement that those steps run without a time limit and can extend `shutdown` past the bound. T44 uses no slow `onStop`.
- `426` over TinyHttpd (W1-001; D-408): already settled under "Carried from earlier waves". No change.

Settled from loop W round 2 (`rounds/r2-merged.json`):

- Thread total (r2 W1-001; D-416, D-346): the 452-thread total of the W2-006 decision is a recorded deviation from the frozen D-416 figure of 451. The extra thread is the wave-5 `naruserve-keeper` thread, which is non-daemon and runs from bind to stop. D-416's stack figure (449 request-path threads, 112.25 MiB) is unchanged, because the keeper thread uses the platform's default stack and sits outside that pool budget. `docs/api/defaults.md` states both figures.
- `426` over TinyHttpd (r2 W1-002; D-408): wave 7 opens the D-020 TinyHttpd-gap issue in its issue ledger at triage, defers it, and keeps D-408 below `verified`.
- Choices left to wave 7 (r2 W1-003; D-317, D-321, D-323, D-332, D-339, D-419, D-436): SRS authoring settles these in the requirements and in `docs/api`:
  - how an `onJson` parse failure is reported;
  - the WebSocket and SSE queue overflow policies;
  - the idle and heartbeat defaults (the handshake bound is settled under "Residual carried into SRS authoring");
  - the setting names;
  - the form of the SSE closing notice.
  It takes the choice most natural to an Express user that does not contradict object.md, and removes the matching `(미확정)` marks.
- Ping (r2 W2-001; D-309, D-311): `WebSocketChannel` gains `ping(byte[] payload)`, which the core calls through the session's sender slot. On TinyHttpd it calls `WebSocket.ping`.
- Start state on a refused handshake (r2 W2-002; D-444, D-445): `HttpExchange.webSocket` marks the response started only once `101` has been written. A pre-`101` `HttpException` leaves the exchange unstarted, so the dispatcher's error path commits the error answer. A T19 test checks the `426` over TinyHttpd.
- Session registration (r2 W2-003; D-445, D-435):
  - The core reserves a session in the WebSocket session registry, under its lock, before it calls `webSocket`. It refuses with `503` and `Connection: close` when the closed flag is already set.
  - A reserved session gets its channel in `onOpen`. If the closed flag was set in between, `onOpen` sends close 1001 at once and does not run the user handler.
  - The closing participant snapshots reserved and open sessions alike. It aborts a reserved session's connection as soon as its channel is set.
  - A refused handshake removes the reservation.
- Sender-slot wait (r2 W2-004; D-417): the wait for the session's in-flight sender task runs in the core, after `HttpExchange.webSocket` returns and before the exchange finishes, not in the TinyHttpd class or in `onClose`. This supersedes where the round-1 W2-007 decision put it; the behaviour is unchanged.
- User exceptions after `101` (r2 W2-005): the core endpoint catches every exception from user listeners, logs it with the request id, and closes the session with 1011. No exception reaches TinyHttpd's callbacks. Any `IOException` or `RuntimeException` that `webSocket` throws after `101` is logged and ends the exchange without an HTTP error answer, because the connection is already upgraded.

Settled from loop W round 3 (`rounds/r3-merged.json`):

- SSE limit answer (r3 W2-001; D-336, D-326, D-106): the start-state rule of the round-2 decision also covers `HttpExchange.eventStream`. On TinyHttpd, the SSE counter is checked before the exchange is marked started. A `503` `HttpException` from the counter leaves the exchange unstarted, so the dispatcher's error path commits the `503`. A test opens one stream over `maxSseStreams` over TinyHttpd and asserts the `503`.
- HEAD on an SSE route (r3 W2-002; D-335, D-336, D-327, D-334, D-406): for a `HEAD` request, `res.sse()` sends the event-stream response headers with no body. It takes no SSE slot and runs no drain, so it holds no worker after the handler returns. It returns a client that has already ended: `send` is a no-op that reports the client is closed, and every `onClose` listener runs once at registration. `docs/api/sse.md` records this. A test sends `HEAD` to an SSE route and asserts that the response completes and that the slot count is unchanged.
- Reserved session at shutdown (r3 W2-003; D-445, D-435, D-340): a session that opens after the closed flag was set takes the `onOpen` path. It hands close 1001 to its sender slot as its only task. The closing participant then treats it like any session in its snapshot: it waits for that close task within the same 1 s close wait, and aborts the connection only if the task has not returned by then. This replaces "aborts a reserved session's connection as soon as its channel is set" in the round-2 decision.

Settled from loop W round 4 (`rounds/r4-merged.json`):

- Deviations in the SRS (r4 W1-001; D-408, D-416): the wave-7 requirements state the D-408 and D-416 deviations as notes beside the frozen wording. They do not copy the frozen figures as if they still held.
- Sends inside the SSE handler (r4 W2-001; D-406, D-106, D-335, D-337, D-339): until the drain loop starts, an `SseClient.send` made on the handler's own thread writes the serialised event straight to the committed stream, under the exchange lock. It does not use the outbound queue, so a replay loop inside the handler is paced by the socket and never trips the overflow policy. Sends from other threads queue as usual. Once the drain loop has started, every send queues. `docs/api/sse.md` records this. A test sends more than 256 KiB from inside an SSE handler to a reading client and asserts that every event arrives and the stream stays open.
- Session opening after the close wait (r4 W2-002; D-445, D-435, D-344, D-340): a reserved session whose `onOpen` runs after the closing participant's close wait has ended is aborted at once through its `UpgradedConnection`, with no close frame. This is a narrow exception to D-340, recorded in `docs/api/websocket.md`. It keeps TinyHttpd's stop callback from waiting on a blocked close write, so D-344's bound holds.

## Residual carried into SRS authoring

Loop W ended at its round cap (5) with no critical or high finding in rounds 3 to 5. In every round, verifier 1 found all 70 items covered, with only the two recorded deviations (D-408, D-416). Findings fell from 11 to 8, 4, 3 and 4. The four round-5 findings are settled below. No loop W round re-verified these lines, so SRS authoring and the feasibility check confirm them.

- SSE send order before the drain starts (r5 W2-001; D-333, D-406, D-319): a handler-thread send made before the drain loop starts first writes every event already in the outbound queue, in queue order, and then its own event. It does both under the exchange lock, so events reach the socket in call order across threads, as D-333 requires. This is a recorded deviation from D-406: until the drain starts, the handler's thread writes and the queue limit applies only to other threads' sends. `docs/api/sse.md` records it.
- WebSocket handshake timeout (r5 W2-002; D-323, D-303): TinyHttpd at `46b973e` has no deadline specific to upgrades. On the TinyHttpd adapter, the handshake is bounded by the existing server-wide `headerTimeout` and `bodyReadTimeout` (mapped in the adapter core under D-210; listed in `docs/api/defaults.md`). The adapter adds no separate handshake-timeout setting, because one would have no effect there, and the adapter core's limit mapping (D-210) stays unchanged. `docs/api/websocket.md` and `docs/api/defaults.md` say the handshake is bounded by those two timeouts. A dedicated upgrade deadline is opened as a deferred TinyHttpd-gap issue (D-020) in the wave 7 issue ledger. D-323 is satisfied on TinyHttpd only through these existing timeouts, which is recorded as a deviation note.
- Deadline for shutdown participants (r5 W2-003; D-435, D-344, D-162): the internal `ShutdownParticipants` hook gains the shutdown deadline. It is package-private, so the change is allowed. Each before-wait participant receives the D-162 deadline, and D-435's close wait is the earlier of 1 s and the time left to that deadline. A T44 case uses a shutdown timeout below 1 s.
- `shutdown` from an SSE `onClose` listener (r5 W2-004; D-334, D-436, D-344): the request mark (`enterRequest`/`exitRequest`) spans the SSE drain loop and the `onClose` listeners it runs. A `shutdown` called from those listeners therefore returns at once, as `docs/api/lifecycle.md` promises for a call made during a request. A test calls `app.shutdown` from an SSE `onClose` listener and asserts that it returns at once.
