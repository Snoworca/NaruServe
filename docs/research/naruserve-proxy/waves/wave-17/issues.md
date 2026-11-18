# Wave-17 issues (Phase 3.m)

Residual union:
- loop P round 1: 18 tests, 0 kept (pass): interim 1xx sequences and the 101 rule, 64 KiB bound across interim heads, bare CR, split codings, HTTP/1.0 empty Transfer-Encoding, chunk data without CRLF, trailers kept away from the client, keep-open chunked backend, bytes past Content-Length cut, HEAD after 100, mid-response failure over TinyHttpd (no terminating chunk, no borrowed bytes, logged once even with a throwing logger, READ_TIMEOUT in a chunk-size line, slot released).
- carried LP-19 (wave-10, obs-text header bytes over TinyHttpd) closed: SEC-PROXY-004 AC-9 now states TinyHttpd 3.1.1's UTF-8 header writing as an adapter-dependent limit (TinyHttpd is not modified in this run); the in-memory adapter test is green.
- srs_deltas applied: SEC-PROXY-004 AC-9 (above), AC-2 (how the 64 KiB bound is counted), AC-12 added (Transfer-Encoding on HTTP/1.0 responses, review FND-001) with evidence.
- declared limits owned by wave-18: TOTAL_TIMEOUT for a watchdog close during the body (FR-PROXY-016), READ_TIMEOUT while reading the head (SEC-PROXY-004 reports 502).
- worker: lane commits carry the REQ and Orch lines in the message body but git does not parse them as trailers (blank line before Co-Authored-By); history not rewritten, recorded for the run report. Review TASK_DONE, 0 kept; FND-201 (refactor reordering) handled as a clean-code revert.
- duplication: the wave-11 deferral (response-side CL/TE parsing vs `FramingLists`) is closed — `ResponseParser` uses `FramingLists`.
- out_of_lease_paths accepted: `ProxyExchangeTest.java` (one frame-name line after the parser class moved).
- host incident: while copying the review report an empty path variable turned `cp -R` into a copy of `/` into the lane dir and filled the disk; the copy was deleted, nothing in the repository or the journal was damaged, and the merge ran afterwards.

No issue rows. Ledger `[]`.
