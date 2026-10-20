# Wave 6 existing SRS context (multipart and static files)

Read-only analysis of the verified requirements of targets wave-1..wave-5 against `docs/research/naruserve-v1/waves/wave-6/design.md` (frozen items, "Wave decisions", "Residual"). Source: `docs/spec/0[1-8]*.srs.md` at the integration branch HEAD (`f0ee81b`), 119 verified non-discarded requirements scanned, all `Stability=evolving`.

Rule applied: supersede only when wave 6 makes the verified AC text literally false and the test tied to it through the Verification Evidence table must assert something different. ACs phrased as statements about an earlier merge, or as a wave's delta to a surface set, follow the existing re-point convention (IR-APP-002 AC-2, IR-APP-004 AC-3, IR-ENGINE-001 AC-7, IR-JSON-002 AC-8, FR-COOKIE-002 AC-8, whose Decision says "new methods add positions without a supersede, following the wave 4 precedent"): wave 6 re-points those tests from one of its own new ACs instead of superseding.

## 1. Requirements to supersede

### 1.1 FR-ENGINE-001 (scope ENGINE)

- Title: TinyHttpd adapter implements HttpAdapter with confined engine types and per-feature classes
- File: `docs/spec/06.engine-adapter.srs.md`; target wave-5; verified; evolving
- Changed: AC-2 only.
- Why: AC-2 asserts that the file-transfer path throws `UnsupportedOperationException` naming wave 6 and that `TinyHttpdFeatures` throws it. The wave decision "File bodies come from the opened stream" says the TinyHttpd `sendFile` hook is built as a plain buffered copy and its "arrives in wave 6" message goes away. The test `sdsAc2_FR_ENGINE_001_AC2_featureHooksThrowNamingTheirWave` asserts `parts[0].contains("wave 6")` on the thrown message and has to change to a byte-range read-back. This is a behaviour flip, not a surface-list delta, so the re-point convention does not cover it.
- Old AC-2 text (verbatim): "The adapter core class sets every TinyHttpd builder limit of FR-ENGINE-003 and FR-ENGINE-004, while the exchange bridge and the file-transfer, event-stream and upgrade paths of `HttpExchange` are separate classes of the module; in wave 5 the file-transfer, event-stream and upgrade classes throw `UnsupportedOperationException` naming the wave (6 or 7) that adds them. [D-210; D-404] Decision [SDS 2026-10-19.naruserve.naruserve-v1-wave-5-5 SDS-AC-2]: in wave 5 the file-transfer, event-stream and upgrade paths of the exchange delegate to the module class `TinyHttpdFeatures`, which throws the `UnsupportedOperationException`."

All old ACs, verbatim:

- AC-1: `TinyHttpdAdapter` is a public final class with a public no-argument constructor implementing `HttpAdapter`, and reflection finds no `com.snoworca.tinyhttpd` type in any public or protected constructor, method or field signature of any public class of `NaruServe-tinyhttpd`. [D-209] Decision: the adapter class is `com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter`.
- AC-2: The adapter core class sets every TinyHttpd builder limit of FR-ENGINE-003 and FR-ENGINE-004, while the exchange bridge and the file-transfer, event-stream and upgrade paths of `HttpExchange` are separate classes of the module; in wave 5 the file-transfer, event-stream and upgrade classes throw `UnsupportedOperationException` naming the wave (6 or 7) that adds them. [D-210; D-404] Decision [SDS 2026-10-19.naruserve.naruserve-v1-wave-5-5 SDS-AC-2]: in wave 5 the file-transfer, event-stream and upgrade paths of the exchange delegate to the module class `TinyHttpdFeatures`, which throws the `UnsupportedOperationException`.
- AC-3: No class of `NaruServe-tinyhttpd` references TinyHttpd's `TlsConfig` or calls the builder's `tls(...)` or a `listen(..., TlsConfig)` overload, which a class-reference test checks, and on the TinyHttpd adapter `req.isSecure()` returns `false` unless proxy trust of FR-ENGINE-008 reports `https`. [D-433; D-080]
- AC-4: Over a real socket, `GET /a%20b?x=%41` reaches the core with raw path `/a%20b` and raw query `x=%41`, and a request without `?` has a `null` raw query, because the adapter reads `getRequestURI()` and `getQueryString()` and never `getPath()`. [D-399; I-021; wave-3 carried: TinyHttpd halves]
- AC-5: The adapter's request headers, method token, HTTP version and remote address reach the core unchanged, and the adapter's `boundPort()` throws `IllegalStateException` before bind like the SPI requires. [D-209; D-107]

Proposed successor ACs: AC-1, AC-3, AC-4 and AC-5 are carried over unchanged. AC-2 is restated:

