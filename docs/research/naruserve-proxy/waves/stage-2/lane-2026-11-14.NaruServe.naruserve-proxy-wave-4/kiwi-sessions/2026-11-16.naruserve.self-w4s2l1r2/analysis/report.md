---
run_id: 2026-11-16.naruserve.self-w4s2l1r2
mode: self
mode_flags: [--auto, --keep-clean-code, --loops 20, --no-pipeline-emit, --req-filter IR-HTTP-014,FR-HTTP-035]
pr_url: null
findings_total: 7
classified: { immediate_fix: 1, declared_limit: 1, discussion_needed: 1, rejected: 4 }
blocking_kept: 0
declared_limits: [FND-301]
scope_widen_reasons: []
fix_iter: 1
recheck_iter: 1
regression_pass: true
test_sufficiency: pass
closed_reqs_count: null
pr_responded: false
outcome: TASK_DONE (check_review_round round 2: PASS)
---

1. Flags: --auto --keep-clean-code --loops 20 --no-pipeline-emit --req-filter IR-HTTP-014,FR-HTTP-035. Range 730b55b..184e89c (7 code files, 31 hunks; 0 prose, 0 refused, 0 unclassified).
2. Round 1 (full, tree 0c249b4): FND-201 keep/block (introduced by 184e89c): the `upgrading` flag set before upgrades.run ignored an exchange-reported abort before onOpen; webSocket then threw, the error path committed 500, no listener ran, log `GET /ws 500` without `aborted` (FR-HTTP-035 AC-7). Proof red: `state=COMPLETED runs=0 commits=1 status=500`. FND-202 residual: check-then-act race between the NEW check and the flag. FND-203 drop (relay length, FR-HTTP-036/037 scope), FND-204 drop (pre-existing middleware-on-aborted-exchange). CC-201 SRP kept. Coverage PASS (7/7 rows). check_review_round: CONTINUE.
3. Fix iter 1 (atomic variant): DefaultResponse.openSession()/abortByExchange() decide under the response lock; DefaultWebSocketSession.onOpen asks openSession() first and refuses (no 101 line, no handler, channel.abort, no close line) when an abort came first; Dispatcher hook = res::abortByExchange; WebSocketUpgrade passes res to the session. Tests FR_HTTP_035_AC7_legacyUpgradeAbortReportedBeforeOnOpenAbortsTheResponse and ...RefusesTheLateOpen red then green. Preservation scan: no public signature/test removed; no mocks.
4. Round 2 (since-prev, tree 1e1c170): FND-201 and FND-202 resolved; CC-201 resolved. FND-301 record (declared_limit): an exchange abort after the adapter wrote 101 but before onOpen logs one `GET /ws 200 ... aborted` line (the core cannot see the adapter-side 101 before onOpen; no AC breached; FR-APP-019 status-token detail outside the guarantee scope). FND-302 rejected: pre-existing since 730b55b (Error from a ws handler in an open session writes a 2nd `GET /ws 200 aborted` line) - follow-up for FR-APP-019/FR-WS. FND-303 rejected (no defect). CC-301, CC-302 dismissed (existing conventions). Coverage PASS (5/5 rows). check_review_round: PASS (block 0, record 1, residual 0).
5. Regression: ./gradlew -PtinyHttpdFromMaven=true build on tree 1e1c170 - 3936 tests, 0 failures (baseline 184e89c: 3934/0).
6. Test sufficiency (IR-HTTP-014, FR-HTTP-035): 13/13 ACs cited, passed.
7. Commits: d8b1c38 test(http): pin exchange aborts reported before the WebSocket onOpen; 1b6fb41 fix(http): gate legacy-path exchange aborts at the WebSocket onOpen.
8. Declared limits: FND-301 (see 4), evidence classified_findings_iter2.json. Follow-ups: FND-301 doc/SPI signal; FND-302 Error path second line.
