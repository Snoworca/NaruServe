# 0.11.0 verification, batch 1

13 of 14 requirements moved to `verified`. OPS-REL-010 stays `implemented` because its AC-3 no longer matches the gate. The extra item OPS-REL-011 gained its evidence row and kept its status.

Every AC was read against the test body, not against its citation or evidence row.

## PRODUCT-GAP

### OPS-REL-010 AC-3: the AC describes a gate rule that OPS-REL-011 replaced

AC text:

> The release-evidence gate accepts version 0.11.0 as pending only while neither docs/release/0.11.0-test-results.md nor docs/release/0.11.0-vulnerability-scan.md exists, still validates the 0.10.0 records, and fails once either 0.11.0 record exists until the gate is pointed at the 0.11.0 records.

What the code does instead:

| AC clause | Current behaviour | Where |
|---|---|---|
| 0.11.0 is pending while neither record exists | Refused. Only version 0.9.0 can be pending; 0.11.0 with no records fails with "current evidence requires result record and scan" | `src/test/java/com/snoworca/naruserve/docs/ReleaseEvidenceSnapshots.java:102-103`, asserted by `ReleaseEvidenceSnapshotsTest.java:148` |
| The gate still validates the 0.10.0 records | At version 0.11.0 the gate reads only the 0.11.0 records, and valid 0.10.0 records do not satisfy it | `ReleaseEvidenceSnapshots.java:99-101`, asserted by `ReleaseEvidenceSnapshotsTest.java:151` |
| Fails once either record exists, until the gate is repointed | The gate has been repointed, so this state cannot occur any more | `ReleaseEvidenceSnapshots.java:99` |

The test the old evidence row named, `version011IsPendingOnlyWithoutItsRecordsAndStillChecks010`, no longer exists. `version011IsCheckedAgainstItsOwnRecords` replaced it, and the comment at `ReleaseEvidenceSnapshotsTest.java:142` says so.

This is not a defect in the release. The gate does what OPS-REL-011 AC-4 requires, and OPS-REL-010 AC-3 described the interval before the release run. No test can assert the AC as worded, because the opposite is asserted. Changing an AC's wording was out of scope for this verifier.

To close it, someone needs to reword or supersede AC-3 to say the rule ended with OPS-REL-011 AC-4. AC-1 and AC-2 are asserted, so the requirement can then go to `verified`.

`speckiwi coverage` shows no gap for this AC, but only because the comment at `ReleaseEvidenceSnapshotsTest.java:142` cites it. That comment records the replacement, not an assertion. No evidence row claims AC-3 any more.

## Results

Verdict counts cover ACs. "Fixed" means the stale `VE-1` row was rewritten and per-AC rows were added. Before this batch nearly every row used a composite reference such as `#SEC_ENGINE_001_AC1..AC5`, which names no real method.

| Requirement | ACs read | Verdict | Tests added | Evidence rows | Status |
|---|---|---|---|---|---|
| CON-ENGINE-001 | 4 | 3 asserted, AC-3 test gap closed | `CON_ENGINE_001_AC3_warningNamesTheUnverifiedLeftMostValue` | Fixed VE-1 and VE-2; now 10 rows | verified |
| IR-ENGINE-003 | 7 | 6 asserted, AC-7 test gap closed | `IR_ENGINE_003_AC7_expressCidrListMappingOnEveryPage` | Fixed VE-1; now 11 rows | verified |
| SEC-ENGINE-001 | 6 | 6 asserted | none | Fixed VE-1; now 8 rows | verified |
| SEC-ENGINE-002 | 7 | 7 asserted | none | Fixed VE-1; now 9 rows | verified |
| SEC-ENGINE-003 | 8 | 8 asserted | none | Fixed VE-1; now 8 rows | verified |
| FR-APP-025 | 7 | 7 asserted | none | Fixed VE-1; now 7 rows | verified |
| FR-APP-026 | 7 | 6 asserted, AC-2 test gap closed | `FR_APP_026_AC2_groupIsRecordedBeforeItsInnerRouteIsWatched` | Fixed VE-1; now 8 rows | verified |
| IR-APP-008 | 8 | 7 asserted, AC-1 test gap closed | `IR_APP_008_AC1_snapshotDecisionDocumented` | Fixed VE-1; now 15 rows | verified |
| SEC-APP-002 | 6 | 5 asserted, AC-3 test gap closed | `SEC_APP_002_AC3_vetoedRegistrationLeavesWAnswering404` | Fixed VE-1; now 7 rows | verified |
| FR-HTTP-029 | 5 | 5 asserted | none | Fixed VE-1; now 5 rows | verified |
| IR-HTTP-010 | 4 | 3 asserted, AC-4 test gap closed | `IR_HTTP_010_AC4_remoteAddressFollowsTheTrustSetting` | Fixed VE-1; now 9 rows | verified |
| SEC-HTTP-001 | 6 | 6 asserted | none (citation comment only) | Fixed VE-1; now 8 rows | verified |
| OPS-REL-010 | 3 | AC-1 and AC-2 test gaps closed, AC-3 PRODUCT-GAP | `OPS_REL_010_AC1_moduleMetadataAndExamplesUseTheReleaseVersion`, `OPS_REL_010_AC2_everyCurrentCoordinateIsTheReleaseVersion` | Fixed VE-1 (named a deleted test); now 5 rows, none for AC-3 | implemented |
| SEC-SVL-001 | 5 | 5 asserted | none | Fixed VE-1; now 4 rows | verified |
| OPS-REL-011 (extra) | AC-1, AC-2 | Test gap closed | `realRecords011PassTheGateAndHoldBothRuns` | Added VE-5 | verified (unchanged) |

