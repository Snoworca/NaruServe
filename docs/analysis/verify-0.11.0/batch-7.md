# 0.11.0 verification, batch 7

16 of the 17 requirements moved to `verified`. FR-FLOW-002 stays `implemented`, because the set-up one of its criteria names cannot be built. All 118 ACs were read against the test body, not against a citation or evidence row. 15 tests were added. No main source and no `docs/api` file changed.

Build, status and coverage results are under "Results of the run".

## PRODUCT-GAP

### FR-FLOW-002 AC-5 (second half): the app.cors set-up cannot be built

AC text: "BOTH with .trustedOrigins("https://spa.example") and app.cors allowedOrigins holding https://spa.example and capacitor://localhost: POST /auth/login with Origin https://spa.example (trusted but not the request's own origin) -> 200 cookie delivery (Set-Cookie for naru_at, naru_rt and naru_csrf, body csrfToken only, no accessToken); the same login with Origin capacitor://localhost (in allowedOrigins, not in trustedOrigins) -> 200 body delivery with accessToken and no Set-Cookie".

Where: `NaruServe-api/src/main/java/com/snoworca/naruserve/internal/DefaultCorsOptions.java:27`, through `NaruServe-api/src/main/java/com/snoworca/naruserve/common/CorsValues.java:10`.

What the code does instead: `allowedOrigins("https://spa.example", "capacitor://localhost")` throws `IllegalArgumentException` ("allowedOrigins requires serialized HTTP(S) origins, null or *"), because `isOrigin` accepts only http and https. `OPS_FLOW_001_AC3_capacitorIsRefusedByAllowedOrigins` pins this.

The delivery rule itself holds. `FR_FLOW_002_AC5_trustedOriginBesideCors` runs the same logins with `https://spa.example` and `http://localhost` in `app.cors`: the spa origin gets cookie delivery, and `capacitor://localhost` (not listed, because it cannot be) gets body delivery with no Set-Cookie. Only the words "in allowedOrigins" are unmet.

The two criteria disagree: OPS-FLOW-001 AC-3 requires the docs to say this origin cannot be listed, and the code and docs agree with that one. It was not treated as EXPIRED-AC: nothing replaced AC-5 on purpose, and the docs and the old OPS-FLOW-001 evidence note call the limit a "yet" and a residual. Either Core accepts non-http(s) origins, or AC-5 is reworded. Both are decisions outside this batch.

All other FR-FLOW-002 ACs are asserted and have real evidence rows; its ACs stay unticked because the requirement was not promoted.

## EXPIRED-AC

None.

## Results

"Rows" is the final number of evidence rows. Before this batch most rows named a whole test file for all ACs, so each was rewritten to one real method with the note `command: ./gradlew build; result: pass`.

| Requirement | ACs read | Verdict | Tests added | Rows |
|---|---|---|---|---|
| FR-FLOW-001 | 6 | 6 asserted; verified | none | 6 |
| FR-FLOW-002 | 9 | 6 asserted, AC-8 and AC-9 gaps closed, AC-5 PRODUCT-GAP; left implemented | `FR_FLOW_002_AC5_trustedOriginBesideCors`, `FR_FLOW_002_AC8_registerAndSetupAreNoStore`, `FR_FLOW_002_AC9_setupResponseShapes` | 12 |
| FR-FLOW-003 | 8 | 5 asserted, AC-4, AC-5 and AC-6 gaps closed; verified | `FR_FLOW_003_AC4_revokeFamilyIsCalledOnceWithTheTokensFamily`, `FR_FLOW_003_AC5_aStoreErrorTriesNoOtherCandidate`, `FR_FLOW_003_AC6_bothModeClearsWithAnIdentity` | 12 |
| FR-FLOW-004 | 7 | 6 asserted, AC-6 gap closed; verified | `FR_FLOW_004_AC6_badPasswordFieldsAreNotHeldToTheFloor` | 8 |
| FR-FLOW-005 | 7 | 7 asserted; verified | none | 7 |
| FR-FLOW-006 | 6 | 6 asserted; verified | none | 6 |
| FR-FLOW-007 | 7 | 7 asserted; verified | none | 5 |
| FR-FLOW-008 | 8 | 8 asserted; verified | none | 8 |
| FR-FLOW-009 | 6 | 4 asserted, AC-2 and AC-5 gaps closed; verified | `FR_FLOW_009_AC2_lockedRefusalIsTheWrongPasswordAnswer`, `FR_FLOW_009_AC5_releaseResetsOnlyTheUserOnlyKey` | 6 |
| IR-FLOW-001 | 7 | 5 asserted, AC-2 and AC-4 gaps closed; verified | `IR_FLOW_001_AC2_AC4_anExpiredJwtIsA401` | 3 |
| IR-FLOW-002 | 5 | 5 asserted; verified | none | 5 |
| IR-FLOW-003 | 6 | 6 asserted; verified | none | 7 |
| OPS-FLOW-001 | 6 | 5 asserted, AC-3 gap closed; verified | `OPS_FLOW_001_AC3_capacitorCannotBeListed`, `OPS_FLOW_001_AC3_capacitorIsRefusedByAllowedOrigins` | 8 |
| REL-FLOW-001 | 8 | 8 asserted; verified | none | 8 |
| SEC-FLOW-001 | 8 | 5 asserted, AC-3, AC-4 and AC-7 gaps closed; verified | `SEC_FLOW_001_AC3_AC4_AC7_theRealLoginPasses` | 9 |
| SEC-FLOW-002 | 5 | 4 asserted, AC-4 gap closed; verified | `SEC_FLOW_002_AC4_cookieLoginWarnsThatTheCookiesLackSecure` | 5 |
| SEC-FLOW-003 | 9 | 8 asserted, AC-8 gap closed; verified | `SEC_FLOW_003_AC8_csrfOfTheTokensOwnFamily` | 12 |

