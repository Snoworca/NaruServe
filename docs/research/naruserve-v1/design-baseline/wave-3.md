## 3. Request and response core

Covers issue #4 plus route registration and dispatch from #2 and #3; acceptance tests T01, T02, T03, T05, T06 (HTTP level through the in-memory adapter) and T07 (in-memory part; the socket part follows in section 5). Depends on sections: 1, 2.

### Public surface ownership

- [D-071] Wave 3 SHALL create the `Request` and `Response` interfaces holding the `docs/api/request.md` and `docs/api/response.md` methods whose parameter and return types exist by the end of wave 3, leaving every other method to the wave that owns its types (D-392).
- [D-072] Every public method parameter and return value of reference type in `Request` and `Response` SHALL carry `@Nullable` or `@NonNull`.
- [D-392] The remaining `Request` and `Response` methods SHALL be added by their owning waves: `req.service` and `req.application` in wave 4, `req.body`, `res.json`, `req.cookie`, `req.getCookies` and `res.addCookie` in wave 5, `req.form`, `req.file`, `req.files`, `req.parts`, `res.file`, `res.download` and `res.cache` in wave 6, and `res.sse` in wave 7.

### Route registration API

- [D-040] `RestApp` SHALL provide `get`, `post`, `put`, `patch`, `delete`, `head` and `options` taking `(String path, RouteHandler handler)` and returning `RestApp`.
- [D-041] `route(Method, path, handler)` and `route(MethodSet, path, handler)` SHALL register the one handler for every method in the argument and return `RestApp` (T01).
- [D-042] A request dispatched to a handler registered for several methods SHALL report its actual method through `req.method()` (T01).
- [D-043] `route(path)` SHALL return a `RouteBuilder` whose `get`, `post`, `put`, `patch`, `delete`, `head` and `options` each register a separate handler on that path (T03).
- [D-044] `app.get(path, h)` and `app.route(path).get(h)` SHALL produce the same registration, so registering both for one path is a duplicate (T03).
- [D-045] `RouteHandler` SHALL be a `@FunctionalInterface` with the single method `void handle(Request req, Response res) throws Exception`.
- [D-046] `RestApp` MUST NOT expose a `serve(...)` registration method.
- [D-393] Wave 3 SHALL create the `RestApp` interface with the route registration and routing policy methods, and each later wave adds the `RestApp` methods of its own features.

### Dispatcher and automatic responses

- [D-394] Wave 3 SHALL own the dispatcher, the single engine-free component in `NaruServe-api` that takes a request from an `HttpAdapter`, looks it up in the router and either calls the matched handler with `Request` and `Response` or writes the automatic response.
- [D-395] The dispatcher SHALL answer the no-match result with `404`, the method-mismatch result with `405` and an `Allow` header listing the accepted methods including automatic `HEAD` and `OPTIONS`, the automatic-OPTIONS result with `204` and `Allow`, the unsupported-method result with `501` and the WebSocket-only result with `426` plus the headers `Upgrade: websocket` and `Connection: Upgrade` required by RFC 9110 section 15.5.22 (T02, T05, T06).
- [D-396] For an automatic-`HEAD` result the dispatcher SHALL run the `GET` handler and send its status and headers without a body under D-102 (T05).
- [D-442] The dispatcher SHALL answer the asterisk-form request `OPTIONS *`, whose raw path TinyHttpd passes as `*`, with `204` and an `Allow` header listing the methods of `Method.any()` (D-036) without route lookup or the path rejection of D-052, and answer any other raw path that does not start with `/` with `400`, identically on the in-memory and TinyHttpd adapters.
- [D-397] The HTTP-level assertions of T01, T02, T03, T05 and T06 SHALL run through the in-memory adapter in wave 3.

### Request accessors

- [D-073] `req.path()` SHALL return the decoded path without the query string.
- [D-074] `req.rawPath()` SHALL return the path as received before decoding, with the inclusion of the query string decided in #4.
- [D-075] `req.param(name)` for a name absent from the matched pattern SHALL throw an exception.
- [D-076] `req.query(name)` SHALL return the first value for the name or `null`, and `req.queries(name)` returns every value in request order or an empty list (T07).
- [D-077] Query names and values SHALL be decoded as UTF-8 `application/x-www-form-urlencoded` text, with `+` read as a space (T07).
- [D-078] A query string with malformed percent-encoding or invalid UTF-8 SHALL make the request fail with `400` (T07).
- [D-079] `req.header(name)` SHALL match names case-insensitively and return the first value or `null`, and `req.headers(name)` returns every value or an empty list (T07).
- [D-080] `req.remoteAddress()` and `req.isSecure()` SHALL ignore `X-Forwarded-*` and `Forwarded` headers unless proxy trust is configured through the API decided in #8.
- [D-081] `req.lastEventId()` SHALL return the `Last-Event-ID` header value or `null`.
- [D-082] `req.attribute(key, value)` and `req.attribute(key, type)` SHALL store values per request, returning `null` for a missing key and never sharing values with another request.
- [D-083] The request attribute store SHALL be safe for concurrent writes from several threads serving the same request.
- [D-084] A mutating `Response` call (`status`, `header`, `addCookie`, `cache` or a body write) after the response completed SHALL throw an `IllegalStateException`, while the read accessors of `Request` and `Response`, `isCommitted()` and `abort()` stay usable after completion, including in middleware after `next.run()` returned and in `onError` (`docs/api/middleware.md`).

