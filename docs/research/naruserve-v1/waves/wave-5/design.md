# Wave 5 design — 5. Engine adapter, JSON, cookies

Source: frozen run design `docs/research/naruserve-v1/design/00.design.md` lines 314–401 (design lock `docs/research/naruserve-v1/design/00.design.lock.json`). GitHub issues: #9, #10, #8. Depends on waves: wave-1, wave-2, wave-3, wave-4.

The items below are the frozen design items of this wave, verbatim and with their run ids; this wave implements every one of them. Integration items I-001..I-021 of the run design also hold at this wave's merge.


Covers issues #9, #10 and #8 in that order; acceptance tests T07 (over sockets), T08, T09, T33, T34, T48, T49; the Android parts of T10 and T12 follow in section 8. Depends on sections: 1, 2, 3, 4.

#### JsonCodec and TypeRef

- [D-183] `TypeRef<T>` SHALL capture the generic `java.lang.reflect.Type` of its anonymous subclass, so `new TypeRef<List<User>>() {}` yields `List<User>` (T08).
- [D-184] Constructing a `TypeRef` without a concrete type argument SHALL throw an exception.
- [D-421] `NaruServe-api` SHALL ship consumer R8/ProGuard rules under `META-INF/proguard/` that keep the `Signature`, `InnerClasses` and `EnclosingMethod` attributes and keep `TypeRef` and every class extending it with `allowobfuscation` and `allowshrinking`, so a `TypeRef` subclass keeps its generic type argument in a minified Android build.
- [D-185] `app.jsonCodec(codec)` SHALL replace the codec used by `req.body`, `res.json`, `ws.sendJson` and `ws.onJson`.

#### JSON request bodies

- [D-186] `req.body(Class)` and `req.body(TypeRef)` SHALL parse only bodies whose `Content-Type` is `application/json` or an allowed `+json` type, and answer `415` otherwise (T09).
- [D-187] A syntactically invalid JSON body SHALL produce `400` with code `BAD_REQUEST` (T09).
- [D-188] A JSON body larger than the limit (default 1 MiB) SHALL produce `413` without being fully read into memory (T09).
- [D-189] A JSON body nested deeper than the limit (default 64) SHALL produce `400` when decoded with the default codec (T09).
- [D-190] A JSON body with invalid UTF-8 SHALL produce `400`.
- [D-191] Decoding MUST NOT instantiate classes named inside the JSON input.
- [D-431] `req.body(Class)` and `req.body(TypeRef)` SHALL return `null` for a JSON `null` body and carry `@Nullable` on their return value, as the T08 round trip of D-194 requires.
- [D-440] When wave 5 applies D-431, it SHALL rewrite every Kotlin example in `docs/api/` that assigns the result of `req.body(Class)` or `req.body(TypeRef)` to a non-null type, dereferences it or passes it on to a non-null parameter (in `kotlin.md` the `val users: List<User>` example and the `val body = req.body(CreateUserRequest::class.java)` example followed by `body.name`, in `README.md` the `val user = req.body(User::class.java)` example and in `express-migration.md` the `val todo = req.body(Todo::class.java)` example, both passed to a service call) to use a nullable type with safe-call syntax or the reified extension of D-354, make the extension body shown there compile under the JSON `null` decision of D-192, and set the `req.body(type)` row of the nullability table to `T?`, so the Kotlin tests of D-349 and D-351 compile.
- [D-192] Wave 5 SHALL decide the JSON size and depth configuration API, whether `jsonTree()`/`jsonMap()` exist, and the handling of a JSON `null` body by the Kotlin reified extension of D-354, and update `docs/api/json.md`, `docs/api/request.md`, `docs/api/kotlin.md` and `docs/api/defaults.md`.

#### JSON responses

- [D-193] `res.json(value)` SHALL encode through the configured codec with `Content-Type: application/json; charset=UTF-8`.
- [D-194] POJOs, `List<User>`, strings, numbers, booleans and `null` SHALL round-trip through `req.body` and `res.json` unchanged, while an untyped `Map` round-trips to JSON-value equality only, because the default Gson decodes its numbers as `Double` (`42` becomes `42.0`) as `docs/api/request.md` states (T08).
- [D-195] `res.json("hello")` SHALL send the body `"hello"` including the quotes, and `res.json(null)` sends `null`.

