# NaruServe 0.11.0 final verification (independent verifier)

- Date: 2026-11-03. Tree: branch `kiwi/orch/2026-10-28.naru.express5-v010/integration`, HEAD `bdd576a`, with the uncommitted 0.11.0 work in place (42 modified, 61 untracked paths before this run).
- Scope: verification only. No project file was changed apart from this report. The temporary tests `NaruServe/src/test/java/com/snoworca/naruserve/tmp9/Tmp9Verify*Test.java` were written, run with `./gradlew :NaruServe:test --offline --tests '*Tmp9Verify*'` (real TinyHttpd + Gson bootstrap, port 0, loopback), and deleted afterwards (see the end of this report).
- Requirement IDs: the SRS of target 0.11.0 (`docs/spec/17`–`21`), quoted per item below.
- Facts the caller had already checked (3379 tests green on JDK 17 and 8, speckiwi validate 0/0, every AC cited) were not re-run.

---

## A. The user's original request against the real API

### A.1 Verdict per point

| # | Request point | Present? | Where (API) | Where (docs) | Junior-usable? |
|---|---|---|---|---|---|
| 1a | JWT authentication utility | Yes | Standalone HS256 `Jwt.sign/verify` `utils/auth/jwt/Jwt.java:34,85,141`; access/refresh tokens issued by `auth.tokens()` `Auth.java:2671`; built-in `POST /auth/login`, `/refresh`, `/logout`, `/password`, `GET/PATCH /auth/me` mounted by `auth.install(app)` `Auth.java:553,840` | `docs/api/auth.md:946` (Jwt), `:1113` (TokenService), `:1418-1476` (account endpoints) | Yes. Login over HTTP works with no extra code (proved, A.2) |
| 1b | User management: login/add/delete/update calls fire CRUD event methods | Yes | `AuthEvents` (all default no-op; `beforeLogin`/`onLogin`, `beforeUserCreate`/`onUserCreated`, `beforeUserUpdate`/`onUserUpdated`, `beforeUserDelete`/`onUserDeleted`, ...) `AuthEvents.java:20-74`; fired by the built-in admin API (`/auth/admin/users` A2/A4/A5), the app facade `auth.users()` `Auth.java:2478`, register, setup and `.user(...)` bootstrap, with `AuthEventContext.getOrigin()` (`ADMIN`, `APP`, `SELF`, `REGISTER`, `BOOTSTRAP`, ...) | `auth.md:350-382`, `:455-488`, `:1196-1243` | Yes (proved: every hook fired with the right origin, A.2) |
| 1c | Annotation vs field mapping (research) | Decided: no annotations; an immutable value class `AuthUser` + `attributes` map, mapped in one `toAuthUser(row)` place of the app's store (annotations break under R8) | `user/AuthUser.java:24` | `auth.md:386`; research `docs/research/auth-rbac/00.overview.md:240` | Yes |
| 1d | "Connect the interface to the real DB and you are done" | Yes, SPI-only persistence (user decision d1, CON-IAM-003): `UserStore` (5 required methods) `user/UserStore.java:31-46`, `GroupStore`/`GroupAdminStore`, `RefreshTokenStore`, `LoginThrottle`, `DeviceTrust`, `ApiKeyStore`; in-memory and file defaults only; no JDBC code shipped | `auth.md:418-453`, `:531-692` (full SQL `UserStore` skeleton), `AuthTesting.verifyUserStore` `:1366-1397` | Yes for the user table. MULTI_INSTANCE needs six app stores (documented `:535`) |
| 2 | Groups: users have groups, groups contain groups, data passed through an interface | Yes. DAG (`parentsOf(Set)`), two required read methods `group/GroupStore.java:18-44`, write SPI `group/GroupAdminStore.java:15`, `InMemoryGroupStore`/`FileGroupStore`, depth 8 / size 256 limits that fail instead of truncating, admin group endpoints G1-G8 | `auth.md:490-529`, `:1196-1243` | Partly. A group gives permissions only through `rolesOfGroups`, whose default is empty (`GroupStore.java:41-43`); neither built-in store can assign a role to a group, `InMemoryGroupStore` is `final` (`group/InMemoryGroupStore.java:13`) and the G9 group-role route is v2 (`auth.md:1198`). A junior has to write a 7-line `GroupStore` adapter (A.2), and the docs give no example of it (`auth.md:496` is one sentence) |
| 3 | Scope-based RBAC | Yes. `resource:action` grammar with `resource:*`/`*` grants `rbac/Scopes.java:63`, `RolePolicy` (built-in `admin` = `*`) `rbac/RolePolicy.java:20,91`, `RoleStore` SPI, guards `auth.require(...)` `Auth.java:2408`, `requireRole` `:2432`, `check(AccessRule)` `:2469`; grant rule on admin writes ("only grant what you have") | `auth.md:159-192`, `:707-743`, `:1109` | Yes |
| — | "Added through `use`" | Partly. The entry point is `auth.install(app)`, not `app.use(auth)`. `Auth` is a `Middleware`, so `app.use(auth)` compiles and protects routes but silently mounts no `/auth/*` endpoint, creates no `.user(...)` account and runs no doctor, with no warning (proved, A.3 probe 3) | `Auth.java:553`; `auth.md:129-157` | Trap (see A.3) |
| — | "A junior can use it right away" | Mostly yes: 29 lines give a working app with login, protected routes, user CRUD hooks, nested groups and scope checks in DEV (A.2). The traps are listed in A.3 | — | — |

