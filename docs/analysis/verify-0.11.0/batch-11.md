# 0.11.0 verification, batch 11

16 of the 19 requirements (OPS-AUTH-023 to OPS-AUTH-033 and SEC-AUTH-001 to SEC-AUTH-008, 116 ACs) moved to `verified`. SEC-AUTH-002 and SEC-AUTH-004 stay `implemented` for product gaps, and OPS-AUTH-023 for an expired AC, all described below. Every AC was read against its test body, and 48 tests were added for clauses nothing asserted.

Build, status and coverage results are under "Results of the run".

## PRODUCT-GAP

### SEC-AUTH-002 AC-1 (left `implemented`)

AC text: "AuthMode.fromEnv() returns DEV when NARU_ENV=dev, and PROD when NARU_ENV is unset, empty, prod or any other value; a builder without .mode(...) runs as PROD."

What the code does instead: any letter case of `dev` gives DEV, so `NARU_ENV=DEV` or `Dev` turns on the DEV relaxations where the AC, and the requirement's "DEV only when NARU_ENV is dev", say PROD.

- `NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/AuthMode.java:34` lowercases the value before comparing it.
- `NaruServe-api/src/test/java/com/snoworca/naruserve/utils/auth/AuthBuilderTest.java:264` pins `fromEnvValue("DEV")` as DEV.
- `docs/api/auth.md:352` and `docs/api/en/auth.md:352` document "any letter case".

No SRS requirement allows the letter-case match. Code, test and guide agree with each other and disagree with the AC, so the behaviour looks deliberate, but this is the switch that relaxes secure transport and key strength. Someone has to choose: amend the AC, or make the match exact.

### SEC-AUTH-004 AC-4 (left `implemented`)

AC text: "app.cors(c -> c.origin("https://app.example.com")) then install: a preflight OPTIONS /admin/x with Origin and Access-Control-Request-Method is answered by CORS without a token."

What the code does instead: `CorsOptions` has no `origin(...)`, so the call the AC names does not compile. The method is `allowedOrigins(...)` (`NaruServe-api/src/main/java/com/snoworca/naruserve/CorsOptions.java:7`).

The behaviour holds: `PreAuthOrderTest#SEC_AUTH_004_AC4_corsAnswersPreflightWithoutAToken` asserts it through `allowedOrigins` (`PreAuthOrderTest.java:98`). Only the method name in the AC is out of date, and changing AC wording was outside this batch. Left `implemented` as batch 10 left OPS-AUTH-011 AC-10. Replacing `c.origin` with `c.allowedOrigins` in the AC closes it.

## EXPIRED-AC

### OPS-AUTH-023 AC-3 (left `implemented`), replaced by OPS-AUTH-024 AC-9

AC text: "DELETE /auth/me/apikeys/:id for an id that is not one of the caller's keys answers 404 `no API key with id <id>; use the id field of the key, not its prefix`."

OPS-AUTH-024 AC-9 narrowed this on purpose: only an 8-character value keeps that message, and any other unknown id answers `no API key with id <id> among your keys` (`AccountEndpoints.java:1324-1325`). `OPS_AUTH_023_AC3_unknownOwnKeyNamesTheId` asserts the 8-character case only. The other five ACs of OPS-AUTH-023 are asserted.

## Verdicts

A row's verdict is ASSERTED when existing tests covered every AC. TEST-GAP lists the ACs that needed a new test, all of which passed against the existing code unless noted.