#### Gson module

- [D-196] `GsonJsonCodec` SHALL live in the Gson module and accept a caller-built `Gson` instance.
- [D-197] The default `Gson` SHALL be built with `Strictness.STRICT` and `disableJdkUnsafe()`.
- [D-198] With the default codec, lenient JSON (comments, unquoted names, single quotes) SHALL be rejected with `400`.
- [D-199] With the default codec, decoding into a class without a no-argument constructor SHALL fail instead of creating the instance through `Unsafe`.

#### Cookie and SameSite

- [D-200] `Cookie` SHALL be a NaruServe class in `NaruServe-api` with constructor `Cookie(String name, String value)` and a name that cannot change after construction.
- [D-201] `Cookie` SHALL provide `getValue`/`setValue`, `getPath`/`setPath`, `getDomain`/`setDomain`, `getMaxAge`/`setMaxAge(int seconds)`, `getSecure`/`setSecure`, `isHttpOnly`/`setHttpOnly` and `getSameSite`/`setSameSite`, so Kotlin uses property syntax.
- [D-202] `SameSite` SHALL be an enum with `STRICT`, `LAX` and `NONE`.
- [D-203] `res.addCookie` SHALL add one `Set-Cookie` header per call, serialising `Path`, `Domain`, `Max-Age` in seconds, `Secure`, `HttpOnly` and `SameSite=Strict|Lax|None` when set (T33).
- [D-204] A cookie with `maxAge` 0 SHALL serialise `Max-Age=0` with its path and domain, deleting the cookie in browsers (T33).
- [D-205] A cookie name or value containing CR, LF, `;` or another character outside the RFC 6265 cookie grammar SHALL be rejected with an exception when it is set.
- [D-206] `req.cookie(name)` SHALL return the first matching request cookie or `null`, and `req.getCookies()` returns an empty array when the request has none (T34).
- [D-207] Cookies parsed from a request SHALL carry only name and value, with every response-only attribute left at its unset default (T34).
- [D-208] Wave 5 SHALL decide the default and negative meaning of `maxAge`, `Expires` support and signed cookies, and update `docs/api/cookie.md`.

#### TinyHttpd adapter