- AC-2 (restated): The adapter core class sets every TinyHttpd builder limit of FR-ENGINE-003 and FR-ENGINE-004, while the exchange bridge and the file-transfer, event-stream and upgrade paths of `HttpExchange` are separate classes of the module: the exchange delegates them to the module class `TinyHttpdFeatures`, and each keeps the exchange's commit lock, abort check and single-start rule. From wave 6, `sendFile(status, headers, file, offset, length)` writes the status, the given headers and exactly `length` bytes of `file` starting at `offset` through a plain buffered copy (no zero-copy transfer) and throws no `UnsupportedOperationException`. A socket test hands over a file with a non-zero offset and a length shorter than the rest of the file and reads back exactly that byte range. The event-stream and upgrade paths still throw `UnsupportedOperationException` naming wave 7, which adds them. [D-210; D-404; D-106; D-291; wave decision: File bodies come from the opened stream]

Tests to re-point to the successor (all cite FR-ENGINE-001 AC-n today):

- `NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/AdapterSurfaceTest.java#sdsAc2_FR_ENGINE_001_AC2_featureHooksThrowNamingTheirWave`: must change. The `sendFile` branch now asserts a byte-range copy; the wave 7 assertions stay.
- `AdapterSurfaceTest.java#FR_ENGINE_001_AC2_featureHooksShareTheCommitLockAbortCheckAndSingleStart`: re-point only. It stays green: `sendFile` after `commit` still gives `IllegalStateException`, and after `abort` it gives `IOException`.
- `AdapterSurfaceTest.java#sdsAc1_FR_ENGINE_001_AC1_noEngineTypeInAnyPublicSignature` (AC-1) and `AdapterSurfaceTest.java#sdsAc3_FR_ENGINE_001_AC3_noClassReferencesTlsConfig` (AC-3): re-point only.
- `NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/RawTargetSocketTest.java#sdsAc4_FR_ENGINE_001_AC4_rawPathAndRawQueryReachTheCoreUndecoded` (AC-4), `RawTargetSocketTest.java#FR_ENGINE_001_AC5_requestLineHeadersAndRemoteAddressReachTheCoreUnchanged` (AC-5) and `RawTargetSocketTest.java#FR_ENGINE_001_AC2_exchangeOutlivesHandleAndGuardsStartAndAbort` (AC-2): re-point only.
- `NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/AbortSocketTest.java#FR_ENGINE_001_AC2_abortBeforeCommitGivesATruncatedResponse` and `#FR_ENGINE_001_AC2_abortDuringAConcurrentWriteOnlyFailsTheWriterWithIOException` (AC-2): re-point only.
- `@req FR-ENGINE-001` tags in main code to re-point: `TinyHttpdAdapter.java`, `TinyHttpdExchange.java`, `TinyHttpdFeatures.java` and `package-info.java` under `NaruServe-tinyhttpd/src/main/java/com/snoworca/naruserve/tinyhttpd/`.

### 1.2 OPS-HTTP-005 (scope HTTP)

- Title: Wave 3 decisions in docs/api with the later-wave markers kept (wave 5)
- File: `docs/spec/04.http-core.srs.md`; target wave-5; verified; evolving
- Changed: AC-8 only.
- Why: AC-8 requires the `file` disposition marker of `response.md` (line 30) and the `parts()` marker of `request.md` (line 85) to stay. Wave 6 settles both: D-293 settles `res.file` `Content-Disposition` and D-239 settles the element type of `parts()`. `HttpDocsTest#sdsAc8_OPS_HTTP_005_AC8_requestKeepsOtherIssuesMarkers` asserts exactly one marker line in `request.md`, starting `| \`parts()\``. `#sdsAc8_OPS_HTTP_005_AC8_responseKeepsFileAndRedirectMarkers` asserts exactly two marker lines in `response.md`, with the `file(File)` row first. Both would fail. The express-migration markers that AC-8 keeps (`express.Router()`, `res.redirect(url)`, `cors()`) are not wave 6 items and stay.
- Old AC-8 text (verbatim): "`HttpDocsTest`, re-pointed to this requirement, checks that the markers of later waves than wave 5 stay — `file` disposition and `redirect` in `response.md`, `parts()` in `request.md` and the markers of `express-migration.md` owned by issues of waves 6 to 8 — that `routing.md` keeps no marker, and that the proxy-trust and `jsonTree()`/`jsonMap()` markers of `request.md` are gone because wave 5 decides them (OPS-ENGINE-001 AC-4, OPS-JSON-001 AC-2). [wave decision: Remaining markers; D-137; D-192; D-225] re_stated_from: OPS-HTTP-003#AC8 (kept-marker set changed by wave 5)"

All old ACs, verbatim:

- AC-1: `docs/api/request.md` states that `rawPath()` returns the raw, undecoded path without the query string (available through `req.query(...)`), gives its Express counterpart as `req.path` before decoding (not `req.originalUrl`), and has no (미확정) marker on the `rawPath()` row. [D-109; wave decision: Remaining markers] re_stated_from: OPS-HTTP-003#AC1
- AC-2: `docs/api/request.md` states that `req.attribute(name, type)` throws `ClassCastException` for a stored value of another type, without a marker, and that a value is removed by storing a typed `null` variable because a literal `null` resolves to the read overload. [D-109; wave decision: Remaining markers; wave decision: Null attribute values] re_stated_from: OPS-HTTP-003#AC2
- AC-3: `docs/api/response.md` states that `send(byte[])` without a `Content-Type` uses `application/octet-stream` and that `stream(InputStream, contentType)` takes ownership and closes the stream on success, failure or abort, with an example that does not use try-with-resources, and the marker line on these two points is gone. [D-109; wave decision: stream() closing; wave decision: Remaining markers] re_stated_from: OPS-HTTP-003#AC3
- AC-4: `docs/api/routing.md` documents `app.routerPolicy(RouterPolicy)` with `RouterPolicy.builder()` (`mergeSlashes`, `ignoreTrailingSlash`, `caseInsensitive`, `enableTrace`, `enableConnect`) as allowed only while the app is in `NEW` (before `listen()`), like every setting in `app.md` (a call outside `NEW` is rejected with `IllegalStateException` by the state guard of FR-APP-002), has no sentence calling that name undecided, and states that `req.method()` returns `HEAD` during automatic `HEAD`; its marker set is checked by OPS-ROUTE-003 AC-3. [D-398; wave decision: Remaining markers; wave decision: req.method() during automatic HEAD; wave-3 residual: marker test of wave 2] re_stated_from: OPS-HTTP-003#AC4
- AC-5: `docs/api/app.md` lists `routerPolicy(RouterPolicy)` among the `RestApp` methods with a Java and a Kotlin example and adds no (미확정) marker for it. [D-398; wave decision: Remaining markers] re_stated_from: OPS-HTTP-003#AC5
- AC-6: `docs/api/defaults.md` names `app.routerPolicy(...)` with `enableTrace`/`enableConnect` as the explicit `TRACE`/`CONNECT` policy, and that row has no (미확정) marker. [D-398; wave decision: Remaining markers] re_stated_from: OPS-HTTP-003#AC6
- AC-7: `docs/api/express-migration.md` does not map `req.originalUrl` to `req.rawPath()`: the `rawPath()` row names `req.path` before decoding as its Express counterpart without a marker, the kept `req.originalUrl` row points to `req.rawPath()` plus the query read through `req.query(...)` without a marker, and the file states once, without contradiction, that v1 has no raw or text body reader, with no raw/text bullet in the undecided list (section 'Express에 있지만 아직 정해지지 않은 기능'). [D-109; wave decision: Remaining markers; wave decision: Body ownership] re_stated_from: OPS-HTTP-003#AC7
- AC-8: `HttpDocsTest`, re-pointed to this requirement, checks that the markers of later waves than wave 5 stay — `file` disposition and `redirect` in `response.md`, `parts()` in `request.md` and the markers of `express-migration.md` owned by issues of waves 6 to 8 — that `routing.md` keeps no marker, and that the proxy-trust and `jsonTree()`/`jsonMap()` markers of `request.md` are gone because wave 5 decides them (OPS-ENGINE-001 AC-4, OPS-JSON-001 AC-2). [wave decision: Remaining markers; D-137; D-192; D-225] re_stated_from: OPS-HTTP-003#AC8 (kept-marker set changed by wave 5)

Proposed successor ACs: AC-1 to AC-7 are carried over unchanged, each with `re_stated_from: OPS-HTTP-005#ACn`. AC-8 is restated:

- AC-8 (restated): `HttpDocsTest`, re-pointed to this requirement, checks these marker states. The markers that wave 6 does not settle stay: `redirect` in `response.md`, and in `express-migration.md` the `express.Router()`, `res.redirect(url)` and `cors()` rows. `request.md` keeps no marker: the `parts()` row names its decided element type and whether text fields are included. The `file(File)` row of `response.md` names its decided `Content-Disposition` behaviour without a marker, so `redirect` is the only marker left in `response.md`. `routing.md` keeps no marker. The proxy-trust and `jsonTree()`/`jsonMap()` markers of `request.md` stay gone (OPS-ENGINE-001 AC-4, OPS-JSON-001 AC-2). [wave decision: Remaining markers; D-137; D-192; D-225; D-239; D-293] re_stated_from: OPS-HTTP-005#AC8 (kept-marker set changed by wave 6)

