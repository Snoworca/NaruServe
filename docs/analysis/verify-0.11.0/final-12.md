# 0.11.0 verification, the last 12 requirements

All 12 requirements moved to `verified`. FR-DX-012 belongs to target `start-api` and was left alone, so it is now the only `implemented` requirement in the repository.

Two requirements needed a code change, with the test failing first. Ten needed an AC reworded. No test was loosened; `JuniorMessagesTest#OPS_AUTH_006_AC1_setupBodyErrorsNameTheField` was tightened from a prefix check to the exact message.

## Results of the run

| Check | Result |
|---|---|
| `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline` | exit 0 |
| Tests, summed over `build/test-results/**/*.xml` | 3775 (baseline 3771), 0 failures, 0 errors, 0 skipped |
| `speckiwi coverage --tests --target 0.11.0 --json` | passed |
| `validate_spec` | 0 errors, 47 `SRS-W015` warnings from the Completed Work Log, the same 47 as before this run |

The 4 new tests are `FR_APIKEY_006_AC6_userStoreJavadocRequiresTheFlag`, `FR_AUTH_009_AC2_issueAndRevokeEventsCarryTheKeyId`, `OPS_AUTH_011_AC10_tokenExampleIssuesTheCreatedUser` and `OPS_AUTH_022_AC1_AC2_bearerKeyAnswersPerOpsAuth021Ac8`.

## Code changes, test first

### FR-APIKEY-006 AC-6

AC text unchanged.

- **Code:** the `UserStore` type Javadoc contract list gained `An app-provided store must persist the service-account flag`.
- **Test:** `Wave9KeyOwnersTest#FR_APIKEY_006_AC6_userStoreJavadocRequiresTheFlag` reads the type Javadoc. It failed before the change.

### OPS-AUTH-006 AC-1

AC text unchanged.

- **Code:** `AccountEndpoints` has a new `passwordField(body)`, used by register and setup. A missing or non-string password now answers 400 `password must be a non-empty JSON string`, as login does.
- **Tests:** `JuniorMessagesTest#OPS_AUTH_006_AC1_bodyErrorsNameTheField` now covers register, and `#OPS_AUTH_006_AC1_setupBodyErrorsNameTheField` asserts the exact text. Both failed before the change.
- **Docs:** `docs/api` never quoted the old text, so it did not change. No existing test pinned the old message.
- **Left as it was:** an empty string on register or setup still answers the 422 password-policy message. The AC and the brief name the missing password only.

## ACs reworded to the rule that replaced them

### OPS-REL-010 AC-3

- **Old:** The release-evidence gate accepts version 0.11.0 as pending only while neither docs/release/0.11.0-test-results.md nor docs/release/0.11.0-vulnerability-scan.md exists, still validates the 0.10.0 records, and fails once either 0.11.0 record exists until the gate is pointed at the 0.11.0 records.
- **New:** Until the 0.11.0 release run, the release-evidence gate accepted version 0.11.0 as pending only while neither docs/release/0.11.0-test-results.md nor docs/release/0.11.0-vulnerability-scan.md existed, still validated the 0.10.0 records, and failed once either 0.11.0 record existed until the gate was pointed at the 0.11.0 records. That run is done: OPS-REL-011 AC-4 replaced this rule, so the gate treats the 0.11.0 records as CURRENT for version 0.11.0.
- **Tests:** `ReleaseEvidenceSnapshotsTest#version011IsCheckedAgainstItsOwnRecords`, added as a row for AC-3.

### FR-AUTH-005 AC-7

- **Old (second half):** installing a second Auth on an app that already has one still throws the before-routes IllegalStateException (it sees the ROUTER_MOUNT of the mount path, a route-family registration).
- **New (second half):** installing a second Auth on an app that already has one throws the IllegalStateException of OPS-AUTH-024 AC-7, which replaced the before-routes message for this case: `another Auth is already installed on this app` when no app route precedes it, and the before-routes message only when an app route precedes it.
- **Tests:** `AuthInstallTest#FR_AUTH_005_AC7_secondInstallThrows` now asserts the message; before, it only caught the exception. `JuniorRound12Test#OPS_AUTH_024_AC7_routeBeforeSecondAuthKeepsTheOrderMessage` was added as a row.

### OPS-AUTH-016 AC-1

- **Old (last clause):** and the other guards keep `the principal lacks a required scope or role`.
- **New (last clause):** and the 403 of a false auth.check(rule) names the auth.check rule (OPS-AUTH-029 AC-7, which replaced the plain `the principal lacks a required scope or role` of the other guards).
- **Tests:** `JuniorRound17Test#OPS_AUTH_029_AC7_checkRefusalNamesTheRule` and `#OPS_AUTH_029_AC7_checkRefusalIs403`, added as rows. The whole-file row VE-1 was deleted.

### OPS-AUTH-023 AC-3

