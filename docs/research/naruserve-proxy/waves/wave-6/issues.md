# Wave-6 issues (Phase 3.m)

Residual union: loop P round 1 residual `[]`; worker review 0 kept, 0 residual, 0 declared limits; `out_of_lease_paths[]` empty.

Notes (not issues):
- FR-ENGINE-014 is test-only (906c2bc): TinyHttpd 3.1.1 already met AC-1..AC-6, so its implementation is intentionally empty. FR-HTTP-036 AC-1..AC-4 were already met by IR-HTTP-014's relay and are pinned.
- Per PRD-271 (U-2), the core commits `Content-Length` on a relayed 304 and TinyHttpd 3.1.1 drops it; loop P confirmed both.
- worker srs_deltas: `Response#relay` and `RestApp.defaultCache` Javadoc should say relay never receives `defaultCache`; `docs/api` relay text belongs to OPS-DX-009 (wave-8), already carried there from wave-4 (LP-10). The Javadoc note is passed to the wave-8 worker through `carry-forward.json`.
- the stage was re-dispatched twice (user pause, host reboot).

No issue rows. Ledger `[]`.