### Body ownership

- [D-085] Each request SHALL track its body with an internal `BodyState` moving from `UNREAD` through `PARSING` to `CONSUMED` or `CLOSED`.
- [D-086] Any body read after the first one (JSON, form, multipart or raw), including a repeated `req.body(...)` call through the same JSON reader, SHALL throw `BodyAlreadyConsumedException`, an unchecked exception in `NaruServe-api`, with the shared parse of D-087 as the only exemption.
- [D-087] Repeated calls of `form`, `file`, `files` and `parts` SHALL share one parse of the body.
- [D-088] Body size limits SHALL be enforced on the incoming byte stream before and during parsing, including chunked bodies without `Content-Length`, with `413` when exceeded.

### Response API

- [D-089] `res.status` and `res.header`, and `res.addCookie` and `res.cache` once waves 5 and 6 add them, SHALL return the same `Response` for chaining.
- [D-090] A response SHALL default to status `200`.
- [D-091] `res.send(String)` SHALL write UTF-8 text with `Content-Type: text/plain; charset=UTF-8` unless a `Content-Type` was set explicitly.
- [D-092] `res.html(String)` SHALL write UTF-8 text with `Content-Type: text/html; charset=UTF-8`.
- [D-093] `res.send(byte[])` SHALL write the bytes unchanged, with its default `Content-Type` decided in #4.
- [D-094] `res.stream(InputStream, contentType)` SHALL copy the stream to the end in bounded chunks, with closing of the stream decided in #4.
- [D-095] `res.end()` SHALL complete the response without a body.
- [D-096] `res.isCommitted()` SHALL return `true` once the status line and headers were handed to the transport.
- [D-097] `res.abort(error)` SHALL end the response abnormally by closing the connection and set the state to `ABORTED`.
- [D-098] `res.header(name, value)` SHALL reject a name or value containing CR or LF with an exception.

### Response state machine

- [D-099] A response SHALL move through the states `NEW`, `COMMITTED`, `STREAMING`, `COMPLETED` and `ABORTED`, committing at most once.
- [D-100] Calling `status` or `header` after commit, and `addCookie` or `cache` after commit once they exist, SHALL throw an exception.
- [D-101] Sending a second body after a body was sent SHALL throw an exception.
- [D-102] Responses with status `204` or `304`, and every response to a `HEAD` request, SHALL be sent without a message body, discarding any body the handler supplied.
- [D-103] A streaming response (`sse`, `file`, `download`, `stream`) SHALL stay in `STREAMING` after the handler returns until the transfer ends.

### HttpAdapter SPI

- [D-104] The `HttpAdapter` SPI SHALL live in `NaruServe-api` and use only JDK and NaruServe types.
- [D-105] The SPI SHALL give the core the request line, headers, a streaming body `InputStream`, remote address and secure flag, and accept status, headers and a streaming body `OutputStream`.
- [D-106] The SPI SHALL include extension points for file transfer, event streams and protocol upgrade, where the event-stream point hands the adapter a core-owned drain loop that keeps writing after the handler returned until the core reports the stream ended, and the adapter chooses the thread that runs that loop (the request worker on TinyHttpd, an `AsyncContext` on the Servlet adapter), so waves 6, 7 and 8 add adapter classes without changing the SPI.
- [D-107] The SPI SHALL let the core bind to an address and port synchronously, read the actual bound port, and stop in two calls: a blocking stop that stops accepting, waits for in-flight exchanges until a deadline the core passes and then force-closes the remaining connections, and a final release that waits at most a fixed margin for the adapter's threads to end.
- [D-108] `NaruServe-api` SHALL ship an in-memory `HttpAdapter` in a test fixtures source set that runs requests through the full core pipeline without sockets.
- [D-399] Every adapter SHALL pass the raw request-target path and the raw query string through the SPI for the core to decode once, with the TinyHttpd adapter taking them from `getRequestURI()` and `getQueryString()` and never from the decoded, `//`-merging `getPath()`.

### Wave 3 open items

- [D-109] Wave 3 SHALL decide the (미확정) items owned by #4 (query in `rawPath()`, default `Content-Type` of `send(byte[])`, stream closing in `stream()`, exception type for an attribute type mismatch) and update `docs/api/request.md` and `docs/api/response.md`.
- [D-398] Wave 3 SHALL decide the `RestApp` names of the routing policy APIs and the value of `req.method()` during automatic `HEAD`, and update `docs/api/routing.md` and `docs/api/app.md`, removing those markers.