- **Old:** DELETE /auth/me/apikeys/:id for an id that is not one of the caller's keys answers 404 `no API key with id <id>; use the id field of the key, not its prefix`.
- **New:** DELETE /auth/me/apikeys/:id for an 8-character value that is not one of the caller's keys answers 404 `no API key with id <id>; use the id field of the key, not its prefix`; any other id that is not one of the caller's keys answers 404 `no API key with id <id> among your keys` (OPS-AUTH-024 AC-9, which narrowed the prefix message to the 8-character case).
- **Tests:** `JuniorRound11Test#OPS_AUTH_023_AC3_unknownOwnKeyNamesTheId` for the 8-character value, and `JuniorRound12Test#OPS_AUTH_024_AC9_staleKeyIsNamed`, added as a row, for any other id.

## ACs reworded to the documented contract

### FR-AUTH-009 AC-2

- **Old (first sentence):** A recording AuditSink set on the builder receives events for: recovery use, self-protection grant denial, security_write_conflict, and API key issue and revoke (carrying the key id and issuedByUserId).
- **New (first sentence):** A recording AuditSink set on the builder receives events for: recovery use, self-protection grant denial, security_write_conflict, API key issue (carrying the key id, and issuedByUserId when a key endpoint issued the key; auth.apiKeys().issue has no issuer) and API key revoke (carrying the revoked key id).
- **Added at the end:** Decision (2026-11-05): issuedByUserId is recorded on the key issue event only, as the key issuance steps of docs/api/auth.md and 00 §6.4 row "R47 × 발급자 기록" state; revoke events carry the key id as docs/api/auth.md states near line 1994, and the issuer stays on the key row until the revoke.
- **Wider than the brief:** the brief said issuedByUserId is on the key issue event. The app call `auth.apiKeys().issue` has no issuer, and 00 and `docs/api` both say so, so the AC limits it to the key endpoints.
- **Tests:** new `Wave10PatTest#FR_AUTH_009_AC2_issueAndRevokeEventsCarryTheKeyId` covers the app calls, which nothing asserted. It also asserts that no revoke event carries issuedByUserId.

### FR-FLOW-002 AC-5

- **Old (second sentence):** BOTH with .trustedOrigins("https://spa.example") and app.cors allowedOrigins holding https://spa.example and capacitor://localhost: POST /auth/login with Origin https://spa.example (trusted but not the request's own origin) -> 200 cookie delivery (Set-Cookie for naru_at, naru_rt and naru_csrf, body csrfToken only, no accessToken); the same login with Origin capacitor://localhost (in allowedOrigins, not in trustedOrigins) -> 200 body delivery with accessToken and no Set-Cookie (00 §6.4 row "R29 × BOTH 응답 모양", R29 (e) ②, (h)).
- **New (second sentence):** BOTH with .trustedOrigins("https://spa.example") and app.cors allowedOrigins holding https://spa.example and http://localhost: POST /auth/login with Origin https://spa.example (trusted but not the request's own origin) -> 200 cookie delivery (Set-Cookie for naru_at, naru_rt and naru_csrf, body csrfToken only, no accessToken); the same login with Origin http://localhost (in allowedOrigins, not in trustedOrigins) or Origin capacitor://localhost (not in trustedOrigins and not listable in allowedOrigins, OPS-FLOW-001 AC-3) -> 200 body delivery with accessToken and no Set-Cookie (00 §6.4 row "R29 × BOTH 응답 모양", R29 (e) ②, (h)).
- **Added at the end:** Decision (2026-11-05): CorsOptions.allowedOrigins takes http(s) origins only, so capacitor://localhost cannot be listed there (OPS-FLOW-001 AC-3); the delivery rule reads trustedOrigins and not allowedOrigins, so that origin gets body delivery and a trusted http(s) origin gets cookies.
- **Tests:** `Wave8LoginTest#FR_FLOW_002_AC5_trustedOriginBesideCors` already ran this set-up and needed no change. Its "partial" note was removed.

### OPS-AUTH-011 AC-10

- **Old:** The token-issue example of both auth guides finds the user by a username it created (no undefined `id`).
- **New:** The token-issue example of both auth guides passes auth.tokens().issue the value auth.users().create(...) returned for the username it created (no undefined `id`). Decision (2026-11-05): the examples keep the value create() returns, which is simpler than finding the user again, and the undefined `id` is gone.
- **Tests:** new `AuthGuideRound3DocsTest#OPS_AUTH_011_AC10_tokenExampleIssuesTheCreatedUser` checks every token-issue block in both guides. The whole-file row VE-1 was deleted.

### OPS-AUTH-022 AC-1 and AC-2