### A.2 The shortest working setup, run end to end

29 lines between the markers (7 of them are the group-to-role adapter, 6 the event hooks). Defaults used: DEV mode (temporary signing key), in-memory user store, built-in endpoints.

```java
InMemoryGroupStore groups = new InMemoryGroupStore();
groups.createGroup("company");
groups.createGroup("eng");
groups.addParent("eng", "company");                       // group inside a group
GroupStore groupRoles = new GroupStore() {                // groups confer roles only via rolesOfGroups
    public Set<String> groupsOfUser(String id) { return groups.groupsOfUser(id); }
    public Set<String> parentsOf(Set<String> ids) { return groups.parentsOf(ids); }
    public Set<String> rolesOfGroups(Set<String> ids) {
        return ids.contains("company") ? Collections.singleton("editor") : Collections.<String>emptySet();
    }
};
Auth auth = Auth.builder(System.getenv("NO_SUCH_SECRET_VAR"))   // null -> DEV temp key
        .mode(AuthMode.DEV)
        .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
        .groups(groupRoles)
        .user("admin", "admin-password-123", "admin")
        .events(new AuthEvents() {                         // the CRUD hooks: wire your DB/side effects here
            public void onLogin(AuthUser u, AuthEventContext c) { EVENTS.add("login " + u.getUsername()); }
            public void onUserCreated(AuthUser u, AuthEventContext c) { EVENTS.add("created " + u.getUsername() + " " + c.getOrigin()); }
            public void onUserUpdated(AuthUser b, AuthUser a, Set<UserChange> ch, AuthEventContext c) { EVENTS.add("updated " + a.getUsername() + " " + ch); }
            public void onUserDeleted(AuthUser u, AuthEventContext c) { EVENTS.add("deleted " + u.getUsername()); }
        })
        .build();
ServerHandle server = RestApp.start(0, app -> {
    auth.install(app);
    app.get("/posts", auth.require("posts:read"), (req, res) -> res.send("posts for " + auth.user(req).getUsername()));
    app.post("/posts", auth.require("posts:write"), (req, res) -> res.status(201).send("saved"));
    app.onError(auth.errors());
});
```

The test then, over raw HTTP/1.1: logged in as `admin` (`POST /auth/login`), created `Bob` with `POST /auth/admin/users`, put bob in `eng` (`eng` is inside `company`, `company` confers `editor`), logged in as `bob`, `POST /posts` 201 and `GET /posts` 200 `posts for bob`, `GET /posts` without a token 401, created `carol` (no group), carol's `POST /posts` 403 `insufficient_scope`, `PATCH /auth/admin/users/3` 200, `DELETE /auth/admin/users/3` 204, then `auth.users().create("dave", ...)` and `delete`.

Command and real output:

```
$ ./gradlew :NaruServe:test --offline --tests '*Tmp9Verify*'          (Tmp9VerifyJuniorAuthTest)
BUILD SUCCESSFUL in 2s
<testsuite name="com.snoworca.naruserve.tmp9.Tmp9VerifyJuniorAuthTest" tests="1" skipped="0" failures="0" errors="0" ... time="1.942">
INFO auth doctor: public paths []; mount /auth, admin /auth/admin, mode DEV, profile STANDARD, deployment SINGLE_INSTANCE
INFO auth doctor: hash cost 330000 PBKDF2 iterations, 487.4 ms per hash on this device (HashCost.calibrate(500 ms), login timing base T 500.0 ms)
INFO auth: created .user("admin") with roles [admin]
INFO POST /auth/login 200 233ms ...
INFO POST /auth/admin/users 201 227ms ...
INFO POST /auth/login 200 229ms ...
INFO POST /posts 201 0ms ...
INFO GET /posts 200 0ms ...
INFO GET /posts 401 0ms ...
INFO POST /auth/admin/users 201 221ms ...
INFO POST /auth/login 200 221ms ...
INFO POST /posts 403 0ms ...
INFO PATCH /auth/admin/users/3 200 2ms ...
INFO DELETE /auth/admin/users/3 204 0ms ...
TMP9 ... EVENTS=[created admin BOOTSTRAP, login admin, created bob ADMIN, login bob, created carol ADMIN, login carol, updated carol [ATTRIBUTES], deleted carol, created dave APP, deleted dave]
WARN auth: the secret is not set; DEV uses a temporary signing key, and restarting the app invalidates all tokens
```

