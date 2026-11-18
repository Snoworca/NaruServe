# Duplication audit — wave-17 (stage 11)

Input: `7b20950..3a6f8e0` (one lane, wave-17). New production code in `NaruServe-proxy`: `ResponseParser` (SEC-PROXY-004 backend response head, interim 1xx, framing), `ChunkedBodyInputStream` (chunked response body decoder, trailers), `RelayedBodyStream` mid-response failure mapping (FR-PROXY-016), bodyless HEAD/204/304 handling (FR-PROXY-014).

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| response-side Content-Length / Transfer-Encoding parsing vs `FramingLists` (open since the wave-11 audit) | wave-17 vs wave-11 | `ResponseParser.java:108-152`, `FramingLists.java`, `Proxy.java:701-729` | merged: `ResponseParser` now parses both lists through `FramingLists.fieldValues/contentLength/transferEncoding`, and the old `Proxy.ResponseHead` parser is gone; the wave-11 deferral is closed |
| `ChunkedBodyInputStream` (decoder) vs `RequestBodyCopier.copyChunked` (encoder, wave-12) | wave-17 vs wave-12 | `ChunkedBodyInputStream.java`, `RequestBodyCopier.java` | keep: one reads chunked framing from the backend, the other writes it to the backend; they share no logic beyond the hex size format |
| header name/value checks in `ResponseParser` vs `HeaderRules` / `common.HttpHeaderValues` | wave-17 vs wave-10 | `ResponseParser.java`, `HeaderRules.java` | merged: the parser calls `HttpHeaderValues.isToken/isFieldValue` (IR-HTTP-012) as the request side does |

No merge candidates left.
