# Duplication audit — wave-19 (stage 13)

Input: `f4ad767..38085bb` (one lane, wave-19). Production changes in `NaruServe-proxy`: `BackendRequest` forwarding headers (SEC-PROXY-002 X-Forwarded-For/-Proto/-Host and dropping client forwarding headers) and fixed request headers (FR-PROXY-005), `ProxyExchange` watchdog properties (REL-PROXY-002), `TlsConnector` handshake watchdog moved onto `ProxyExchange.Scheduler`, `Proxy` wiring.

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| `TlsConnector`'s own watchdog executor vs `ProxyExchange.Scheduler` (open since the wave-18 audit) | wave-19 vs wave-14/18 | `TlsConnector.java`, `ProxyExchange.java` | merged: the TLS handshake watchdog now runs on `ProxyExchange.Scheduler.INSTANCE`; one watchdog executor remains |
| X-Forwarded-* building in `BackendRequest` vs the core's trusted-proxy parsing (`FR-HTTP-034`, `internal.ProxyTrustSetting`) | wave-19 vs core | `BackendRequest.java`, `NaruServe-api` | keep: the core parses incoming forwarding headers for `req.ip()`/`isFromTrustedProxy()`; the proxy writes outgoing ones and reads trust only through the public `isFromTrustedProxy()` (CON-BUILD-020 forbids `internal`) |

No merge candidates left.