## What each added test closes

- **CON-ENGINE-001 AC-3:** the old test matched "client-controlled" but not "unverified" or "left-most" on the warning line.
- **IR-ENGINE-003 AC-7:** the old test checked `trustProxyCidrs(` somewhere on the page, not that an Express CIDR list maps to it.
- **IR-HTTP-010 AC-4:** nothing asserted the sentence that `remoteAddress()` follows the proxy trust setting.
- **IR-APP-008 AC-1:** nothing asserted that the snapshot decision is documented.
- **FR-APP-026 AC-2:** the old test checked final order only. The new one reads the list from inside the watcher, so it asserts that GROUP is made after the configurer returns and is recorded before its inner route is watched.
- **SEC-APP-002 AC-3:** the old test checked that the answer was not a marker string. The new one asserts the literal `404` on `/w` for all five registration kinds.
- **OPS-REL-010 AC-1:** nothing read the `.module` content or the examples' dependencies. Staging checked `.module` by file name only.
- **OPS-REL-010 AC-2:** no test scanned for a stale coordinate across all the pages.
- **OPS-REL-011 AC-1, AC-2:** `ReleaseRecordsDocsTest.fr_REL_002_AC3_vulnerabilityScanRecordHasStatusAndReasonWhenPending` already ran the gate on the real records, but the gate reads only the `full-suite` line. AC-1 also requires the Java 8 run, so a citation was not enough. The new test runs the gate and checks the `java8-full` line with the same fields.

## Limits of this evidence

- **OPS-REL-010 AC-2:** the clause that 0.10.0 mentions stay is not asserted by any test. The links exist today (`README.md:286-287`, `README.ko.md:282-283`). The requirement is not verified, so this does not affect a status.
- **SEC-SVL-001:** values are observed on embedded Jetty 9.4 only; other containers remain unmeasured. AC-5 is exercised with a captured `isSecure()` of `false` only.
- **SEC-HTTP-001 AC-6, SEC-ENGINE-001 AC-6, SEC-ENGINE-002 AC-6:** `CrossAdapterTest.FR_ENGINE_008_AC4_noAdapterClassReadsForwardedHeaders` scans the TinyHttpd and in-memory classes only, although a comment at `RequestIdAndSocketAddressTest.java:248` implies it also covers Servlet. The Servlet half is `SEC_SVL_001_AC5_exchangeReadsNoForwardingHeaderAndNoRemoteHost`, now cited and given an evidence row.
- **CON-ENGINE-001 AC-1:** "without modification of their assertions" was checked by `git diff bdd576a HEAD` on the three test files, which shows comment lines only (plus one added method). No automated test enforces it.

## Run results

| Check | Result |
|---|---|
| `./gradlew -PtinyHttpdFromMaven=true build --continue --console=plain --offline` | exit 0 |
| Summed `build/test-results` | 3607 tests, 0 failures, 0 errors, 0 skipped (3598 before, plus 9 added) |
| `speckiwi coverage --tests --target 0.11.0` | No gap for any batch id. `passed` is false because of 4 gaps outside this batch: FR-AUTH-010 AC-6, FR-IAM-016 AC-6, OPS-AUTH-005 AC-6, OPS-AUTH-007 AC-8 |
| `validate_spec` | 0 errors, 47 SRS-W015 warnings (the same count as before this batch) |
| Guard refusals on `update_status` | none |
| Main sources and `docs/api` | unchanged |
