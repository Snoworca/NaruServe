# Wave-20 issues (Phase 3.m)

Residual union:
- loop P round 1: 11 tests, 0 kept (pass): byte-identical request heads on every attempt (incl. X-Forwarded-* and fixed request headers), chunked body intact after a refused connection, last 503 relayed unchanged over TinyHttpd, attempt counts for safe and unsafe methods, retry delay and the total-deadline bound, one slot during waits, one connection closed per attempt, no watchdog reservation left, FR-PROXY-020 AC-4 over TinyHttpd. One first-draft timing probe was red because `Thread.sleep(400)` took 495–549 ms on this host; rewritten with wider margins and green (kept:false).
- srs_deltas applied: FR-PROXY-018 AC-12 (zero-byte body definition; an empty chunked body is not retried — host test 24ae849, red under a mutated `bodyless()`), AC-13 (interrupt handling).
- worker: FR-PROXY-020 held at base except the retry cases FR-PROXY-018 supplies (tests only, `intentionally_empty`); review pass after 5 rounds, 0 blocking; kiwi-coder work done by the worker in the foreground because subagents start in the background; the card's run id was lower-cased for the session directory.
- out_of_lease_paths: none.

No issue rows. Ledger `[]`.
