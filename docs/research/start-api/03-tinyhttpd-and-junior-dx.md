# 03. TinyHttpd 결합면과 주니어 첫 5분 — `start` API 연구

작성: 연구 담당(코드·spec·build 파일 수정 없음). 실험 산출물은 `/private/tmp/claude-501/-Users-beom-work-git-NaruServe/d80056f3-cc64-48fd-af47-0ccf9de56a5d/scratchpad/` 아래에만 있다.

표기 규칙
- **[확인]** 이 세션에서 소스를 읽었거나 실제로 실행해 본 사실. 근거(파일:줄, 실행 결과)를 붙인다.
- **[추정]** 일반 지식이나 추론이고 이 세션에서 확인하지 못했다.
- **(미확정)** 사양서에 이름이 없어 새로 정해야 하는 것. 결정은 하지 않고 추천만 쓴다.
- 관련 요구 ID: FR-ENGINE-002(루프백 기본 바인드·Host 허용 목록), FR-ENGINE-006(keeper 스레드), FR-ENGINE-011(어댑터), IR-APP-005(`listen`/`ServerHandle`). `start`는 이 ID들에 없으므로 구현 전에 SRS 요구를 먼저 만들어야 한다(CLAUDE.md의 SpecKiwi 규칙). 이 문서는 구현이 아니라 연구다.

---

## 1. 요약

1. **`start`가 줄여야 할 가장 큰 문제는 "아무 출력이 없다"와 "Ctrl+C가 서버를 그냥 죽인다"이다.** [확인] 현재 `RestApp.create()` + `listen(3000)`은 시작 로그를 한 줄도 찍지 않고(요청 접근 로그만 나온다), shutdown hook이 없어 SIGTERM 때 처리 중이던 요청이 끊기고 `onStop`이 실행되지 않는다(실행 결과 curl rc=52, `onStop` 미실행).
2. JVM이 main 종료 후에도 살아 있는 문제는 **이미 해결돼 있다.** [확인] `KeeperThread`(비데몬 1개)가 `listen()` 성공부터 `shutdown`까지 JVM을 붙든다. 그래서 `start`를 블로킹으로 만들 필요가 없다.
3. 추천 1순위는 **(A) `RestApp.start([port,] app -> ...)`: 설정 람다를 받고 블로킹 없이 `ServerHandle`을 돌려주며, 시작 로그 한 줄과 JVM 한정 자동 shutdown hook을 포함**한다. `listen`은 부작용 없는 저수준 경로로 그대로 둔다. 2순위 C(인스턴스 `app.start`), 3순위 B(블로킹 `run`), 4순위 D(`start(Class)` 스캔형, Android 부적합).
4. 기본값 추천: 포트는 명시 인자 > 환경변수 `PORT` > 3000, 바인드는 `127.0.0.1` 유지(LAN은 람다 안에서 `bindAddress("0.0.0.0")`로 명시), hook은 JVM에서만 자동 등록(유예 5초), 시작 로그는 `Listening on http://127.0.0.1:3000 (this device only) ...` 형태. 보안 기본값(FR-ENGINE-002)은 하나도 약화하지 않는다.
5. 주니어가 처음 부딪히는 실패 중 **현재 메시지가 불친절한 것**: 포트 사용 중(메시지에 포트·주소가 없다), 커스텀 호스트 421(본문이 generic하고 서버 로그에도 안 남는다), 시작 로그 부재.
6. Android: `start`는 API 21 규칙(`CompletableFuture`·`Optional`·`java.nio.file` 금지)을 지키면 그대로 쓸 수 있다. 다만 **UI 스레드에서 부르면 `onStart` 콜백과 바인드가 UI 스레드에서 돈다.** hook은 Android에서 의미가 없으므로 건너뛰어야 한다. 별도 비동기 경로(`startAsync` → `Future`)는 필요하지만 첫 구현의 필수는 아니다.
7. Kotlin: `RestApp.start(3000) { app -> ... }`는 후행 람다로 그대로 컴파일된다. [확인] Java 인터페이스 정적 메서드 + `Configurer<RestApp>` 모양을 스크래치에서 Kotlin 2.2.0으로 컴파일했다. `NaruServe-kotlin`에 둘 것은 당장 없다.
8. **선행 장애물(별개 발견)**: [확인] TinyHttpd가 3.0.0으로 오르며 Gradle group이 `com.snoworca.tinyhttpd`(0.1.0-SNAPSHOT)에서 `com.snoworca`(3.0.0)로 바뀌어, 현재 NaruServe checkout은 TinyHttpd HEAD(c000eb1)에 `includeBuild`해도 `tinyhttpd-core`가 **FAILED로 해석된다**(스크래치 복사본에서 재현). 주니어 첫 5분의 0단계가 이미 막혀 있다. 좌표만 바꾼 복사본에서는 어댑터가 3.0.0 소스에 그대로 컴파일된다.

---

## 2. TinyHttpd ↔ NaruServe 설정 대응표

TinyHttpd 3.0.0(`/Users/beom/work/git/TinyHttpd`, HEAD c000eb1, `group='com.snoworca' version='3.0.0'`)의 `TinyHttpd.Builder`와 `TinyHttpd` 런타임 메서드를 기준으로 한다. 어댑터의 매핑 코드는 `NaruServe-tinyhttpd/.../TinyHttpdAdapter.java:63-124`(`bind`, `configure`)다.

### 2.1 Builder 옵션

