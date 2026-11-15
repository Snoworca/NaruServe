# Wave-3 issues (Phase 3.m)

Residual union:
- loop P round 4 residual LP-3 (cites C-005: `docs/api/request.md` does not document `bodyStream`, `headerNames`, `rawHeaders`, `isFromTrustedProxy`). This documentation is the scope of OPS-DX-008 in wave-21, which is not yet dispatched. Per `verify-loop.md` §8 it is carried forward (`cross_wave: true`, `carried_into: wave-21`, reason_class `cross-wave-carry-forward`) and recorded in `docs/research/naruserve-proxy/waves/carry-forward.json`. It is not an issue row: no wave-3 requirement is incomplete and the owning requirement already exists. Wave-21's loop P re-checks it.
- host review hop (kiwi-review-fix-loop over 84c0698..8140fbe): one declared limit FND-001 — on JDK 16+ `equalsIgnoreCase` is not transitive for header names with lone surrogates; no adapter can deliver such names (TinyHttpd accepts ASCII token characters only; servlet containers decode names byte by byte). Recorded in the `sameHeaderName` Javadoc (5d9b1b2) and the run report; no issue row.
- worker manifest `out_of_lease_paths[]` empty.

No issue rows. Ledger `[]`. `speckiwi orchestrate wave close --wave 3` → ok, no violations.
