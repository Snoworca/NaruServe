# Stage 3 partition — 2026-11-14.NaruServe.naruserve-proxy

Lane cap 4. Lanes dispatched in stage 3:

- `lane-2026-11-14.NaruServe.naruserve-proxy-wave-5` — 2026-11-14.NaruServe.naruserve-proxy-wave-5, 15 paths in its write set
- `lane-2026-11-14.NaruServe.naruserve-proxy-wave-6` — 2026-11-14.NaruServe.naruserve-proxy-wave-6, 8 paths in its write set

Verdict: pass — two lanes with disjoint write sets (checked by orchestrate schedule waves): wave-5 holds the request body stream, rawBodyLimit and access-log counting; wave-6 holds res.relay framing and the TinyHttpd relay conformance tests. Neither depends on the other.
