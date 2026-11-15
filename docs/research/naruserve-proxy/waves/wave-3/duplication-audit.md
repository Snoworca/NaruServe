# Duplication audit — wave-3 (stage 1)

Input: same stage-1 ranges (`664ad96`, `38c0c4e`, `84c0698`) plus the loop-P fix commits on the integration branch up to `8140fbe`, each limited to its write set.

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| `JsonBodyReader` body source (`req.bodyStream()` → `req.exchangeInput()`) | wave-2, wave-3 | `NaruServe-api/src/main/java/com/snoworca/naruserve/internal/JsonBodyReader.java` | parallel-evolution |

Notes: wave-3's header enumeration, header-name matching (`DefaultRequest`) and `ProxyTrustSetting` have no counterpart in waves 1–2. The loop-P fixes replaced the matching rule in place; no second matcher remains.