## What each added test closes

- **FR-FLOW-002 AC-5:** the old test never configured `app.cors` and checked only `naru_at` on the trusted origin. The new one sets `app.cors` and checks all three cookies and the body shape.
- **FR-FLOW-002 AC-8:** no-store was asserted on login, refresh and password. Register and setup were not.
- **FR-FLOW-002 AC-9:** the old test checked only `naru_rt` and a one-field body for COOKIE, and only `accessToken` for HEADER.
- **FR-FLOW-003 AC-4:** the old test used the default store and only showed the token stopped working. The new one records `revokeFamily` and checks one call with the token's family.
- **FR-FLOW-003 AC-5:** the old request carried one token, so "no other candidate is tried" could not fail. The new one adds a valid Bearer, and checks the code of the second 503, ver, revocations and onLogout.
- **FR-FLOW-003 AC-6:** the clearing with an identity was asserted only in COOKIE mode.
- **FR-FLOW-004 AC-6:** "not held to the floor" was not asserted. A recording scheduler shows no delay for the 400s and one for a wrong password.
- **FR-FLOW-009 AC-2:** the code was asserted, but not "the same body", and not that the hash ran in one slot.
- **FR-FLOW-009 AC-5:** nothing showed that the IP keys stay. A recording throttle shows the release resets only the user-only key. `SEC_FLOW_012_AC6_lockoutReleaseEndsTheUserOnlyBlock` covers the (user, IP) keys from the outside.
- **IR-FLOW-001 AC-2, AC-4:** the keys-off and `ak` responses had their header checked but not their 401 status.
- **OPS-FLOW-001 AC-3:** the last clause, on `capacitor://localhost`, was not asserted. The docs do state it.
- **SEC-FLOW-001 AC-3, AC-4, AC-7:** these ran only on stand-in handlers, so "with valid credentials" was never exercised. The new test uses the real login.
- **SEC-FLOW-002 AC-4:** the existing assertion could not fail. `warned("secure")` also matches the start-up doctor WARN ("secure transport"), which is logged whether or not a cookie is sent. `insecureCookieDeliveryWarns` proves the WARN only at component level. The new test counts the exact WARN: 0 before the login, 1 after. The old test is cited for AC-1 only.
- **SEC-FLOW-003 AC-8:** the old test used a CSRF token of "any-family", though its comment said the token's own family. The new one uses the real family.

## Notes

- FR-FLOW-005 AC-6: "neither verified" is asserted through the AC's own parenthesis (family G still refreshes afterwards), not by counting store reads.
- SEC-FLOW-003 AC-6: permit cases (a) and (b) run in one app with both permits, which is the wider configuration.
- A repeated logout with a still-valid access token finds an identity again, so `onLogout` fires twice. No AC forbids this. An assertion of one call that I had added failed, and I removed it from my own new test.
- REL-FLOW-001's trace link to `build.gradle:L274-L280` still mentions the test-only sqlite-jdbc driver, removed on 2026-11-03. It was left alone, because this batch only touches evidence rows.

## Results of the run

- Build: `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline`, exit 0. Summed over every module: 3675 tests, 0 failures, 0 errors, 0 skipped. It ran before any status change, and no flake needed a rerun.
- Status: 16 requirements verified, one at a time, with no guard refusal.
- Coverage: `speckiwi coverage --tests --target 0.11.0 --json` shows no gap for any batch id. It reports `passed: false` for three requirements outside this batch: FR-AUTH-010 AC-6, OPS-AUTH-005 AC-6 and OPS-AUTH-007 AC-8.
- `validate_spec`: 0 errors. 47 SRS-W015 warnings, all from the completed work log, none about this batch.
