### SEC-FLOW-014 — POST /auth/password self password change with atomic hash swap
#### Requirement

POST /auth/password shall verify the current password under the per-user attempt limit, then call UserStore.changePassword(id, expectedOldHash, expectedVer, newHash) with expectedVer equal to the presenting token's ver as one atomic hash swap with ver++, retrying once only when the newly read hash still verifies the current password, and on success shall return a new token pair on a new family with a new device token and CSRF token through the delivery rule.

#### Acceptance Criteria

- [ ] AC-1: Valid Bearer, a JSON body with a correct currentPassword and a policy-compliant newPassword (body field names from 05 §4.1 #6, name not final; 00 gives no body shape for POST /auth/password) [INFERRED:med:advisory] -> 200 with a new pair whose fam differs from the old family and a new deviceToken; the old access token is rejected afterwards (ver incremented).
- [ ] AC-2: Wrong current password in the AC-1 current-password field -> 401, the hash is unchanged, and the failure counts toward the SEC-FLOW-015 per-user limit; the body code is the same code that FR-APIKEY-003 AC-2 and FR-APIKEY-004 AC-4/AC-7 return for a wrong or null-hash password (code name not final).
- [ ] AC-3: The token's ver differs from the re-read ver, or the user is disabled -> 401 and the password is unchanged.
- [ ] AC-4: changePassword returns false because a concurrent rehash changed the hash and the current password verifies against the newly read hash -> one retry succeeds (200); when it does not verify (an admin reset happened first) -> 409 and no tokens are issued.
- [ ] AC-5: COOKIE, naru_at of family F with F's X-CSRF-Token and a same-origin Origin -> 200 setting naru_at, naru_rt, naru_csrf of the new family F2 and naru_device, body csrfToken only; a following POST /api/posts with a same-origin Origin and the new csrfToken -> 201 and with the old F csrf token -> 403 csrf_failed.
- [ ] AC-6: Events fire as beforeUserUpdate (ctx.getOrigin() == UserOrigin.SELF), then onUserUpdated and onPasswordChanged.
- [ ] AC-7: The route is registered with methodAsync and its hashing uses the shared hash slot; the expired-Bearer 401 invalid_token of this subject-required endpoint is owned by SEC-FLOW-003 AC-4.
- [ ] AC-8: Valid Bearer, the AC-1 body with a correct current password and a policy-compliant new password, with a beforeUserUpdate listener that throws new AuthRejection(403, "password_change_locked") for this user -> 403 JSON with code password_change_locked; the listener saw ctx.getOrigin() == UserOrigin.SELF and received neither the hash nor the plaintext; changePassword is not called, the stored hash and ver are unchanged (the old access token still works), no tokens are issued and onUserUpdated and onPasswordChanged do not fire (00 §6.4 row 'R30 × 본인 비밀번호 변경').
- [ ] AC-9: CSRF and Origin on this subject-required endpoint (SEC-FLOW-006, SEC-FLOW-007; 00 R29 (e)): in COOKIE, and in BOTH with no Authorization header, a valid naru_at of family F and a correct current password in the AC-1 body: without X-CSRF-Token (same-origin Origin) -> 403 csrf_failed; with F's X-CSRF-Token and naru_csrf but Origin https://evil.example, not in trustedOrigins -> 403 csrf_failed; in each case changePassword is not called, the stored hash and ver are unchanged and no Set-Cookie is sent.


### FR-APIKEY-006 — Service accounts without passwords
#### Requirement

`AuthUser.isServiceAccount()` shall mark a user created through admin A2 or `auth.users().create` with the service-account flag and no password (`passwordHash` null); such a user shall authenticate only by API key, its password login shall behave exactly like a wrong password, the flag shall be immutable after creation, an admin A2 or A4 or a recovery that would give it a password shall be 400 `service_account_no_password` (00 R47 (10)), except that a key actor's password-setting write (A2 or A4) is refused first with 403 `api_key_not_allowed` (SEC-APIKEY-008), the app call `auth.users().create` with the flag and a non-null password shall throw `IllegalArgumentException` (the init rule of SEC-IAM-001 AC-7), and stores shall persist the flag.

#### Acceptance Criteria

- [ ] AC-1: `POST /auth/login` with a service account's name returns the same 401 as a wrong password, takes the same dummy-hash time, and counts toward the R34 login counters.
- [ ] AC-2: Admin `PATCH` A4 that sets `password` on a service account gets 400 `service_account_no_password`.
- [ ] AC-3: A2 with the service-account flag and a `password` gets 400 `service_account_no_password` when the actor is a JWT admin (a key actor gets 403 `api_key_not_allowed` first, whatever its scopes: SEC-APIKEY-008 AC-7, AC-9).
- [ ] AC-4: Recovery (R31 (6)) whose target is a service account gets 400 `service_account_no_password`.
- [ ] AC-5: A `UserMutator` that changes the service-account flag of an existing user throws `IllegalArgumentException`.
- [ ] AC-6: A service account created through `auth.users().create` with the flag round-trips `isServiceAccount()` true through `InMemoryUserStore`, `FileUserStore` and `JdbcUserStore` (reference DDL has the column).
- [ ] AC-7: Audit events for actions by a service account mark the actor as a service account.


### REL-APIKEY-003 — SINGLE_INSTANCE future-key deletion before ver++ and before issuance
#### Requirement

Under `SINGLE_INSTANCE`, inside the per-owner key lock, which on every auth-triggered `ver++` path is taken while the R37 per-user write lock of that user is already held (one-way lock order, 00 R47 (2)), the auth module shall read the owner's current `ver` from the store and call `deleteFutureByOwner(owner, ver)` before every auth-triggered `ver++` (logout `?all=true`, self-service password change, admin password reset through A4, disable, recovery; 00 R47 (2) lists R44's password change, and 00 R37 runs both the password change and the admin reset through `changePassword`) and then perform that `ver++` inside the same key owner lock (00 §6.3 ApiKeyStore notes, R47 (2) 27th edition), and shall do the same read and call before `insertIfUnderLimit` on every issuance, where that in-lock re-read only selects the `deleteFutureByOwner` argument and never becomes the new key's ownerVer (SEC-APIKEY-010) and issuance never takes the user write lock while holding the key owner lock; a re-entrant acquisition of the R37 per-user write lock that the same thread already holds (for example by the `SINGLE_INSTANCE` default `bumpTokenVersion`, `setEnabled` or `changePassword` of REL-IAM-001 called inside the key lock for that `ver++`; REL-IAM-001 makes it one re-entrant lock shared by the defaults and auth's writes, REL-IAM-001 AC-7) is not a reversed lock order; a failed deletion before `ver++` shall log ERROR (once per minute) and still perform `ver++`, a failed deletion before issuance shall fail the issuance with 503, and under `MULTI_INSTANCE` this deletion shall not run.

#### Acceptance Criteria

- [ ] AC-1: `SINGLE_INSTANCE`, store ver rolled back 6→5, key K with ownerVer 6 never used, owner calls logout `?all=true`: `deleteFutureByOwner(u, 5)` deletes K inside the owner lock before ver becomes 6, the `ver++` write happens while the same thread still holds that owner lock, and K then gets 401 `invalid_api_key`.
- [ ] AC-2: `SINGLE_INSTANCE`, owner with 19 live keys and 1 key whose ownerVer exceeds the store ver issues a PAT: the violating key is deleted before counting and the response is 201.
- [ ] AC-3: `SINGLE_INSTANCE`, `deleteFutureByOwner` throws during a disable: the user is disabled, `ver++` happens, an ERROR log is written and a pending marker is left.
- [ ] AC-4: `SINGLE_INSTANCE`, `deleteFutureByOwner` throws during PAT issuance: 503 `SERVICE_UNAVAILABLE` and no key is inserted.
- [ ] AC-5: `MULTI_INSTANCE` on the MULTI_INSTANCE key fixture of OPS-APIKEY-001 AC-7 (so the app starts and the HTTP paths run): PAT issuance, admin issuance, app issue (`auth.apiKeys().issue`, IR-APIKEY-002) and the auth-triggered `ver++` paths (logout `?all=true`, self-service password change, A4 password reset, disable, recovery) never call `deleteFutureByOwner`.
- [ ] AC-6: Lock order (00 R47 (2), 27th edition: the key owner lock is taken only inside the R37 user write lock, and issuance never takes the user write lock inside the key lock): under `SINGLE_INSTANCE`, on logout `?all=true`, self-service password change, A4 password reset, A4 disable and recovery the key owner lock of user u is acquired only while the R37 per-user write lock of u is held by the same thread, and PAT, admin and app issuance never take the R37 user write lock while holding the key owner lock; a lock-order test instrumenting both locks over these five `ver++` paths and the three issuance paths (PAT, admin endpoint and `auth.apiKeys().issue`) records no key-lock-then-user-lock acquisition. A re-entrant re-acquisition of the R37 user write lock that the same thread already holds (for example by the `SINGLE_INSTANCE` default `bumpTokenVersion`, `setEnabled` or `changePassword` of REL-IAM-001 called inside the key lock; one shared re-entrant lock per REL-IAM-001 AC-7) is not a key-lock-then-user-lock acquisition; only an acquisition of a user write lock the thread did not already hold is recorded.
- [ ] AC-7: `SINGLE_INSTANCE`, store ver rolled back 6→5, key K with ownerVer 6 never used, the built-in admin sends A4 `{password}` for owner u: `deleteFutureByOwner(u, 5)` deletes K inside u's key owner lock before `changePassword` raises ver to 6, the `changePassword` write happens while the same thread holds that owner lock, and K then gets 401 `invalid_api_key`.


### SEC-IAM-006 — Recovery path user-state outcome
#### Requirement

