# Duplication audit — wave-5 (stage 3)

Input: the stage-3 ranges on the integration branch — wave-5 `7d1bdff..d5c6591` (merge `da225ec`) and wave-6 `0e2088b..ca0fdf4` (merge `6d26b53`), each limited to its lane write set.

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| argument range checks (`bodyStream(long)` / `rawBodyLimit` 1..1073741824 vs `res.relay` length ≥ -1) | wave-5, wave-6 | `NaruServe-api/src/main/java/com/snoworca/naruserve/internal/DefaultRequest.java`, `NaruServe-api/src/main/java/com/snoworca/naruserve/internal/DefaultResponse.java` | acceptable |

Notes: the two checks validate different ranges with different meanings (a byte limit vs a declared length where -1 means unknown). Wave-5's own review moved its two 1..1073741824 checks into one helper that reuses `JsonBodyReader.MAX_LIMIT` (d5c6591), so no second limit constant exists. `LimitedInputStream` from wave-2 is reused, not copied.