File store variant (probe 6, `Tmp9VerifyTripwiresTest`): PROD, `KeySources.fromFile(keyFile, key)` + `new FileUserStore(usersFile, key)` + `.user("admin", ...)`, two app starts on the same directory; login and `GET /auth/me` worked in both rounds and the hash is stored as `enc(...)` (format version 2), user decision d2:

```
TMP9 probe6 round 0 /auth/me -> 200 {"id":"1","username":"admin","enabled":true,"roles":["admin"],"attributes":{},"serviceAccount":false}
TMP9 probe6 round 1 /auth/me -> 200 {"id":"1","username":"admin","enabled":true,"roles":["admin"],"attributes":{},"serviceAccount":false}
TMP9 probe6 users.json -> {"format":"naru-users","version":2,"nextId":2,"users":[{"id":"1","username":"admin","passwordHash":"enc(Af46NoBvH5Po...)","ver":0,...}]}
TMP9 probe6 files -> [.naru-auth.key.lock, users-refresh-tokens.json, users.json, naru-auth.key]
TMP9 probe6b PROD file store without key -> com.snoworca.naruserve.utils.auth.AuthConfigException: Auth configuration is invalid: PROD keeps no secret unencrypted in an auth file: pass an AuthFileKey to new FileUserStore(file, AuthFileKey) for .../u2.json and to KeySources.fromFile(file, AuthFileKey) for .../k2.key; AuthFileKey.fromEnv() reads it from NARU_AUTH_FILE_KEY (32 random bytes as base64, for example openssl rand -base64 32); AuthMode.DEV runs without one and writes these secrets plain
```

### A.3 What a junior trips over (all proved by execution unless marked "read")

| # | Trap | Evidence |
|---|---|---|
| 1 | `app.use(auth)` (the "use" of the request) instead of `auth.install(app)`: it compiles and protects routes, but `/auth/login` is a 404 HTML page, the `.user("admin")` account is never created, the doctor never runs, and nothing warns | probe 3: `login -> 404 <!DOCTYPE html>`, `.user admin exists -> false`, `GET /p no token -> 401 {"error":{"code":"unauthenticated",...}}`. Docs only mention `app.use(auth)` as what install does internally (`auth.md:135`) |
| 2 | The PROD "no secret" message says "or run with NARU_ENV=dev for a temporary key", but the builder never reads `NARU_ENV`; it only works if the app wrote `.mode(AuthMode.fromEnv())`. A junior who follows the message gets the same failure | probe 7: a child JVM with `NARU_ENV=dev` and no `.mode(...)` printed `CHILD Auth configuration is invalid: JWT_SECRET is not set. Generate one with: openssl rand -base64 32 — or run with NARU_ENV=dev for a temporary key.` Source: `internal/SecretRules.java:28`, default `AuthMode.PROD` at `Auth.java:3146`, doc `auth.md:276`. (SEC-AUTH-001 AC-1 requires this wording, so the AC itself carries the trap) |
| 3 | Groups give no permission until the app writes a `GroupStore` that overrides `rolesOfGroups`; `InMemoryGroupStore` is final and has no role setter, the G9 route is v2, and the docs have no example | A.2 needed 7 extra lines; read `GroupStore.java:41-43`, `InMemoryGroupStore.java:13`, `auth.md:496,1198` |
| 4 | The page tells the reader the core feature is missing: "내장 로그인·refresh·로그아웃 엔드포인트와 요청의 API 키 인증은 아직 없습니다" (`auth.md:5`) and, right under the quick start, "토큰 발급(`/auth/login`)과 검증은 아직 들어가지 않았습니다" (`auth.md:58`); same in `en/auth.md:5,58`. Both are false (A.2 logged in) | read; see C.3 for the other stale sentences |
| 5 | Ordinary 4xx answers are logged at ERROR with a full stack trace (bad refresh token, wrong current password, an app `AuthRejection(422)`, duplicate user). Looks like a server fault to a junior, and an anonymous client can flood the log through `POST /auth/refresh` | `Tmp9VerifyLoggingTest` output in B.3 |
| 6 | Startup calibrates hashing to ~500 ms per hash, so every login in a junior's own tests takes ~230-800 ms (a failed login is held to the 1.25T floor); the fast `hashIterations` hook is package-private, and `HashCost.fixed(n)` below 50,000 is a doctor FAIL in PROD | log above (`487.4 ms per hash`, failed logins 738-800 ms in B.3); `Auth.java:3759` (package-private) |
| 7 | Mandatory configuration: in DEV, only `.mode(AuthMode.DEV)`. In PROD: a secret of at least 32 bytes with 8 distinct byte values; file stores need an `AuthFileKey`; a password shorter than 8 characters is refused (`the password must have at least 8 characters`, an `IllegalArgumentException` from the internal package); install after a route throws `auth.install(app) must come before routes; found GET /x registered before it`; a missing `app.onError(auth.errors())` is a doctor WARN with a clear fix text | probes 1, 2, 4, 5. These messages are clear |
| 8 | `Auth.builder(null)` does not compile (ambiguous overload); documented | read `auth.md:64` |