| TinyHttpd 3.0.0 옵션 (기본값) | NaruServe 노출 | NaruServe 기본값·매핑 | 근거 |
|---|---|---|---|
| `port(int)` (0) | `listen(port)` 인자 | 0..65535, 0이면 빈 포트 | `DefaultRestApp.java:464-467`, `TinyHttpdAdapter.java:99` |
| `listen(addr, port, tls)` 다중 리스너 (없음) | **숨김** | 리스너 하나만 | `configure()`가 `port`/`bindAddress`만 호출 |
| `bindAddress(InetAddress)` (**null = 모든 인터페이스**) | `ServerOptions.bindAddress(String)` | **`127.0.0.1`** 로 덮어씀. 어댑터가 `InetAddress.getByName`으로 항상 명시 전달 | `ServerSettings.java` Builder 기본값, `TinyHttpdAdapter.java:100`, FR-ENGINE-002 AC-1 |
| `tls(TlsConfig)`, `TlsConfig` | **숨김** | 어댑터는 TLS를 열지 않음. 문서는 "TLS 종단 리버스 프록시 + `trustProxy(true)`"를 안내 | `docs/api/defaults.md` "TLS" 항목 |
| `daemon(boolean)` (**true**: acceptor·worker는 daemon, watchdog은 항상 daemon) | **숨김** | 호출하지 않아 TinyHttpd 기본(daemon) 그대로. 대신 `KeeperThread`(비데몬)가 JVM을 붙듦 | `TinyHttpd.java` Builder `daemon=true`, `HttpServer.java:163,574`, `KeeperThread.java` |
| `hostCheck(boolean)` (true) | **숨김** (항상 켬) | 끌 수 없음 | `configure()`가 호출 안 함 |
| `allowedHosts(String...)` (없음. `localhost`와 IP 리터럴은 내장 허용) | `ServerOptions.allowedHosts(String...)` | 목록 + (바인드 주소가 IP가 아니면 그 이름) 추가. 미등록 DNS 이름은 **421** | `TinyHttpdAdapter.java:118-122`, `HostAllowlistFilter.java` |
| `threadFactory(ThreadFactory)` (null) | 어댑터 내부 | `naruserve-<port>-worker-<n>`, daemon, stack 256 KiB, uncaught는 앱 로거 ERROR | `WorkerThreadFactory.java` |
| `handler`, `filter`, `errorHandler`, `logger` | 어댑터 내부 | handler=`TinyHttpdExchange` 브리지, errorHandler=`JsonErrorHandler`(표준 오류 JSON), logger=`TinyLogBridge`(앱 로거로 위임), filter 추가 없음 | `TinyHttpdAdapter.java:70,102,103` |
| `serverHeader(String)` (null=보내지 않음) | 숨김 | 기본 그대로(헤더 없음) | |
| `inputBufferBytes` (8192) | 숨김(파생) | `max(8192, maxRequestLineLength+2)` | `TinyHttpdAdapter.java:107` |
| `outputBufferBytes` (8192) | 숨김 | 기본 그대로 | |
| `maxRequestLineBytes` (8190) | `maxRequestLineLength` | 기본 8190, 범위 1..65534 | `ServerOptions.java` |
| `maxHeaderBytes` (16 KiB) | `maxHeaderSize` | 16384, 1..1 MiB | |
| `maxHeaderCount` (100) | `maxHeaderCount` | 100, 1..10000 | |
| `maxRequestBodyBytes` (**-1 = 무제한**) | **숨김** | 엔진은 무제한으로 두고 NaruServe 코어가 `jsonLimit`/`formLimit`/`multipart`로 한도를 건다 | `TinyHttpdAdapter.java:97` 주석 |
| `maxDrainBytes`(64 KiB), `maxChunkSizeLineBytes`(1024), `maxTrailerBytes`(8 KiB), `maxRequestsPerConnection`(1000), `lingerTimeout`(2 s) | 숨김 | TinyHttpd 기본 | |
| `idleTimeout`(15 s), `idleTimeoutUnderLoad`(2 s) | **숨김** | keep-alive 유휴 시간은 조정 불가 | |
| `headerTimeout`(10 s) | `headerTimeout(t, unit)` | 10 s, 1 ms..1 h | |
| `bodyReadTimeout`(30 s), `writeTimeout`(30 s) | 같은 이름 | 30 s | |
| `minRequestBodyRate` (**0 = 끔**) | `minBodyRate` | **240 B/s** (켜짐). 1..100 MiB/s라 **끌 수 없음** | `ServerSettings.java`, TinyHttpd 46b973e 커밋 |
| `queueWaitTimeout`(5 s), `workerKeepAlive`(60 s) | 숨김 | TinyHttpd 기본 | |
| `shutdownGrace` (5 s) | **숨김, 사용 안 함** | `app.shutdown(timeout, unit)`의 남은 시간을 `stop(graceMillis)`로 전달. NaruServe에는 **기본 유예 시간이 없다**(호출자가 매번 지정) | `TinyHttpdAdapter.java:167-180` |
| `maxWorkers` (64) | `maxWorkers` (64) | 엔진에는 `maxWorkers + maxSseStreams`(기본 192)를 전달 | `TinyHttpdAdapter.java:113` |
| `maxConnections` (128) | `maxConnections` (**512**) | 기본이 4배. `maxWorkers+maxSseStreams` 이상이어야 하며 어기면 `listen()`이 `IllegalStateException` | `DefaultRestApp` 동결 단계 |
| `maxConnectionsPerAddress` (32) | 같은 이름 (**256**) | | |
| `workerStackBytes` (256 KiB) | 숨김 | `WorkerThreadFactory`가 256 KiB 고정 | |
| `sendFileBufferBytes`(64 KiB), `sendFileBuffers`(8) | 숨김 | 어댑터의 `sendFile`은 8 KiB 버퍼 복사를 직접 구현 | `TinyHttpdFeatures.java` |
| `maxEventStreams` (64) | `maxSseStreams` (**128**) | 어댑터가 엔진 값도 같은 수로 맞춤. SSE 카운트는 NaruServe 자체 `Slots` | `TinyHttpdAdapter.java:68,114` |
| `eventQueueEvents`(64), `eventQueueBytes`(64 KiB) | 숨김 | NaruServe 자체 SSE 큐 사용(`app.sse(o -> o.sendQueueSize)`) | |
| `maxUpgradedConnections` (16) | 같은 이름 (**128**) | | |

`UNLIMITED`(= `Integer.MAX_VALUE`, 타임아웃이면 약 24.8일)와 `UNLIMITED_LONG`은 **NaruServe가 한 번도 넘기지 않는다.** [확인] 모든 `ServerOptions` 설정은 범위 검사를 하고 최대값이 있으며(`docs/api/defaults.md` 첫 문단), 엔진 쪽에서 무제한인 것은 `maxRequestBodyBytes`뿐이다(코어가 대신 거절).

### 2.2 `TinyHttpd` 런타임 메서드

| TinyHttpd 3.0.0 | NaruServe | 비고 |
|---|---|---|
| `start()` (호출 스레드에서 바인드) | `listen()` 안에서 호출 | 바인드가 호출 스레드에서 일어남. Android에서 중요(§5) |
| `stop()` / `stop(graceMillis)` | `shutdown(timeout, unit)` → 어댑터 `stop` | 단계 1~7은 `docs/api/app.md` shutdown |
| `awaitTermination(ms)` | 어댑터 `release()` 안에서만(1초 한도) | **앱 수준 `await` API 없음** → 블로킹 `start`/`await()`가 필요하면 새로 만들어야 함 |
| `isAlive()`, `getStats()`, `getConfig()`, `getPort(i)` | `boundPort()`만 내부 사용 | `ServerHandle`은 `port()`·`address()`·`state()`뿐 |
| `setMaxRequestBodyBytes`, `setHeaderTimeout`, `setBodyReadTimeout`, `setMinRequestBodyRate`, `setWriteTimeout`, `setIdleTimeout`, `setIdleTimeoutUnderLoad`, `setQueueWaitTimeout`, `setLingerTimeout`, `setWorkerKeepAlive`, `setShutdownGrace`, `setWorkerLimits`, `setMaxEventStreams`, `setMaxUpgradedConnections` | **전부 숨김** | NaruServe는 설정을 `listen()` 때 동결한다(`serverOptions.freeze`, `DefaultRestApp.java:497`). 실행 중 조정 불가 |

