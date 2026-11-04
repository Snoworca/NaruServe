# Draft 12 — open questions and recommended answers (2026-11-02)

Source: per-req-judgement.json (medium reasons), the requirement blocks ("Open" notes), and
`docs/analysis/kiwi-srs-2026-10-30.naruserve.auth-rbac-v1/open_questions.md` (Q-numbers).
Recommendations follow 00 first; where 00 is silent, the choice that is most natural for Express users (CLAUDE.md).

## A. No open value — draft only because they turn green late (promote with a scheduling condition)

| REQ | Why draft | Recommended handling |
|---|---|---|
| SEC-AUTH-007 principal cache | AC-7 spans logout, refresh, device, API key, webSocket | evolving; AC-7 is checked in the last wave |
| SEC-AUTH-008 sensitive-value hygiene | AC-1 needs almost every flow | evolving; the cross-scope gate test runs in the last wave |
| REL-FLOW-001 counter storage | API 21 signature, Android detection, JDBC test driver | evolving; java.sql and FileDescriptor.sync are already confirmed in the API 21 signature; Android detection is injectable through a system property; sqlite-jdbc is testImplementation |

## B. Open values that need a decision

| REQ | Q | Question | Recommendation |
|---|---|---|---|
| SEC-AUTH-003 setup | Q15 | Status and code for a wrong or missing setup token | 401 `invalid_setup_token` |
| | Q23 | Status and code for a non-local setup request | 403 `setup_not_local` |
| SEC-TOKEN-009 constant work | Q39 | Is the in-handler request-shape 400 (empty password) subject to the 1.25T floor? | No. It is not an attempt: no hash ran and the request never reached step ④ |
| | Q45 | T under HashCost.fixed(n) | Measure one hash at fixed(n) at startup and use that as T |
| | 00 §12 | Is it safe to complete the response from the scheduler thread? | Confirm with an adapter test (TinyHttpd and Servlet) |
| IR-TOKEN-001 Authenticator | Q35 | Method shape of the Bearer and API key authenticators | Keep both as internal implementations. The public SPI is only the password seam of 05 §3.9 |
| FR-FLOW-006 /auth/me | Q49/Q53 | Response to an attribute that is not allowed | 400 `BAD_REQUEST` (00 R30 wins over 05's 422) |
| | Q51 | PATCH body shape | `{"attributes":{...}}` (05 §4.1) |
| FR-FLOW-009 Lockout | Q54/Q55/Q120 | Response to a login on a locked account | Same 401 as a wrong password (prevents account enumeration; matches IR-TOKEN-001's no-distinction rule) |
| | Q120 | Builder shape | `.lockout(Lockout.after(5).forMinutes(15))` (07) |
| | Q120 | Success of `DELETE …/lockout`, and does it clear the throttle keys? | 204. **Corrected:** clears the lock and the user-only key; the IP and (user, IP) keys stay (00 §6.4 'R34 × 새 기기 admin') |
| SEC-FLOW-013 DeviceTrust | Q60 | SPI method set and builder option name | Fixed during implementation and recorded in docs/api; later changes are default methods only (R20) |
| | Q118 | naru_device cookie attributes | HttpOnly, Secure (except local DEV), SameSite=Strict, Path=`<mount>/login`, Max-Age = device trust lifetime |
| FR-IAM-013 JDBC counters | — | Window semantics | Fixed window: reset the count when `window_start` is past the window |
| | Q60 | DeviceTrust SPI | Follows SEC-FLOW-013 |
| FR-IAM-014 admin A4 | Q74 | Placement of `applied` | Top-level `"applied": ["username","password","enabled","roles","attributes"]` (subset applied) |
| | Q14 | 409 code for a lost password race | `write_conflict` (one 409 code per cause: `duplicate_user`, `write_conflict`, `group_cycle`, `group_too_large`) |
| | Q75 | Audit type for a store failure after the password step | `user_update_failed` |
| | Q76 | AuthRejection or IAE after the password step | **Corrected:** 503 SERVICE_UNAVAILABLE + `applied` + `user_update_failed` audit (00 R37 edition 14) |
| SEC-APIKEY-008 key actor ban | Q57 | Non-JSON A2/A4 body from a key that fails R39 | **Not applied:** AC-10 already follows 00 R29 (f) (403 `insufficient_scope` first) |
| | Q90 | JSON body limit | Use Core's configured JSON limit (no separate value) |
| | Q89 | Implementation notes cannot be edited (MCP tool gap) | No decision needed; AC-11 owns the rule |
