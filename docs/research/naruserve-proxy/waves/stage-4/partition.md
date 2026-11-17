# Stage 4 partition — 2026-11-14.NaruServe.naruserve-proxy

Lane cap 4. Lanes dispatched in stage 4:

- `lane-2026-11-14.NaruServe.naruserve-proxy-wave-7` — 2026-11-14.NaruServe.naruserve-proxy-wave-7, 4 paths in its write set
- `lane-2026-11-14.NaruServe.naruserve-proxy-wave-8` — 2026-11-14.NaruServe.naruserve-proxy-wave-8, 9 paths in its write set
- `lane-2026-11-14.NaruServe.naruserve-proxy-wave-9` — 2026-11-14.NaruServe.naruserve-proxy-wave-9, 2 paths in its write set

Verdict: pass — three lanes with disjoint write sets (checked by orchestrate schedule waves): wave-7 TinyHttpd request-side raw access conformance tests, wave-8 res.append / public isFieldValue and the response docs (OPS-DX-009), wave-9 the servlet adapter conformance (FR-SVL-010, AC-1 now order-independent). No wave of the stage depends on another.