### 2.3 대응표에서 나온 DX 관찰

- [확인] TinyHttpd 기본은 "모든 인터페이스 바인드"이고 NaruServe는 "루프백만"이다. TinyHttpd 문서를 먼저 읽은 사람은 두 기본값이 반대라는 점에 놀랄 수 있다. 같은 이름의 `bindAddress`가 한쪽은 `InetAddress`/null, 다른 쪽은 `String`/`127.0.0.1`이다.
- [확인] `daemon`과 `shutdownGrace`는 숨겨져 있어서 TinyHttpd README의 "독립 실행 앱이 바로 종료됨 → `.daemon(false)`" 해법이 NaruServe에는 적용되지 않는다(필요 없다). 문서에서 이 차이를 한 줄로 알려 주면 혼란이 준다.
- [확인] 대부분의 한도가 TinyHttpd 기본값보다 크다(연결 512 vs 128, SSE 128 vs 64, 업그레이드 128 vs 16). 저사양 Android에서는 `defaults.md`가 "직접 낮춰 보라"고 안내한다.

---

## 3. 주니어 실패 시나리오표

모든 "현재 동작"은 NaruServe 빌드 산출물(`NaruServe/build/libs/*.jar`)을 TinyHttpd 3.0.0 jar와 함께 JDK 17에서 실행한 결과다(스크래치 `exp/Exp.java`, `exp/Sig.java`, `exp/Late.java`). 클래스는 `--release 8`로 컴파일했다.

| # | 시나리오 | 원인 | 현재 동작 [확인] | `start`로 개선하는 방법 |
|---|---|---|---|---|
| 1 | **포트 사용 중** | 다른 프로세스, 이전 실행이 안 죽음(Ctrl+C가 JVM을 못 끈 IDE 세션), 3000을 쓰는 다른 개발 서버 | `AppStartException: app start failed: java.net.BindException: Address already in use`(cause에 같은 `BindException`). **메시지에 포트와 바인드 주소가 없다.** 앱 상태는 `FAILED`를 거쳐 `TERMINATED`, 소켓·스레드 정리됨, keeper는 시작 안 됨 → main이 예외로 끝나 JVM도 종료(스택 트레이스 출력). `DefaultRestApp.java:546-554`가 checked 원인을 감쌈 | 같은 예외 타입·cause를 유지하되 메시지를 "Port 3000 on 127.0.0.1 is already in use. Stop the other process, or start on another port (`start(0)` picks a free one, or set `PORT`)" 식으로 바꾼다. 자동으로 다른 포트로 우회하지 **않는다**(조용한 우회는 curl 대상이 달라져 더 혼란) |
| 2 | **main이 끝났는데 서버가 계속 돎 / 또는 반대로 JVM이 바로 종료됨** | TinyHttpd는 기본 daemon이라 main만 끝나면 JVM이 종료되는 것이 원래 동작 | NaruServe에서는 **JVM이 살아 있다**(`Exp hello`로 main 종료 3초 뒤 `kill -0` 성공, `JvmLifetimeTest`와 FR-ENGINE-006 AC-5도 동일). 문제는 반대로 **터미널이 아무 출력 없이 멈춘 것처럼 보인다**는 점 | 시작 로그 한 줄(§4.5)로 "떠 있다"를 알려 준다. 블로킹으로 바꿀 필요는 없다 |
| 3 | **다른 기기·Android 에뮬레이터에서 접속 불가** | 기본 바인드 `127.0.0.1`(FR-ENGINE-002) | 기본 바인드로 LAN IP(192.168.0.115) 접속 → 연결 거부(curl `000`). `bindAddress("0.0.0.0")`로 바꾸면 같은 LAN IP로 200. `ServerHandle.address()`는 `0.0.0.0` 문자열 그대로라 `http://0.0.0.0:3000`을 브라우저에 쓰면 일부 OS에서 열리지 않음 [추정] | 시작 로그에 "(this device only; LAN 노출은 `bindAddress` 명시)" 힌트를 넣는다. 와일드카드면 `localhost`와 LAN 주소 후보를 보여 준다. 기본은 유지. Android: 에뮬레이터 안의 서버에 호스트 PC에서 붙으려면 `adb forward`가 필요할 가능성이 큼 [추정, 미검증]; 호스트 서버에 에뮬레이터가 붙는 `10.0.2.2`는 IP 리터럴이라 Host 검사를 통과한다 |
| 4 | **커스텀 호스트 이름에서 HTTP 421** | `Host: myphone.local` 같은 DNS 이름이 허용 목록에 없음(DNS rebinding 방어) | `Host: myphone.local` 요청이 `421 Misdirected Request`, 본문 `{"error":{"code":"HTTP_421","message":"Misdirected Request","requestId":"..."}}`. **어떤 호스트가 거절됐는지·어떻게 고치는지 본문에도 서버 로그에도 없다.** 접근 로그에 이 요청이 남지 않음(필터가 앱 앞단. FR-ENGINE-002 AC-4가 `onError`·접근 로그에 기록하지 않도록 못박음). 리버스 프록시가 원래 `Host`를 전달하는 구성에서도 같은 증상 [추정] | `start` 자체로는 못 줄이지만 어댑터 개선이 필요하다: 거절된 호스트 이름을 **호스트당 1회 WARN**으로 로그에 남기고 해법(`allowedHosts("myphone.local")`)을 적는다(요청 본문에 힌트를 넣는 것은 표준 오류 본문 계약을 바꾸므로 비추천). 설정 람다 안에서 `app.server(o -> o.allowedHosts(...))`로 해결 가능 |
| 5 | **종료 시 shutdown hook 부재** | NaruServe는 hook을 등록하지 않음. 예제만 수동 등록(`examples/java/.../JavaExample.java:74`, `docs/api/app.md` 예제) | hook 없이 SIGTERM: 5초짜리 요청 처리 중 curl이 빈 응답(rc=52), `onStop` **미실행**(`ctx.own`으로 등록한 DB 등 리소스도 닫히지 않음). hook을 직접 넣으면 요청이 `done`으로 끝나고 `onStop`이 실행됨 | `start`가 JVM에서 자동으로 hook(`app.shutdown(5초)`)을 등록한다. `shutdown`은 여러 번 불러도 안전하다고 문서화돼 있어(`docs/api/app.md`) 수동 `shutdown`과 겹쳐도 된다 |
| 6 | **예외가 어디로 나가는가** | | `listen`/`start` 실패는 모두 **unchecked로 호출 스레드에 던져진다**: checked 원인은 `AppStartException`으로 감쌈(포트·주소 해석 실패 `UnknownHostException`도 `AppStartException`), unchecked는 그대로(범위 밖 포트 `IllegalArgumentException`, 라우트 충돌 `RoutingConfigException: GET /x conflicts with GET /x`, `maxConnections` 부족 `IllegalStateException`). 오타 난 호스트 이름은 보안 경고 WARN을 먼저 찍고 실패하는 점도 어색함(`listening on no-such-host.invalid, reachable beyond this device`). main에서는 스택 트레이스+종료 코드 1, **Android 작업 스레드에서는 잡지 않으면 앱 프로세스가 죽는다**(`docs/api/lifecycle.md`). 요청 처리 중 핸들러 예외는 500 표준 JSON + ERROR 로그(스택 포함) | 예외 타입은 그대로 두고(삼키지 않는다. 테스트·호출자가 구분해야 함) **메시지를 처방형으로** 만든다(Spring Boot의 "Description / Action" 식은 [추정] 일반 지식). 포트 범위·라우트 충돌 같은 구성 오류는 메시지가 이미 구체적이다 |
| 7 | **로그가 안 보임** | 시작 로그가 없고, 로거 교체·임계값에 따라 접근 로그도 사라짐 | 기본 `ConsoleLogger`(INFO)에서 요청마다 `... INFO GET /hello 200 7ms ...` 접근 로그는 stdout에 나온다. **시작·종료 로그는 없다**(LAN 바인드일 때 WARN 한 줄만 예외). Android에서는 기본 로거가 Logcat 태그 `NaruServe`(`DefaultAppFactory.defaultLogger`)라 Android Studio 필터에 따라 안 보일 수 있다 | 시작·종료 로그를 앱 로거(INFO)로 낸다(§4.5). 로거를 WARN 이상으로 올린 사용자는 의도적으로 안 보는 것이므로 존중 |
| 8 | **포트 0일 때 실제 포트를 어떻게 아나** | `listen(0)`은 빈 포트를 고른다 | `ServerHandle.port()`가 실제 포트를 돌려준다(실행 `PORT=58180`). 하지만 시작 로그가 없어서 **직접 `println`하지 않으면 알 수 없다**. `ServerHandle`에 URL 접근자가 없다 | 시작 로그가 실제 포트를 찍는다. `ServerHandle.url()` (미확정) |
| 9 | **설정을 `listen` 뒤에 함** | 라우트 등록 순서 실수 | `IllegalStateException: get not allowed in state RUNNING`, `listen() not allowed in state RUNNING`(둘째 호출). 메시지는 명확하다 | `start(port, app -> ...)`는 설정이 람다 안에서 끝난 뒤에만 바인드하므로 **순서 실수가 구조적으로 불가능**하다 |
| 10 | **의존성 해석 실패(0단계)** | TinyHttpd group 변경: `com.snoworca.tinyhttpd` 0.1.0-SNAPSHOT → `com.snoworca` 3.0.0(TinyHttpd 커밋 86272ec) | NaruServe는 versionless `com.snoworca.tinyhttpd:tinyhttpd-core`를 선언해 `includeBuild` 치환에 의존(`build.gradle:61-64`, `settings.gradle:26`의 검증 커밋 `46b973e`). TinyHttpd HEAD를 가리키면 `:NaruServe-tinyhttpd:compileClasspath`에서 `tinyhttpd-core FAILED`, `tinyhttpd-websocket FAILED`(스크래치 복사본, `--offline`). 좌표를 `com.snoworca:tinyhttpd-*`로 바꾼 복사본에서는 `:NaruServe-tinyhttpd:compileJava`가 성공 | `start`와 무관한 선행 과제다. 주니어 첫 5분은 이 단계에서 이미 실패한다. README는 "NaruServe는 mavenLocal 발행만, TinyHttpd 체크아웃 `includeBuild` 필수"라고 적어 0단계가 무겁다 |

