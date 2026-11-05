# 0.11.0 verification, batch 6

All 16 requirements moved to `verified`. All 102 ACs were read against the test body, not against a citation or evidence row. 91 were already asserted. The other 11, across 7 requirements, were only partly asserted, and 11 added tests close them. No main source and no `docs/api` file changed.

Build, status and coverage results are under "Results of the run".

## PRODUCT-GAP

None. Every AC is met by the code. The 11 partial ACs were all gaps in the tests, and each now has an assertion that passes without a change to main sources or `docs/api`.

## EXPIRED-AC

None. REL-IAM-003 AC-5 was already reworded when the built-in JDBC store was removed, and its test follows the new wording. Only the note on evidence row VE-1 still named `JdbcUserStore`, and that note is rewritten.

## Results

"Rows" is the final number of evidence rows. Before this batch most rows named a whole test file, or several files in one cell, so each was rewritten to one real method with the note `command: ./gradlew build; result: pass`.

| Requirement | ACs read | Verdict | Tests added | Rows |
|---|---|---|---|---|
| IR-IAM-002 | 5 | 5 asserted | none | 6 |
| IR-IAM-003 | 6 | 5 asserted, AC-4 gap closed | `IR_IAM_003_AC4_everyMethodIs503ThroughAuthErrors` | 8 |
| IR-IAM-004 | 6 | 3 asserted, AC-4, AC-5 and AC-6 gaps closed | `IR_IAM_004_AC4_g7RefusesCycleAndSelfEdge`, `IR_IAM_004_AC5_g7RefusesADepthAboveEight`, `IR_IAM_004_AC6_fileStorePersistsThroughAtomicFiles` | 9 |
| IR-IAM-005 | 7 | 7 asserted | none | 7 |
| REL-IAM-001 | 7 | 6 asserted, AC-2 gap closed | `REL_IAM_001_AC2_logoutAllUnconfirmedIs503WithOneAudit` | 9 |
| REL-IAM-002 | 8 | 7 asserted, AC-8 gap closed | `REL_IAM_002_AC8_resetEnablesAfterTheHash` | 12 |
| REL-IAM-003 | 5 | 5 asserted | none | 5 |
| REL-IAM-004 | 7 | 7 asserted | none | 7 |
| SEC-IAM-001 | 8 | 8 asserted | none | 9 |
| SEC-IAM-002 | 4 | 4 asserted | none | 4 |
| SEC-IAM-003 | 5 | 5 asserted | none | 6 |
| SEC-IAM-004 | 8 | 7 asserted, AC-2 gap closed | `SEC_IAM_004_AC2_refusedResetCarriesTheCode` | 9 |
| SEC-IAM-005 | 8 | 8 asserted | none | 8 |
| SEC-IAM-006 | 2 | 2 asserted | none | 2 |
| SEC-IAM-007 | 8 | 5 asserted, AC-3, AC-4 and AC-8 gaps closed | `SEC_IAM_007_AC3_usersListRefusalCarriesTheCode`, `SEC_IAM_007_AC4_adminPassesTheKeyRouteGuards`, `SEC_IAM_007_AC8_keyReaderPostCarriesTheCode` | 11 |
| SEC-IAM-008 | 8 | 7 asserted, AC-7 gap closed | `SEC_IAM_008_AC7_fromEnvReadsTheNamedVariable` | 14 |

## What each added test closes

- **IR-IAM-003 AC-4:** the type was asserted for seven calls. The init overload of `create` was not among them, and nothing asserted "503 through auth.errors()". The new test runs all eight methods in app routes with `auth.errors()` as the error handler. Auth is not installed there, because the start-up doctor correctly refuses a store that is already broken.
- **IR-IAM-004 AC-4 and AC-5:** the 409 was shown on a stand-in route that called `GroupEdges.addParent`, written before the admin endpoints existed. The new tests go through the real G7 `PUT /auth/admin/groups/:id/parents/:parentId`.
- **IR-IAM-004 AC-6:** the reopen was asserted, but not that the file goes through AtomicFiles. The new test checks that a write leaves only the main file, and that a lone `.bak` is restored with one WARN.
- **REL-IAM-001 AC-2:** the old test used a stand-in `POST /auth/logout-all` route. The AC names `logout ?all=true`. The new test uses the real endpoint with a five-method store whose update does not apply. The A4 half was already in `FR_IAM_014_AC6_securityWriteAfterPassword`, which now cites this AC.
- **REL-IAM-002 AC-8:** the outcome was asserted, but not "and then enables" or the store-level sentence. The new test records that `changePassword` runs while the user is still disabled and `setEnabled` follows, and calls `changePassword` directly on a disabled user.
- **SEC-IAM-004 AC-2:** the password reset row asserted 403 and not the code `insufficient_scope`.
- **SEC-IAM-007 AC-3 and AC-8:** one refusal in each asserted 403 and not the code.
- **SEC-IAM-007 AC-4:** "every admin endpoint" was run over the fifteen routes only. The new test adds the three key routes with keys on.
- **SEC-IAM-008 AC-7:** `fromEnvValue` was tested, but `fromEnv()` was only checked to be null when the variable happened to be unset. The new test runs a child JVM with the variable unset, empty, valid and invalid.

