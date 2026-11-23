# Code review brief — NaruServe 0.12.0 proxy run (severity loop)

Repository: /Users/beom/work/git/NaruServe, branch kiwi/orch/2026-11-14.NaruServe.naruserve-proxy/integration.
Java 8 bytecode, Android API 21 (animal-sniffer). Build: `./gradlew -PtinyHttpdFromMaven=true build`.

## Scope
Product code changed in `git diff 853c7734784d13ae75f208bf16dd81e4fa1dbba4..<head>`:
- `NaruServe-proxy/src/main/**` (reverse proxy module, ~5000 lines) — main focus;
- `NaruServe-api/src/main/**` and `NaruServe-tinyhttpd/src/main/**` changes (raw access API: bodyStream, rawBodyLimit, res.relay, res.onAbort, res.append, header enumeration, BodyTooLargeException ...);
- tests only where a test claims to check something it does not (weak/vacuous assertion), or a flaky construction you can show.
Requirements: `docs/spec/22.reverse-proxy.srs.md` and others (use `speckiwi search <words>` / `speckiwi show <ID> --json`). API docs: `docs/api/proxy.md`, `docs/api/en/proxy.md`.

## Already decided (do not report)
- JVM limitation: an unneeded MD5-signed certificate in the chain gives 502 with trustCertificates (X509ExtendedTrustManager is API 24). Documented.
- trustCertificates disables TLS session resumption on purpose (full handshake per connection).
- Core must not use java.nio.file, CompletableFuture, java.util.Base64 (API 26) or any API above 21.
- TinyHttpd (../TinyHttpd) is read-only; its own behaviour is out of scope.
- Half-duplex upload: the proxy sends the whole request body before reading the response (FR-PROXY-015 AC-10, declared and documented); a streaming-echo backend with a large body ends in 504 WRITE_TIMEOUT.

## Severity rubric
- CRITICAL: security hole (request smuggling, header injection, TLS trust bypass, path traversal), data corruption, or crash/hang reachable by a remote client.
- HIGH: wrong behaviour against a requirement AC or the API docs in a normal use, resource leak (socket/thread/slot) on a reachable path, race that can be shown.
- MEDIUM: wrong behaviour on an edge path, misleading error/status, docs-vs-code mismatch, missing input validation on a public API, concurrency hazard without a shown failure but with a concrete interleaving.
- LOW: maintainability defect with a concrete cost (dead code, duplicated logic that already diverged or can diverge, misleading name/comment, unused field), minor inefficiency on a hot path.
Do NOT report pure style preferences, formatting, or "could be split into smaller classes" opinions.

## Evidence
- For CRITICAL/HIGH/MEDIUM behaviour claims, prove it: write a probe test in a scratch worktree you create (`git -C /Users/beom/work/git/NaruServe worktree add <scratchpad>/cr-r<r> <head>` detached), run only that test class in Maven mode, and include the failing output. A claim you could not show red is at most LOW, or drop it.
- For LOW, give the exact file:line and why it costs something.
- Remove your scratch worktree afterwards (`git -C /Users/beom/work/git/NaruServe worktree remove --force <path>`). Never write, stage or commit in /Users/beom/work/git/NaruServe itself, except your output JSON.

## Output
Write the JSON named in your task:
{"round": r, "head": "<sha>", "findings": [{"id": "CR<r>-<n>", "severity": "CRITICAL|HIGH|MEDIUM|LOW", "title": "...", "file": "...", "line": N, "requirement": "<REQ-ID AC-n or ->", "description": "...", "evidence": "probe test + failing output, or exact code", "fix_hint": "..."}],
 "counts": {"CRITICAL": 0, "HIGH": 0, "MEDIUM": 0, "LOW": 0}, "areas_examined": ["..."]}
Report back in under 200 words: counts and one line per finding.
