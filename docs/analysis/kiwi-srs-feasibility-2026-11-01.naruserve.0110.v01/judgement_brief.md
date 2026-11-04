# Phase 2 judgement brief — kiwi-srs-feasibility, target 0.11.0

You are one of six per-scope analysts. For each 0.11.0 requirement in your scope, you prescreen it and then judge it. Do the work yourself and never launch subagents. Read only; write only your output file.

## Inputs

All inputs are in folder D = `/Users/beom/work/git/NaruServe/docs/analysis/kiwi-srs-feasibility-2026-11-01.naruserve.0110.v01/`.

- The requirement blocks, in the scope files your assignment names. Read the whole block: statement, ACs, trace links, research, implementation notes.
- `D/code_context.json`. Trace validation found no missing `verifies` paths. It also records build facts:
  - NaruServe-api has no main dependencies.
  - The animal-sniffer API 21 check covers the main source set only.
  - No JDBC driver is present yet. A test-only sqlite-jdbc is allowed, following the Jetty precedent.
  - Whether java.sql, javax.sql.DataSource, java.text.Normalizer and FileDescriptor.sync pass the API 21 signature is unconfirmed.
- `D/existing_srs_context.json`. It has the dependency graph and two depends_on cycles:
  - the pair FR-AUTH-004 ⇄ FR-IAM-006;
  - a 76-member strongly connected component, listed under `cycles`.
- `/Users/beom/work/git/NaruServe/docs/analysis/kiwi-srs-2026-10-30.naruserve.auth-rbac-v1/open_questions.md`. It lists values that the research document does not fix; they are decided during implementation, per CLAUDE.md.
- The research document: `/Users/beom/work/git/NaruServe/docs/research/auth-rbac/00.overview.md`, edition 33.
- The code under `/Users/beom/work/git/NaruServe`.

## Prescreen

Put each requirement in exactly one class:

- `quick_pass`: obviously high. The traces match, the ACs are concrete and testable, the dependencies are healthy, and nothing needs an unknown platform API.
- `quick_block`: obviously blocked, for example an impossible API on API 21 or a missing external module.
- `senior_target`: anything else. Judge every `senior_target` in full. Give a `quick_pass` requirement a short judgement too, but cite at least one code or build fact for it.

## Scoring axes

Total 0–100.

| Axis | Weight | What it measures |
|---|---|---|
| implementability | 30 | Difficulty on Java 8 / API 21 with no new main dependency, using only the Core public API. Penalise needs for java.time, java.util.Base64, java.nio.file or Core internals. |
| evidence_strength | 20 | `verifies` traces exist and match the statement. For new code, the `addition_site` parent exists and the design is concrete. |
| dependency_health | 15 | The dependencies are evolving or verified, or are 0.11.0 siblings that are themselves implementable. Subtract when the requirement is in a depends_on cycle: cycles block wave scheduling until the edges are cut. A cycle is an ordering problem, not an implementability one, so take at most 6 points off for it. |
| ac_verifiability | 15 | Each AC can be decided by an automated test. Subtract for ACs that depend on open questions or "(name not final)" items only if the open item makes the test impossible to write, not merely the name. |
| scope_fit | 10 | |
| product_fit | 10 | core / nice-to-have / out-of-scope, judged against 00 §9's v1 row. |

Labels: 80 or more is `high`, 60–79 is `medium`, 40–59 is `low`, under 40 is `blocked`.

Every implementability judgement cites path:line or a build fact. A judgement without a citation gets `evidence_strength: weak` in its rationale.

## Output

Write `D/per-req-judgement-<GROUP>.json`:

```
{"group":"<GROUP>",
 "prescreen":{"senior_targets":[...],"quick_pass":[...],"quick_block":[...]},
 "judgements":[{"req_id","feasibility","score","axes":{"implementability":{"score","rationale","evidence":[...]},"evidence_strength":{...},"dependency_health":{...},"ac_verifiability":{...},"scope_fit":{...},"product_fit":{...}},"blockers":[],"conditions":[],"has_verification":false,"current_stability":"draft","current_status":"planned","external_module_impact":null,"in_dependency_cycle":bool}]}
```

Rules for the output:

- The axis scores must add up to the total score.
- A blocker needs code or build evidence. A guess is not a blocker.
- Conditions are things implementation must do, for example "confirm javax.sql.DataSource is in the API 21 signature".

Reply in under 150 words with:

- the label distribution;
- every requirement not labelled `high`, with a one-line reason each;
- any blocker.
