# Wave-15 issues (Phase 3.m)

Residual union:
- loop P round 1: 27 tests, 0 kept (pass): Location rewrite bypass attempts (backslash and tab network paths, scheme case, default ports, IPv6, userinfo, empty port, refused authorities, base-path edges, stripPrefix mounts), fixed response header order and hiding, methods and upgrade hand-off over TinyHttpd.
- worker review: PASS after 2 rounds; FND-001 (userinfo or empty port in Location leaking the backend address) fixed in-lane; FND-101 (`http://h:80:/x` becomes `/x`, no leak) kept as a declared limit.
- srs_deltas applied: FR-PROXY-013 AC-11 added (network-path guard, userinfo, empty port, refused host or port). FR-PROXY-006 AC-2 note (an upgrade request hands off to next(), so the raw-suffix 400 is checked at requestTarget level) recorded only; FR-PROXY-006 is verified and its AC still holds for non-upgrade requests. FR-PROXY-009 AC-1/6/7 already held at base (tests green before the implementation commit).
- merge: one conflict in `Proxy.forward` (only the `@req` markers of both lanes) resolved by keeping both.
- out_of_lease_paths accepted: `ProxyRequestTargetTest.java` (FR-PROXY-009 AC-4 changes a request-target expectation; both original checks kept).

No issue rows. Ledger `[]`.
