# Wave-19 issues (Phase 3.m)

Residual union:
- loop P round 1: 12 tests, 0 kept (pass; one IPv6 probe skipped because TinyHttpd binds IPv4 only, recorded as not decidable). FR-PROXY-003 AC-8 holds over real TinyHttpd sockets: Host, client headers, real X-Forwarded-For/-Proto/-Host, `.requestHeader` values in call order, framing headers, `Connection: close`. Client `X-Forwarded-Host`, `Forwarded`, `X-Real-IP`, `X-Forwarded-Port`, `X-Forwarded-Prefix` in any case or duplicated never reach the backend. No scheduler entries left after any response shape, TLS failure or client abort; one daemon watchdog thread with a null context class loader.
- carried items closed: wave-12 LP-9 (FR-PROXY-003 AC-8, promoted in this stage), wave-13 LP-10 (client forwarding headers dropped), wave-18 TLS watchdog executor (moved onto `ProxyExchange.Scheduler`).
- worker review: 0 blocking; FND-001 (WatchdogTest AC-4/AC-6 read the JVM-wide scheduler state and could fail if another test leaves a reservation) kept as a declared limit; CC-001 fixed.
- srs_deltas: none. out_of_lease_paths accepted: `TlsConnector.java` (carried watchdog item), `ProxyHostHeaderTest.java` (exact-match expectations gain the new X-Forwarded-For/-Proto lines).

No issue rows. Ledger `[]`.