- [D-209] The TinyHttpd adapter SHALL implement the `HttpAdapter` SPI with TinyHttpd types confined to the adapter module.
- [D-210] The adapter SHALL be split into per-feature classes, so waves 6 and 7 add classes for file transfer, event streams and upgrades without editing the adapter core, while every TinyHttpd builder limit stays mapped in the adapter core by wave 5 (D-404).
- [D-211] The adapter SHALL bind to `InetAddress.getLoopbackAddress()` by default, passing it to TinyHttpd explicitly because TinyHttpd binds all interfaces when none is given (T49).
- [D-212] Binding to `0.0.0.0` or another non-loopback address SHALL require an explicit configuration call whose name is decided in #8 (T49).
- [D-213] `listen()` with a non-loopback bind address SHALL log a security warning through the app logger (T49).
- [D-214] The adapter SHALL configure TinyHttpd's Host allowlist so requests addressed to the configured bind address are accepted.
- [D-433] The v1 TinyHttpd adapter MUST NOT configure a TinyHttpd TLS listener, so `req.isSecure()` on it returns `true` only through the proxy trust of D-080, with TLS/WSS recorded as a post-v1 item under D-434.
- [D-215] The adapter SHALL configure TinyHttpd with `maxHeaderBytes` 16384 (16 KiB) answered with `431` and `maxRequestLineBytes` 8190 answered with `414`, a limit that counts the whole request line (method, target and version) and keeps TinyHttpd's default `inputBufferBytes` of 8192, because TinyHttpd's builder rejects an `inputBufferBytes` below `maxRequestLineBytes + 2` (T48).
- [D-414] The request-line configuration API of D-225 SHALL set TinyHttpd's `inputBufferBytes` to the larger of 8192 and the configured request-line limit plus 2, so every accepted value passes TinyHttpd's builder validation.
- [D-437] One request header line SHALL be limited to `inputBufferBytes - 2` bytes (8190 by default, more only when D-414 raises `inputBufferBytes`) and answered with TinyHttpd's transport-level `431` of D-400 beyond it, because TinyHttpd's `RequestParser.parseHeaders` reads each header line with that cap apart from the `maxHeaderBytes` total of D-215, with `docs/api/defaults.md` recording this per-line limit beside the header-size total and the header-size API of D-225 stating that it leaves the per-line limit unchanged.
- [D-216] The adapter SHALL keep TinyHttpd's header-count limit, header timeout and body-read timeout enabled and turn on the slow-body guard of D-413, closing header and body slowloris connections (T48).
- [D-413] The adapter SHALL set TinyHttpd's `minRequestBodyRate` to a default of 240 bytes per second, so a request body arriving slower than that after TinyHttpd's 5 s body-wait grace fails, is answered `408` with `Connection: close` and closes the connection, and a T48 test drip-feeds a body and asserts the `408` and the close.
- [D-217] Requests with conflicting `Content-Length` and `Transfer-Encoding` SHALL be rejected with `400` by the TinyHttpd parser, verified by a NaruServe test (T48).
- [D-218] The adapter SHALL use TinyHttpd's bounded worker pool, and a connection that cannot enter the full wait queue receives TinyHttpd's plain socket-level `503` under D-400.
- [D-219] The adapter SHALL map TinyHttpd body exceptions to `413` for size overruns, `408` with `Connection: close` for every `SocketTimeoutException` while reading the request body (the slow-body guard of D-413 and the body-read timeout kept by D-216), and `400` for truncated or malformed bodies.
- [D-443] The TinyHttpd adapter SHALL leave TinyHttpd's `maxRequestBodyBytes` at its default of `-1`, so no plain-text `413` is sent before dispatch and the core's per-type body limits of D-088 and D-188, and the form and multipart limits added in wave 6, answer `413` through `onError`, the standard error JSON and the access log, while TinyHttpd's `maxDrainBytes` (default 64 KiB) bounds the body bytes that no handler reads.
- [D-220] The adapter SHALL route TinyHttpd internal logging to the app `Logger`.
- [D-400] Requests that TinyHttpd rejects before dispatch with its fixed plain-text responses (`400` for an invalid request line, target, path, `Host` or framing including conflicting `Content-Length` and `Transfer-Encoding`, `408` for the header timeout, `414`, `417`, `431`, `501` for a `CONNECT` with an authority-form target and for unknown transfer codings, `505` for an unsupported HTTP version, and the socket-level `503`) SHALL bypass `onError`, the standard error JSON and the access log, and `docs/api/defaults.md` lists them as transport-level rejections.
- [D-422] A TinyHttpd adapter test SHALL send `CONNECT / HTTP/1.1` and assert the dispatcher's unsupported-method `501` with an access-log line, and send `CONNECT example.com:443 HTTP/1.1` and assert TinyHttpd's transport-level `501` with no access-log line.
- [D-405] The TinyHttpd adapter SHALL install a TinyHttpd `ErrorHandler` that renders every error TinyHttpd raises after parsing and outside any NaruServe dispatch, such as the `Host` rejection of D-418, as the standard error body of D-132 with a fresh request id.
- [D-418] A request whose `Host` fails TinyHttpd's allowlist of D-214 SHALL receive `421` rendered by the `ErrorHandler` of D-405 as the standard error body, bypassing `onError` and the access log, with the allowed host names set through the LAN binding API of D-225, as a TinyHttpd adapter test asserts and `docs/api/defaults.md` records.
- [D-404] The TinyHttpd adapter core SHALL map the NaruServe connection limits onto TinyHttpd's builder with these bounded defaults: HTTP worker budget 64 and SSE stream limit 128, which together set `maxWorkers` to 192; `maxConnections` 512, which bounds open plus queued ordinary connections, excludes upgraded WebSocket connections and so leaves a wait queue of `maxConnections - maxWorkers` = 320; `maxUpgradedConnections` 128, which bounds WebSocket connections on their own, so open connections total at most 640; and `maxConnectionsPerAddress` 256 over every open connection from one address, so the T26 load from one loopback address fits the defaults.
- [D-415] Freezing the configuration in `listen()` SHALL fail with a configuration error, before TinyHttpd's builder runs, when `maxConnections` is below the HTTP worker budget plus the SSE stream limit, and the wait-queue length is derived as `maxConnections - maxWorkers` instead of being configured on its own.
- [D-221] `listen(0)` SHALL bind an ephemeral port that `ServerHandle` reports.
- [D-222] A bind failure in the adapter SHALL make `listen()` throw with no socket left open.
- [D-223] `shutdown(timeout, unit)` SHALL implement the adapter stop of D-107 with TinyHttpd's `stop(grace)`, passing the time left until the shared deadline of D-162 as grace, and the adapter release with `awaitTermination` bounded by the fixed margin of D-107.
- [D-224] T07 SHALL run against the TinyHttpd adapter over real sockets as well as against the in-memory adapter.
- [D-225] Wave 5 SHALL decide the LAN binding API including the allowed host names of D-418, the proxy trust API, and the configuration APIs for header size, header count, request-line length (D-414), timeouts, the minimum request body rate of D-413 and the connection limits of D-404 with the derived wait-queue length of D-415, and confirm `431`/`414`, updating `docs/api/app.md`, `docs/api/defaults.md` (naming the limit request-line length instead of URL length) and `docs/api/request.md`.

