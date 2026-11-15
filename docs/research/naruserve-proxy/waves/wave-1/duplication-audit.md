# Duplication audit — wave-1 (stage 1)

Input: commit ranges of the stage-1 waves on `kiwi/orch/2026-11-14.NaruServe.naruserve-proxy/integration` (wave-1 merge `664ad96`, wave-2 `38c0c4e`, wave-3 `84c0698`), each limited to its lane write set. Method: compared the symbols and blocks each wave added (classes, public methods, test helpers) across the three ranges.

Wave-1 added build wiring (`settings.gradle`, `build.gradle`, `gradle/release.gradle`), `NaruServe-proxy/.../package-info.java`, and build/docs tests under `src/test/java/com/snoworca/naruserve/build|docs/`. Waves 2 and 3 touched none of these files and added no build logic or build-test helper.

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|

No candidates found.