---

## B. Requirement conformance sample (40 ACs, all five SRS files)

Method: read the AC, `grep -rnE "<ID> AC-<n>([^0-9]|$)"` over the test sources, read the citing test, and judge whether its assertions check the AC. Rows marked "exec" were also proved over the real engine by `Tmp9VerifySecurityProbesTest` (output in B.3).

### B.1 Table

| # | AC | Citing test | Verdict | Note |
|---|---|---|---|---|
| 1 | SEC-AUTH-001 AC-1 (PROD null secret message) | `AuthBuilderTest.java:47` | conform | Message checked; see A.3 #2 for the wording problem |
| 2 | SEC-AUTH-001 AC-4 (fewer than 8 distinct bytes) | `AuthBuilderTest.java:84` | conform | 40x'a' refused, 7 vs 8 distinct boundary |
| 3 | SEC-AUTH-002 AC-4 (DEV keeps deny, order, public rule, CSRF, throttle, grant, JSON) | `Wave11ClosureTest.java:911` | conform | Loops PROD and DEV, every listed rule asserted |
| 4 | FR-AUTH-003 AC-3 (MULTI_INSTANCE refuses local stores) | `Wave7MultiInstanceTest.java:115` | conform | Every built-in store kind and the implicit defaults |
| 5 | SEC-AUTH-005 AC-1 (exact `.permit`) | `CredentialTransportTest.java:199` | conform | GET/HEAD, POST, `/HEALTH`, `/health/`, `/%68ealth` |
| 6 | FR-AUTH-006 AC-3 (auth-router error table and logging) | `Wave11ClosureTest.java:458` | **weak** | The statuses and codes are checked, but logging only by `count(ERROR)+count(WARN) >= errors + 4` after the 5xx cases. Nothing checks that the 4xx cases are not logged, and execution shows they are logged at ERROR with a stack trace (B.3), contrary to `docs/api/auth.md:215` ("503과 500은 로그를 남깁니다") |
| 7 | FR-AUTH-007 AC-1 (beforeUserCreate rejection) | `Wave10CarryTest.java:233` | conform | 422 code + JSON + no user created |
| 8 | SEC-AUTH-008 AC-3 (char[] wiped) | `PasswordConfirmLimitTest.java:131` | conform, exec | create/checkPassword/duplicate paths |
| 9 | SEC-TOKEN-001 AC-4 (ConstantTime) | `internal/TokenPrimitivesTest.java:121` | conform | Lengths, content, bytecode has no `isEqual` |
| 10 | SEC-TOKEN-003 AC-2 (alg none/HS512/hs256) | `jwt/JwtTest.java:103` | conform, exec | alg=none on a real route -> 401 `invalid_token` |
| 11 | SEC-TOKEN-005 AC-3 (typ separates purposes) | `TokenServiceTest.java:166,237` | conform, exec | refresh-as-access 401, access-as-refresh 401 |
| 12 | SEC-TOKEN-005 AC-4 (refresh key differs) | `TokenServiceTest.java:211` | conform | Access-key-signed rt+jwt -> BAD_SIGNATURE and 401 |
| 13 | SEC-TOKEN-006 AC-1 (reuse revokes family) | `Wave7TokenTest.java:119` | conform, exec | `revoked == [F]`, rt2 then refused |
| 14 | SEC-TOKEN-006 AC-2 (reuse event, indistinguishable answer) | `Wave7TokenTest.java:132` | conform | Status/code/message/Retry-After equal to bad-signature and expired |
| 15 | FR-TOKEN-001 AC-4 (refresh re-checks user) | `TokenServiceTest.java:373` | conform | ver++, disabled, deleted; nothing issued |
| 16 | SEC-TOKEN-007 AC-1 (RFC 7914 vectors) | `internal/Pbkdf2Test.java:27` | conform | Both vectors byte for byte |
| 17 | SEC-TOKEN-007 AC-3 (verify with stored count, ConstantTime) | `password/Pbkdf2PasswordHasherTest.java:33`, `AuthArchitectureTest.java:161` | conform | ConstantTime use is proved by a bytecode reference check, not behaviourally |
| 18 | SEC-TOKEN-009 AC-1 (one PBKDF2 per attempt, one slot) | `Wave7LoginTest.java:474` | conform, exec | Counting hasher + slot counter for 5 failing and 2 legacy users; real-engine failed logins: same body, 800 vs 738 ms |
| 19 | SEC-FLOW-001 AC-1 (plain remote login 403 before handler) | `TransportRuleTest.java:64` | conform | Handler and credential reader counters 0 |
| 20 | SEC-FLOW-001 AC-3 (loopback local unless proxy header) | `TransportRuleTest.java:83` | conform | 127.0.0.1 and ::1, X-Forwarded-For and Forwarded |
| 21 | SEC-FLOW-006 AC-2 (CSRF of another family) | `Wave7CsrfTest.java:138` | conform | 403 `csrf_failed` |
| 22 | SEC-FLOW-008 AC-2 (cookie refresh needs CSRF, token not used) | `Wave9RefreshTest.java:285` | conform | `marks` counter unchanged, then 200 |
| 23 | SEC-FLOW-011 AC-2 (unknown name throttled alike) | `Wave12ThrottleTest.java:106` | conform | Same attempt number and same header set |
| 24 | SEC-FLOW-014 AC-4 (one retry only for a rehash) | `Wave9PasswordTest.java:252` | conform | 2 calls then 200; admin reset -> 409, 1 call, no token |
| 25 | SEC-IAM-004 AC-2 (target protection) | `AdminEndpointsTest.java:422` | conform | Group removal, disable, password reset of a `*` holder all 403, state unchanged |
| 26 | FR-IAM-005 AC-2 (covering table) | `rbac/ScopesTest.java:46` | conform | Full table plus write-does-not-imply-read |
| 27 | FR-IAM-009 AC-4 (key and owner both cover) | `AuthGuardsTest.java:322` | conform | Both cases of the AC |
| 28 | SEC-IAM-008 AC-1 (hash stored as enc(...)) | `FileSecretsTest.java:216` | conform, exec | Version byte, length, plain hash absent, reopen + login |
| 29 | SEC-IAM-008 AC-2 (random IV) | `internal/SecretBoxTest.java:42` | conform | 1,000 distinct IVs. The second citation `FileSecretsTest.java:239` tests a different property (only changed rows re-sealed); mis-label only |
| 30 | SEC-IAM-008 AC-4 (users file fails closed) | `FileSecretsTest.java:368`, `SecretBoxTest.java:91` | conform | 5 variants, message hygiene, file byte-equal |
| 31 | FR-IAM-015 AC-3 (PROD refuses unencrypted file secrets) | `FileSecretsTest.java:630` | conform, exec | Each one-missing case names only that option |
| 32 | REL-IAM-004 AC-1 (sync before rename) | `internal/AtomicFilesTest.java:97` | conform | Instrumented event order |
| 33 | REL-IAM-004 AC-4 (corrupt main + .bak: FAIL, untouched) | `internal/AtomicFilesTest.java:153` | conform, exec | Component level; end-to-end through `FileUserStore` confirmed by execution. The other citation `group/GroupAdminStoreTest.java:344` tests the size limit, not this AC (mis-label) |
| 34 | REL-IAM-003 AC-1 (MULTI_INSTANCE undeclared atomicity) | `Wave7MultiInstanceTest.java:95` | conform | Doctor FAIL naming `supportsAtomicFieldWrites` |
| 35 | FR-IAM-007 AC-3 (too-deep groups fail the request) | `CarriedAcceptanceTest.java:197` | conform | 503 JSON at 9, 200 at 8 |
| 36 | SEC-APIKEY-003 AC-2 (prefix + SHA-256 hex stored) | `Wave9KeyAuthTest.java:934` | conform | Row, toString, list endpoint |
| 37 | SEC-APIKEY-004 AC-9 (one compare for an unknown prefix) | `Wave9KeyAuthTest.java:394` | conform | Compare hook 1 then 2 |
| 38 | SEC-APIKEY-005 AC-1 (101st key failure 429, no lookup) | `Wave9KeyAuthTest.java:432` | conform | Lookup counter unchanged, Retry-After present |
| 39 | REL-APIKEY-001 AC-3 (rows never cached) / SEC-APIKEY-008 AC-2 (key on /me routes) | `Wave9KeyAuthTest.java:806` / `Wave10AccountTest.java:333` | conform / conform | 2 lookups for 2 requests; 403 `api_key_not_allowed` x4, `GET /auth/me` 200 |

