# 0.11.0 verification, batch 10

18 of the 22 requirements (OPS-AUTH-001 to OPS-AUTH-022, 132 ACs) moved to `verified`. OPS-AUTH-006, 011 and 022 stay `implemented` for product gaps, and OPS-AUTH-016 for an expired clause, all described below. Every AC was read against the test body, not against a citation or evidence row. 28 tests were added, 21 existing tests were strengthened, and no test was weakened. No main source and no `docs/api` file changed.

Build, status and coverage results are under "Results of the run".

## PRODUCT-GAP

### OPS-AUTH-006 AC-1 (left `implemented`)

AC text: "POST /auth/login with {"user":"a","pass":"b"} answers 400 BAD_REQUEST whose message is `username must be a JSON string`; with {"username":"a"} the message is `password must be a non-empty JSON string`; register and setup carry the same field messages. Any other HttpException in the auth routers still answers without its message (FR-AUTH-006 AC-4)."

What the code does instead: a missing password on register and setup answers `password must be a JSON string`, not login's `password must be a non-empty JSON string`.

- `NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/AccountEndpoints.java:958` (register) and `:1029` (setup) read the field with `string(body, "password")`, whose message is built at `:191`.
- `AccountEndpoints.java:279-281` (login) has its own check with the `non-empty` text.

The new test failed on this for setup, with `expected:<password must be a [non-empty ]JSON string> but was:<password must be a []JSON string>`. Register goes through the same `string(...)` call. Both still name the field, so a looser reading of "the same field messages" could pass; read literally it does not. One of these has to change: give register and setup login's text, or narrow the AC. The username message, and the hiding of any other HttpException's message, are asserted.

`JuniorMessagesTest#OPS_AUTH_006_AC1_setupBodyErrorsNameTheField` asserts only what holds (the username text, and a message starting `password must be a `), so it does not record the difference as correct.

### OPS-AUTH-011 AC-10 (left `implemented`)

AC text: "The token-issue example of both auth guides finds the user by a username it created (no undefined `id`)."

What the docs do instead: the examples do not find the user. They keep the value `auth.users().create(...)` returns and pass it to `auth.tokens().issue(alice)`.

- `docs/api/auth.md` and `docs/api/en/auth.md`, lines 1319-1320 and 1327-1328 (Tokens section), 1421-1422 and 1431-1432 (Admin endpoints section).

The defect behind the AC, an undefined `id`, is gone, and `AuthGuideRound3DocsTest#OPS_AUTH_011_AC10_tokenExampleHasNoUndefinedId` asserts that. The lookup the AC describes is not there. The wording may simply be out of date; narrowing the AC to "uses the user it created" would close this with no docs change.

### OPS-AUTH-022 AC-1 and AC-2 (left `implemented`)

AC-1 text: "Without .apiKeys(store), a 401 for an Authorization value in the API-key scheme or of the nk_ form says `API keys are off` and names .apiKeys(store)."

AC-2 text: "With API keys on, a 401 for an Authorization value holding an nk_ key without the API-key scheme says `an API key goes in the Authorization header as <scheme> <key>`."

What the code does instead: `Authorization: Bearer nk_...` never reaches those messages. It answers 401 `invalid_token` with `the token is an API key, not an access token; send API keys as Authorization: ApiKey <key> (or the scheme set with .apiKeyScheme(...))`, whether keys are on or off.

- `NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/internal/AccessTokens.java:93-96` raises it, for OPS-AUTH-021 AC-8.
- `Auth.java`, `unauthenticatedMessage` (the `keyLike` branch starting at the `OPS-AUTH-022 AC-1..AC-3` comment) excludes Bearer from the AC-2 text.

With keys off this sends the client to a scheme that then answers `API keys are off`. The failure was measured for keys off (`JuniorRound10Test`, first run); the keys-on case is read from the code, which has no keys-on branch at that line. A bare `nk_` value and `Token nk_...` do get both messages, and those are asserted. If the Bearer case is meant to stay with OPS-AUTH-021 AC-8 alone, both ACs should say so; if not, `AccessTokens` needs to know whether keys are on.

## EXPIRED-AC

### OPS-AUTH-016 AC-1, last clause (left `implemented`)

AC text: "... and the other guards keep `the principal lacks a required scope or role`."

Replaced by OPS-AUTH-029 AC-7: "The 403 of a false auth.check(rule) names the auth.check rule." `auth.check` is the only other public guard that can answer 403 (`auth.authenticated()` never does), and it now says `...; this route requires its auth.check rule to allow the principal` (`Auth.java:3054`, asserted by `JuniorRound17Test#OPS_AUTH_029_AC7_checkRefusalNamesTheRule`). No public guard keeps the plain text any more. The `require` and `requireRole` clauses of AC-1 are asserted with exact messages.

## Observation, not a gap

OPS-AUTH-002 AC-1 asks for the admin mount path "when auth.adminRoutes() is mounted outside that path". That cannot happen: `adminPath` is always derived from the mount path (`Auth.java:341`), so the `!underPrefix(adminPath, mountPath)` branch at `Auth.java:1336` is dead code. `describe()` prints `admin: <path>` in every case, and `DescribeTest` now asserts that line in protectOnly mode too.

## Results