Tests to re-point to the successor (all in `NaruServe-api/src/test/java/com/snoworca/naruserve/HttpDocsTest.java`; class javadoc line 17 names OPS-HTTP-005):

- `#OPS_HTTP_005_AC1_rawPathRowIsRawPathWithoutQuery`, `#OPS_HTTP_005_AC2_attributeTypeMismatchAndNullRemoval`, `#OPS_HTTP_005_AC3_octetStreamAndStreamClosing`, `#OPS_HTTP_005_AC4_routerPolicyDocumented`, `#OPS_HTTP_005_AC5_appListsRouterPolicy`, `#OPS_HTTP_005_AC6_defaultsNamesTraceConnectPolicy` and `#OPS_HTTP_005_AC7_expressRawPathAndRawTextMapping`: re-point only.
- `#sdsAc8_OPS_HTTP_005_AC8_requestKeepsOtherIssuesMarkers`: must change; expect 0 marker lines.
- `#sdsAc8_OPS_HTTP_005_AC8_responseKeepsFileAndRedirectMarkers`: must change; expect 1 marker line, the `res.redirect` one.
- `#sdsAc8_OPS_HTTP_005_AC8_expressKeepsOtherIssuesMarkers` and `#sdsAc8_OPS_HTTP_005_AC8_routingKeepsNoMarker`: re-point only.

### 1.3 OPS-APP-003 (scope APP)

- Title: Wave 4 decisions recorded in docs/api with the wave 5 marker set and keeper-thread wording
- File: `docs/spec/05.application.srs.md`; target wave-5; verified; evolving
- Changed: AC-6 only.
- Why: AC-6 requires the kept list to hold "the markers of waves 6 to 8 (for example `ws`, `staticFiles`, `multipart`, ...)". `AppDocsTest.KEPT` holds `static.md` → `"SPA fallback은"`, `` "`/docs/`로 301" `` and `multipart.md` → ``"| `tempDirectory`"``. Wave 6 settles exactly these markers (D-267, D-239, plus the wave decision "SPA fallback exclusions"). `#sdsAc7_OPS_APP_003_AC6_laterWaveMarkersAreKept` would then fail, so the entries move to the removed list. This is the same supersede wave 5 made (OPS-APP-001 → OPS-APP-003).
- Old AC-6 text (verbatim): "`AppDocsTest`, re-pointed to this requirement, keeps a removed-phrase list that fails when a listed phrase still carries `(미확정)` on its line and a kept-phrase list that fails when a listed phrase lost its marker; the kept list holds the markers of waves 6 to 8 (for example `ws`, `staticFiles`, `multipart`, the SSE send queue, the WebSocket idle, ping and handshake limits and the `ws` return-type and `ws` callback-name markers of `app.md`) and the `express.Router()` and `cors()` markers of `express-migration.md`, while every entry wave 5 decides moves to the removed list: the cookie markers, the LAN binding API line of `app.md`, the `express-migration.md` rows for trust proxy and for the 0.0.0.0 binding, and the `docs/api/defaults.md` rows for the bind address (바인딩 주소) and the proxy headers (프록시 헤더). [D-137; I-008; wave-5: OPS-ENGINE-001; wave-5: OPS-COOKIE-001] re_stated_from: OPS-APP-001#AC6 (kept-marker set changed by wave 5)"

All old ACs, verbatim:

