# Duplication audit — wave-18 (stage 12)

Input: `9f6bc57..94a3c1f` (one lane, wave-18, including the host-dispatched FND-001 fix). New production code in `NaruServe-proxy`: the watchdog `ProxyExchange.Scheduler` and deadline settling (FR-PROXY-017), failure mapping by kind before the response (FR-PROXY-015), `PendingResponseHeaders` (headers computed before the final deadline check).

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| `ProxyExchange.Scheduler` (JVM-wide watchdog) vs `TlsConnector`'s own `naruserve-proxy-tls-watchdog` executor (wave-14) | wave-18 vs wave-14 | `ProxyExchange.java`, `TlsConnector.java` | merge candidate, deferred: two watchdog executors; the TLS one keeps a non-timing-out thread and the creator's context class loader, which REL-PROXY-002 AC-2..AC-4 (wave-19) govern. Carried to wave-19 in `carry-forward.json`; not merged here to stay inside the wave's write set. |
| `PendingResponseHeaders` vs direct `res` writes in `ResponseHeaderCopier` / `LocationRewriter` | wave-18 vs wave-10/15 | `PendingResponseHeaders.java`, `ResponseHeaderCopier.java`, `LocationRewriter.java` | merged: both writers gained an overload that writes into `PendingResponseHeaders`; the `Response` overloads delegate to it, so there is one header computation path |

One merge candidate left for wave-19.
