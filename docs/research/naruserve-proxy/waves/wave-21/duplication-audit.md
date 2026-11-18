# Duplication audit — wave-21 (stage 15)

Input: `9150c04..9ca1df5` (one lane, wave-21, documentation). Changes: `docs/api/request.md`/`en/request.md` (raw request API), new `docs/api/proxy.md`/`en/proxy.md`, README ko/en, mkdocs nav, docs index pages, doc tests (`ReadmeDocsTest`, `GuideConsistencyDocsTest`, `DocsExamplesCompileTest`, `KotlinDocsMarkersTest`, `RawRequestDocsKotlinTest.kt`, `ProxyDocsKotlinTest.kt`), root `build.gradle` test dependency on `:NaruServe-proxy`.

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| proxy page facts (defaults, kinds, statuses) vs the PROXY requirements | wave-21 vs waves 10–20 | `docs/api/proxy.md`, `docs/spec/22.reverse-proxy.srs.md` | keep: the page restates the requirement values for users; `ProxyDocsKotlinTest` pins them against the code accessors, so drift fails a test |
| default error page note in `docs/api/defaults.md` (wave-18 host fix) vs the proxy page error section | wave-21 vs wave-18 | `docs/api/defaults.md`, `docs/api/proxy.md` | keep: the defaults page states the core rule, the proxy page links the user-facing consequence |

No code duplication (documentation and tests only).
