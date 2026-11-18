# Wave-13 issues (Phase 3.m)

Residual union:
- loop P round 1: 14 tests, 0 kept (verdict pass). Two red probes not kept:
  - LP-9 (FR-PROXY-004 AC-4): an empty `Host` line over a TinyHttpd socket gets 421 from the default host allowlist before middleware. Adapter-first, the same answer SEC-PROXY-001 AC-8 now accepts; the proxy's own empty-Host rule holds through the in-memory adapter. Recorded for the run report.
  - LP-10 (FR-PROXY-004 AC-1): a client-sent `X-Forwarded-Host` is forwarded ahead of the proxy's own line. Dropping client forwarding headers is SEC-PROXY-002 (wave-19); noted in `carry-forward.json` for the wave-19 worker and loop P.
- worker review: TASK_DONE after 2 rounds, 0 kept; degenerate host names fixed in-lane tests-first (442e072, 6e06870); IPv4/IPv6 literal bounds pinned after a late TDD verifier (a0880bd).
- srs_deltas (4) applied as AC rewordings with change notes: SEC-PROXY-001 AC-8 (421 of the default host allowlist), FR-PROXY-001 AC-2 (host-name grammar), FR-PROXY-004 AC-4 (empty Host = no Host), FR-PROXY-006 AC-2 (mount path and raw-suffix 400).
- host fix: servlet half of SEC-PROXY-001 AC-8 pinned by `ServletProxyAbsoluteFormTest` (8badfe2, test-only; `build.gradle` gains `testImplementation project(':NaruServe-proxy')` in `:NaruServe-servlet`). Full build 4369 tests, 0 failures.
- out_of_lease_paths: none from the lane.

No issue rows. Ledger `[]`.
