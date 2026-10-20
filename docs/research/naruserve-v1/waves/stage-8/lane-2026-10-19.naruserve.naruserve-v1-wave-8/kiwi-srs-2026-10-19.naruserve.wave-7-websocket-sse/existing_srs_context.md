# Wave 7 existing-SRS analysis (supersede decisions)

Rule: supersede only where wave 7 changes what a verified AC asserts; otherwise re-point tests.

## Superseded (supersede_requirement, successor in target wave-7)

| Old | Successor | Why |
|---|---|---|
| FR-ENGINE-010 | FR-ENGINE-011 | Statement and AC-2: event-stream and upgrade paths "still throw UnsupportedOperationException naming wave 7"; wave 7 fills them, and the package-private feature hooks change to receive the exchange (W2-003). AC-1, AC-3, AC-4, AC-5 unchanged. |
| IR-HTTP-004 | IR-HTTP-007 | AC-4 "without changing the SPI"; wave 7 adds the add-only types WebSocketEndpoint, WebSocketChannel, WebSocketSettings and the default method HttpExchange.webSocket (W2-001, D-106 read as "without breaking it"). AC-1..AC-3, AC-5, AC-6 unchanged. |
| FR-APP-015 | FR-APP-019 | "exactly one access log line per HTTP request ... when the response completes": an accepted upgrade gets its HTTP line at onOpen (D-430) plus a separate WebSocket close line (D-325); an SSE stream's line is written at stream end and carries start time and closing cause (D-338). AC-1..AC-7 restated unchanged for plain HTTP. |
| OPS-HTTP-006 | OPS-HTTP-007 | Statement "keep the markers owned by issues of later waves than wave 6": wave 7 removes the defaults.md WebSocket/SSE markers. AC-1..AC-8 restated; kept-marker set becomes "later than wave 7". |
| OPS-APP-004 | OPS-APP-005 | Statement "keep the remaining markers of waves 7 and 8"; AC-6 kept list holds the SSE heartbeat/send-queue, WebSocket idle/ping/handshake, 101 timing and app.md ws return-type and callback-name markers, which wave 7 removes; app.md shutdown steps change wording for D-446. |
| OPS-ENGINE-001 | OPS-ENGINE-002 | AC-7 "idle timeout, ping interval and handshake timeout keep their wave 7 marker" (WaveFiveDocsTest kept("websocket.md","Ping 주기"), kept defaults.md WebSocket/SSE rows, kept sse.md heartbeat). |

## Not superseded (AC stays literally true; tests re-pointed where they break)

- FR-HTTP-022 AC-6 and FR-HTTP-008 AC-7: worded "until wave 7 adds app.ws" / "wave 3 dispatches no handshake", true as history. Tests that assert the 426 for an upgrade request to a hook WebSocket route are re-pointed to FR-WS-001.
- IR-HTTP-001 AC-3 and IR-HTTP-005 AC-5: "at the wave 3 merge" surface statements (re-point convention of waves 5 and 6); HttpApiSurfaceTest re-pointed to IR-SSE-001 (Response.sse) and IR-WS-001 (RestApp.ws, webSocket, sse).
- IR-APP-003: HttpException gains add-only methods; no AC asserts the method set. New IR-HTTP-008.
- FR-APP-005 AC-4 (error response drops headers the app set) holds; the exception's own headers are new (IR-HTTP-008).
- FR-APP-006: 403 uses HTTP_403 by AC-2's fallback rule; no change.
- FR-APP-011: before-wait phase and order unchanged; ShutdownParticipants gains the deadline as an add-only overload (package-private).
- FR-APP-013: upgrade and SSE requests after STOPPING get the gate's 503 (D-301 satisfied by it).
- FR-ENGINE-004 AC-1: maxEventStreams kept as inert bound (W2-009); "effective limit 128" now held by the NaruServe counter.
- FR-ENGINE-005 AC-3: already "outside a NaruServe dispatch" (D-405 narrowed).
- FR-ENGINE-006: stop/release/keeper unchanged; new threads must also end (FR-APP-020).
- FR-HTTP-011 AC-3: req.lastEventId already exists (D-337 reuses it).
- OPS-JSON-001, OPS-COOKIE-001, OPS-MULTIPART-001, OPS-STATIC-001, OPS-HTTP-004, OPS-ROUTE-003: no wave 7 marker asserted. Watch: WaveFiveDocsTest first-match lineWith(d, "무제한") must keep finding the same line.

## Tests to re-point (from the superseded requirements)

- AdapterSurfaceTest (FR_ENGINE_001/010 methods, featureHooksThrowNamingTheirWave) -> FR-ENGINE-011
- RawTargetSocketTest, AbortSocketTest FR-ENGINE-010 methods -> FR-ENGINE-011
- HttpAdapterSpiTest IR-HTTP-004 methods -> IR-HTTP-007
- AccessLog tests citing FR-APP-015 (AccessLogTest, StoppingGateTest access-log asserts, pre-request rejection log tests) -> FR-APP-019
- HttpDocsTest OPS_HTTP_006 methods -> OPS-HTTP-007
- AppDocsTest OPS_APP_004 methods (kept list) -> OPS-APP-005
- WaveFiveDocsTest settledMarkerLinesAreGoneAndLaterMarkersKept and other OPS-ENGINE-001 methods -> OPS-ENGINE-002
- Dispatcher/HttpAcceptance tests asserting 426 for an upgrade to a hook WebSocket route (FR-HTTP-022 AC-6) -> FR-WS-001
- HttpApiSurfaceTest (LATER_WAVE_NAMES containing sse, RestApp method set) -> IR-SSE-001, IR-WS-001

## Dependency links allowed

depends_on only to wave 7 requirements or verified ones; extends to verified wave 1-6 (FR-APP-011, FR-APP-013, FR-APP-005, FR-APP-006, IR-APP-007, IR-ENGINE-001, FR-ENGINE-004, FR-ENGINE-006, FR-ROUTE-015, FR-ROUTE-016, FR-HTTP-011, FR-HTTP-022, IR-APP-003, IR-APP-004, IR-JSON-002, FR-HTTP-016, FR-HTTP-015). Never to CON-*-draft, CON-ROUTE-001, OPS-ROUTE-001 or discarded ids.
