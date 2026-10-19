import hashlib, json, os, datetime
Q = '/Users/beom/work/git/NaruServe/kiwi/orchestrator/2026-10-19.NaruServe.naruserve-v1/lanes/lane-2026-10-19.naruserve.naruserve-v1-wave-4/deferred-srs-queue.jsonl'
RUN = '2026-10-19.naruserve.naruserve-v1-wave-4-2'
NOTE = 'command: ./gradlew build; result: pass (exit 0)'
T = 'NaruServe-api/src/test/java/com/snoworca/naruserve/'
K = 'NaruServe-api/src/test/kotlin/com/snoworca/naruserve/'
L = T + 'AppLifecycleApiTest.java'
C = T + 'ConsoleLoggerTest.java'
A = T + 'AccessLogOptionsTest.java'
KT = K + 'AppLifecycleKotlinTest.kt'
ev = {
    'IR-APP-004': [
        (L, 'sdsAc1_IR_APP_004_AC1_registryAndContextDeclareExactlyTheirMethods', 'AC-1'),
        (L, 'sdsAc1_IR_APP_004_AC2_getMissThrowsNamingTheTypeWhileGetOrNullAndContainsReportAbsence', 'AC-2'),
        (T + 'HttpApiSurfaceTest.java', 'sdsAc2_IR_APP_004_AC3_requestDeclaresApplicationAndGenericService', 'AC-3'),
        (T + 'HttpApiSurfaceTest.java', 'sdsAc2_IR_HTTP_001_AC3_laterWaveMethodNamesAreAbsent', 'AC-3'),
        (L, 'sdsAc3_IR_APP_004_AC4_typedNullRemovesNullArgumentsThrowAndWrongTypeIsClassCast', 'AC-4'),
        (L, 'sdsAc4_IR_APP_004_AC5_onlyGetOrNullAndTheAttributeReadAndValueAreNullable', 'AC-5'),
        (KT, 'SDS-AC-4 IR-APP-004 AC-5 register and service are non-null in Kotlin', 'AC-5'),
    ],
    'IR-APP-005': [
        (L, 'sdsAc5_IR_APP_005_AC1_AC2_appStateOrderAndLifecycleCallbackShape', 'AC-1'),
        (L, 'sdsAc5_IR_APP_005_AC1_AC2_appStateOrderAndLifecycleCallbackShape', 'AC-2'),
        (L, 'sdsAc6_IR_APP_005_AC4_badPortTimeoutUnitAndNullCallbackThrowBeforeAnyStateChange', 'AC-2'),
        (L, 'sdsAc5_IR_APP_005_AC3_serverHandleHasPortAddressStateReadingTheLiveState', 'AC-3'),
        (L, 'sdsAc6_IR_APP_005_AC4_badPortTimeoutUnitAndNullCallbackThrowBeforeAnyStateChange', 'AC-4'),
        (L, 'sdsAc7_IR_APP_005_AC5_appStartExceptionIsUncheckedAndCarriesTheCheckedCause', 'AC-5'),
        (KT, 'SDS-AC-8 IR-APP-005 AC-6 onStart, listen(0), port, state and shutdown without DSL', 'AC-6'),
    ],
    'IR-APP-006': [
        (C, 'sdsAc9_IR_APP_006_AC1_loggerHasExactlySixMethodsAndLogLevelOrder', 'AC-1'),
        (C, 'sdsAc9_IR_APP_006_AC2_defaultThresholdInfoWritesInfoToOutAndWarnErrorToErr', 'AC-2'),
        (C, 'sdsAc9_IR_APP_006_AC2_traceThresholdWritesAllFiveLevels', 'AC-2'),
        (C, 'sdsAc9_IR_APP_006_AC2_errorWithThrowableWritesTheStackTraceAfterTheLine', 'AC-2'),
        (C, 'sdsAc9_IR_APP_006_AC2_timestampOffsetFollowsTheDefaultZoneAtWriteTime', 'AC-2'),
        (C, 'sdsAc9_IR_APP_006_AC2_timestampDigitsStayAsciiUnderAnArabicLocale', 'AC-2'),
        (C, 'sdsAc10_IR_APP_006_AC3_closeAndFlushFlushBothStreamsAndLeaveThemOpen', 'AC-3'),
        (L, 'sdsAc11_IR_APP_006_AC4_loggerSetterReturnsTheAppAndNullThrows', 'AC-4'),
        (C, 'sdsAc10_IR_APP_006_AC5_consoleLoggerIsPublicFinalAndReferencesNoSlf4jOrAndroidType', 'AC-5'),
    ],
    'IR-APP-007': [
        (A, 'sdsAc12_IR_APP_007_AC1_optionChainReturnsTheSameOptionsAndAccessLogReturnsTheApp', 'AC-1'),
        (A, 'sdsAc12_IR_APP_007_AC2_defaultsAndTwoCallsAccumulateOnTheOneOptionsInOrder', 'AC-2'),
        (A, 'sdsAc13_IR_APP_007_AC3_nullConfigurerLevelAndExcludeThrowNullPointerException', 'AC-3'),
        (A, 'sdsAc14_IR_APP_007_AC4_configurerIsFunctionalWithOneConfigureMethod', 'AC-4'),
        (KT, 'SDS-AC-14 IR-APP-007 AC-4 accessLog trailing lambda compiles and runs', 'AC-4'),
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
