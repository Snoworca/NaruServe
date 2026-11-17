# Wave-5 issues (Phase 3.m)

Residual union: loop P round 1 residual `[]`; worker review 0 kept, 0 residual; `out_of_lease_paths[]` empty.

Notes (not issues):
- worker srs_deltas: FR-APP-028 already held at the wave base (test-only commit 82b055c; implementation intentionally empty). FR-HTTP-031 AC-5 — the error code comes from the error-code table and is not in the body text. FR-HTTP-031 AC-4 — after the 413 the body counts as already read. Neither changes what a test checks; listed in the run report as SRS wording clarifications.
- loop P note: reading an undeclared-length body past the limit logs `in=limit+1` (the probe byte is counted, as the JSON reader does); no criterion covers it. docs/api for `rawBodyLimit`/`bodyStream` is OPS-DX-008 (wave-21), already in `carry-forward.json` from wave-3.
- the stage was re-dispatched twice (user pause, host reboot after loopback port exhaustion); the stopped workers' kiwi-coder work was committed tests first.

No issue rows. Ledger `[]`.
