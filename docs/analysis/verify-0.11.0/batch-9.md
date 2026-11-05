# 0.11.0 verification, batch 9

20 of the 22 requirements moved to `verified`. FR-AUTH-005 and FR-AUTH-009 stay `implemented`, each for one open AC described below. All 127 ACs were read against the test body, not against a citation or evidence row. 10 tests were added and 3 existing tests were strengthened. No main source and no `docs/api` file changed.

Build, status and coverage results are under "Results of the run".

## PRODUCT-GAP

### FR-AUTH-009 AC-2 (left `implemented`)

AC text: "A recording AuditSink set on the builder receives events for: recovery use, self-protection grant denial, security_write_conflict, and API key issue and revoke (carrying the key id and issuedByUserId). Every audit event raised during a request authenticated by an API key (for example an admin write by a key actor, or a key revoke) carries that key id, and every event whose actor is a service account marks it as such; an ordinary API-key request that triggers no audited action produces no audit event."

What the code does instead: an `api_key_revoked` event carries `revokedApiKeyId` and, for a key actor, `apiKeyId`, but never `issuedByUserId`.

- `NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/DefaultAuthApiKeys.java:287` (`revokedThrough`, the endpoint path) sets only `revokedApiKeyId`.
- `DefaultAuthApiKeys.java:260` (`auth.apiKeys().revoke`) does the same, and also has no actor.
- `KeyIssuance.java:314` is the issue path. It does carry `issuedByUserId`.

Read literally, the parenthesis covers both issue and revoke, so the AC is not met. The wording may be loose rather than the code wrong: `docs/api/auth.md:1994` and 00 §6.4 row "R47 × 발급자 기록" put `issuedByUserId` on the issue event only. One of these has to change: add the detail to the revoke event, or narrow the AC. I could not do either here. The other AC-2 clauses are all asserted, and every other AC of this requirement is too.

## EXPIRED-AC

### FR-AUTH-005 AC-7, second half (left `implemented`)

AC text: "installing a second Auth on an app that already has one still throws the before-routes IllegalStateException (it sees the ROUTER_MOUNT of the mount path, a route-family registration)."

Replaced by OPS-AUTH-024 AC-7: with no app route before it, `auth.install(app)` now throws `another Auth is already installed on this app`; the route-order message stays only when an app route precedes (`Auth.java:590-611`). `JuniorRound12Test#OPS_AUTH_024_AC7_secondAuthIsNamed` asserts the new message. `AuthInstallTest#FR_AUTH_005_AC7_secondInstallThrows` only catches `IllegalStateException` for this case, so it proves neither message. The first half of AC-7 (the same Auth twice) is asserted with its exact message. The old evidence note claiming the before-routes error was corrected.

## Results

"Rows" is the final number of evidence rows. Before this batch most rows named a whole test file for several ACs, so each was rewritten to a real method with the note `command: ./gradlew build; result: pass`.

| Requirement | ACs read | Verdict | Tests added or strengthened | Rows |
|---|---|---|---|---|
| CON-AUTH-001 | 5 | 5 asserted | none; a row pointing at a missing 0.10.0 jar replaced | 5 |
| CON-AUTH-002 | 4 | 4 asserted | none | 4 |
| CON-AUTH-003 | 4 | 4 asserted | none; a stale row claiming java.sql references replaced | 5 |
| IR-AUTH-001 | 4 | 4 asserted | none | 5 |
| CON-AUTH-004 | 6 | 5 asserted, AC-2 gap closed | strengthened `CON_AUTH_004_AC2_nullabilityOfTheAuthPackages` | 8 |
| CON-AUTH-005 | 6 | 6 asserted | none | 7 |
| CON-AUTH-006 | 5 | 5 asserted | none | 6 |
| CON-AUTH-007 | 4 | 4 asserted; AC-3 re-run on Java 8 | none | 4 |
| FR-AUTH-001 | 5 | 5 asserted | none | 6 |
| FR-AUTH-002 | 5 | 3 asserted, AC-1 and AC-2 gaps closed | added `FR_AUTH_002_AC1_theEnvValueIsTheSigningKey`, `FR_AUTH_002_AC2_theFileContentIsTheSigningKey`, `FR_AUTH_002_AC2_anUnreadableFileIsNamed` | 8 |
| FR-AUTH-003 | 6 | 5 asserted, AC-4 gap closed | added `FR_AUTH_003_AC4_aForwardedForHeaderAloneWarnsOnce` | 8 |
| FR-AUTH-004 | 7 | 6 asserted, AC-7 gap closed | added `FR_AUTH_004_AC7_aRefusedSeedPasswordIsNotHashed`, `FR_AUTH_004_AC7_seedPasswordsSkipTheBreachChecker` | 9 |
| FR-AUTH-005 | 8 | 7 asserted, AC-7 half expired; left `implemented` | none | 8 |
| FR-AUTH-006 | 7 | 7 asserted | none | 9 |
| IR-AUTH-002 | 6 | 6 asserted | none | 7 |
| IR-AUTH-003 | 6 | 5 asserted, AC-4 gap closed | strengthened `throttleFailureBeforeTheHashIs503` | 12 |
| FR-AUTH-007 | 5 | 5 asserted | none | 5 |
| FR-AUTH-008 | 5 | 4 asserted, AC-3 gap closed | added `FR_AUTH_008_AC3_recoveryReportsTheChangedSubset` | 5 |
| IR-AUTH-004 | 8 | 7 asserted, AC-2 gap closed | added `IR_AUTH_004_AC2_storeErrorsAreLoggedAndAGuardPromotionIsKept` | 12 |
| FR-AUTH-009 | 7 | 6 asserted, AC-2 PRODUCT-GAP; left `implemented` | added `FR_AUTH_009_AC2_anOrdinaryKeyRequestIsNotAudited` | 12 |
| IR-AUTH-005 | 7 | 7 asserted | none | 7 |
| FR-AUTH-010 | 7 | 5 asserted, AC-4 and AC-6 gaps closed | strengthened `FR_AUTH_010_AC4_uninstalledAuthFailsLoudly`; added `FR_AUTH_010_AC6_useAuthIsTheDocumentedWayToAttach` | 7 |

