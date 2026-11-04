# Junior usability re-study: auth/RBAC module (0.11.0), round 6

Date: 2026-11-04. Branch `kiwi/orch/2026-10-28.naru.express5-v010/integration`, working tree uncommitted, after OPS-AUTH-014 and OPS-AUTH-015 (build 3477/0). The study was run by an independent agent that was not given the earlier reports or the list of fixes. Persona and method are those of [report.md](report.md). This round also checked every error answer it triggered against the doc statement about it, ran about 85 mistake variants and compiled all 103 auth.md fragments.

Cleanup check: `git status --porcelain | grep tmpjunior` prints nothing.

## Compared with round 5

| | Round 5 | Round 6 |
|---|---|---|
| Tasks 1–6, K, examples | All first try | **All first try** |
| Java fragments | 51/51 | 52/52 |
| Wrong method on an auth endpoint | bare 401 | 401 naming `POST <path>` |
| Non-object body | two messages | one message, except `[]` (Gson reads it as `{}`) |
| High items | 0 | 0 |

## Findings

The main session checked the leeway, the DEV list, the 403 message and the password field in the code.

| Sev | Where | Problem | Suggested fix |
|---|---|---|---|
| Medium | auth.md token lifetimes | Access tokens are verified with the 60 s `Jwt` leeway; an expiry test at `accessTtl` + 30 s still gets 200 | State the leeway |
| Medium | auth.md:287 | `[]` gives the missing-field answer (Gson reads it as `{}`), not the one body message | Say so |
| Medium | auth.md:350, :354 | The DEV list omits the `.user(...)` password policy (WARN in DEV, refusal in PROD) | Add it |
| Medium | auth.md:1587 | The Kotlin test-kit fragment has top-level `@Test fun`; JUnit 4 rejects it | Wrap in a class |
| Low | auth.md `auth.users()` | A policy refusal from a handler reaches `auth.errors()` as a bare 400 | Document, catch `IllegalArgumentException` |
| Low | runtime | `POST /auth/password` without `currentPassword` gives a bare 400 | Name the field |
| Low | runtime | A bodiless `POST /auth/login` gives a generic 415 | The auth 415 message |
| Low | auth.md:199 | The never-installed ERROR goes to the auth logger, not `app.logger()` | Say so |
| Low | HTTP test recipe | 5 failed logins per (user, IP) give 429 for 15 minutes in tests | Mention `.loginFailureLimits(...)` |
| Low | auth.md:985, :1205, :1566 | Partial import lists look complete | Show every import |
| Low | auth.md:1188, :1317, :1352, :1470 | Kotlin fragments use `auth` without a note | Add the note |
| Low | runtime | A 403 does not name the missing scope or role | Name it (RFC 6750 scope) |
| Low | express-migration.md:92 | "is127.0.0.1" | Space |

## Verdict (from the study)

A junior can add login, roles, groups, events, PROD file persistence and a custom store in one afternoon using only the docs; nothing blocks. Every other error-answer statement checked matched the observed answer.
