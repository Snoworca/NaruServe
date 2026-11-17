# Duplication audit — wave-11 (stage 6)

Input: `02e627b..6b08a73` (one lane, wave-11). New code: `NaruServe-proxy` `FramingLists` (package-private) and the request framing checks in `Proxy`.

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| `FramingLists.contentLength` / `transferEncoding` vs the backend response head reader `Proxy.ResponseHead` (Content-Length `Long.parseLong`, Transfer-Encoding split at `Proxy.java` ~866–900) | wave-11 vs wave-10 | `NaruServe-proxy/.../FramingLists.java`, `NaruServe-proxy/.../Proxy.java` | deferred — the same list parsing applies to backend responses (design D-054), and the response framing rules and parser belong to SEC-PROXY-004 (wave-17, expected file `FramingLists.java` in its write set). Wave-17 replaces the response-side parsing with `FramingLists`; recorded as the worker's declared limit "response parser reuse" |

`FramingLists` reuses `HeaderRules.trimOws`; no other copy of the OWS trim or token rule was added. Test classes `FramingListsTest` and `ProxyRequestFramingTest` reuse the wave-10 `RawBackend` helper.
