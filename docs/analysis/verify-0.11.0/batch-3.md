# 0.11.0 verification, batch 3

19 of 20 requirements moved to `verified`. FR-APIKEY-006 stays `implemented` because the `UserStore` Javadoc does not say what its AC-6 requires. All 109 ACs were read against the test body, not against a citation or evidence row. 10 tests were added to close partial assertions. No main source and no `docs/api` file changed.

## PRODUCT-GAP

### FR-APIKEY-006 AC-6: the UserStore Javadoc says nothing about the service-account flag

- **AC text:** "A service account created through `auth.users().create` with the flag round-trips `isServiceAccount()` true through `InMemoryUserStore` and `FileUserStore`, and the `UserStore` Javadoc states that an app-provided store must persist the service-account flag (Javadoc test)."
- **Where:** `NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/user/UserStore.java:7-30` (type Javadoc). A case-insensitive search of all 105 lines for `service`, `persist` and `flag` finds only the enabled flag at line 69.
- **What the code does instead:** the type Javadoc lists its store contract (ids, ver, duplicates, consistency, normalized username) and leaves this rule out. The rule appears only indirectly, as a `service_account` column in the example table at `docs/api/en/auth.md:721`. `AuthUser.isServiceAccount()` has a Javadoc (`AuthUser.java:107`), but it does not say a store must persist the flag.
- **Test:** no Javadoc test exists, and one cannot pass without a change to main sources. None was added, because a failing test would break the build.
- **The other half is met:** the round trip is asserted by `Wave9KeyOwnersTest#FR_APIKEY_006_AC6_theFlagIsPersisted`. That test reads back from the same `FileUserStore` instance; `FileUserStoreTest#FR_IAM_002_AC2_rowsSurviveANewInstance` covers a reopen at store level.
- **Fix:** add one line to the `UserStore` contract list, add the Javadoc test, then tick the ACs and verify. AC-1 to AC-5 and AC-7 are asserted, but their boxes stay unticked because this batch ticks only requirements it verifies.

## EXPIRED-AC

None.

## Results

"Rows" is the final number of evidence rows. Before this batch most rows named a whole test file and listed several ACs, so each row was rewritten to one real method with the note `command: ./gradlew build; result: pass`.

| Requirement | ACs read | Verdict | Tests added | Rows | Status |
|---|---|---|---|---|---|
| CON-APIKEY-001 | 4 | 4 asserted | none | 2 | verified |
| DR-APIKEY-001 | 6 | 6 asserted | none | 6 | verified |
| FR-APIKEY-001 | 5 | 5 asserted | none | 3 | verified |
| FR-APIKEY-002 | 5 | 5 asserted | none | 2 | verified |
| FR-APIKEY-003 | 10 | 9 asserted, AC-8 gap closed | `FR_APIKEY_003_AC8_theOneVerifyRunsAtTheTargetCount` | 11 | verified |
| FR-APIKEY-004 | 10 | 9 asserted, AC-1 gap closed | `FR_APIKEY_004_AC1_aDisabledOwnersKeysKeepAStatus` | 11 | verified |
| FR-APIKEY-005 | 7 | 5 asserted, AC-5 and AC-7 gaps closed | `FR_APIKEY_005_AC5_AC7_refusalsAreCoreHttpExceptions` | 8 | verified |
| FR-APIKEY-006 | 7 | 6 asserted, AC-6 PRODUCT-GAP | none | 7 | implemented |
| FR-APIKEY-007 | 4 | 4 asserted | none | 3 | verified |
| FR-APIKEY-008 | 2 | 2 asserted | none | 1 | verified |
| FR-APIKEY-009 | 6 | 5 asserted, AC-1 gap closed | `FR_APIKEY_009_AC1_nullFieldsAndAnAbsentPrefix` | 7 | verified |
| FR-APIKEY-010 | 5 | 5 asserted | none | 3 | verified |
| IR-APIKEY-001 | 1 | 1 asserted | none | 1 | verified |
| IR-APIKEY-002 | 8 | 8 asserted | none | 5 | verified |
| IR-APIKEY-003 | 6 | 3 asserted, AC-1, AC-3 and AC-4 gaps closed | `IR_APIKEY_003_AC1_eachKeyGetsItsOwnStatus`, `IR_APIKEY_003_AC3_AC4_theRouteFollowsTheCodecAndTheEndpointDoesNot` | 6 | verified |
| IR-APIKEY-004 | 7 | 7 asserted | none | 8 | verified |
| IR-APIKEY-005 | 1 | 1 asserted | none | 1 | verified |
| OPS-APIKEY-001 | 7 | 7 asserted | none | 5 | verified |
| PERF-APIKEY-001 | 4 | 2 asserted, AC-2 and AC-4 gaps closed | `PERF_APIKEY_001_AC2_theSecondUpdateComesAtSixtySeconds`, `PERF_APIKEY_001_AC4_theResponseIsSentWhileTheUpdateIsBlocked` | 3 | verified |
| REL-APIKEY-001 | 4 | 2 asserted, AC-2 and AC-4 gaps closed | `REL_APIKEY_001_AC2_aRevokedKeyIsInvalidAtOnce`, `REL_APIKEY_001_AC4_theActingNodeAnswersInvalidApiKey` | 4 | verified |

