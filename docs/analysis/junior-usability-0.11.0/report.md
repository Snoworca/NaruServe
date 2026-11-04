# Junior usability study: auth/RBAC module (0.11.0)

Date: 2026-11-04. Branch: `kiwi/orch/2026-10-28.naru.express5-v010/integration`, working tree with uncommitted changes. Run by an independent agent. The main session wrote this file and checked the top findings against the files: `defaults.md:131/134`, `express-migration.md:130` in both languages, and `en/README.md:70`.

Persona: a junior developer with about a year of experience. Knows Express.js and basic Java/Kotlin, has never seen this codebase, and has one afternoon to add login. The agent learned from README.md, docs/api/README.md (both languages), docs/api/auth.md (both languages) and the pages they link to (app.md, defaults.md, express-migration.md). It read no `NaruServe-api/src/main` file. The only non-doc file it read was `build.gradle`, once, to find the test classpath.

Method:
- **Tests:** temporary JUnit tests started a real TinyHttpd server on port 0 and called it with `HttpURLConnection`.
- **Classpath:** the `NaruServe/src/test` classpath (API, TinyHttpd and Gson), the same one the `naruserve` dependency gives. `:NaruServe-api:test` has no engine or codec.
- **Cleanup:** every temporary file was deleted and nothing was committed.

## Verdict per task

| # | Task | Verdict | Setup lines | Docs needed |
|---|---|---|---|---|
| 1 | Login, one public route, one protected route | Worked after 1 fix: PROD refused the missing secret, so the agent switched to DEV | 11 | The quick start was not enough because it creates no user. `.user(...)` is at auth.md:876, halfway down the page |
| 2 | Only `editor` may call a route; a non-editor gets 403 | Worked first try | +3 | Quick start and the Guards section |
| 3 | Own code on create, update, delete and login | Worked first try, but 3 of 4 signatures had to be guessed | 6 | AuthEvents; only `onUserUpdated` has its parameters listed |
| 4 | Groups, with a parent-group role reaching child-group members | Worked first try. A membership added after the user's first request took about 5 s to apply (principal cache) | 7 + one `addMember` per user | Groups and principal cache sections |
| 5 | PROD, real secret, users kept in a file across a restart | Worked after 1 fix (`AuthFileKey`); the error message alone was enough. A PROD server reached over the LAN is blocked | +3 | Error message, then AuthFileKey (the last section) |
| 6 | Own user store (HashMap) | Worked after 1 fix: `verifyUserStore` caught id reuse | 34 | UserStore, own database, test kit |
| 7 | Mistakes | 9 of 13 messages tell you the fix | — | — |
| K | Kotlin version of tasks 1–3 | Worked first try | about the same | — |

## Mistakes and their messages

| Mistake | Result | Tells you the fix? |
|---|---|---|
| `app.use(auth)` after a route | `IllegalStateException: auth.install(app) must come before routes; found GET /hello registered before it` | Yes |
| Forgot `app.onError(auth.errors())` | Doctor WARN with the exact fix. The client gets an HTML error page | Yes, in the log |
| `auth.user(req)` on a public path | `AuthStateException ... on a public path use auth.userOrNull(req)` | Yes |
| PROD with no secret, a short secret, an example secret or one repeated byte | The message names the problem and gives `openssl rand -base64 32` and the DEV alternative | Yes (grammar slip: "1 distinct byte values") |
| PROD `FileUserStore` without a key | The message names `AuthFileKey`, `NARU_AUTH_FILE_KEY` and the `openssl` command | Yes |
| Too-short password on register or password change | `422 {"error":{"code":"UNPROCESSABLE_ENTITY","message":"Unprocessable Content"}}` | **No.** en/auth.md:1576 promises the reason in the message |
| Too-short password through `auth.users().create` | `the password must have at least 8 characters` | Yes |
| `.user("hank","abc")` in PROD | Accepted, and the login returns 200 | **No error at all** |
| Garbage or expired token | `401 invalid_token "the token is not valid"` | Acceptable; no hint about refresh |
| Login with a form body | `415 ... accept only application/json bodies` | Yes |
| Login with `{"user","pass"}` | `400 BAD_REQUEST "Bad Request"` | **No** |
| Token sent without `Bearer ` | `401 unauthenticated` | No |
| `app.use("/api", auth)`, copied from express-migration.md:130 | 500 on every request. The ERROR log names the fix but prints `GET /x` for `/api/x` | Yes in the log |
| `app.trustProxy(true)` in PROD, as defaults.md:131 advises | Doctor FAIL, so the app does not start | Yes, but it contradicts the docs |
| PROD server called over the LAN | `403 insecure_transport`, with nothing in the log explaining it | **No** |

