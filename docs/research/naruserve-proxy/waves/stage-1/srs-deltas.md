# Stage 1 SRS deltas — 2026-11-14.NaruServe.naruserve-proxy

Collected from the three lane manifests (PW-1). Applied by the host through MCP; each keeps the requirement's existing PRD basis.

| # | From | Requirement | Kind | Change | Evidence |
|---|---|---|---|---|---|
| 1 | wave-1 | FR-REL-006 AC-5 | ac-change (wording) | name the SBOM component as the plugin writes it: `com.snoworca.naruserve:NaruServe-api` | `./gradlew -PtinyHttpdFromMaven=true -Prelease releaseSbom --offline` wrote `NaruServe-proxy.cdx.json` with the single component `com.snoworca.naruserve:NaruServe-api:0.11.0` |
| 2 | wave-2 | FR-APP-027 | implementation note | FinalHandler, Auth.prodAnswer and Auth.routerError also map an unwrapped overrun to 413 | red tests `ErrorPathTest.FR_APP_027_AC1_theCommonHttpFinalAnswerGivesAnEscapedOverrun413AndNoErrorLog`, `JuniorFinalItemsTest.FR_APP_027_AC1_AC3_*` |
| 3 | wave-3 | FR-HTTP-034 | stale implementation note | helper is `ProxyTrustSetting.trustsPeer(String)`; the private `peerTrusted` and the old line ranges are gone | merged code at `84c0698` |
| 4 | wave-2 | FR-ENGINE-013 | implementation note | carry design D-023 (TinyHttpd adapter names TinyHttpd's `BodyTooLargeException` by fully qualified name) to the TinyHttpd request-side wave | `TinyHttpdBodyStream.java` still uses the single-type import (compiles, resolves) |

Not SRS changes (recorded only): FR-REL-006 AC-4 and FR-REL-007 AC-1/AC-3 need recorded release runs, which belong to the release step (design D-009); docs/api entries belong to OPS-DX-008/009; FR-HTTP-034 AC-1 could not be red before implementation because the interim stub already returned false (AC-2..AC-5 were red first); the coder-queued `update_status implemented` rows were replayed and agree with the landed state.
