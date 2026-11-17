# Duplication audit — wave-12 (stage 7)

Input: `09770ae..5736592` (one lane, wave-12). New production code in `NaruServe-proxy`: `BackendRequest` (request line and forwarded header block), `RequestBodyCopier` (length and chunked body copy to the backend), `HeaderRules` additions, `Proxy` rewiring.

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|

No candidates found. `RequestBodyCopier` writes chunked framing to the backend; the only other chunked code in the module is the backend response chunk decoder in `Proxy` (wave-10), which reads rather than writes, so the two share no logic. Header checks reuse `HeaderRules` / `common.HttpHeaderValues`, and framing reuses wave-11 `FramingLists`. The API module's chunked writers live behind the adapters (`internal`, TinyHttpd), which the proxy may not use (CON-BUILD-021).
