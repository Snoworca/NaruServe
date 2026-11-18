# Wave-16 issues (Phase 3.m)

Residual union:
- loop P round 1: 6 tests, 1 kept, carried — LP-1 (REL-PROXY-001 AC-1): over real TinyHttpd sockets, when the backend goes silent mid-body and the client disconnects, nothing is written, so TinyHttpd 3.1.1 cannot report the disconnect (`res.onAbort` is not called) and the slot and backend socket stay held until the read timeout (default 60 s). TinyHttpd is not modified in this run, so REL-PROXY-001 AC-1 was bounded (release at the latest by the read or total timeout; change note) and LP-1 carried to wave-18 (FR-PROXY-015/017 own the timeouts) as `cross-wave-carry-forward`: the wave-18 worker and loop P re-check the read-timeout and total-timeout bounds over real sockets. Verdict pass-with-residual.
- loop P green: maxConcurrent(1) under parallel clients, sequential clients never 503, concurrent close/abort release once, later-middleware exception, HEAD.
- worker: FR-PROXY-010 held at base (tests only, `intentionally_empty`; each test red under a deliberately broken check order). Review 3 rounds, 0 kept, 3 declared limits. The worker waited on background subagents by polling in the foreground (manifest note).
- srs_deltas: none. out_of_lease_paths: none.

No issue rows. Ledger `[]`.
