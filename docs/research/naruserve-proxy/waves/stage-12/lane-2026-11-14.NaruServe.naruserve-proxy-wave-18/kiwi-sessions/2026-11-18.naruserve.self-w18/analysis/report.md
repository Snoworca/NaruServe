---
run_id: 2026-11-18.naruserve.self-w18
mode: self
mode_flags: [--keep-clean-code, --loops 20, --no-pipeline-emit, --req-filter FR-PROXY-017,FR-PROXY-015]
findings_total: 11
classified: { immediate_fix: 2, declared_limit: 1, discussion_needed: 1, rejected: 7 }
blocking_kept: 1
declared_limits: []
fix_iter: 1
recheck_iter: 1
regression_pass: true
test_sufficiency: pass
closed_reqs_count: null
---
# Self-review: wave-18 (9f6bc57..HEAD)

The review covered 6 code files: BackendClient, Proxy, ProxyExchange, RelayedBodyStream, ProxyErrorsTest and ProxyTimeoutsTest. No prose, refused or unclassified files were in scope.

## Round 1 (full scope, tree f62d7b8)
The review coverage check passed. RelayedBodyStream has two hunks that each hold only the line `@req FR-PROXY-017`, with different indentation. Their anchors differ only in whitespace, and no other anchor could be used.

- FND-001 is kept and blocks. A total or write deadline that fires after the head is parsed but before res.relay is reported as 200 followed by an abort and a log entry, not as a 504 (FR-PROXY-017).
- FND-002 to FND-008 were dropped. The https and close-delimited timeout probes ran correctly. The TCP silly-window counterexample is confounded. The rest were unmeasured or are wave-19 scheduler details.

Clean code: CC-001 (duplicated settle-and-rewrap), CC-002 (dead fallback) and CC-004 (naming of `fired`) are marked for fixing. CC-003 and CC-005 are dismissed because they belong to REL-PROXY-002 in wave-19.

## Fix 1 (5877b93)
The fix added `throwIfFired` after the head read and again before res.relay, and added a regression test. The test was red before the fix and green after it, and all 532 module tests pass.

## Round 2 (since-prev, tree d192ead)
The coverage check passed. FND-001 is resolved.

- FND-101 is kept, blocks, and was introduced by round 1. The second check runs after res.status and the backend header copy. A 504 then carries the backend's Set-Cookie and `Cache-Control: public, max-age=600` (a probe reproduced this).
- FND-102 is kept as a record, introduced by round 1. It is a mutation survivor: removing the post-head check leaves every test green.
- FND-103 is residual. A small window remains between the final check and the start of res.relay, and it could not be reproduced.

`speckiwi review-round check` returned NEEDS_USER with reason `earlier-fix-majority`: all of the round's blocking findings were introduced by the round-1 fix. That is the critical gate `fix-induced-defects-majority`.

## Action
The fix loop stopped at that gate. Commit 3605870 reverts 5877b93, which brings the branch tree back to the reviewed tree f62d7b8 and removes the FND-101 header leak. FND-001 is still an open blocking finding.

The recommended next step, from the classifier:
- Compute the response headers before touching res.
- Make one final `throwIfFired` just before res.status.
- Write one test that pins both the check and the absence of backend headers.
- Accept FND-103 as the documented hand-off boundary and record it as an FR-PROXY-017 clarification.

## Clean-code rounds
These did not run, because the functional loop ended in NEEDS_USER.

## Regression and sufficiency
- The full build passed: 4622 tests, 0 failures, exit code 0.
- `check_test_sufficiency` for FR-PROXY-017 and FR-PROXY-015 passed with 0 gaps.
