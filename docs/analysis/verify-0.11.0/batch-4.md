# 0.11.0 verification, batch 4

All 14 requirements moved to `verified`. All 104 ACs were read against the test body, not against a citation or evidence row. 91 were already asserted. The other 13, across 10 requirements, were only partly asserted, and 9 added tests plus one strengthened helper close them. No main source and no `docs/api` file changed.

## PRODUCT-GAP

None. Every AC is met by the code. The 13 partial ACs were all gaps in the tests, and each now has an assertion that passes without a change to main sources or `docs/api`.

## EXPIRED-AC

None.

## Results

"Rows" is the final number of evidence rows. Before this batch every row named a whole test file, so each was rewritten to one real method with the note `command: ./gradlew build; result: pass`.

| Requirement | ACs read | Verdict | Tests added or strengthened | Rows | Status |
|---|---|---|---|---|---|
| REL-APIKEY-002 | 5 | 5 asserted | none | 6 | verified |
| REL-APIKEY-003 | 7 | 6 asserted, AC-7 gap closed | `REL_APIKEY_003_AC7_theKeyThenAnswersInvalidApiKey` | 8 | verified |
| REL-APIKEY-004 | 9 | 7 asserted, AC-6 and AC-8 gaps closed | `REL_APIKEY_004_AC6_theMarkerIsClearedInsideTheOwnerLock`, `REL_APIKEY_004_AC8_theRetriesPassTheMarkersVer` | 10 | verified |
| SEC-APIKEY-001 | 7 | 6 asserted, AC-5 gap closed | `SEC_APIKEY_001_AC5_thePublicPathStaysAnonymous` | 4 | verified |
| SEC-APIKEY-002 | 6 | 5 asserted, AC-3 gap closed | `SEC_APIKEY_002_AC3_theLoginAnswerIsInvalidRequest` | 3 | verified |
| SEC-APIKEY-003 | 5 | 3 asserted, AC-2 and AC-3 gaps closed | `SEC_APIKEY_003_AC2_AC3_noFieldHoldsTheSecret` | 2 | verified |
| SEC-APIKEY-004 | 10 | 9 asserted, AC-10 gap closed | `SEC_APIKEY_004_AC10_theAdminIssueHandlerDoesNotRun` | 11 | verified |
| SEC-APIKEY-005 | 11 | 8 asserted, AC-2, AC-3 and AC-10 gaps closed | `SEC_APIKEY_005_AC2_AC3_AC10_theBodyCodesOfTheLimit` | 10 | verified |
| SEC-APIKEY-006 | 6 | 6 asserted | none | 5 | verified |
| SEC-APIKEY-007 | 6 | 6 asserted | none | 5 | verified |
| SEC-APIKEY-008 | 11 | 11 asserted | none | 8 | verified |
| SEC-APIKEY-009 | 7 | 6 asserted, AC-4 gap closed | `SEC_APIKEY_009_AC4_aStoredWriteScopeIsIgnoredToo` | 6 | verified |
| SEC-APIKEY-010 | 6 | 5 asserted, AC-5 gap closed | `race` helper in `Wave8ApiKeysTest` strengthened (no new test) | 6 | verified |
| SEC-APIKEY-011 | 8 | 8 asserted | none | 9 | verified |

## What each added test closes

- **REL-APIKEY-003 AC-7:** the old test checked the 401 status only. The new test also asserts the body code `invalid_api_key`.
- **REL-APIKEY-004 AC-6:** the old test showed the marker was cleared after the keys went, but not that this happened under the owner lock. The new test records the lock state at the key deletion and at the row deletion, and shows one lock acquisition covers both.
- **REL-APIKEY-004 AC-8:** the old test showed K gone and the marker cleared, but not the argument. The new test asserts `deleteFutureByOwner(u, 5)` on all three retry paths, and that the call came while ver was still 6.
- **SEC-APIKEY-001 AC-5:** the old test asserted 200 on the public path, not that the request stayed anonymous. The new test asserts `anon`, with a control showing the same key alone gives `user`.
- **SEC-APIKEY-002 AC-3:** the old test asserted 400 with `.permit` only. The new test asserts the code `invalid_request`, with and without `.permit`.
- **SEC-APIKEY-003 AC-2, AC-3:** this was the weakest assertion in the batch. The old test checked `row.toString()`, which is redacted by design (`StoredApiKey.java:135`), so it could not show that no field holds the secret. The admin list endpoint was checked only for the text `secretHash`. The new test reads every getter of `StoredApiKey` and `ApiKey`, checks the field names and values of both list endpoints, and compares the hash with a SHA-256 computed in the test.
- **SEC-APIKEY-004 AC-10:** the old test showed no insert. The new test adds no read of the body's `ownerUserId` and no password hash.
- **SEC-APIKEY-005 AC-2, AC-3, AC-10:** the old tests asserted the status at these points, not the body codes. The new test asserts them.
- **SEC-APIKEY-009 AC-4:** the old test used `auth-users:read`, and the AC names `auth-users:write`. The new test uses that scope on a write route, with a control showing the same write succeeds under the opt-in.
- **SEC-APIKEY-010 AC-5:** the `race` helper asserted the code `api_key_limit`, not the status. It now also asserts 409.

