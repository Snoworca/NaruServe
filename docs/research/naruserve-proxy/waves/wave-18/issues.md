# Wave-18 issues (Phase 3.m)

Residual union:
- worker review: stopped NEEDS_USER on FND-001 (a total or write deadline firing after the head read but before `res.relay` gave an aborted 200; the worker's own fix leaked backend `Set-Cookie`/`Cache-Control` into the 504 and the round check hit the earlier-fix-majority gate, so the worker reverted it). Host decision (recommended option): an independent fixer computed the response headers into `PendingResponseHeaders` and ran one final deadline check before `res.status` (736691e test, b6f88f5 fix); FR-PROXY-017 AC-11 records the hand-off boundary (FND-103), AC-12 the trickled client body. Lane full build 4624/0.
- loop P round 1: 11 tests, 1 kept — LP-1 (PRD-99): on an app without `onError` the core default error page printed the ProxyException's cause chain, so an `UnknownHostException` (or a TLS host-name failure) leaked the target host. Fixed tests-first by an independent fixer (0ad992c tests, 6c51bda: internal `ConcealedCauses` prints a ProxyException's causes by class name only; `printStackTrace` untouched so loggers and `getCause()` keep the address; the core matches the type by name because the proxy may use only public API and the core keeps no mutable static state; the proxy jar's R8 consumer rule keeps the name; docs/api/defaults.md ko/en updated). Full build 4627/0. Design note for the run report: a name-based coupling from the core to the optional proxy module.
- loop P round 2: 9 tests, 0 kept (pass); one red probe (slow-reading backend) dropped because the socket accepted no byte before the 1 s write deadline, so the WRITE_TIMEOUT was per spec.
- carried LP-1 from wave-16 closed (ProxyTimeoutsTest:418-445; REL-PROXY-001 VE-12).
- srs_deltas applied: FR-PROXY-017 AC-11/AC-12; SEC-PROXY-004 change note (head read timeout now 504 READ_TIMEOUT). FR-PROXY-015's early-response read timeout as INVALID_RESPONSE matches its statement ("reports INVALID_RESPONSE otherwise"), no edit.
- carried to wave-19: the separate TLS watchdog executor in `TlsConnector` (REL-PROXY-002 AC-2..AC-4) — duplication audit merge candidate.
- out_of_lease_paths accepted: `LocationRewriter.java`, `PendingResponseHeaders.java`, `ResponseHeaderCopier.java` (FND-001 fix); host fix touched core `FinalHandler`/`ConcealedCauses` and the proxy R8 rule (REQ FR-PROXY-015).

No issue rows. Ledger `[]`.
