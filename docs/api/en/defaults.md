# Defaults and security

[한국어](../defaults.md) | [English](defaults.md)

NaruServe starts with bounded inputs to control memory and thread use, including on low-memory Android 5.0 devices. No numeric limit accepts an unlimited value. Each setting has an upper bound; invalid values throw `IllegalArgumentException` with the setting name and allowed range. Increase limits explicitly within those bounds.

## Size and count limits

These are design defaults checked by JVM automated tests. They have not been validated or tuned on low-memory Android devices. Bare setting names such as `maxHeaderSize` belong to `ServerOptions`, for example `app.server(options -> options.maxHeaderSize(32768))`. See [RestApp](app.md).

| Item | Default | Maximum | When exceeded | Setting |
|---|---:|---:|---|---|
| JSON request body | 1 MiB | 1 GiB | `413` | `app.jsonLimit(bytes)` |
| JSON nesting depth | 64 | 255 | `400` | `new GsonJsonCodec(gson, maxDepth)` |
| Form body | 1 MiB | 1 GiB | `413` | `app.formLimit(bytes)`; applies to the urlencoded body and the total multipart text-field contents per request |
| One uploaded file | 32 MiB | 4 GiB | `413` | `app.multipart(c -> c.maxFileSize(...))` |
| Entire upload request | 64 MiB | 4 GiB | `413` | `c.maxRequestSize(...)` |
| Upload parts | 20 | 10000 | `413` | `c.maxParts(...)` |
| Upload memory threshold | 64 KiB | 16 MiB | Spill to disk | `c.memoryThreshold(...)` |
| One part-header line | 8192 B | Fixed | `413` | Not configurable |
| All headers of one part | 16 KiB | Fixed | `413` | Not configurable |
| `UploadFile.bytes()` | 8 MiB | Fixed | `IllegalStateException` | Use `inputStream()`, `transferTo` or `save` for larger parts |
| Total HTTP headers | 16 KiB | 1 MiB | `431` | `maxHeaderSize` |
| One HTTP header line | 8190 B | Buffer-dependent | `431` | TinyHttpd input buffer minus two bytes; the adapter sets the buffer to `max(8192, maxRequestLineLength + 2)`, so only increasing `maxRequestLineLength` above 8190 increases this limit. `maxHeaderSize` does not change it |
| Request line | 8190 B | 65534 B | `414` | `maxRequestLineLength` |
| WebSocket message | 1 MiB | 16 MiB | Close `1009` | `app.webSocket(o -> o.maxMessageSize(bytes))`; applies to each frame and the assembled fragmented message |
| WebSocket send queue per connection | 1 MiB | 64 MiB | Close `1008` | `app.webSocket(o -> o.sendQueueSize(bytes))`; drops the overflowing message and queued messages, closes with reason `send queue overflow`, and returns normally |
| SSE send queue per connection | 256 KiB | 16 MiB | End stream | `app.sse(o -> o.sendQueueSize(bytes))` |
| Shutdown wait | Call argument, e.g. 10 seconds | — | Force termination | `app.shutdown(timeout, unit)` |

