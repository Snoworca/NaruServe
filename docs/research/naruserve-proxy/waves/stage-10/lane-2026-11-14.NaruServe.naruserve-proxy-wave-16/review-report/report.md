---
run_id: 2026-11-18.naruserve.self-w16s10
mode: self
mode_flags: [--base 1f4974b, --head HEAD, --req-filter FR-PROXY-010,FR-PROXY-019,REL-PROXY-001, --no-pipeline-emit, --keep-clean-code, --loops 20, --regression-baseline]
pr_url: null
findings_total: 7
classified: { immediate_fix: 1, declared_limit: 1, discussion_needed: 0, rejected: 5 }
blocking_kept: 0
declared_limits: [FND-101]
scope_widen_reasons: []
fix_iter: 1
recheck_iter: 2
regression_pass: true
test_sufficiency: pass
closed_reqs_count: null
pr_responded: false
---
# kiwi-review-fix-loop report: wave 16, stage 10 (NaruServe-proxy)

## Scope
- Self mode, base-head range 1f4974b..HEAD (869dcd7): 8 files, 8 included, 0 prose, 0 refused, 0 unclassified.
- Guarantee scope: the ACs of FR-PROXY-010, FR-PROXY-019 and REL-PROXY-001. The later-wave limits are recorded in `state.json`.

## Rounds
| Round | Tree | Scope | Denominator (hunks) | Coverage | Findings | check_review_round |
|---|---|---|---|---|---|---|
| 1 | 7c581ab | full | 8 files (1/37/1/1/1/1/1/1) | OK | FND-001 keep→block, FND-002..005 drop | CONTINUE |
| 2 | 8115174 | since-prev | ProxyExchangeTest.java (4) | OK | FND-001 resolved; FND-101 keep→record | PASS |
| 3 (clean-code re-review) | 8429dc6 | since-prev | Proxy.java (21), ProxyExchangeTest.java (3) | OK | FND-201 drop; no new clean-code items | PASS |

## Fixes
- **FND-001** (block, REL-PROXY-001 AC-3), changed in ProxyExchangeTest.java only.
  - Before the fix, no test showed that the onAbort notification alone closes the backend socket. Mutant M1b, whose listener does not close the socket, survived all 417 tests.
  - The fix adds assertions right after the abort and before close(), a new `ABORT_ALONE` order, and a test of an abort during a relay copy blocked on a quiet backend.
  - Mutant M1b now fails 3 of 10 tests (red), and the same tests are green on the real code.

## Declared limits (record)
- **FND-101**: the new test helper `awaitBlockedRelayCopy` scans every JVM thread for a `DefaultResponse.runTransfer` blocked on a socket read.
  - **Evidence:** with a stray blocked relay left by another test, 5 of 10 runs on correct code fail falsely. Mutant M1b fails 10 of 10, so there is no false pass. Details are in `repro/ReviewReproStrayRelayThreadTest.output.txt`.
  - **Why it is not a fix:** the current suite leaves no such thread, there is no real call path, and production code is not affected.
  - **Suggested hardening:** wait until `reservationCount()==1` and `resRef!=null` before the scan, or match only this request's own thread.

## Rejected
See `rejected_findings.log`: FND-002, FND-003, FND-004, FND-005, FND-201.

## Clean code (--keep-clean-code)
- Two rounds ran. Round 2's review raised no new items.
- **Fixed:**
  - CC-002: option methods now keep a watchdog installed with `withWatchdog`, through the private `derived()` helper. A new test covers it, and a mutation check confirmed that test.
  - CC-003: the private `applyResponseHeaders` was extracted. The FR-PROXY-012 order is unchanged.
  - CC-101: javadoc wording.
  - CC-102: assertion message wording.
- **Dismissed:**
  - CC-001: restructuring the 15-argument Options type is too risky.
  - CC-004: `RELEASED` is an intentional sentinel.
  - CC-005: merging the test backends needs a shared file outside the write set.
- **Unresolved:** none.
- **Preservation scan:** no public signature changed, no test or assertion was removed, there are no internal/spi imports, and no mocks were added.

## Regression
- `./gradlew -PtinyHttpdFromMaven=true build` exited 0 with 4511 tests, 0 failures, 0 errors and 0 skipped.
- **Pinned baseline:** 3829 tests, 0 failing; all still pass.
- **Comparison with HEAD:** HEAD had 4508 tests; this run adds 3 new tests and has 0 new failures.

## Test sufficiency (Phase 7.4)
- MCP `check_test_sufficiency` with workspaceRoot set to the worktree and ids FR-PROXY-010, FR-PROXY-019 and REL-PROXY-001 passed.
- 22 of 22 ACs are cited, with 0 gaps.

## Known limits carried over (not fixed, outside the write set)
- `DefaultResponse.runTransfer` in the core calls `ex.finish()` before it closes the relayed stream. Fixing this needs a NaruServe-api change.
- TinyHttpd does not report an idle client disconnect while the relay is blocked reading the backend.