- AC-1: `docs/api/app.md` and `docs/api/README.md` document `RestApp.builder()` with `httpAdapter`, `jsonCodec`, `logger` and `build()`, the `AppFactory` SPI and the missing-module exception of `RestApp.create()`, the route-middleware overload return type, `LifecycleCallback` and `ErrorHandler` with `throws Exception`, `ServerHandle#port()`, `address()` and `state()`, and that one non-daemon keeper thread of the TinyHttpd adapter keeps the JVM alive after `main` returns until `shutdown` while TinyHttpd's own threads stay daemon, and no longer mark these as (미확정). [D-137; D-167; wave decision: RestApp.create(); wave decision: JVM lifetime; wave-5 wave decision: Thread wording] re_stated_from: OPS-APP-001#AC1 (JVM-lifetime wording changed by wave 5)
- AC-2: `docs/api/middleware.md` and `docs/api/routing.md` document `RouteBuilder.use(middleware)` as the way to attach several route middleware, the warning-and-`end()` finishing of D-126, `ErrorHandler`, `HttpException#status()`/`code()` and `UnsupportedMediaTypeException`, the `onError` chain rule, the `code` table of FR-APP-006, that `404`/`405` bodies are not configurable in v1, the row `STOPPING` → `503` with the standard body, and the full-queue `503` as a TinyHttpd transport-level plain-text answer, and drop the matching markers. [D-137; D-134; wave decision: Overload 503] re_stated_from: OPS-APP-001#AC2
- AC-3: `docs/api/lifecycle.md` documents `registerExternal`, `AppStartException`, the start gate (a request arriving in `STARTING` waits until `RUNNING` and gets `503` with `Connection: close` if the start fails) and that a `shutdown` during `STARTING` whose timeout passes returns normally while the pending stop stays in force, and drops the matching markers. [D-151; D-438; D-159; wave decision: STARTING] re_stated_from: OPS-APP-001#AC3
- AC-4: `docs/api/logging.md` documents the access log line format, the option defaults, the byte fields as body bytes actually read and written, the Logcat tag `NaruServe`, the console logger format, the file logger defaults and file names, that Android apps pass a writable directory such as `context.getFilesDir()`, that shutdown flushes and closes the app logger, and that a logger shared by several apps needs a delegate whose `close()` does nothing, and drops its marker; the documented line form is `<method> <path> <status>[ aborted][ <n>ms][ in=<i> out=<o>][ rid=<id>]`, and the file logger defaults are `./logs`, 5 MiB per file and 5 files. [D-181; D-177; wave decision: Logger shutdown; wave decision: File logger directory] re_stated_from: OPS-APP-001#AC4
- AC-5: `docs/api/express-migration.md` documents route middleware through `RouteBuilder.use`, that `req.path()` keeps the full path under `app.use("/api", mw)`, the `500` `code` `INTERNAL_ERROR` and the absence of custom `404` bodies, and drops those markers. [D-137] re_stated_from: OPS-APP-001#AC5
- AC-6: `AppDocsTest`, re-pointed to this requirement, keeps a removed-phrase list that fails when a listed phrase still carries `(미확정)` on its line and a kept-phrase list that fails when a listed phrase lost its marker; the kept list holds the markers of waves 6 to 8 (for example `ws`, `staticFiles`, `multipart`, the SSE send queue, the WebSocket idle, ping and handshake limits and the `ws` return-type and `ws` callback-name markers of `app.md`) and the `express.Router()` and `cors()` markers of `express-migration.md`, while every entry wave 5 decides moves to the removed list: the cookie markers, the LAN binding API line of `app.md`, the `express-migration.md` rows for trust proxy and for the 0.0.0.0 binding, and the `docs/api/defaults.md` rows for the bind address (바인딩 주소) and the proxy headers (프록시 헤더). [D-137; I-008; wave-5: OPS-ENGINE-001; wave-5: OPS-COOKIE-001] re_stated_from: OPS-APP-001#AC6 (kept-marker set changed by wave 5)
- AC-7: Next to each wave 4 decision of AC-1 to AC-5, `docs/api` states its reason in one sentence (for example why `RouteBuilder.use` instead of a varargs overload), so the wave 4 commit can quote each decision with its reason. [I-013; D-137] re_stated_from: OPS-APP-001#AC7

Proposed successor ACs: AC-1 to AC-5 and AC-7 are carried over unchanged, each with `re_stated_from: OPS-APP-003#ACn`. AC-6 is restated:

- AC-6 (restated): `AppDocsTest`, re-pointed to this requirement, keeps two lists. A removed-phrase list fails when a listed phrase still carries `(미확정)` on its line, and a kept-phrase list fails when a listed phrase lost its marker.
  - The kept list holds the markers of waves 7 and 8: the SSE heartbeat and send-queue markers, the WebSocket idle, ping and handshake limits, the `101` timing, and the `ws` return-type and `ws` callback-name markers of `app.md`. It also holds the `express.Router()`, `cors()` and `res.redirect(url)` markers of `express-migration.md`.
  - The removed list keeps every entry that waves 4 and 5 decided: the cookie markers, the LAN binding API line of `app.md`, the `express-migration.md` rows for trust proxy and for the 0.0.0.0 binding, and the `docs/api/defaults.md` rows for the bind address (바인딩 주소) and the proxy headers (프록시 헤더).
  - The removed list also gains every entry that wave 6 decides:
    - in `static.md`: the SPA fallback exclusion line (`SPA fallback은`), the trailing-slash redirect line (`` `/docs/`로 301 ``), the cache pattern syntax row (`` `cache(pattern, policy)` ``) and the symbolic-link allow-setting line;
    - in `multipart.md`: the `` `tempDirectory` `` row and the unlimited-limit sentence;
    - in `defaults.md`: the symbolic-link row (`| 심볼릭 링크`).

  [D-137; I-008; D-239; D-267; D-272; D-282; wave decision: SPA fallback exclusions; wave-5: OPS-ENGINE-001; wave-5: OPS-COOKIE-001] re_stated_from: OPS-APP-003#AC6 (kept-marker set changed by wave 6)

