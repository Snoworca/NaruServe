# Open questions — 0.11.0 auth/RBAC SRS

Values or names that 00 (edition 33) does not fix. They are left out of ACs and are decided during implementation (CLAUDE.md: (미확정) items). Collected from the writer and applier records of the kiwi-srs run and deduplicated by text; some may already be settled by later SRS edits, so check the current requirement before acting on one.

Total: 143

## APP (4)

| # | Requirement | Question | Source |
|---|---|---|---|
| Q1 | - | C4: public representation of a Pattern/array path (FR-APP-026 AC-5 only requires it be distinguishable). | srs_delta_CORE.json |
| Q2 | - | Name and shape of the record accessor that exposes the ROUTER_MOUNT record a late Router registration was expanded under (FR-APP-026 AC-4, name not final). | iter1/applied_B1.json |
| Q3 | - | IR-APP-008 AC-1: 00 does not fix whether registrations() returns a call-time snapshot or a concurrency-safe live view. Left to implementation and to be documented in docs/api/app.md. | iter3/applied_B1.json |
| Q4 | - | FR-APP-026 AC-2 vs AC-7: a GROUP is recorded before its inner replays, and each replay is a separate registration. A watcher veto on an inner group route therefore leaves the GROUP record and any earlier inner routes in place, unlike the atomic populated-Router mount. GROUP is pre-start only (refused after start), so this only affects pre-start doctor reading. 00 is silent. | iter3/applied_B1.json |

## AUTH (22)