### B.2 Counts

- ACs judged: 40 (rows 1-39; row 39 holds two ACs, REL-APIKEY-001 AC-3 and SEC-APIKEY-008 AC-2)
- conform: 39
- weak: 1 — FR-AUTH-006 AC-3 (logging clause unchecked; the behaviour contradicts the docs, B.3)
- mismatch: 0 against the literal AC text. The one behaviour that differs from a written statement is the 4xx ERROR logging, which contradicts `docs/api/auth.md:215` / `docs/api/en/auth.md:215` (C.2 row 11)
- Mis-labelled citations (the AC is still covered by another test): SEC-IAM-008 AC-2 at `FileSecretsTest.java:239`, REL-IAM-004 AC-4 at `GroupAdminStoreTest.java:344`

### B.3 Execution evidence

```
$ ./gradlew :NaruServe:test --offline --tests '*Tmp9VerifySecurity*'
BUILD SUCCESSFUL in 4s
<testsuite name="com.snoworca.naruserve.tmp9.Tmp9VerifySecurityProbesTest" tests="2" skipped="0" failures="0" errors="0" ...>
TMP9 REL-IAM-004 AC-4 -> FAIL: auth store file .../users.json is unreadable (invalid JSON at offset 1: expected a member name); a backup users.json.bak exists: inspect it and restore it by hand if it is intact
TMP9 REL-IAM-004 AC-4 files unchanged -> true
TMP9 SEC-TOKEN-003 alg=none on route -> 401 {"error":{"code":"invalid_token","message":"the token is not valid",...}}
TMP9 SEC-TOKEN-005 refresh-as-access -> 401 {"error":{"code":"invalid_token",...}}
TMP9 SEC-TOKEN-005 access-as-refresh -> 401 {"error":{"code":"invalid_refresh_token",...}}
TMP9 SEC-TOKEN-006 reuse rt1 -> 401 {"error":{"code":"invalid_refresh_token",...}}
TMP9 SEC-TOKEN-006 rt2 after reuse -> 401 {"error":{"code":"invalid_refresh_token",...}}
TMP9 SEC-TOKEN-006 onRefreshTokenReused -> [1/_ALGUDmWJvqKmrviu0hUcw]
TMP9 SEC-TOKEN-009 wrong-pw 401 800ms body={"error":{"code":"invalid_credentials","message":"the username or password is wrong",}} | unknown 401 738ms body={"error":{"code":"invalid_credentials","message":"the username or password is wrong",}}
TMP9 SEC-AUTH-008 AC-3 char[] after create -> 0000000000000000
ERROR request 1f23d08526f562b2 failed
com.snoworca.naruserve.utils.auth.AuthRejection: the refresh token is not valid
	at com.snoworca.naruserve.utils.auth.TokenService.invalid(TokenService.java:314)
	at com.snoworca.naruserve.utils.auth.TokenService.refresh(TokenService.java:155)
	at com.snoworca.naruserve.utils.auth.AccountEndpoints.refresh(AccountEndpoints.java:399)
	... (full stack trace for each of the three 401 refresh answers)

$ ./gradlew :NaruServe:test --offline --tests '*Tmp9VerifyLogging*'     (app.logger(capture), ERROR lines per request)
BUILD SUCCESSFUL in 3s
TMP9 LOG POST /auth/refresh -> 401 invalid_refresh_token | ERROR lines: [request bebd603333d7a84b failed <AuthRejection>]
TMP9 LOG POST /auth/password -> 401 invalid_credentials ("the current password is wrong") | ERROR lines: [request 27dbc3af923f5d91 failed <AuthRejection>]
TMP9 LOG POST /auth/reject -> 422 domain_not_allowed | ERROR lines: [request 44c6519e1f978102 failed <AuthRejection>]
TMP9 LOG POST /app-reject -> 422 tenant_required (app route + app.onError(auth.errors())) | ERROR lines: [request a7d5798686d171e0 failed <AuthRejection>]
TMP9 LOG POST /auth/admin/users -> 409 duplicate_user | ERROR lines: [request 90a660121a4f35b6 failed <DuplicateUserException>]
TMP9 LOG POST /auth/login -> 401 invalid_credentials | ERROR lines: []
```

