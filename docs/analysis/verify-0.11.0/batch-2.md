# 0.11.0 verification, batch 2

All 18 requirements moved to `verified`. Every one of the 119 ACs was read against the test body, not against its citation or evidence row. 12 tests were added to close partial assertions. No main source and no `docs/api` file changed.

## PRODUCT-GAP

None. Every test added for a gap passed against the existing code on its first run.

## EXPIRED-AC

None.

## Results

"Rows" is the final number of evidence rows. Before this batch nearly every row named a whole test file and listed several ACs, so each row was rewritten to one real method with the note `command: ./gradlew build; result: pass`.

| Requirement | ACs read | Verdict | Tests added | Rows | Status |
|---|---|---|---|---|---|
| SEC-TOKEN-001 | 5 | 4 asserted, AC-2 gap closed | `SEC_TOKEN_001_AC2_plusAndSlashAreEachRefused` | 6 | verified |
| SEC-TOKEN-002 | 7 | 6 asserted, AC-4 gap closed | `SEC_TOKEN_002_AC4_deepArrayInAJwtHeaderOrPayloadIsRefused` | 9 | verified |
| SEC-TOKEN-003 | 8 | 7 asserted, AC-3 gap closed | `SEC_TOKEN_003_AC3_forbiddenCharactersInEveryPart` | 9 | verified |
| SEC-TOKEN-004 | 8 | 6 asserted, AC-4 and AC-5 gaps closed | `SEC_TOKEN_004_AC4_emptyEnvironmentVariableIsNamed`, `SEC_TOKEN_004_AC5_freshFilesGetDifferentKeys`, `SEC_TOKEN_004_AC5_SEC_TOKEN_007_AC2_keyAndSaltBytesComeFromSecureRandom` | 12 | verified |
| SEC-TOKEN-005 | 6 | 6 asserted | none | 7 | verified |
| SEC-TOKEN-006 | 6 | 6 asserted | none | 6 | verified |
| SEC-TOKEN-007 | 6 | 5 asserted, AC-2 gap closed | shares the SecureRandom test above | 8 | verified |
| SEC-TOKEN-008 | 7 | 7 asserted | none | 7 | verified |
| SEC-TOKEN-009 | 8 | 8 asserted | none | 9 | verified |
| FR-TOKEN-001 | 7 | 7 asserted | none | 7 | verified |
| FR-TOKEN-002 | 8 | 6 asserted, AC-5 and AC-7 gaps closed | `FR_TOKEN_002_AC5_defaultIntervalIsTenMinutes`, `FR_TOKEN_002_AC7_tokenIssuedBeforeARestartStillRefreshes` | 10 | verified |
| FR-TOKEN-003 | 7 | 6 asserted, AC-3 gap closed | `FR_TOKEN_003_AC3_everyLegacyFormatRehashesToPbkdf2` | 10 | verified |
| FR-TOKEN-004 | 8 | 7 asserted, AC-8 gap closed | `FR_TOKEN_004_AC8_everyHttpPathSaysWhy` | 8 | verified |
| IR-TOKEN-001 | 7 | 7 asserted | none | 8 | verified |
| IR-TOKEN-002 | 3 | 2 asserted, AC-2 gap closed | `IR_TOKEN_002_AC2_decodeFailureReasonIsLoggedAndNotSent` | 6 | verified |
| PERF-TOKEN-001 | 7 | 7 asserted | none | 9 | verified |
| PERF-TOKEN-002 | 5 | 4 asserted, AC-1 gap closed | `PERF_TOKEN_002_AC1_oneVerifyInTheSlotAndTheLegacyLoginDoesNotWait` | 5 | verified |
| REL-TOKEN-001 | 6 | 6 asserted | none | 6 | verified |

One row pointed at a missing file: FR-TOKEN-002 VE-2 named `AuthJdbcDefaultsTest.java`, which no longer exists. That row now names a real method, and AC-2 is covered by `AuthStoreDefaultsTest#FR_TOKEN_002_AC2_defaultRefreshStoreFollowsTheUserStore`.

## What each added test closes