## What each change closes

- **CON-AUTH-004 AC-2:** the AC names five nullable positions. `Auth.userOrNull`, `Auth.loadUser` and `AuthPrincipal.getApiKeyId` were not asserted in this test. They are now, and `Auth.user` is asserted non-null.
- **FR-AUTH-002 AC-1, AC-2:** the old tests only showed that `build()` succeeded, so a wrong key of good strength would have passed. The new tests sign a token through the reference and verify it under an Auth built from the literal value, and show that another key refuses it. The unreadable-file case was missing; it uses a directory path.
- **FR-AUTH-003 AC-4:** the first forwarded request in the old test carried `X-Forwarded-Proto`. `X-Forwarded-For` alone was not shown to trigger the WARN.
- **FR-AUTH-004 AC-7:** "hashes nothing" and "the BreachedPasswordChecker is not consulted" were not asserted.
- **FR-AUTH-008 AC-3:** recovery was shown only with all three fields changed. The new test shows the subsets {PASSWORD}, {ENABLED, PASSWORD} and {PASSWORD, ROLES}. The A4 "only the applied fields" half rests on `AdminEndpointsTest#FR_IAM_014_AC6_securityWriteAfterPassword`.
- **FR-AUTH-009 AC-2:** "an ordinary API-key request produces no audit event" had no test. The other clauses were asserted by tests the evidence table did not name, and those rows were added.
- **FR-AUTH-010 AC-4:** a router attached without a prefix was missing; only a prefixed one was tested.
- **FR-AUTH-010 AC-6:** this AC had a doc row and no test, which is the gap earlier batches saw in coverage.
- **IR-AUTH-003 AC-4:** the throttle 503 asserted only the status, not `SERVICE_UNAVAILABLE`.
- **IR-AUTH-004 AC-2:** "store errors are logged" and the "after a guard promoted it" case were not asserted.

## Results of the run

- **Build:** `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline` exited 0. Summed over every module: 3695 tests, 0 failures, 0 errors, 0 skipped. No flake and no rerun.
- **Second build, beyond the brief:** CON-AUTH-007 AC-3 needs an actual Java 8 run, and the recorded one (2026-11-03) predates main-source commit 42e4f8c of 2026-11-05. So the build was run once more with `--rerun-tasks -I gradle/java8-tests.gradle` on Zulu 8.96.0.205: exit 0, 11 test tasks forked on Java 8, 3695 tests, 0 failures, 0 errors, 0 skipped. The `build/` test results on disk are from this run.
- **Status:** the 20 requirements had every AC ticked and moved to `verified`, one at a time. No guard refused.
- **Evidence:** a script confirmed that all 159 rows of the batch name an existing file holding the named method, and that all 127 ACs have a row.
- **Coverage:** `speckiwi coverage --tests --target 0.11.0 --json` shows no gap for the batch ids. The target as a whole still reports gaps for OPS-AUTH-005 AC-6 and OPS-AUTH-007 AC-8, both outside this batch.
- **Validation:** `validate_spec` reports 0 errors and 47 SRS-W015 warnings, the same count as before this batch.

## Notes

- CON-AUTH-007 AC-3 also says "no previously verified test removed or weakened". This batch changed no test that way, but it did not audit the whole branch history for that clause.
- CON-AUTH-001 AC-5: the completed-work record still holds the wave-12 size (403660 -> 1016604 bytes). The jar of this build is 1027265 bytes. The VE-2 note quotes 1002307, the 2026-11-03 measurement from the old VE-4 row, not the record, and a verified row can no longer be edited. The release checklist asks for a re-measure on the release commit.
- FR-AUTH-010's statement still lists "a prefix use" as a 500 case. AC-7 replaced that with an IllegalArgumentException at registration, and the ACs are what was verified.
- `auth.apiKeys().revoke` (app code, no request) emits no actor, which is consistent with having no request.
