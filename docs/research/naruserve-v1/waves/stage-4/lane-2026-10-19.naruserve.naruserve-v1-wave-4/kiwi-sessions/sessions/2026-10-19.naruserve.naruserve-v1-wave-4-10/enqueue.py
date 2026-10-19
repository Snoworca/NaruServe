import hashlib, json, sys

Q = sys.argv[1]
RUN = "2026-10-19.naruserve.naruserve-v1-wave-4-10"
NOW = "2026-10-19T08:30:00Z"
NOTE = "command ./gradlew build result pass (SDS wave-4-10)"
A = "NaruServe-api/src/test/java/com/snoworca/naruserve/AppDocsTest.java"
H = "NaruServe-api/src/test/java/com/snoworca/naruserve/HttpDocsTest.java"
R = "NaruServe-api/src/test/java/com/snoworca/naruserve/RoutingDocsTest.java"
D = "src/test/java/com/snoworca/naruserve/docs/DocsExamplesCompileTest.java"

EVIDENCE = {
    "OPS-APP-001": [
        (A, "sdsAc1_OPS_APP_001_AC1_builderFactoryAndServerHandle", "AC-1"),
        (A, "sdsAc3_OPS_APP_001_AC2_middlewareAndRoutingDecisions", "AC-2"),
        (A, "sdsAc6_OPS_APP_001_AC3_lifecycleStartGateAndPendingStop", "AC-3"),
        (A, "sdsAc7_OPS_APP_001_AC4_loggingFormatDefaultsAndClose", "AC-4"),
        (A, "sdsAc8_OPS_APP_001_AC5_expressMigrationRows", "AC-5"),
        (A, "sdsAc5_OPS_APP_001_AC6_decidedMarkersAreGone", "AC-6"),
        (A, "sdsAc5_OPS_APP_001_AC6_laterWaveMarkersAreKept", "AC-6"),
        (A, "sdsAc5_OPS_APP_001_AC6_wave4PagesKeepNoMarker", "AC-6"),
        (A, "sdsAc2_OPS_APP_001_AC6_wsMarkersSitOnTheirOwnLines", "AC-6"),
        (A, "sdsAc4_OPS_APP_001_AC7_decisionsCarryAReason", "AC-7"),
    ],
    "OPS-APP-002": [
        (D, "sdsAc13_OPS_APP_002_AC1_noSkipNamesAWave4Member", "AC-1"),
        (D, "sdsAc11_OPS_HTTP_004_AC3_everyNonSkippedDocsBlockCompiles", "AC-1"),
        (D, "sdsAc14_OPS_APP_002_AC2_everySkippedBlockStillFailsToCompile", "AC-2"),
    ],
    "OPS-HTTP-003": [
        (H, "sdsAc9_OPS_HTTP_003_AC1_rawPathRowIsRawPathWithoutQuery", "AC-1"),
        (H, "sdsAc9_OPS_HTTP_003_AC2_attributeTypeMismatchAndNullRemoval", "AC-2"),
        (H, "sdsAc9_OPS_HTTP_003_AC3_octetStreamAndStreamClosing", "AC-3"),
        (H, "sdsAc9_OPS_HTTP_003_AC4_routerPolicyDocumented", "AC-4"),
        (H, "sdsAc9_OPS_HTTP_003_AC5_appListsRouterPolicy", "AC-5"),
        (H, "sdsAc9_OPS_HTTP_003_AC6_defaultsNamesTraceConnectPolicy", "AC-6"),
        (H, "sdsAc9_OPS_HTTP_003_AC7_expressRawPathAndRawTextMapping", "AC-7"),
        (H, "sdsAc9_OPS_HTTP_003_AC8_requestKeepsOtherIssuesMarkers", "AC-8"),
        (H, "sdsAc9_OPS_HTTP_003_AC8_responseKeepsFileAndRedirectMarkers", "AC-8"),
        (H, "sdsAc9_OPS_HTTP_003_AC8_expressKeepsOtherIssuesMarkers", "AC-8"),
        (H, "sdsAc9_OPS_HTTP_003_AC8_routingKeepsNoMarker", "AC-8"),
    ],
    "OPS-HTTP-004": [
        (D, "sdsAc11_OPS_HTTP_004_AC2_everyJavaFenceOfEveryDocIsExtracted", "AC-2"),
        (D, "sdsAc11_OPS_HTTP_004_AC3_nonTypeBlocksAreWrappedInTheTemplate", "AC-3"),
        (D, "sdsAc11_OPS_HTTP_004_AC3_compilerRunsInProcessAndReportsErrors", "AC-3"),
        (D, "sdsAc11_OPS_HTTP_004_AC3_everyNonSkippedDocsBlockCompiles", "AC-3"),
        (D, "sdsAc12_OPS_HTTP_004_AC4_everySkipNamesFileBlockAndReason", "AC-4"),
        (D, "sdsAc12_OPS_HTTP_004_AC4_appDefinedTypesAreNamedReasons", "AC-4"),
        (D, "sdsAc11_OPS_HTTP_004_AC5_noJavaScriptInJavaFences", "AC-5"),
        (D, "sdsAc12_OPS_HTTP_004_AC6_noStaleSkip", "AC-6"),
        (D, "sdsAc11_OPS_HTTP_004_AC7_responseAndRoutingBlocksAreCompiled", "AC-7"),
    ],
    "OPS-ROUTE-003": [
        (R, "sdsAc10_OPS_ROUTE_003_AC1_routingRulesSectionPresent", "AC-1"),
        (R, "sdsAc10_OPS_ROUTE_003_AC2_accessorsNamedWithJavaKotlinAndExpressNote", "AC-2"),
        (R, "noMarkerRemains", "AC-3"),
    ],
}


def entry(tool, args):
    h = hashlib.sha1(json.dumps(args, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
    return {"called_at": NOW, "tool": tool, "args": args, "args_hash": h, "ok": None, "response_hash": None,
            "dry_run": False, "deferred": True, "run_id": RUN}


out = []
for rid, evs in EVIDENCE.items():
    out.append(entry("update_status", {"id": rid, "status": "implemented"}))
    for f, name, ac in evs:
        out.append(entry("add_verification_evidence", {"id": rid, "type": "test", "reference": f + ":" + name,
                                                       "covers": ac, "notes": NOTE}))
out.append(entry("add_completed_work", {
    "date": "2026-10-19",
    "summary": "[sds] 2026-10-19.naruserve.naruserve-v1-wave-4-10 - wave 4 decisions recorded in docs/api (builder, "
               "AppFactory, ServerHandle, LifecycleCallback/ErrorHandler, RouteBuilder.use, onError chain, code table, "
               "start gate, pending stop, registerExternal, AppStartException, access log format and defaults, logger "
               "modules and shutdown close), wave 4 markers removed and later-wave markers kept; HttpDocsTest, "
               "RoutingDocsTest, DocsExamplesCompileTest re-pointed; skip list reasons corrected. green=PASS "
               "(./gradlew build). files: docs/api/{app,README,middleware,routing,lifecycle,logging,express-migration}.md, "
               "AppDocsTest.java (new), HttpDocsTest.java, RoutingDocsTest.java, DocsExamplesCompileTest.java. "
               "SDS-AC: 1..14 PASS",
    "requirementIds": list(EVIDENCE.keys()), "target": "wave-4", "allowIncomplete": False, "dryRun": False}))

with open(Q, "a", encoding="utf-8") as fh:
    for e in out:
        fh.write(json.dumps(e, ensure_ascii=False) + "\n")
print(len(out))