Tests to re-point to the successor (all in `NaruServe-api/src/test/java/com/snoworca/naruserve/AppDocsTest.java`; class javadoc line 20 names OPS-APP-003):

- `#OPS_APP_003_AC1_builderFactoryServerHandleAndKeeperThread`, `#OPS_APP_003_AC2_middlewareAndRoutingDecisions`, `#OPS_APP_003_AC3_lifecycleStartGateAndPendingStop`, `#OPS_APP_003_AC4_loggingFormatDefaultsAndClose`, `#OPS_APP_003_AC5_expressMigrationRows` and `#OPS_APP_003_AC7_decisionsCarryAReason`: re-point only.
- `#sdsAc7_OPS_APP_003_AC6_decidedMarkersAreGone`: the REMOVED map gains the wave 6 entries.
- `#sdsAc7_OPS_APP_003_AC6_laterWaveMarkersAreKept`: the KEPT map loses `static.md` and `multipart.md`.
- `#sdsAc7_OPS_APP_003_AC6_wave4PagesKeepNoMarker` and `#sdsAc7_OPS_APP_003_AC6_wsMarkersSitOnTheirOwnLines` (cites AC-6): re-point only.

## 2. Examined and NOT superseded

- IR-HTTP-001: AC-1 to AC-4 describe the wave 3 surface ("At the wave 3 merge"). By the established convention, a wave 6 surface AC re-points `HttpApiSurfaceTest`; see section 4. Following IR-JSON-002 AC-8 and FR-COOKIE-002 AC-8, that AC should say: the Request/Response sets gain `form`, `file`, `files`, `parts`, `file`, `download` and `cache`; those names leave `LATER_WAVE_NAMES` (only `sse` stays); the nullable set gains the returns of `form(String)` and `file(String)` and nothing else.
- IR-HTTP-005: AC-5 is about the wave 3 merge. IR-APP-002 AC-2 and IR-ENGINE-001 AC-7 already re-pointed it. A wave 6 AC re-points `#sdsAc6_IR_HTTP_005_AC1_AC2_AC3_AC5_IR_APP_002_AC2_restAppDeclaresExactlyTheWave4Methods` to the wave 6 set (adding `multipart`, `staticFiles` and its overload, `mimeTypes`, the form-limit method and the default-cache method). AC-4 (no `serve`) stays true.
- IR-ENGINE-001: AC-7 names "the wave 5 set". It is a wave delta statement, like IR-APP-002 AC-2, which wave 5 re-pointed without superseding. AC-2/AC-3 cover `ServerOptions` only and stay true.
- IR-JSON-002: AC-8 is the wave 5 re-point delta. Its own Decision says new methods add positions without a supersede. `BodyStateTest#sdsAc11_FR_HTTP_019_AC5_requestHasNoPublicBodyReadingMethod` (its `bodyNames` list contains form/file/files/parts) is re-pointed by a wave 6 AC that allows exactly `form`, `file`, `files` and `parts` as additional readers. It still finds no InputStream, `byte[]`, raw or text reader on `Request`.
- FR-COOKIE-002: AC-8 is a wave 5 delta ("gains exactly the return of `cookie(String)`") and stays true as history.
- FR-HTTP-019: AC-3/AC-4 (exclusive kinds; a shared form/multipart parse cached) are what wave 6 uses, so they stay true. AC-5 is "at the wave 3 merge" (convention; re-pointed via IR-JSON-002 AC-8, and now via a wave 6 AC).
- FR-HTTP-020: the limit stream and `BodyTooLargeException` are unchanged; D-234 reuses them.
- FR-HTTP-025: query decoding is unchanged. A urlencoded body is a new reader (D-238, D-240) and leaves the query ACs true.
- FR-HTTP-021: pre-request order is unchanged. The Residual confirms that D-052/D-268 `400` runs before method handling.
- FR-HTTP-022: every AC scenario registers routes only, with no static mount, and the wave decisions keep them: "if no static mount serves it, the route's 405 with the route's own Allow answers", "the automatic 204 of D-395 applies to explicit routes only", "no Allow values merged". `DispatcherTest` and the fallback tests stay unchanged.
- FR-HTTP-023, FR-HTTP-024: unchanged.
- FR-HTTP-004, FR-HTTP-014, FR-HTTP-016, FR-HTTP-017: unchanged. Static HEAD/304/stream reuse them.
- IR-HTTP-003, IR-HTTP-004: the SPI stays as frozen (wave decision, I-014). IR-HTTP-004 AC-4 ("waves 6, 7 and 8 add adapter classes ... without changing the SPI") holds.
- FR-HTTP-001, FR-HTTP-008, CON-HTTP-001: unchanged.
- OPS-HTTP-004: AC-4/AC-6 are rules (a stale skip fails). Wave 6 removes the `staticFiles`/`form`/`multipart`/`mimeTypes`/`cache`/`file` skip entries of `DocsExamplesCompileTest` as those rules require. "UploadFile" appears only as an example reason.
- FR-ROUTE-* and IR-ROUTE-*: the router is unchanged. Static mounts are consulted by the dispatcher after the lookup. FR-ROUTE-010/012 Allow sets stay as they are.
- OPS-ROUTE-003: `routing.md` must stay marker-free. Wave 6 records the static fall-through in `static.md`, so the AC holds.
- FR-APP-002: AC-3/AC-4 hold if the new configuration methods use the guard. `AppLifecycleGuardTest#sdsAc4_FR_APP_002_AC4_...` needs new `argFor` cases (`File`, `Configurer` for multipart/static options), which is a test-helper addition with an unchanged AC. AC-2 (no mutable static) constrains the built-in MIME table.
- FR-APP-003: AC-5 ("matches no route ... receives the 404 of the fallback point") is asserted without a static mount and stays true. D-407 (middleware on static) builds on it.
- FR-APP-005, FR-APP-006: unchanged. `413`/`400`/`416` use the standard body (`416` → `HTTP_416` by AC-2). AC-9 (no API replaces the 404/405 bodies) holds, because SPA fallback is not a body-replacement API.
- FR-APP-011: wave 6 adds an internal after-`onStop` participant through the existing registry. AC-1/AC-3 tests register their own participants, so they stay unchanged. `ShutdownParticipantsTest` and `ShutdownSequenceTest` stay unchanged.
- FR-APP-009: AC-8 needs the wave 3 `Dispatcher(Router)` constructor kept (it may gain an overload).
- FR-APP-015, SEC-APP-001: unchanged (one line per static request).
- OPS-APP-002: AC-2 is a rule with examples ("for example ... `staticFiles`"). It stays true.
- FR-ENGINE-003: AC-7 (the adapter never sets `maxRequestBodyBytes`). TinyHttpd's default is `-1` (no limit) and the core enforces the multipart limits, so a 64 MiB upload needs no adapter change. The risk in `code_context.json` is answered.
- FR-ENGINE-005: the body-stream mapping (`413`/`408`/`400`) is reused for uploads and mid-upload disconnects.
- OPS-ENGINE-001: AC-2 ("no setting accepts an unlimited value") stays true only if D-239 decides that unlimited multipart limits are NOT allowed. This is the natural choice under I-012 and the existing `defaults.md` line 3. If wave 6 allowed unlimited values, AC-2 would need a supersede.
- OPS-JSON-001, OPS-COOKIE-001, IR-COOKIE-001, FR-COOKIE-001, FR-JSON-001..003, IR-JSON-001: unaffected. FR-JSON-001 AC-1 (urlencoded → `415` for `req.body`) still holds.
- `WaveFiveDocsTest` (OPS-ENGINE-001 AC-7, lines 418-428): its kept markers (WebSocket message, SSE queue, sse heartbeat, Router, cors) are not wave 6 items, so it stays unchanged. Watch that a new `defaults.md` line containing `무제한` does not displace `lineWith(d, "무제한")` (first-match helper).
- IR-APP-001, IR-APP-003..007, FR-APP-001, FR-APP-004, FR-APP-007, FR-APP-008, FR-APP-010, FR-APP-012..014, FR-APP-016..018, CON-APP-001 and all wave 1 BUILD requirements: unaffected. No new module or dependency.
- Conditional on D-410: if the default server-wide `Cache-Control` is "no header unless configured" (Express default for non-static routes), no existing AC changes. A non-empty default would add a header to every response, so any test that asserts an exact header list would need re-checking. No verified AC asserts an exclusive header set.

