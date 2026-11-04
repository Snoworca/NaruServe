# Junior usability re-study: auth/RBAC module (0.11.0), round 8

Date: 2026-11-04. Branch `kiwi/orch/2026-10-28.naru.express5-v010/integration`, working tree uncommitted, after OPS-AUTH-018 and OPS-AUTH-019 (build 3501/0). This is the re-study after the fifth and last fix cycle the user allowed ("repeat up to 5 times").

The study was run by an independent agent that was not given the earlier reports or the list of fixes. Persona and method are those of [report.md](report.md). This round ran 112 mistake variants, checked about 40 doc statements about error answers, and compiled all 103 auth.md fragments.

Cleanup check: `git status --porcelain | grep tmpjunior` prints nothing.

## Rounds 1 to 8

| | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 |
|---|---|---|---|---|---|---|---|---|
| High | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 0 |
| Medium | 7 | 5 | 5 | 2 | 4 | 4 | 2 | 1 |
| Low | 5 | 10 | 7 | 6 | 6 | 9 | 7 | 6 |
| Tasks 1–6, K first try | no | partly | yes | yes | yes | yes | yes | yes |

## Findings (not fixed: the cycle limit was reached)

| Sev | Where | Problem | Suggested fix |
|---|---|---|---|
| Medium | auth.md:1388-1404 (also 138, 653) | Without `.groups(...)`, the group admin endpoints G1–G8 answer a bare 404 `NOT_FOUND`; the docs describe them as always present | Say they need `.groups(GroupAdminStore)`; a 404 message like the register one |
| Low | auth.md:165 | The quoted missing-secret message is the old text | Update the quote |
| Low | auth.md:1619 | "Five failed logins ... answer 429" reads as the 5th being 429; the 6th attempt is | Reword |
| Low | auth.md:855-864 | The Kotlin multi-instance block uses `SqlUsers(dataSource)` and similar names defined nowhere | Comment or rename |
| Low | auth.md:115 | A wrong path under no endpoint (`POST /login`, `POST /auth/signup`) gets a bare 401 | Document in the error table, or hint under the mount |
| Low | auth.md:1619 | No Java 8 PATCH helper in the recipe | A small helper or the OkHttp dependency line |
| Low | auth.md:1392 | A1 `?limit=abc` gives a bare 400 | Name the rule |

## Verdict (from the study)

Ready for a junior. The study did all tasks in about 1.5 hours and had its first working login at about 25 minutes, mostly spent reading. The recipe and the examples work verbatim in Java and Kotlin. Every doc statement checked matched the observed answer, apart from the out-of-date quote above. The only confusion that could block someone was the undocumented 404 of the group endpoints.
