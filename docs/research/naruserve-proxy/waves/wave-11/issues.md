# Wave-11 issues (Phase 3.m)

Residual union:
- loop P round 1: 0 kept. Two red probes not kept, both answered by TinyHttpd before the proxy runs (a recording middleware in front of the proxy never saw them), so no backend connection is opened either way:
  - LP-8: `Transfer-Encoding: chunked, chunked` and two `chunked` lines get 501 from TinyHttpd 3.1.1 instead of the 400 of SEC-PROXY-003 AC-3; the proxy's own check answers 400 through the in-memory adapter. The requirement's implementation note sends adapter-first refusals to the in-memory adapter; the 501-vs-400 status belongs to the TinyHttpd adapter (FR-ENGINE-003 scope). Recorded for the run report.
  - LP-11: on HTTP/1.0 TinyHttpd answers `gzip, chunked` with 400 before dispatch, while HTTP/1.1 reaches the proxy and gets 501. PRD-68 leaves HTTP/1.0 handling to the adapter; AC-7 (proxy decides only from reported headers) holds through the in-memory adapter. Recorded for the run report.
- worker review: TASK_DONE after 2 rounds, 0 kept; the round-1 finding (an https mount answered 502 before the framing checks) was fixed tests-first in the lane. Declared limits owned by later waves: chunked wire form (FR-PROXY-007, wave-12), early 413 for list-form Content-Length (FR-PROXY-008, wave-12), response parser reuse of `FramingLists` (SEC-PROXY-004, wave-17).
- out_of_lease_paths: none.

No issue rows. Ledger `[]`.
