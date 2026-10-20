# NaruServe 0.1.0-SNAPSHOT test results

This document lists the acceptance tests T01 to T50 of `object.md` §17.2 with one status each.

## Scope of v1

The Android sample build and API 21 device or emulator verification are not part of v1.
API 21 compatibility rests on compiling with `--release 8` and on the animal-sniffer `android-api-level-21` check of the seven API 21 modules.
The Android sample build, the AGP and R8 builds, StrictMode, Logcat on a device and any API 21 device or emulator run were removed from v1 by the user decision of 2026-10-20; issue #17 is closed by that decision (2026-10-20) and has no remaining deliverable. The third part of the API 21 basis is the D-385 rule: no module has a compile-time `android.*` reference; `checkClassReferences` fails on an `android/` class reference in `NaruServe-api` and `NaruServe-logcat`, and no module has an Android artifact on its compile classpath.

## Status literals

- `automated`: automated tests of the default `./gradlew build` cover the line; the evidence is one or more
  `path#method` links separated by `, `.
- `manual-pending`: a run or check that has not happened yet; the evidence is an item of the checklist below. The
  platform matrix of the release notes and the vulnerability scan record call the same state `pending`.
- `verified`: a run on the release machine or a manual check with recorded evidence; the evidence is a recorded run.
- `out-of-v1-scope`: removed from v1; the evidence is the reason.

No requirement is `verified` on the strength of a `manual-pending` line.

## Results

