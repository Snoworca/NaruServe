# Wave-10 issues (Phase 3.m)

Residual union:
- loop P round 1 residual LP-19 (cites FR-PROXY-011 AC-2): a backend header value holding obs-text (byte 0xE9) reaches the client over a real TinyHttpd socket as 0xC3 0xA9, because TinyHttpd 3.1.1 writes header characters as UTF-8; the in-memory adapter keeps the character (LP-20 green). Byte-exact relay of obs-text header bytes is the criterion of SEC-PROXY-004 AC-9 (wave-17, not yet dispatched); carried forward per `verify-loop.md` §8 (`carried_into: wave-17`, `cross-wave-carry-forward`), recorded in `waves/carry-forward.json`. Not an issue row. Risk for wave-17: the encoding sits in the TinyHttpd library, which this run does not modify.
- loop P round 1 LP-18 (HEAD keeps backend Content-Length, green) is informational; HEAD length belongs to a later proxy requirement.
- worker review: TASK_DONE after 3 rounds, 0 blocking, 0 residual; 8 red probes outside this wave (SEC-PROXY-004, FR-PROXY-014 — wave-17; FR-PROXY-017, REL-PROXY-002 — waves 18–19; FR-PROXY-003, FR-PROXY-007 — wave-12) recorded as declared limits owned by later waves.
- out_of_lease_paths: `build.gradle` — test-scope dependencies of `NaruServe-proxy` only (api testFixtures, `NaruServe-tinyhttpd`); the runtime dependency stays `NaruServe-api` (CON-BUILD-021). Accepted at verify-lane.

No issue rows. Ledger `[]`.
