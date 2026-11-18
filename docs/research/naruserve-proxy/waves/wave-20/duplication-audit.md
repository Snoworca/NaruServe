# Duplication audit — wave-20 (stage 14)

Input: `c900c9d..fa3e441` (one lane, wave-20) plus the host test commit 24ae849. Production changes in `NaruServe-proxy`: `RetryPolicy` (FR-PROXY-018 retry decision), `Proxy` retry loop and attempt lifetime, `BackendClient` per-attempt connect.

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| `RetryPolicy` failure classification vs `Proxy.backendFailure` kind mapping (wave-18) | wave-20 vs wave-18 | `RetryPolicy.java`, `Proxy.java` | keep: `backendFailure` maps an exception to a `ProxyException` kind; `RetryPolicy` decides retryability from that kind and the response status, so the classification lives once in `backendFailure` |
| retry wait vs the watchdog deadline (`ProxyExchange`) | wave-20 vs wave-18 | `Proxy.java`, `ProxyExchange.java` | merged: the wait reads the same total deadline and is bounded by it (FR-PROXY-018 AC-10) |

No merge candidates.
