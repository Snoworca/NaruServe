# Junior usability re-study: auth/RBAC module (0.11.0), round 2

Date: 2026-11-04. Branch `kiwi/orch/2026-10-28.naru.express5-v010/integration`, working tree with uncommitted changes (after OPS-AUTH-004…007, FR-AUTH-010, FR-IAM-016, FR-TOKEN-004 AC-8, FR-AUTH-004 AC-7). Run by an independent agent that was not given the first report or the list of fixes. The main session wrote this file and spot-checked: `README.md:21-33` (Install pins 0.10.0), `build.gradle:25` (`version = '0.10.0'`), `FileLoginThrottle`/`FileDeviceTrust` absent from `docs/api/en/auth.md`, `auth.md:1941` (constructors listed without `throws IOException`), and the cleanup (`git status --porcelain | grep tmpjunior` is empty).

Persona and method as in [report.md](report.md): the agent learned from README.md, docs/api/en/README.md, docs/api/en/auth.md, examples/README.md and the example sources, read no library `*/src/main/`, and ran every task as JUnit tests on a real TinyHttpd server on port 0 (`NaruServe/src/test/{java,kotlin}/tmpjunior/`, since deleted).

## Compared with round 1

| | Round 1 | Round 2 |
|---|---|---|
| Tasks blocked or needing a doc-driven fix | Task 1 (no user, PROD default), task 5 LAN stuck | None blocked; fixes were the agent's own HTTP client (PATCH), the 5 s cache wait, and a checked `IOException` |
| Mistakes whose message tells the fix | 9 of 13 | about 30 of 40 (the rest are 401/403 at runtime with no hint, listed below) |
| Quick start | Not runnable | Runs as written in Java and Kotlin; `runAuth` examples match the README curl output |
| Time to first login | ~30 min with a jar, 1 h+ or blocked following the docs | ~30 min; whole study ~1.5 h |
| Remaining high item | Install coordinates | Install coordinates (still the version question, now narrower: what coordinate a source build produces) |