---

## 4. 기본값 정책 제안

### 4.1 포트

| 후보 | 장점 | 단점 |
|---|---|---|
| 항상 명시(기본값 없음) | 숨은 동작 없음 | 주니어가 매번 숫자를 정해야 함 |
| 고정 3000 | Express 관행. README·`examples/`·`docs/api` 전부 3000을 이미 쓴다 | 다른 개발 서버와 자주 충돌 |
| 환경변수 `PORT` | PaaS·컨테이너 관행 [추정]. 코드를 안 고치고 바꿈 | 숨은 입력원. Android에는 의미 없음 |
| 시스템 프로퍼티 `naruserve.port` | 명시적 이름 | 입력원이 늘어 디버깅이 어려워짐 |

**추천**: 포트 인자를 받는 오버로드는 그 값만 쓴다. 포트 인자가 없는 오버로드만 `PORT` 환경변수 → 3000 순으로 본다. 어느 쪽에서 왔는지를 시작 로그에 적는다(`port 3000 (default)`, `port 8081 (from PORT)`). `PORT`가 숫자가 아니거나 범위 밖이면 조용히 3000으로 폴백하지 말고 `IllegalArgumentException`으로 실패한다. 시스템 프로퍼티는 v1 `start`에서는 넣지 않는 쪽을 추천한다(입력원 최소화). 포트가 사용 중일 때 **자동으로 다른 포트를 고르지 않는다.**

### 4.2 바인드 주소

**`127.0.0.1` 유지.** FR-ENGINE-002 AC-1~3(루프백 기본, LAN은 명시, 비루프백이면 무조건 WARN)을 그대로 적용한다.
- LAN 노출은 설정 람다 안에서 `app.server(o -> o.bindAddress("0.0.0.0"))`로 명시한다. `start`가 새 옵션 타입을 만들 필요가 없다는 것이 설정 람다 방식(A)의 장점이다.
- `start(host, port, config)` 오버로드는 Express `listen(port, host)`에 대응하는 선택지다(미확정). 문자열 `"0.0.0.0"`을 호출부에 적게 하므로 명시성은 유지된다.
- **환경변수로 바인드 주소를 바꾸는 입력원은 만들지 않는다.** 주변 환경 변수 하나로 서버가 LAN에 열리면 FR-ENGINE-002의 "명시적으로만 열린다" 원칙이 약해진다. 필요하다면 이름을 `NARUSERVE_` 접두로 한정하는 방안을 따로 결정해야 한다(미확정).
- `lan()` 같은 불리언 편의 메서드는 추천하지 않는다(WARN 계약과 의미가 겹침).

