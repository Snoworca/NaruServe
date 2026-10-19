# Wave 3 design — 3. Request and response core

Source: frozen run design `docs/research/naruserve-v1/design/00.design.md` lines 127–212 (design lock `docs/research/naruserve-v1/design/00.design.lock.json`). GitHub issues: #4, #2, #3. Depends on waves: wave-1, wave-2.

The items below are the frozen design items of this wave, verbatim and with their run ids; this wave implements every one of them. Integration items I-001..I-021 of the run design also hold at this wave's merge, for the components that exist by then (see the adapter-halves and docs-examples decisions below).


Covers issue #4 plus route registration and dispatch from #2 and #3; acceptance tests T01, T02, T03, T05, T06 (HTTP level through the in-memory adapter) and T07 (in-memory part; the socket part follows in section 5). Depends on sections: 1, 2.

#### Public surface ownership

- [D-071] Wave 3 SHALL create the `Request` and `Response` interfaces holding the `docs/api/request.md` and `docs/api/response.md` methods whose parameter and return types exist by the end of wave 3, leaving every other method to the wave that owns its types (D-392).
- [D-072] Every public method parameter and return value of reference type in `Request` and `Response` SHALL carry `@Nullable` or `@NonNull`.
- [D-392] The remaining `Request` and `Response` methods SHALL be added by their owning waves: `req.service` and `req.application` in wave 4, `req.body`, `res.json`, `req.cookie`, `req.getCookies` and `res.addCookie` in wave 5, `req.form`, `req.file`, `req.files`, `req.parts`, `res.file`, `res.download` and `res.cache` in wave 6, and `res.sse` in wave 7.

#### Route registration API

- [D-040] `RestApp` SHALL provide `get`, `post`, `put`, `patch`, `delete`, `head` and `options` taking `(String path, RouteHandler handler)` and returning `RestApp`.
- [D-041] `route(Method, path, handler)` and `route(MethodSet, path, handler)` SHALL register the one handler for every method in the argument and return `RestApp` (T01).
- [D-042] A request dispatched to a handler registered for several methods SHALL report its actual method through `req.method()` (T01).
- [D-043] `route(path)` SHALL return a `RouteBuilder` whose `get`, `post`, `put`, `patch`, `delete`, `head` and `options` each register a separate handler on that path (T03).
- [D-044] `app.get(path, h)` and `app.route(path).get(h)` SHALL produce the same registration, so registering both for one path is a duplicate (T03).
- [D-045] `RouteHandler` SHALL be a `@FunctionalInterface` with the single method `void handle(Request req, Response res) throws Exception`.
- [D-046] `RestApp` MUST NOT expose a `serve(...)` registration method.
- [D-393] Wave 3 SHALL create the `RestApp` interface with the route registration and routing policy methods, and each later wave adds the `RestApp` methods of its own features.

#### Dispatcher and automatic responses

- [D-394] Wave 3 SHALL own the dispatcher, the single engine-free component in `NaruServe-api` that takes a request from an `HttpAdapter`, looks it up in the router and either calls the matched handler with `Request` and `Response` or writes the automatic response.
- [D-395] The dispatcher SHALL answer the no-match result with `404`, the method-mismatch result with `405` and an `Allow` header listing the accepted methods including automatic `HEAD` and `OPTIONS`, the automatic-OPTIONS result with `204` and `Allow`, the unsupported-method result with `501` and the WebSocket-only result with `426` plus the headers `Upgrade: websocket` and `Connection: Upgrade` required by RFC 9110 section 15.5.22 (T02, T05, T06).
- [D-396] For an automatic-`HEAD` result the dispatcher SHALL run the `GET` handler and send its status and headers without a body under D-102 (T05).
- [D-442] The dispatcher SHALL answer the asterisk-form request `OPTIONS *`, whose raw path TinyHttpd passes as `*`, with `204` and an `Allow` header listing the methods of `Method.any()` (D-036) without route lookup or the path rejection of D-052, and answer any other raw path that does not start with `/` with `400`, identically on the in-memory and TinyHttpd adapters.
- [D-397] The HTTP-level assertions of T01, T02, T03, T05 and T06 SHALL run through the in-memory adapter in wave 3.

#### Request accessors

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

#### Body ownership