## Citations added to tests that already asserted the AC

These are comments only, on tests whose bodies assert the AC:

- **REL-IAM-001 AC-2:** `AdminEndpointsTest#FR_IAM_014_AC6_securityWriteAfterPassword`.
- **REL-IAM-002 AC-2:** `UserStoreContractTest#IR_IAM_001_AC2_defaultMethods`. The swap after the refused call still expects the unchanged hash and ver, which is what shows that nothing changed.
- **REL-IAM-002 AC-7:** `CarriedAcceptanceTest#IR_AUTH_003_AC6_unconfirmedSecurityWrites`, which fails if the default returns false instead of throwing.
- **IR-IAM-002 AC-5:** `AuthArchitectureTest#CON_AUTH_003_AC3_majorVersion52`.

## Rows fixed

- **Whole-file references:** every requirement in the batch had rows naming a test file with no method. Each is now one method.
- **Multi-file cells:** REL-IAM-004 VE-1 and SEC-IAM-008 VE-1 held several files in one cell.
- **Stale notes:** REL-IAM-003 VE-1 named `JdbcUserStore` and open ACs. REL-IAM-001 VE-1, REL-IAM-002 VE-1, SEC-IAM-003 VE-1 and SEC-IAM-005 VE-1 said ACs were still open or waiting.
- **Wrong covers:** REL-IAM-003 VE-3 listed AC-1, AC-2 and AC-5 on one method that asserts AC-1 only. SEC-IAM-008 VE-2 listed AC-2 on a method that asserts AC-5 only.

## Results of the run

- **Build:** `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline` exited 0. Summed over every module: 3660 tests, 0 failures, 0 errors, 0 skipped. That is 11 more than batch 5, one for each added test. No test file changed after this build.
- **First run of one test:** `IR_IAM_003_AC4_everyMethodIs503ThroughAuthErrors` failed once, before the full build. It was a bug in the test: it installed Auth over a broken store, and the start-up doctor correctly refused to start. The test now attaches `auth.errors()` without installing Auth.
- **Status:** 16 of 16 `verified`, one requirement at a time. There were no guard refusals. The index moved from 351 to 367 verified and from 111 to 95 implemented.
- **Coverage:** `speckiwi coverage --tests --target 0.11.0 --json` shows no gap for this batch. It still reports `passed: false` for three requirements outside it: FR-AUTH-010 AC-6, OPS-AUTH-005 AC-6 and OPS-AUTH-007 AC-8.
- **Validation:** `validate_spec` reports 0 errors and 47 SRS-W015 warnings, the same count as before this batch.

## Limits of this evidence

- **No mutation run:** the task forbids changing main sources, so no new test was proven by breaking the product.
- **Animal-sniffer ACs (IR-IAM-002 AC-5, REL-IAM-004 AC-5, SEC-IAM-008 AC-6):** the API 21 half is the `animalsnifferMain` task of the build, not a test method. A test asserts the rest of each AC and, for SEC-IAM-008 AC-6, that the signature is configured.
- **IR-IAM-003 AC-4:** the non-store exception types are asserted on a sample of calls, not on every method.
- **REL-IAM-002 AC-5:** "no state exists where the hash changed but ver did not" is asserted by reading the row after the write, not by observing it during the write.
- **REL-IAM-002 AC-8:** "also for an app-provided store" is the default method only. An app's own store is not something the framework can test.
- **REL-IAM-003 AC-1 and AC-2:** the doctor's failure is read from the exception text of the start.
- **REL-IAM-004 AC-2 to AC-4:** asserted on `AtomicFiles` with a fake parser. Only the groups file also has a store-level `.bak` test (added here).
- **SEC-IAM-003 AC-3:** the `.statelessAccess(true)` exception in the AC is owned and tested by SEC-AUTH-007 AC-7, and is not exercised here.
- **SEC-IAM-004 AC-4 and AC-7:** "fixed TimeSource" is the fixture clock moved by 1 second. The default 5 s TTL is not restated in these tests.
- **SEC-IAM-005 AC-2:** the status 409 is asserted, as the AC says. The code `group_too_large` is asserted on the edge path in AC-5 and in the catalog test.
- **SEC-IAM-007 AC-1:** "the handler does not run" is read from the 401 body holding no `items`.
- **SEC-IAM-007 AC-4:** the new test shows the key routes answer neither 401 nor 403 for the admin. It does not assert what each handler then answers, which FR-APIKEY-004 owns.
- **REL-IAM-001 AC-4:** the concurrency is exercised over 6 rounds with a slow update, so it is strong evidence of the lock, not a proof.
- **SEC-IAM-008 AC-6:** the crypto calls are asserted by searching the source of `SecretBox` and `AuthFileKey`. The runtime behaviour is covered by the other SEC-IAM-008 tests.
- **Evidence notes:** rows say `./gradlew build`, as the format requires. The actual run also passed `-PtinyHttpdFromMaven=true --continue --console=plain --offline`.
