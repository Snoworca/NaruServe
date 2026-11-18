# Wave-14 issues (Phase 3.m)

Residual union:
- loop P round 1: 10 tests, 1 kept — LP-2 (SEC-PROXY-005 AC-8): a delegating `SSLSocket` from `.sslSocketFactory` silently dropped the reflective endpoint identification and host name verification was off. Fixed tests-first by an independent fixer (f71835b test, 32cda82 read-back fix).
- loop P round 2: 5 kept — LP-1/LP-3/LP-4 (chain-only `X509ExtendedTrustManager`, eager-handshake factory, parameter-caching delegate each bypassed the JDK identification), LP-2 (the r1 fix fell back to the JDK default `HostnameVerifier`, which rejects every host), LP-5 (PRD-148: a reference to `utils.auth.internal` passed the class-reference check). SRS first (0c12468): SEC-PROXY-005 statement, AC-6, AC-9 rewritten and AC-10 added (the proxy checks the peer leaf certificate host name itself after every handshake: SAN dNSName with leftmost whole-label wildcard, iPAddress for IP literals, no CN fallback); CON-BUILD-020 AC-4 widened to every internal/spi package segment. Fixed tests-first (ac196a4, ee74726 tests; 98c25ce `CertificateHostnames`, 4d6e517 build check). Four existing TLS tests encoded the old Android-only AC-6/AC-9 wording and were renamed or replaced by the new AC tests. Full build 4476 tests, 0 failures.
- loop P round 3: 12 tests, 0 kept (pass). Note for the run report: an IPv4-mapped IPv6 target `[::ffff:127.0.0.1]` does not match an iPAddress SAN `127.0.0.1` (fails closed; the contract does not name the case).
- worker: implemented three of the four requirements directly instead of through kiwi-pm, and reviewed with two read-only reviewers instead of kiwi-review-fix-loop (one major fixed: Android host check with an app's plain trust manager). Recorded for the run report.
- srs_deltas applied: OPS-BUILD-004 AC-2 (placeholder expectations of later requirements; build command evidence). The SEC-PROXY-005 Android delta was superseded by the round-2 rewrite.
- out_of_lease_paths accepted: `Proxy.java` (https wiring through `BackendClient.open`), `ProxyRequestFramingTest.java` (pre-TLS 502 placeholder replaced by one TLS connection and 502 from a non-TLS listener).
- CLAUDE.md: the lane changed only the module line (OPS-BUILD-004); the user's uncommitted SpecKiwi-block edits were saved as a patch, the file restored for the merge and the patch re-applied cleanly; they are in no commit.

No issue rows. Ledger `[]`.
