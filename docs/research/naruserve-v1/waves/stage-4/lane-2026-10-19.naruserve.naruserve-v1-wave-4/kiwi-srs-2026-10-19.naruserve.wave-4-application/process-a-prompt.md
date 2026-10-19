You are Process A (verification) of the kiwi-srs A/B loop for wave 4 of NaruServe at /Users/beom/work/git/NaruServe. Read-only: do not edit any file except the one output file named below; do not commit.

Inputs (read them):
- Research docs (unverified working notes, do not edit): docs/research/naruserve-v1/waves/wave-4/design.md and docs/research/naruserve-v1/waves/wave-4/excerpt.md (the 79 design items D-xxx, the "Carried from earlier waves" lines, the "Wave decisions" section and the "Residual carried into SRS authoring" section).
- Constraints: docs/research/naruserve-v1/design/constraints.json.
- Deferred to later waves (must NOT be required by wave 4): the wave-4 lines of docs/research/naruserve-v1/waves/carried-into-wave-5.md and carried-into-wave-7.md.
- The SRS under review: every requirement block with Target `wave-4` in docs/spec/05.application.srs.md and docs/spec/04.http-core.srs.md (the wave-4 successors of FR-HTTP-002/003/005/006/010, which carry a `supersedes` link). Verified wave 1–3 requirements in docs/spec/0[2-4].*.srs.md are context only.
- Code: NaruServe-api/src (main, test, testFixtures), the logger modules, docs/api/*.md (current text, before wave 4 edits).

Check and report:
1. Coverage: every one of the 79 design items, every wave decision, the residual section and every carried line is covered by at least one AC whose text realises it (not only a rationale mention). List gaps.
2. Incorrect requirements: AC text that contradicts a design item, wave decision or residual; ACs that require something the carried-into-wave-5/7 files defer; internal contradictions between ACs (within or across requirements, including between the APP requirements and the HTTP successors); ACs that are not testable through the in-memory adapter / unit tests in this repo.
3. Feasibility on the current architecture (Dispatcher, DefaultRestApp, DefaultRequest, DefaultResponse, HttpAdapter SPI, InMemoryHttpAdapter, TestApp): anything not implementable or needing a change outside this repo (the TinyHttpd checkout is read-only).
4. Regression: a wave-4 AC that breaks a verified wave 1–3 requirement that was NOT superseded (for example verified FR-HTTP-001, FR-HTTP-008, FR-HTTP-016, FR-HTTP-017, IR-HTTP-003/004, OPS-HTTP-002) without saying so; also any verified requirement whose AC wave 4 changes but which was not superseded.
5. Requirement dependency hygiene: Requirement-type trace links with relation depends_on must point only at verified wave 1–3 requirements or wave-4 requirements; never at discarded, superseded or Stability=draft ones (CON-ROUTE-002..005, CON-HTTP-002..009, CON-BUILD-014..016 are draft).
6. Code trace references must be existing files.

Severity: CRITICAL (contradiction, wrong behaviour, deferred item required, missing core item), HIGH (uncovered design item/decision, untestable AC, bad dependency), MEDIUM, LOW. Do not inflate; cite the requirement id + AC number and the design id.

Write your findings as JSON to OUTPUT_PATH:
{"iter": N, "findings":[{"id":"A-N-k","severity":"...","req_id":"...|null","ac":"AC-n|null","design_ref":"D-xxx|wave decision ...|null","description":"...","suggested_fix":"concrete replacement text or action"}],"summary":{"CRITICAL":n,"HIGH":n,"MEDIUM":n,"LOW":n}}
Reply with only the summary counts and the ids+one-line descriptions of CRITICAL/HIGH findings.