### 4.3 블로킹 여부

**블로킹하지 않고 `ServerHandle`을 돌려준다.** 이유:
1. [확인] 비블로킹이어도 keeper가 JVM을 붙들어 Ctrl+C 전까지 프로세스가 산다. CLI 체감은 블로킹과 같다.
2. 블로킹이면 포트·핸들을 돌려줄 수 없어 `start(0)` + 테스트·Android에 쓸 수 없다.
3. 블로킹이 필요한 사람을 위해 `ServerHandle.await()`(앱이 `TERMINATED`가 될 때까지 대기) (미확정)를 둔다. [확인] 현재 앱 수준 `await`가 없으므로 `AppLifecycle`에 대기 수단을 추가해야 한다(작은 구현 과제).

### 4.4 shutdown hook 자동 등록

**JVM에서는 자동 등록, Android에서는 건너뜀.** 근거는 시나리오 5. 세부 제안:
- hook 본문은 `app.shutdown(5, SECONDS)`. 5초는 TinyHttpd `shutdownGrace` 기본(5 s)과 `examples/`의 값과 같다. `docs/api/app.md` 일부 예제는 10초라 문서 통일이 필요하다.
- [확인] `shutdown`은 timeout에 약 3초를 더한 시간 안에 돌아오지만 `onStop`·리소스 닫기·로거 flush는 시간 제한이 없다(`docs/api/lifecycle.md` 종료 순서). hook이 이 때문에 JVM 종료를 오래 붙들 수 있음을 문서에 적는다.
- 수동 `handle.stop()`/`shutdown`이 끝나면 `Runtime.removeShutdownHook`으로 hook을 제거한다(이미 JVM 종료 중이면 `IllegalStateException`을 삼킴). 테스트에서 앱을 여러 개 띄울 때 hook이 쌓이지 않게 하려는 것이다.
- 끄는 스위치는 따로 만들지 않는다. hook이 싫은 호출자(테스트·라이브러리 내장)는 부작용 없는 기존 `RestApp.create()` + `listen()`을 쓴다. 두 경로의 차이를 문서 한 곳에 대비표로 둔다.
- Android 판별은 `DefaultAppFactory.defaultLogger`가 이미 쓰는 `Class.forName("android.util.Log")` 방식과 같은 리플렉션이면 된다. D-385(`android.*` 컴파일 참조 금지)에 걸리지 않는다. 다만 `start`가 `NaruServe-api`에 놓이므로 판별 코드를 api 내부에 두는지 SPI로 빼는지는 (미확정).

### 4.5 시작 로그

앱 로거 INFO로 한 줄(기존 `ConsoleLogger` 형식 `<timestamp> <LEVEL> <message>` 유지). 제안 문구:

```
2026-10-23T09:00:52.430+0900 INFO Listening on http://127.0.0.1:3000 (this device only). Press Ctrl+C to stop.
2026-10-23T09:00:52.430+0900 INFO Listening on http://0.0.0.0:3000 - open http://localhost:3000 or http://<this-device-ip>:3000      # LAN 바인드 시. 직전에 기존 WARN이 한 줄 나감
2026-10-23T09:00:52.430+0900 INFO Listening on http://127.0.0.1:8081 (port from PORT)                                                  # 입력원이 기본값이 아닐 때
```

- 먼저 `Listening on http://<주소>:<실제 포트>`로 시작한다. TinyHttpd README와 `examples/`가 이미 같은 문구를 쓴다.
- 보여 줄 것: URL, 루프백 여부 힌트, 포트 입력원(명시가 아니면), 종료 방법(JVM에서만).
- 보여 주지 않을 것: 헤더·쿠키·토큰 등 민감값(FR-ENGINE-002 AC-3와 같은 원칙), 라우트 표(DEBUG 로그 옵션으로만).
- 종료 시 `Stopping (grace 5s)` 한 줄을 hook이 낸다. Ctrl+C를 눌렀을 때 반응이 있어야 안심한다.
- `listen()` 본체에는 로그를 넣지 않고 `start`에서만 낸다. `listen`을 쓰는 기존 사용자·테스트의 로그 개수를 바꾸지 않으려는 것이다.

### 4.6 `ServerHandle` 확장(미확정)

현재 `port()`, `address()`, `state()`뿐이다. `start`가 의미를 가지려면 다음이 필요하다.

| 메서드 | 용도 | 주의 |
|---|---|---|
| `url()` | `http://127.0.0.1:3000`. 와일드카드면 `http://localhost:<port>` | 문자열 규칙 결정 필요 |
| `stop()` (유예 5초) / `stop(timeout, unit)` | `app.shutdown`의 핸들 쪽 짧은 이름. hook 제거 포함 | Android에서는 메인 스레드 금지(`shutdown`과 동일) |
| `await()` | 블로킹이 필요한 사용자용 | `AppLifecycle` 변경 필요 |
| `AutoCloseable` 구현 | 테스트의 try-with-resources, Kotlin `use` | **주니어가 `try (ServerHandle h = start(...)) { }`를 장기 서버에 쓰면 블록 끝에서 바로 종료됨.** 이 위험이 이점보다 큰지 결정 필요 |

---

## 5. Android(API 21)·Kotlin 관점

### 5.1 Android

- **UI 스레드에서 `start`를 부르면**: [확인] `listen()`은 호출 스레드에서 (1) 사용자 설정 람다, (2) `onStart` 콜백(DB 열기 등), (3) 라우트 검사, (4) TinyHttpd `start()`의 소켓 바인드를 차례로 실행한다(`DefaultRestApp.java:483-510`, `TinyHttpd.java`의 `start()` 주석 "Binds the listening socket on the calling thread"). NaruServe 문서는 이미 "Android에서는 메인 스레드에서 호출하지 마세요"라고 적었다(`app.md`, `lifecycle.md`). **Service.onCreate()도 메인 스레드에서 돈다**는 점이 흔한 함정이다(Android 일반 지식 [추정]). 바인드 자체가 `NetworkOnMainThreadException`을 던지는지는 확인하지 못했다(§8). `onStart`에서 디스크·DB를 만지면 StrictMode 위반이나 ANR 위험이 크다 [추정].
  - 제안: `start`가 Android 메인 스레드를 리플렉션(`Looper.getMainLooper().getThread()`)으로 감지하면 **예외 대신 WARN 로그**를 남긴다. 바인드가 실제로 막히는지 확인하지 못했기 때문이다. (미확정)
