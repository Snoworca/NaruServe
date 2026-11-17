# Duplication audit — wave-6 (stage 3)

Input: same stage-3 ranges as wave-5's audit.

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| argument range checks (see wave-5 audit) | wave-5, wave-6 | `NaruServe-api/src/main/java/com/snoworca/naruserve/internal/DefaultRequest.java`, `NaruServe-api/src/main/java/com/snoworca/naruserve/internal/DefaultResponse.java` | acceptable |

Notes: the relay flag lives in `DefaultResponse` only; `ResponseRelayTest`, `ResponseRelayFramingTest` and `RawResponseSocketTest` share no helper with wave-5's body-stream tests. The host made no code commit after the merges (replay and index sync touch `docs/spec` only): host review hop `no-host-code-commits`.