When a recovery request {token, username, password} is accepted by the SEC-AUTH-003 endpoint, auth shall create the named user with role admin if absent, or else enable it, set the new password and grant admin with exactly one ver++, shall accept a target whose stored hash is null, and shall emit only on* user events with origin RECOVERY carrying the changed subset; the endpoint rules (token, locality, service-account refusal, setup naming, audit event, WARN per start, closure after one success) are owned by SEC-AUTH-003. This admin-granting rule does not apply to the setup that a missing null-password admin .user opens, even with recovery on: that setup fixes the username to the .user name and grants the .user roles (SEC-AUTH-003 AC-4, AC-5; 00 R31 (6), R41 11판/12판).

#### Acceptance Criteria

- [ ] AC-1: Recovery with username "ops" when no user ops exists creates ops with role admin and the given password; onUserCreated fires with origin RECOVERY and no before event fires.
- [ ] AC-2: Recovery with username "alice" (disabled, roles {editor}, hash null, ver 7) results in enabled true, roles containing admin, a hash verifying the new password and ver 8; onUserUpdated fires once with the changed subset of ENABLED, PASSWORD, ROLES and origin RECOVERY, and onPasswordChanged fires.


### REL-APIKEY-004 — In-memory pending marker after a failed future-key deletion
#### Requirement

Under `SINGLE_INSTANCE`, when `deleteFutureByOwner` fails before an auth-triggered `ver++`, the auth module shall keep an in-memory pending marker {owner id, oldVer} that rejects keys of that owner with ownerVer > oldVer (HTTP before step 7 and WebSocket rechecks), forces issuance and later `ver++` to retry `deleteFutureByOwner(owner, oldVer)` first, is retried by the 10-minute R42 cleanup scheduler, is re-read under the per-owner lock by every asynchronous retry, and is cleared on a successful retry or on user deletion.

#### Acceptance Criteria

