# 0.11.0 verification, batch 8

All 12 requirements (SEC-FLOW-004 to SEC-FLOW-015) moved to `verified`. All 105 ACs were read against the test body, not against a citation or evidence row. No AC turned out to be a PRODUCT-GAP or an EXPIRED-AC. 10 tests were added and 10 existing tests were strengthened. No main source and no `docs/api` file changed.

Build, status and coverage results are under "Results of the run".

## PRODUCT-GAP

None. Every test added for a TEST-GAP passed on its first run, so the code already did what each AC states.

## EXPIRED-AC

None.

## Results

"Rows" is the final number of evidence rows. Before this batch every row named a whole test file for several ACs, so each was rewritten to one real method with the note `command: ./gradlew build; result: pass`.

| Requirement | ACs read | Verdict | Tests added or strengthened | Rows |
|---|---|---|---|---|
| SEC-FLOW-004 | 9 | 9 asserted | none | 9 |
| SEC-FLOW-005 | 7 | 5 asserted, AC-1 and AC-4 gaps closed | added `SEC_FLOW_005_AC1_theRealLoginRefusesACrossOriginForm`, `SEC_FLOW_005_AC4_theRealEndpointsCheckLengthAndChunked` | 9 |
| SEC-FLOW-006 | 8 | 7 asserted, AC-7 gap closed | added `SEC_FLOW_006_AC7_theCsrfValueIsTheHmacOfSubFamRandom` | 8 |
| SEC-FLOW-007 | 8 | 5 asserted, AC-1, AC-7 and AC-8 gaps closed | added `SEC_FLOW_007_AC1_aHeldAccessCookieAttachesNoPrincipalOnLogin`, `SEC_FLOW_007_AC8_theRouteDoesNotRunOnAForeignOrigin`; strengthened `FR_FLOW_007_AC6_cookieModeChecksTheOriginOnly`, `SEC_FLOW_008_AC7_foreignOriginIsRefused` | 10 |
| SEC-FLOW-008 | 10 | 9 asserted, AC-6 gap closed | strengthened `SEC_FLOW_008_AC6_AC8_bothModeCookieRefreshNeedsOriginAndCsrf` | 8 |
| SEC-FLOW-009 | 8 | 8 asserted | none | 8 |
| SEC-FLOW-010 | 15 | 8 asserted, AC-5, AC-6, AC-11, AC-12, AC-13, AC-14 and AC-15 gaps closed | strengthened the seven tests of those ACs | 15 |
| SEC-FLOW-011 | 9 | 9 asserted | none | 8 |
| SEC-FLOW-012 | 6 | 5 asserted, AC-4 gap closed | added `SEC_FLOW_012_AC4_anotherUsersIdFallsBackToAllKeys` | 9 |
| SEC-FLOW-013 | 10 | 7 asserted, AC-3, AC-7 and AC-10 gaps closed | added `SEC_FLOW_013_AC3_logoutOfAllDevicesEndsTheDeviceToken`, `SEC_FLOW_013_AC7_cookieModeUsesTheNaruDeviceCookie`, `SEC_FLOW_013_AC7_bothModePrefersTheHeader`, `SEC_FLOW_013_AC10_deviceCookieOnLoginAndPasswordChange` | 15 |
| SEC-FLOW-014 | 9 | 9 asserted | none | 13 |
| SEC-FLOW-015 | 6 | 6 asserted | none | 6 |

## What each change closes

- **SEC-FLOW-005 AC-1, AC-4:** `AuthJsonBodyTest` runs against stand-in handlers on `auth.routes()`. The new tests use the real login and logout, in every mode for AC-1. A cross-origin form with a real Origin step behind it answers 415, not 403, and takes no hash slot.
- **SEC-FLOW-006 AC-7:** the old test checked the value only through `CsrfTokens.matches`, so a wrong HMAC input would have passed on both sides. The new one recomputes the HMAC with the JDK `Mac` over sub, fam and the random part.
- **SEC-FLOW-007 AC-1:** "no principal is attached" was asserted only for a Bearer in HEADER mode (`SEC_FLOW_003_AC3`). The new test uses a held `naru_at` in COOKIE mode.
- **SEC-FLOW-007 AC-7:** the old test covered only login. Refresh and setup were covered elsewhere. The register and COOKIE refresh refusals did not check for planted cookies, so one assertion was added to each.
- **SEC-FLOW-007 AC-8:** "the route does not run" was inferred from the 403. The new test counts entries into the handler.
- **SEC-FLOW-008 AC-6:** "no body tokens are sent" checked `accessToken` only. `refreshToken` is now checked too.
- **SEC-FLOW-010 AC-5, AC-6, AC-11, AC-12, AC-15:** several refusals asserted only the status. A plain 403 cannot tell `csrf_failed` from `insecure_transport`. A `csrfFailed` helper now asserts both.
- **SEC-FLOW-010 AC-13, AC-14:** the 401 was checked through `WWW-Authenticate` only. The body code `invalid_token` is now asserted as well.
- **SEC-FLOW-012 AC-4:** ver differs and user gone were asserted, but not "user id differs". The new test renames alice, gives the name to another user at the same ver, and shows both the deferred IP key and the user-only key answer.
- **SEC-FLOW-013 AC-3:** only the password-change half was asserted. The new test shows `logout?all=true` ends trust.
- **SEC-FLOW-013 AC-7:** only the HEADER read was asserted. The new tests cover the COOKIE read, a cookie set by Referer and by `trustedOrigins`, and the BOTH preference for the header over the cookie.
- **SEC-FLOW-013 AC-10:** the mount-path case existed only at component level, BOTH was not checked, and the password change did not check Secure. The new test runs COOKIE and BOTH, both mounts, over https and http, through the real login and password endpoints.

## Results of the run

- **Build:** `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline` exited 0. Summed over every module: 3685 tests, 0 failures, 0 errors, 0 skipped. No flake and no rerun.
- **Status:** all 12 requirements had every AC ticked and moved to `verified`, one at a time. No guard refused.
- **Evidence:** a script confirmed every row's file exists and holds the named method, and every AC has a row.
- **Coverage:** `speckiwi coverage --tests --target 0.11.0 --json` shows no gap for the batch ids. The target as a whole still reports gaps for FR-AUTH-010, OPS-AUTH-005, OPS-AUTH-007, all outside this batch.
- **Validation:** `validate_spec` reports 0 errors.

## Note

`AuthJsonBodyTest` still says in its header that the account endpoints are not built yet. That comment is stale, but its two tests stay valid as checks of the body-format step, so they were left alone.