Cause (read): Core logs every thrown exception whose `StandardErrors.statusOf` is 500 (`internal/StandardErrors.java:19-20`: anything that is not an `HttpException`), in `internal/DefaultRequest.java:178-183` and `internal/ErrorPath.java:38-41`, before the auth error layer turns it into its 4xx. `AuthRejection` extends `RuntimeException`, not `HttpException` (`utils/auth/AuthRejection.java:12`). Login is not affected because its handler writes the 401 itself.

---

## C. Docs vs code

### C.1 Markers

```
$ grep -rn "미확정\|name not final\|not final)" docs/api
(no output)
```

No "(미확정)" or "(name not final)" marker remains in `docs/api`. (The SRS files still carry 88 "not final" phrases in AC text: 17: 20, 18: 26, 19: 8, 20: 26, 21: 8. Those are outside `docs/api`.)

### C.2 Ten-plus statements checked against the code, with the English mirror

| # | Statement (ko `docs/api/auth.md`) | Code | en `docs/api/en/auth.md` | Result |
|---|---|---|---|---|
| 1 | `Auth.builder(@Nullable String)` and `Auth.builder(KeySource)` (`:64-65`) | `Auth.java:530,536` | `:64-65` same | match |
| 2 | `.mountPath` default `/auth`, admin at `/auth/admin` (`:80`) | `Auth.java:3150`; doctor log `mount /auth, admin /auth/admin` (A.2) | `:80` same | match |
| 3 | `.maxKeysPerUser` 20, `.maxApiKeyDays` 365, `.apiKeyFailureLimit` 100 per 900000 ms (`:82`) | `Auth.java:143-149,3186-3190` | `:82` same | match |
| 4 | `.apiKeyScheme` default `ApiKey` (`:81`) | `Auth.java:3185` | `:81` same | match |
| 5 | `GroupLimits.defaults()` depth 8, size 256 (`:497`) | `group/GroupLimits.java:11` | `:497` same | match |
| 6 | `FileUserStore` / `FileGroupStore` size limit 8 MB (`:436`, `:511`) | `user/FileUserStore.java:46` -> `internal/MiniJson.java:73` (8 MiB); `group/FileGroupStore.java:28` | `:436`, `:511` same | match |
| 7 | Codes `csrf_failed` 403, `api_key_limit` 409, `invalid_credentials` 401 (alias `WRONG_CURRENT_PASSWORD`), `group_too_large` 409 (`:310-325`) | `AuthErrorCode.java:26,28,38,48,55` | `:310-325` same | match |
| 8 | `AuthRejection` status 400-499, code `[a-z][a-z0-9_]{0,63}` (`:301`) | `AuthRejection.java:20,36` | `:301` same | match |
| 9 | Profiles: BASELINE 15 min / 14 d, STANDARD 15 min / 1 d (default), STRICT 5 min / 12 h; min password 8/8/15, max 128 (`:106-110`) | `Auth.java:478-486`; `PasswordPolicy.java:33`; doctor log `profile STANDARD` | `:108-110` same | match |
| 10 | `AuthFileKey.of(byte[])` min 32 bytes, `fromEnv()` reads `NARU_AUTH_FILE_KEY` (`:1637-1639`) | `AuthFileKey.java:29,32,49,82` | `:1637-1639` same | match |
| 11 | `checkPassword` shares 5 failures / 15 min per user (`:467`) | `DefaultAuthUsers.java:39-41` | `:467`, `:1606` same | match |
| 12 | `userCacheTtl` 5 s default (`:1146`) | `internal/PrincipalCache.java:25` | `:1146,1152` same | match |
| 13 | "auth 라우터의 오류 미들웨어 ... 503과 500은 로그를 남깁니다" (`:215`) | Core also logs every 4xx `AuthRejection`/`DuplicateUserException` at ERROR with a stack trace (B.3) | `:215` "503 and 500 cases are logged." | **code differs from the docs** (both languages) |