- **블로킹 `start`는 Android에서 위험하다.** 메인 스레드에서 블로킹하면 즉시 ANR이다. 그래서 B(블로킹 `run`)는 Android 공용 API로 부적합하고, A는 비블로킹이라 문제가 없다.
- **shutdown hook은 Android에서 의미가 없다.** 앱 프로세스는 시스템이 종료하며 JVM 종료 경로를 타지 않는 경우가 많다 [추정, 미검증]. `docs/api/lifecycle.md`도 "프로세스가 강제 종료되면 `onDestroy`도 `onStop`도 실행되지 않을 수 있다"고 적었다. 수명 주기는 `Service.onDestroy`에서 `shutdown`을 부르는 것이 정답이다. 그래서 Android에서는 hook 자동 등록을 건너뛰는 것을 추천한다.
- **keeper 스레드**는 Android에서도 무해하다(JVM 종료와 무관하게 프로세스는 시스템이 관리). `shutdown` 때 종료된다는 점만 지키면 된다.
- **별도 논블로킹 경로가 필요한가?** `start`는 이미 바인드 후 반환하므로 "main을 붙잡지 않음"이라는 의미의 논블로킹은 충족한다. 필요한 것은 "**호출 스레드에서 바인드·콜백을 하지 않는** 경로"이다. 선택지는 둘이다.
  1. 문서의 `ExecutorService` 레시피를 유지(현재 `lifecycle.md` 예제).
  2. `startAsync(port, config)`가 `java.util.concurrent.Future<ServerHandle>`을 돌려준다(짧게 사는 daemon 스레드 `naruserve-start`, 실패는 `ExecutionException(AppStartException)`). **`CompletableFuture`는 API 24라 Core에 금지**이므로 `Future`나 콜백이어야 한다.
  
  첫 구현의 필수는 아니다(메모리 기록상 Android 샘플은 v1 범위 밖이고 API 21 문법만 유지). 단, 새 API가 `Optional`·`java.nio.file`·`CompletableFuture`를 노출하지 않아야 animal-sniffer 검사를 통과한다.
- `RestApp.start`는 인터페이스 정적 메서드라 API 24 미만에서 desugaring이 필요하다. 기존 `RestApp.create()`가 이미 같은 형태이므로 새 위험은 아니다 [확인: `RestApp.java`의 `static RestApp create()`].
- 기본 바인드 `127.0.0.1`은 Android에서 "같은 기기의 앱·WebView만 접근"이다. TinyHttpd의 주 용도인 폰 NAS에서는 LAN 노출이 필수이므로 `bindAddress("0.0.0.0")` + 인증 미들웨어를 문서의 정식 레시피로 둘 가치가 있다. Android 9+ 평문 HTTP 정책(cleartext)과 `adb forward`는 확인하지 못했다(§8).

### 5.2 Kotlin

- [확인] 아래가 Kotlin 2.2.0(`-jvm-target 1.8`)으로 **오류·경고 없이 컴파일**됐다(스크래치 `exp/kt/Main.kt`, Java 인터페이스 `Starter`가 실제 `RestApp`/`Configurer`/`ServerHandle`을 쓰는 stand-in).
  ```kotlin
  val h = Starter.start(0) { app ->
      app.get("/hello") { _, res -> res.send("hi") }      // 마지막 식이 RestApp이어도 SAM(void)이라 무시됨
  }
  val h2 = Starter.start { app -> app.get("/x") { _, res -> res.send("x") } }   // 포트 생략 오버로드
  ```
  Java 쪽도 식 람다 `app -> app.get(...)`가 `void` SAM에 그대로 컴파일된다.
- 후행 람다는 설정 람다가 **마지막 매개변수**일 때만 성립한다. 따라서 시그니처는 `start(int port, Configurer<RestApp> config)` 순서를 지켜야 한다(`start(Configurer, int)`는 안 됨).
- CLAUDE.md의 Kotlin 규칙(콜백 `@FunctionalInterface`, 인자·반환 `@NonNull`/`@Nullable`, Kotlin 키워드 메서드명 금지)과 충돌하는 점은 없다. `start`·`stop`·`await`·`url`은 키워드가 아니다.
- **`NaruServe-kotlin`에 둘 것은 당장 없다.** 선택지:
  - 수신 객체 람다 DSL `fun naruServe(port: Int = 3000, configure: RestApp.() -> Unit): ServerHandle`(이름 미확정)은 `app.` 접두만 줄이고 "DSL 없이 쓴다"는 원칙과 방언이 둘이 된다. 사용자 요구가 생길 때까지 보류를 추천한다.
  - `ServerHandle`이 `AutoCloseable`이면 Core만으로 `use {}`가 된다(§4.6의 주의 참고).
  - `kotlin-stdlib`은 이 모듈에만 둔다는 제약은 그대로 지켜진다.
- 공개 API 변경 시 Kotlin 소스 컴파일·실행 테스트를 함께 유지해야 한다(CLAUDE.md T11). `start`를 넣으면 위 스니펫이 곧 Kotlin 회귀 테스트 후보다.

---

## 6. 후보 API 첫 5분 체험 비교

시나리오: 의존성이 이미 준비됐다고 가정(0단계는 시나리오 10 참고)하고 "Hello World를 띄우고 curl로 확인하고 Ctrl+C로 끈다". 줄 수는 `main` 본문 줄 / 파일 전체 줄(빈 줄·import 포함). 현재 방식과 A·B·C의 코드는 [확인] 가능한 형태이며 A의 Java·Kotlin은 stand-in으로 컴파일했다. B·D는 개념 코드다.

### 6.0 현재(기준선)

```java
import com.snoworca.naruserve.RestApp;

public class Hello {
    public static void main(String[] args) {
        RestApp app = RestApp.create();
        app.get("/hello", (req, res) -> res.send("hi"));
        app.listen(3000);
    }
}
```
```kotlin
import com.snoworca.naruserve.RestApp

fun main() {
    val app = RestApp.create()
    app.get("/hello") { _, res -> res.send("hi") }
    app.listen(3000)
}
```
- Java 본문 3줄/파일 9줄, Kotlin 본문 3줄/파일 7줄. 안전 종료까지 하려면 import 1 + hook 1줄이 늘어 본문 4줄/파일 11줄(Java).
- 실수 지점: 실행해도 아무 출력이 없음, hook을 모르면 Ctrl+C로 요청이 끊김, 3000 충돌 시 포트가 없는 메시지, `listen` 뒤에 라우트를 쓰면 `IllegalStateException`.

### 6.1 (A) 설정 람다 + 비블로킹 `ServerHandle` 반환

