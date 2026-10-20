# Wave 7 TinyHttpd gaps (D-020)

TinyHttpd (/Users/beom/work/git/TinyHttpd) is read-only for this run. Wave 7 meets the frozen design by other means in each case below and records the gap here; none is patched in TinyHttpd. The issue ledger's classification vocabulary has no entry for a gap deferred on an external dependency, so the gaps live in this document, in the requirement text and in `docs/api/`, and the run report lists them.

| gap | measured at | effect | where recorded | requirement status |
|---|---|---|---|---|
| `ExchangeResponse` replaces the `Connection` header on every non-`101` response, so `Connection: Upgrade` of the `426` answer never reaches the client over TinyHttpd | TinyHttpd 46b973e, `ExchangeResponse.java:172-177` and `:466-470` | D-408 is asserted for both headers through the in-memory adapter and for `426` plus `Upgrade: websocket` over TinyHttpd | FR-WS-004 AC-2, AC-3 and its D-020 note; `docs/api/websocket.md` | FR-WS-004 stays `implemented` and is not promoted to `verified` on the TinyHttpd test alone (D-408) |
| `WebSocketListener` has no Pong callback | TinyHttpd 46b973e, websocket module | a separate pong timeout cannot be measured; the pong timeout equals the ping interval (D-424) | FR-WS-008 AC-3; `docs/api/websocket.md`, `docs/api/defaults.md` | the AC states the limitation, so FR-WS-008 is promoted |
| no upgrade-specific (handshake) deadline | TinyHttpd 46b973e | the handshake is bounded only by `headerTimeout` and `bodyReadTimeout` (D-323, r5 W2-002) | FR-WS-008 AC-7; `docs/api/websocket.md`, `docs/api/defaults.md` | the AC states the limitation, so FR-WS-008 is promoted |
| the closing read timeout restarts on every inbound frame | TinyHttpd 46b973e, `WebSocket.java` | a peer that never answers the close frame and keeps sending is ended only by its own close, the shutdown abort or `writeTimeout` | FR-WS-008 rationale note | FR-WS-008 is promoted |