## 3. Builds-on (dependency links), all verified, Stability=evolving (checked in the metadata tables)

| ID | Target | Reason |
| --- | --- | --- |
| FR-HTTP-019 | wave-3 | BodyState exclusive kinds and the shared, cached form/multipart parse behind form/file/files/parts |
| FR-HTTP-020 | wave-3 | limit-enforcing body stream and `BodyTooLargeException` for accumulated/chunked limits (D-234, D-238) |
| FR-HTTP-025 | wave-4 | form-urlencoded decoding rules reused for urlencoded bodies |
| FR-HTTP-021 | wave-4 | path validation `400` before lookup and method handling (D-268, Residual) |
| FR-HTTP-022 | wave-4 | fallback point that static mounts are consulted before (404/405/Allow, OPTIONS 204 for routes only) |
| FR-HTTP-014 | wave-3 | bounded-chunk stream/commit path for file bodies (D-264) |
| FR-HTTP-016 | wave-3 | single-commit response state machine for file/download/cache |
| FR-HTTP-017 | wave-3 | bodyless 304 and HEAD (D-284..D-286) |
| IR-HTTP-003 | wave-3 | commit contentLength contract for 206/304/HEAD |
| IR-HTTP-004 | wave-3 | file-transfer extension point kept unchanged (D-106, I-014) |
| IR-HTTP-001 | wave-3 | Request/Response surface extended by form/file/files/parts/file/download/cache |
| IR-HTTP-005 | wave-3 | RestApp surface extended by multipart/staticFiles/mimeTypes/form limit/default cache |
| CON-HTTP-001 | wave-3 | engine-free core placement of the parser, ResourceResolver, Range/ETag code (D-247, D-291, D-409) |
| FR-ROUTE-004 | wave-2 | PathValidator D-052 rejections that static paths reuse |
| FR-ROUTE-010 | wave-2 | the route's own Allow set kept on method mismatch (no merge) |
| FR-APP-002 | wave-4 | NEW-state guard for the new configuration methods; no mutable statics |
| FR-APP-003 | wave-4 | global middleware on static responses (D-407) |
| FR-APP-005 | wave-4 | unified error path for 400/413/416/500 from accessors |
| FR-APP-006 | wave-4 | standard error body and code values |
| FR-APP-011 | wave-4 | shutdown participant registry, after-onStop phase for temp cleanup (D-255, D-401) |
| FR-APP-015 | wave-4 | one access-log line per static request |
| IR-APP-007 | wave-4 | `Configurer` functional interface for `app.multipart(config -> ...)` and static options |
| IR-JSON-002 | wave-5 | size-limit API pattern (`jsonLimit`, 1 MiB default, bounded max) for the urlencoded form limit |
| FR-JSON-001 | wave-5 | urlencoded/multipart bodies are not JSON (415), exclusive body kinds |
| FR-ENGINE-001 | wave-5 | `TinyHttpdFeatures.sendFile` hook filled as a buffered copy (superseded here; link the successor) |
| FR-ENGINE-003 | wave-5 | no TinyHttpd body limit, so the core enforces multipart limits |
| FR-ENGINE-005 | wave-5 | adapter body-stream error mapping during uploads |
| OPS-HTTP-004 | wave-4 | docs compile test whose skip entries wave 6 removes |
| CON-BUILD-012 | wave-1 | API 21 animal-sniffer check (no `java.nio.file`, D-253, D-270) |
| CON-BUILD-013 | wave-1 | core isolation check (no TinyHttpd `MultipartReader` in api, D-247) |