## Limits of this evidence

- **SEC-APIKEY-004 AC-5, AC-6 and SEC-APIKEY-005 AC-7:** I read "limited to once per minute process-wide" as applying to the public-path log, as the SEC-APIKEY-004 rationale puts it. The protected and public paths use separate limiters (`Auth.java:2262` and `Auth.java:2565`), so one failure of each within a minute logs two ERRORs, and the existing tests expect that. Only the public-path limit is asserted. The limiters are fields of `Auth`, so "process-wide" holds per instance, and a second `Auth` in one process would log on its own.
- **SEC-APIKEY-004 AC-1:** "the same 401" is asserted as status and body code. The message text differs on purpose for an expired key (OPS-AUTH-024 AC-9).
- **SEC-APIKEY-004 AC-9:** the hook counts comparisons. That the absent-prefix case compares against the dummy hash was read in the code (`ApiKeyCredentials.java:137`), not asserted.
- **SEC-APIKEY-006 AC-1, AC-3, AC-4:** the tests use ver 0 and 1, not the literal 5 and 6, and the file store's rollback is simulated by a forwarding store.
- **SEC-APIKEY-007 AC-4:** the key id in audit is asserted on one event type, `GRANT_DENIED`.
- **SEC-APIKEY-005 AC-8:** one test covers throttling and lockout together: the login gets 429 and the key still works. It does not tell the two apart.
- **REL-APIKEY-002 AC-4:** the method named for it shows the deletion runs on another thread. It would still pass if the request waited for the deletion, up to its 10 s latch. "Without waiting" is shown by `SEC_APIKEY_006_AC1_aStaleKeySchedulesItsDeletion`, where the 401 arrives with the job still queued, so both are cited.
- **REL-APIKEY-004 AC-8 (a):** the assertions call `cleanupRun()` directly. The scheduler itself is covered by FR-TOKEN-002 AC-5.
- **REL-APIKEY-004 AC-6:** the marker removal itself has no spy. The test brackets it between two checks inside one lock hold, and `ApiKeyOwners.java:334-343` was read.
- **SEC-APIKEY-011 AC-3, AC-5:** "no later than `userCacheTtl`" is checked by a tick at exactly 5 s on a fixed clock.
- **Evidence notes:** rows say `./gradlew build`, as the format requires. The actual run also passed `-PtinyHttpdFromMaven=true --continue --console=plain --offline`.
- **Stale Trace Links notes:** SEC-APIKEY-003 still says `not yet wired to auth.apiKeys()` on its `ApiKeyFormat.java` row. This batch edited evidence rows only.

## Run results

| Check | Result |
|---|---|
| `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline` | exit 0, no flake |
| Summed `build/test-results` | 3638 tests, 0 failures, 0 errors, 0 skipped (3629 before, plus 9 added) |
| Evidence references | 93 rows in the 14 requirements checked by script: every file and method exists, every AC has a row, every note has the required form |
| `check_acceptance_criteria`, `update_status` | 14 of 14 accepted, no guard refusal |
| `speckiwi coverage --tests --target 0.11.0 --json` | no gap for any batch id and no uncited batch AC. The overall `passed` is false because of 4 gaps outside this batch: FR-AUTH-010 AC-6, FR-IAM-016 AC-6, OPS-AUTH-005 AC-6, OPS-AUTH-007 AC-8 |
| `validate_spec` | 0 errors, 47 `SRS-W015` warnings (Completed Work Log rows for requirements not yet completed; the count is unchanged by this batch) |
