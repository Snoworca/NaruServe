# Stage 8 partition — 2026-11-14.NaruServe.naruserve-proxy

Lane cap 4. Lanes dispatched in stage 8:

- `lane-2026-11-14.NaruServe.naruserve-proxy-wave-13` — 2026-11-14.NaruServe.naruserve-proxy-wave-13, 9 paths in its write set

Verdict: pass — one lane; wave-13 adds request-target and path checks, Proxy.to(url) validation, Host handling and the forwarded target path to the Proxy; no other lane in stage 8, so no write-set overlap
