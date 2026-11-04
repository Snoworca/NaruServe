# kiwi-srs-feasibility - 0.11.0

| Field | Value |
|---|---|
| run-id | 2026-11-01.naruserve.0110.v01 |
| target | 0.11.0 |
| mode | live, Normal |
| policy | default mapping (no policy file) |

## Feasibility

- Evaluated: 165 (all planned/draft)
- high 153, medium 12, low 0, blocked 0
- Target verdict: conditionally-ready. No blockers; the conditions below apply, and the depends_on cycles must be cut before wave scheduling.

## Stability changes

- draft -> evolving: 153
- kept draft (medium): 12
- guard blocked 0, user rejected 0, system failed 0; tag fixes 19/19; validate_spec 0 errors; MCP-Markdown sync: no mismatch

## Kept at draft (medium)

| REQ | Score | Main condition |
|---|---|---|
| SEC-APIKEY-008 | 79 | Cut one edge of the FR-FLOW-006 <-> SEC-APIKEY-008 2-cycle before wave scheduling |
| SEC-AUTH-003 | 79 | Choose and document the setup rejection status/code (Q15, Q23) under the IR-AUTH-003 AC-3 rule |
| SEC-AUTH-007 | 79 | Schedule AC-7 after FLOW logout/webSocket and APIKEY ver checks |
| SEC-AUTH-008 | 75 | Implement after login/refresh/logout/API key/setup/doctor exist; run as the final cross-scope gate |
| FR-FLOW-006 | 78 | Settle the PATCH /auth/me body shape and the non-allowed-attribute status/code (Q49, Q51, Q53) and record them in docs/api |
| SEC-FLOW-013 | 79 | Fix the DeviceTrust SPI method signatures before release (R20: later changes only by default methods) |
| FR-FLOW-009 | 77 | Choose lockout refusal status/code under IR-AUTH-003 AC-3 and the builder shape (Q120), then update docs/api |
| REL-FLOW-001 | 77 | Add test-only sqlite-jdbc as testImplementation in the project(':NaruServe-api') block (build.gradle:273-280), not a main dependency |
| FR-IAM-013 | 79 | Add org.xerial sqlite-jdbc as NaruServe-api testImplementation only (CON-IAM-002), keeping build.gradle:59-60 mainDependencies unchanged |
| FR-IAM-014 | 76 | Cut at least one depends_on edge of the 70-member SCC before wave scheduling (ordering only, not implementability) |
| SEC-TOKEN-009 | 79 | Confirm with an adapter-level test (TinyHttpd and Servlet) that completing the async login response from the scheduler thread is safe (00 §1 |
| IR-TOKEN-001 | 76 | Cut the IR-TOKEN-001 ⇄ SEC-FLOW-004 depends_on 2-cycle before wave scheduling |

## Dependency cycles

- SCC sizes [70, 2] (72 requirements); shortest-cycle histogram {'2': 4, '3': 5, '4': 2, '5': 1, '6': 3, '7': 6, '8': 8, '9': 6, '10': 2, '11': 2, '12': 3, '13': 1, '14': 1}
- Not an implementability blocker, but orchestrate schedule waves refuses cycles (schedule-cycle). Cut or relabel edges first.

## Recurring implementation conditions

- add org.xerial sqlite-jdbc as testImplementation only (needs network on first build)
- no JDBC 4.1 methods (setNetworkTimeout/abort/closeOnCompletion) and no ConcurrentHashMap.newKeySet (API 21)
- hand-written PBKDF2 over Mac(HmacSHA256); no java.util.Base64/java.time
- C6a/C6b/C4 Core changes land before FLOW/IAM consumers
- test JsonCodec that decodes into Map (StubJsonCodec throws on decode)
- confirm with a real animalsnifferMain run

## Verification

- eval_iter1: CRITICAL 0, HIGH 0, MEDIUM 5, LOW 6 - passes the Normal gate. F1 (cycle counts) and F4 (19 contradicting tags) fixed; F3 (REL-FLOW-001 could re-score high) left conservative.

## Next steps

- The 153 evolving REQs can enter implementation (kiwi-orchestrator / kiwi-pm).
- The 12 draft REQs: settle their open questions in the SRS, then re-run feasibility with --req-filter.
- Cut the depends_on cycles before wave decomposition.