Of the round-1 items this round touched, all behaved as fixed except the version question (round-1 #1), which is now narrower. This round did not read defaults.md or express-migration.md (round-1 #4, #5), so those two were not re-checked.

## 1. Verdict per task

| Task | Verdict | Setup lines | Docs needed |
|---|---|---|---|
| 1. Login, one public and one protected route | Worked first try | ~10 | Quick start (l.42–115), HTTP test recipe (l.1556) |
| 2. `editor` only, others 403 | Worked first try | 1 | Guards (l.219–252) |
| 3. Own code on create/update/delete/login | Worked after 1 fix (own client: `HttpURLConnection` cannot PATCH) | ~15 | AuthEvents (l.414–521), `auth.users()` (l.594), Admin endpoints (l.1353) |
| 4. Parent-group role reaches child-group members | Worked after waiting: 403 right after `addMember`, 201 after 5.5 s (principal cache) | ~6 | Groups (l.629–686), cache note (l.660) |
| 5. PROD, real secret, users in a file across restart | Worked after 1 fix: `new FileUserStore(...)` throws checked `IOException`, the snippet hides it | ~8 | AuthFileKey (l.1929–1970), UserStore (l.575) |
| 6. Own HashMap `UserStore` | Worked first try; `AuthTesting.verifyUserStore` passed | ~30 | UserStore (l.557–571), SQL skeleton (l.706), Test kit (l.1533) |
| 7. Deliberate mistakes | 30+ variants, see §2 | — | — |
| K. Kotlin version of 1–3 | Worked first try | same | Kotlin quick start, AuthEvents Kotlin block |
| Examples (`runAuth`, Java and Kotlin, `--offline`) | Both ran; curl gave the documented results | — | examples/README.md "Auth example" |

## 2. Mistakes and their messages

| Mistake | Exact result | Tells the fix? |
|---|---|---|
| `app.use(auth)` after a route | `IllegalStateException: auth.install(app) must come before routes; found GET /health registered before it` | Yes |
| `app.use(auth)` twice | `IllegalStateException: auth.install(app) must come before routes; found ROUTER_MOUNT /auth registered before it` | No (reads as an ordering problem) |
| No `app.onError(auth.errors())` | Doctor WARN `errors placement: ... add app.onError(auth.errors()) after the routes...`; runtime HTML 500/409 | Yes in log |
| `auth.user(req)` on a public path | 500 `INTERNAL_ERROR`; log `AuthStateException ... on a public path use auth.userOrNull(req)`, logged twice with two stack traces | Yes in log |
| `auth.userOrNull(req)` on a path not in `.permit` | 401 `unauthenticated` | No (documented) |
| PROD no / short / repeated-byte / example secret | `AuthConfigException` naming the problem, `openssl rand -base64 32` and DEV; "only 1 distinct byte value" | Yes |
| PROD `FileUserStore` without key | `AuthConfigException ... pass an AuthFileKey ... NARU_AUTH_FILE_KEY ... openssl rand -base64 32` | Yes |
| Short password on register, `/auth/password`, admin create | 422 `UNPROCESSABLE_ENTITY "the password must have at least 8 characters"` | Yes |
| Empty password on admin create | 400 `BAD_REQUEST "Bad Request"` | No |
| Short password on `auth.users().create` | `...auth.internal.AuthArgumentException: the password must have at least 8 characters` | Yes (internal class name) |
| Short `.user(...)` seed, PROD / DEV | `AuthConfigException ... .user("alice") password: ...` / WARN `DEV creates the account anyway, PROD refuses it` | Yes / Yes in log |
| Null-password admin seed in PROD | Doctor FAIL naming `NARU_ADMIN_PASSWORD` and `.recovery(true)` | Yes |
| Garbage or expired token | 401 `invalid_token "the token is not valid or has expired; renew it with the refresh endpoint (POST /auth/refresh by default) or log in again"` | Yes |
| Refresh token sent as Bearer | Same 401 | Partly |
| Form body / JSON without Content-Type | 415 `auth endpoints accept only application/json bodies` | Yes |
| `{"user","pass"}` / `{"email","password"}` | 400 `"username must be a JSON string"` | Mostly |
| `POST /login`, `GET /auth/login` | 401 `unauthenticated` (default deny before 404) | No |
| Token without `Bearer ` | 401 `"authentication is required"`, `WWW-Authenticate: Bearer` | No |
| `app.use("/api", auth)` | Accepted at registration; first `/api/x` gives 500 + ERROR `GET /api/x reached auth, but auth was never installed ... app.use(auth) or auth.install(app)`; `/auth/login` 404, other paths open | Yes in log (first request only) |
| `app.trustProxy(true)` in PROD | Start refused, `use trustProxyHops(n) or trustProxyCidrs(...)` | Yes |
| PROD on 0.0.0.0, LAN client, plain HTTP | 403 `insecure_transport`; WARN once with the three ways out; Core LAN WARN | Yes in log |
| Role typo `requireRole("editr")` / `.user(..., "editr")` | Doctor WARN `undeclared role ... a typo makes the role check fail with 403`; runtime 403 | Yes in log |
| Scope typo `require("post:write")` | No warning; runtime 403 `insufficient_scope` | No |
| Bad scope grammar, `require("posts:*")`, `.permitPrefix("/")` | `IllegalArgumentException` / `AuthConfigException` with the rule | Yes |
| `cors` after `app.use(auth)` | Doctor FAIL `cors must be registered before auth.install(app); found CORS  registered after it` (double space) | Yes |
| `POST /health` with `.permit("/health")`; `GET /health/` | 401 | No (both documented) |
| Java `Auth.builder(null)` / Kotlin | Java ambiguous (documented) / Kotlin compiles | n/a |
| Access token after `POST /auth/logout` | Still 200 until expiry | No (not stated plainly) |

## 3. Friction log (rough time from start)

- **0–10 min:** README auth section says 0.11.0, build from source (README.md:153–155); Install and coordinates say 0.10.0 (README.md:21–33); quick start gives the artifact with no version (auth.md:46). What a local build or `publishToMavenLocal` produces (0.10.0) is only in build.gradle. ~5 min lost.
- **10–25 min:** auth.md is 2,015 lines, but the task table (l.9–23) and the quick start made it fast.
- **~30 min:** first working login; tasks 1 and 2 first try.
- **~40 min:** task 4, 403 right after `addMember` until the 5 s cache expired; understood only through l.660. Also: a `.user` seed's id exists only after start (the doc example uses a literal `"7"`).
- **~50 min:** task 5 compile error (checked `IOException`, snippet l.1957–1963).
- **~55 min:** task 6. Only `new DuplicateUserException(msg, cause)` was shown. The doctor WARN names `FileLoginThrottle` and `FileDeviceTrust`, which auth.md never mentions.
- **55–90 min:** mistakes, examples, Kotlin.

## 4. Ranked improvements

| # | Sev | Where | Problem | Suggested fix |
|---|---|---|---|---|
| 1 | High | README.md:21–33, :155; auth.md:46; examples/README.md:56–64 | Auth is "0.11.0", Install and examples say 0.10.0, nothing says what a source build publishes (0.10.0, shadowing the Central 0.10.0 without auth) | State the coordinate to use today, or bump to `0.11.0-SNAPSHOT` |
| 2 | Medium | auth.md:223, doctor list :329 | Scope typo in `require(...)` has no warning, only runtime 403 | Doctor WARN for a required scope no declared role grants (ignoring `*`) |
| 3 | Medium | auth.md:191 | `app.use("/api", auth)` accepted at registration | Reject a path-scoped `Installable` at `use(path, ...)` time |
| 4 | Medium | auth.md:660, :666–676, :1301 | 5 s group-write delay explained only in prose | Comment line in the group example |
| 5 | Medium | auth.md:1728, :1281–1283 | Not stated plainly that logout leaves the access token valid until expiry | One sentence on the logout row |
| 6 | Medium | auth.md:30–38 | Package table misses `PasswordPolicy`, `spi.Authenticator`, `spi.BreachedPasswordChecker`, `InMemoryApiKeyStore`, `FileLoginThrottle`, `FileDeviceTrust` (last two named by the doctor) | Complete the table; document the two file stores |
| 7 | Low | auth.md:575, :1941, :1957–1963 | `FileUserStore` constructors throw checked `IOException`, not shown | Say so; `throws Exception` in the snippet |
| 8 | Low | auth.md:329 | Doctor list omits the `undeclared role` WARN | Add it |
| 9 | Low | Runtime | `Authorization` without `Bearer ` gives a bare 401 | Hint or once-only WARN |
| 10 | Low | Runtime | Double install reported as an ordering error | Detect and name a second install |
| 11 | Low | Runtime | `auth.user(req)` on a public path logged twice with two traces | Log once |
| 12 | Low | Runtime | Admin create with empty password: bare 400 | "password must be a non-empty string" |
| 13 | Low | Runtime | `found CORS  registered` double space | Drop the empty path |
| 14 | Low | auth.md:1857 | `auth.users().create` throws an `internal` class | Public class name |
| 15 | Low | auth.md:122 | "a bare null is ambiguous" is Java-only | Say "in Java" |
| 16 | Low | auth.md:60 | Unknown paths answer 401, not 404 | One sentence on default deny |

## 5. What is already good

- Quick start and `runAuth` examples run as written in Java and Kotlin; curl output matches.
- Task table at the top of auth.md; Express equivalent plus Java and Kotlin examples almost everywhere.
- Fail-fast configuration with fix-it text: secret strength, file key, PROD seed passwords, `trustProxy(true)`, cors order, null admin seed, `permitPrefix("/")`, scope grammar.
- Consistent JSON errors with `requestId` and correct `WWW-Authenticate`; `invalid_token` points to refresh.
- The PROD plain-HTTP refusal WARN gives all three ways out.
- `AuthEvents` defaults and `AuthTesting.verifyUserStore` made tasks 3 and 6 easy.
- `FileUserStore` + `AuthFileKey` survives restart with tokens intact and hashes encrypted.
- Kotlin works without a DSL.

## 6. Verdict

Time to first working login: about 30 minutes from a cold start, mostly reading; the code was copied from the quick start and passed first time. A junior can ship login in one afternoon, in DEV and in single-machine PROD (all tasks in about 1.5 hours). Remaining time sinks: the version/install question, the 5 s group-cache delay, and silent 403/401s from a scope typo or a missing `Bearer ` prefix.
