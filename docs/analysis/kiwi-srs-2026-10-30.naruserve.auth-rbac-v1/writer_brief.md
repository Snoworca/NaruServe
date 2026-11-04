# kiwi-srs Phase 4 writer brief — target 0.11.0 (auth/RBAC v1 + Core C4/C6a/C6b/C7)

You are one of six parallel SRS writers. Each writer owns different scope documents. Work only on the scope(s) in your assignment.

## Inputs (read these)

- Research (source of truth): `docs/research/auth-rbac/00.overview.md` (edition 33).
  - §6.3 rules R28–R47 are final. §6.4 is the cross-check table; use its rows as AC material. §7 lists names, §8 the Core changes, §9 the roadmap (0.11.0 = "선행 (Core)" required items + the "v1" row), §12 the verification limits.
  - §6.1, §6.2 and §11 are history. Use them only to resolve doubt.
  - Files 01–07 in the same folder are background. Use them only where 00 is silent; 00 wins every conflict.
- Inventory: `docs/analysis/kiwi-srs-2026-10-30.naruserve.auth-rbac-v1/intent.json`. In `requirement_inventory`, filter by the `area` values named in your assignment.
- Code anchors: `code_context.json` (same folder), holding verified file:line for Core citations.
- Existing SRS: `existing_srs_context.json`.
- Decisions: `qna_log.json`.
- Rules: `docs/rule/SRS-MD-Rules-v2.5.0.md`. Skim the requirement block rules, ID and prefix rules, and trace rules.
- Example of a finished block: `docs/spec/05.application.srs.md`, `### FR-APP-021`.

## Tools

Load the speckiwi MCP tools with ToolSearch:
`select:mcp__speckiwi__add_requirement,mcp__speckiwi__add_trace_link,mcp__speckiwi__validate_spec,mcp__speckiwi__get_requirement,mcp__speckiwi__list_requirements`

All requirement writes go through `add_requirement` and `add_trace_link`. Never edit `docs/spec/*.srs.md` by hand after your first MCP mutation on that file.

- **Template exception.** Your new scope file (if any) is still a fresh template. Before any mutation on it you MAY replace the template prose once with Edit: `## 1. Scope Overview` text, `## 2. Scope Boundaries` In Scope / Out of Scope bullets, and `## 3. Assumptions and Constraints` bullets. Do this first, then never Edit that file again.
- **Existing scope files** (APP, HTTP, ENGINE, SVL): never Edit them.
- **Dry run first.** Call `add_requirement` with `dryRun: true` once, to see how fields render (for example whether you must prefix AC text with "AC-n:"). Then write for real.
- **Concurrency.** Other writers are mutating other files and the shared index at the same time. If a call fails with a lock, stale-snapshot or conflict error, retry the same call (up to 5 times). Never pass `ignoreLock`.

## Field rules for `add_requirement`

| Field | Rule |
|---|---|
| `type` | `functional`, `interface`, `security`, `data`, `constraint`, `operational`, `reliability` or `performance`. Pick what the statement is; security-relevant behavior → `security`. |
| `scope` | Your assigned prefix. |
| `target` | `"0.11.0"`. |
| `status` | `"planned"`. Do not pass `stability`; the default `draft` is intended. |
| `priority` | `high` for anything on the junior minimum path or security-relevant; `medium` otherwise. |
| `tags` | `["0.11.0", "auth-rbac", "feasibility:high"]`, or `feasibility:medium` for items in §12 verification limits (InnoDB/PostgreSQL locking behavior, WebView Origin), plus one short area tag. |
| `relatedDocs` | `["docs/research/auth-rbac/00.overview.md"]`, plus the 01–07 file if you used it. |
| `verificationMethod` | `test`. Use `inspection` only where no automated test is meaningful (docs wording). |
| `title` | English, short, specific. |
| `requirement` | English, one or two sentences with "shall". The behavior, not the history. |
| `acceptanceCriteria` | 3–8 items. Each is an observable, decidable check (input → expected status/code/value/state), written so a test can be derived. Use exact names, codes and numbers from the research. No vague words (appropriate, fast, reasonable, consistent, sufficient). |
| `rationale` | Why, citing the research rule (e.g. "00 §6.3 R29 (e), edition 32–33"). |
| `research` | The design the implementer needs, per kiwi-srs §0.23: decisive facts, the ground-truth decision where a plausible alternative exists, the pitfall named where the implementer will step on it, and rejected alternatives with their reopen condition. Keep it tight; this is where the research doc's reasoning goes, so later sessions do not re-argue it. |
| `implementationNotes` | Where it lands (files/classes), ordering constraints, the API 21 / no-`java.time` / no-`java.util.Base64` / no-`java.nio.file` limits when relevant. |
| `trace` | Array of `{type:"Code", reference:"<repo-relative path>[:Lx-Ly]", relation:"implements", notes:"trace_intent=verifies"\|"trace_intent=addition_site"}`. `verifies` = existing code the statement relies on, path:line from code_context.json. `addition_site` = where new code goes, e.g. `NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/` or a proposed file path. Every requirement gets at least one Code trace. Do not invent line numbers: use code_context.json or check the file yourself. |

## Content rules

- **No fabrication.** Every value in an AC (status code, error code, header, limit, default, order) must come from 00 (or 01–07 where 00 is silent). Mark a value taken from 01–07 that 00 does not restate with ` [INFERRED:med:advisory]` at the end of that AC line. If a needed value appears nowhere, do not invent it. Record it in your output file under `open_questions` and leave it out of the AC.
- **Names.** Use names exactly as 00 gives them. Where 00 says a name is proposed or not final ((미확정), "이름은 제안"), append " (name not final)" in the requirement or the AC. CLAUDE.md says these names are decided during implementation, so do not ask about them.
- **Granularity.**
  - One requirement per independently testable behavior surface.
  - Merge inventory candidates that are one test surface; split a candidate that holds two unrelated surfaces.
  - You need not map 1:1 to the inventory. Every inventory item in your areas must be covered by some requirement: list the mapping in your output file.
- **Cross-cutting rules** such as "no new dependency", "Core public API only" and "API 21": the AUTH writer owns them. Other writers reference them in `implementationNotes` and do not re-state them as requirements.
- **Links within your own scope(s):** after writing, add `add_trace_link {id: <newer/dependent REQ>, type:"Requirement", reference:<REQ it depends on>, relation:"depends_on"}`. Direction is always dependent → dependency.
- **Links to existing verified REQs** named in your assignment: add them with relation `extends` or `depends_on` and a short `notes`. Never change existing requirements: no `update_status`, no `update_stability` on them.
- **Links to requirements other writers are creating now:** do NOT add them. List each one in your output file under `pending_cross_links: [{from_id, to_area_or_title, relation, reason}]`. The main session adds them later.
- **Stop at writing.** Do not touch code, `docs/api`, the research docs, or git.

## Finish

1. Run `validate_spec` and fix diagnostics in your files only. Fix them via MCP where possible. If a block itself is wrong, report it; do not hand-edit.
2. Write `docs/analysis/kiwi-srs-2026-10-30.naruserve.auth-rbac-v1/srs_delta_<PREFIX>.json` containing:
   - `mcp_calls`: `[{tool, result_id, ok}]`
   - `added_ids`
   - `inventory_mapping`: `[{candidate_title, req_id}]`
   - `pending_cross_links`
   - `open_questions`
   - `validate_spec_result`: counts plus your-file diagnostics
3. Reply in under 250 words: number of requirements per scope, ID range, open questions, anything you could not do.
