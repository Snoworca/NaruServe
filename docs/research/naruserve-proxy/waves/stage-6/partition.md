# Stage 6 partition — 2026-11-14.NaruServe.naruserve-proxy

Lane cap 4. Lanes dispatched in stage 6:

- `lane-2026-11-14.NaruServe.naruserve-proxy-wave-11` — 2026-11-14.NaruServe.naruserve-proxy-wave-11, 4 paths in its write set

Verdict: pass — one lane; wave-11 adds request framing checks (Content-Length/Transfer-Encoding) to Proxy.java before any backend connection; no other lane in stage 6, so no write-set overlap
