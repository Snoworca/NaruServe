# Wave-12 issues (Phase 3.m)

Residual union:
- loop P round 1: 0 kept. LP-9 (cites FR-PROXY-003 AC-8): the backend header block has no `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host` lines because SEC-PROXY-002 (wave-19) produces them; wave-12 leaves an empty slot in the AC-8 order. Carried forward per `verify-loop.md` §8 (`carried_into: wave-19`, `cross-wave-carry-forward`, `waves/carry-forward.json`). Consequence at promotion: FR-PROXY-003 is promoted to `implemented` only, with AC-1..AC-7 checked and AC-8 left unchecked; wave-19 re-checks AC-8 and verifies FR-PROXY-003.
- worker srs_delta (FR-PROXY-008 AC-1): a `Content-Length` list of one agreed value (`2048, 2048`) over the app `rawBodyLimit` on a mount without `.bodyLimit` got 413 only after the backend connect, because the core read only a single number as the declared length. Fixed in-wave by an independent fixer, tests first: 3708b61 (tests, red before), 4e49b0a (shared `internal/DeclaredLength` for `DefaultRequest` and `JsonBodyReader`; also fixed `5, 5` reading 10 bytes). Full build 4254 tests, 0 failures. Loop P round 1 probed the fix (proxy list forms over a socket, JSON and urlencoded limits) green.
- worker: resumed once after an API login expiry; the uncommitted FR-PROXY-008 work was kept and committed tests-first. FR-PROXY-021 held at base (test commit only, `intentionally_empty`). Review 0 kept.
- out_of_lease_paths: none (the core fix commits are host-dispatched fixes on the integration branch, REQ FR-HTTP-031, FR-PROXY-008).

No issue rows. Ledger `[]`.