| Requirement | ACs read | Verdict | Tests added or rows fixed |
| --- | --- | --- | --- |
| OPS-AUTH-023 | 6 | EXPIRED-AC AC-3; TEST-GAP AC-1, 2, 4, 5 | 4 tests in `JuniorRound11Test`: the other three guard kinds and INTERNAL_ERROR, `.refreshTtl` and `24 hours`, the admin endpoints in the WARN, leading whitespace. 9 rows |
| OPS-AUTH-024 | 10 | TEST-GAP AC-1, 2, 3, 4, 5, 7, 9, 10 | 8 tests in `JuniorRound12Test`: one auth ERROR, `.permitPrefix` and `.permitWebSocket`, first pattern segment, 401 invalid_token, other permitted methods, route-order message, expired key and invalid_api_key, enabled and token version. 19 rows |
| OPS-AUTH-025 | 7 | TEST-GAP AC-1, 4, 6 | 3 tests in `JuniorRound13Test`: `before app.use(auth)` in the 401 and the WARN, A2 and A4, `.refreshTtl`. AC-3's bodiless clause cites `JuniorRound6Test`. 11 rows |
| OPS-AUTH-026 | 4 | TEST-GAP AC-1, 3, 4 | 3 tests in `JuniorRound14Test`: no userOrNull advice, 401 invalid_token, the FAIL item itself. 6 rows |
| OPS-AUTH-027 | 6 | TEST-GAP AC-1, 2, 6 | 3 tests in `JuniorRound15Test`: insufficient_scope, POST /auth/admin/apikeys, the blank name quoted. 9 rows |
| OPS-AUTH-028 | 6 | TEST-GAP AC-1, 3, 4, 5, 6 | 5 tests in `JuniorRound16Test`: router.use and route middleware, letter case with an exact-match control, 401 invalid_api_key, invalid_credentials on both routes, `applied` kept. 11 rows |
| OPS-AUTH-029 | 7 | TEST-GAP AC-4, 7 | 2 tests in `JuniorRound17Test`: an UnsupportedOperationException list() passes, 403 status. AC-1's guard clause cites `JuniorRound15Test`. 10 rows |
| OPS-AUTH-030 | 8 | TEST-GAP AC-1, 2, 3, 5, 6, 7, 8 | 7 tests in `JuniorRound18Test`: all four guard kinds and 401, invalid_token and the other causes, HEAD, id and route in the WARN, `issue` directly, `False`, insufficient_scope. 13 rows |
| OPS-AUTH-031 | 3 | TEST-GAP AC-2, 3 | 2 tests in `JuniorRound19Test`: 401 invalid_token, no echo of the value. 5 rows |
| OPS-AUTH-032 | 3 | TEST-GAP AC-1, 3 | 2 tests in `JuniorRound20Test`: message prefix and a single failure's own message, a status below 400 and `an ordinary exception`. 4 rows |
| OPS-AUTH-033 | 6 | TEST-GAP AC-1, 2, 3, 5, 6 | 4 tests in `JuniorFinalItemsTest`: app layers answer first, HttpException status and hidden 5xx message, OPTIONS passes on, username and changes nothing. 2 in `AuthGuideFinalItemsDocsTest`: final error layer text, own roles in words. 17 rows |
| SEC-AUTH-001 | 8 | TEST-GAP AC-8 | 1 test in `AuthBuilderTest`: start continues in DEV for each weak key. 9 rows |
| SEC-AUTH-002 | 5 | PRODUCT-GAP AC-1; AC-2 to AC-5 asserted | No tests. 5 rows |
| SEC-AUTH-003 | 8 | ASSERTED | No tests. 8 rows |
| SEC-AUTH-004 | 5 | PRODUCT-GAP AC-4; AC-1, 2, 3, 5 asserted | No tests. 5 rows |
| SEC-AUTH-005 | 6 | ASSERTED | No tests. 7 rows |
| SEC-AUTH-006 | 8 | TEST-GAP AC-5 | 1 test in `ProtectOnlyTest`: both upgrades on a `.permit` path. 9 rows, and the old row named `ProtectOnlyTest` for AC-1 and AC-7, which live in `RealJwtPathTest` |
| SEC-AUTH-007 | 7 | TEST-GAP AC-2 | 1 test in `PrincipalCacheTest`: a role change clears the whole cache. 8 rows |
| SEC-AUTH-008 | 3 | ASSERTED | No tests. 3 rows |

Every whole-file evidence row was replaced with `path/FileTest.java#method` references. All 168 rows now resolve to a real method, and every AC has at least one.

## Notes on ACs marked as met

These passed, but the wording is close enough to the edge that a reader should know.

- **OPS-AUTH-025 AC-1 and OPS-AUTH-027 AC-3, "once per process":** the guard flags are fields of `Auth` (`Auth.java:1963` and `:1967`), so it is once per `Auth` instance. Two Auth instances in one process would each log once.
- **OPS-AUTH-025 AC-5, `?token=`:** the hint reads `?token` without the equals sign (`Auth.java:1907`). The parameter is named, so this counts as met.
- **SEC-AUTH-006 AC-4, admin routes outside the mount:** this clause cannot occur today. The admin path is always under the mount (`Auth.java:341`), so no test can reach it.
- **OPS-AUTH-024 AC-1, "no second ERROR":** Core still logs its own `ERROR request <id> failed` line for the 503. The AC limits the rule to auth, so the new test counts only `ERROR auth` lines. A first version counted every ERROR and failed on that Core line, which was a bug in the test, not in the product.

Not changed: the SEC-AUTH-002 trace link note still says `fromEnv() withheld pending the FR-DX-012 AC-1 conflict`, which is out of date because `fromEnv()` exists.

## Results of the run

- Build: `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline` exit 0; 3771 tests, 0 failures, 0 errors, 0 skipped, summed over every module. It ran once, after all test changes and before any status change.
- Status: 16 requirements moved to `verified` one at a time, with no guard refusal. Index totals are now 13 implemented and 449 verified.
- Coverage: `speckiwi coverage --tests --target 0.11.0 --json` reports `passed: true`, with no gap for any batch id and all 116 ACs cited.
- `validate_spec`: 0 errors, and the same 47 SRS-W015 warnings about the Completed Work Log that were there before this batch.
- No test was weakened or removed: the Java diff adds lines only. No main source or `docs/api` file changed.
