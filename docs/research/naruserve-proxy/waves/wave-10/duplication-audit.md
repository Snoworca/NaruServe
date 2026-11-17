# Duplication audit — wave-10 (stage 5)

Input: `aa90b96..df1a27c` (one lane, wave-10). New code lives only in `NaruServe-proxy` (`Proxy`, `HeaderRules`, `ResponseHeaderCopier`) plus its tests.

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| `HeaderRules.trimOws` | wave-10 | `NaruServe-proxy/.../HeaderRules.java` | keep — 10-line OWS trim; `NaruServe-api` exposes no public OWS trim (`common.HttpHeaderValues` has `isToken`, `isFieldValue`, `commaSeparated` only), and the proxy may depend on the api's public surface only (CON-BUILD-021) |

Header name and value checks reuse `common.HttpHeaderValues.isToken` / `isFieldValue`, the same rule `DefaultResponse` and `Cookie` use, so no rule is copied. The managed and hop-by-hop name sets are proxy-specific (FR-PROXY-002, FR-PROXY-011) and have no counterpart in the api. Test helper `RawBackend` is local to the proxy test source set.
