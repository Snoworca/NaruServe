# Wave-4 issues (Phase 3.m)

Residual union:
- loop P round 1 residual LP-10 (cites C-005: `docs/api/response.md` and `docs/api/en/response.md` do not document `Response.append`, `onAbort`, `relay`). Owned by OPS-DX-009 in wave-8 (not yet dispatched); carried forward per `verify-loop.md` §8 (`carried_into: wave-8`, `cross-wave-carry-forward`), recorded in `waves/carry-forward.json`. Not an issue row.
- worker review (after the independent remediation, option A): FND-301 declared limit — an exchange abort after the adapter wrote 101 but before `onOpen` logs one `GET /ws 200 … aborted` line (the core cannot see the adapter-side 101 before `onOpen`; no AC breached). FND-302 was rejected by the review as pre-existing since `730b55b` (an `Error` from a WebSocket handler in an open session writes a second `GET /ws 200 aborted` line); it is not a residual of this wave and is listed in the run report as a follow-up for FR-APP-019.
- worker srs_deltas: FR-HTTP-035 AC-2 race (an abort racing `ex.finish()` counts as abnormal; documented in the `Response.onAbort` Javadoc), listener wrapper catching `Throwable`, IR-HTTP-014 docs owned by OPS-DX-009. None changes a criterion; recorded in the run report.
- out_of_lease_paths: `Dispatcher.java`, `WebSocketUpgrade.java`, `DefaultWebSocketSession.java` — accepted at verify-lane (legacy WebSocket-upgrade path of FR-HTTP-035; no other lane in stage 2).

No issue rows. Ledger `[]`. `speckiwi orchestrate wave close --wave 4` → ok, no violations.
