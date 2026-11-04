# Phase 2 brief: re-judge 12 draft requirements (kiwi-srs-feasibility, target 0.11.0)

You are the senior analyst. Judge the 12 requirements listed below yourself; do not launch subagents. This is read-only work: the only file you write is your output file.

All 12 were rated `medium` on 2026-11-01 and kept at `draft`. Since then:

- Their open values were decided and written into their ACs on 2026-11-02. The decisions are listed in `docs/analysis/kiwi-srs-feasibility-2026-11-01.naruserve.0110.v01/draft-open-questions.md`.
- 13 `depends_on` edges were relabelled `related_to`. The dependency graph now has no cycles.

Judge each requirement fresh, as its current text reads in `docs/spec/`. The earlier judgements in `docs/analysis/kiwi-srs-feasibility-2026-11-01.naruserve.0110.v01/per-req-judgement.json` are there so you can see what kept each one at medium. Do not copy their scores; decide whether those reasons still hold.

## Requirements

- SEC-AUTH-003, SEC-AUTH-007, SEC-AUTH-008 (`docs/spec/17.auth.srs.md`)
- SEC-TOKEN-009, IR-TOKEN-001 (`18.auth-tokens.srs.md`)
- FR-FLOW-006, FR-FLOW-009, SEC-FLOW-013, REL-FLOW-001 (`19.auth-transport-and-endpoints.srs.md`)
- FR-IAM-013, FR-IAM-014 (`20.auth-identity-and-grants.srs.md`)
- SEC-APIKEY-008 (`21.auth-api-keys.srs.md`)

Read each whole block. Also read the blocks it links to whenever a decision depends on them. Examples:

- IR-AUTH-003 AC-3 holds the code catalog.
- PERF-TOKEN-001 AC-3 and AC-6.
- SEC-FLOW-012 AC-6.
- SEC-FLOW-009 AC-4.

## Inputs

Folder D = `docs/analysis/kiwi-srs-feasibility-2026-11-02.naruserve.0110.v01/`.

- `D/code_context.json` holds build facts, reused from the previous run:
  - NaruServe-api has no main dependencies (`build.gradle:59-61`).
  - animal-sniffer checks the main source set against the API 21 signature.
  - That signature contains `java.sql`, `javax.sql.DataSource`, `java.text.Normalizer` and `FileDescriptor.sync`.
  - It lacks `java.time`, `java.util.Base64`, `ConcurrentHashMap.newKeySet` and the JDBC 4.1 methods.
  - sqlite-jdbc is allowed as `testImplementation` only.
- `D/existing_srs_context.json` holds the current dependencies of the 12, with their draft dependencies.
- The research doc is `docs/research/auth-rbac/00.overview.md` (edition 33). 00 wins over files 01–07.
- The code is the repository root.

## Scoring

Score each requirement 0–100 over six axes, weighted:

| Axis | Weight | What it measures |
|---|---|---|
| implementability | 30 | Java 8 / API 21, no new main dependency, Core public API only |
| evidence_strength | 20 | Do the `verifies` traces exist and match? Is the addition site concrete? |
| dependency_health | 15 | Dependencies are evolving, or are draft siblings in this set that are themselves implementable. There are no cycles now, so no cycle penalty applies. |
| ac_verifiability | 15 | Every AC can be decided by an automated test. Deduct only where an open item makes a test impossible to write. |
| scope_fit | 10 | |
| product_fit | 10 | Measured against the v1 row of 00 §9. |

Map the total to a label:

| Score | Label |
|---|---|
| 80 or more | high |
| 60–79 | medium |
| 40–59 | low |
| under 40 | blocked |

Rules for the judgement:

- The axis scores must add up to the total.
- Every implementability rationale cites a `path:line` or a build fact.
- A blocker needs code or build evidence behind it.
- A requirement that turns green late is not low on feasibility. Examples are a cross-scope gate test such as SEC-AUTH-008 AC-1, or the SEC-AUTH-007 AC-7 path. Record that as a condition, for example "schedule in the last wave".
- If a decided value now in an AC contradicts 00 or another 0.11.0 block, report it under `blockers`, citing the exact text on both sides, and lower ac_verifiability.

## Output

Write `D/per-req-judgement.json` in this shape:

```
{"judgements":[{"req_id","feasibility","score","axes":{"implementability":{"score","rationale","evidence":[...]},"evidence_strength":{...},"dependency_health":{...},"ac_verifiability":{...},"scope_fit":{...},"product_fit":{...}},"blockers":[],"conditions":[],"has_verification":false,"current_stability":"draft","current_status":"planned","external_module_impact":null,"contradictions_found":[]}]}
```

Then reply in under 150 words. Include:

- the label distribution;
- every requirement that is not high, with a one-line reason;
- any contradiction you found.