| # | Requirement | Question | Source |
|---|---|---|---|
| Q5 | - | Which 403 code does auth.check answer when the AccessRule returns false (insufficient_scope or another), and does an HttpException thrown by the rule pass its status through (03 sec 8.1 says yes)? 00 is silent; IR-AUTH-004 AC-8 states only '403 JSON'. | iter1/applied_B1.json |
| Q6 | - | Whether auth.adminRoutes() follows a configured .mountPath(String) (<mount>/admin) or stays at /auth/admin: 00 §3 step 3 shows only the default app.use("/auth/admin", ...). FR-AUTH-005, IR-IAM-005 and SEC-FLOW-003 leave it open; no AC asserts it. Same question decides whether /auth/admin is inside the protectOnly range when the mount is configured. | iter10/applied_ALL.json |
| Q7 | - | Whether a user AuthResponses may change headers (for example WWW-Authenticate) as well as the body: 00 §7 says only 'response shape'; FR-AUTH-006 AC-6 asserts body and status only. | iter10/applied_ALL.json |
| Q8 | - | AuthResponses method set and the Auth.Builder setter name are not given by 00 (marked not final in FR-AUTH-006 AC-6). | iter10/applied_ALL.json |
| Q9 | - | FR-AUTH-006: whether an app AuthResponses may reshape token and user success bodies (FR-FLOW-002, FR-FLOW-006) is not given by 00; not asserted in 0.11.0. | iter11/applied_ALL.json |
| Q10 | - | A1 GET /auth/admin/users for a UserStore that does not override list: 05 §3.4/§4.2 says the default throws UnsupportedOperationException and A1 answers 501 NOT_SUPPORTED; 00 is silent and NOT_SUPPORTED is not in the IR-AUTH-003 catalog. Left out of the ACs. | iter12/applied_ALL.json |
| Q11 | - | Auth router ErrorMiddleware handling of the auth-internal IllegalArgumentException subclass: 00 R30 (2) names only HttpException, AuthRejection, store and other exceptions, so FR-AUTH-006 AC-3 does not add an IAE-to-400 branch. Auth-router handlers must therefore throw a Core HttpException for input 400s (as SEC-IAM-001 AC-6 now states). | iter12/applied_ALL.json |
| Q12 | - | 00 does not say whether AuthUnavailableException codes other than SERVICE_UNAVAILABLE and security_write_conflict are allowed. IR-AUTH-003 AC-4 states that the only other auth 503 code is security_write_conflict. | iter13/applied_ALL.json |
| Q13 | IR-AUTH-002 | 00 does not say whether auth.errors()/auth.errorMiddleware() emit a security_write_conflict audit event when an app handler calling auth.userStore().setEnabled throws the exception (IR-AUTH-002 AC-1 app-handler case). It is left out of the ACs and recorded in the REL-IAM-001 research. | iter14/applied_ALL.json |
| Q14 | - | IR-AUTH-003 AC-3: 00 gives no code for the 409 answers (duplicate username or rename, lost password race, group cycle or depth, group-delete 1,000-user limit). Undecided: lower-case auth code(s) or Core HTTP_409, and one shared code or separate codes. This is decided at implementation and recorded in docs/api. | iter17/applied_ALL.json |
| Q15 | - | IR-AUTH-003 AC-3: the status and code of the POST /auth/setup rejection and the 403 codes of requireRole/auth.check stay open (00 silent); chosen at implementation and recorded in docs/api. | iter18/applied_ALL.json |
| Q16 | - | 00 R41 13판: is a role named 'admin' that a replaceBuiltinRoles() policy defines with '*' (for example .replaceBuiltinRoles().role("admin", "*")) a 'RolePolicy role granting *' (counts) or a 'replaced built-in role' (does not count)? 00 does not say. FR-AUTH-004 AC-5 uses the role name 'root' for the passing case, so it decides neither way. | iter19/applied_ALL.json |
| Q17 | - | In DEV, does the short/example-secret key-strength WARN come from build() (logged) or from doctor in onStart? 00 R41 only says DEV turns key-strength FAIL into WARN; SEC-AUTH-001 AC-8 says only 'reported as a WARN'. | iter2/applied_B1.json |
| Q18 | - | Which 401 code does the guard answer for an expired token on an out-of-range (or permitPrefix) path where the global middleware left the request anonymous: unauthenticated or invalid_token? SEC-AUTH-006 AC-7 states only '401'. | iter2/applied_B1.json |
| Q19 | - | FR-AUTH-003 AC-6: per-profile policy values (incl. access/refresh lifetimes) and the default profile are chosen at implementation (00 §10 Q1); 07 §1.3 figures are advisory only. | iter20/applied_ALL.json |
| Q20 | - | SEC-AUTH-006 / FR-AUTH-005: whether auth.adminRoutes() follows .mountPath stays open (00 does not say); ACs assert only that the admin mount is in the protectOnly range wherever it is. | iter20/applied_ALL.json |
| Q21 | - | FR-AUTH-009: 00 names only AuditSinks. The shipped factory set and names (07 proposes logger/json/async) are still open and are decided at implementation; AC-6 needs only at least one factory. | iter24/applied_ALL.json |
| Q22 | - | IR-AUTH-003: does the wrong-current-password 401 code reuse invalid_credentials or have its own code? 00 R47 (10) leaves both the name and this choice open. Recorded in the research section. | iter5/applied_RESIDUAL.json |
| Q23 | - | Status and error code for a POST /auth/setup rejected because it is not local (secure non-local, or loopback with X-Forwarded-*). 00 §4.4/R33 require rejection but give no status/code; SEC-AUTH-003 AC-8 leaves it open. | iter6/applied_ALL.json |
| Q24 | - | Hash-cost doctor item threshold and level: 00 R41 implies a PROD FAIL that DEV downgrades, 04 §6.2 gives only a PROD WARN (>1 s or <100,000 iterations). Affects OPS-AUTH-001 AC-2, SEC-AUTH-002 AC-3, PERF-TOKEN-001 AC-5. | iter6/applied_ALL.json |
| Q25 | - | Store connectivity, registration-enabled and clock doctor triggers/levels are not in 00; OPS-AUTH-001 AC-2 uses 04 §6.2 values tagged [INFERRED:med:advisory] pending confirmation. | iter6/applied_ALL.json |
| Q26 | - | In DEV, is the weak-key WARN emitted by build() or by the doctor report (SEC-AUTH-001 AC-8, OPS-AUTH-001 AC-2)? Carried from iter2 missing-5. | iter6/applied_ALL.json |

## TOKEN (19)

