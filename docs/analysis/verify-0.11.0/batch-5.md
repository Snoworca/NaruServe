# 0.11.0 verification, batch 5

All 17 requirements moved to `verified`. All 107 ACs were read against the test body, not against a citation or evidence row. 96 were already asserted. The other 11, across 9 requirements, were only partly asserted, and 11 added tests close them. No main source and no `docs/api` file changed.

Build, status and coverage results are under "Results of the run".

## PRODUCT-GAP

None. Every AC is met by the code. The 11 partial ACs were all gaps in the tests, and each now has an assertion that passes without a change to main sources or `docs/api`.

## EXPIRED-AC

None. CON-IAM-003 replaced the JDBC rules on purpose, and the ACs of this batch were already reworded for that (FR-IAM-001 AC-2, FR-IAM-004 AC-1, IR-IAM-001 AC-6).

## Results

"Rows" is the final number of evidence rows. Before this batch most rows named a whole test file or several files in one cell, so each was rewritten to one real method with the note `command: ./gradlew build; result: pass`.

| Requirement | ACs read | Verdict | Tests added | Rows |
|---|---|---|---|---|
| CON-IAM-001 | 5 | 4 asserted, AC-3 gap closed | `CON-IAM-001 AC-3 Kotlin ForwardingUserStore passes verifyUserStore` | 7 |
| CON-IAM-003 | 4 | 4 asserted | none | 4 |
| DR-IAM-001 | 8 | 8 asserted | none | 10 |
| DR-IAM-002 | 5 | 5 asserted | none | 6 |
| FR-IAM-001 | 5 | 4 asserted, AC-1 gap closed | `FR_IAM_001_AC1_duplicateUserExceptionIsUnchecked` | 6 |
| FR-IAM-002 | 6 | 6 asserted | none | 6 |
| FR-IAM-003 | 7 | 5 asserted, AC-1 and AC-2 gaps closed | `FR_IAM_003_AC1_theOtherLookupsReachTheStoreNormalized`, `FR_IAM_003_AC2_noFormatCheckOnTheLoginName` | 8 |
| FR-IAM-004 | 6 | 6 asserted | none | 6 |
| FR-IAM-005 | 6 | 6 asserted | none | 6 |
| FR-IAM-006 | 6 | 6 asserted | none | 6 |
| FR-IAM-007 | 6 | 5 asserted, AC-4 gap closed | `FR_IAM_007_AC4_tooManyGroupsAreAJsonError` | 7 |
| FR-IAM-008 | 5 | 5 asserted | none | 4 |
| FR-IAM-009 | 5 | 4 asserted, AC-5 gap closed | `FR_IAM_009_AC5_theGrantRuleUsesTheBothCoverPredicate` | 6 |
| FR-IAM-014 | 11 | 9 asserted, AC-6 and AC-10 gaps closed | `FR_IAM_014_AC6_setEnabledAfterPassword`, `FR_IAM_014_AC10_appliedSitsBesideTheErrorObject` | 13 |
| FR-IAM-015 | 10 | 9 asserted, AC-2 gap closed | `FR_IAM_015_AC2_theDeviceTrustFileKeepsItsFormat` | 11 |
| FR-IAM-016 | 6 | 5 asserted, AC-4 gap closed | `FR_IAM_016_AC4_eachRoleIsALineOfTheFile` | 8 |
| IR-IAM-001 | 6 | 5 asserted, AC-5 gap closed | `IR_IAM_001_AC5_storeExceptionOnAnAuthEndpointIs503` | 10 |

## What each added test closes

- **CON-IAM-001 AC-3:** the old test exercised a few methods by hand. The new test runs `AuthTesting.verifyUserStore` on the Kotlin `ForwardingUserStore` subclass. The Kotlin `GroupStore` keeps its hand checks, because no `verifyGroupStore` exists.
- **FR-IAM-001 AC-1:** the 409 was asserted, but nothing asserted that the exception is unchecked. It extends `HttpException`, so a test reading only the route could not show it.
- **FR-IAM-003 AC-1:** only login and the facade's result were asserted. "Every auth lookup" now also has the store-side argument for `auth.users().findByUsername` and A2.
- **FR-IAM-003 AC-2:** the old test used `bob+ci@example.com`, with no digits and not the AC's literal. The new test uses `bob@example.com`, `0107+1@example.com` and `12345`.
- **FR-IAM-007 AC-4:** the AC says "the same way" as AC-3, which is a JSON error. The old test asserted the status only.
- **FR-IAM-009 AC-5:** this was the weakest assertion in the batch. The guards were covered, but the R31 side was covered only by `keyActorScopesInTheGrantRule`, whose grant case is a refusal with no allowed control, so it could not tell the predicate from a blanket key refusal. `FR_IAM_009_AC5_noWildcardSetIntersection` only searches for `retainAll(`. The new test runs the three AC-3 and AC-4 owner and key pairs through A4 grants, with an allowed 200 and a refused 403 for each. The refusals include one where only the owner lacks the scope and one where only the key lacks it.
- **FR-IAM-014 AC-6:** the AC names `setEnabled or setRoles`, and only `setRoles` was tested.
- **FR-IAM-014 AC-10:** `applied` was asserted, but not that it sits beside an error object holding code, message and requestId. The new test asserts the exact key sets, for a 409 and a 503.
- **FR-IAM-015 AC-2:** the old test checked the format of the refresh and throttle files. The device-trust file, the third file the AC names, was not checked.
- **FR-IAM-016 AC-4:** the old test checked that the file contains `editor`. The AC says each role is a line.
- **IR-IAM-001 AC-5:** the protected path was asserted, and "an auth endpoint" was not. The new test makes `findByUsername` throw under `POST /auth/login`, and asserts 503 JSON, the Core shape and no leaked exception text.