"Rows" is the final number of evidence rows. OPS-AUTH-001 to 008 had their whole-file rows rewritten to real methods. OPS-AUTH-009 to 022 keep their one old whole-file row (the file exists, so it is not stale) and gained a `file#method` row per AC with the note `command: ./gradlew build; result: pass`. Those old rows still list every AC in Covers, including the open ones in 011, 016 and 022; the partial rows say what is open.

| Requirement | ACs read | Verdict | Tests added or strengthened | Rows |
|---|---|---|---|---|
| OPS-AUTH-001 | 6 | 6 asserted | none | 6 |
| OPS-AUTH-002 | 3 | 3 asserted | strengthened AC-1 (`admin:` line in protectOnly mode) | 3 |
| OPS-AUTH-003 | 4 | 4 asserted, 2 partial gaps closed | strengthened AC-1 (two-argument constructor, RuntimeException) and AC-2 (`invalid_credentials` code) | 4 |
| OPS-AUTH-004 | 3 | 3 asserted, AC-3 gap closed | added `OPS_AUTH_004_AC3_lanParagraphIsInTheSecureTransportSection` | 3 |
| OPS-AUTH-005 | 6 | 6 asserted, 3 gaps closed | strengthened AC-1, AC-2; added `OPS_AUTH_005_AC3_quickStartPasswordMeetsThePolicy`; AC-6 citation | 7 |
| OPS-AUTH-006 | 6 | 5 asserted, AC-1 PRODUCT-GAP | added `OPS_AUTH_006_AC1_setupBodyErrorsNameTheField`, `OPS_AUTH_006_AC1_otherHttpExceptionHidesItsMessage`; strengthened AC-4 | 8 |
| OPS-AUTH-007 | 8 | 8 asserted | AC-8 citation only | 9 |
| OPS-AUTH-008 | 6 | 6 asserted | strengthened AC-1 (`typo`) | 6 |
| OPS-AUTH-009 | 8 | 8 asserted | none | 9 |
| OPS-AUTH-010 | 2 | 2 asserted, 2 gaps closed | added `OPS_AUTH_010_AC1_protectOnlyPublicRouteIsNotWarned`; strengthened AC-2 | 5 |
| OPS-AUTH-011 | 12 | 11 asserted, AC-10 PRODUCT-GAP | added 6 doc tests for AC-1, 5, 6, 7, 8, 11 | 15 |
| OPS-AUTH-012 | 4 | 4 asserted, 2 gaps closed | added `OPS_AUTH_012_AC1_placedErrorMiddlewareGetsNoSecond`, `OPS_AUTH_012_AC2_bothTransportNamesTheField` | 7 |
| OPS-AUTH-013 | 5 | 5 asserted, 3 gaps closed | added 3 doc tests for AC-2, 3, 4 | 9 |
| OPS-AUTH-014 | 4 | 4 asserted, 3 gaps closed | added `OPS_AUTH_014_AC2_registerAndSetupNamePost` and, in new `AuthBodyGsonTest`, `OPS_AUTH_014_AC1_oneMessageUnderTheGsonCodec`; strengthened AC-3 | 7 |
| OPS-AUTH-015 | 6 | 6 asserted, AC-1 gap closed | strengthened AC-1 | 7 |
| OPS-AUTH-016 | 3 | 2 asserted, AC-1 last clause EXPIRED-AC | added `OPS_AUTH_016_AC2_emptyOrNonStringCurrentPasswordIsNamed` | 5 |
| OPS-AUTH-017 | 10 | 10 asserted, 6 gaps closed | strengthened AC-1, 2, 3, 6, 7, 10; added `OPS_AUTH_017_AC2_emptyArrayGetsTheMissingFieldAnswer` | 12 |
| OPS-AUTH-018 | 7 | 7 asserted, 3 gaps closed | added 3 tests for AC-1, 3, 7 | 11 |
| OPS-AUTH-019 | 4 | 4 asserted, AC-3 gap closed | strengthened AC-3 | 5 |
| OPS-AUTH-020 | 3 | 3 asserted, 2 gaps closed | strengthened AC-1 (all 7 endpoints); added `OPS_AUTH_020_AC3_everyKeyIssuanceShapeRefusalIsNamed` | 5 |
| OPS-AUTH-021 | 14 | 14 asserted, 5 gaps closed | added 3 tests for AC-1, 3, 4; strengthened AC-9, AC-12 | 18 |
| OPS-AUTH-022 | 8 | 6 asserted, AC-1 and AC-2 PRODUCT-GAP | added `OPS_AUTH_022_AC1_nkFormWithKeysOffIsNamed`; strengthened AC-2 | 10 |

## Results of the run

- Status: 18 of 22 moved to `verified`; OPS-AUTH-006, 011, 016 and 022 stay `implemented`, their ACs unticked. No guard refused.
- Build: `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline` exit 0. Summed over `build/test-results/**/*.xml`: 3723 tests, 0 failures, 0 errors, 0 skipped. No flake to rerun.
- After that build, two comment-only citation lines were added (OPS-AUTH-005 AC-6, OPS-AUTH-007 AC-8). `AuthOnboardingDocsTest` and `AuthGuideDocsTest` were then run again and passed; the full build was not repeated.
- Coverage: `speckiwi coverage --tests --target 0.11.0 --json` reports no gaps and `passed: true`.
- `validate_spec`: 0 errors, 47 warnings, all SRS-W015 on `91.completed-work-log.md`, about requirements outside this batch.