### C.3 Stale sentences (in both languages, contradicted by the code)

| Location (ko / en) | Sentence | Contradicted by |
|---|---|---|
| `auth.md:5` / `en/auth.md:5` | built-in login/refresh/logout endpoints and API-key request authentication "are not in yet" | `auth.md:1418-1476`, `:1492-1525`; A.2 login worked |
| `auth.md:58` / `en/auth.md:58` | "Token issuing (`/auth/login`) and verification are not in yet" (directly under the quick start) | same |
| `auth.md:101` / `en/auth.md:101` | "Until the login throttle and device trust stores can be configured, `MULTI_INSTANCE` is always refused for this reason." | `.loginThrottle(..)` `Auth.java:3674`, `.deviceTrust(..)` `Auth.java:3685`; `Wave7MultiInstanceTest.java:95-110` builds MULTI_INSTANCE with app stores |
| `auth.md:265` / `en/auth.md:265` | the MULTI_INSTANCE atomic-writes doctor item "runs once the shared token stores arrive" | same; REL-IAM-003 AC-1 test runs it |
| `auth.md:860` (ko) | "`POST /auth/setup`과 `NARU_AUTH_RECOVERY` 환경 변수는 setup 엔드포인트와 함께 들어갑니다" (future tense) | `.recoveryFromEnv()` `Auth.java:3593`; setup endpoint documented at `auth.md:1431` |