## Friction log

Times are estimates from the start of the session.

1. **0:00** README.md never mentions auth. The API index row (en/README.md:70) says "In progress for 0.11.0 ... JDBC DDL", while auth.md says it is final and that no built-in DB store exists.
2. **0:05** README Install pins 0.10.0, which has no `utils/auth`, and the build is still version 0.10.0. Following Install ends in "package does not exist". The only way out is a source build and `publishToMavenLocal`, which auth.md never mentions. This can block a junior outright (estimate: 20–40 min lost).
3. **0:10** The quick start has no imports. The package table lists about 120 types in 13 packages.
4. **0:15** The quick start creates no user, so login cannot succeed. `.user(...)` is only at :876 (estimate: 10–20 min to find).
5. **0:30** First working login. The docs show no curl sequence.
6. **0:35** `auth.require("editor")` fails with a clear scope-syntax error, but the error does not suggest `requireRole`. `requireRole` accepts an undeclared role silently, so a typo fails as 403.
7. **0:45** Event signatures were guessed (all compiled).
8. **1:00** The groups example worked. The cache delay is documented only for `grantRole`, not for `addMember`, and there is no `auth.groups()` facade.
9. **1:15** PROD `FileUserStore` worked once the message's fix was copied. Users and tokens survived a restart, and the hashes were stored as `enc(...)`.
10. **1:25** defaults.md recommends `trustProxy(true)`, which the doctor FAILs.
11. **1:35** Over the LAN, `/auth/login` gives 403 `insecure_transport`. The engine has no TLS, and nothing points to `.allowInsecureTransport`. Stuck.
12. **1:50** A custom store with `id = size()+1` was caught by `verifyUserStore`, with an excellent message. The startup WARN then points to three more SPIs and does not mention `FileRefreshTokenStore`.
13. **2:20** express-migration.md:130 (both languages) uses `app.use("/api", auth)`.
14. **Stale or unexplained text:** :784 and :829 contain stale "not in yet" sentences. Requirement codes appear unexplained: C4, R32, R33, 00 R5, A4, G9.

## Ranked improvements

