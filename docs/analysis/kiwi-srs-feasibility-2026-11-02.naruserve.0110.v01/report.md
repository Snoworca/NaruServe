# kiwi-srs-feasibility — 0.11.0, 12 draft re-evaluation

- run-id: 2026-11-02.naruserve.0110.v01 · target 0.11.0 · mode live, --req-filter (12), --auto
- Prior run: 2026-11-01.naruserve.0110.v01 (153 promoted, these 12 kept at draft)

## Result

- Feasibility: high 12 (scores 83–91), medium 0, low 0, blocked 0
- Stability: 12 × draft → evolving (guard ok, 0 skipped/blocked/failed); tags feasibility:medium → high
- Target 0.11.0 now: evolving 165, draft 0; depends_on graph acyclic (518 edges)
- validate_spec: 0 errors · MCP↔Markdown sync: no mismatch
- Verification: iter1 C0 H1 M10 L8 → F01 fixed → iter2 C0 H0 M12 L10 (pass)

## SRS corrections made during the run (to follow 00)

1. FR-FLOW-009 AC-5, SEC-FLOW-012 AC-6: DELETE …/lockout clears the Lockout state and the user-only throttle key; IP and (user, IP) keys stay (00 §6.4 'R34 × 새 기기 admin').
2. FR-IAM-014 statement and AC-11: an AuthRejection or IAE after the password step → 503 SERVICE_UNAVAILABLE + applied + user_update_failed audit (00 R37 14th edition).
3. FR-AUTH-006 statement: A4 exception routing aligned with item 2 (eval F01).
4. Q57 recommendation (415 first) not applied: SEC-APIKEY-008 AC-10 already follows 00 R29 (f).

## Remaining MEDIUM findings (non-blocking; settle during implementation)

- F02 SEC-TOKEN-009: The floor scope is narrower than the literal text of 00 R38. 00 R38 says '모든 실패 응답(503 포함, 429 제외 — 해시를 하지 않는다 …)은 요청 시작부터 max(1.25T, 실제 경과) + 0–50 ms 난수 지터 뒤에 보낸다'. The SEC-TOKEN-009 statement floors only attempts that 
- F03 FR-IAM-013: Two ACs pull against each other. AC-2 requires a SQL-text test that 'recordFailure issues an UPDATE ... SET <count> = <count> + 1 … rather than SELECT-then-UPDATE'. AC-6 (decided 2026-11-02) requires that 'the first fail
- F04 REL-FLOW-001: The fixed counting window was decided only for the JDBC counters (FR-IAM-013 AC-6: 'fixed, not sliding … node clocks are used the same way as the in-memory throttle'). REL-FLOW-001 and IR-FLOW-003 do not state a window r
- F05 FR-FLOW-009: The judgement misses a condition about where lock state lives. The FR-FLOW-009 implementation notes say 'lock state stored with the throttle/device-trust storage defaults (REL-FLOW-001)'. REL-FLOW-001 and FR-IAM-013 defi
- F06 SEC-TOKEN-009: AC-8 requires a real-adapter test where a failed login (JSON body) on TinyHttpd gets a 401 JSON answer after the floor. The NaruServe-tinyhttpd test classpath holds only testFixtures(project(':NaruServe-api')) (build.gra
- F07 IR-TOKEN-001: AC-7 is hard to turn into a deterministic reflection test, for two reasons. (1) The statement's seam is 'authenticate(String username, char[] password, AuthEventContext ctx)', while AC-7 says the single abstract method '
- F08 SEC-TOKEN-009: The SEC-TOKEN-009 statement says 'Every password login attempt shall … run exactly one PBKDF2 computation'. IR-TOKEN-001 makes the password authenticator replaceable through the public Authenticator SPI (AC-1), for examp
- F09 FR-FLOW-009: Parts of the judgement are stale. For FR-FLOW-009 and FR-IAM-014 the text was corrected on 2026-11-02 (blockers_resolved: 'SRS corrected 2026-11-02 to follow 00'). Yet the conditions still read 'Resolve the AC-5 / SEC-FL
- F10 FR-FLOW-006: AC-6 says only that 'PATCH /auth/me carrying a username field does not change the username'. It does not fix the answer (field ignored with 200, or 400 BAD_REQUEST like the non-allowed attribute of AC-3). A test can asse
- F11 FR-IAM-014: evidence_strength is 15/20 based only on 'code_context.json trace_validation FR-IAM-014'. The only trace is an addition site, utils/auth/Auth.java, which does not exist yet. The block's own implementation notes place the
- F20 FR-IAM-014: After the password, the A4 handler now catches store exceptions, AuthUnavailableException, AuthRejection and IllegalArgumentException. The new FR-AUTH-006 catch-all ('Every other exception in the auth routers ... goes th
- F21 FR-IAM-014: The new routing is keyed on 'after the password was applied' (FR-IAM-014) and 'before the password step' (FR-AUTH-006). Neither block covers an A4 request that has no password. In A4 {username: a free name, roles}, the r

## Next

- All 165 0.11.0 requirements are evolving with no dependency cycles: kiwi-orchestrator can schedule waves.
- Conditions: sqlite-jdbc testImplementation only; no JDBC 4.1 / newKeySet / java.time / java.util.Base64; hand-written PBKDF2; Core C4/C6a/C6b first; Map-decoding test JsonCodec; SEC-AUTH-008 AC-1 and SEC-AUTH-007 AC-7 in the last wave.
