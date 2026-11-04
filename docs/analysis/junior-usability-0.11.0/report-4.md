# Junior usability re-study: auth/RBAC module (0.11.0), round 4

Date: 2026-11-04. Branch `kiwi/orch/2026-10-28.naru.express5-v010/integration`, working tree uncommitted, after OPS-AUTH-010, OPS-AUTH-011 and OPS-REL-010 (build 3458/0). The study was run by an independent agent that was not given the earlier reports or the list of fixes. Persona and method are those of [report.md](report.md); this round also read middleware.md and logging.md, ran 88 mistake variants and compiled all 102 auth.md fragments (51 Java, 51 Kotlin).

Cleanup check: `git status --porcelain | grep tmpjunior` prints nothing.

## Compared with rounds 1 to 3

| | Round 1 | Round 2 | Round 3 | Round 4 |
|---|---|---|---|---|
| Tasks 1–6 and K | 2 blocked or needed a doc fix | All done; 3 needed a fix or a wait | All first try | **All first try** |
| Mistake variants | 13 | about 40 | 34 | 88 |
| Messages that name the fix | 9 of 13 | about 30 of 40 | about 30 of 34 | most; the rest are documented 401/404 answers |
| Time to first login | 1 h+ or blocked | about 30 min | 30–60 min | about 17 min (3 min of coding) |
| High items | 1 | 1 | 2 | **0** |

Round-3 items re-observed as fixed:
- the 0.11.0 coordinates and the local-build version
- the PROD file-store snippets
- the JWT_SECRET note
- `checkAuth` in middleware.md
- the defaults link
- the logout note
- the permit-method WARN (its suggested fix gave 200)
- the register-404 note
- the group, audit and token examples
- the PATCH note
- case-sensitive roles

## Findings (main session spot-checked rank 1 and rank 3 in the files)

| Sev | Where | Problem | Suggested fix |
|---|---|---|---|
| Medium | auth.md HTTP test recipe `call()` (en :1634, Kotlin twin) | Always `setDoOutput(true)` and writes a body, so `HttpURLConnection` turns a GET into POST; reusing it for a GET route gave 404 `Cannot POST /me` | Write a body only when `json != null`; GET passes `null` |
| Medium | doctor `errors placement`; Core final handler | Without `app.onError(auth.errors())` a PROD client gets an HTML page with the Java stack trace (422 / 500); only a WARN | FAIL in PROD, or no stack trace in PROD |
| Low | logging.md:95, en/README.md:80 | `Logger` shown without its package and listed under "SPI"; the junior guessed `spi.Logger` | Give `import com.snoworca.naruserve.Logger;` and list it as public API |
| Low | refresh endpoint (auth.md account table) | A body without `refreshToken` (`{"token":…}`) gets "the refresh token is not valid" | Say to send `{"refreshToken": …}` when the field is missing |
| Low | auth.md "File defaults" | Says auth "keeps" the throttle and device-trust files; they appear only at the first snapshot (60 s / stop) and the first device-trust write | Say when each file is written |
| Low | auth.md:115, :283 | `POST /login` is a plain 401 and a disabled `/auth/register` an HTML 404 with no hint (both documented) | A hint naming the route or `.register(true)` |
| Low | README.md:17–18, :80, :155 | Availability is conditional; the module table does not say auth is in `naruserve-api` | One plain status line; name `utils.auth` in the `naruserve-api` row |
| Low | auth endpoints | Invalid JSON gets 400 "Bad Request" with no detail | Say the body is not valid JSON |

Kotlin fragments that use names defined elsewhere (11 blocks: `auth`, `key`, `row`, …) compile once the names are filled in; the page says fragments assume the surrounding objects, so this is not listed as a defect.

## Verdict (from the study)

A junior who knows Express can add login, roles, groups, events, PROD file persistence and a custom store in an afternoon. 51 of 51 Java fragments compile with IDE imports; the error messages name the exact fix almost everywhere; both `runAuth` examples behave as documented. The fixes worth making are the GET-to-POST trap of the test recipe and the stack-trace page in PROD when the `onError` line is forgotten.