```java
import com.snoworca.naruserve.RestApp;

public class Hello {
    public static void main(String[] args) {
        RestApp.start(3000, app -> app.get("/hello", (req, res) -> res.send("hi")));
    }
}
```
```kotlin
import com.snoworca.naruserve.RestApp

fun main() {
    RestApp.start(3000) { app -> app.get("/hello") { _, res -> res.send("hi") } }
}
```
- Java 본문 1줄/파일 7줄, Kotlin 본문 1줄/파일 5줄. 포트 생략(`RestApp.start(app -> ...)`)이면 `PORT`→3000.
- 동작: 시작 로그, Ctrl+C 시 `Stopping` 로그 후 정상 종료, 포트 충돌 시 처방형 메시지.
- 실수 지점: (1) `start`와 기존 `listen`의 차이를 모름(문서 한 줄로 해결). (2) 람다 중괄호·이중 람다 가독성(Kotlin `_` 매개변수 이름). (3) `app` 변수를 밖으로 못 뺌 → `handle.stop()`이 필요. (4) Android UI 스레드 호출.

### 6.2 (B) 같은 시그니처, `run`처럼 블로킹

```java
RestApp.run(3000, app -> app.get("/hello", (req, res) -> res.send("hi")));   // 종료까지 반환하지 않음
```
- 줄 수는 A와 같다(Java 7줄, Kotlin 5줄).
- 장점: "서버가 끝날 때까지 프로그램이 안 끝난다"는 Spring `run`의 사고방식과 같고, `try/finally`로 뒷정리를 둘 수 있다.
- 단점: (1) [확인] keeper가 이미 JVM을 붙들므로 **블로킹으로 얻는 JVM 생존 이득이 없다.** (2) 포트·핸들을 돌려줄 수 없어 `start(0)`·테스트에 못 쓴다. (3) Android 메인 스레드에서 호출하면 즉시 ANR. (4) 블로킹 해제를 위한 앱 수준 `await`가 지금 없다.
- 실수 지점: 테스트·Android에서 멈춤, 뒤에 쓴 코드가 실행되지 않는 줄 모름.

### 6.3 (C) 인스턴스 메서드 `app.start(3000)`

```java
RestApp app = RestApp.create();
app.get("/hello", (req, res) -> res.send("hi"));
app.start(3000);
```
- Java 본문 3줄/파일 9줄, Kotlin 본문 3줄/파일 7줄. Express `listen`과 구조가 같아 가장 익숙하다.
- 단점: **`listen`과 `start` 두 이름이 공존**하면 "어느 것을 써야 하나"가 첫 질문이 된다. `start`에만 hook·로그가 있다는 차이를 코드만 봐서는 알 수 없다. `listen` 뒤 라우트 등록 순서 실수는 여전히 가능하다.
- 장점: `app` 변수가 남아 `app.shutdown`·`app.state()` 접근이 자연스럽고, 설정 람다가 필요 없다.

### 6.4 (D) `start(Class)` 스캔형

```java
public class Hello {
    @Get("/hello")                                  // 새 어노테이션(미확정)이 NaruServe-api에 필요
    public static void hello(Request req, Response res) { res.send("hi"); }

    public static void main(String[] args) { RestApp.start(Hello.class); }
}
```
- 본문 약 3줄/파일 약 10~11줄(import 추가). 줄 수는 늘고 "마법"이 생긴다.
- **Android 부적합 판단**: (1) 클래스패스 스캔은 dex 환경에서 표준 수단이 없다(`DexFile`은 사용 중단 방향 [추정]). (2) R8/ProGuard가 어노테이션이 달린 메서드를 제거·개명한다. (3) 어노테이션 정의·리플렉션이 Core에 새 의존을 만든다. (4) `docs/api/lifecycle.md`가 이미 "Spring 같은 의존성 주입이나 자동 스캔은 없습니다"라고 설계 방침을 못박았다. 컴파일 타임 어노테이션 프로세서로 우회할 수 있어도 별도 모듈·큰 설계 변경이다.
- 실수 지점: 어노테이션 누락 시 조용히 라우트가 0개, 접근 제한자·static 규칙, 난독화.
- 변형 D'(스캔 없는 모듈 등록): `RestApp.start(3000, UserRoutes::install)`처럼 A의 람다 자리에 **메서드 참조**를 넘기면 Spring식 구조화를 스캔 없이 얻는다. A에 이미 포함된다.

### 6.5 추가 후보

- **(E) A + `args` 파싱** `RestApp.start(args, app -> ...)`: `--port=8081`, `--host=0.0.0.0` 같은 인자를 읽는다. 주니어가 이미 쓰는 `main(String[] args)` 관례와 맞지만 호스트 인자가 LAN 노출을 숨은 입력원으로 만들 수 있어 §4.2와 충돌한다. 포트만 읽는다면 `PORT` 환경변수와 겹친다. 우선순위 낮음.
- **(F) Kotlin 전용 최상위 함수** `naruServe(3000) { get("/hello") { _, res -> res.send("hi") } }`: `NaruServe-kotlin` 선택 모듈. A로 이미 충분히 짧아 이점이 `app.` 접두 제거뿐이다. 보류.
- **(G) `startAsync` → `Future<ServerHandle>`**: Android용. A와 조합하는 보조 API이며 단독 후보가 아니다.

### 6.6 비교 요약

| 후보 | Java 본문/파일 줄 | Kotlin 본문/파일 줄 | Ctrl+C | 시작 출력 | 주요 실수 지점 | Android |
|---|---|---|---|---|---|---|
| 현재 | 3 / 9 (hook 포함 4 / 11) | 3 / 7 | 즉시 종료(요청 끊김, `onStop` 미실행) | 없음 | 무출력, hook 누락, 순서 실수 | 문서 레시피 |
| A | 1 / 7 | 1 / 5 | 정상 종료(자동 hook) | 있음 | `listen`과 차이, 이중 람다 | OK(UI 스레드 주의, hook 생략) |
| B | 1 / 7 | 1 / 5 | 정상 종료 | 있음 | 블로킹을 모르고 뒤 코드 기대 | **부적합** |
| C | 3 / 9 | 3 / 7 | 정상 종료 | 있음 | 두 이름 혼동, 순서 실수 | OK |
| D | 약 3 / 약 10 | 비슷 | 정상 종료 | 있음 | 어노테이션 누락·난독화 | **부적합** |

---

## 7. 추천 순위와 근거 (결정은 하지 않음)

1. **A: `RestApp.start([port,] Configurer<RestApp>)` → 비블로킹 `ServerHandle`** — 추천.
   - 주니어 첫 5분의 문제(무출력, hook 부재, 포트 충돌 메시지, 설정 순서 실수)를 한 번에 줄이는 유일한 후보다.
   - Core 변경이 작다: 기존 `create()`/`listen()` 위에 얹히고, keeper가 JVM 생존을 이미 맡는다.
   - Java·Kotlin 모두 본문 1줄이고 Kotlin 후행 람다가 컴파일됨을 확인했다.
   - 보안 기본값(FR-ENGINE-002)을 건드리지 않는다. LAN 노출은 람다 안에서 명시한다.
   - 필요한 보조 결정(미확정): `ServerHandle.url()`/`stop()`/`await()`, 포트 생략 오버로드와 `PORT` 입력원, Android 메인 스레드 WARN, hook의 Android 생략 방식, `AutoCloseable` 여부.
