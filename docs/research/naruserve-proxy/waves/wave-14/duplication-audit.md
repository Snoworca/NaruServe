# Duplication audit — wave-14 and wave-15 (stage 9)

Input: `a177fc1..7602bee` (two lanes, merged in wave order: wave-14 at 196378f, wave-15 at 7602bee). New production code in `NaruServe-proxy`:
- wave-14: `BackendClient` (plain or TLS backend socket), `TlsConnector` (SNI, endpoint identification by reflection, HostnameVerifier fallback, handshake timeouts), `ProxyException` (public kinds), `Proxy.forward` wiring.
- wave-15: `LocationRewriter` (FR-PROXY-013), fixed response headers in `ResponseHeaderCopier` (FR-PROXY-012), method and upgrade handling in `Proxy` (FR-PROXY-009).

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| `LocationRewriter` origin parsing vs `TargetUrl.parse` | wave-15 vs wave-13 | `LocationRewriter.java:93`, `TargetUrl.java` | already merged: the rewriter parses the Location origin through `TargetUrl.parse`, so the host and port grammar is shared |
| `BackendClient.open` vs the former inline `socket.connect` in `Proxy.forward` | wave-14 vs wave-10 | `BackendClient.java`, `Proxy.java` | already merged: `Proxy.forward` now calls `BackendClient.open` for both schemes; no second connect path remains |
| `TlsConnector` vs TLS code in the core | wave-14 vs core | `NaruServe-api` | no candidate: the core has no client-side TLS code (server TLS belongs to the adapters) |

No merge candidates left. Wave-15 shares this file (one stage).