## 4. Other affected tests (no supersede; re-point from a wave 6 AC)

- `NaruServe-api/src/test/java/com/snoworca/naruserve/HttpApiSurfaceTest.java`:
  - `LATER_WAVE_NAMES` keeps only `sse`.
  - The `#sdsAc1_IR_HTTP_001_AC1_requestDeclaresTheWave3Methods`/`#sdsAc2_...responseStatusAndHeader...` exact sets gain the wave 6 methods.
  - `#sdsAc3_IR_HTTP_001_AC4_packageNullMarkedAndNullableOnlyOnListedPositions` gains the `form(String)` and `file(String)` returns.
  - `#sdsAc6_IR_HTTP_005_AC1_AC2_AC3_AC5_IR_APP_002_AC2_restAppDeclaresExactlyTheWave4Methods` gains the wave 6 RestApp methods. RestApp keeps no `@Nullable` position, and `builder`/`create` stay the only statics.
- `NaruServe-api/src/test/java/com/snoworca/naruserve/internal/BodyStateTest.java#sdsAc11_FR_HTTP_019_AC5_requestHasNoPublicBodyReadingMethod`: `bodyNames` drops `form`, `file`, `files` and `parts`.
- `NaruServe-api/src/test/java/com/snoworca/naruserve/internal/AppLifecycleGuardTest.java#sdsAc4_FR_APP_002_AC4_...`: `argFor` gains the wave 6 parameter types.
- `src/test/java/com/snoworca/naruserve/docs/DocsExamplesCompileTest.java`: the skip entries at lines 76, 83, 105, 106, 119 and 129-133 (`staticFiles`, `form`, `multipart`, `res.file`, `mimeTypes`, `cache`) are removed or re-pointed, as OPS-HTTP-004 AC-6 requires.
- Kotlin surface tests: `NaruServe-api/src/test/kotlin/com/snoworca/naruserve/HttpApiKotlinTest.kt` is not affected, but the new methods need Kotlin compile/run coverage (T11).