- [D-085] Each request SHALL track its body with an internal `BodyState` moving from `UNREAD` through `PARSING` to `CONSUMED` or `CLOSED`.
- [D-086] Any body read after the first one (JSON, form, multipart or raw), including a repeated `req.body(...)` call through the same JSON reader, SHALL throw `BodyAlreadyConsumedException`, an unchecked exception in `NaruServe-api`, with the shared parse of D-087 as the only exemption.
- [D-087] Repeated calls of `form`, `file`, `files` and `parts` SHALL share one parse of the body.
- [D-088] Body size limits SHALL be enforced on the incoming byte stream before and during parsing, including chunked bodies without `Content-Length`, with `413` when exceeded.

#### Response API

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

#### Response state machine

- [D-099] A response SHALL move through the states `NEW`, `COMMITTED`, `STREAMING`, `COMPLETED` and `ABORTED`, committing at most once.
- [D-100] Calling `status` or `header` after commit, and `addCookie` or `cache` after commit once they exist, SHALL throw an exception.
- [D-101] Sending a second body after a body was sent SHALL throw an exception.
- [D-102] Responses with status `204` or `304`, and every response to a `HEAD` request, SHALL be sent without a message body, discarding any body the handler supplied.
- [D-103] A streaming response (`sse`, `file`, `download`, `stream`) SHALL stay in `STREAMING` after the handler returns until the transfer ends.

#### HttpAdapter SPI

- [D-104] The `HttpAdapter` SPI SHALL live in `NaruServe-api` and use only JDK and NaruServe types.
- [D-105] The SPI SHALL give the core the request line, headers, a streaming body `InputStream`, remote address and secure flag, and accept status, headers and a streaming body `OutputStream`.
- [D-106] The SPI SHALL include extension points for file transfer, event streams and protocol upgrade, where the event-stream point hands the adapter a core-owned drain loop that keeps writing after the handler returned until the core reports the stream ended, and the adapter chooses the thread that runs that loop (the request worker on TinyHttpd, an `AsyncContext` on the Servlet adapter), so waves 6, 7 and 8 add adapter classes without changing the SPI.
- [D-107] The SPI SHALL let the core bind to an address and port synchronously, read the actual bound port, and stop in two calls: a blocking stop that stops accepting, waits for in-flight exchanges until a deadline the core passes and then force-closes the remaining connections, and a final release that waits at most a fixed margin for the adapter's threads to end.
- [D-108] `NaruServe-api` SHALL ship an in-memory `HttpAdapter` in a test fixtures source set that runs requests through the full core pipeline without sockets.
- [D-399] Every adapter SHALL pass the raw request-target path and the raw query string through the SPI for the core to decode once, with the TinyHttpd adapter taking them from `getRequestURI()` and `getQueryString()` and never from the decoded, `//`-merging `getPath()`.

#### Wave 3 open items

- [D-109] Wave 3 SHALL decide the (미확정) items owned by #4 (query in `rawPath()`, default `Content-Type` of `send(byte[])`, stream closing in `stream()`, exception type for an attribute type mismatch) and update `docs/api/request.md` and `docs/api/response.md`.
- [D-398] Wave 3 SHALL decide the `RestApp` names of the routing policy APIs and the value of `req.method()` during automatic `HEAD`, and update `docs/api/routing.md` and `docs/api/app.md`, removing those markers.



## Carried from loop D

Loop D ended at its round cap; the committee chose fix-and-freeze. The fixes below landed in the frozen design and this wave re-checks them in loop W:

- V1-006: New D-442: OPTIONS * answered 204 with Allow = Method.any() methods without route lookup or D-052 rejection; any other raw path not starting with / gets 400; identical on in-memory and TinyHttpd adapters. I-021 now includes the OPTIONS * case. (items D-442, I-021)
- V2-004: Same fix as V1-006: D-442 fixes the dispatcher's answer to OPTIONS * on both adapters; I-021 covers it. (items D-442, I-021)

## Carried from earlier waves

- D-442 applies to paths that reach NaruServe. TinyHttpd answers authority-form `CONNECT` (for example `CONNECT host:443`) with `501` itself and rewrites an absolute-form target to its path before dispatch; the in-memory adapter mirrors both so the adapters agree (I-021).
- The dispatcher and every adapter run the wave 2 path validator before the router lookup and answer `400` on rejection; the validator does not decode.
- The `req.method()` value during automatic `HEAD` and the routing-policy configuration API names are decided in wave 3 (D-398).

## Wave decisions

These choices settle behaviour that the frozen items leave open between waves and to the implementer under the standing delegation rule. They add no new requirement; each line names the frozen items it applies to. Between equally valid options the decision follows HTTP semantics (RFC 9110) and the behaviour an Express user expects.