| test | part | status | evidence |
|---|---|---|---|
| T01 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/HttpAcceptanceTest.java#sds3Ac11_T01_FR_HTTP_007_AC1_AC2_AC4_oneHandlerForAMethodSetSeesTheActualMethod |
| T02 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/HttpAcceptanceTest.java#sdsAc15_T02_FR_HTTP_022_AC7_anyExceptDeleteAnswersDelete405 |
| T03 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/HttpAcceptanceTest.java#sdsAc11_T03_IR_HTTP_006_AC3_duplicateBuilderAndAppGetFailsTheHarnessFreeze |
| T04 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/routing/RouterPriorityTest.java#sdsAc11_FR_ROUTE_007_AC1_literalBeatsVariableInBothOrders |
| T05 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/HttpAcceptanceTest.java#sds3Ac7_T05_FR_HTTP_004_AC1_AC2_automaticHeadUsesTheGetHandler, NaruServe-api/src/test/java/com/snoworca/naruserve/HttpAcceptanceTest.java#sdsAc16_T05_FR_HTTP_022_AC3_automaticOptionsListsAllowWithoutBody |
| T06 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/HttpAcceptanceTest.java#sdsAc15_T06_FR_HTTP_022_AC1_AC2_AC4_noPathNoMethodUnknownMethod |
| T07 | - | automated | NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/CrossAdapterTest.java#sdsAc1_FR_ENGINE_007_AC1_t07RunsOnBothAdaptersWithTheSameResults |
| T08 | - | automated | NaruServe-gson/src/test/java/com/snoworca/naruserve/gson/GsonJsonCodecTest.java#FR_JSON_002_AC2_echoRoundTripsValuesThroughTheDefaultCodec |
| T09 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/JsonBodyAcceptanceTest.java#sdsAc7_FR_JSON_001_AC1_otherMediaTypesGive415WithoutCallingTheCodec, NaruServe-api/src/test/java/com/snoworca/naruserve/JsonBodyAcceptanceTest.java#sdsAc8_FR_JSON_001_AC2_emptyAndWhitespaceBodiesGive400WithoutCallingTheCodec, NaruServe-api/src/test/java/com/snoworca/naruserve/JsonBodyAcceptanceTest.java#sdsAc9_FR_JSON_001_AC4_contentLengthOverTheDefaultLimitGives413BeforeTheCodec, NaruServe-gson/src/test/java/com/snoworca/naruserve/gson/GsonJsonCodecTest.java#sdsAc4_FR_JSON_003_AC4_default64DecodesAnd65Fails |
| T10 | - | out-of-v1-scope | user decision 2026-10-20 |
| T11 | - | automated | NaruServe-api/src/test/kotlin/com/snoworca/naruserve/CallbackMatrixKotlinTest.kt#FR-KT-003 AC-3 a RouteHandler Middleware and ErrorHandler lambdas are reached by requests |
| T12 | - | out-of-v1-scope | user decision 2026-10-20 |
| T13 | - | automated | NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/MultipartCrossAdapterTest.java#FR_MULTIPART_001_AC1_accessorsGiveTheSameResultsOnBothAdapters |
| T14 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/MultipartSpoolingTest.java#FR_MULTIPART_004_AC2_spooledUploadKeepsHeapGrowthUnder8MiB |
| T15 | - | automated | NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/MultipartCrossAdapterTest.java#FR_MULTIPART_001_AC1_limitsGiveTheSame413OnBothAdapters |
| T16 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/MultipartSpoolingTest.java#sdsAc2_FR_MULTIPART_004_AC3_tempNamesNeverDeriveFromClientValues |
| T17 | - | automated | NaruServe-servlet/src/test/java/com/snoworca/naruserve/servlet/ServletMultipartDisconnectTest.java#sdsAc4_FR_SVL_004_AC4_aClientCutMidUploadLeavesNoUploadFileAfterTheRequestThreadReturned |
| T18 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/internal/DefaultUploadFileTest.java#FR_MULTIPART_005_AC2_transferToCopiesAndNeverClosesTarget |
| T19 | - | automated | NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/WebSocketSocketTest.java#sdsAc7_FR_WS_002_AC1_handshakeGets101WithAcceptAndTextReachesOnText |
| T20 | - | automated | NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/WebSocketProtocolSocketTest.java#FR_WS_005_AC1_unmaskedFrameClosesWith1002, NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/WebSocketProtocolSocketTest.java#FR_WS_005_AC2_invalidUtf8ClosesWith1007AndNoOnText, NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/WebSocketProtocolSocketTest.java#FR_WS_005_AC3_defaultLimitAcceptsOneMebibyteAndRefusesOneByteMore, NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/WebSocketProtocolSocketTest.java#sdsAc11_FR_WS_005_AC5_fragmentsJoinAndAnInterleavedPingIsAnswered |
| T21 | - | automated | NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/WebSocketProtocolSocketTest.java#sdsAc8_FR_WS_005_AC7_pingIsAnsweredAndWsPingSendsAPing, NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/WebSocketShutdownSocketTest.java#sdsAc9_FR_WS_010_AC4_AC2_twoStalledSessionsAbortedWithin1200MsHealthyGets1001, NaruServe-api/src/test/java/com/snoworca/naruserve/WebSocketDispatchTest.java#FR_WS_001_AC6_reqServiceInTheHandlerIsTheOnStartInstance |
| T22 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/WebSocketOriginTest.java#sdsAc6_FR_WS_003_AC2_crossOriginGets403WithTheRequestIdAndNoUpgrade |
| T23 | - | automated | NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/WebSocketSendSocketTest.java#sdsAc5_FR_WS_007_AC5_twentyStalledSessionsNeverDelayAHealthyOne |
| T24 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/SseStreamTest.java#sdsAc10_FR_SSE_001_AC5_sendOfAnSseEventWritesItsFieldsInOrder, NaruServe-api/src/test/java/com/snoworca/naruserve/SseStreamTest.java#sdsAc13_FR_SSE_001_AC8_lastEventIdReachesTheHandlerAndTheReplaySendsOnlyLaterEvents, NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/SseSocketTest.java#sdsAc6_FR_SSE_001_AC1_AC3_sseCommits200WithEachEventStreamHeaderOnceOverTinyHttpd |
| T25 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/SseQueueTest.java#sdsAc2_FR_SSE_002_AC3_anIdleStreamGetsAPingAfterEachHeartbeatInterval |
| T26 | jvm | automated | NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/LoadIsolatedSocketTest.java#sdsAc1_PERF_APP_001_AC1_120LongConnectionsStayWithinTheThreadAndHeapBounds |
| T26 | device | out-of-v1-scope | user decision 2026-10-20 |
| T27 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/StaticMountingTest.java#FR_STATIC_001_AC1_AC2_AC4_filesIndexesAndRoutesFirst |
| T28 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/MimeTypesTest.java#FR_STATIC_007_AC1_mountThenAppThenBuiltInThenOctetStream |
| T29 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/StaticTraversalTest.java#sdsAc1_FR_STATIC_005_AC1_traversalRequestsAre400AndSecretNeverSent |
| T30 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/StaticConditionalTest.java#FR_STATIC_009_AC1_staticFileAndDownloadCarryEtagLastModifiedAndAcceptRanges |
| T31 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/StaticRangeTest.java#FR_STATIC_010_AC1_singleRangesGive206AndClipTheEnd |
| T32 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/StaticCacheTest.java#FR_STATIC_008_AC1_policyHeaderValues |
| T33 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/CookieAcceptanceTest.java#sdsAc4_FR_COOKIE_001_AC2_fullAttributeLineAndSameSiteValues |
| T34 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/CookieAcceptanceTest.java#sdsAc10_FR_COOKIE_002_AC1_AC2_AC3_readersReturnFreshArraysAndTheFirstMatch |
| T35 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/internal/ChainNextTest.java#sdsAc9_FR_APP_004_AC1_secondRunThrowsAndDownstreamRanOnce |
| T36 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/internal/ChainNextTest.java#sdsAc11_FR_APP_004_AC4_middlewareThatAnswersWithoutNextStopsTheChain |
| T37 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/internal/ErrorPathTest.java#sdsAc5_FR_APP_005_AC9_aResponseCommittedButNotCompletedIsAborted |
| T38 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/AccessLogAcceptanceTest.java#wave7SdsAc4_FR_APP_019_AC10_sseLineAtStreamEndWithTheFirstRecordedCause, NaruServe-api/src/test/java/com/snoworca/naruserve/AccessLogAcceptanceTest.java#sdsAc4_FR_APP_019_AC1_defaultLineForGetUsers7, NaruServe-api/src/test/java/com/snoworca/naruserve/AccessLogAcceptanceTest.java#wave7SdsAc1_FR_APP_019_AC8_handshakeLineIsWrittenBeforeTheFirstClientFrame, NaruServe-api/src/test/java/com/snoworca/naruserve/AccessLogAcceptanceTest.java#wave7SdsAc3_FR_APP_019_AC9_closeLineCarriesTheReportedCodeAndTheHandshakeRidOnce |
| T39 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/AccessLogAcceptanceTest.java#sdsAc11_SEC_APP_001_AC1_noQueryHeaderOrBodySecretInTheLine |
| T40 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/ConsoleLoggerTest.java#sdsAc10_IR_APP_006_AC5_consoleLoggerIsPublicFinalAndReferencesNoSlf4jOrAndroidType, NaruServe-logcat/src/test/java/com/snoworca/naruserve/logcat/LogcatLoggerTest.java#sdsAc4_FR_APP_016_AC3_withoutAndroidLogTheConstructorThrowsIseNamingTheClass |
| T41 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/ServicesAcceptanceTest.java#sdsAc1_FR_APP_007_AC1_T41_serviceFromOnStartIsIdenticalViaServiceAndApplicationGet |
| T42 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/ServicesAcceptanceTest.java#sdsAc4_FR_APP_007_AC3_T42_duplicateInOnStartFailsListenAndTheCloseHookSeesTheFirst |
| T43 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/internal/StartFailureTest.java#sdsAc4_FR_APP_010_AC4_resourcesCloseInReverseSeeingFailedThenTheStateIsTerminated |
| T44 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/internal/ShutdownSequenceTest.java#wave7SdsAc7_FR_APP_020_AC5_inFlight300MsRequestCompletesBeforeOnStop, NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/LongConnectionShutdownSocketTest.java#wave7SdsAc9_FR_APP_020_AC8_AC6_AC7_oneSecondShutdownClosesStalledAndHealthyConnections, NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/WebSocketShutdownSocketTest.java#sdsAc9_FR_WS_010_AC4_AC2_twoStalledSessionsAbortedWithin1200MsHealthyGets1001, NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/SseShutdownSocketTest.java#sdsAc12_FR_SSE_005_AC2_AC5_AC6_noticeThenEndForAReaderStalledClientForceClosedRegistryEmpty |
| T45 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/ServicesAcceptanceTest.java#sdsAc10_FR_APP_008_AC1_T45_ownDbThenRegisterUsersCloseUsersThenDbOnShutdown |
| T46 | - | automated | NaruServe-api/src/test/java/com/snoworca/naruserve/internal/ShutdownStatesTest.java#sdsAc20_FR_APP_012_AC6_listenOnATerminatedAppThrowsIllegalState, NaruServe-api/src/test/java/com/snoworca/naruserve/internal/ShutdownSequenceTest.java#sdsAc11_FR_APP_011_AC5_concurrentShutdownRunsTheSequenceOnceAndTheLoserWaits |
| T47 | - | out-of-v1-scope | user decision 2026-10-20 |
| T48 | - | automated | NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/LimitsSocketTest.java#FR_ENGINE_003_AC6_contentLengthWithChunkedIsRejectedBeforeDispatch, NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/LimitsSocketTest.java#FR_ENGINE_003_AC4_headerCountHeaderTimeoutAndBodyReadTimeout, NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/LimitsSocketTest.java#FR_ENGINE_003_AC5_dripFedBodyIsAnswered408AfterTheGraceAndClosed |
| T49 | jvm | automated | NaruServe-tinyhttpd/src/test/java/com/snoworca/naruserve/tinyhttpd/BindingSocketTest.java#sdsAc5_FR_ENGINE_002_AC2_lanAddressIsRefusedByDefaultAndServedAfterAWildcardBind |
| T49 | device | out-of-v1-scope | user decision 2026-10-20 |
| T50 | sbom | verified | [SBOM run](#run-sbom) |
| T50 | vulnerability | verified | [vulnerability scan run](#run-vulnerability-scan) |
| T50 | license | verified | [license run](#run-licenses) |

## Checklist

- <a id="checklist-1"></a>Checklist item 1 completed on 2026-10-21: the real mirror-backed scan completed and `releaseCheck` passed. See [security review](security-review.md) for the exact artifact/CVE false-positive rule and [scan report](vulnerability-scan.md). No risk-acceptance exception was added.

## Post-v1 items

- Low-end Android tuning of `maxSseStreams`, `maxUpgradedConnections` and `sendQueueSize` (D-347): post-v1, not a
  manual release check, because Android device verification is outside the v1 scope.

## Recorded runs

Each line records one run on the build machine (macOS, OpenJDK 17.0.20, Gradle 8.14.3).

<a id="run-full-suite"></a>
- run full-suite: command=./gradlew build --rerun-tasks --console=plain; date=2026-10-21; result=exit=0; commit=c7f306c; tests=1677; failures=0; errors=0; skipped=0; evidence=audit-2026-10-21/full17.json

<a id="run-sbom"></a>
- run sbom: command=./gradlew -Prelease '-PnvdDatafeedUrl=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz' releaseSbom releaseScan releaseLicenses releaseCheck --console=plain; date=2026-10-21; result=exit=0; NaruServe-api.cdx.json=0, NaruServe-filelog.cdx.json=1, NaruServe-gson.cdx.json=3, NaruServe-kotlin.cdx.json=3, NaruServe-logcat.cdx.json=1, NaruServe-servlet.cdx.json=1, NaruServe-slf4j.cdx.json=2, NaruServe-tinyhttpd.cdx.json=3, NaruServe.cdx.json=8

<a id="run-offline-build"></a>
- run offline-build: command=./gradlew build --offline; date=2026-10-20; result=exit=0; commit=ae44f5a

<a id="run-publish-local"></a>
- run publish-local: command=./gradlew publishToMavenLocal --offline; date=2026-10-20; result=exit=0; commit=ae44f5a; ~/.m2/repository: com/snoworca/naruserve/NaruServe-api/0.1.0-SNAPSHOT/, com/snoworca/naruserve/NaruServe-servlet/0.1.0-SNAPSHOT/, com/snoworca/naruserve/NaruServe-tinyhttpd/0.1.0-SNAPSHOT/, com/snoworca/naruserve/NaruServe-gson/0.1.0-SNAPSHOT/, com/snoworca/naruserve/NaruServe/0.1.0-SNAPSHOT/, com/snoworca/naruserve/NaruServe-kotlin/0.1.0-SNAPSHOT/, com/snoworca/naruserve/NaruServe-slf4j/0.1.0-SNAPSHOT/, com/snoworca/naruserve/NaruServe-filelog/0.1.0-SNAPSHOT/, com/snoworca/naruserve/NaruServe-logcat/0.1.0-SNAPSHOT/

<a id="run-licenses"></a>
- run licenses: command=./gradlew -Prelease '-PnvdDatafeedUrl=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz' releaseSbom releaseScan releaseLicenses releaseCheck --console=plain; date=2026-10-21; result=exit=0; THIRD-PARTY-LICENSES.md: 7 shipped, 3 provided; non-GPL check passed; evidence=verification/release-check.log

<a id="run-vulnerability-scan"></a>
- run vulnerability-scan: command=./gradlew -Prelease '-PnvdDatafeedUrl=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz' releaseSbom releaseScan releaseLicenses releaseCheck --console=plain; date=2026-10-21; result=exit=0; Status: completed; High=0; Critical=0; unscored=0; Medium=3; evidence=audit-2026-10-21/scan.csv, audit-2026-10-21/release-check.log; NVD mirror lastModifiedDate=2026-10-20T15:35:25Z

<a id="run-java8"></a>
- run java8: command=./gradlew build --rerun-tasks --console=plain -I gradle/java8-tests.gradle -Dnaruserve.java8.home=/Users/beom/Library/Java/JavaVirtualMachines/zulu8.96.0.205-ca-jdk8.0.504-macosx_aarch64/Contents/Home; date=2026-10-21; result=exit=0; tests=1677; runtime=1.8.0_504; evidence=docs/release/verification/java8-suite.json
