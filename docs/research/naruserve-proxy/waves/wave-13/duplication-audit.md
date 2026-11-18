# Duplication audit — wave-13 (stage 8)

Input: `4549558..1c94acb` (one lane, wave-13). New production code in `NaruServe-proxy`: `PathChecker` (raw path and request-target checks, SEC-PROXY-001), `TargetUrl` (hand parser of the `Proxy.to` URL, FR-PROXY-001), `BackendRequest` Host and X-Forwarded-Host additions (FR-PROXY-004), `Proxy` request-target assembly (FR-PROXY-006).

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|
| `PathChecker` vs `routing.PathValidator.isValid` | wave-13 vs core | `NaruServe-proxy/.../PathChecker.java`, `NaruServe-api/.../routing/PathValidator.java` | keep: the proxy rules are a strict superset (encoded unreserved, `;`, `%5c`, raw backslash, empty segments, double encoding, query bytes); calling `isValid` first would add a second decode pass without removing any proxy check |
| `TargetUrl.isIpv4/isIpv6` vs `internal.ProxyTrustSetting` address parsing | wave-13 vs core | `NaruServe-proxy/.../TargetUrl.java`, `NaruServe-api/.../internal/ProxyTrustSetting.java` | keep: the core parser is in `internal` (not public API, CON-BUILD-021) and parses CIDR bytes rather than validating a URL host grammar |

No merge candidates. Both overlaps are justified by visibility or by different rules.