- **Old AC-1:** Without .apiKeys(store), a 401 for an Authorization value in the API-key scheme or of the nk_ form says `API keys are off` and names .apiKeys(store).
- **New AC-1:** Without .apiKeys(store), a 401 for an Authorization value in the API-key scheme or of the nk_ form (a bare nk_ value, Token nk_... or Api-Key nk_...) says `API keys are off` and names .apiKeys(store). Authorization: Bearer nk_... answers per OPS-AUTH-021 AC-8 under HEADER and BOTH. Decision (2026-11-05): a Bearer value is read as an access token first, and OPS-AUTH-021 AC-8 owns that answer; it still names the API-key scheme, and the next try with that scheme gets this message.
- **Old AC-2:** With API keys on, a 401 for an Authorization value holding an nk_ key without the API-key scheme says `an API key goes in the Authorization header as <scheme> <key>`.
- **New AC-2:** With API keys on, a 401 for an Authorization value holding an nk_ key without the API-key scheme (a bare nk_ value, Token nk_... or Api-Key nk_...) says `an API key goes in the Authorization header as <scheme> <key>`. Authorization: Bearer nk_... answers per OPS-AUTH-021 AC-8 under HEADER and BOTH. Decision (2026-11-05): a Bearer value is read as an access token first, and OPS-AUTH-021 AC-8 owns that answer, which already names the API-key scheme.
- **Narrower than the brief:** the Bearer sentence is limited to HEADER and BOTH. Under COOKIE, auth does not read a Bearer token, so OPS-AUTH-021 AC-8 does not apply; AC-3 covers that mode.
- **Tests:** new `JuniorRound10Test#OPS_AUTH_022_AC1_AC2_bearerKeyAnswersPerOpsAuth021Ac8` runs keys off and on under HEADER and BOTH. The keys-on case was only read from the code before. Both nk_ form loops now cover the bare value, `Token` and `Api-Key`. The whole-file row VE-1 was deleted.

### SEC-AUTH-002 AC-1

- **Old:** AuthMode.fromEnv() returns DEV when NARU_ENV=dev, and PROD when NARU_ENV is unset, empty, prod or any other value; a builder without .mode(...) runs as PROD.
- **New:** AuthMode.fromEnv() returns DEV when NARU_ENV is dev in any letter case (dev, DEV, Dev), and PROD when NARU_ENV is unset, empty, prod or any other value (including a value with surrounding whitespace); a builder without .mode(...) runs as PROD. Decision (2026-11-05): the match ignores letter case, as AuthMode.java, AuthBuilderTest and both auth guides state; only a value that spells dev turns DEV on, so the match never widens to another word.
- **Safety check:** `AuthMode.fromEnvValue` lowercases with `Locale.ROOT`, so a Turkish default locale cannot change the match. ` dev` and `develop` stay PROD.
- **Tests:** `AuthBuilderTest#SEC_AUTH_002_AC1_fromEnvAndDefaultMode` gained `Dev`.

### SEC-AUTH-004 AC-4

- **Old (start):** app.cors(c -> c.origin("https://app.example.com")) then install:
- **New (start):** app.cors(c -> c.allowedOrigins("https://app.example.com")) then install:
- **Added at the end:** Decision (2026-11-05): the CorsOptions method is allowedOrigins; no c.origin(...) exists, so the AC names the method the code and the docs use.
- **Tests:** `PreAuthOrderTest#SEC_AUTH_004_AC4_corsAnswersPreflightWithoutAToken` already used `allowedOrigins`.

## Evidence

Each of the 12 now has real `path#method` rows, and all 107 references were checked against the test sources. The stale "open", "partial" and "PRODUCT-GAP" notes are gone. Three whole-file rows were deleted: VE-1 of OPS-AUTH-011, OPS-AUTH-016 and OPS-AUTH-022.

The stale gap comments in `JuniorMessagesTest`, `JuniorRound10Test` and `Wave8LoginTest` were corrected too.

## Noticed, not changed

1. **iOS Capacitor cannot read a cross-origin answer from a browser context.** `DefaultCors` tests `CorsValues.isOrigin(origin)` before it looks at the list, so `capacitor://localhost` gets no `Access-Control-Allow-Origin` even with `allowedOrigins("*")`. FR-FLOW-002 AC-5 holds at the server, and `docs/api/auth.md` says the origin cannot be listed yet, but it does not say that `*` fails too. A client that bypasses browser CORS is unaffected. The FR-FLOW-002 research note names Capacitor as a reason for body delivery, so this limit is worth a decision.
2. **`auth.apiKeys().revoke` has no target.** `docs/api/auth.md:1994` says the target of `api_key_revoked` is the owner, for the endpoints and for this call. The app call sets no `targetId`, because `deleteById` does not return the owner. No AC asks for it, so either the code or that sentence should change.
3. **Three Requirement sections still use the old rule.** Only the ACs were in scope:
   - OPS-REL-010 says the gate keeps 0.11.0 pending.
   - FR-AUTH-005 says install throws on a ROUTER_MOUNT, without the second-Auth message.
   - SEC-AUTH-002 says "only when NARU_ENV is dev".
4. **A Bearer `nk_` value with keys off takes two tries.** The client is told to use `ApiKey`, and only that retry says keys are off. This is now the stated contract.