Three rows carried stale claims and were rewritten: IR-APIKEY-004 VE-2 and FR-APIKEY-010 VE-1 named a SQLite `JdbcApiKeyStore`, and FR-APIKEY-009 VE-1 named `jdbc/JdbcApiKeyStoreTest`. None of these exists any more.

## What each added test closes

- **REL-APIKEY-001 AC-2, AC-4:** the old tests checked 401 after `revoke` and on the acting node, but not the body code `invalid_api_key`.
- **IR-APIKEY-003 AC-1:** the old test checked that `active` and `stale` both appear somewhere in the body, so swapped statuses would have passed. The new test matches each status to its key id, at ver 5 and 6 as the AC states.
- **IR-APIKEY-003 AC-3, AC-4:** AC-3 ran on a test codec that never wrote `ACTIVE`, and AC-4 called the codec directly, not `res.json`. The new test runs one app on the Gson codec: a route writes `ACTIVE` and `GET /auth/me/apikeys` writes `active`.
- **PERF-APIKEY-001 AC-2:** the old request came about 119 s after the last update. The exact 60 s edge was asserted only on `ApiKeyUsage` directly. The new test uses real key requests at 59.999 s and 60 s.
- **PERF-APIKEY-001 AC-4:** the old test only bounded the response time. The new test holds the update blocked and shows the response finished while the update had started and not been released, so it needs no timing bound.
- **FR-APIKEY-003 AC-8:** verifies were counted, but nothing showed the iteration count. The new test records the stored string of each verify on both issuers, the dummy for a null hash included, and checks it runs at 1000 iterations.
- **FR-APIKEY-004 AC-1:** for a disabled owner only the list size was asserted. The new test asserts the status too. It accepts any of the three values, because the AC names no specific one.
- **FR-APIKEY-005 AC-5, AC-7:** only the 400 and the code were asserted, so nothing showed the exception type. The new test asserts `HttpException` with status 400 and not `AuthRejection`.
- **FR-APIKEY-009 AC-1:** the contract checks null round trips and absent prefixes, but no broken store proved those checks fire. Four new broken variants do.

## Limits of this evidence

- **FR-APIKEY-005 AC-5, AC-7:** the exception type is asserted at `KeyIssuance.spec`, the check both issuers call, not on the HTTP path. The auth router's own error layer answers these 400s, so `app.onError` never sees them. My first version of the test watched `app.onError` and failed for that reason. That was a test bug, not a product gap. The 400 answers themselves are asserted over HTTP by `_bodyChecks` and `_grammarBeforeOwnerAndPassword`.
- **FR-APIKEY-001 AC-1:** "no store lookup" holds trivially, because the auth under test was built without the store. The test shows no lookup; it does not show a lookup being suppressed.
- **FR-APIKEY-002 AC-5:** the one-run assertions call `cleanupRun()` directly. A real scheduler is shown calling `deleteExpired` at a 20 ms interval. The 10-minute default is covered by FR-TOKEN-002 AC-5 in batch 2.
- **REL-APIKEY-001 AC-4:** node B is checked once, at exactly 5 s on its clock. Nothing asserts how it answers inside the window, and the AC does not require it.
- **PERF-APIKEY-001 AC-4:** the store blocks on a latch, not for a literal 5 seconds.
- **IR-APIKEY-003 AC-6:** the docs test matches "still counts toward" text. That the same sentence names `MULTI_INSTANCE` was read by hand (`ApiKeyStatus.java:8-9`).
- **IR-APIKEY-002 AC-8, OPS-APIKEY-001 AC-6:** Javadoc checks match source text, not rendered docs.
- **FR-APIKEY-006 AC-1, AC-7:** "same dummy-hash time" is shown by an equal verify count, not by a clock. The audit mark is asserted on one event type, `GRANT_DENIED`.
- **Evidence notes:** rows say `./gradlew build`, as the format requires. The actual run also passed `-PtinyHttpdFromMaven=true --continue --console=plain --offline`.
- **Stale Trace Links notes:** OPS-APIKEY-001 still says `JdbcApiKeyStore` on its REL-APIKEY-005 row, and PERF-APIKEY-001 still says "request-path caller arrives with FR-APIKEY-001". This batch edited evidence rows only, and `edit_requirement_table_rows` now refuses verified requirements.

## Run results

| Check | Result |
|---|---|
| `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline` | exit 0, no flake |
| Summed `build/test-results` | 3629 tests, 0 failures, 0 errors, 0 skipped (3619 before, plus 10 added) |
| Evidence references | 97 rows in the 20 requirements checked by script: every file and method exists, every AC has a row, every note has the required form |
| `speckiwi coverage --tests --target 0.11.0` | No gap for any batch id. `passed` is false because of the same 4 gaps outside this batch: FR-AUTH-010 AC-6, FR-IAM-016 AC-6, OPS-AUTH-005 AC-6, OPS-AUTH-007 AC-8 |
| Citations in this checkout | All 109 batch ACs are cited outside `.claude/worktrees` |
| `validate_spec` | 0 errors, 47 SRS-W015 warnings (the same count as before this batch) |
| Guard refusals on `update_status` | none |
| Main sources and `docs/api` | unchanged |