2. **C: 인스턴스 `app.start(port)`** — A를 거부할 때의 차선.
   - 가장 익숙하지만 `listen`/`start` 이름 공존 문제가 크다. 만약 채택하면 `start`를 `listen` + hook + 로그로 명확히 정의하고 `listen` 문서에서 "저수준, 부작용 없음"이라고 대비해야 한다. A의 내부 구현은 어차피 C와 같은 단계를 거치므로 C를 A의 부품으로 쓰는 것도 가능하다.
3. **B: 블로킹 `run`** — 보조 수단으로만. 블로킹 수요는 `ServerHandle.await()`로 충족할 수 있어 별도 진입점은 불필요하다. Android 부적합과 핸들 부재가 결정적이다.
4. **D: `start(Class)` 스캔형** — 비추천. Android 부적합과 기존 설계 방침(자동 스캔 없음) 위반. 구조화 욕구는 A + 메서드 참조(D')로 대체된다.

구현 전 선행 과제(우선순위 순):
1. TinyHttpd 3.0.0 좌표 정렬(`com.snoworca:tinyhttpd-*`, `build.gradle:61-64`, `settings.gradle` 검증 커밋). 시나리오 10에서 재현됨.
2. SRS: `start`/`ServerHandle` 확장/시작 로그/hook 요구를 새로 만든다(`docs/spec/16.developer-experience.srs.md` 후보. `FR-ENGINE-002`·`FR-ENGINE-006`는 `listen`을 바꾸지 않으면 영향이 없다).
3. 어댑터 개선: 421 거절 호스트 1회 WARN, 포트 충돌 메시지에 포트·주소 포함(`start` 경로 또는 `failStart`).
4. `docs/api/app.md`·`express-migration.md`·`README.md` 갱신(`start` vs `listen` 대비표, TinyHttpd README의 `.daemon(false)` 해법이 NaruServe에서는 불필요하다는 한 줄).

---

## 8. 확인하지 못한 것

- **Android 동작 전부**: 에뮬레이터·실기기를 쓰지 않았다(범위 밖). `ServerSocket.bind`가 UI 스레드에서 `NetworkOnMainThreadException`을 내는지, Android에서 JVM shutdown hook이 실행되는지, `adb forward`로 루프백 바인드에 접속되는지, Android 9+ 평문 HTTP 정책이 `127.0.0.1`에도 적용되는지, `Looper` 리플렉션 감지의 동작은 모두 일반 지식 기반 [추정]이다.
- **IDE의 정지 버튼**(IntelliJ 등)이 SIGTERM인지 강제 종료인지, hook이 실행되는지. 이 세션에서는 `kill -TERM`만 시험했다. 이전 실행이 안 죽어 포트가 막히는 시나리오의 실제 빈도도 모른다.
- **Windows·Linux 동작**: macOS에서만 실행했다. `0.0.0.0`을 브라우저 URL로 쓰는 문제(Windows)와 Ctrl+C 처리 차이는 [추정].
- **JDK 8 실행**: JDK 17(`openjdk 17.0.20.1`)에서만 돌렸다. 클래스는 `--release 8`로 컴파일했다.
- **리버스 프록시에서 421**이 나는 구체적 구성(nginx `proxy_set_header Host $host` 등)은 시험하지 않았다 [추정].
- **Spring Boot·Express의 기본 동작**(Spring: 8080, 모든 인터페이스, shutdown hook, 시작 로그 / Express: `listen`이 모든 인터페이스)은 일반 지식이고 이 세션에서 다시 확인하지 않았다. Express 차이는 `docs/api/express-migration.md:151`이 NaruServe 쪽에서 이미 적어 둔다.
- **`./gradlew build` 전체 회귀**는 돌리지 않았다. 실행한 것은 스크래치 복사본에서의 `:NaruServe-tinyhttpd:dependencies`(`com.snoworca.tinyhttpd` 좌표 FAILED)와, 좌표를 바꾼 뒤 `:NaruServe-tinyhttpd:compileJava`(성공)뿐이다. 이 복사본은 `/private/tmp/.../scratchpad/gcheck/{N,T}`이며 원본 저장소는 수정하지 않았다(`git status`로 확인: 원래의 untracked `docs/.kiwi/`, `kiwi/*`만 있음).
- **TinyHttpd 3.0.0이 Maven Central에 실제로 올라가 있는지**는 네트워크로 확인하지 않았다(TinyHttpd README의 주장). 그 좌표로 NaruServe를 풀 때 전이 의존(`tinyhttpd-websocket`)도 같은 버전으로 풀리는지는 미확인.
- **`start` 구현의 부작용**(예: hook 제거 타이밍, `await`의 `AppLifecycle` 변경이 기존 상태 기계 테스트에 미치는 영향, 시작 로그가 기존 로그 기반 테스트에 미치는 영향)은 코드를 바꾸지 않았으므로 시험하지 않았다. `DefaultRestAppTest`, `DefaultLoggerTest` 등을 읽지 않았다.
- TinyHttpd 작업 트리에는 커밋되지 않은 문서 변경이 있다(`README.md`, `docs/api/servlet-differences.md` 수정, `docs/api/servlet-differences.en.md`·`docs/guide/` 미추적). 코드는 HEAD c000eb1과 같다.

### 실험 파일 위치(재현용)

- `/private/tmp/claude-501/-Users-beom-work-git-NaruServe/d80056f3-cc64-48fd-af47-0ccf9de56a5d/scratchpad/exp/Exp.java` (hello/busy/lan/badhost/routedup/uncaught)
- `/private/tmp/claude-501/-Users-beom-work-git-NaruServe/d80056f3-cc64-48fd-af47-0ccf9de56a5d/scratchpad/exp/Sig.java` (SIGTERM, hook 유무)
- `/private/tmp/claude-501/-Users-beom-work-git-NaruServe/d80056f3-cc64-48fd-af47-0ccf9de56a5d/scratchpad/exp/Late.java` (listen 뒤 설정)
- `/private/tmp/claude-501/-Users-beom-work-git-NaruServe/d80056f3-cc64-48fd-af47-0ccf9de56a5d/scratchpad/exp/kt/` (Kotlin 후행 람다 컴파일 확인)
- `/private/tmp/claude-501/-Users-beom-work-git-NaruServe/d80056f3-cc64-48fd-af47-0ccf9de56a5d/scratchpad/gradle.out`, `gradle2.out` (좌표 해석 결과)