- Working app in wave 3 (W2-001; D-040..D-046, D-393, D-394, D-397, D-044, D-058, D-111, D-154): wave 3 ships a `RestApp` implementation in an internal package of `NaruServe-api` (a public class in a package documented as internal and not part of the API contract, since Java 8 has no module exports; wave 4's entry point constructs it) that holds the router and the dispatcher, plus a test-fixtures harness that builds it over the in-memory adapter, runs the route-table freeze and sends requests. Each registration call parses its pattern immediately (`RoutePattern.parse`), so an invalid pattern fails at registration (D-047, D-048); the implementation records the parsed registrations and the routing policy and builds the `Router` with that policy only at the freeze, so a policy call made after `app.get(...)` still applies (W2-004 of round 2). The duplicate of D-044 is raised by that freeze and asserted through the harness. Wave 4 makes `listen()` run the same freeze (D-058, D-154), and the public entry point of D-111 stays with #5; wave 3 adds no public factory.
- Adapter halves (W2-002; D-399, D-442, I-021): wave 3 defines the SPI fields that carry the raw request-target path and the raw query string, and implements the in-memory side, which mirrors the TinyHttpd behaviour recorded under "Carried from earlier waves". The TinyHttpd halves of D-399, D-442 and I-021 are built and checked in wave 5 with the adapter (D-209); `docs/research/naruserve-v1/waves/carried-into-wave-5.md` carries them, including reading `getRequestURI()` and `getQueryString()` and never `getPath()`, and tests that compare the `rawPath()` and query values, not only statuses, across both adapters. At this wave's merge an integration item holds for the components that exist.
- Request and response surface (W2-003, W1-101; D-071, D-392, D-084): D-392's list overrides D-071, so wave 3 does not add `req.body`, `req.form`, `req.file`, `req.files`, `req.parts`, `res.json`, `res.file`, `res.download`, `res.cache`, `res.sse`, the cookie methods, `req.service` or `req.application`, even where their types exist. The "throw after completion" rule of D-084 applies to `addCookie` and `cache` from the waves that add them (5 and 6).
- Body ownership (W2-004; D-085..D-088, I-012): wave 3 delivers `BodyAlreadyConsumedException` as a public unchecked exception in the public package of `NaruServe-api` (users catch it, `docs/api/request.md`), and `BodyState` and a limit-enforcing input stream as internal components, all with direct unit tests. The stream requires a positive limit at construction, so no unlimited default exists (I-012). Wave 3 exposes no public body-reading method. The "raw" kind of D-086 is an internal consumer of the body stream (the adapter-facing readers of later waves); no wave adds a public raw-body accessor, because neither `object.md` nor `docs/api` defines one. Wave 3 replaces the `express.raw()`/`express.text()` marker in `docs/api/express-migration.md` with the statement that v1 has no raw or text body reader. The HTTP-level `413` checks land with the body readers in waves 5 and 6.
- `abort()` after completion (W2-005; D-103, D-084): `res.abort()` on a response that is already complete or aborted does nothing.
- `req.method()` during automatic `HEAD` (W2-006; D-042, D-396, D-398): it returns `HEAD`, the actual request method. `docs/api/routing.md` records this and removes the marker.
- `stream()` closing (W2-007; D-103, D-109): `res.stream(InputStream, contentType)` (D-094) takes ownership of the stream and closes it when the transfer ends, whether by success, failure or abort, including after the handler has returned. The caller does not close it, and `docs/api/response.md` says so with an example that does not use try-with-resources.
- Automatic response bodies (W2-008; D-395, D-442, D-052): in wave 3 the automatic `400`, `404`, `405`, `426` and `501` responses have an empty body (`Content-Length: 0`) and the `204` replies have none. Wave 4 gives the error statuses the standard error body. Wave 3 tests assert status and headers only.
- Order before lookup (W2-003 of round 2; D-442, D-052, D-394): the dispatcher checks the asterisk-form `OPTIONS *` first, then rejects any other raw path not starting with `/` with `400`, then runs the path validator (`400` on rejection), and only then calls the router lookup.
- Interim failure handling (W2-001 of round 2; D-078, D-126, D-127, D-129, D-134): the dispatcher decodes the query string before calling the handler and answers malformed percent-encoding or invalid UTF-8 with `400` (T07). Until wave 4 adds the error path, an exception thrown by a handler gives `500` with an empty body while the response is not yet committed, and aborts the response once it is committed or streaming (D-097, D-099, D-131), and a handler that returns without finishing a non-streaming response has the response finished by the dispatcher with the status and headers set so far and an empty body. Wave 4 replaces both with D-126..D-134 behaviour.
- Docs examples (W2-006 of round 2; I-009): at this wave's merge I-009 covers the `docs/api` Java examples whose methods exist. An example that uses a method of a later wave (for example `res.json`) joins the compiled set in the wave that adds the method.
- Remaining markers (W1-110 of round 2; D-109, D-398): `rawPath()` returns the raw, undecoded path without the query string, which stays available through `req.query(...)`. `send(byte[])` without a `Content-Type` uses `application/octet-stream`, as Express does for a `Buffer`. `req.attribute(name, type)` with a stored value of another type throws `ClassCastException`. The routing policy is set with `app.routerPolicy(RouterPolicy)`, taking the existing `RouterPolicy` built with `RouterPolicy.builder()` (`mergeSlashes`, `ignoreTrailingSlash`, `caseInsensitive`, `enableTrace`, `enableConnect`), allowed only in `NEW` like every setting. The Express counterpart of `rawPath()` becomes "`req.path` before decoding" (not `req.originalUrl`, which includes the query). Wave 3 removes these markers, including the routing-policy setting-API markers at `docs/api/routing.md` (TRACE/CONNECT and path-form policy) and `docs/api/defaults.md`, and the `req.method()` marker, from `docs/api/request.md`, `docs/api/response.md`, `docs/api/routing.md`, `docs/api/app.md`, `docs/api/defaults.md` and `docs/api/express-migration.md`. The markers owned by other issues (`file` disposition, `redirect`, proxy trust, `parts()`, `RouteBuilder.use`) stay.
- Settings after the freeze (W2-004 of round 3; D-114, D-152): in wave 3 the internal implementation rejects a route registration or `routerPolicy` call after the harness freeze with `IllegalStateException`; wave 4 ties the same rule to the `NEW` state.
- Null attribute values (W2-006 of round 3; D-072, D-082, D-083): the value parameter of `req.attribute(key, value)` is `@Nullable` and storing `null` removes the key, so a later read returns `null`. Because Java resolves a literal `req.attribute("k", null)` to the read overload, `docs/api/request.md` says to remove a value with a typed `null` variable. The store is a concurrent map that never holds `null`.
- `426` over TinyHttpd (W2-001 of round 3; D-069, D-395, D-408, D-020): TinyHttpd at `46b973e` writes only `Connection: close` or `keep-alive` on a non-`101` response, so the `Connection: Upgrade` header of the `426` answer cannot pass through it. The dispatcher still sets both headers (in-memory tests assert both); `docs/research/naruserve-v1/waves/carried-into-wave-7.md` records the gap as a deferred issue under D-020, Wave 7 asserts the full D-408 (both headers) through the in-memory adapter and `426` with `Upgrade: websocket` over TinyHttpd; D-408 is not promoted to `verified` on the TinyHttpd test alone, and the D-020 issue is opened in the wave 7 issue ledger.

## Residual carried into SRS authoring

Loop W ended at its round cap (5) with no critical or high finding. The four remaining findings of round 5 are settled below; SRS authoring and the feasibility check confirm them, since no loop W round re-verified these lines.

- Marker test of wave 2 (r5 W2-001; D-109, D-398, OPS-ROUTE-001 AC-3): removing the routing-policy and `req.method()` markers from `docs/api/routing.md` changes what OPS-ROUTE-001 AC-3 (verified) and its test `RoutingDocsTest` assert. Wave 3 SRS authoring records the change with a guarded SRS mutation that supersedes OPS-ROUTE-001 with a successor that keeps its ACs and restates AC-3 as "`docs/api/routing.md` keeps only the markers owned by other issues (`RouteBuilder.use`) and adds none"; the worker updates the test to cite the successor. This is a requirement change, not a weakened test.
- Pre-request rejections (r5 W2-002; D-422, D-442, D-052, D-117, D-129, I-014): the dispatcher answers an unrecognised method token (`501`), a raw path not starting with `/` (`400`), a path rejected by the validator (`400`) and `OPTIONS *` (`204`) before it builds a `Request`. No middleware, handler or `onError` sees these, so `req.method()` stays a non-null `Method` and `req.path()` a non-null decoded path. D-117 and D-129 apply to every request for which a `Request` is built, including a malformed query (`400`), which is decoded after the `Request` exists.
- Adapter value comparison (r5 W1-001, W2-003): `carried-into-wave-5.md` names the policy and routes: `/a//b` under `mergeSlashes(true)` to a `/a/b` route, and `/a%20b` to a `/:x` route, both compared by `rawPath()` and decoded values; `/a//b` under the default policy is compared by status (`404`) only.

