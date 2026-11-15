# Duplication audit — wave-2 (stage 1)

Input: same stage-1 ranges as wave-1's audit (`664ad96`, `38c0c4e`, `84c0698`), each limited to its lane write set.

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| `BodyTooLargeException` (public `com.snoworca.naruserve` vs former `internal`) | wave-2 | `NaruServe-api/src/main/java/com/snoworca/naruserve/BodyTooLargeException.java`, `NaruServe-api/src/main/java/com/snoworca/naruserve/internal/BodyTooLargeException.java` | acceptable |
| `LimitedInputStream` use in `JsonBodyReader` (wave-2 limit stream, wave-3 switches its source to `req.exchangeInput()`) | wave-2, wave-3 | `NaruServe-api/src/main/java/com/snoworca/naruserve/internal/LimitedInputStream.java`, `NaruServe-api/src/main/java/com/snoworca/naruserve/internal/JsonBodyReader.java` | parallel-evolution |

Notes: the internal class was deleted in the same merge (moved to the public package, FR-HTTP-033 / IR-HTTP-012), so only one class exists. Wave-3 reuses wave-2's `LimitedInputStream` rather than adding a second limiter.
