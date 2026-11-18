# Wave-21 issues (Phase 3.m)

Residual union:
- loop P rounds 1–6 each kept documentation findings (the code was right every time); each was fixed tests-first by an independent fixer with doc tests that run the real app or proxy and compare the pages:
  - r1: default 413 of an unwrapped overrun is the HTML default page, not a JSON body (OPS-DX-008 AC-2); failures before the response are not logged by the default flow (OPS-DX-010 AC-3) — 56ba544/11e9618.
  - r2: interrupt cases of retries (FR-PROXY-018 AC-13); slot held until the read/total timeout when the backend goes silent mid-body (REL-PROXY-001 AC-1) — 2077a82/2e6822d.
  - r3: Location rewrite `/.` guard, userinfo, empty port and the rest of FR-PROXY-013 — 3dc536a/4c6bb8d.
  - r4: per-header table of client values vs `.requestHeader` for forwarding headers (FR-PROXY-005 AC-2, SEC-PROXY-002, FR-PROXY-002) — b0c959d/daf128b.
  - r5: failure after the response started (closed connection or status line with a cut body), IPv4 literal wording, `BodyTooLargeException` section with Express counterpart and constructor (C-005) — 1da42a3/fb2357c/4663bb2.
  - r6: thread bound is `maxWorkers + maxSseStreams` (OPS-DX-010 AC-9 reworded in the SRS too); Java and Kotlin examples for every public Proxy/ProxyException member, pinned by a reflection doc test (C-005) — 51a2f4b/bb44e35.
- loop P round 7: 26 tests, 0 kept (pass); `mkdocs build --strict` (ko/en) and `check_site.py` pass; C-005 holds for every public member added in 0.12.0.
- carried LP-3 from wave-3 (C-005 request.md methods) closed in this wave.
- srs_deltas applied: OPS-DX-010 AC-4 (shorter read timeout, longer total timeout), AC-9 (thread bound).
- worker review: PASS in round 4; FND-301/302 (GFM rendering edges the doc tests cannot reproduce) declared limits; FND-003/303 dismissed (recommended option).
- process notes for the run report: workers waited on background subagents by polling; one doc fixer ran its builds in the background despite the brief; the stage-14 review-report copy was the wrong committed report and was removed.
- out_of_lease_paths: none.

No issue rows. Ledger `[]`.
