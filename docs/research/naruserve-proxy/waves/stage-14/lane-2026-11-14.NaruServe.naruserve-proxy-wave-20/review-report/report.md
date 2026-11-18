---
run_id: 2026-11-02.naruserve.self-1830
mode: self
mode_flags: [--auto, --resume (5.0.1), --loops 6 (user-extended)]
pr_url: null
findings_total: 128 (round 1) + 12 new in rechecks
classified: { immediate_fix: 59, discussion_needed: 0, rejected: 69 } (round 1, pre-5.0.1) ; 5.0.1 re-judge of 82 open: keep 16 / drop 67 / residual 2
fix_iter: 6
recheck_iter: 6
regression_pass: true
test_sufficiency: no-scope
closed_reqs_count: null
pr_responded: false
---
# kiwi-review-fix-loop report — 2026-11-02.naruserve.self-1830

## 1. Flags and cost
Self mode, --auto. Started under the pre-5.0.1 skill (severity gate); on 2026-11-03 the user upgraded speckiwi to 5.0.1 and asked to continue in 5.0.1 form, so the run resumed under the keep/drop (execution-evidence) rules with one reviewer per round. Round cap 5 was reached with 1 kept finding; the user extended by one round (--loops 6). Verifiers ran strictly one at a time (user rule).

## 2. Scope
Working-tree changes of target 0.11.0 against HEAD bdd576a: 566 candidates = 342 code (184 main, 158 test) + 183 prose excluded + 15 refused (journals/locks) + 26 unclassified (binary, .kiwi session files). Identity holds. 448 hunks in round 1, reviewed in 9 chunks (M1-M5, T1-T4).

## 3-4. Findings and classification
- Round 1 (pre-5.0.1): 128 findings (HIGH 2, MEDIUM 26, LOW 100). Classified: immediate_fix 59, rejected 69 (57 auto-LOW, 10 duplicate, 1 intended, 1 misread).
- Round 2 recheck (pre-5.0.1): 19 new (MEDIUM 2, LOW 17), 52 earlier resolved; gate CRITICAL=0/HIGH=0 passed. The 2 MEDIUM were fixed (F7).
- 5.0.1 re-judge of the 82 still-open findings (3 parts + F7 recheck): keep 16, drop 67, residual 2.
- Residual decisions by the user: R2-M1-F06 (Windows key .bak) -> fix; R2-T2-F03 (TinyHttpd floor latency margin) -> keep as is.
- 5.0.1 recheck rounds: round 3 keep 2 (N3-01, N3-02), round 4 keep 1 (N4-01), round 5 keep 1 (N5-01), round 6 keep 0 -> PASS.

## 5. Fixes applied
Fixer runs F1-F6 (59 round-1 items: 56 fixed, 1 not reproduced after JDBC removal, 2 skipped: M3-F06 later dropped as not a defect, T4-F03 partial), F7 (2), G1-G3 (16 kept + R2-M1-F06), G4 (2), G5 (1), G6 (1). Every behavioural fix had a red test first; from G1 on each also had a mutation check (fix reverted -> red). Details: fix_iter*.json.
Kept findings fixed under 5.0.1: F7-N01, M1-F04, M2-F09, M2-F12, M2-F19, R2-M1-F02, R2-M1-F08, R2-M1-F09, R2-M1-F03, R2-M1-F04, R2-M1-F05, M4-F09, R2-T1-F03, T2-F07, R2-T2-F06, R2-T2-F04, N3-01, N3-02, N4-01, N5-01.

## 6. Recheck rounds
Round 2: 3 chunks over 104 files / 699 hunks. Rounds 3-6 over 26/130, 5/18, 3/12, 2/9 files/hunks. Every earlier kept finding was re-run against the final code and no longer reproduces.

## 7. Regression
Final full build (PM-run, clean test results): ./gradlew build --continue --offline -> 3379 tests, 0 failures, 0 errors, 0 skipped. Baseline at loop start 3374 (all green). No new failures at any step. animal-sniffer (API 21) green.

## 9-10. Dropped findings and residuals
All drops with reasons: rejected_findings.log. Residuals: 2, both decided by the user (above). No residual remains.

## 12. Scope buckets
excluded_prose 183, refused_artifacts 15, unclassified_files 26 — lists derivable from rfl_candidates.txt classification (mode_decision.json).

## 13. Coverage check
All rounds machine-checked with rfl_check.py (anchors per hunk, distinct hunks, no duplicates in a row). Exceptions: (a) round-2 M1 DefaultRestApp row repeated one anchor because two hunks are identical (56 hunks, max 55 distinct anchors) — accepted as structural, checker extended to allow repeats only up to the structural deficit; (b) round-2 T1 first submission was invalid (6 rows over-repeated), resubmitted once and passed — no two consecutive invalid rounds.

## 14. Test sufficiency
no-scope (no --close-reqs / --req-filter / --sds). Separately, the PM verified speckiwi coverage --tests gap-free for every 0.11.0 requirement promoted in this session.

## Context changes during the loop (user decisions)
- Built-in JDBC persistence removed (CON-IAM-003; 11 JDBC requirements discarded).
- File-stored secrets encrypted as enc(...) under AuthFileKey (SEC-IAM-008, FR-IAM-015).
- speckiwi skills upgraded to 5.0.1.

## Not committed
Nothing was committed or pushed.
