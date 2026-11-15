# Stage 2 partition — 2026-11-14.NaruServe.naruserve-proxy

Lane cap 4. Lanes dispatched in stage 2:

- `lane-2026-11-14.NaruServe.naruserve-proxy-wave-4` — 2026-11-14.NaruServe.naruserve-proxy-wave-4, 6 paths in its write set

Verdict: pass — one lane; wave-4 depends on wave-3 (complete) and touches Response/DefaultResponse/HttpExecution plus the shared API surface tests, which no other lane holds in this stage.
