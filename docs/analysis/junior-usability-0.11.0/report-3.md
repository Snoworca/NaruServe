# Junior usability re-study: auth/RBAC module (0.11.0), round 3

Date: 2026-11-04. Branch `kiwi/orch/2026-10-28.naru.express5-v010/integration`, working tree uncommitted, after OPS-AUTH-008 and OPS-AUTH-009. The study was run by an independent agent that was not given the earlier reports or the list of fixes. The user told it that 0.11.0 is not yet on Maven and will be published as 0.11.0. Persona and method are those of [report.md](report.md), with these additions:
- this round also read app.md, defaults.md, express-migration.md and middleware.md;
- this round compiled every auth.md snippet.

Cleanup check: `git status --porcelain | grep tmpjunior` prints nothing.

## Compared with rounds 1 and 2

| | Round 1 | Round 2 | Round 3 |
|---|---|---|---|
| Tasks 1–6 and K | 2 blocked or needed a doc fix | All done; 3 needed a fix or a wait | **All worked first try** |
| Mistake messages that name the fix | 9 of 13 | about 30 of 40 | about 30 of 34 rows; the rest are documented 401s and the logout semantics |
| Silent runtime mistakes | LAN 403, `{"user","pass"}`, password 422 | scope typo, missing `Bearer`, `use("/api", auth)` | none of these; the remaining bare 401s are wrong URLs and `.permit` method or path mismatches |
| Junior time to first login | 1 h+ or blocked | about 30 min | 30–60 min, most of it the install and version question |

Round-2 items re-observed as fixed:
- scope typo WARN
- `app.use("/api", auth)` throws at registration
- double-install message
- Bearer hint
- one ERROR line for `auth.user(req)` on a public path
- admin empty-password message
- CORS text
- group cache comment
- logout row
- `IOException` note
- Java-only null ambiguity
- 401-before-404 note

## Fixed during this round

- **express-migration.md:131 (both languages):** the comment still said a path prefix "is not installed and every request gets 500". That has been wrong since FR-AUTH-010 AC-7. A doc test now refuses `500` on that line (`AuthGuideRound2DocsTest.FR_AUTH_010_AC7`; seen red first), and the comment now says the call throws `IllegalArgumentException` at registration.

## Remaining findings (not fixed yet)

| Sev | Where | Problem | Suggested fix |
|---|---|---|---|
| High | README.md:15-54, :155; examples/README.md:69; docs/api/en/README.md:76; build.gradle:25 | Every coordinate is 0.10.0, and a local build also publishes 0.10.0, which shadows the released 0.10.0 that has no auth | Bump to 0.11.0 for the release (user decision: publish as 0.11.0) and state the coordinate in Install |
| High | auth.md:1213-1216, :972-978 | `new FileUserStore(file)` and `KeySources.fromFile(file)` without an `AuthFileKey` throw `AuthConfigException` in PROD, the default | Pass `AuthFileKey.fromEnv()`, or use `.mode(AuthMode.DEV)` with a comment |
| Medium | auth.md (most fragments) | 41 of 46 fragments fail at run time unless `JWT_SECRET` holds a valid secret | One line under the status: snippets assume `JWT_SECRET`; add `.mode(AuthMode.DEV)` to try them |
| Medium | middleware.md:39, :46 | `app.route("/api/admin").use(auth)` with an unnamed `auth`; plugging in the `Auth` object there leaves it uninstalled, so every request gets 500 | Rename it (`checkAuth`) or point to auth.md |
| Medium | defaults.md:127 | "Authentication and authorization: implement middleware" ignores the built-in module | Link to auth.md |
| Medium | Quick start (auth.md:101-117) | A plain logout leaves the access token valid for up to 15 min; juniors assume logout ends the session | One sentence in the quick start |
| Medium | `/auth/register` without `.register(true)` | Core HTML 404 `Cannot POST /auth/register`, which contradicts auth.md:277 ("every response ... JSON") | JSON 404 under the mount, or document it |
| Low | Without `auth.errors()` | Auth exceptions become Core HTML with a stack trace (the doctor WARN already says how to fix it) | Keep the WARN; consider JSON by default |
| Low | auth.md:134 | `.permit` is GET/HEAD-only and exact-match; POST, `/health/` and `/Health` get a bare 401 | Doctor INFO for a non-GET route on a permit path; mention it in the quick-start comment |
| Low | auth.md:669-677 | The group example uses id `"7"` before any user exists | Create the user first |
| Low | auth.md:1191; logging.md:107 | The `ConsoleLogger` package is not documented | Add the import |
| Low | auth.md:1392 | Uses an undefined `id`; throws NPE when it is absent | Use a created user |
| Low | auth.md:1617 | The HTTP test recipe's `HttpURLConnection` cannot send PATCH | A note or a PATCH-capable client |
| Low | Roles section | Role names are case-sensitive (`"Editor"` builds; the doctor WARNs) | Mention it |

## Verdict (from the study)

Tasks 1–6 and K worked first try once the module was on the classpath. The quick start, the HTTP test recipe and both `runAuth` examples run as copied. A junior can ship login in one afternoon for DEV, loopback, PROD behind a TLS proxy, or PROD on the same machine. The risks are:
- the 0.11.0 install path;
- the two PROD file-store snippets;
- logout semantics.

Deploying on a LAN over plain HTTP needs the `allowInsecureTransport` section.