- [ ] AC-1: Store ver rolled back 6→5, key K ownerVer 6, `?all=true` with a failing `deleteFutureByOwner(u, 5)`: ver becomes 6, marker (u, 5) remains, and a request with K gets 401 `invalid_api_key` and schedules one re-delete.
- [ ] AC-2: With marker (u, 5) present, a PAT issuance first runs `deleteFutureByOwner(u, 5)` under the owner lock; on success the marker is cleared and the new key (ownerVer 6) is inserted afterwards and is not deleted; on failure the issuance gets 503 and the marker stays.
- [ ] AC-3: With marker (u, 5) present, a later `ver++` whose retry deletion fails keeps the marker with oldVer 5 unchanged.
- [ ] AC-4: Scheduler race: the cleanup scheduler reads u from the marker list, an issuance then clears the marker and inserts key N (ownerVer 6), then the scheduler takes u's lock: it re-reads the marker, finds none, does nothing, and N remains.
- [ ] AC-5: With the deletion executor held (an injected executor that runs no queued task until released), 100 requests with violating keys of an owner with a marker produce one pending re-delete; after release, a request after that job started schedules another.
- [ ] AC-6: A WebSocket opened with a key whose ownerVer > oldVer of a present marker is closed at the next recheck; deleting the owner via A5 deletes all keys and then clears the marker under the owner lock.
- [ ] AC-7: The marker is not persisted: a new `Auth` instance over the same stores starts with no marker (documented residual risk).
- [ ] AC-8: Successful retries (00 R47 (2) 28–29판, §6.4 row 'R47 × 보류 표시'): `SINGLE_INSTANCE`, store ver rolled back 6→5, key K ownerVer 6, `?all=true` whose `deleteFutureByOwner(u, 5)` fails once (ver becomes 6, marker (u, 5)), after which the store recovers. (a) After one run of the R42 10-minute cleanup scheduler (fixed `TimeSource`, the FR-TOKEN-002 AC-5 scheduler), `deleteFutureByOwner(u, 5)` has been called inside u's owner lock, K is gone (`findByPrefix(K's prefix)` returns null) and the marker is cleared. (b) Separately, with the deletion executor of AC-5 released, the re-delete scheduled by a request with K (AC-1) calls `deleteFutureByOwner(u, 5)` inside u's owner lock, K is gone and the marker is cleared. (c) Separately, a later `POST /auth/logout?all=true` for u first runs `deleteFutureByOwner(u, 5)` inside the owner lock, which succeeds, and then raises ver to 7; K is gone, the marker is cleared, and a key issued afterwards with ownerVer 7 authenticates.
- [ ] AC-9: Request-path race (00 R47 (2) 29판 in-lock re-check; §6.4 row 'R47 × 보류 표시와 스케줄러 경쟁'): with marker (u, 5) and the deletion executor of AC-5 held, a request with K queues one re-delete; a PAT issuance for u then clears the marker by a successful `deleteFutureByOwner(u, 5)` and inserts key N (ownerVer 6); after the executor is released the queued re-delete takes u's owner lock, re-reads the marker, finds none and calls no `deleteFutureByOwner`, and N still authenticates.


### FR-APIKEY-008 — User deletion clears the owner's pending key marker and stops the deleted owner's keys
#### Requirement

Within the user delete order owned by FR-IAM-008 (memberships, then `deleteAllByOwner`, then the user row, 503 and row kept on failure), auth shall clear the owner's pending marker under the per-owner lock after the keys are deleted and before the user row is deleted, and the deleted owner's keys shall no longer authenticate.

#### Acceptance Criteria

- [ ] AC-1: `SINGLE_INSTANCE` with marker (u, 5): deleting u removes all keys, then clears the marker, then deletes the user row.
- [ ] AC-2: After deleting a user who owns 3 keys via A5, each of the 3 keys gets 401 `invalid_api_key`.


### SEC-APIKEY-004 — API key verification order on protected paths
#### Requirement

On a protected path (credential endpoints included) the auth module shall decide a key request in exactly this order: transport check (R33), multiple credentials (400), then 1 scheme, 2 key-only IP limit, 3 parse, 4 `findByPrefix` with a dummy hash when absent and a byte-wise prefix re-compare, 5 constant-time SHA-256 compare, 6 expiry, (under `SINGLE_INSTANCE`, the REL-APIKEY-004 pending-marker check), 7 owner read, 8 owner `enabled` and ownerVer, 9 off-request stale-key deletion scheduling, 10 endpoint restriction (403 `api_key_not_allowed`), 11 scope guard (403 `insufficient_scope`); failures in steps 3–8 and the pending-marker check shall all be 401 `invalid_api_key`, store errors in steps 4, 7 and 8 shall be 503 `SERVICE_UNAVAILABLE`, and key verification shall take no hash slot (R38) and no 1.25T failure floor.

#### Acceptance Criteria

- [ ] AC-1: An unknown prefix, a known prefix with a wrong secret and an expired key each get the same 401 with body `code` `invalid_api_key`.
- [ ] AC-2: A wrong key on `POST /auth/refresh` gets 401 `invalid_api_key`; a valid key on the same request gets 403 `api_key_not_allowed` (endpoint restriction runs after key authentication).
- [ ] AC-3: A stored key with prefix `aBcd1234` and a request prefix `AbCD1234`, with a store that returns the `aBcd1234` row for the lookup, gets 401 `invalid_api_key` (byte-wise prefix re-compare).
- [ ] AC-4: The owner is not read from the store or principal cache when the secret does not match (a store spy records no owner read for a wrong-secret request).
- [ ] AC-5: A store exception thrown by `findByPrefix` on a protected path gets 503 with body `code` `SERVICE_UNAVAILABLE`, not 401; the same exception on a public path lets the request proceed anonymously, and the error log is limited to once per minute process-wide.
- [ ] AC-6: A store exception while re-reading the owner in step 8 (ownerVer above the cached ver) on a protected path gets 503 `SERVICE_UNAVAILABLE`; the same exception on a public path lets the request proceed anonymously, and the error log is limited to once per minute process-wide.
- [ ] AC-7: In PROD a plaintext non-local key request to a protected path gets 403 `insecure_transport` before the `Authorization` header is read (no key lookup).
- [ ] AC-8: With every hash slot taken, a valid key request on a protected path authenticates without waiting for a slot, and a wrong-key 401 is not delayed to the 1.25T login floor (a fixed `TimeSource` or timer spy records no floor sleep or delayed response).
- [ ] AC-9: An unknown prefix runs exactly one constant-time SHA-256 comparison against a dummy hash before its 401, the same count as a known prefix with a wrong secret (a spying hash/compare hook records one comparison in each case and none is skipped for the absent prefix) (00 R47 (2): an absent prefix is also compared against a dummy hash).
- [ ] AC-10: Step 10 before step 11 (00 R47 (2) 26th edition: when conditions overlap, the earlier step answers): with `.apiKeys(store)` and `.allowAdminScopesOnApiKeys` off, on a local connection, a valid key K whose scopes are `posts:read` (its owner holds `*` through the built-in `admin` role) on `POST /auth/admin/apikeys` with a well-formed body gets 403 `api_key_not_allowed`, not 403 `insufficient_scope`; no WWW-Authenticate header (IR-FLOW-001 AC-6), the admin-issue handler does not run (no read of the body's `ownerUserId`, no password hash, no insert).


### FR-APIKEY-002 — API key expiry judged without leeway on the evaluating node's clock, and scheduled deletion of expired keys
#### Requirement

The auth module shall treat a key as expired when `TimeSource.nowMillis()` of the evaluating node is greater than or equal to its `expiresAt` (epoch milliseconds), with no leeway, shall treat `expiresAt` null as never expiring, and, when `.apiKeys(store)` is set, the R42 10-minute daemon cleanup scheduler (FR-TOKEN-002, stopped in onStop) shall call `ApiKeyStore.deleteExpired(TimeSource.nowMillis())` on every run.

#### Acceptance Criteria

- [ ] AC-1: With a fixed `TimeSource` where `nowMillis()` equals the key's `expiresAt`, a key request gets 401 `invalid_api_key`.
- [ ] AC-2: With `nowMillis()` equal to `expiresAt - 1`, the same key authenticates.
- [ ] AC-3: A key issued through `auth.apiKeys().issue(owner, name, null, scopes)` (expiresAt null) still authenticates with `nowMillis()` set to `Long.MAX_VALUE - 1`.
- [ ] AC-4: `ApiKeyStore.deleteExpired(nowMillis)` removes keys whose non-null `expiresAt` is less than or equal to `nowMillis` and keeps keys with null `expiresAt`.
- [ ] AC-5: Scheduled cleanup (00 R47 (6) "dead keys": expired keys are deleted by the R42 10-minute cleanup scheduler; §6.3 ApiKeyStore table, `deleteExpired` row, used by the R42 cleanup scheduler at a 10-minute interval): with `.apiKeys(store)` set, a fixed `TimeSource` past key K's `expiresAt` and a non-expiring key N, after one run of the same 10-minute daemon cleanup scheduler that calls `RefreshTokenStore.purgeExpired` (FR-TOKEN-002 AC-5), `deleteExpired` has been called once with that `nowMillis`, `findByPrefix(K's prefix)` returns null and N remains; without `.apiKeys(store)` the scheduler makes no `ApiKeyStore` call; after onStop no further `deleteExpired` call happens. An exception from `deleteExpired` leaves request responses unchanged (IR-APIKEY-004 AC-6).


### FR-FLOW-001 — Fixed decision order for requests to auth endpoints
#### Requirement

Requests to auth endpoints shall be judged in the order transport check (R33), multiple credentials and the API-key procedure, body format (415), COOKIE-mode body refreshToken (400), CSRF and Origin (403, or delivery selection for login, register and setup in BOTH mode), then the handler, and when several conditions apply the earliest step in this order shall decide the response; for a naru_rt cookie token that refresh or logout uses as its credential or identity (FR-FLOW-005 body-first rule, SEC-FLOW-010 table) the CSRF and Origin step begins by verifying that token (an invalid one fails refresh with its 401 before any CSRF check, SEC-FLOW-008 AC-9, and gives logout no identity, SEC-FLOW-010 AC-9; 00 R29 (a)(b)).

#### Acceptance Criteria

- [ ] AC-1: PROD, plain-text non-local POST /auth/refresh with two Authorization headers and a text/plain body -> 403 insecure_transport.
- [ ] AC-2: Secure POST /auth/login with two Authorization headers and a text/plain body -> 400 invalid_request, not 415.
- [ ] AC-3: Secure COOKIE-mode POST /auth/refresh with an application/x-www-form-urlencoded body containing refreshToken -> 415 UNSUPPORTED_MEDIA_TYPE, not 400.
- [ ] AC-4: Secure COOKIE-mode POST /auth/refresh with a JSON body refreshToken, no X-CSRF-Token and a cross-origin Origin -> 400 BAD_REQUEST, not 403.
- [ ] AC-5: Secure BOTH-mode POST /auth/login with an Origin outside trustedOrigins and valid credentials -> 200 with body delivery and no Set-Cookie; no 403 is produced.
- [ ] AC-6: Secure COOKIE-mode (or BOTH-mode) with API keys enabled (.apiKeys(store)), POST /auth/refresh with a valid API-key Authorization header and a naru_rt cookie -> the key procedure decides the response before the refresh cookie is read (naru_rt is neither read nor rotated); the 403 api_key_not_allowed outcome is owned by SEC-FLOW-003 AC-5. Without .apiKeys(store) the key-scheme header is no credential (IR-TOKEN-001 AC-5) and the request is decided by naru_rt (FR-FLOW-005 statement). (CSRF-before-rotation on cookie refresh is owned by SEC-FLOW-008 AC-2.)


### FR-FLOW-005 — POST /auth/refresh token rotation endpoint
#### Requirement

POST /auth/refresh shall accept the refresh token from the JSON body in HEADER and BOTH modes or from the naru_rt cookie in COOKIE and BOTH modes (body first), rotate it within the same family, and return the new pair through the delivery rule; with API keys enabled (.apiKeys(store)), a request carrying an API-key scheme Authorization header shall be decided by the key procedure before the refresh cookie is read (a valid key -> 403 api_key_not_allowed, owned by SEC-FLOW-003 AC-5; a wrong key -> 401 invalid_api_key, owned by SEC-APIKEY-004 AC-2), and naru_rt shall be neither read nor rotated for it; without .apiKeys(store) such a header is no credential (IR-TOKEN-001 AC-5) and the request is decided by its refresh token alone.

#### Acceptance Criteria

- [ ] AC-1: HEADER, valid body refreshToken -> 200 with new accessToken and refreshToken in the body; the new access token's fam equals the family of the presented refresh token.
- [ ] AC-2: With the reuse grace window set to 0 (SEC-TOKEN-006 AC-4; the default is open, 00 §10 Q2): presenting a refresh token that was already rotated -> 401, and the newest refresh token of that family then also fails with 401 (family revoked by reuse detection).
- [ ] AC-3: COOKIE or BOTH with API keys enabled (.apiKeys(store)), a valid API key in Authorization on POST /auth/refresh with a valid naru_rt cookie issues no refresh token and does not read naru_rt; the 403 api_key_not_allowed outcome is owned by SEC-FLOW-003 AC-5. COOKIE or BOTH with API keys enabled, a wrong API key on the same request with a valid naru_rt cookie also issues no refresh token and neither reads nor rotates naru_rt; its 401 invalid_api_key outcome is owned by SEC-APIKEY-004 AC-2. Without .apiKeys(store) the API-key scheme header is no credential (IR-TOKEN-001 AC-5), so these requests are decided by the naru_rt cookie as if the header were absent.
- [ ] AC-4: Any mode (HEADER, COOKIE or BOTH), no body token and no naru_rt cookie -> 401 with code invalid_refresh_token [INFERRED:med:advisory]
- [ ] AC-5: After POST /auth/logout revoked family F, a refresh with a token of F -> 401.
- [ ] AC-6: BOTH, a body token of family F and a naru_rt of a different family G (the same user, issued by a second login) both present, with no X-CSRF-Token and no Origin header -> the body token is used: a valid body token -> 200 with body delivery and no Set-Cookie, and naru_rt is neither verified nor rotated (family G still refreshes afterwards with G's own CSRF token and a same-origin Origin); an invalid body token -> 401 without trying naru_rt (00 R29 (a), (e) ①; SEC-FLOW-008 statement).
- [ ] AC-7: HEADER, POST /auth/refresh with only a valid naru_rt cookie -> the same 401 as a refresh with no refresh token (AC-4); the cookie is not read and its family is not rotated (00 R29 (g)).


### IR-APIKEY-005 — Configured key scheme name in WWW-Authenticate challenges
#### Requirement

When keys are on, auth shall build its 401 challenges as IR-FLOW-001 specifies and its error codes as IR-AUTH-003 specifies, and shall use the scheme set by `.apiKeyScheme(String)` (default `ApiKey`) as the key challenge name in every 401 `WWW-Authenticate` header, including responses with no credential and with a wrong key.

#### Acceptance Criteria

- [ ] AC-1: With `.apiKeyScheme("ak")`: a protected path with no credential gets 401 with `WWW-Authenticate: Bearer, ak`, and a wrong key presented as `Authorization: ak nk_...` gets 401 body `code` `invalid_api_key` with the same `WWW-Authenticate: Bearer, ak` (no attribute on either challenge).


### REL-APIKEY-001 — Key rows are never cached so delete and expiry apply on the next request
#### Requirement

The auth module shall read the key row from the store by prefix on every key request (no key-row cache), so that `DELETE` (own or admin), `revoke` and expiry stop a key on every node from the next request, while owner `ver++`, disabling and demotion, which pass through the principal cache, take effect immediately on the acting node and within 5 seconds on other nodes.

#### Acceptance Criteria

- [ ] AC-1: Node A and node B share one store; after `DELETE /auth/me/apikeys/:id` on node A, the next request with that key to node B gets 401 `invalid_api_key`.
- [ ] AC-2: After `auth.apiKeys().revoke(keyId)` returns true, the next request with that key gets 401 `invalid_api_key` with no wait.
- [ ] AC-3: Two consecutive requests with the same valid key each call `ApiKeyStore.findByPrefix` once (a counting store records 2 calls).
- [ ] AC-4: After the owner is disabled on node A, a key request to node A gets 401 `invalid_api_key` immediately, and a key request to node B gets 401 `invalid_api_key` no later than 5 seconds after the disable (principal cache TTL).


### SEC-APIKEY-005 — Key-only per-IP failure counter and 429 too_many_attempts
#### Requirement

The auth module shall count every key 401 `invalid_api_key` of SEC-APIKEY-004 (steps 3–8 and the pending-marker check: malformed, unknown prefix, wrong secret, expired, stale, disabled owner, invariant violation, pending-marker rejection) on protected and public paths alike in a key-only per-IP counter (default 100 failures per 15 minutes, IP resolved as in R34 (5) with IPv6 /64), where a failing key on a public path leaves the request anonymous and is still counted; it shall never reset the counter on success, and shall answer a key request from an IP over the limit with 429 `too_many_attempts` and `Retry-After` before parsing or lookup on protected paths and as anonymous without lookup on public paths, independent of login throttling.

#### Acceptance Criteria

- [ ] AC-1: 101 wrong-key requests from one IP within 15 minutes to a protected path: requests 1–100 get 401 `invalid_api_key`, request 101 gets 429 `too_many_attempts` with a `Retry-After` header and no `findByPrefix` call.
- [ ] AC-2: 200 requests from one IP with an expired key whose secret matches: requests 1–100 get 401 `invalid_api_key`, from request 101 on 429 `too_many_attempts` without lookup.
- [ ] AC-3: From an IP over the key limit, `Authorization: ApiKey abc` on a protected path gets 429 `too_many_attempts` (limit check precedes parsing).
- [ ] AC-4: 50 wrong-key requests then a valid key from the same IP: the valid key authenticates and the counter is not reset (the next 50 wrong keys reach the limit).
- [ ] AC-5: 20 login failures from one IP that make the login IP key return 429 do not block key authentication from that IP; 100 key failures do not consume the login IP limit.
- [ ] AC-6: An IP over the key limit sending a key to a public path gets an anonymous response with no 429 and no lookup.
- [ ] AC-7: A `LoginThrottle` exception during a key request gets 503 JSON on a protected path; on a public path the request is anonymous without key lookup and the error log is limited to once per minute process-wide.
- [ ] AC-8: Login throttling and hard lockout of a service account name do not stop key authentication for that account.
- [ ] AC-9: An expired key retried after `deleteExpired` removed it gets 401 `invalid_api_key` (unknown prefix) and is counted in the key-only IP counter exactly as before the cleanup.
- [ ] AC-10: On the public path `GET /health`, `Authorization: ApiKey <wrong key>` gets the anonymous 200 and increments the key-only IP counter; after 100 such requests from one IP, request 101 to a protected path gets 429 `too_many_attempts` without `findByPrefix`.
- [ ] AC-11: docs/api states that the key-only IP limit also blocks valid keys sent from the same IP (NAT or proxy) as a residual risk (docs test).


### SEC-APIKEY-007 — API key principal and effective scopes
#### Requirement

A request authenticated by an API key shall authenticate as the key's owner user, with effective scopes equal to the key scopes intersected with the owner's current scopes under the R31 "both cover" predicate, `auth.user(req)` returning the owner, `AuthPrincipal.getApiKeyId()` returning the key id (null for JWT logins), and the key id recorded in audit events.

#### Acceptance Criteria

- [ ] AC-1: Key scopes `posts:*`, owner demoted to `posts:read`: a route guarded by `posts:read` passes; a route guarded by `posts:write` gets 403 `insufficient_scope`.
- [ ] AC-2: `auth.user(req)` on a key-authenticated request returns the owner `AuthUser`; `auth.principal(req).getApiKeyId()` equals the key id.
- [ ] AC-3: For a JWT-authenticated request `getApiKeyId()` returns null.
- [ ] AC-4: An audit event produced by a key-authenticated admin action carries the key id.
- [ ] AC-5: 403 responses to key-authenticated requests (`insufficient_scope`, `api_key_not_allowed`) carry no `WWW-Authenticate` header.
- [ ] AC-6: After the owner is demoted on node A, the key's effective scopes shrink on node A immediately and on another node within 5 seconds.


### SEC-APIKEY-009 — auth-* admin scopes on keys and the key wildcard scope rule
#### Requirement

The auth module shall reject `auth-*` scopes in key issuance unless `.allowAdminScopesOnApiKeys(true)` is set (400 `BAD_REQUEST` on PAT and admin issue, `IllegalArgumentException` in the app API), shall ignore stored `auth-*` key scopes in authorization while the opt-in is off, shall never let a key scope `*` cover an `auth-*` resource whatever the opt-in, and shall let no key scope cover a required scope `*`.

#### Acceptance Criteria

- [ ] AC-1: Opt-in off: PAT `POST /auth/me/apikeys` and `POST /auth/admin/apikeys` with scope `auth-users:write` get 400 with body `code` `BAD_REQUEST`; `auth.apiKeys().issue(owner, "n", null, "auth-users:write")` throws `IllegalArgumentException`.
- [ ] AC-2: Opt-in off or on: a key with scope `*` calling `GET /auth/admin/users` gets 403 `insufficient_scope`.
- [ ] AC-3: A `*` key issued without the opt-in, app restarted with `.allowAdminScopesOnApiKeys(true)`, the same key on `GET /auth/admin/users` gets 403 `insufficient_scope`.
- [ ] AC-4: A key issued with `auth-users:write` under the opt-in, app restarted without it: the key gets 403 `insufficient_scope` on admin endpoints, its other scopes keep working, and the key row is not deleted.
- [ ] AC-5: Opt-in on, key holding named `auth-*` scopes: granting the built-in `admin` role (`*`), disabling a `*` holder, or adding a user to a group that grants `*` each gets 403 `insufficient_scope`.
- [ ] AC-6: An admin holding only `auth-apikeys:write` issuing a key with scope `*` gets 403 `insufficient_scope` (new key scopes must be covered by the actor's scopes).
- [ ] AC-7: Opt-in on, a key with scopes `*` and `auth-users:write` whose owner holds `*`: granting user X a role whose scopes include `auth-apikeys:read` gets 403 `insufficient_scope` and X's roles are unchanged, because in the R31 (1) grant check the key's `*` does not cover `auth-apikeys:read` (00 R47 (5)).


### OPS-APIKEY-001 — Doctor checks for API keys
#### Requirement

When keys are on, the doctor shall FAIL under `MULTI_INSTANCE` if `ApiKeyStore.supportsAtomicLimitInsert()` is false, shall WARN at every start while `.allowAdminScopesOnApiKeys(true)` is set, and, while the opt-in is off, shall WARN with the count when `countKeysWithAdminScopes()` is greater than 0, or, when it returns -1, shall instead WARN at request time with the key id whenever an `auth-*` key scope is ignored, limited to once per minute process-wide; the WARN for a throttle whose `usesLimitArguments()` is false is owned by IR-FLOW-003.

#### Acceptance Criteria

- [ ] AC-1: The MULTI_INSTANCE key fixture of AC-7 with only the ApiKeyStore changed to a custom store whose `supportsAtomicLimitInsert()` is false (the UserStore still returns true from `supportsAtomicFieldWrites()`): `build()` succeeds, then doctor reports a FAIL naming `supportsAtomicLimitInsert` and no FAIL naming `supportsAtomicFieldWrites`, and the app does not start.
- [ ] AC-2: `.allowAdminScopesOnApiKeys(true)`: doctor WARN at each start containing the persistence-of-effects text (proposed: `API keys may hold auth-* admin scopes; role grants and reactivations made by a key persist after the key is deleted. Review audit events by apiKeyId.`).
- [ ] AC-3: Opt-in off, store with three `*` keys and one `auth-users:read` key: doctor WARN reports count 1.
- [ ] AC-4: Opt-in off, store returning -1: no start-time count WARN; two different keys whose `auth-*` scopes are ignored within one minute produce one WARN containing only the first key id.
- [ ] AC-5: `SINGLE_INSTANCE` with a store whose `supportsAtomicLimitInsert()` is false: no FAIL for that check.
- [ ] AC-6: The `.allowAdminScopesOnApiKeys` Javadoc states the same lasting-effects risk as the AC-2 WARN (role grants and reactivations made by a key persist after the key is deleted; review audit events by apiKeyId) (00 R47 (5)); a docs test reads the Javadoc of the builder method and asserts that sentence is present.
- [ ] AC-7: MULTI_INSTANCE key fixture (used by AC-1 here and by SEC-APIKEY-006 AC-4, SEC-APIKEY-011 AC-7, IR-APIKEY-003 AC-5 and REL-APIKEY-003 AC-5): `deployment(MULTI_INSTANCE)` and `.apiKeys(store)`, with every store that FR-AUTH-003 AC-3 requires shared (non-in-memory, non-file test fakes or JDBC stores on SQLite for users, refresh tokens, throttle, device trust and API keys, and for groups when configured), where the UserStore returns true from `supportsAtomicFieldWrites()` (a test fake declares it; JdbcUserStore does, REL-IAM-003 AC-5) and the ApiKeyStore returns true from `supportsAtomicLimitInsert()` (a test fake declares it; JdbcApiKeyStore does, REL-APIKEY-005 AC-7). With this fixture `build()` succeeds, doctor reports no FAIL naming `supportsAtomicFieldWrites` (REL-IAM-003 AC-1) or `supportsAtomicLimitInsert` (AC-1), and the app starts.


### SEC-APIKEY-006 — Stale-key detection and ownerVer invariant handling
#### Requirement

The auth module shall compare a key's ownerVer with the owner's cached `ver`: equal authenticates from the cache, smaller is a stale key (401 `invalid_api_key` without a store re-read), and greater triggers one owner re-read from the store and a cache refresh, after which equal authenticates, smaller is stale, and still greater is an invariant violation (401 `invalid_api_key`, ERROR log limited to once per minute process-wide, counted in the key IP counter); under `SINGLE_INSTANCE` an invariant-violating key shall be deleted off the request path with `deleteById`, and under `MULTI_INSTANCE` it shall never be deleted.

#### Acceptance Criteria

- [ ] AC-1: Cached ver 6, request key ownerVer 5: 401 `invalid_api_key`, no owner re-read, and `deleteStaleByOwner(u, 6)` is scheduled off the request path.
- [ ] AC-2: Node A raises ver 5→6 and issues key K6 (ownerVer 6); node B with cached ver 5 receives K6: B re-reads the owner (ver 6), authenticates, refreshes its cache, and does not delete K6.
- [ ] AC-3: `SINGLE_INSTANCE`, a file store whose ver went back from 6 to 5, request key ownerVer 6: 401 `invalid_api_key`, counted in the key IP counter, one ERROR log within a minute for repeated requests, and `deleteById(keyId)` runs off the request path.
- [ ] AC-4: `MULTI_INSTANCE` on the MULTI_INSTANCE key fixture of OPS-APIKEY-001 AC-7 (the UserStore is a test fake that returns true from `supportsAtomicFieldWrites()`, so the app starts), node B's `UserStore` reads a replica still at ver 5 while the key has ownerVer 6: 401 `invalid_api_key` and an ERROR log, no delete call; after the replica returns ver 6 the same key authenticates.
- [ ] AC-5: A disabled owner's key with a matching secret gets 401 `invalid_api_key`.
- [ ] AC-6: Until deleted, an invariant-violating key is listed with `status` `stale`.


### IR-APIKEY-003 — Key status in listings and its JSON form
#### Requirement

Every key listing (`GET /auth/me/apikeys`, `GET /auth/admin/apikeys`, `AuthApiKeys.list`) shall give each key an `ApiKeyStatus` (`ACTIVE`, `EXPIRED`, `STALE`) computed from the owner `ver` read from the store (not the principal cache), with invariant-violating keys reported as `STALE`, and auth endpoints shall write the status as the lower-case strings `active`, `expired`, `stale` themselves rather than through the app codec.

#### Acceptance Criteria

- [ ] AC-1: Node B cache ver 5, store ver 6, owner with keys of ownerVer 6 and 5: `GET /auth/me/apikeys` on node B shows the ownerVer 6 key as `active` and the ownerVer 5 key as `stale`.
- [ ] AC-2: A key whose ownerVer exceeds the store ver is shown as `"status":"stale"`.
- [ ] AC-3: The auth list endpoint JSON contains `"status":"active"` (lower-case) even when the app's JSON codec would serialise the enum as `ACTIVE`.
- [ ] AC-4: An app route that passes an `ApiKey` to `res.json(...)` with the default Gson codec emits the enum per the codec (`ACTIVE`).
- [ ] AC-5: `MULTI_INSTANCE` on the MULTI_INSTANCE key fixture of OPS-APIKEY-001 AC-7 (the UserStore is a test fake that returns true from `supportsAtomicFieldWrites()`, so the app starts), owner with 19 live keys and one key whose ownerVer exceeds the replica-read ver 5: PAT issuance gets 409 `api_key_limit`, and that key is listed as `stale`; a stale key with ownerVer below ver is not counted.
- [ ] AC-6: `ApiKeyStatus` Javadoc states that values may be added in later versions, and the Kotlin `when` over it in the Kotlin test has an `else` branch; the `ApiKeyStatus` Javadoc and the reference doc in docs/api also state that under `MULTI_INSTANCE` a `stale` key whose ownerVer exceeds the read ver still counts toward `maxKeysPerUser` (00 R47 (6), edition 29); a docs test asserts both sentences.


### SEC-FLOW-003 — Credential endpoint classes: anonymous-entry and subject-required
#### Requirement

The auth module shall classify POST /auth/login, /auth/refresh, /auth/logout, /auth/register and /auth/setup as anonymous-entry credential endpoints that ignore invalid or expired JWT and cookie access tokens and never attach a principal (logout excepted for identity), and POST /auth/password, POST /auth/me/apikeys and POST /auth/admin/apikeys as subject-required credential endpoints judged exactly like protected paths, independent of any .permit configuration. The /auth prefix here is the default mount path; the account endpoints are classified under the configured mount path (.mountPath(String), FR-AUTH-005), and the admin endpoint is classified where auth.adminRoutes() is mounted (whether that follows a configured mount path is open).

#### Acceptance Criteria

- [ ] AC-1: BOTH, POST /auth/refresh with an expired Authorization: Bearer token and a valid naru_rt cookie (matching X-CSRF-Token, same-origin Origin) -> 200; the expired JWT is read and ignored and no 401 invalid_token is sent. COOKIE, the same request with an expired naru_at cookie instead of the Bearer token -> 200.
- [ ] AC-2: POST /auth/login with an expired Bearer token and valid credentials -> 200 regular login; no 401 invalid_token.
- [ ] AC-3: POST /auth/login, /auth/register, /auth/setup and /auth/refresh carrying a valid access token -> the handler sees no principal (auth.userOrNull(req) returns null).
- [ ] AC-4: POST /auth/password with an expired Bearer token -> 401, code invalid_token, header WWW-Authenticate: Bearer error="invalid_token"; the handler does not run. With API keys enabled, POST /auth/me/apikeys with the same token and POST /auth/admin/apikeys with a bad-signature Bearer give the same 401 with ", ApiKey" appended to the challenge.
- [ ] AC-5: On an anonymous-entry endpoint, two Authorization headers -> 400 invalid_request, and with API keys enabled (.apiKeys(store)) a valid API-key Authorization header on POST /auth/refresh -> 403 api_key_not_allowed; multiple credentials (SEC-FLOW-004, except two or more naru_at cookies in COOKIE or BOTH mode, which count as no access cookie) and the key procedure are the only credential judgments on anonymous-entry endpoints that follow protected-path rules. With API keys enabled (.apiKeys(store)), an API-key header is still authenticated by the key procedure first, so a wrong key on POST /auth/refresh -> 401 invalid_api_key (owned by SEC-APIKEY-004 AC-2). Without .apiKeys(store) the key-scheme header is no credential (IR-TOKEN-001 AC-5) and the request is decided by its refresh token alone (FR-FLOW-005 statement; with no refresh token, FR-FLOW-005 AC-4).
- [ ] AC-6: With .permitPrefix("/auth") configured, POST /auth/password with an expired Bearer token still -> 401 invalid_token, and POST /auth/login with two Authorization headers still -> 400 invalid_request. The two key endpoints are judged the same way under a permit that covers them (00 R29 29th edition: credential endpoints are judged independently of .permit, and the three subject-required endpoints are judged exactly like protected paths and not passed to the handler). HEADER mode, .apiKeys(store), over a secure or local connection, Content-Type: application/json and the body {}: (a) with .permitPrefix("/auth"), POST /auth/me/apikeys with an expired Bearer token -> 401 code invalid_token with WWW-Authenticate: Bearer error="invalid_token", ApiKey, and with no credential (no Authorization header, no cookies) -> 401 code unauthenticated with WWW-Authenticate: Bearer, ApiKey; (b) with .permit(Method.POST, "/auth/admin/apikeys"), the same two requests to POST /auth/admin/apikeys get the same two responses; in every case of (a) and (b) the handler does not run and no key is issued.
- [ ] AC-7: With .mountPath("/api/auth") (FR-AUTH-005) the classes follow the configured mount path: (a) PROD with .permitPrefix("/api/auth"), a plain-text non-local POST /api/auth/login -> 403 insecure_transport and the login handler does not run (00 R33: credential endpoints are never public paths); (b) HEADER over a local connection, POST /api/auth/refresh with an expired Authorization: Bearer token and a valid JSON body refreshToken -> 200, with no 401 invalid_token; (c) HEADER over a local connection, POST /api/auth/password with an expired Bearer token -> 401 invalid_token with WWW-Authenticate: Bearer error="invalid_token", and the handler does not run.
- [ ] AC-8: Access cookie on a subject-required endpoint and a protected path (00 R29 29th edition: expired or invalid Bearer and cookie access tokens are 401 invalid_token there). COOKIE mode, API keys disabled, over a secure or local connection, no Authorization header: (a) POST /auth/password with a well-formed JSON body, a same-origin Origin, naru_csrf and X-CSRF-Token both built from the cookie token's sub and fam, and only an expired naru_at -> 401 code invalid_token with header WWW-Authenticate: Bearer error="invalid_token", and the handler does not run (changePassword is not called); (b) the same request with a naru_at whose signature is altered -> the same 401 invalid_token response and the handler does not run; (c) the expired naru_at of (a) on a protected GET /api/x -> 401 invalid_token with WWW-Authenticate: Bearer error="invalid_token" (IR-FLOW-001 statement), not 401 unauthenticated with a plain Bearer challenge, and the route does not run.
- [ ] AC-9: No credential on a subject-required endpoint (00 R29 29th edition, replacing the 28th edition's "a handler that needs a subject answers 401 itself": the three subject-required endpoints are judged exactly like protected paths and the request is not passed to the handler; §6.4 row "R47 × 자격 증명 없음 코드" for the challenge with keys on). HEADER mode, API keys disabled, over a secure or local connection, no Authorization header and no cookies: POST /auth/password with Content-Type: application/json and the body {} (no current-password field) -> 401 code unauthenticated with WWW-Authenticate: Bearer (no error attribute), not 400, and the handler does not run (changePassword is not called and no password hash runs); the same request with .permitPrefix("/auth") configured -> the same 401 unauthenticated. With .apiKeys(store), POST /auth/me/apikeys and POST /auth/admin/apikeys with Content-Type: application/json, the body {} and no credential -> 401 code unauthenticated with WWW-Authenticate: Bearer, ApiKey, and neither handler runs (no key is issued).


### SEC-FLOW-008 — CSRF for refresh by naru_rt and body refresh tokens per transport mode
#### Requirement

For POST /auth/refresh that uses the naru_rt cookie as its refresh token (no body refreshToken; when both arrive in BOTH mode the body token is used and naru_rt is neither verified nor CSRF-checked, FR-FLOW-005 AC-6, 00 R29 (e) ①) the auth module shall verify the refresh token first, then require that the X-CSRF-Token header equals, in constant time, one of the naru_csrf cookie values whose HMAC matches that refresh token's sub and fam (two or more naru_csrf cookies pass under the same rule, 00 R29 (d) 33rd edition) and pass the Origin check, all before rotation and regardless of the access cookie state; a body refreshToken shall be accepted only in HEADER and BOTH modes without CSRF checks and with body-only delivery, and shall be rejected in COOKIE mode with 400 BAD_REQUEST on refresh and logout.

#### Acceptance Criteria

- [ ] AC-1: COOKIE, expired naru_at, valid naru_rt, X-CSRF-Token and naru_csrf built from naru_rt's sub and fam, same-origin Origin, POST /auth/refresh -> 200.
- [ ] AC-2: COOKIE, the AC-1 request without X-CSRF-Token -> 403 csrf_failed; the refresh token is not marked used, so the same naru_rt with a correct header then refreshes successfully (200).
- [ ] AC-3: COOKIE, the outcome of the AC-1 and AC-2 requests is identical when naru_at is valid, expired or absent.
- [ ] AC-4: COOKIE, same-origin JSON body {"refreshToken":"..."} on POST /auth/refresh -> 400 BAD_REQUEST, the token is not used and no Set-Cookie gives a value to naru_at, naru_rt, naru_csrf or naru_device (clearing is not asserted, SEC-FLOW-007 AC-7); POST /auth/logout with a body refreshToken and a naru_rt -> 400 BAD_REQUEST.
- [ ] AC-5: HEADER or BOTH, JSON body refreshToken with no X-CSRF-Token and no Origin, POST /auth/refresh -> 200 with accessToken and refreshToken in the body and no Set-Cookie.
- [ ] AC-6: BOTH, no body refreshToken, valid naru_rt with X-CSRF-Token and naru_csrf both built from its sub and fam, but no Origin and no Referer -> 403 csrf_failed; the same request with Origin https://evil.example, not in trustedOrigins -> 403 csrf_failed; in both cases the refresh token is not marked used, no Set-Cookie gives a value to naru_at, naru_rt, naru_csrf or naru_device (clearing is not asserted) and no body tokens are sent (the cookie token requires the Origin check, 00 §6.4 row 'R29 × BOTH 모드 전달 방식').
- [ ] AC-7: COOKIE, the AC-1 request (valid naru_rt with matching X-CSRF-Token) but with Origin https://evil.example, not in trustedOrigins -> 403 csrf_failed; the refresh token is not marked used, so the same naru_rt with a same-origin Origin then refreshes successfully (200).
- [ ] AC-8: BOTH, no body refreshToken, valid naru_rt, same-origin Origin, no X-CSRF-Token, POST /auth/refresh -> 403 csrf_failed and the refresh token is not marked used; the same request with X-CSRF-Token and naru_csrf built from naru_rt's sub and fam -> 200.
- [ ] AC-9: COOKIE, same-origin Origin, no X-CSRF-Token, POST /auth/refresh with a naru_rt whose family was revoked by logout (FR-FLOW-005 AC-5) and, separately, with a naru_rt whose exp has passed -> 401, the same status and body code as the same request carrying X-CSRF-Token and naru_csrf built from that token's sub and fam, and not 403 csrf_failed, because the refresh token is verified before the CSRF check (00 R29 (a)); no Set-Cookie gives a value to naru_at, naru_rt, naru_csrf or naru_device (clearing is not asserted, SEC-FLOW-007 AC-7).
- [ ] AC-10: COOKIE, same-origin Origin, a valid naru_rt of family F and two naru_csrf cookies, the first built from an old family G and the second from F (00 R29 (d) 33rd edition): X-CSRF-Token equal to the F value, POST /auth/refresh -> 200; X-CSRF-Token equal only to the G value -> 403 csrf_failed and the refresh token is not marked used, so the same naru_rt with the F value then refreshes successfully (200).


### FR-FLOW-002 — Token delivery rule for the five token-issuing responses
#### Requirement

The responses of login, register, setup, refresh and password shall deliver tokens by one rule: HEADER mode always by body, COOKIE mode always by cookies, and BOTH mode by how the presented credential arrived for refresh and password and by Origin (or Referer when Origin is absent) for login, register and setup, where same-origin or trustedOrigins means cookies and anything else means body without Set-Cookie and without 403.

#### Acceptance Criteria

- [ ] AC-1: HEADER, successful POST /auth/login -> body contains accessToken and refreshToken, plus deviceToken when a device token is issued; the response has no Set-Cookie header.
- [ ] AC-2: COOKIE, successful POST /auth/login with a same-origin Origin -> Set-Cookie for naru_at, naru_rt and naru_csrf, plus naru_device when a device token is issued; the body contains csrfToken and none of accessToken, refreshToken and deviceToken (00 R29 (e): cookie delivery carries only csrfToken in the body).
- [ ] AC-3: BOTH refresh: a body refreshToken -> body delivery; only naru_rt (with matching X-CSRF-Token and a same-origin Origin) -> cookie delivery; which token is used when both are present is owned by FR-FLOW-005 AC-6, and that case answers with body delivery.
- [ ] AC-4: BOTH password: Authorization Bearer -> new accessToken, refreshToken and deviceToken in the body and no Set-Cookie; naru_at (with its family's X-CSRF-Token and a same-origin Origin) -> naru_at, naru_rt, naru_csrf and naru_device cookies with body csrfToken only. HEADER password: the same Bearer request -> new accessToken, refreshToken and deviceToken in the body and no Set-Cookie (00 §6.4 "R10 × Bearer 비밀번호 변경").
- [ ] AC-5: BOTH login: no Origin and no Referer -> 200 body delivery; same-origin Origin -> 200 cookie delivery with body csrfToken only; Origin capacitor://localhost not in trustedOrigins -> 200 body delivery without Set-Cookie and not 403. BOTH with .trustedOrigins("https://spa.example") and app.cors allowedOrigins holding https://spa.example and capacitor://localhost: POST /auth/login with Origin https://spa.example (trusted but not the request's own origin) -> 200 cookie delivery (Set-Cookie for naru_at, naru_rt and naru_csrf, body csrfToken only, no accessToken); the same login with Origin capacitor://localhost (in allowedOrigins, not in trustedOrigins) -> 200 body delivery with accessToken and no Set-Cookie (00 §6.4 row "R29 × BOTH 응답 모양", R29 (e) ②, (h)).
- [ ] AC-6: BOTH, Origin: null, POST /auth/login -> 200 body delivery without Set-Cookie; POST /auth/register with .register(true) from the same client -> 201 body delivery without Set-Cookie.
- [ ] AC-7: BOTH login without Origin but with a same-origin Referer -> cookie delivery.
- [ ] AC-8: Every token-issuing response carries Cache-Control: no-store [INFERRED:med:advisory]
- [ ] AC-9: Setup delivery and Origin check (setup open, a local request per SEC-AUTH-003 AC-1 with the logged token, username and password; success status owned by SEC-AUTH-003): COOKIE with a same-origin Origin -> Set-Cookie for naru_at, naru_rt and naru_csrf and body csrfToken only (no accessToken or refreshToken in the body); COOKIE with Origin https://evil.example, not in trustedOrigins -> 403 csrf_failed, no Set-Cookie and no user created; HEADER -> body accessToken and refreshToken and no Set-Cookie; BOTH with Origin: null -> body delivery without Set-Cookie and not 403 (00 R29 (c), (e)).


### REL-IAM-002 — changePassword atomic hash swap with ver++, null-hash equality and race retry rules
#### Requirement

UserStore.changePassword(id, expectedOldHash, expectedVer, newHash) shall replace the hash and increment ver in one write only when the stored ver equals expectedVer and the stored hash equals expectedOldHash (null equal to null), and return false otherwise (the SINGLE_INSTANCE default, when its re-read does not confirm the write, throws an AuthUnavailableException with code security_write_conflict, IR-AUTH-003 AC-6, which the auth router ErrorMiddleware answers as 503 security_write_conflict, FR-AUTH-006 AC-3; in admin PATCH A4 the A4 handler answers it itself with the applied list, FR-IAM-014 AC-9); for POST /auth/password auth shall pass the request token's ver as expectedVer and write only when the re-read user's ver equals it and the re-read user is enabled (401 otherwise), while admin reset and recovery pass the re-read ver with no enabled condition; auth shall retry a failed self-service change once only when the re-read hash still verifies the request's current password, answer 409 otherwise, never retry an admin reset, and issue the success response and new token pair only after the write really applied.

#### Acceptance Criteria

- [ ] AC-1: changePassword on a user with passwordHash null and expectedOldHash null succeeds, stores newHash and ver becomes old ver + 1 (SSO or legacy users can receive a password through admin reset or recovery).
- [ ] AC-2: changePassword with expectedVer different from the stored ver returns false and changes nothing; on POST /auth/password, where expectedVer is the request token's ver, a re-read ver different from it answers 401 and issues no tokens, and a re-read user with enabled false also answers 401 without calling changePassword.
- [ ] AC-3: Self-service change racing a rehash: the first changePassword returns false (expectation mismatch), auth re-reads, the new hash verifies the request's current password, auth retries once with the new hash as expectation and succeeds.
- [ ] AC-4: Self-service change racing an admin reset: the re-read hash does not verify the current password, so auth answers 409 without a second write; an admin reset whose changePassword returns false answers 409 with no retry.
- [ ] AC-5: After a successful changePassword, ver has increased by exactly 1 and the hash is the new one in the same re-read; no state exists where the hash changed but ver did not.
- [ ] AC-6: The response body containing a new token pair is produced only when changePassword returned true (a test store returning false yields no token pair in any response).
- [ ] AC-7: With a store implementing only the five required methods whose update() silently does not apply, the SINGLE_INSTANCE default changePassword calls update once (the 3-retry rule of REL-IAM-001 covers only setEnabled, bumpTokenVersion and setRoles), the re-read still shows the old hash and ver, and POST /auth/password answers 503 with body code security_write_conflict and no token pair; the default reports the unconfirmed write by throwing an AuthUnavailableException with code security_write_conflict (IR-AUTH-003 AC-6), which the auth router ErrorMiddleware renders with that code (FR-AUTH-006 AC-3), not by returning false, so the AC-3 retry does not run.
- [ ] AC-8: A4 {password, enabled:true} on a disabled user with a matching re-read ver stores the new hash, increments ver once and then enables the user (the A4 success status of IR-IAM-005 AC-5); the store-level changePassword has no enabled condition (FR-IAM-010 AC-2 SQL form).


### SEC-FLOW-013 — DeviceTrust signed device token with name fingerprint and per-token failure limit
#### Requirement

On successful password login DeviceTrust shall issue a signed device token holding the user id, the issuing ver, the fingerprint HMAC(fp-key, normalized login name), a random value and a 90-day expiry, signed and fingerprinted with the device-token and fingerprint keys whose per-kid derivation is owned by SEC-TOKEN-005 AC-5, and shall trust it only when signature, expiry, fingerprint, user id and ver all match, invalidating it after 5 failures within 15 minutes.

#### Acceptance Criteria

- [ ] AC-1: Decoding an issued device token shows the user id, ver, fingerprint, random value and expiry, and no raw login name.
- [ ] AC-2: A token issued at login as alice is trusted for the next login as alice; when the store also resolves alice@example.com to the same user, a login as alice@example.com with that token is not trusted (fingerprint differs).
- [ ] AC-3: After logout ?all=true (ver++) or a password change, the old device token is no longer trusted, and the password-change response carries a new device token.
- [ ] AC-4: After alice is renamed to alice2 the old token is not trusted for alice2; if another user later takes the name alice, the old token is not trusted for that user either (user id differs).
- [ ] AC-5: A token signed under kid k1 stays trusted after the signing key rotates to k2 while k1 remains in the verification set, and is not trusted once k1 is removed.
- [ ] AC-6: Per-device-token failure limit (00 R34 (2): 5 failures per 15 minutes, then the token is invalid; §6.4 row 'R34 × 기기 토큰 탈취'). Default limits (SEC-FLOW-011), no Lockout, all requests within one 15-minute window: first exhaust the IP key of IP A6 with 20 failed logins without a device token from A6 for five other names u1..u5, 4 each (SEC-FLOW-011 AC-3; no (user, IP) key reaches 5 and none of alice's keys is touched). Then send 5 failed logins as alice carrying alice's valid device token D from IPs A1..A5, one each (D is trusted, so the IP and user-only keys do not apply, SEC-FLOW-012 step ③, and each (alice, Ai) key holds one failure). Then a login as alice with D and the correct password from A6 -> 429 too_many_attempts from A6's IP key, because D was invalidated by its 5th failure and the request is handled as one without a device token (the same answer as that login sent with no device token). Control: the same sequence with only 4 failures carrying D -> the A6 login with D and the correct password gets 200, because D is still trusted and exempt from the IP key (SEC-FLOW-011 AC-3). In both runs (alice, A6) holds no failure and alice's user-only key holds at most 5 of its 10, so only A6's IP key can answer 429, and it applies only once D has lost trust.
- [ ] AC-7: Transport per mode: COOKIE uses the naru_device cookie, which is set only by a response that passed the Origin check (a login that passed it by its Origin, or by its Referer when Origin is absent, being the request's own origin or in trustedOrigins, SEC-FLOW-007 AC-4/AC-5, or a password change with cookie delivery, SEC-FLOW-014 AC-5); HEADER returns body deviceToken and reads only the Naru-Device request header, never the naru_device cookie (00 §5, 33판; R29 (g)); BOTH delivers the device token the way that response delivers its tokens and on read prefers the Naru-Device header, else the naru_device cookie (00 R34, 32판). HEADER, a login carrying only a valid naru_device cookie and no Naru-Device header -> handled as a request without a device token (no trust exemption from the IP and user-only keys).
- [ ] AC-8: A token whose 90-day expiry has passed (TimeSource advanced by 90 days plus one second) is not trusted.
- [ ] AC-9: NaruServe-api contains the public interface com.snoworca.naruserve.utils.auth.spi.DeviceTrust (method set not final; 00 §2 spi, §9 v1). A custom DeviceTrust test fake configured on the Auth builder (option name not final) is the one auth calls to issue the device token on a successful login, to judge trust in step ③ of SEC-FLOW-012 and to record the per-token failures; the built-in device trust is not used. With none configured, the default follows the user store (REL-FLOW-001 AC-5). A Kotlin source test implements DeviceTrust in Kotlin and passes it to the builder, and it compiles and runs (CLAUDE.md T11).
- [ ] AC-10: naru_device cookie attributes (open question Q118, decided 2026-11-02; 00 and 05 give none): COOKIE or BOTH (cookie delivery), a successful login with a same-origin Origin sets naru_device with HttpOnly, SameSite=Strict, Path equal to the mount path followed by /login (/auth/login by default, /api/auth/login with .mountPath("/api/auth")), Secure present exactly when req.isSecure() is true (the SEC-FLOW-009 AC-4 rule) and Max-Age equal to the 90-day device-token lifetime (7776000); a password change with cookie delivery (SEC-FLOW-014 AC-5) sets it with the same attributes.


### SEC-IAM-005 — Group delete and edge removal: indirect-member protection and the 1,000-user limit
#### Requirement

For group deletion and group edge removal auth shall require that the current effective scopes of every affected user, including indirect members through sub-groups, are covered by the actor's scopes A and that the actor is not among them, shall answer 409 when more than 1,000 users are affected, and shall skip enumeration and the limit when A covers every scope the group and its ancestors confer, where "covers" for a key actor follows the R31 (4) key-actor rule so that no key covers a conferred `*` and a key actor never skips enumeration for a group that confers `*`.

#### Acceptance Criteria

- [ ] AC-1: A delegated admin (auth-groups:write, posts:read) deleting group writers (confers posts:*, no ancestors) whose members include an admin (*): A does not cover posts:*, so auth enumerates the affected users, finds the admin uncovered and answers 403 insufficient_scope; the group still exists (00 §6.4 row 'R31 × 그룹 삭제 우회').
- [ ] AC-2: The same delegated admin (auth-groups:write, posts:read) deleting a writers group (confers posts:*) with 1,001 affected users (direct plus indirect) answers 409 and nothing is deleted (00 §6.4 row 'R31 × 대형 그룹': delegated admin gets 409); the limit is checked before the per-user cover check. With 1,000 affected users the limit does not refuse the request and the per-user cover check decides it (here 403 insufficient_scope, because each affected user holds posts:*, which posts:read does not cover).
- [ ] AC-3: Built-in admin (*) deleting a group with 5,000 members succeeds without enumerating members (no membersOf/childrenOf traversal calls are made).
- [ ] AC-4: The only admin deleting the admin group it belongs to (directly or through a sub-group): refused under the self rule.
- [ ] AC-5: Removing an edge child→parent applies the same checks to every user who loses scopes through that edge.
- [ ] AC-6: An empty group (no direct or nested members) that confers * may be deleted by an actor who cannot cover * because no affected user exists.
- [ ] AC-7: Opt-in on, a key holding auth-groups:* and * whose owner holds * through the built-in admin role and is not a member of the group: DELETE /auth/admin/groups/admins (confers *, whose single member is another * holder) answers 403 insufficient_scope without the enumeration skip and the group remains; the same key deleting an empty *-conferring group succeeds.
- [ ] AC-8: Enumeration skip for a delegated admin (00 R31 (2), 6판 P-6): a delegated admin (auth-groups:write, posts:*) deleting writers (confers posts:*, no ancestors) that has 1,001 members, none holding more than posts:*, succeeds; no membersOf/childrenOf traversal call is made and the 1,000-user limit is not applied.


### SEC-FLOW-010 — Logout identity decision table with per-source CSRF
#### Requirement

POST /auth/logout shall select its identity by the decision table ① body refreshToken (HEADER and BOTH), ② otherwise exactly one naru_rt with CSRF and Origin checks on its sub and fam, ③ otherwise a valid Bearer token without CSRF or a valid naru_at with CSRF and Origin on its sub and fam, ④ otherwise no identity; a later candidate shall be consulted only when the earlier one was not sent, a sent but invalid candidate shall yield no identity, and a candidate shall be valid only when it passes the same signature, expiry and ver checks as refresh or a protected path and, for a refresh token, has a RefreshTokenStore record that is neither used nor revoked (00 R45). The ② and ③ CSRF checks shall pass when the X-CSRF-Token header equals, in constant time, one of the naru_csrf cookie values whose HMAC matches the identity token's sub and fam, so two or more naru_csrf cookies pass under the same rule (00 R29 (d) 33rd edition).

#### Acceptance Criteria

- [ ] AC-1: ① BOTH, same user's valid JSON body refreshToken (family F) and valid naru_rt (family G), no X-CSRF-Token, no Origin -> 204; both F and G are revoked and neither CSRF token nor Origin is checked.
- [ ] AC-2: ① BOTH, body token of family F and a naru_rt of another user's family H -> 204; only F is revoked and H stays usable.
- [ ] AC-3: ① BOTH, expired body token plus valid naru_rt of family G -> 204 with no identity; G stays usable. The same request with ?all=true -> 401 invalid_token.
- [ ] AC-4: ① Body holds rt1 of family F already marked used by rotation, plus a valid Bearer at2 of F -> 204 with no identity; F is not revoked and onRefreshTokenReused is not fired.
- [ ] AC-5: ② COOKIE, no access cookie, valid naru_rt, matching X-CSRF-Token and same-origin Origin -> identity found (204, its family revoked); without X-CSRF-Token -> 403 csrf_failed and nothing is revoked; the same request with ?all=true and no X-CSRF-Token -> 403 csrf_failed and the user's ver is unchanged.
- [ ] AC-6: ③ COOKIE, no naru_rt, valid naru_at of family F with X-CSRF-Token built from F and a same-origin Origin -> 204 and revokeFamily(F); without X-CSRF-Token -> 403 csrf_failed and F is not revoked.
- [ ] AC-7: ③ HEADER, login gave at1 and rt1 (family F); POST /auth/logout with only Authorization: Bearer at1 -> 204 and revokeFamily(F); a following POST /auth/refresh with rt1 -> 401 while families of other devices still refresh.
- [ ] AC-8: COOKIE, two naru_rt cookies count as no refresh token (step ③ applies), and two naru_at cookies count as no access cookie on logout; a Bearer token and a naru_at sent together in BOTH mode -> 400 invalid_request before the table is applied.
- [ ] AC-9: ② COOKIE, same-origin Origin, an invalid (expired or revoked) naru_rt of a family G other than F plus a valid naru_at of family F with F's X-CSRF-Token -> 204 with no identity and F is not revoked (the access token is not consulted); the same request with ?all=true -> 401 invalid_token and ver unchanged.
- [ ] AC-10: HEADER, POST /auth/logout with only a valid naru_rt cookie -> 204 with no identity and the naru_rt family is not revoked (HEADER mode does not read naru_rt, 00 R29 (g)); the same request with ?all=true -> 401 unauthenticated and ver unchanged (no token was read).
- [ ] AC-11: ② COOKIE, valid naru_rt with matching X-CSRF-Token but Origin https://evil.example, not in trustedOrigins, POST /auth/logout -> 403 csrf_failed and nothing is revoked; the same request with ?all=true -> 403 csrf_failed and the user's ver is unchanged. ③ COOKIE, no naru_rt, valid naru_at of family F with F's X-CSRF-Token and the same cross-origin Origin -> 403 csrf_failed and F is not revoked.
- [ ] AC-12: BOTH, no body refreshToken and no Authorization header: ② a valid naru_rt with a same-origin Origin but no X-CSRF-Token -> 403 csrf_failed and nothing is revoked; ② a valid naru_rt with matching X-CSRF-Token but Origin https://evil.example, not in trustedOrigins -> 403 csrf_failed and nothing is revoked; ③ no naru_rt, a valid naru_at of family F with F's X-CSRF-Token and Origin https://evil.example -> 403 csrf_failed and F is not revoked; ③ the same naru_at request with a same-origin Origin -> 204 and revokeFamily(F).
- [ ] AC-13: Stale ver (00 R45: valid = signature, expiry and ver): HEADER, login gives at1 and rt1 (family F) for user u; a second login of u gives at2, and POST /auth/logout?all=true with Bearer at2 -> 204 increments u's ver. Then POST /auth/logout with body rt1 (unexpired, unused, unrevoked record) -> 204 with no identity, revokeFamily(F) is not called through it and onLogout does not fire; the same request with ?all=true -> 401 invalid_token with WWW-Authenticate: Bearer error="invalid_token" and u's ver unchanged. The same two requests carrying only Authorization: Bearer at1 before its exp give the same two outcomes.
- [ ] AC-14: No identity means no CSRF check (00 R29 (b), R45 ②③④): COOKIE, same-origin Origin, no X-CSRF-Token header. (a) Only a naru_rt of family G that was revoked by an earlier logout of G, and separately only a naru_rt whose exp has passed -> 204 that clears naru_at, naru_rt and naru_csrf with Max-Age=0, not 403 csrf_failed; the same requests with ?all=true -> 401 invalid_token with WWW-Authenticate: Bearer error="invalid_token", not 403, and the user's ver unchanged. (b) No naru_rt and only an expired naru_at -> 204 that clears the same cookies, not 403; with ?all=true -> 401 invalid_token, not 403.
- [ ] AC-15: Two naru_csrf cookies on logout (00 R29 (d) 33rd edition): COOKIE, same-origin Origin. ② A valid naru_rt of family F with two naru_csrf cookies (the first built from an old family G, the second from F) and X-CSRF-Token equal to the F value -> 204 and revokeFamily(F); X-CSRF-Token equal only to the G value -> 403 csrf_failed and F is not revoked. ③ No naru_rt, a valid naru_at of family F with the same two naru_csrf cookies: X-CSRF-Token equal to the F value -> 204 and revokeFamily(F); equal only to the G value -> 403 csrf_failed and F is not revoked.


### FR-FLOW-003 — Logout outcomes: family revocation, all-devices logout, no-identity responses and store errors
#### Requirement

When logout finds an identity it shall revoke that token's family with RefreshTokenStore.revokeFamily(fam) (a RefreshTokenStore is always present in v1, FR-TOKEN-002), and with ?all=true shall increment the user's ver; without identity a plain logout shall answer 204 clearing auth cookies in COOKIE and BOTH modes without onLogout and ?all=true shall answer 401 invalid_token when at least one token was read under the effective transport mode (SEC-FLOW-004) but the SEC-FLOW-010 decision table yields no identity (the candidate it selects fails the signature, expiry, ver or RefreshTokenStore record-state check, even when a later candidate it does not consult is valid) or 401 unauthenticated when none was read, in both cases leaving ver unchanged; a store error during identity resolution shall answer 503 SERVICE_UNAVAILABLE.

#### Acceptance Criteria

- [ ] AC-1: HEADER or BOTH, only an expired Bearer token, POST /auth/logout -> 204, auth cookies cleared in BOTH mode, no onLogout, ver unchanged; the same token with ?all=true -> 401 invalid_token with WWW-Authenticate: Bearer error="invalid_token" and ver unchanged. COOKIE, the same request with ?all=true -> 401 unauthenticated and ver unchanged (COOKIE mode does not read the Bearer, 00 R29 (g)).
- [ ] AC-2: No token at all, POST /auth/logout?all=true -> 401 unauthenticated with WWW-Authenticate: Bearer without an error attribute (", ApiKey" appended when API keys are enabled).
- [ ] AC-3: COOKIE, no access cookie, valid naru_rt with matching X-CSRF-Token and a same-origin Origin, POST /auth/logout?all=true -> 204 and the user's ver is incremented by exactly 1; every refresh token of that user then fails.
- [ ] AC-4: Valid identity, plain logout -> revokeFamily(fam) called once, ver unchanged, onLogout fired once (the effective RefreshTokenStore is the FR-TOKEN-002 default when the app configures none).
- [ ] AC-5: Valid refresh token with ?all=true while RefreshTokenStore.find throws -> 503 SERVICE_UNAVAILABLE; while the UserStore ver read throws -> 503 SERVICE_UNAVAILABLE; in both cases no other candidate is tried and no 401 is sent.
- [ ] AC-6: Every 204 in COOKIE or BOTH mode clears naru_at, naru_rt and naru_csrf with Max-Age=0 whether or not an identity was found; a repeated logout returns 204 again (idempotent).
- [ ] AC-7: Token from AuthTesting.tokenFor(auth, "alice") as Bearer, POST /auth/logout -> 204; no real family of alice is revoked.
- [ ] AC-8: BOTH, an expired body refreshToken plus a valid naru_rt, POST /auth/logout?all=true -> 401 invalid_token and ver unchanged, although the unconsulted naru_rt is valid (00 §6.4 row 'R45 × 본문과 쿠키가 함께 온 로그아웃'; same case as SEC-FLOW-010 AC-3).


### FR-TOKEN-004 — PasswordPolicy and BreachedPasswordChecker applied before every new password is hashed
#### Requirement

The auth module shall check every new password set through register, setup, admin create, admin reset, AuthUsers.create and POST /auth/password against PasswordPolicy and, when configured, the BreachedPasswordChecker SPI before taking a hash slot, and shall not apply the policy to login.

#### Acceptance Criteria

- [ ] AC-1: A new password shorter than the policy minimum length is rejected on each of the six paths and the hasher is called zero times
- [ ] AC-2: A password longer than 128 characters is rejected on each of the six paths with zero hash calls (06 AUTH-T24) [INFERRED:med:advisory]
- [ ] AC-3: An empty password is rejected before hashing on the six set-password paths; on login the empty password is the request-shape 400 owned by FR-FLOW-004 AC-6 (not a policy check); no IllegalArgumentException from SecretKeySpec escapes on any path
- [ ] AC-4: With a BreachedPasswordChecker that returns true for "Password123!", setting that password is rejected; with no checker configured no checker call is made
- [ ] AC-5: When the BreachedPasswordChecker throws, the new password is not stored, the hasher is called zero times, and the exception message does not appear in the response body
- [ ] AC-6: Login with an existing password that violates the current policy still succeeds (the policy is not applied to login) [INFERRED:med:advisory]
- [ ] AC-7: BreachedPasswordChecker is a @FunctionalInterface so a Kotlin lambda compiles as the checker


### SEC-IAM-003 — Revocation by a single ver++ and which changes bump ver
#### Requirement

Auth shall increment the user's ver exactly once for logout ?all=true, password change or admin reset, disable, and recovery, so that one ver++ revokes access, refresh and device tokens, WebSocket sessions and API keys of that user, and shall not change ver for role, group, profile or rehash writes, whose effect reaches requests through the principal cache TTL and the key decision formula.

#### Acceptance Criteria

- [ ] AC-1: Each of POST /auth/logout?all=true, a successful password change, an admin password reset, an admin disable (A4 with enabled false, which writes setEnabled(false) and then bumpTokenVersion) and recovery of an existing user increases ver by exactly 1 (store row checked before and after).
- [ ] AC-2: setRoles, a group membership change, a group edge change, AuthUsers.update of attributes, rename and updatePasswordHash (rehash) leave ver unchanged; a direct UserStore.setEnabled call also leaves ver unchanged (it writes only enabled, REL-IAM-001 AC-1), so the bump on disable comes from the separate bumpTokenVersion.
- [ ] AC-3: After one ver++, a previously valid access token (except on a route without a guard under .statelessAccess(true), where it stays valid until exp per SEC-AUTH-007 AC-7), refresh token and device token of that user are all rejected (401 / not trusted) and an API key issued before the bump is rejected with 401 invalid_api_key; with the off-request stale-key deletion held (an injected executor that runs no queued task, REL-APIKEY-002), that key is listed with status stale.
- [ ] AC-4: A demoted user (role removed, ver unchanged) loses the removed scopes on the acting node immediately and on other nodes within userCacheTtl (default 5 s); a key of that user loses the scope by the 'key covers and owner covers' rule.
- [ ] AC-5: Javadoc and docs/api state that ver only increases and that API keys must be reissued after a password change.


### SEC-APIKEY-003 — API key format nk_prefix8_secret32 and SHA-256 storage
#### Requirement

The auth module shall generate keys as `nk_<prefix8>_<secret32>` where prefix8 and secret32 are base62 (`[A-Za-z0-9]`) and secret32 comes from `SecureRandom`, shall store only the SHA-256 of the secret as 64 lower-case hex characters, and shall return the plaintext key exactly once in the issuance result.

#### Acceptance Criteria

- [ ] AC-1: A key returned by `auth.apiKeys().issue(...)` matches the regular expression `nk_[A-Za-z0-9]{8}_[A-Za-z0-9]{32}`.
- [ ] AC-2: The `StoredApiKey` written to the store has `getPrefix()` equal to the 8 prefix characters and `getSecretHash()` equal to the lower-case hex SHA-256 of the 32 secret characters (64 characters, `[0-9a-f]{64}`); no store field holds the plaintext secret.
- [ ] AC-3: `ApiKey` returned by `list(ownerUserId)` and by the list endpoints carries no secret and no secret hash.
- [ ] AC-4: On a protected path with a non-exhausted IP, `Authorization: ApiKey abc` gets 401 `invalid_api_key` and is counted in the key-only IP counter.
- [ ] AC-5: `Authorization: ApiKey nk_ab12cd3_<secret32>` (7-character prefix) and a value whose secret contains `-` both get 401 `invalid_api_key`.


