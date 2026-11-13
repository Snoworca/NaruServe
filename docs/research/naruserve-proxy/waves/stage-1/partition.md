# Stage 1 partition — 2026-11-14.NaruServe.naruserve-proxy

Lane cap 4. Lanes dispatched in stage 1 (the lock also plans stage 2 = wave-4, which is recomputed after this stage):

- `lane-2026-11-14.NaruServe.naruserve-proxy-wave-1` — 2026-11-14.NaruServe.naruserve-proxy-wave-1, 14 paths in its write set
- `lane-2026-11-14.NaruServe.naruserve-proxy-wave-2` — 2026-11-14.NaruServe.naruserve-proxy-wave-2, 13 paths in its write set
- `lane-2026-11-14.NaruServe.naruserve-proxy-wave-3` — 2026-11-14.NaruServe.naruserve-proxy-wave-3, 9 paths in its write set

Disjointness: the three write sets share no path (checked by `orchestrate schedule waves`). wave-4 shares `HttpApiSurfaceTest.java` with wave-3, so it waits for stage 2.

Verdict: pass — dependencies are honoured (no wave of stage 1 depends on another), the hot build files (`build.gradle`, `settings.gradle`, `BuildLayoutTest`) belong to wave-1 only in this stage, and wave-2/wave-3 touch disjoint `NaruServe-api` files.
