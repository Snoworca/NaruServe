# Junior usability re-study: auth/RBAC module (0.11.0), round 5

Date: 2026-11-04. Branch `kiwi/orch/2026-10-28.naru.express5-v010/integration`, working tree uncommitted, after OPS-AUTH-012 and OPS-AUTH-013 (build 3467/0). The study was run by an independent agent that was not given the earlier reports or the list of fixes. Persona and method are those of [report.md](report.md).

This round:
- ran 91 mistake variants;
- compiled all 102 auth.md fragments;
- ran both HTTP test recipes as copied.

Cleanup check: `git status --porcelain | grep tmpjunior` prints nothing, and the porcelain count is unchanged.

## Compared with round 4

| | Round 4 | Round 5 |
|---|---|---|
| Tasks 1–6 and K | All first try | **All first try**, also the examples `runAuth` |
| Java fragments | 51/51 with IDE imports | 51/51 (3 need a `throws` clause) |
| Missing `onError(auth.errors())` | HTML page with a stack trace in PROD | **JSON** (appended layer) plus a WARN |
| Refresh without a token, invalid JSON, disabled sign-up | bare answers | messages that name the fix |
| High items | 0 | 0 |

## Findings

The main session checked findings 1 and 2 in the files and the code.

| # | Sev | Where | Problem | Suggested fix |
|---|---|---|---|---|
| 1 | Medium | auth.md:283 | Says `GET /auth/logout` gets `Cannot GET`; without a token it is 401 first (default deny), only with a token the HTML 404 | Say so |
| 2 | Medium | auth.md:283, runtime | Says an array body gets `a JSON object is required`; with Gson an array body into `Map` fails to decode and gets `the request body is not valid JSON` | One message for both |
| 3 | Medium | auth.md guards | A 403 from a scope or role typo says only "the principal lacks a required scope or role"; the startup WARN names the typo | Point to the `undeclared scope`/`undeclared role` WARN next to the guards table and in the quick start |
| 4 | Medium | runtime, auth.md:115 | `GET /auth/login` gives a bare "authentication is required" | Name the method when the path is an auth endpoint |
| 5 | Low | runtime | The 403 `insecure_transport` body names no way out; only the WARN does | Point to the server log |
| 6 | Low | runtime | Without an error layer, `auth.user(req)` on a public path logs two ERRORs (Core's and auth's) | Log once, as with `auth.errors()` |
| 7 | Low | auth.md Kotlin blocks at 1141, 1256, 1564, 1869, 1887, 1906, 1995 | Use names declared only in the Java block beside them (`key`, `now`, `row`, `ds`, `botId`, `commonPasswords`, `store`, `directory`, `dataDir`); 1564 lacks `import org.junit.Test` | Say where each name comes from |
| 8 | Low | auth.md:982 | `getBytes("UTF-8")` forces a checked exception | `StandardCharsets.UTF_8` |
| 9 | Low | auth.md test kit | No example of a PROD secret and file key for a test | A `SecureRandom` base64 line |
| 10 | Low | README.md:18–20 | The fallback sentence ends in a colon and repeats the Availability note | Merge |

## Verdict (from the study)

A junior can add the following in one afternoon:
- login
- roles
- groups
- events
- PROD file storage
- a custom store

Every task passed first try with documented code. The quick start, the HTTP test recipe and the examples run as copied.

The remaining friction:
- runtime messages for a wrong method or URL, 403 typos and `insecure_transport`;
- two statements at auth.md:283;
- the length of auth.md.