| # | Requirement | Question | Source |
|---|---|---|---|
| Q27 | SEC-TOKEN-006 | Default refresh reuse grace window (reuseGrace): 0 s vs a few seconds (00 §10 Q2). AC pins only the 0 s behaviour; 05 proposes default 0 with 'within grace: 401 only, no revocation'. | srs_delta_TOKEN.json |
| Q28 | SEC-TOKEN-005 | Derivation labels for the access-token and refresh-token keys are not given in 00 (only naru-device and naru-device-fp are). Choose labels at implementation and fix them in docs/api. | srs_delta_TOKEN.json |
| Q29 | SEC-TOKEN-003 | Verification of a token without kid when the key set has a single key: 01 §2.4 allows it, 00 R8 says always issue kid. Left out of the ACs. | srs_delta_TOKEN.json |
| Q30 | PERF-TOKEN-001 | Default HashCost when the app configures none, and whether calibrate(target) rejects targets outside 250-500 ms (R5 gives the range without saying how it is enforced). | srs_delta_TOKEN.json |
| Q31 | FR-TOKEN-004 | Default PasswordPolicy minimum length (00 §10 Q1: 8 vs 15) and the HTTP status/error code for a policy violation (05 uses 422 WEAK_PASSWORD in upper case, conflicting with 00's lower-case code rule). | srs_delta_TOKEN.json |
| Q32 | REL-TOKEN-001 | Retry-After value on slot backpressure 503 is not fixed by 00 (06 suggests 1 s). | srs_delta_TOKEN.json |
| Q33 | SEC-TOKEN-002 | Behaviour when an auth file exceeds the FILE limit: R43 says doctor WARN advising JDBC, but does not say whether reading fails above the cap or whether the WARN fires before reaching it. KB interpreted as KiB. | srs_delta_TOKEN.json |
| Q34 | FR-TOKEN-002 | RefreshTokenStore method set and RefreshRecord fields are not tabled in 00 (05 §3.9 proposal used as advisory). revokeUser from 05 has no 00 counterpart (00 R1 uses ver++ for ?all=true). | srs_delta_TOKEN.json |
| Q35 | IR-TOKEN-001 | Method shapes of the Authenticator SPI for Bearer and API key credentials are not given in 00; only the password seam is proposed in 05 §3.9. | srs_delta_TOKEN.json |
| Q36 | SEC-TOKEN-004 | KeySources.rotating(...) argument and file format, and the package of KeySources (utils.auth vs spi), are not fixed by 00. | srs_delta_TOKEN.json |
| Q37 | - | 00 internal ambiguity: R45 says 'R이 없으면 ver++' (store-less logout), but R42 always selects a default RefreshTokenStore. TOKEN/FLOW now settle it as 'no store-less mode in v1' (FR-TOKEN-002 Research). Confirm or add a builder switch in a later edition. | iter1/applied_B2.json |
| Q38 | - | Default access/refresh TTL: 05 §3.1 and 07 BASELINE give 15 min / 14 d, 07 STANDARD 15 min / 1 d, 07 STRICT 5 min / 12 h. This depends on the default profile (00 §10 Q1). FR-TOKEN-001 AC-6 now ties the defaults to the active AuthProfile. | iter19/applied_ALL.json |
| Q39 | - | wrong-5: 00 R38 says 'every failure response (incl. 503, excl. 429)' is floored, and the R29 (f) decision order puts the transport, credential, 415, body-token and CSRF/Origin checks before the handler. 00 does not say whether the in-handler request-shape 400 (missing, non-string or empty password) is floored. SEC-TOKEN-009 now says it is not, because the response does not depend on the account and runs no hash, like the exempt 429. Confirm at implementation. | iter2/applied_B2.json |
| Q40 | - | SEC-TOKEN-006: the behaviour of a second use inside a non-zero reuse grace window (05 §5.4 proposes '401 only, no revocation') is not fixed by 00 §10 Q2, so no within-grace AC was added. | iter20/applied_ALL.json |
| Q41 | - | 06 AUTH-T24 also expects a 129-character password on login to get 400 with zero hash calls. FR-TOKEN-004 does not apply the policy to login, and 00 gives no max-length rule for login, so no login max-length AC was added. | iter3/applied_B2.json |
| Q42 | - | 00 R43 says an over-limit file store gives a doctor WARN while R42 makes an unreadable main file a FAIL. SEC-TOKEN-002 AC-6 picks FAIL for an over-limit file on read; whether a WARN is also due for a file near the limit or a write that would exceed it is open. | iter6/applied_ALL.json |
| Q43 | - | RefreshTokenStore.purgeExpired name, signature and default body (no-op vs. scan) are not in 00; FR-TOKEN-002 uses the 05 §3.9 proposal (name not final). | iter6/applied_ALL.json |
| Q44 | - | TokenCodec method set, claims type and builder option name (.tokenCodec vs .tokens) - 05 §3.8 and 07 differ; IR-TOKEN-002 marks them not final. | iter6/applied_ALL.json |
| Q45 | - | PERF-TOKEN-001: T under HashCost.fixed(n) stays open; SEC-TOKEN-009 AC-2 and FR-APIKEY-003 AC-8 now use calibrate(250 ms). | iter7/applied_ALL.json |

## FLOW (17)

| # | Requirement | Question | Source |
|---|---|---|---|
| Q46 | - | Failed-login 401 code: 00 does not restate it; FR-FLOW-004 AC-2 uses invalid_credentials (05 INVALID_CREDENTIALS lower-cased by the R30 code rule) tagged [INFERRED:med:advisory]. | srs_delta_FLOW.json |
| Q47 | - | Refresh failure 401 code: FR-FLOW-005 AC-4 uses invalid_refresh_token (05) tagged [INFERRED:med:advisory]; 00 only says '401'. | srs_delta_FLOW.json |
| Q48 | - | Wrong current password on POST /auth/password: SEC-FLOW-014 AC-2 uses 403 current_password_invalid from 05 [INFERRED]; 00 does not restate it. | srs_delta_FLOW.json |
| Q49 | - | PATCH /auth/me with a non-allowed attribute: 05 says 422 ATTRIBUTE_NOT_ALLOWED, 00 R30 puts auth input checks under 400 BAD_REQUEST; FR-FLOW-006 AC-3 fixes only 'rejected, unchanged'. PATCH /auth/me success status 200 is from 05 [INFERRED]. | srs_delta_FLOW.json |
| Q50 | - | SEC-FLOW-014 Research and FR-FLOW-001/SEC-FLOW-005 Implementation Notes need edits that the MCP toolset cannot make (see rejected). | iter1/applied_B2.json |
| Q51 | - | SEC-FLOW-014 / FR-FLOW-006: 00 gives no body field names for POST /auth/password or PATCH /auth/me. The names from 05 §4.1 (currentPassword/newPassword, {attributes:{...}}) are kept as [INFERRED:med:advisory], name not final. | iter17/applied_ALL.json |
| Q52 | - | Whether POST /auth/setup is registered while setup is closed is not fixed by 00. IR-FLOW-002 AC-1 chose 'registered whatever the setup state, answers 404 NOT_FOUND while closed', because setup closes at runtime (SEC-AUTH-003 AC-2/AC-4). | iter19/applied_ALL.json |
| Q53 | - | FR-FLOW-006 AC-3: the status and code for an attribute not in allowedAttributes (05: 422 ATTRIBUTE_NOT_ALLOWED; 00 R30: auth input checks give 400 BAD_REQUEST) are not fixed by 00. | iter2/applied_B2.json |
| Q54 | - | FR-FLOW-009: the lockout refusal status/code and whether DELETE /auth/admin/users/:id/lockout clears throttle keys are still not given by 00 (left open; SEC-FLOW-012 AC-6). | iter21/applied_ALL.json |
| Q55 | - | Status and code of the Lockout login refusal (FR-FLOW-009 AC-2) and of the R31 (3) self-rule refusal (SEC-IAM-004 AC-3/AC-6): 00 gives neither. They are chosen at implementation under IR-AUTH-003 AC-3. | iter22/applied_ALL.json |
| Q56 | - | Whether DELETE /auth/admin/users/:id/lockout clears the user-only throttle key, with or without a configured Lockout (SEC-FLOW-012 AC-6). | iter22/applied_ALL.json |
| Q57 | - | Whether a non-JSON A2/A4 body from a key that fails the R39 guard gets the FR-FLOW-001 415 or 403 insufficient_scope (SEC-APIKEY-008 implementation note). 00 gives no order. | iter22/applied_ALL.json |
| Q58 | - | Default TokenTransport: 00 says nothing; HEADER comes from 05 §1 item 7 and is marked [INFERRED:med:advisory] in SEC-FLOW-004. | iter3/applied_B2.json |
| Q59 | - | Name of the wrong-current-password 401 code shared by SEC-FLOW-014 AC-2 and FR-APIKEY-003/004: 00 R47 (10) and the owner-decision lines leave it open; IR-AUTH-003 AC-3 keeps it as (name not final). | iter5/applied_FINAL.json |
| Q60 | - | SEC-FLOW-013 AC-9: DeviceTrust SPI method set and the builder option name are not given by 00 (marked not final). | iter7/applied_ALL.json |
| Q61 | - | 00 does not give the 401 body code for an expired or revoked naru_rt on POST /auth/refresh (FR-FLOW-005 AC-4 infers invalid_refresh_token only for a missing token); SEC-FLOW-008 AC-9 therefore compares against the same request carrying a correct CSRF header instead of naming a code. | iter8/applied_ALL.json |
| Q62 | - | 00 R45 defines a valid logout identity as passing the same checks as a protected path. It does not say whether .statelessAccess(true) (R44: access tokens skip the ver check on unguarded routes) relaxes the ver check for a logout identity that comes from an access token. SEC-FLOW-010 AC-13 runs with the default configuration only. | iter9/applied_ALL.json |

## IAM (22)

| # | Requirement | Question | Source |
|---|---|---|---|
| Q63 | - | HTTP status/code when group expansion exceeds depth 8 or size 256 at request time: 00 says only 'error, not truncation'; 05 says GroupLimitException -> 500. FR-IAM-007 AC-3 requires only a non-allowing JSON error. | srs_delta_IAM.json |
| Q64 | - | Whether R31 grant checks apply to app-side AuthUsers calls: 00 states only that auth.apiKeys() skips R31 (R47 (5)); SEC-IAM-004 implementationNotes assume AuthUsers is an app-trusted path too — confirm. | srs_delta_IAM.json |
| Q65 | - | Whether JdbcUserStore offers a dev-only createTablesIfMissing (04 §3.3) — 00 is silent; FR-IAM-010 AC-6 only requires no automatic CREATE TABLE. | srs_delta_IAM.json |
| Q66 | - | Recovery (SEC-IAM-006) overlaps the AUTH inventory item 'First-admin setup endpoint and recovery mode'; main session should dedupe or keep SEC-IAM-006 as the user-state outcome and AUTH as endpoint mechanics. | srs_delta_IAM.json |
| Q67 | - | JdbcRefreshTokenStore (FR-IAM-012) may overlap TOKEN item 'RefreshTokenStore SPI with in-memory, file and JDBC defaults'; dedupe default-selection ACs. | srs_delta_IAM.json |
| Q68 | SEC-IAM-007 / FR-APIKEY-004 | 00 does not say where the R39 auth-apikeys guard sits relative to FR-APIKEY-004's body 400. SEC-IAM-007 models it as a per-route guard before the handler (AC-7 INFERRED), so a missing-scope actor gets 403 before body validation; confirm during implementation. | iter1/applied_B3.json |
| Q69 | SEC-IAM-001 / IR-IAM-003 | 00 R47 (7) lets auth.users().create make a service account without a password but does not give the API shape (nullable password on the init overload vs. a separate overload) nor the exception/code for create with the flag and a non-null password (A2 HTTP gives 400 service_account_no_password). Also unstated: create with a null password and no service-account flag. | iter1/applied_B3.json |
| Q70 | SEC-IAM-004 | 00 R31 (3) gives no HTTP status/code for a self-rule refusal (05 used 409 CANNOT_DELETE_SELF); recorded as Open in Research. | iter1/applied_B3.json |
| Q71 | - | Whether A2 attribute keys id, username and serviceAccount (the extra SRS mutator fields) are also reserved: 00 R37 names only passwordHash, ver, enabled, roles as security fields, so SEC-IAM-001 AC-6 tests only those four. | iter10/applied_ALL.json |
| Q72 | - | SEC-IAM-001: 00 names the reserved attribute-key rejection only for A2. Whether admin PATCH A4 (and PATCH /auth/me, already limited by allowedAttributes) should also reject attributes keyed passwordHash/ver/enabled/roles is not stated in 00; current spec treats them as opaque outside A2. | iter11/applied_ALL.json |
| Q73 | - | Whether AuthTesting.verifyUserStore (FR-IAM-004) checks list: not given by 00. | iter12/applied_ALL.json |
| Q74 | FR-IAM-014 | 00 does not give the JSON placement of the applied member in the A4 409/503 body, or the 409 code string for a lost admin password race. Both are marked not final / not given. | iter14/applied_ALL.json |
| Q75 | - | 00 R37 names no audit event type for a non-security_write_conflict store failure after the password in A4 (FR-IAM-014 AC-9(b) asserts only that one event is emitted). | iter15/applied_ALL.json |
| Q76 | - | 00 R37 says any failure after the password is answered 503 with applied, but does not say how an AuthRejection (e.g. a before-event rejection of the profile update) or IllegalArgumentException raised after the password is answered; FR-IAM-014 leaves these on the FR-AUTH-006 AC-3 path. | iter15/applied_ALL.json |
| Q77 | - | 00 R42 says auth files have a format version but gives no behaviour on mismatch; REL-IAM-004 AC-7 reuses FR-IAM-002 AC-3 (fail startup, no overwrite) for every auth store file as instructed. | iter15/applied_ALL.json |
| Q78 | - | REL-IAM-001: re-entrancy is derived from 00 R37 '같은 락' plus the R47 (2) lock order (00 does not say 're-entrant' in words). | iter18/applied_ALL.json |
| Q79 | - | REL-IAM-002 AC-7: 00 does not say whether an unconfirmed changePassword write emits a security_write_conflict audit event (stated only for setEnabled/bumpTokenVersion/setRoles); left out of the AC. | iter2/applied_B3.json |
| Q80 | - | SEC-IAM-004 AC-3: self-rule refusal HTTP status/code still not given by 00 (existing open item). | iter21/applied_ALL.json |
| Q81 | IR-IAM-001 | 00 R40 does not say what a WebSocket recheck does when the UserStore or ApiKeyStore throws during the userCacheTtl re-read (close, keep, or log only). No AC was added. | iter3/applied_B3.json |
| Q82 | REL-IAM-002 | 00 does not name the exception type the SINGLE_INSTANCE default changePassword throws for an unconfirmed write. AC-7 fixes only that it throws and maps to 503 security_write_conflict. | iter3/applied_B3.json |
| Q83 | - | SEC-IAM-005: 00 R31 (2) skip clause vs §6.4 row 'R31 × 그룹 삭제 우회' (387) — does the enumeration skip apply when an actor covering everything the group confers deletes a group that has a * member? Left out of the ACs (AC-8 uses members holding no more than posts:*). | iter7/applied_ALL.json |
| Q84 | - | SEC-IAM-005 AC-2: 'limit checked before per-user cover check' is read from row 565 ('위임 관리자는 409'); 00 states no explicit order. | iter7/applied_ALL.json |

## APIKEY (7)

| # | Requirement | Question | Source |
|---|---|---|---|
| Q85 | FR-APIKEY-002 | 00 does not say what the cleanup scheduler does when deleteExpired (or purgeExpired) throws: log level, whether that run's other tasks still run, and that later runs continue. AC-5 asserts only that responses are unchanged (IR-APIKEY-004 AC-6). The suggested 'logged, next run still happens' was left out. | iter16/applied_ALL.json |
| Q86 | - | REL-APIKEY-003: the re-entrant exclusion assumes the R37 per-user write lock can be re-entered by the thread that holds it. That follows from 00, where the auth write path holds the lock and the default field methods take it again, but neither 00 nor REL-IAM-001 says it outright. REL-IAM-001 may want an explicit AC for re-entrancy. | iter17/applied_ALL.json |
| Q87 | - | REL-APIKEY-005 / fabricated-1: whether JdbcApiKeyStore overrides countKeysWithAdminScopes() is not given by 00 (only the default -1 and the doctor/request-time WARN fallback). Implementation decision; if overridden, add an AC tagged [INFERRED:low:advisory]. | iter2/applied_B3.json |
| Q88 | - | IR-APIKEY-004 AC-6: 00 does not say whether failures of off-request deletions (scheduled deleteStaleByOwner, invariant-violation deleteById, scheduled re-delete, deleteExpired) are logged; AC only fixes that the response is unchanged. | iter2/applied_B3.json |
| Q89 | - | SEC-APIKEY-008 Implementation Notes could not be extended: edit_requirement_fields has no implementationNotes field (dry run with implementationNotes updated nothing). The rule now lives in AC-11. The note still says the router 'reads the A2/A4 JSON body as a Map' and does not add that a failed read decides nothing; if a notes-editing tool exists, add: 'A step-10 body read that fails (empty, malformed, non-object, over the limit) applies no body-level ban and answers nothing itself; the step-11 guard decides next and only a passing request gets the Core 400/413 (AC-11).' | iter25/applied_ALL.json |
| Q90 | - | SEC-APIKEY-008 AC-11 (iv) uses 'the JSON body limit' without a number; 00 gives no auth-specific value, so it relies on Core's configured JSON limit. | iter25/applied_ALL.json |
| Q91 | - | 00 does not fix whether PAT issuance (POST /auth/me/apikeys) runs body validation before the password check; FR-APIKEY-005 AC-7 asserts no password hash only for admin issuance. | iter8/applied_ALL.json |

## GENERAL (52)

| # | Requirement | Question | Source |
|---|---|---|---|
| Q92 | - | Name of the key table and its column names in the reference DDL | srs_delta_APIKEY.json |
| Q93 | - | Final names of .apiKeyFailureLimit(int,long) and the ApiKeyStore lastUsedAt update method | srs_delta_APIKEY.json |
| Q94 | - | Name of the accessor returning AuthPrincipal for a request | srs_delta_APIKEY.json |
| Q95 | - | Exact 401 code for a wrong current password on PAT/admin issuance | srs_delta_APIKEY.json |
| Q96 | - | Name of the service-account flag in A2 body / AuthUsers.create | srs_delta_APIKEY.json |
| Q97 | - | Java type of ownerVer | srs_delta_APIKEY.json |
| Q98 | - | Default AuthProfile and STANDARD minimum password length (00 §10 Q1; 07 recommends STANDARD with min 8 vs NIST 15). Per-profile numbers exist only in stale 07 §1.3. | srs_delta_AUTH.json |
| Q99 | - | AuthConfig file key names and precedence between code, file and env; whether plaintext secrets in the file are rejected at STANDARD+ (07 §7.2 only). | srs_delta_AUTH.json |
| Q100 | - | 403 code for auth.requireRole failure (00 gives none; insufficient_scope assumed but not written into the AC). | srs_delta_AUTH.json |
| Q101 | - | AuthTesting location: main utils.auth.testing vs NaruServe-api testFixtures (00 §10 Q4). | srs_delta_AUTH.json |
| Q102 | - | AuditEvent type/outcome value sets and which AuditSinks factories ship in 0.11.0 (07 §6.1 only). | srs_delta_AUTH.json |
| Q103 | - | Setup token format, length and lifetime (00 silent; 04 §8.1 shows 30 min). | srs_delta_AUTH.json |
| Q104 | - | Names not final: TimeSource builder setter and system instance, AuthErrorCode members, describe() format, doctor report type, userCacheTtl setter, csrfTokenFor, app.onRegistration/registrations. | srs_delta_AUTH.json |
| Q105 | - | Whether calling Auth.Builder.build() twice is allowed (00 silent). | srs_delta_AUTH.json |
| Q106 | - | C4: what getMethod() and getTarget() hold for ROUTE, GROUP, STATIC, CORS and PARAM records, and whether a route(path) builder yields one record per method call (00 §8 silent). Left out of ACs; decide during implementation. | srs_delta_CORE.json |
| Q107 | - | C4: a Router mounted under two prefixes or into two apps; how late additions are reported (once per mount?). | srs_delta_CORE.json |
| Q108 | - | C4: watcher that registers again from inside the watcher (re-entrancy), and whether later watchers still run after the first one throws. | srs_delta_CORE.json |
| Q109 | - | C6b: behaviour when legacy trustProxy(true) and trustProxyHops/trustProxyCidrs are both set (last call wins or reject). | srs_delta_CORE.json |
| Q110 | - | C6b: read accessor names and the 'unset' representation (00 gives no names). | srs_delta_CORE.json |
| Q111 | - | C6b: whether trustProxyCidrs accepts a bare address without /prefix, and whether trustProxyCidrs() with no argument clears trust. | srs_delta_CORE.json |
| Q112 | - | C6b hop mode: result when XFF has fewer than n entries, or blank/malformed entries. | srs_delta_CORE.json |
| Q113 | - | C6b CIDR mode: result when every XFF entry is inside the trusted CIDRs; whether an IPv4-mapped IPv6 peer (::ffff:a.b.c.d) matches an IPv4 CIDR. | srs_delta_CORE.json |
| Q114 | - | C6b XFP: case-insensitive comparison of 'https' and combination of several XFP header lines in hop/CIDR modes. | srs_delta_CORE.json |
| Q115 | - | R32 runtime WARN 'XFF seen with no trust configured, once per run': assumed to be emitted by auth, not Core; confirm ownership with the AUTH writer. | srs_delta_CORE.json |
| Q116 | - | Servlet: container-level forwarding (Tomcat RemoteIpValve, Jetty ForwardedRequestCustomizer) stacking with Core trust is documented as a pitfall only; whether Core should detect it is open. | srs_delta_CORE.json |
| Q117 | - | CSRF token HMAC key derivation is not specified in 00 (device tokens use HMAC(secret,'naru-device')); token string format base64url(random16).HMAC is from 05 [INFERRED]. | srs_delta_FLOW.json |
| Q118 | - | naru_device cookie attributes (Path, HttpOnly, SameSite, Max-Age) are not given in 00 or 05; other cookie defaults (HttpOnly/SameSite/Path/Max-Age/Secure) are from 05 only [INFERRED]. | srs_delta_FLOW.json |
| Q119 | - | DeviceTrust SPI method signatures are not specified in 00 or 05. | srs_delta_FLOW.json |
| Q120 | - | Lockout: response status/code for a locked account, policy parameters and builder shape (07 proposes .lockout(Lockout.after(5).forMinutes(15)), tagged INFERRED, name not final), and success status of DELETE /auth/admin/users/:id/lockout are not given. | srs_delta_FLOW.json |
| Q121 | - | Builder method names for login-throttle limits ('수치는 빌더로 바꾼다', R34 (1)) are not named. | srs_delta_FLOW.json |
| Q122 | - | allowInsecureTransport: handling of null/empty reason and how often the 'cookies without Secure' warning is logged are not specified. | srs_delta_FLOW.json |
| Q123 | - | Whether the JWT 403 insufficient_scope challenge also carries scope="<s>" (05 §4.0) is not restated by 00. | srs_delta_FLOW.json |
| Q124 | - | Cache-Control: no-store on token responses comes from 05 only [INFERRED]. | srs_delta_FLOW.json |
| Q125 | - | Response status when POST /auth/register is disabled (404 vs other) is not specified; ACs only assert no user and not 201. | srs_delta_FLOW.json |
| Q126 | - | Browser WebSocket token transport recommendation (cookie mode vs Sec-WebSocket-Protocol) is open per 00 §10 Q3. | srs_delta_FLOW.json |
| Q127 | - | LoginThrottle return-value semantics (0 allow, positive ms) are from 05 §3.9 [INFERRED]. | srs_delta_FLOW.json |
| Q128 | - | Ownership: inventory items 'Admin user and group endpoints' and 'Admin PATCH A4 application order' sit in the FLOW area but the assignment gives admin endpoints to IAM; FLOW did not write them. Main session should confirm IAM covers A1-A6/G1-G8 + A4 order + DELETE lockout route table. | srs_delta_FLOW.json |
| Q129 | - | AuthUser ver getter name (getTokenVersion in 05 vs a ver-named getter) and ver column name in DDL (token_version vs ver) — not fixed by 00; decide at implementation. | srs_delta_IAM.json |
| Q130 | - | Error code strings for group cycle / too-deep 409 (05: GROUP_CYCLE, GROUP_TOO_DEEP uppercase), duplicate group (DuplicateGroupException / GROUP_EXISTS), and the 409 for >1,000 affected users in R31 (2): 00 gives status only. | srs_delta_IAM.json |
| Q131 | - | HTTP status for an invalid group id on G2 (05: 422 INVALID_GROUP_ID; 00 R30 24판 says auth input validation 400 is Core BAD_REQUEST) — FLOW endpoint table decides. | srs_delta_IAM.json |
| Q132 | - | Fixed vs sliding window semantics of JdbcLoginThrottle counters in SQL (00 R42 gives only the atomic count + 1 form). | srs_delta_IAM.json |
| Q133 | - | FileUserStore file layout and whether the 04 §3.2 '>1,000 users -> doctor WARN' applies (not restated by 00). | srs_delta_IAM.json |
| Q134 | - | Exact GroupAdminStore method list and GroupStore.rolesOfGroups name come from 05 §3.5 (00 names only the types) — tagged INFERRED. | srs_delta_IAM.json |
| Q135 | - | Admin endpoints /auth/admin/* (R45) and the user-change event table/UserOrigin were listed as IAM key material but the inventory places them in FLOW (items 90, 91) and AUTH (items 43, 44); IAM wrote no requirement for the endpoint table or event matrix and records them as pending cross links. | srs_delta_IAM.json |
| Q136 | - | On a public path, what does JWT optional authentication do when the UserStore throws while loading the principal? 00 states anonymous plus a rate-limited log only for API key lookups (6.4 rows 'R47 x 키 조회 중 store 오류', 'R47 x 공개 경로 throttle 오류'). Aligning JWT with that rule is plausible but not stated. | iter1/applied_B1.json |
| Q137 | - | Which C4 records does auth.describe() list (pre-install middleware, ROUTE/WEBSOCKET outside the protected range, all)? 00 sec 8 names describe() as a C4 consumer only. | iter1/applied_B1.json |
| Q138 | - | The 401 body code for a wrong current password (/auth/password, PAT and admin key issuance) is left open by 00 R47 (10) ('이름은 열어 둔다'); decide at implementation. 05's refresh-loop concern (why it chose 403) is now an open design point: clients must not treat this 401 as a token expiry. | iter1/applied_B2.json |
| Q139 | - | UserStore.list search matching rule, default and maximum limit, and the total=-1 'unknown' convention of 05: not given by 00. | iter12/applied_ALL.json |
| Q140 | - | 00 names no exception type or accessor for the security_write_conflict signal. It is fixed as AuthUnavailableException carrying a code (constructor/accessor names not final), not a new type, to keep the 00 R30 (6) four-type rule. | iter13/applied_ALL.json |
| Q141 | - | What is T (the 1.25T floor base) under HashCost.fixed(n)? 00 R38 defines T only as the calibration target time. Candidate (unadopted): measured time of one n-iteration hash at startup. | iter6/applied_ALL.json |
| Q142 | - | Out-of-range HashCost.calibrate target: refuse at build() or clamp into 250-500 ms? Exact default target when no HashCost is set (00 R5 gives only the range; §4.2 archetype B uses 250 ms). | iter6/applied_ALL.json |
| Q143 | - | 00 §6.4 row 'R34 × 새 기기 admin' says another admin can release the lockout. Without a configured Lockout, does DELETE /auth/admin/users/:id/lockout clear the user-only (and (user, IP)) login-throttle keys, or answer 404/no-op? | iter6/applied_ALL.json |