## Rows fixed

- **Stale:** FR-IAM-001 VE-2 named `AuthJdbcDefaultsTest.java`, which no longer exists. It now names the AC-4 test, and AC-2 stays on VE-4.
- **Stale notes:** IR-IAM-001 VE-2 described JDBC stores on a file-only row that VE-3 had already replaced, so it now names the Kotlin AC-1 and AC-2 test.
- **Multi-file cells:** DR-IAM-001 VE-1, DR-IAM-002 VE-1 and FR-IAM-015 VE-1 each held several files in one cell.
- **Doc row:** FR-IAM-016 VE-4 was type `doc` with no test behind it. AC-6 is now on two docs tests: `OPS_AUTH_009_AC1_groupExampleNamesTheCacheTtl` (the Java `grantRole` example in both languages) and `OPS_AUTH_007_AC3_groupWritesReachSignedInUsers` (the cache sentence in both languages). Both already asserted the AC but did not cite it, which `speckiwi coverage` reported as a gap, so each gained a `FR-IAM-016 AC-6` comment.

## Results of the run

- **Build:** `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline` exited 0. Summed over every module: 3649 tests, 0 failures, 0 errors, 0 skipped.
- **After that build:** the two `FR-IAM-016 AC-6` comments were added, with no code change. The two docs test classes were rerun alone and passed (7 and 13 tests, 0 failures). The full build was not repeated, so `build/test-results/test` in the root module now holds that filtered run. Rerun the build before summing test counts from it.
- **Status:** 17 of 17 `verified`, one requirement at a time. There were no guard refusals.
- **Coverage:** `speckiwi coverage --tests --target 0.11.0 --json` shows no gap for this batch. It still reports `passed: false` for three requirements outside it: FR-AUTH-010 AC-6, OPS-AUTH-005 AC-6 and OPS-AUTH-007 AC-8.
- **Validation:** `validate_spec` reports 0 errors and 47 SRS-W015 warnings, the same count as before this batch.

## Limits of this evidence

- **No mutation run:** the task forbids changing main sources, so no new test was proven by breaking the product. The new FR-IAM-009 AC-5 test discriminates by construction, as described above.
- **FR-IAM-005 AC-5:** "at most 3 hash lookups" is asserted on `ScopeMatcher.allows` over one set. An API-key actor checks two sets (`AuthPrincipal.java:102` and `:113`), so that path can do up to 6 lookups. I read the AC as per set, because the both-cover predicate of FR-IAM-009 needs both sets.
- **FR-IAM-006 AC-5:** "no matter how many requests" is asserted with 5 calls on one `EffectiveGrants`, not over HTTP. `Auth.java:411` builds exactly one per Auth, which is what makes that equivalent.
- **FR-IAM-003 AC-1:** the new test adds the facade and A2. The other lookups were read in the code, not asserted: A4 (`AdminEndpoints.java:669`), register and setup (`AccountEndpoints.java:963`, `:1041`, `:1101`), bootstrap seeds (`Auth.java:4577`, `:4600`) and `AuthTestingOps.java:34`.
- **FR-IAM-009 AC-3, AC-4, AC-5:** key actors come from the `TestCredentials` resolver, so these tests exercise the predicate rather than real key lookup. Real keys are covered by the SEC-APIKEY tests.
- **FR-IAM-009 AC-5:** "no code path computes a set intersection" is a search for `retainAll(` plus my reading of `GrantRule.covers` and `AuthPrincipal.hasScope`. A hand-written loop would not be caught by the search.
- **FR-IAM-009 AC-5:** in the new test every owner also holds `auth-users:write` and every key names it, because the A4 guard needs that scope before the grant rule runs. The pairs under test are the posts scopes of AC-3 and AC-4.
- **DR-IAM-002 AC-2:** "auth's write paths" is asserted on G2 only. That is the one path that creates a group id. Other paths take an id that must already exist.
- **FR-IAM-014 AC-6:** "after its 3 retries" is not counted here. REL-IAM-001 AC-2 owns that count.
- **FR-IAM-015 AC-6:** "an older reader" is modelled by giving this release a version 3 file, as no version-1-only reader exists to run.
- **FR-IAM-015 AC-9:** the test catches `IllegalArgumentException` from `fromEnv()`, so a malformed `NARU_AUTH_FILE_KEY` on the machine cannot fail it, and it uses `key ?: fileKey` for the build.
- **CON-IAM-001 AC-1:** Kotlin does not warn on `T!`, so "no platform type" rests on the `@NullMarked` reflection check (AC-4), as the test comment says.
- **CON-IAM-001 AC-5:** both tests are indirect: the `':NaruServe-api' : [:]` entry in `build.gradle`, and no `kotlin/` reference in the compiled `utils` classes. The second scans `utils` only, not the whole jar.
- **Evidence notes:** rows say `./gradlew build`, as the format requires. The actual run also passed `-PtinyHttpdFromMaven=true --continue --console=plain --offline`.
