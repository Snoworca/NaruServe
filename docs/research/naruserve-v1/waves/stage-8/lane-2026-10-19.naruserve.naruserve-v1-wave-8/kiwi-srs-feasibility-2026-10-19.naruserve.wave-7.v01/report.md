# kiwi-srs-feasibility wave-7 (run 2026-10-19.naruserve.wave-7.v01)

- Target: wave-7, mode live, --auto, policy: default mapping (no policy file)
- Evaluated: 29 requirements (23 new WS/SSE/APP/HTTP + successors FR-APP-019, OPS-APP-005, FR-ENGINE-011, OPS-ENGINE-002, IR-HTTP-007, OPS-HTTP-007)
- First pass: high 27, medium 2 (FR-SSE-002 75, FR-SSE-003 79; blocker B-1: no SPI stream for handler-thread SSE writes before the drain starts)
- B-1 resolved by committee decision (2 of 2 votes, confidence 0.77): add-only `default void attach(OutputStream)` on `StreamDrain`; recorded as a deviation from W2-001 "one default method"; improvement pass applied (improvement_iter1.json) plus depends_on FR-SSE-002/003 -> IR-HTTP-007 and statement fix
- Rescore: FR-SSE-002 84, FR-SSE-003 87 -> high 29 (scores 84-95), verdict release-ready for design (kiwi-sds)
- Stability: draft -> evolving 29 applied, 0 skipped, 0 guard-blocked; MCP/Markdown sync 29/29 PASS
- Evaluator (eval_iter1.json): 0 CRITICAL, 0 HIGH, 3 MEDIUM (F-01..F-03 applied after eval), 8 LOW
- validate_spec: 0 errors, 21 SRS-W015 (completed-work-log rows of discarded requirements)

## Conditions
- Verification groups (AC text couples them): IR-WS-002 + IR-HTTP-007; FR-WS-010 + FR-APP-020; FR-APP-019 with the producers of its log lines; SSE API + serialisation + adapter.
- In-memory adapter starts the drain at once: the switch from attached-stream writes to queued writes must be atomic (SDS).
- Thread-count tests run in an isolated JVM fork (tinyhttpd-watchdog has no port in its name).
- TinyHttpd checkout HEAD 38051b6 vs cited 46b973e (javadoc and overflow-safe arithmetic only).