#### Bootstrap and RestApp.create()

- [D-226] `RestApp.create()` SHALL locate the bootstrap factory at runtime (ServiceLoader or a fixed class name), so `NaruServe-api` has no compile-time reference to TinyHttpd or Gson.
- [D-227] `RestApp.create()` without the bootstrap module on the classpath SHALL throw an exception whose message names the missing module.
- [D-228] The bootstrap module SHALL assemble the TinyHttpd adapter with a `GsonJsonCodec` built from the default `Gson` configuration.
- [D-229] The bootstrap module SHALL ship consumer R8/ProGuard keep rules that keep the factory found by `RestApp.create()`.
- [D-402] `RestApp.create()` SHALL make the Logcat `Logger` the default app logger when `android.util.Log` is present at runtime, and the console `Logger` otherwise (`docs/api/logging.md`).
- [D-230] Wave 5 SHALL verify that `docs/api/README.md`, `docs/api/app.md` and `docs/api/json.md` name the bootstrap and Gson modules exactly as wave 1 fixed them.



## Carried from loop D

Loop D ended at its round cap; the committee chose fix-and-freeze. The fixes below landed in the frozen design and this wave re-checks them in loop W:

- V1-002: New D-443: the TinyHttpd adapter leaves maxRequestBodyBytes at -1 so no pre-dispatch plain-text 413 exists; core per-type limits answer 413 through onError/error JSON/access log, and maxDrainBytes bounds unread bodies. D-400 stays unchanged since no pre-dispatch 413 is configured. (items D-443)
- V1-005: D-440 extended to every docs/api Kotlin example using req.body(...), naming the README.md and express-migration.md examples passed to service calls. (items D-440)

## Carried from earlier waves

- TinyHttpd halves of D-399, D-442 and I-021: the TinyHttpd adapter takes the raw request-target path from `getRequestURI()` and the raw query from `getQueryString()`, never from the decoded, `//`-merging `getPath()`. The asterisk-form `OPTIONS *` (raw path `*`) and the D-052 path-rejection cases run on both the in-memory and the TinyHttpd adapter with identical status results, and the tests also compare the `rawPath()` and decoded query values, not only the status. The compared requests reach a handler: `/a//b` sent with `RouterPolicy.builder().mergeSlashes(true)` to a `/a/b` route, and an allowed percent-encoded segment `/a%20b` sent to a `/:x` route (an encoded slash is rejected with `400` by D-052); both compare `rawPath()` and the decoded values across the adapters, the only inputs that tell `getPath()` apart from `getRequestURI()`. Under the default strict policy `/a//b` gives `404` and is compared by status only, as are rejected requests and `OPTIONS *`, which never reach a handler.
- From wave 4: the TinyHttpd keep-alive socket case of D-166 and acceptance test T44 (an idle keep-alive connection is closed once the adapter stop begins; in-flight responses commit with `Connection: close`); the bootstrap factory of D-228 behind wave 4's `ServiceLoader` lookup (wave 4 already delivered the lookup of D-226 and the missing-module exception of D-227), the end-to-end half of D-110 (`RestApp.create()` returns the bootstrap-assembled app; wave 5 re-runs the D-226 lookup test with the real bootstrap module on the classpath and exactly one `AppFactory` provider; the D-227 missing-module test stays a wave 4 test), the `create()` half of D-169, and D-402 (`RestApp.create()` defaults to the Logcat logger when `android.util.Log` is present, else the console logger); the TinyHttpd adapter holds a non-daemon thread from bind until stop so the JVM stays alive after `main` returns (D-167, TinyHttpd threads are daemon by default).
- From wave 4 (D-136, D-112): an exception thrown while `res.json` serialises turns into the standard error response only while the response is uncommitted, and a committed response is aborted instead; wave 5 asserts this once `res.json` and the Gson codec exist (wave 4 created only the `JsonCodec` SPI interface of D-182).

