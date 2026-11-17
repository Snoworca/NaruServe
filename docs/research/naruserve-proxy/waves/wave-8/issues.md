# Wave-8 issues (Phase 3.m)

Residual union:
- loop P round 1 LP-4 and round 2 LP-1 (C-005: the prose descriptions of `app.defaultCache` in docs/api did not state the `res.relay` exception) — resolved in-wave by independent fixers, tests first: 2da6d2b/1969ab2 (static, defaults, response pages) and e30f576/b1e9622 (app pages); round 3 passed with 0 kept.
- carried in from wave-4 (LP-10: response docs for append/onAbort/relay) and wave-6 (relay/defaultCache Javadoc) — closed by the worker (OPS-DX-009 work; Javadoc lines in `Response.java`/`RestApp.java`, accepted out of lease) and confirmed by loop P rounds 1–3.
- worker review: 3 recorded limits (CRLF checkouts for the docs tests; the AC-4 text scan; a comment overstating the U+017F sweep range). Not residuals.
- `out_of_lease_paths`: `Response.java`, `RestApp.java` (one Javadoc sentence each).

No issue rows. Ledger `[]`.
