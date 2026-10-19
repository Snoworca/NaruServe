# Wave 3 duplication audit (stage 3)

Stage 3 merged one lane. Wave 3 adds the public HTTP types, the SPI, the internal app, request, response and dispatcher, and the in-memory adapter. One block repeats wave 2 code.

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| strict UTF-8 percent-decoding loop (`decode`, `hex`, UTF-8 REPORT decoder) | wave-2 lane, wave-3 lane | NaruServe-api/src/main/java/com/snoworca/naruserve/routing/PathValidator.java `decode`; NaruServe-api/src/main/java/com/snoworca/naruserve/internal/DefaultRequest.java `decode` | duplicate — resolution issue:w3-D-001 (one shared decoder; the path form keeps '+', the query form maps '+' to space; malformed input stays null for the validator and an IllegalArgumentException for the request) |