- **SEC-TOKEN-001 AC-2:** the only standard-alphabet sample was `a+b/`, which fails on the `+`, so `/` was never reached by `Base64Url.decode`.
- **SEC-TOKEN-002 AC-4:** the old test parsed with `MiniJson` directly. Nothing showed that a signed JWT header or payload is held to the CLAIMS limit. The new test signs both, with a well-formed array and with content that is not JSON past the limit.
- **SEC-TOKEN-003 AC-3:** the AC says "in any part". The old test covered `=` in the header and at the end, `+` in the signature and `/` in the payload. The new test puts each of the three characters in each of the three parts.
- **SEC-TOKEN-004 AC-4:** only the unset variable was tested. The new test starts a child JVM holding the variable set and empty, because a JVM cannot change its own environment.
- **SEC-TOKEN-004 AC-5, SEC-TOKEN-007 AC-2:** nothing asserted that the bytes come from SecureRandom. The new tests check that two fresh key files differ, and that `KeySources` and `Pbkdf2PasswordHasher` reference `java/security/SecureRandom` and not `java/util/Random`.
- **FR-TOKEN-002 AC-5:** the old test asserted the constant, then replaced the interval with 30 ms. The new test reads the interval a default `Auth` was built with.
- **FR-TOKEN-002 AC-7:** the old test worked on store records only. The new test refreshes a real token on a new `Auth` over a new store opened on the same file.
- **FR-TOKEN-003 AC-3:** only MD5 went through a login. The new test covers MD5, SHA-1, SHA-256, salted SHA-256 and bcrypt.
- **FR-TOKEN-004 AC-8:** setup and A4 were not asserted for the rule message, and no path was asserted for a breach-check refusal. The new test covers both, on all five HTTP paths for the breach refusal.
- **IR-TOKEN-002 AC-2:** nothing asserted that the failure reason reaches the log. The new test checks that it is logged and absent from the response.
- **PERF-TOKEN-002 AC-1:** the latch test did not count verifies, and no latch test covered a legacy user. The new test does both, through `POST /auth/login`. `LatchedHasher` gained a `verifies` counter for it; nothing else about the helper changed.

## Limits of this evidence

- **Five ACs rest partly on class-file or source checks, not on observed behaviour.** SEC-TOKEN-004 AC-5 and SEC-TOKEN-007 AC-2 (SecureRandom), SEC-TOKEN-007 AC-3 and SEC-TOKEN-008 AC-7 (ConstantTime) check class references. FR-TOKEN-003 AC-4 (ConstantTime for digests) matches source text. A reference proves the class is used, not that every comparison goes through it.
- **SEC-TOKEN-003 AC-6:** "before its signature is computed" is shown by order. An oversized token with a wrong signature answers `TOO_LARGE`, not `BAD_SIGNATURE`. No counter proves the HMAC did not run.
- **SEC-TOKEN-004 AC-5:** the test calls `KeySources.fromFile(file).signingKey()` directly. `Auth.builder` over it is exercised in DEV only, because PROD needs an `AuthFileKey` (FR-IAM-015).
- **FR-TOKEN-001 AC-6:** the profile lifetimes in the test match the `AuthProfile` table in `docs/api/en/auth.md:174-176`. That match was read by hand; no test compares the two.
- **FR-TOKEN-002 AC-5:** the 10-minute default is read by reflection from a private field. The scheduler itself is tested at 30 ms and 20 ms.
- **FR-TOKEN-002 AC-7:** "restart" is a second `Auth` over a second store on one file, with no app stop between them.
- **REL-TOKEN-001 AC-3:** the 5-second cap on a login is asserted at `PasswordLogin` (the UNAVAILABLE outcome). The HTTP 503 with Retry-After is asserted on an app route calling `checkPassword`.
- **SEC-TOKEN-009 AC-2, AC-7:** the floor uses a stub clock and a recording scheduler. Real-time delivery is covered by AC-8 on TinyHttpd and Servlet.
- **PERF-TOKEN-002 AC-1:** the legacy half uses MD5 in the latch test. SHA and bcrypt logins are covered by `FR_TOKEN_003_AC3_everyLegacyFormatRehashesToPbkdf2` and `SEC_TOKEN_009_AC1_everyAttemptRunsOnePbkdf2InOneSlot`, without a latch.
- **Stale notes in Trace Links:** REL-TOKEN-001 still says "not yet wired to hashing endpoints", and SEC-TOKEN-002 still says "JDBC advice" on its OPS-AUTH-001 row. Both notes are out of date. They were left alone because this batch edited evidence rows only, and `edit_requirement_table_rows` now refuses these verified requirements.

## Run results

| Check | Result |
|---|---|
| `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline` | exit 0 on the first run, no flake |
| Summed `build/test-results` | 3619 tests, 0 failures, 0 errors, 0 skipped (3607 before, plus 12 added) |
| Evidence references | 142 rows in the 18 requirements checked by script: every file and method exists, every AC has a row |
| `speckiwi coverage --tests --target 0.11.0` | No gap for any batch id. `passed` is false because of the same 4 gaps outside this batch: FR-AUTH-010 AC-6, FR-IAM-016 AC-6, OPS-AUTH-005 AC-6, OPS-AUTH-007 AC-8 |
| Citations in this checkout | All 119 batch ACs are cited outside `.claude/worktrees`, which the coverage scan also reads |
| `validate_spec` | 0 errors, 47 SRS-W015 warnings (the same count as before this batch). The top-level summary of `speckiwi validate --json` shows 0 warnings; the MCP count is the one recorded here |
| Guard refusals on `update_status` | none |
| Main sources and `docs/api` | unchanged |
