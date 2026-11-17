# Stage 7 partition — 2026-11-14.NaruServe.naruserve-proxy

Lane cap 4. Lanes dispatched in stage 7:

- `lane-2026-11-14.NaruServe.naruserve-proxy-wave-12` — 2026-11-14.NaruServe.naruserve-proxy-wave-12, 8 paths in its write set

Verdict: pass — one lane; wave-12 adds request header forwarding, request body forwarding (chunked wire form), the early 413 body limit and the abort/log tie-in to the Proxy; no other lane in stage 7, so no write-set overlap