## Wave decisions

These choices settle behaviour that the frozen items leave open between waves and to the implementer under the standing delegation rule. They add no new requirement; each line names the frozen items it applies to. Between equally valid options the decision follows HTTP semantics (RFC 9110) and the behaviour an Express user expects.

- Server settings reach the adapter (W2-001 of round 1, W2-001/W2-003 of round 2; D-225, D-104, D-213, D-214, D-220, D-404, D-405, D-413, D-414, D-415, D-418, D-437): every adapter-level D-225 setting — bind address, allowed host names, header size and count, request-line length, timeouts, minimum body rate and connection limits — is configured on `RestApp` while it is `NEW`, so `RestApp.create()` users reach all of it, and never only on an adapter-specific builder. The core hands the frozen values to the adapter at `listen()` through one immutable settings value of the SPI, given to a new `HttpAdapter` bind method. That value uses JDK and `NaruServe-api` SPI types only (D-104), never engine types: it carries the app `Logger` for D-220 and a core-provided renderer of the standard error body and request id, so the TinyHttpd module produces the D-405/D-418 error bodies without calling `internal` classes. The current `bind(host, port, handler)` stays, and the new method's default implementation delegates to it, so the in-memory adapter and third-party adapters keep compiling. Proxy trust (D-080) is not an adapter setting: the core applies it in `DefaultRequest` on every adapter, the in-memory one included. `listen(port)` keeps the loopback default. The public names follow the Express-first rule and are decided in the wave 5 SRS (the C-07 delegation), replacing the docs/api (미확정) markers they settle.
- Request cookies are parsed leniently (W2-002 of round 1, W1-001/W1-002/W1-003/W2-004 of round 2, W1-001/W2-001 of round 3; D-203, D-205, D-206, D-207, D-208): D-205's exception applies whenever a cookie is set or sent — `new Cookie(...)`, its setters and `res.addCookie(...)` — so a request cookie echoed back with an invalid name or value throws instead of emitting a bad `Set-Cookie`. Parsing the request `Cookie` header never throws. A pair whose name is outside the RFC 6265 token grammar is skipped, and every value is kept exactly as the client sent it — no percent-decoding and no quote stripping — like the Servlet `Cookie` model this class follows (W1-001/W2-001 of round 3). So a value round-trips unchanged: `res.addCookie(req.cookie("x"))` sends back what arrived, and an app that sets `a%2Cb` reads `a%2Cb`. The other pairs are still returned, so one bad cookie from a client never turns `req.getCookies()` into a `500`. Unlike Express's `res.cookie`, NaruServe neither encodes nor decodes values; `docs/api/cookie.md` says so, and an app that needs other characters encodes them itself (for example with `URLEncoder`). A T34 case sets a value containing `%XX`, reads it back unchanged and sends it again.
- A JSON `null` decodes to `null` (W2-003 of round 1; D-431, D-182, D-194): `JsonCodec.decode` carries `@Nullable` on its return value, and the Gson codec returns `null` for a JSON `null` document. That is source- and binary-compatible with the wave 4 interface and lets `req.body(...)` return `null` as D-431 requires. `res.json(null)` writes the JSON literal `null`. An empty or whitespace-only JSON body is not a JSON `null`: the core answers it with D-187's `400` before the codec runs, because Gson 2.14 returns `null` for empty input (W2-002 of round 2).
- The 413 branch of D-219 is a guard (W2-004 of round 1; D-219, D-443): with D-443 in force, TinyHttpd never raises its body-size exception, so the adapter's mapping of that exception to `413` stays as defensive code. It is unit-tested against a constructed engine exception, not through a real request, and `413` for a too-large body comes from the core limits through `onError`, as D-443 states.
- The LAN warning is unconditional (W2-005 of round 1; D-213, T49): `listen()` with any non-loopback bind address logs the security warning, because the framework cannot tell whether the app installed global authentication. `docs/api/defaults.md` is corrected to say that it always warns and that the app must add authentication itself.
- The default bind address is IPv4 `127.0.0.1` (W2-001 of round 4; D-211, D-212, D-221): D-211's loopback default is applied as the literal IPv4 loopback `127.0.0.1`, passed to TinyHttpd explicitly. The adapter does not call `InetAddress.getLoopbackAddress()`, which can return the IPv6 `::1` on Android; a socket bound there refuses `127.0.0.1`, the address the handle reports and the docs name. `ServerHandle.address()` reports the address actually bound — the configured bind address after a LAN bind, `127.0.0.1` by default — and its javadoc and `docs/api/app.md` say so (W1-001 of round 4).
- The JSON depth limit lives in the Gson codec (W2-002 of round 4; D-189, D-192, D-182): `JsonCodec.decode(Reader, Type)` keeps its frozen signature. The nesting depth is a `GsonJsonCodec` setting enforced through `JsonReader.setNestingLimit` on every decode, with 64 as the default for every instance, including one built from the app's own `Gson` (`new GsonJsonCodec(gson)`), so replacing the Gson configuration never silently raises the limit to Gson's 255. Exceeding it gives D-189's `400`.
- The adapter gets the safe logger (W2-003 of round 4; D-220): the settings value carries the core's exception-safe wrapper of the app logger, not the raw user logger, so a logger that throws never breaks a TinyHttpd thread.
- Thread wording (W2-004 of round 4; D-167): TinyHttpd's worker and acceptor threads stay daemon; the adapter holds one non-daemon keeper thread from bind until stop, which keeps the JVM alive after `main` returns. `docs/api/app.md` is reworded to describe that keeper thread instead of saying the server threads are non-daemon.

