# kiwi-srs-feasibility 0.12.0 — 2026-11-14.naruserve.0120.v01

- Target: 0.12.0 (58 requirements, all `planned`)
- Mode: live, default policy (no policy file)
- Caller: kiwi-orchestrator run 2026-11-14.NaruServe.naruserve-proxy, step 2.a′

## Result

- Feasibility: high 58 (after the SRS amendments below; the first pass rated FR-ENGINE-014 and SEC-PROXY-005 medium).
- Target verdict: ready for implementation; no blockers, no dependency cycle.
- Stability changes: none (all 58 stay `evolving`; high → evolving is a no-op).

## Findings that changed the SRS

1. TinyHttpd 3.1.1 writes no `Content-Length` on a 304 (`ExchangeResponse.commit`, commit `e73cd5d`). The user decided (PRD U-2, PRD-271) that the 304 length through `res.relay` is adapter-dependent; FR-HTTP-037, FR-ENGINE-014, FR-SVL-010, FR-PROXY-014 and OPS-DX-009 were amended.
2. On the JDK the default `HostnameVerifier` rejects every host, and the JDK sends no SNI for `localhost`; SEC-PROXY-005 AC-3 and AC-6 now use a dotted test host and an injected verifier.
3. FR-PROXY-015 ↔ FR-PROXY-017 dependency cycle: the FR-PROXY-017 link became `extends`.

## Conditions for implementation

- Run the regression in Maven mode (`-PtinyHttpdFromMaven=true`): the local TinyHttpd checkout is at `de5c7ff`, not the verified `e73cd5d`.
- FR-REL-006/FR-REL-007 criteria that require recorded release runs are met at the release step (design D-009).
