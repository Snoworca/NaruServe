# kiwi-pm FR-HTTP-030 (lane worker, --no-final)

- status: done, attempts 1 (one interim NEEDS_USER after a stalled background review, resumed)
- commits: test 0faa171, implementation 5e686f6
- regression: 3969 tests, 0 failures
- review: kept 1 (fixed test-first), dropped 8
- deferred mutations appended: 27; T-final skipped (--no-final)
- behaviour note: handler close() of the bodyStream no longer closes the adapter input immediately; it closes once in request cleanup (AC-10)
