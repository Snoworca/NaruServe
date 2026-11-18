# Duplication audit — wave-16 (stage 10)

Input: `1f4974b..917681b` (one lane, wave-16). New production code in `NaruServe-proxy`: `ConcurrencyLimiter` (FR-PROXY-019 slot counter), `ProxyExchange` (REL-PROXY-001 one-time release of slot, watchdog and backend socket), `RelayedBodyStream` (release on relayed body close), `Proxy` check order and lifetime wiring (FR-PROXY-010).

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| `ConcurrencyLimiter` vs core limiters | wave-16 vs core | `NaruServe-api` | no candidate: the core has no semaphore or slot limiter in its main sources |
| `ProxyExchange` release-once vs `Proxy.forward` finally blocks (wave-10..15) | wave-16 vs earlier proxy waves | `Proxy.java`, `ProxyExchange.java` | already merged: `forward` now releases through `ProxyExchange` instead of its own `closeQuietly` paths |

No merge candidates.