## Residual carried into SRS authoring

Loop W ended at its round cap (5) with no critical or high finding. The four remaining findings of round 5 are settled below; SRS authoring and the feasibility check confirm them, since no loop W round re-verified these lines.

- D-211 wording (r5 W1-001; D-211): the SRS criterion for D-211 states that the adapter binds the IPv4 loopback `127.0.0.1` explicitly by default and cites the wave decision "The default bind address is IPv4 `127.0.0.1`", so the criterion and the code agree; the loopback-only intent of D-211 is unchanged.
- Trailing JSON data (r5 W2-001; D-187, D-189, D-192, D-198): after decoding through a `JsonReader` with the nesting limit, the Gson codec requires `reader.peek() == END_DOCUMENT` and otherwise fails with D-187's `400`, because `fromJson(JsonReader, Type)` does not check for trailing input. T09 gains the cases `{"a":1} x` and `{} {}`.
- Upper bounds on size settings (r5 W2-002; D-414, D-225): every size and count setting of D-225 refuses values below 1 and above a fixed per-setting maximum with `IllegalArgumentException`, and no setting accepts "unlimited", which settles the open question in `docs/api/defaults.md`. The request-line limit is capped at 65534 bytes, so D-414's `inputBufferBytes` is at most 65536 and cannot overflow; the SRS names the maximum of every other setting.
- Building request cookies (r5 W2-003; D-200, D-205, D-206, D-207): the request-cookie parser in the `internal` package builds `Cookie` objects through a package-private, non-validating factory of `Cookie` (reached from `internal` through an accessor in the same package as `Cookie`, adding no public API), so values kept exactly as sent never throw; only the public constructor, the setters and `res.addCookie` validate.