Uploads use `java.io.tmpdir` by default (the application's cache directory on Android). Override it with `c.tempDirectory(dir)`. Upload limits must be at least one and no greater than their maxima. See [uploads](multipart.md).

Request-line length counts the entire `GET /path?query HTTP/1.1` line in bytes, rather than only the URL. TinyHttpd reads and checks that line as a unit.

## Time and connection limits

Set socket timeouts through `headerTimeout`, `bodyReadTimeout` and `writeTimeout`. Set header count with `maxHeaderCount`, and concurrency with `maxWorkers`, `maxSseStreams`, `maxConnections`, `maxUpgradedConnections` and `maxConnectionsPerAddress`. The queue size is derived rather than configured separately. All belong to `app.server(options -> ...)`.

| Item | Default | Maximum | When exceeded | Setting |
|---|---:|---:|---|---|
| Header receive timeout | 10 seconds | 1 hour | `408` | `headerTimeout(timeout, unit)` |
| Body read timeout | 30 seconds | 1 hour | `408` | `bodyReadTimeout(timeout, unit)` |
| Write timeout | 30 seconds | 1 hour | Close connection | `writeTimeout(timeout, unit)` |
| Minimum body rate | 240 B/s | 104857600 B/s | `408` | `minBodyRate(bytesPerSecond)` |
| HTTP worker allocation | 64 | 1024 | Wait in queue | `maxWorkers(workers)`; engine pool size is `maxWorkers + maxSseStreams` (192 by default). SSE uses at most its reserved `maxSseStreams`, leaving at least `maxWorkers` for ordinary requests. Without open SSE streams, HTTP can use the whole pool: this is a guaranteed allocation, not an upper bound on ordinary request threads |
| Concurrent SSE streams | 128 | 1024 | `503` | `maxSseStreams(streams)` |
| Open connections | 512 | 65536 | Socket-level `503` | `maxConnections(connections)`; must be at least `maxWorkers + maxSseStreams`, otherwise `listen()` throws `IllegalStateException` |
| Waiting queue | 320 | Derived | Socket-level `503` | `maxConnections - maxWorkers - maxSseStreams` |
| Upgraded connections | 128 | 65536 | `503` | `maxUpgradedConnections(connections)` for WebSocket |
| Connections per address | 256 | 65536 | Socket-level `503` | `maxConnectionsPerAddress(connections)` |

Socket timeout values must be at least 1 ms; the other numeric values above must be at least one.

WebSocket time settings belong to `app.webSocket(o -> ...)`, and the SSE heartbeat belongs to `app.sse(o -> ...)`, rather than `ServerOptions`. See [WebSocket](websocket.md#configuration) and [SSE](sse.md#configuration).

| Item | Default | Maximum | Behavior | Setting |
|---|---:|---:|---|---|
| WebSocket ping interval | 30 seconds | 1 hour | Send Ping after an interval without received frames; close `1001` after another interval without a frame | `pingInterval(time, unit)`; minimum 1 second |
| WebSocket idle timeout | 5 minutes | 24 hours | Close `1001` | `idleTimeout(time, unit)`; minimum 1 second. Only text and binary message activity counts |
| SSE heartbeat interval | 15 seconds | 1 hour | Send a `: ping` comment after an interval with no writes | `heartbeatInterval(time, unit)`; minimum 1 second |

Pong timeout follows the ping interval. TinyHttpd's `WebSocketListener` has no Pong callback, so there is no independently configurable Pong timeout. WebSocket handshake time is bounded only by `headerTimeout` and `bodyReadTimeout`, with no dedicated upgrade deadline.

### Thread counts and stacks

The default bounds allow these thread counts per application:

| Item | Default | Explanation |
|---|---:|---|
| Total application threads | 452 | Engine pool 192 + upgrade readers 128 + WebSocket senders 128 + one WebSocket idle scheduler + TinyHttpd acceptor/watchdog 2 + non-daemon `naruserve-keeper` 1. The keeper runs from `listen()` until shutdown; it adds one to the design's original total of 451 |
| Requested stack per request-path thread | 256 KiB | The 449 request-path threads exclude acceptor, watchdog and keeper: pool 192 + upgrade readers 128 + senders 128 + idle scheduler 1. If all start, their requested stacks total 112.25 MiB on API 21 |

## Tuning low-memory Android devices

The defaults were checked with JVM load tests (T26: 60 WebSockets and 60 SSE streams together). Device-specific tuning of `maxSseStreams`, `maxUpgradedConnections` and WebSocket/SSE `sendQueueSize` is a post-v1 task. Device verification is outside v1 scope, so it is not a pending manual v1 release gate. Lower these values explicitly for devices with limited memory; do not treat JVM test results as device tuning evidence.

## Default security policies

| Item | Default | To change |
|---|---|---|
| Bind address | `127.0.0.1` (IPv4 loopback), including `RestApp.start(...)` | `app.server(options -> options.bindAddress("0.0.0.0"))`. A non-loopback address always logs a security warning, whether or not you added authentication. Implement authentication in middleware; the framework cannot inspect it and does not suppress the warning |
| Static directory listing | Off | `options.directoryListing(true)` |
| Directory index | `index.html` | `options.index(name)` |
| SPA fallback | Off | `options.spaFallback(true)`; `/api/` and `/ws/` remain excluded |
| Hidden files (`.env`, `.git`) | Blocked | — |
| Symbolic links | Blocked | `options.allowSymlinks(true)`; targets outside the mount root still return `404` |
| `Cache-Control` | Absent | `app.defaultCache(policy)` fills responses without it; values from `res.cache`, `res.header` or `options.cache` stay intact |
| `X-Content-Type-Options` | `nosniff` on static-file, `res.file` and `res.download` responses | Not automatically added to ordinary `send` or JSON responses |
| Stack traces in error responses | Omitted | — |
| Sensitive access-log values | Omitted | Keep this policy |
| Proxy headers (`X-Forwarded-*`) | Untrusted | `app.trustProxy(true)` uses the leftmost value of the first `X-Forwarded-For` and the first `X-Forwarded-Proto` for `req.remoteAddress()` and `req.isSecure()`. Values are not validated. Enable only behind a reverse proxy that overwrites them. The `Forwarded` header is not read |
| `Host` | Bind address, `localhost` and IP literals accepted | `app.server(options -> options.allowedHosts("myphone.local"))`; other hosts receive `421` with the standard error body (DNS rebinding defense) |
| `TRACE`, `CONNECT` | Not routed | Enable `enableTrace` / `enableConnect` in `app.routerPolicy(...)`; see [routing](routing.md) |
| Automatic JSON polymorphic binding | Off | — |

## HTTP protocol defenses

- Reject conflicting `Content-Length` and `Transfer-Encoding` (request smuggling).
- Reject CR/LF injection and response splitting, including in `res.header`, `res.addCookie` and download filenames.
- Stop very slow header/body senders (slowloris) through timeouts.

### Transport-level rejection

TinyHttpd may reject a request before an application `Request` exists. These responses are short plain text and bypass `onError`, standard JSON errors and access logging: malformed request `400`, header receive timeout `408`, long request line `414`, unsupported `Expect` `417`, large headers `431`, unsupported `Transfer-Encoding` `501`, unsupported HTTP version `505`, and socket-level capacity `503`.

Body-read `408` errors (`bodyReadTimeout`, `minBodyRate`) occur when a handler reads the body, so they use `HttpException`, `onError` and the standard error body. With the default TinyHttpd adapter, malformed path percent encoding, encoded slash (`%2F`), NUL, and `.`/`..` segments also produce transport-level plain-text `400`: the engine checks paths before dispatch.

Host allowlist `421` errors happen after parsing and use the [standard error format](middleware.md).

## Application responsibilities

- **Authentication and authorization:** implement [middleware](middleware.md).
- **CSRF:** protect APIs that authenticate with cookies in middleware.
- **WebSocket authentication:** authenticate before Upgrade. Default Origin checks accept missing or same-origin headers and reject others with `403`. Set `app.webSocket(o -> o.allowedOrigins("https://app.example.com"))` to allow others; `"*"` allows every Origin. Origin checks do not authenticate users. See [WebSocket](websocket.md#authentication-and-origin).
- **Upload filenames and contents:** validate them; see [uploads](multipart.md).
- **TLS:** the TinyHttpd adapter has no v1 TLS listener. Put an internet-facing server behind a TLS-terminating reverse proxy and enable `app.trustProxy(true)`. TinyHttpd TLS/WSS is post-v1 because epic #20 has no sub-issue for it, and `object.md` §§14.3/20.3 require separate API 21 TLS-provider/certificate tests, which are outside v1's Android-device scope.
- **Service thread safety:** see [lifecycle and services](lifecycle.md#storage-scopes).
