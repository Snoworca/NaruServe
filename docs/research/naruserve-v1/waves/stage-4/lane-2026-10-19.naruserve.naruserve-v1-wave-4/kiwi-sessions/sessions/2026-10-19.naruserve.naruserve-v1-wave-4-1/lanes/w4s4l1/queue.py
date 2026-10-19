import hashlib, json, os, datetime
Q = '/Users/beom/work/git/NaruServe/kiwi/orchestrator/2026-10-19.NaruServe.naruserve-v1/lanes/lane-2026-10-19.naruserve.naruserve-v1-wave-4/deferred-srs-queue.jsonl'
RUN = '2026-10-19.naruserve.naruserve-v1-wave-4-1'
NOTE = 'command: ./gradlew build; result: pass (exit 0)'
T = 'NaruServe-api/src/test/java/com/snoworca/naruserve/'
K = 'NaruServe-api/src/test/kotlin/com/snoworca/naruserve/'
ev = {
    'IR-APP-001': [
        (T + 'RestAppBuilderTest.java', 'sdsAc1_IR_APP_001_AC1_twoBuildsGiveDistinctNewDefaultRestAppsSharingTheConfiguration', 'AC-1'),
        (T + 'RestAppBuilderTest.java', 'sdsAc2_IR_APP_001_AC2_missingAdapterIsReportedFirstThenMissingCodec', 'AC-2'),
        (T + 'RestAppBuilderTest.java', 'sdsAc2_IR_APP_001_AC2_nullSetterArgumentsThrowNullPointerExceptionAtTheSetter', 'AC-2'),
        (T + 'RestAppBuilderTest.java', 'sdsAc3_IR_APP_001_AC3_withoutLoggerEachBuildGetsItsOwnNewConsoleLogger', 'AC-3'),
        (T + 'AppEntryApiTest.java', 'sdsAc5_IR_APP_001_AC4_jsonCodecHasExactlyDecodeAndEncodeOverJavaTypes', 'AC-4'),
        (K + 'AppEntryKotlinTest.kt', 'SDS-AC-4 IR-APP-001 AC-5 Kotlin object JsonCodec goes through the builder chain', 'AC-5'),
    ],
    'IR-APP-002': [
        (T + 'AppEntryApiTest.java', 'sdsAc5_IR_APP_002_AC1_middlewareIsFunctionalAndNextHasExactlyRunAndError', 'AC-1'),
        (T + 'HttpApiSurfaceTest.java', 'sdsAc6_IR_HTTP_005_AC1_AC2_AC3_AC5_IR_APP_002_AC2_restAppDeclaresExactlyTheWave4Methods', 'AC-2'),
        (T + 'AppEntryApiTest.java', 'sdsAc6_IR_APP_002_AC3_routeBuilderUseAppliesOnlyEarlierMiddlewareInUseOrder', 'AC-3'),
        (T + 'AppEntryApiTest.java', 'sdsAc7_IR_APP_002_AC4_nullArgumentsAndRelativePrefixAreRefused', 'AC-4'),
        (T + 'AppEntryApiTest.java', 'sdsAc5_IR_APP_001_AC4_IR_APP_002_AC5_noGsonAndNoFutureTypeOnTheEntrySurface', 'AC-5'),
        (K + 'AppEntryKotlinTest.kt', 'SDS-AC-8 IR-APP-002 AC-6 IR-APP-003 AC-1 use, prefix use, route middleware and onError lambdas by SAM conversion', 'AC-6'),
    ],
    'IR-APP-003': [
        (T + 'AppEntryApiTest.java', 'sdsAc5_IR_APP_003_AC1_errorHandlerIsFunctionalAndOnErrorReturnsTheApp', 'AC-1'),
        (T + 'HttpExceptionTest.java', 'sdsAc9_IR_APP_003_AC2_statusOutside400To599IsRefusedNamingTheStatus', 'AC-2'),
        (T + 'HttpExceptionTest.java', 'sdsAc10_IR_APP_003_AC3_codeIsTheStandardCodeOfTheStatus', 'AC-3'),
        (T + 'HttpExceptionTest.java', 'sdsAc10_IR_APP_003_AC4_unsupportedMediaTypeIs415', 'AC-4'),
        (T + 'AppEntryApiTest.java', 'sdsAc11_IR_APP_003_AC5_onErrorNullThrowsAndOnErrorIsRefusedAfterFreeze', 'AC-5'),
    ],
    'FR-APP-001': [
        (T + 'HttpApiSurfaceTest.java', 'sdsAc6_IR_HTTP_005_AC1_AC2_AC3_AC5_IR_APP_002_AC2_restAppDeclaresExactlyTheWave4Methods', 'AC-1'),
        (T + 'internal/AppFactoryLoaderTest.java', 'sdsAc12_FR_APP_001_AC2_oneProviderReturnsWhatItsCreateReturned', 'AC-2'),
        (T + 'internal/AppFactoryLoaderTest.java', 'sdsAc13_FR_APP_001_AC3_noProviderNamesTheModuleAndTheBuilder', 'AC-3'),
        (T + 'internal/AppFactoryLoaderTest.java', 'sdsAc13_FR_APP_001_AC3_restAppCreateThrowsInTheApiTestRun', 'AC-3'),
        (T + 'internal/AppFactoryLoaderTest.java', 'sdsAc14_FR_APP_001_AC4_twoProvidersAreBothNamed', 'AC-4'),
        (T + 'internal/AppFactoryLoaderTest.java', 'sdsAc15_FR_APP_001_AC5_servicesFileExistsOnlyInTheTempDir', 'AC-5'),
    ],
}
now = datetime.datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%SZ')
existing = set()
if os.path.exists(Q):
    for line in open(Q):
        line = line.strip()
        if line:
            existing.add(json.loads(line)['args_hash'])
out = []
def add(tool, args):
    h = hashlib.sha1(json.dumps(args, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    if h in existing:
        return
    existing.add(h)
    out.append(json.dumps({'called_at': now, 'tool': tool, 'args': args, 'args_hash': h, 'ok': None,
                           'response_hash': None, 'dry_run': False, 'deferred': True, 'run_id': RUN}))
for rid in ev:
    add('update_status', {'id': rid, 'status': 'in_progress'})
for rid, items in ev.items():
    for f, name, ac in items:
        add('add_verification_evidence', {'id': rid, 'type': 'test', 'reference': f + ':' + name, 'covers': ac,
                                          'notes': NOTE})
    add('update_status', {'id': rid, 'status': 'implemented'})
with open(Q, 'a') as fh:
    for o in out:
        fh.write(o + '\n')
print(len(out))
