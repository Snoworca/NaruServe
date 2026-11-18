# Reverse proxy

[한국어](../proxy.md) | [API index](README.md)

> **Version scope:** `NaruServe-proxy` is a module added in 0.12.0. It is not part of 0.11.0.

`Proxy` is a reverse proxy middleware: it forwards the request it receives to one backend HTTP server and relays that server's response to the client unchanged. In Express this is the job of `http-proxy-middleware`.

## Module

| Item | Value |
|---|---|
| Module | `NaruServe-proxy` |
| Artifact | `com.snoworca.naruserve:naruserve-proxy` |
| Dependencies | `NaruServe-api` only |
| Package | `com.snoworca.naruserve.proxy` |

`NaruServe-proxy` depends on `NaruServe-api` only and brings no HTTP engine. Add this artifact next to the `NaruServe` artifact (or another adapter) that runs the app. The class name `Proxy` is also used by `java.net.Proxy` and `java.lang.reflect.Proxy`, so import it with its package: `import com.snoworca.naruserve.proxy.Proxy;`.

| Express (`http-proxy-middleware`) | NaruServe |
|---|---|
| `app.use(path, createProxyMiddleware({ target }))` | `app.use(path, Proxy.to(url))` |
| `on: { error: (err, req, res) => ... }` with `err.code` | `app.onError` with `ProxyException.kind()` ([Errors](#errors)) |

## Basic use

`Proxy.to(url)` is the only way to create a proxy. `url` is an `http` or `https` origin, optionally followed by a base path (`http://127.0.0.1:8081`, `https://backend.example/v1`). Trailing `/` characters are removed. `null` throws `NullPointerException`; a URL with a query, a fragment or user information, another scheme, a host that is not an ASCII name, a dotted-quad IPv4 literal or a bracketed IPv6 literal without a zone id, a port outside `1..65535`, or a base path that fails the request path checks throws `IllegalArgumentException`. Everything is checked when `Proxy.to` is called, so a wrong configuration shows at startup rather than on the first request.

`Proxy` implements `Middleware` (not `Installable`), so it is mounted with `app.use(path, proxy)` or a router's `use`. A `Proxy` is immutable. Every option method returns a new `Proxy` and leaves its receiver unchanged, so one configured value can be shared by several mounts.

The example below shares one `Proxy` holding the common settings between two mounts. Every request gets the fixed header `X-Gateway-Source`; the read timeout is shortened to 30 seconds and the total timeout is raised to 10 minutes. A path whose request must never run twice, such as payments, is mounted first with the defaults (no retries); the rest of `/api` is mounted with `retries(2)`.

```java
import com.snoworca.naruserve.proxy.Proxy;

String url = "http://127.0.0.1:8081";      // backend address
Proxy api = Proxy.to(url).readTimeout(30, TimeUnit.SECONDS)
        .totalTimeout(10, TimeUnit.MINUTES)
        .requestHeader("X-Gateway-Source", "gateway");
app.use("/api/payments", api);             // must not be retried: defaults, registered first
app.use("/api", api.retries(2));           // the rest of /api: 2 retries
```

Kotlin (`url` is the backend address string):

```kotlin
val api = Proxy.to(url).readTimeout(30, TimeUnit.SECONDS)
    .totalTimeout(10, TimeUnit.MINUTES)
    .requestHeader("X-Gateway-Source", "gateway")
app.use("/api/payments", api)
app.use("/api", api.retries(2))
```

Kotlin uses the Java option chain as it is, without a wrapper function or extension.

The proxy never calls `next()` after it forwarded a request. So under one prefix the **mount registered first** handles the request and later mounts never see it. In the example, `/api/payments/charge` is forwarded by the first mount without retries and never reaches the `/api` mount. In the other order the `/api` mount would retry payment requests too. The one exception is a WebSocket upgrade request: the proxy does not forward WebSockets and passes them to `next()`, so an `app.ws` route on the same path gets them.

### Forwarded path

By default the original path with the mount prefix (`req.originalUrl()`) is appended to the base path of the target URL. With `stripPrefix(true)` the remainder after the prefix (`req.url()`) is appended instead. The path and the query are sent exactly as the client encoded them.

| Mount | Client request | Request target the backend receives |
|---|---|---|
| `app.use("/api", Proxy.to("http://127.0.0.1:8081"))` | `/api/orders?x=1` | `/api/orders?x=1` |
| `app.use("/api", Proxy.to("http://127.0.0.1:8081").stripPrefix(true))` | `/api/orders?x=1` | `/orders?x=1` |
| `app.use("/api", Proxy.to("http://127.0.0.1:8081/v1").stripPrefix(true))` | `/api/orders` | `/v1/orders` |

In Express, mounting with `app.use('/api', createProxyMiddleware(...))` removes the mount path from `req.url` (http-proxy-middleware 3.x), so `stripPrefix(true)` is the closer behavior. NaruServe keeps the prefix by default.

## Options

Every option method returns a new `Proxy`, and its value is checked when the method is called. Time options take `(long, TimeUnit)`, not `java.time.Duration` (Android 5.0 has no `java.time`). Sockets get them in milliseconds, rounded up to at least 1 ms and at most `Integer.MAX_VALUE` ms; the total timeout is measured with nanosecond precision (saturated at `Long.MAX_VALUE`).

| Option | Default | Accepted range and exceptions | http-proxy-middleware |
|---|---|---|---|
| `connectTimeout(long, TimeUnit)` | 10 seconds | Must be above 0. 0 or less throws `IllegalArgumentException`, a `null` `unit` throws `NullPointerException` | No counterpart |
| `writeTimeout(long, TimeUnit)` | 60 seconds | Must be above 0. 0 or less throws `IllegalArgumentException`, a `null` `unit` throws `NullPointerException` | No counterpart |
| `readTimeout(long, TimeUnit)` | 60 seconds | Must be above 0. 0 or less throws `IllegalArgumentException`, a `null` `unit` throws `NullPointerException` | `proxyTimeout` |
| `totalTimeout(long, TimeUnit)` | 5 minutes | Must be above 0. 0 or less throws `IllegalArgumentException`, a `null` `unit` throws `NullPointerException` | No counterpart (`timeout` is the idle limit of the incoming socket) |
| `retries(int)` | 0 (no retries) | 0 or more. A negative value throws `IllegalArgumentException` | No counterpart (it has no retries) |
| `retryDelay(long, TimeUnit)` | 200 milliseconds | 0 or more. A negative value throws `IllegalArgumentException`, a `null` `unit` throws `NullPointerException` | No counterpart |
| `retryUnsafeMethods(boolean)` | `false` | Accepts every value | No counterpart |
| `stripPrefix(boolean)` | `false` (the prefix is kept) | Accepts every value | `pathRewrite`, or the 3.x `app.use(path, ...)` that removes the mount path |
| `preserveHost(boolean)` | `false` (the target host is sent as `Host`) | Accepts every value | `changeOrigin: false` (the http-proxy-middleware default). The NaruServe default corresponds to `changeOrigin: true` |
| `bodyLimit(long)` | The app's `rawBodyLimit` (default 1 MiB) | `1..1073741824` (1 GiB). Outside it throws `IllegalArgumentException` | No counterpart |
| `maxConcurrent(int)` | No limit | 1 or more. 0 or less throws `IllegalArgumentException` | No counterpart (the `maxSockets` of an `agent` makes excess requests wait) |
| `requestHeader(String, String)` | None | The name must be an HTTP token that the proxy does not manage, and the value a header value. Otherwise `IllegalArgumentException`; `null` throws `NullPointerException` | `headers: { name: value }` |
| `removeRequestHeader(String)` | None | The name must be an HTTP token that the proxy does not manage. Otherwise `IllegalArgumentException`; `null` throws `NullPointerException` | `proxyReq.removeHeader(name)` in `on.proxyReq` |
| `responseHeader(String, String)` | None | The name must be an HTTP token that is neither a hop-by-hop header nor `Content-Length`, and the value a header value. Otherwise `IllegalArgumentException`; `null` throws `NullPointerException` | `proxyRes.headers[name] = value` in `on.proxyRes` |
| `removeResponseHeader(String)` | None | The name must be an HTTP token that is neither a hop-by-hop header nor `Content-Length`. Otherwise `IllegalArgumentException`; `null` throws `NullPointerException` | `delete proxyRes.headers[name]` in `on.proxyRes` |
| `rewriteLocation(boolean)` | `true` | Accepts every value | `autoRewrite`, `hostRewrite`, `protocolRewrite` |
| `sslSocketFactory(SSLSocketFactory)` | The JDK default factory | `null` throws `NullPointerException`. An `http` target accepts it and never uses it | `agent` (for example `new https.Agent({ ca })`). Nothing corresponds to `secure: false` |

`retries` and `maxConcurrent` take an `int`, `bodyLimit` a `long`, and the switches a `boolean`. No public method reads a configured value back.

The request headers the proxy manages are `Connection`, `Keep-Alive`, `Proxy-Connection`, `TE`, `Transfer-Encoding`, `Upgrade`, `Proxy-Authenticate`, `Proxy-Authorization`, `Trailer` (the hop-by-hop headers), `Host`, `Content-Length`, `Expect`, `Forwarded`, `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host`. Letter case does not matter, so `requestHeader("host", "h")` also throws `IllegalArgumentException`. On the response side the managed names are the hop-by-hop headers and `Content-Length`.

### Fixed headers

- `requestHeader(name, value)` sends exactly one line of that name. It replaces every line of that name the client sent, in any letter case. The line follows the forwarding headers (`X-Forwarded-*`), in the order of the last call for each name.
- `removeRequestHeader(name)` drops every line of that name the client sent.
- `responseHeader(name, value)` sets the value with `res.header` after the backend header copy and the `Location` rewrite, so it replaces both the backend's value and a value the app set before the proxy.
- `removeResponseHeader(name)` drops only the backend's lines of that name. A value the app set before the proxy stays.
- Names compare case-insensitively, and when one name is given several times (`requestHeader` and `removeRequestHeader` mixed included) only the last call counts. The response side works the same way.

```java
import com.snoworca.naruserve.proxy.Proxy;

String url = "http://127.0.0.1:8081";
Proxy users = Proxy.to(url)
        .stripPrefix(true)                             // /users/42 → /42 on the backend
        .requestHeader("X-Api-Key", "internal-key")    // replaces the client's X-Api-Key
        .removeRequestHeader("Cookie")                 // cookies never reach the backend
        .responseHeader("Cache-Control", "no-store")   // replaces the value from the backend and the app
        .removeResponseHeader("Server");               // drops the backend's Server line
app.use("/users", users);
```

Kotlin:

```kotlin
val users = Proxy.to(url)
    .stripPrefix(true)
    .requestHeader("X-Api-Key", "internal-key")
    .removeRequestHeader("Cookie")
    .responseHeader("Cache-Control", "no-store")
    .removeResponseHeader("Server")
app.use("/users", users)
```

## Request forwarding

- The method token is sent as received over HTTP/1.1, extension methods included. A method that is not a token gets `400`, and `CONNECT` and `TRACE` (any letter case) get `405` with `Allow: GET, HEAD, POST, PUT, DELETE, PATCH, OPTIONS`, without a backend connection.
- A WebSocket upgrade request goes to `next()` without a backend connection. Any other request with `Upgrade` is forwarded without the `Upgrade` header.
- Path checks: a request with `..` or `.` segments (`%2e%2e` included), an encoded `/` or `\` (`%2f`, `%5c`), `NUL`, an encoded character that needs no encoding (`%70rivate`), a `;` inside a segment, one of those encodings encoded once more (`%252e`, `%252f`), an empty segment (`//`), or a control character, space, non-ASCII byte, `#` or broken `%` encoding gets `400` without a backend connection. The checks run on the original path (`req.originalUrl()`) whatever `stripPrefix` says. The proxy only ever connects to the one configured target host.
- `Host` is replaced by the target host (with `:port` when the port is not the default), and the received `Host` goes out as `X-Forwarded-Host`. With `preserveHost(true)` the received `Host` is sent unchanged. A request with more than one `Host` line gets `400`.
- Received headers are sent in order with duplicates, without the hop-by-hop headers, the headers named in `Connection` and `Expect`; several `Cookie` lines are joined with `; ` into one line. No `Via` is added. The request line and headers are written in ISO-8859-1. A header whose name is not a token or whose value holds a character a header cannot carry, a request with both `Content-Length` and `Transfer-Encoding`, a `Content-Length` list that is not one agreed value and a `Transfer-Encoding` that does not end in a single `chunked` get `400`, and another transfer coding before `chunked` gets `501`. All of them are answered before any backend connection.
- The body is streamed, never buffered whole. It is read through `req.bodyStream(n)` with `bodyLimit(n)` and through `req.bodyStream()` (the app's `rawBodyLimit`) otherwise, so a declared length over the limit gets `413` before any backend connection. A chunked body that passes the limit while being forwarded closes the backend connection and gets `413`, and the exception `onError` receives is an `HttpException` whose cause (`getCause()`) is a `BodyTooLargeException`. A body an earlier middleware already read gets `500` without a backend connection.

### X-Forwarded-* headers

| Header | What the backend receives |
|---|---|
| `X-Forwarded-For` | When the client connection comes from a [trusted proxy](app.md#proxy-trust), the received values followed by the socket address; otherwise the socket address alone. Not sent when the socket address is not an IP literal |
| `X-Forwarded-Proto` | Always replaced by `https` or `http` from `req.isSecure()` |
| `X-Forwarded-Host` | Always replaced by the received `Host` (none when there is no `Host`) |
| `Forwarded`, `X-Real-IP`, `X-Forwarded-Port`, `X-Forwarded-Prefix` | Always removed, whatever the trust setting |

Express counterpart: `xfwd: true`.

### Address-based checks are not reliable behind the proxy

A backend that tells clients apart by address loses that check behind the proxy.

- A backend that looks at the socket address sees every request coming from the proxy (`127.0.0.1` on the same host). A check such as "allow only localhost" lets outside clients through.
- A backend that looks at the leftmost `X-Forwarded-For` value can be fooled. When the gateway trusts the client connection, it keeps the values the client sent and appends the address after them, so the leftmost value can be one the client chose.

So move checks that relied on the address, such as protecting admin paths, to means other than the address: require an admin key, or run the admin app on a separate port that is not exposed outside. When the backend needs the client address, make it trust the gateway only with `trustProxyHops(1)` or `trustProxyCidrs(...)` ([Proxy trust](app.md#proxy-trust)). Do not use the legacy `trustProxy(true)` with the proxy: under it, clients can forge the address.

## Response relay

- The backend's final status code is relayed unchanged. A `3xx` is passed on without following the redirect, and `4xx` and `5xx` stay as they are.
- Backend response headers are copied in received order with duplicates. The first line of a name goes through `res.header` and replaces a value the app set; later lines go through `res.append`. `Set-Cookie` and `Vary` are always appended, so the app's cookies and `Vary` stay.
- Hop-by-hop headers and the headers named in `Connection` are not copied. `Content-Length` is not copied either; it reaches the client only as the length argument of `res.relay`. No `Content-Type` the backend did not send is added.
- The body is streamed through `res.relay`. Responses to `HEAD` and `204` and `304` responses carry no body.
- The `Date` header and the reason phrase of the status line that clients see come from the gateway's adapter, not from the backend. When the backend sends `HTTP/1.1 404 Nope`, the client gets the standard phrase the adapter writes for `404`.
- Backend headers are read as ISO-8859-1 and handed to the adapter unchanged. TinyHttpd 3.1.1 writes header characters as UTF-8, so the byte `0xE9` in a backend header value reaches the client as `0xC3 0xA9`. This is an adapter-dependent limit.

**Do not set representation metadata headers before the proxy.** The public `Response` has no method that removes a header, so when the app sets a header such as `Content-Type`, `Content-Encoding` or `ETag` before the proxy and the backend does not send that header, the app's value stays on the relayed backend body. For example, a `Content-Encoding: gzip` from an earlier middleware ends up on an uncompressed backend body. Leave such headers to the backend, or set them with `responseHeader`.

### Location rewrite

While `rewriteLocation` is on (default `true`), a backend `Location` that is an absolute URL (or `//host/...`) with the target's origin (scheme, host and port) and a path that equals the target's base path or continues it with `/` gets its origin and base path replaced by the client-side prefix. The prefix is `req.baseUrl()` under `stripPrefix(true)` and the empty string otherwise. The rest of the path, the query and the fragment stay, and an empty resulting path becomes `/`. The result never holds an origin or the received `Host`. Relative references and URLs of another origin stay unchanged.

| Target and mount | Backend `Location` | `Location` the client receives |
|---|---|---|
| `app.use("/api/orders", Proxy.to("http://127.0.0.1:8081"))` | `http://127.0.0.1:8081/api/orders/x` | `/api/orders/x` |
| The same mount with `stripPrefix(true)` | `http://127.0.0.1:8081/x` | `/api/orders/x` |
| The same mount | `http://other.example/x` | Unchanged |
| The same mount with `rewriteLocation(false)` | `http://127.0.0.1:8081/api/orders/x` | Unchanged |

## Errors

### Failures before the response

Backend-side failures reach the app's error flow (`onError`, `ErrorMiddleware`) as a `ProxyException`. The proxy writes no error body of its own and no app-logger entry; logging is left to the error flow.

`ProxyException` is a subclass of `HttpException`, and `kind()` returns the kind of failure (`ProxyException.Kind`). The status code follows from the kind.

| `kind()` | Status | Meaning |
|---|---|---|
| `CONNECT_FAILED` | 502 | The connection could not be opened (refused, unresolved name, a TLS failure other than a timeout) |
| `CONNECT_TIMEOUT` | 504 | The TCP connect passed the connect timeout, or the TLS handshake stalled for the connect timeout |
| `WRITE_TIMEOUT` | 504 | A backend write made no progress for the write timeout |
| `READ_TIMEOUT` | 504 | Reading the response passed the read timeout |
| `TOTAL_TIMEOUT` | 504 | The whole request passed the total timeout |
| `INVALID_RESPONSE` | 502 | The backend response is not a valid HTTP response, or the connection ended before the header block was complete |
| `CONCURRENCY_LIMIT` | 503 | The `maxConcurrent` limit was reached |

- The message (`getMessage()`) is always `null`, so the default error response never echoes the backend address. The underlying failure is in `getCause()` (for example `java.net.ConnectException` or `java.net.UnknownHostException`).
- Without `onError`, the default error page shows the causes of a `ProxyException` by class name only, so the target host and port are not revealed. The app logger and `getCause()` keep the full cause.
- The proxy's own answers `400` (bad method, path, header or framing), `405` (`CONNECT`, `TRACE`) and `501` (unsupported transfer coding) are plain `HttpException`, not `ProxyException`, with a `null` message. The `413` of an over-limit body is a plain `HttpException` too.
- The public constructor `new ProxyException(kind, cause)` lets a test build one and throw it through `next.error(e)` to exercise `onError`.

Express counterpart: compare `kind()` the way you compare `err.code` (`ECONNREFUSED` and so on) in `createProxyMiddleware({ on: { error: (err, req, res) => ... } })`.

An `onError` that answers JSON by `kind()`:

```java
import com.snoworca.naruserve.proxy.ProxyException;

app.onError((error, req, res) -> {
    if (error instanceof ProxyException) {             // backend-side failure: 502, 503, 504
        ProxyException e = (ProxyException) error;
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "upstream");
        body.put("kind", e.kind().name());
        res.status(e.status()).json(body);
    } else if (error instanceof HttpException) {       // the proxy's 400, 405, 501 and 413 come here too
        res.status(((HttpException) error).status()).json(Collections.singletonMap("error", "request"));
    } else {
        res.status(500).json(Collections.singletonMap("error", "internal"));
    }
});
```

Kotlin:

```kotlin
app.onError { error, req, res ->
    when (error) {
        is ProxyException -> res.status(error.status()).json(mapOf("error" to "upstream", "kind" to error.kind().name))
        is HttpException -> res.status(error.status()).json(mapOf("error" to "request"))
        else -> res.status(500).json(mapOf("error" to "internal"))
    }
}
```

Check `ProxyException` before `HttpException`, because a `ProxyException` is an `HttpException` too.

### Failures after the response started

When relaying the body fails after the response headers were sent (the connection ending before the declared length, a malformed chunk, a read timeout between body bytes, the total timeout), the status can no longer change. The proxy logs a `ProxyException` of that kind once to the app logger (`req.application().logger()`), and the framework aborts the client connection. The client sees a response cut short.

## Timeouts

| Option | Default | What it measures | `kind()` when passed |
|---|---|---|---|
| `connectTimeout` | 10 seconds | The TCP connect and, separately, the TLS handshake | `CONNECT_TIMEOUT` |
| `writeTimeout` | 60 seconds | One backend socket write blocked without progress. Time spent waiting for client body bytes does not count | `WRITE_TIMEOUT` |
| `readTimeout` | 60 seconds | From sending the request to the first response byte, and between response bytes | `READ_TIMEOUT` |
| `totalTimeout` | 5 minutes | From the first attempt to the end of the request, retries and retry waits included | `TOTAL_TIMEOUT` |

- All four timeouts are finite. No value means unlimited, and 0 or less throws `IllegalArgumentException`.
- The first timeout reached applies. Under `readTimeout(60, TimeUnit.SECONDS).totalTimeout(1, TimeUnit.SECONDS)` a backend that never answers gives `TOTAL_TIMEOUT` after 1 second.
- Reached before the response is relayed, a timeout is a `504` through the error flow; reached after the relay started, it aborts the client connection and is logged to the app logger.
- The default 5-minute total timeout also cuts long downloads. Raise `totalTimeout` on mounts that relay large files.
- There are two cases the total timeout cannot release either: name resolution (DNS) before the socket exists follows the JDK resolver, and time blocked in I/O with the client (a slowly arriving request body, a client that reads the response slowly) is left to the adapter's time limits. A slowly arriving body ends with `TOTAL_TIMEOUT` at the first body read after the deadline.

A mount that relays large files:

```java
import com.snoworca.naruserve.proxy.Proxy;

String url = "http://127.0.0.1:8081";
app.use("/downloads", Proxy.to(url)
        .totalTimeout(2, TimeUnit.HOURS)               // the 5-minute default cuts long downloads
        .connectTimeout(3, TimeUnit.SECONDS));
```

Kotlin:

```kotlin
app.use("/downloads", Proxy.to(url)
    .totalTimeout(2, TimeUnit.HOURS)
    .connectTimeout(3, TimeUnit.SECONDS))
```

Express counterpart: `proxyTimeout` is the idle limit of the outgoing request's socket, like `readTimeout`; nothing corresponds to the other three.

## Retries

Retries are off by default (`retries(0)`). With `retries(n)` a failed request is sent up to `n` more times, each time over a new connection.

- After a TCP-level connect failure (connection refused, unresolved name, no route) every method is retried, because the backend never received the request. A connect timeout and a TLS failure are not retried.
- When the backend answers `502` or `503`, the request is retried only when the body sent to the backend was zero bytes (not chunked and length 0) and the method is `GET`, `HEAD` or `OPTIONS`. With `retryUnsafeMethods(true)` every method with a zero-byte body is retried. A request with a body is never retried, since its body cannot be sent again.
- Write, read and total timeouts, `INVALID_RESPONSE` and every other status are not retried.
- Before each retry the proxy waits `retryDelay` (default 200 milliseconds). When that wait would end past the total-timeout deadline, the last failure is returned at once.
- When `502` or `503` remains after the last retry, that backend response is relayed unchanged.
- A request thread that is interrupted stops retrying and keeps its interrupt status.

**Safe methods are an HTTP semantics distinction only.** A `GET` endpoint with side effects (for example `GET /api/payments/charge?id=1`) may run twice under retries. Give such a path its own proxy with retries off and mount it before the general mount, as the example in [Basic use](#basic-use) does.

Express counterpart: http-proxy-middleware has no retries.

## Concurrency limit

`maxConcurrent(n)` limits the number of requests in flight. By default there is no limit.

- An object created by `maxConcurrent` and the objects derived from it through other option methods share one counter.
- Calling `maxConcurrent` again on a derived object starts a new counter.
- A `Proxy` created by another `Proxy.to` call never shares a counter, even for the same target.
- Requests of mounts without a limit are not counted.
- The same object mounted in two apps counts the requests of both apps.
- A request over the limit gets `503` (`CONCURRENCY_LIMIT`) at once, without waiting and without a backend connection.
- The slot is taken after every check and `req.bodyStream(...)` pass, right before the backend connect, and returned when the request ends (the response body fully relayed or the client gone).

```java
import com.snoworca.naruserve.proxy.Proxy;

String url = "http://127.0.0.1:8081";
Proxy p = Proxy.to(url).maxConcurrent(16);
app.use("/search", p);                               // p and
app.use("/export", p.retries(0));                    // p.retries(0) share the limit of 16
app.use("/other", Proxy.to(url).maxConcurrent(16));  // a separately created object has its own 16
```

Kotlin:

```kotlin
val p = Proxy.to(url).maxConcurrent(16)
app.use("/search", p)
app.use("/export", p.retries(0))
app.use("/other", Proxy.to(url).maxConcurrent(16))
```

Express counterpart: none. The `maxSockets` of an `agent` comes closest, but it makes excess requests wait instead of refusing them.

## Thread model

- The proxy runs synchronously on the request thread. A proxied request holds one worker thread until it ends.
- When the client disconnects while the proxy waits for the backend, the adapter may notice only when the response starts. So the thread stays bound until the backend answers, the read timeout or the total timeout.
- Slow backends can use up the worker pool (`maxWorkers`, default 64) and make non-proxy routes wait, more so with a longer read timeout.
- Since there is no `maxConcurrent` limit by default, `maxWorkers` and `totalTimeout` are what bound the threads a proxy holds. Give backends that may be slow a per-mount limit with `maxConcurrent`.

## Backend connections

- The proxy talks to the backend through its own HTTP/1.1 client over `java.net.Socket` (no `HttpURLConnection` or `java.net.http`).
- Every backend request (every attempt of a retried one) opens a new connection and sends `Connection: close`, with no reuse. A backend that keeps the connection open delays neither the response nor the release of the slot.
- **High throughput can exhaust ports.** Every closed connection leaves a socket in TIME_WAIT, so at many requests per second the ephemeral ports of the proxy host can run out. The root remedy is a connection pool, which this version does not have.
- The write and total timeouts are enforced by one JVM-wide scheduler. The first proxy request that needs it creates it lazily, and it lives outside the app lifecycle, so stopping an app does not stop it. It runs one daemon thread that ends after about 10 seconds without proxy requests (within 30 seconds at the latest), so it needs no shutdown hook.

## https backends

For an `https` target the proxy connects a plain socket with the connect timeout and then layers TLS over it (SNI carries the target host name). Certificate chain and host name verification are always on, and no option turns them off. The proxy checks the host name itself after the handshake, also when `sslSocketFactory` is given. A wildcard `*` is allowed only as the whole leftmost label and matches one label, an IP literal target matches only `iPAddress` alternative names, and the subject CN is never used as a fallback. A failed check gets `502` (`CONNECT_FAILED`) before any request byte is sent.

To trust a private CA, pass an `SSLSocketFactory` that holds it.

```java
import com.snoworca.naruserve.proxy.Proxy;
import javax.net.ssl.SSLContext;

SSLContext tls = SSLContext.getDefault();   // in practice an SSLContext initialised with a TrustManager for the private CA
app.use("/internal", Proxy.to("https://backend.internal:8443").sslSocketFactory(tls.getSocketFactory()));
```

Kotlin:

```kotlin
val tls = SSLContext.getDefault()
app.use("/internal", Proxy.to("https://backend.internal:8443").sslSocketFactory(tls.socketFactory))
```

Express counterpart: `agent: new https.Agent({ ca })`. Nothing corresponds to `secure: false`, which turns verification off.

## Access log

Proxied requests appear in the access log in the same format as other requests. `in=` holds the number of request body bytes forwarded to the backend.
