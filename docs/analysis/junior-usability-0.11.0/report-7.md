# Junior usability re-study: auth/RBAC module (0.11.0), round 7

Date: 2026-11-04. Branch `kiwi/orch/2026-10-28.naru.express5-v010/integration`, working tree uncommitted, after OPS-AUTH-016 and OPS-AUTH-017 (build 3490/0). The study was run by an independent agent that was not given the earlier reports or the list of fixes. Persona and method are those of [report.md](report.md).

This round:
- triggered more than 100 error answers and compared each with the docs;
- compiled all 103 auth.md fragments;
- ran the 45 statement fragments with a real secret.

Cleanup check: `git status --porcelain | grep tmpjunior` prints nothing.

## Compared with round 6

| | Round 6 | Round 7 |
|---|---|---|
| Tasks 1–6, K, examples | All first try | **All first try** |
| Error answers that differ from the docs | 4 | **0** |
| Java fragments | 52/52 compile | 52/52 compile; the 45 statement fragments also run |
| Kotlin fragments | 51/51 after guessing | 49/51; the 2 left are intentional sketches |
| High items | 0 | 0 |

## Findings

| # | Sev | Where | Problem | Suggested fix |
|---|---|---|---|---|
| 1 | Medium | `auth.users()` from a handler | A password-policy refusal reaches the client as a bare 400 | Show the policy reason |
| 2 | Medium | `PATCH /auth/me` | A disallowed attribute is a bare 400 | Name the attribute and `.allowedAttributes(...)` |
| 3 | Low | admin A2, G2 | Wrong field types and invalid group ids are bare 400s | Name the field and rule |
| 4 | Low | admin endpoints | A 403 does not name the scope (app guards do) | Same suffix |
| 5 | Low | request-time WARN | Suggests `app.onError`, which throws after start | Name `app.use(auth.errorMiddleware())` only |
| 6 | Low | HTTP test recipe | `java.net.http` needs Java 11 | Name OkHttp for Java 8 |
| 7 | Low | auth.md | Bodiless `POST /auth/login` 415 is not documented | Document |
| 8 | Low | PROD missing secret | Always says `JWT_SECRET is not set`, even when the app read another variable | Say the secret passed to `Auth.builder` is null or empty |
| 9 | Low | admin A2 | An undeclared role is accepted silently | WARN |

## Verdict (from the study)

Ready for a junior to add login in one afternoon. All 8 tasks pass, and the docs are accurate for every error answer triggered. What remains is polish: a few 400 answers that say only "Bad Request", and one WARN that suggests a call which throws after start.