---

## D. Remaining work for 0.11.0 as a release

| # | Item | Source |
|---|---|---|
| 1 | Nothing of 0.11.0 is committed: the whole `utils.auth` package, its tests and the 0.11.0 SRS/doc edits are untracked or modified (42 `M`, 61 `??` before this run, including `NaruServe-api/src/main/java/com/snoworca/naruserve/utils/`) | `git status --porcelain`; review-loop report "Not committed: Nothing was committed or pushed." (`docs/analysis/kiwi-review-fix-loop-2026-11-02.naruserve.self-1830/report.md:57-58`) |
| 2 | A stray empty file named `=` at the repository root (untracked, 0 bytes, 2026-11-03 13:12), most likely a shell-redirection accident; delete before committing | `ls -la =` -> `-rw-r--r-- 1 beom staff 0 Nov 3 13:12 =` |
| 3 | Version label still 0.10.0 | `build.gradle:25` `version = '0.10.0'`; README install lines `README.md:13-81` all 0.10.0 |
| 4 | No 0.11.0 release notes, test-results or vulnerability-scan record (only 0.10.0 files exist); README does not mention auth at all | `ls docs/release` (`0.10.0-release-notes*.md`, `0.10.0-test-results.md`, `0.10.0-vulnerability-scan.md`); `grep -c auth README.md` -> 0 |
| 5 | No 0.11.0 release requirement in the SRS: `docs/spec/15.release.srs.md` has no 0.11.0 entry, so version bump, notes, `-Prelease` SBOM/scan/licence report and Maven Central publication are not tracked for this target | `grep -c 0.11.0 docs/spec/15.release.srs.md` -> 0; `gradle/release.gradle` exists for those checks |
| 6 | All 157 implemented 0.11.0 requirements are `implemented`, none `verified` (11 discarded JDBC ones); missing evidence 0, blockers 0 | `speckiwi active-target --json`: `countsByStatus {"implemented":157,"discarded":11}`, `implementedNotVerified` 157, `missingEvidence` 0 |
| 7 | Residual decision kept as is by the user: R2-T2-F03 (TinyHttpd failure-floor latency margin). R2-M1-F06 was fixed. No other residual | review-loop report `:27`, `:41` |
| 8 | Fix or decide: 4xx `AuthRejection`/`DuplicateUserException` logged at ERROR with stack trace (log-flood from anonymous `POST /auth/refresh`); docs say only 503/500 are logged; FR-AUTH-006 AC-3 test does not catch it | B.1 row 6, B.3, C.2 row 13 |
| 9 | Fix the stale doc sentences (both languages) | C.3 |
| 10 | Junior-facing gaps: `app.use(auth)` fails silently (no warning); the PROD secret message suggests `NARU_ENV=dev`, which has no effect without `.mode(AuthMode.fromEnv())`; no built-in way or doc example to give a group a role | A.3 #1-#3 |
| 11 | Two mis-labelled AC citations (coverage still holds) | B.2 |
| 12 | SRS AC text still carries 88 "(name not final)" phrases (outside `docs/api`; the CLAUDE.md rule settles them in `docs/api`, where none remain) | C.1 |

---

## Cleanup

```
$ rm -rf NaruServe/src/test/java/com/snoworca/naruserve/tmp9 ... ; git status --porcelain | grep -i tmp9verify
(no output)
```
