# Duplication audit — wave-4 (stage 2)

Input: wave-4's commit range on the integration branch (`730b55b..1b6fb41`, merged at `026f639`); stage 2 has no other lane. Compared against the symbols the earlier waves added (`Request` raw access, `BodyTooLargeException`, `LimitedInputStream`, build wiring).

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|

No candidates found. `Response.append/onAbort/relay`, the `DefaultResponse` listener list and the legacy-path abort gate (`Dispatcher`, `WebSocketUpgrade`, `DefaultWebSocketSession`) have no counterpart elsewhere. The host made no code commit after the merge (replay and index sync only touch `docs/spec`), so the stage's host review hop is `no-host-code-commits`.