| Rank | Severity | Where | Problem | Suggested fix |
|---|---|---|---|---|
| 1 | high | README Install, en/README.md:70, auth.md:5 | No released coordinate contains auth; the status wording contradicts itself | Add auth to README Install and the API map. Explain the source build until 0.11.0 ships. Drop "JDBC DDL" |
| 2 | high | auth.md:24-60 (both languages) | Quick start cannot run as written: no imports, no user, PROD by default | Complete `main` with imports and `.user(...)`, plus curl lines for a DEV start, login and the Bearer call |
| 3 | high | Secure-transport rule; auth.md:200, :1419 | A PROD server on the LAN or a device gives 403 `insecure_transport` with no log hint, and the engine has no TLS | Log a WARN once naming the TLS proxy + `trustProxyHops`, `.allowInsecureTransport(reason)` or DEV. Add a "Serving on a LAN / device" box |
| 4 | high | defaults.md:131 / :134 | Recommends `trustProxy(true)`, which the auth doctor FAILs in PROD | Use `trustProxyHops(1)` or `trustProxyCidrs(...)` |
| 5 | high | express-migration.md:130 (both languages) | `app.use("/api", auth)` leaves auth uninstalled, so every request gets 500 | `app.use(auth)` (+ `.protectOnly("/api")`) |
| 6 | high | Password policy vs auth.md:1576 | The 422 password-policy response carries no reason | Put the reason in `message` (with a code such as `password_too_short`), or fix the doc |
| 7 | medium-high | `.user` seeding | Seed passwords skip `PasswordPolicy` (`"abc"` is accepted in PROD) | Check in `build()` (PROD violation, DEV WARN) and document it |
| 8 | medium | auth.md structure (1,689 lines, 53 sections, no table of contents) | Reads like a spec; task sections are scattered among internals | Task guide at the top. Reorder the page: start, endpoints, guards, users, roles, groups, events, storage, deploy, then reference. Move internals to an advanced page |
| 9 | medium | AuthEvents | Signatures incomplete | Table of method, signature, when it fires and whether it can refuse, plus a "sync my table" example |
| 10 | medium | Login body validation | `{"user","pass"}` gets a bare "Bad Request" | Say which fields are required |
| 11 | medium | Groups docs | The cache TTL is documented only for `grantRole` | Document it for every store write, show `.userCacheTtl(0)` for tests, or add `auth.groups()` |
| 12 | medium | auth.md:784, :829 | Stale "not in yet" sentences | Delete them |
| 13 | medium | Jargon in both languages | Requirement codes and unexplained terms (principal, fam, ver, kid, doctor, DAG, NFKC, hop mode, CIDR, grant rule) | Remove the codes and add a short glossary |
| 14 | medium | examples/ | No auth example | Add Java and Kotlin examples with a DEV seed user and curl lines |
| 15 | medium | Test kit | No recipe for an HTTP-level test of your own app | JUnit recipe with `listen(0)`, `port()`, `AuthTesting.tokenFor` and `.userCacheTtl(0)` |
| 16 | low-medium | Custom UserStore WARN | Points to three more SPIs | Mention `FileRefreshTokenStore` |
| 17 | low | `requireRole` and `.user` roles | Undeclared role names are accepted silently | Doctor INFO or WARN listing undeclared roles |
| 18 | low | `listening on 0.0.0.0 ... must add its own authentication` | Logged even with auth installed | Skip or reword it when auth is installed |
| 19 | low | Uninstalled-auth ERROR | Prints `GET /x` for `/api/x` | Print the original path |
| 20 | low | `invalid_token` message | No hint that expiry is the usual cause | Mention expiry and `POST /auth/refresh` |
| 21 | low | Key-strength message | "1 distinct byte values" | Pluralise correctly |

## What is already good

- **Short wiring:** `app.use(auth)` gives the login, refresh, logout, `/auth/me` and admin endpoints in 11 lines, and the guards look like Express middleware.
- **Error messages:** they name the offending route, method, environment variable, the `openssl` command and the DEV alternative, and they never print a secret.
- **Safe defaults:** PROD refuses weak or example secrets and plain file secrets. DEV relaxes three listed things and announces it.
- **Client errors:** the JSON error shape is consistent, `WWW-Authenticate` is correct, and the error-code catalogue is in one table.
- **Extension points:** event overrides compile on the first guess, and `object : AuthEvents` works in Kotlin.
- **Groups:** the inheritance example worked as written.
- **Store checks:** `AuthTesting.verifyUserStore` gives excellent contract messages.
- **Encrypted file persistence:** one constructor argument, and users and tokens survive a restart.
- **Korean/English parity:** both pages have 1,689 lines and the same headings.
- **Express mapping:** present nearly everywhere.

## Verdict

Time to the first working login: about 30 minutes with a jar that contains auth. Following the documented coordinates, it takes an hour or more, or blocks.

A junior can ship login in one afternoon, provided they have a jar with auth and run locally or behind a proxy configured with `trustProxyHops`. Fixing improvements 1–5 would make the "junior can use it right away" goal true without any API change.
