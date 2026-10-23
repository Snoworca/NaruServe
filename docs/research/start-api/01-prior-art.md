# start API 선행 사례 조사

- 작성일: 2026-10-23
- 목적: NaruServe에 "Spring Boot처럼 정적 `start`를 실행하면 서버가 뜨는" 진입점을 둘 때 참고할 선행 API 비교
- 성격: 조사 문서. 결정이 아니다. 후보(§4)는 스케치일 뿐이다.
- 표기: **(확인 못함)** = 이번 조사에서 1차 자료로 확인하지 못함. **(일반 지식)** = 1차 자료로는 못 읽었고 알려진 동작을 적음. 반드시 재확인 후 사용.

## 0. 현재 NaruServe 상태 (저장소에서 직접 확인)

- `RestApp`에는 `static create()`, `static builder()`, `listen(int)` → `ServerHandle`, `shutdown(long, TimeUnit)`, `onStart/onStop`, `state()`가 있고 `start` 정적 메서드는 없다. (`NaruServe-api/src/main/java/com/snoworca/naruserve/RestApp.java`)
- `listen(port)`는 동기다. `onStart` 실행 → 라우트 검사 → 바인딩 후 반환하고, 실패는 예외로 던진다. 기본 바인드 주소는 `127.0.0.1`이다. (`docs/api/app.md` listen 절)
- `listen()` 반환 후 `main`이 끝나도 프로세스는 `shutdown`까지 산다. TinyHttpd 어댑터가 daemon이 아닌 keeper 스레드 하나를 띄우기 때문이다. TinyHttpd 자체 스레드는 daemon이다. (`NaruServe-tinyhttpd/.../KeeperThread.java`, `docs/api/app.md`)
- JVM shutdown hook은 자동 등록되지 않는다. 문서 예제가 `Runtime.getRuntime().addShutdownHook(new Thread(() -> app.shutdown(10, TimeUnit.SECONDS)))`를 직접 쓴다.
- `ServerHandle`은 `port()`, `address()`, `state()`만 가진다. 종료 메서드가 없고 종료는 `RestApp.shutdown`으로만 한다.
- `Configurer<T>`는 `@FunctionalInterface`, `void configure(T target)`이고 `throws`가 없다. `LifecycleCallback`은 `throws Exception`을 허용한다.
- 설정 주입 수단은 코드뿐이다. 환경변수, 시스템 프로퍼티, `args`를 읽는 코드는 Core/bootstrap에서 찾지 못했다.
- TinyHttpd(`core/.../TinyHttpd.java`)의 `Builder.daemon`은 기본 `true`다.

## 1. 요약

1. 선행 API는 5가지 형태로 갈린다. (a) 정적 `run(Class, args)` (Spring, Micronaut, Quarkus), (b) 빌더 `.start()` (Helidon, Ktor, Spring Builder), (c) `create(cfg).start(port)` (Javalin), (d) 정적 전역 DSL (Spark), (e) 인스턴스 `listen(port)` (Express, 현 NaruServe).
2. "정적 메서드 하나로 뜬다"는 Spring/Micronaut/Quarkus의 인상은 클래스(`Application.class`)를 받아 어노테이션·컨텍스트를 처리하는 구조에서 나온다. Spring은 `@ComponentScan`으로 패키지를 스캔한다. 이 구조는 NaruServe의 "스캔 금지"와 맞지 않는다. 시그니처 모양만 가져올 수 있다.
3. 블로킹 여부는 제각각이다. Quarkus `run(String...)`만 명시적으로 반환하지 않는다. Spring, Javalin, Vert.x, Helidon, NanoHTTPD는 서버를 띄운 뒤 반환한다. 이 중 Javalin(문서 명시)과 Vert.x(문서 명시)는 서버 스레드가 non-daemon이라 JVM이 살고, Spring과 Helidon은 유지 메커니즘을 이번에 확인하지 못했다. NanoHTTPD `start()`는 daemon이라 JVM이 살지 않는다. Ktor만 `start(wait = true)`로 호출 스레드를 직접 붙잡는다.
4. NanoHTTPD 계열은 `start(timeout, daemon)`의 daemon 플래그가 함정이다. `start()`는 daemon=true라서 `main`이 끝나면 서버도 죽는다. NaruServe는 keeper 스레드로 이미 막았다.
5. shutdown hook은 Spring(기본 등록), Helidon(`shutdown-hook` 기본 `true`)만 자동이다. Javalin, Spark, Vert.x 임베디드, Express는 공식 자료에서 자동 등록을 확인하지 못했다(Javalin·Spark는 소스에 없음 확인). Ktor는 `addShutdownHook`을 따로 부른다. 현재 NaruServe는 수동이다.
6. 기본 바인드 주소가 갈린다. NaruServe `127.0.0.1`(보안 기본값), Spark `0.0.0.0`, Helidon `0.0.0.0`, Vert.x `0.0.0.0`. 주니어가 "왜 폰/다른 PC에서 안 붙지"를 겪기 쉬운 지점이다.
7. 정적 전역 DSL(Spark)은 Java 8·Kotlin 모두 컴파일되지만 앱이 하나로 고정되고, 테스트와 다중 앱에 불리하다. Spark도 이를 위해 `Service.ignite()`를 둔다.
8. NaruServe 제약(Java 8, API 21, Core에 엔진 타입 금지, 스캔 금지, SAM 람다)에는 "정적 `start(포트, 라우트 람다)`" 형태와 "`RestApp.create()`에 붙는 인스턴스 `start`"가 맞는다. 둘 다 새 의존을 만들지 않는다. 선택은 별도로 해야 한다.
9. 후보 설계 전에 열려 있는 결정: 반환 핸들에 종료/대기 수단을 줄지, shutdown hook을 기본 등록할지, 바인드 기본값을 `start`에서도 `127.0.0.1`로 유지할지.

## 2. 비교표

### 2.1 (1) 진입 메서드·최소 예 / (2) 블로킹 여부

| 프레임워크 | (1) 진입 시그니처와 최소 예 | (2) 블로킹 / main 유지 |
|---|---|---|
| Spring Boot | `static ConfigurableApplicationContext run(Class<?> primarySource, String... args)`, `run(Class<?>[], String[])`. 예: `SpringApplication.run(MyApplication.class, args);` 인스턴스 `run(String...)`도 있음. 빌더: `new SpringApplicationBuilder().sources(Parent.class).child(Application.class).bannerMode(Banner.Mode.OFF).run(args)` | 컨텍스트 refresh 후 반환(문서상 liveness는 refresh 직후). JVM 유지 메커니즘은 문서 페이지에 설명 없음. 문서는 JVM 규칙 "모든 스레드가 daemon이면 종료"와, 가상 스레드 사용 시 `spring.main.keep-alive=true` 필요를 적음 |
| Javalin (문서는 7.x 기준으로 보임) | `static Javalin create()`, `create(Consumer<JavalinConfig>)`, `static Javalin start(Consumer<JavalinConfig>)`, `start()`, `start(int)`, `start(String host, int)`. 예: `Javalin.create(cfg -> ...).start(7070);` | 문서가 "sync/blocking"이라 주석을 달면서 동시에 "start()는 user 스레드를 띄우고 반환"이라 쓴다. 프로그램은 `app.stop()` 전까지 종료되지 않는다고 명시. 즉 반환하지만 JVM은 산다 |
| Ktor | `embeddedServer(Netty, port = 8080) { ... }.start(wait = true)`. `start(wait: Boolean = false)` | `wait = true`면 호출 스레드를 붙잡는다(API 문서 요지). `false`면 `main`이 끝나 프로세스가 종료될 수 있음 |
| Micronaut | 공식 가이드 예: `Micronaut.run(Application.class);`. `Micronaut.build(args)...start()` 형태 존재(API 문서에 `start()` = "Starts the ApplicationContext"로 확인). `run(Class, String...)` 오버로드는 **(확인 못함)** | 임베디드 서버가 있으면 서버가 JVM을 유지한다. 2.2.0 소스 스니펫에서 latch 대기가 보였으나 최신 버전은 **(확인 못함)** |
| Quarkus | `static void run(String... args)`, `run(Class<? extends QuarkusApplication>, String...)`, `run(Class, BiConsumer<Integer,Throwable>, String...)`, `waitForExit()`, `asyncExit(...)`. 예: `Quarkus.run(args);` | `run(String...)`은 반환하지 않는다. Javadoc: "This method does not return, as System.exit() is called after the application is finished." |
| Vert.x | `Vertx.vertx().createHttpServer().requestHandler(...).listen(8080)` → `Future` | 바인딩이 비동기라 `listen` 반환 시점에 아직 수신 전일 수 있음. Vert.x 스레드는 non-daemon이라 JVM이 산다 |
| Helidon SE 4 | `WebServer.builder().port(9090).routing(it -> it.get("/greet", (req,res) -> res.send("Hi there!"))).build().start();` `start()`는 `WebServer` 반환 | 4.x는 동기 시작(리액티브 래퍼 없음, 웹 검색 요약). main 유지 방법은 **(확인 못함)** |
| Spark Java | 정적 DSL: `port(8080); get("/hello", (req, res) -> "Hello World");` 첫 `port`/라우트 호출로 자동 시작, `init()`으로 수동 시작 | 호출 즉시 반환. `awaitInitialization()`으로 준비 대기, `awaitStop()`으로 종료 대기. JVM 유지는 Jetty 스레드에 따름 **(확인 못함)** |
| Express | `app.listen([port[, host[, backlog]]][, callback])` (일반 지식). 문서 예: `app.listen(3000);` 구현: `http.createServer(this)` 후 `server.listen.apply(server, args)` | 이벤트 루프가 소켓을 잡고 있으므로 프로세스가 산다 (일반 지식) |
| NanoHTTPD | `new NanoHTTPD(int port)` / `(String hostname, int port)`; `start()`, `start(int timeout)`, `start(int timeout, boolean daemon)` — 모두 `throws IOException` | 호출자는 바인드 성공/실패까지만 기다리고 반환(10ms 간격 폴링). 리스너 스레드 이름 "NanoHttpd Main Listener". `start()`·`start(int)`는 daemon=true |

### 2.2 (3) 반환값·종료 / (4) 설정 주입 / (5) shutdown hook

| 프레임워크 | (3) 반환값(핸들)과 종료 | (4) 설정 주입 | (5) shutdown hook |
|---|---|---|---|
| Spring Boot | `ConfigurableApplicationContext`. 종료는 `close()`, 종료 코드는 `SpringApplication.exit(ctx)` + `System.exit` | 포트는 `server.port`, 주소는 `server.address`(공식 페이지 발췌에서는 8080 기본값만 확인. 속성명은 2차 자료). `args`는 `CommandLinePropertySource`로 프로퍼티화(기본 켜짐, `setAddCommandLineProperties`). `setDefaultProperties`, `setAdditionalProfiles` 있음. 환경변수 `SERVER_PORT`는 2차 자료 | 기본 등록. "Each `SpringApplication` registers a shutdown hook with the JVM". `setRegisterShutdownHook(boolean)`, 기본 `true` |
| Javalin | `Javalin` 인스턴스(`start`, `stop`이 인스턴스 반환). `stop()`은 이미 정지 중이면 바로 반환, 실행 중 요청은 대기 | `config.jetty.host`, `config.jetty.port` 또는 `start(host, port)` / `start(port)`. `start()` 무인자는 포트 미설정 시 8080(Javadoc). Javalin 7은 라우트를 `config.routes` 안에서 정의해야 함(생성 시점) | `Javalin.java`에 `addShutdownHook`/`registerShutdownHook` 없음 확인. 문서 예는 사용자가 `Runtime.getRuntime().addShutdownHook(new Thread(() -> app.stop()))`을 씀. `config.events.serverStopping` 이벤트 있음 |
| Ktor | `EmbeddedServer`(`start`가 서버 반환). `stop(gracePeriodMillis, timeoutMillis)` | 코드(`port=`, `connectors.add(EngineConnectorBuilder().apply { host = ...; port = ... })`), `CommandLineConfig(args)`, `application.conf`의 `ktor.deployment`. 문서 기본 포트 예 8080 | 별도 `EmbeddedServer.addShutdownHook(stop)` 확장. 서버 시작 전에 부르도록 KDoc에 명시. 자동인지는 **(확인 못함)**. 커뮤니티 글은 Ctrl+C에서 직접 hook 필요하다고 보고(2차) |
| Micronaut | `ApplicationContext`/`EmbeddedServer`. 서버만 멈추는 `NettyEmbeddedServer` 메서드도 있음(Javadoc) | `micronaut.server.port`(기본 8080), `-1`이면 랜덤, 환경변수 `MICRONAUT_SERVER_PORT`. `micronaut.server.host`는 **(확인 못함)** | **(확인 못함)** |
| Quarkus | 반환 없음(`void`, 내부에서 `System.exit`). 종료는 신호 또는 `Quarkus.asyncExit()`. `QuarkusApplication.run`이 반환하면 앱 종료 | `quarkus.http.port=0`이면 랜덤(공식 가이드). 기본값, `quarkus.http.host`, 환경변수명은 이번 조사에서 **(확인 못함)**(전체 설정 페이지에서 해당 구간을 읽지 못함) | 가이드는 JVM hook이 아니라 `ShutdownEvent` 옵저버, `@Shutdown` 메서드를 설명. JVM hook 자체는 **(확인 못함)** |
| Vert.x | `Future<HttpServer>`. `server.close()`는 즉시(유예 없음), `shutdown()`은 유예(기본 30초, Vert.x 5 API). 임베디드는 `vertx.close()`로 스레드 풀 정리해야 JVM 종료 | `listen(1234, "localhost")`. 호스트 기본 `0.0.0.0`. 포트 0이면 랜덤, `server.actualPort()`로 확인 | 임베디드 예에서는 자동 등록 **(확인 못함)**. Launcher의 hook 처리도 **(확인 못함)** |
| Helidon SE 4 | `WebServer`(`start()`, `stop()`: "Attempt to gracefully shutdown", `port()`, `isRunning()`). `start()`는 "Has no effect if server is running" | `port`(기본 `0`=랜덤), `host`(기본 `0.0.0.0`). `Config.create()` 후 `.config(config.get("server"))`로 `application.yaml`의 `server.port`/`server.host` 주입 | `shutdown-hook` 설정, 기본 `true`("registers a shutdown hook with the JVM Runtime"). `shutdown-grace-period` 기본 `PT0.5S` |
| Spark Java | `Service`/정적. `stop()`은 서버 중지 + **라우트 전부 삭제**(Javadoc). `awaitStop()` | 코드: `port(int)`(기본 4567, 0이면 임의 포트), `ipAddress(String)`(기본 `0.0.0.0`). 둘 다 "route mapping 전에 호출" | `Service.java`에 `addShutdownHook` 없음 확인. 종료는 `stop()` 호출뿐. 시작 실패 기본 핸들러는 `System.exit(100)` |
| Express | `http.Server`(`server.close(cb)`로 종료, `server.address().port`로 실제 포트: 일반 지식) | `app.listen(port, host)` 코드 인자. 환경변수는 사용자가 `process.env.PORT`를 직접 읽는 관행(일반 지식). 호스트 생략 시 전 인터페이스(일반 지식) | 없음(문서에서 확인 못함, 일반 지식으로는 사용자가 SIGTERM을 직접 처리). 시작 실패는 콜백이 `error` 이벤트에 once로 연결되어 전달됨(소스 확인) |
| NanoHTTPD | 반환 없음(`void`). 종료 `stop()`: 소켓 닫고 연결 정리 후 리스너 스레드 join, 예외는 로그만 남기고 던지지 않음. `closeAllConnections()`는 `stop()`과 동일 | 생성자 인자만(`hostname` 생략 시 null = 전 인터페이스로 보이나 **확인 못함**), 환경변수·args 없음 | 없음(소스에서 확인 못함) |

### 2.3 (6) 주니어 실수 / (7) Java 8·Android·Kotlin 적합성

| 프레임워크 | (6) 주니어가 실수하기 쉬운 지점 | (7) Java 8 / Android / Kotlin |
|---|---|---|
| Spring Boot | `@SpringBootApplication` 클래스를 컴포넌트 패키지의 상위에 두지 않으면 빈이 안 잡힘(`@ComponentScan`은 애플리케이션 클래스의 패키지 기준). 문서가 경고하는 것: 긴 작업을 이벤트 리스너에 넣음, 시작 작업을 `@PostConstruct`에 넣어 readiness를 왜곡 | 클래스패스 스캔 + 오토컨피그 + 리플렉션 기반. Spring 문서는 `@Import`로 스캔 없이 쓰는 방법을 보여준다. Android 적합성은 **(확인 못함)**, 스캔 구조는 NaruServe 제약과 충돌. Kotlin은 `runApplication` 확장이 따로 있으나 이번에 확인 못함 |
| Javalin | 7.x에서 서버 시작 뒤에는 라우트를 못 붙임(`config.routes` 안에서만). `start()` 후 `main`이 끝나도 안 죽는다는 점을 모르고 종료 처리를 빼먹음. 문서의 "sync/blocking" 주석과 "반환한다" 서술이 섞여 있어 혼동 소지 | Javalin 7은 Java 17 최소(공식 뉴스). Javalin 6의 최소 Java는 **(확인 못함)**. Jetty 기반이라 Android 부적합(Android 지원 여부는 **확인 못함**, Jetty 12 + Java 17 요구로 추정만 가능). 람다 설정은 Java·Kotlin 모두 자연스러움 |
| Ktor | `start(wait = false)`가 기본이라 `main`이 바로 끝나 서버가 안 뜬 것처럼 보임. 반대로 `wait = true` 뒤에 `stop`을 적으면 도달하지 않음(커뮤니티 보고) | Kotlin 전용 API(`embeddedServer` 확장, suspend, DSL). Java에서는 부자연스럽다. Android 서버 사용은 **(확인 못함)** |
| Micronaut | 오토 와이어링이 컴파일 타임 처리에 의존. 포트 `-1`(랜덤)이 `0`이 아니라는 점 | 컴파일 타임 DI라 런타임 스캔이 적다는 평가는 일반 지식. 이번에 1차 자료로 **확인 못함**. Android는 **(확인 못함)** |
| Quarkus | 가이드가 "`main`에 비즈니스 로직을 넣지 마라(아직 Quarkus가 구성 전)"고 경고. `run`이 반환하지 않으므로 뒤에 둔 코드는 실행되지 않음 | 빌드 타임 처리 중심(일반 지식). Android는 **(확인 못함)** |
| Vert.x | `listen()`이 `Future`라 반환 직후 접속을 가정하면 실패. 임베디드에서 `vertx.close()`를 안 하면 JVM이 안 끝남 | Java 8은 Vert.x 버전에 따라 다름 **(확인 못함)**. Android는 **(확인 못함)**. 람다 핸들러는 자연스러움 |
| Helidon SE | 포트 기본 `0`(랜덤)이라 포트를 안 정하면 매번 다른 포트로 뜸. 기본 호스트 `0.0.0.0` | Helidon 4는 Java 21 계열로 알려져 있으나 이번에 **확인 못함**. Android 부적합 추정만 가능 |
| Spark Java | `port()`/`ipAddress()`를 라우트 정의 뒤에 호출하면 무시되거나 오류 ("route mapping 전에 호출"). `stop()`이 라우트를 지우므로 재시작이 안 됨. 시작 실패 기본 처리가 `System.exit(100)` | 정적 메서드 + Java 8 람다 가능. 전역 상태. Jetty 기반이라 Android 적합성은 **(확인 못함)** |
| Express | `app.listen` 호출 뒤 라우트 추가가 가능해 타이밍 버그가 생김(NaruServe는 `listen` 뒤 설정 변경을 막음). 호스트 생략이 전 인터페이스 공개 | 해당 없음(JS). NaruServe의 모델 원본 |
| NanoHTTPD | `start()`가 daemon이라 `main`이 끝나면 서버가 같이 죽음. `start(5000, false)`처럼 `timeout`과 `daemon` 인자 순서·의미 혼동. `IOException`을 잡아야 컴파일됨 | Java 8·Android 5.0에서 널리 쓰임. NaruServe의 엔진 쪽이므로 직접 노출하지 않는다 |

## 3. 패턴 분류와 NaruServe 제약 판정

판정 기준: Java 8 / API 21 / Core 공개 API에 엔진 타입 금지 / 스캔 금지 / Kotlin SAM 람다.

| 패턴 | 대표 | Java 8 | API 21 | 엔진 타입 비노출 | 스캔 금지 | Kotlin SAM | 판정 |
|---|---|---|---|---|---|---|---|
| P1. 정적 `run(Class, args)` | Spring, Micronaut, Quarkus | 가능 | 시그니처는 가능. 의미를 지키려면 스캔 필요 | 가능 | **충돌**(Class 인자가 스캔 시작점) | 가능 | 모양만 차용 가능. `Class` 인자는 쓸모가 없어 부적합 |
| P2. 빌더 `.start()` / `.run()` | Helidon, Ktor, Spring Builder, `Micronaut.build()` | 가능 | 가능 | 가능 | 무관 | 가능(람다 설정) | 적합. `RestApp.builder()`가 이미 있어 확장 지점이 있다. 단, 기존 `Builder`는 어댑터·코덱·로거 주입용이고 `build()`가 `RestApp`을 반환한다 |
| P3. 정적 전역 DSL | Spark | 가능 | 가능 | 가능 | 무관 | 가능 | 부적합에 가깝다. 앱이 JVM당 1개로 고정되고, 현 `RestApp` 인스턴스 모델과 충돌. 테스트·다중 서버에 불리 |
| P4. `create(cfg).start(port)` | Javalin | 가능 | 가능 | 가능 | 무관 | 가능 | 적합. 현재 `RestApp.create()` + `listen(port)`와 거의 같은 형태. 새로움은 "구성 람다를 `create`에 넣는" 부분뿐 |
| P5. 인스턴스 `listen(port)` | Express, 현 NaruServe | 가능 | 가능 | 가능 | 무관 | 가능 | 이미 있음. "정적 진입점"은 제공하지 않는다 |
| P6. 기반 엔진 `start(timeout, daemon)` | NanoHTTPD | 가능 | 가능 | 엔진 타입 자체 | 무관 | 가능 | 직접 모방할 대상이 아님. daemon 플래그 함정만 참고 |
| P7. 정적 한 줄 + 라우트 람다 | (정확한 선행 사례를 이번에 찾지 못함. Spark의 `port()`+`get()`와 Javalin의 `create(cfg)`의 중간 형태) | 가능 | 가능 | 가능 | 무관 | 가능(`RestApp.start(3000) { app -> ... }`) | 적합. 새 의존 없이 `Configurer<RestApp>`로 표현 가능 |

보충:
- Core의 `RestApp.create()`는 `AppFactoryLoader`(ServiceLoader)로 `NaruServe` bootstrap 모듈의 `AppFactory`를 찾는다. 정적 `start`를 `RestApp`에 두면 이 경로를 그대로 재사용할 수 있어 Core가 엔진 타입을 알 필요가 없다.
- Java 8은 인터페이스 정적 메서드를 허용한다(`create()`/`builder()`가 이미 그렇다). Kotlin에서 인터페이스 정적 메서드를 부르려면 JVM 타깃 1.8 이상이 필요하며 프로젝트는 `jvmTarget = 1.8`이다(`CLAUDE.md`).
- 스캔 대신 선행 사례가 쓰는 대안: Spring의 `@Import` 명시 등록, Javalin/Helidon/Ktor의 코드 구성. NaruServe는 이미 코드 구성이다.

## 4. NaruServe용 `start` 후보 스케치

모두 **후보**다. 결정하지 않는다. 공통 전제:
- 이름은 요청에 따라 `start`로 적었다. `run` 등 대안 이름은 비교 대상이 아니다.
- 모든 후보는 기존 `listen`/`shutdown`을 대체하지 않는다고 가정한다(대체 여부는 별도 결정).
- `Configurer<RestApp>`은 `throws`가 없다. 라우트 등록 중 checked 예외를 던지는 코드는 람다 안에서 try/catch가 필요하다.

### 후보 A. 정적 `start(int port, Configurer<RestApp> routes)`

```java
// 시그니처 스케치
static ServerHandle start(int port, Configurer<RestApp> routes);
```

```java
// Java
ServerHandle server = RestApp.start(3000, app -> {
    app.get("/hello", (req, res) -> res.send("hello"));
});
```

```kotlin
// Kotlin
val server = RestApp.start(3000) { app ->
    app.get("/hello") { req, res -> res.send("hello") }
}
```

- 장점: 줄 수가 가장 적다. "`start`를 실행하면 서버가 뜬다"에 가장 가깝다. 새 의존·스캔 없음. 라우트를 람다 안에서만 등록하게 하므로 "`listen` 뒤 설정 변경 예외"를 구조적으로 피한다.
- 단점: 내부에서 `create()`한 앱을 호출자가 못 잡는다. `ServerHandle`에는 종료 메서드가 없어 종료 수단이 비어 있다(핸들에 종료를 넣거나 앱을 반환해야 함: 열린 결정). `onStart`/`onStop`/`cors`/`server(...)` 설정은 같은 람다 안에서 해야 하는데 `start` 이전 설정과 섞이는 혼란이 생긴다.
- 주니어 실수 가능성: 람다 안에서 `app.listen(...)`을 또 부름(이중 시작). 람다 밖에서 앱을 만들어 쓰려다 막힘. `main`이 반환해도 프로세스가 산다는 점을 모르고 종료 방법을 못 찾음(keeper 스레드는 이미 있음). Kotlin에서 `return`을 쓰면 컴파일 오류(`return@get` 필요, `docs/api/kotlin.md`).

### 후보 B. 정적 `start(String[] args, Configurer<RestApp> routes)` (Spring식 `args` 수용)

```java
static ServerHandle start(String[] args, Configurer<RestApp> routes);
```

```java
// Java
public static void main(String[] args) {
    RestApp.start(args, app -> app.get("/hello", (req, res) -> res.send("hello")));
}
```

```kotlin
// Kotlin
fun main(args: Array<String>) {
    RestApp.start(args) { app -> app.get("/hello") { req, res -> res.send("hello") } }
}
```

- 장점: Spring의 `run(Class, args)`와 호출 모양이 비슷해 Spring 경험자가 익숙하다. 포트·주소를 코드 수정 없이 바꿀 수 있다.
- 단점: `args` 형식(`--port=3000`? `--server.port`? 기본 포트는?)을 새로 정해야 한다. 사양서에 없는 이름을 만드는 일이다. 환경변수·시스템 프로퍼티를 함께 읽을지도 새 결정이다. Android에는 `main(args)`가 없어 이 경로를 쓸 수 없다(`start(0, ...)` 계열이 따로 필요).
- 주니어 실수 가능성: 인자 형식을 틀려도 조용히 기본값으로 뜰 수 있다(검증 정책에 달림). 기본 포트가 무엇인지 몰라 접속 실패. 바인드가 `127.0.0.1`이라 LAN에서 접속 안 됨(현재 기본값).

### 후보 C. 인스턴스 `RestApp.start(int port)` (Javalin식 `create().start(port)`)

```java
// RestApp 인스턴스 메서드 스케치: listen(int)와 별칭 관계인지 다른 동작인지는 열린 결정
ServerHandle start(int port);
```

```java
// Java
RestApp app = RestApp.create();
app.get("/hello", (req, res) -> res.send("hello"));
app.start(3000);
```

```kotlin
// Kotlin
val app = RestApp.create()
app.get("/hello") { req, res -> res.send("hello") }
app.start(3000)
```

- 장점: 기존 모델(`create` → 라우트 → 시작)을 그대로 유지한다. 앱 참조를 호출자가 가지므로 `shutdown`이 자연스럽다. Javalin의 `create(...).start(port)`와 같은 모양이라 선행 사례가 있다.
- 단점: `listen`과 `start` 두 개가 공존한다. 둘의 차이가 없다면 중복 API이고, 있다면(예: `start`는 shutdown hook 등록 + 블로킹) 차이를 문서로 설명해야 한다. "정적 메서드"라는 요청 형태와 다르다.
- 주니어 실수 가능성: `listen`과 `start` 중 무엇을 써야 하는지 헷갈림. 한쪽만 hook을 등록한다면 다른 쪽에서 종료 처리 누락.

### 후보 D. 정적 블로킹 `run(...)` 형태 + 핸들의 대기 (Quarkus/Ktor `wait=true`식)

```java
// 스케치: 반환하지 않거나 종료까지 대기하는 변형
static void start(int port, Configurer<RestApp> routes);   // 종료 신호까지 호출 스레드를 붙잡음 (안)
// 또는 ServerHandle.await() 같은 대기 메서드를 핸들에 추가 (안)
```

```java
// Java
public static void main(String[] args) {
    RestApp.start(3000, app -> app.get("/hello", (req, res) -> res.send("hello")));
    // 여기에 도달하지 않거나, 서버 종료 뒤에 도달함
}
```

```kotlin
// Kotlin
fun main() = RestApp.start(3000) { app ->
    app.get("/hello") { req, res -> res.send("hello") }
}
```

- 장점: `main`이 서버 수명과 일치해 "프로세스가 왜 안 끝나지"가 사라진다. 종료 신호 처리를 한 곳에 둘 수 있다(hook과 결합).
- 단점: Android 메인 스레드에서 부르면 ANR 위험(`docs/api/app.md`가 이미 "메인 스레드 금지"). 테스트에서 블로킹 호출은 다루기 어렵다. Quarkus와 Ktor 모두 이 때문에 비블로킹 변형(`QuarkusApplication`, `wait=false`)을 병행한다. 반환 후 코드를 못 쓰는 문제(Quarkus 가이드의 경고와 같은 종류).
- 주니어 실수 가능성: `start` 뒤에 쓴 코드(로그, 종료 처리)가 실행되지 않음. Android UI 스레드에서 호출.

### 후보 E. 옵션 람다 + 라우트 람다 (Helidon·Javalin의 구성 람다식)

```java
static ServerHandle start(Configurer<StartOptions> options, Configurer<RestApp> routes);
// StartOptions 타입과 포트/주소/hook 항목은 (미확정)
```

```java
// Java
RestApp.start(
    opt -> opt.port(3000).bindAddress("0.0.0.0"),
    app -> app.get("/hello", (req, res) -> res.send("hello")));
```

```kotlin
// Kotlin
RestApp.start({ opt -> opt.port(3000).bindAddress("0.0.0.0") }) { app ->
    app.get("/hello") { req, res -> res.send("hello") }
}
```

- 장점: 포트·바인드 주소·hook 여부를 한 곳에서 명시적으로 고른다. 이미 `app.server(options -> ...)`가 있어 같은 문법이다(`docs/api/app.md`). 기본값을 코드에서 보이게 하여 보안 기본값(`127.0.0.1`) 설명이 쉽다.
- 단점: 람다 두 개가 연달아 나와 Kotlin에서 첫 람다를 괄호 안에 넣어야 하고 읽기 어렵다. `start(3000, ...)`보다 입문 난도가 높다. 새 옵션 타입이 늘어난다.
- 주니어 실수 가능성: 두 람다의 순서를 바꿔 씀(타입은 다르지만 `app ->`/`opt ->` 이름만 보고 오해). Kotlin 구문 실수.

### 후보 간 공통 열린 결정 (정리용)

| 항목 | 선행 사례의 선택 | 현재 NaruServe |
|---|---|---|
| 종료 수단을 어디에 두는가 | Spring: 컨텍스트, Javalin·Helidon: 서버 인스턴스, Quarkus: 신호 | `RestApp.shutdown`만 있음. `ServerHandle`에는 없음 |
| shutdown hook 기본 등록 | Spring·Helidon 기본 켜짐, 그 외 수동 | 수동 |
| 기본 바인드 주소 | `0.0.0.0` 다수, 보안 기본값은 드묾 | `127.0.0.1` |
| 설정 주입(env/args/system property) | Spring·Micronaut·Quarkus·Ktor(CLI)는 지원 | 없음 |
| 블로킹 | Quarkus, Ktor(`wait=true`)만 | 비블로킹 + keeper 스레드로 프로세스 유지 |

## 5. 출처

로컬 확인:
- `/Users/beom/work/git/NaruServe/NaruServe-api/src/main/java/com/snoworca/naruserve/RestApp.java`
- `/Users/beom/work/git/NaruServe/NaruServe-api/src/main/java/com/snoworca/naruserve/ServerHandle.java`
- `/Users/beom/work/git/NaruServe/NaruServe-api/src/main/java/com/snoworca/naruserve/Configurer.java`
- `/Users/beom/work/git/NaruServe/NaruServe-tinyhttpd/src/main/java/com/snoworca/naruserve/tinyhttpd/KeeperThread.java`
- `/Users/beom/work/git/NaruServe/docs/api/app.md`, `/Users/beom/work/git/NaruServe/docs/api/kotlin.md`
- `/Users/beom/work/git/TinyHttpd/core/src/main/java/com/snoworca/tinyhttpd/TinyHttpd.java` (daemon 기본값)

웹 (조회일 2026-10-23):
- Spring Boot SpringApplication API: https://docs.spring.io/spring-boot/api/java/org/springframework/boot/SpringApplication.html
- Spring Boot SpringApplication 레퍼런스: https://docs.spring.io/spring-boot/reference/features/spring-application.html
- Spring Boot @SpringBootApplication: https://docs.spring.io/spring-boot/reference/using/using-the-springbootapplication-annotation.html
- Spring Boot 서블릿 웹 서버(8080 기본값 문구만 확인): https://docs.spring.io/spring-boot/reference/web/servlet.html
- Javalin 문서: https://javalin.io/documentation
- Javalin 소스: https://raw.githubusercontent.com/javalin/javalin/master/javalin/src/main/java/io/javalin/Javalin.java
- Javalin 뉴스(7.0 = Java 17): https://javalin.io/news
- Ktor 서버 생성: https://ktor.io/docs/server-create-and-configure.html
- Ktor 엔진: https://ktor.io/docs/server-engines.html
- Ktor EmbeddedServer API(검색 요약 경유): https://api.ktor.io/ktor-server-core/io.ktor.server.engine/-embedded-server/index.html
- Micronaut HTTP 서버 가이드: https://docs.micronaut.io/5.1.x/http/
- Micronaut 포트 설정 검색 결과: https://mkyong.com/micronaut/how-to-change-the-default-micronaut-server-port/
- Micronaut 2.2.0 `Micronaut.java`(구버전 스니펫): https://code.yawk.at/io.micronaut/micronaut-runtime/2.2.0/io/micronaut/runtime/Micronaut.java
- Micronaut API(5.0.1) `Micronaut` 클래스(직접 조회는 404, 검색 요약 경유): https://docs.micronaut.io/5.0.1/api/io/micronaut/runtime/Micronaut.html
- Quarkus 수명주기 가이드: https://quarkus.io/guides/lifecycle
- Quarkus HTTP 레퍼런스: https://quarkus.io/guides/http-reference
- Quarkus 소스: https://raw.githubusercontent.com/quarkusio/quarkus/main/core/runtime/src/main/java/io/quarkus/runtime/Quarkus.java
- Vert.x Core 문서: https://vertx.io/docs/vertx-core/java/
- Vert.x HttpServer API 5.0.12: https://vertx.io/docs/5.0.12/apidocs/io/vertx/core/http/HttpServer.html
- Helidon WebServer 문서: https://helidon.io/docs/v4/se/webserver
- Helidon WebServer 소스: https://raw.githubusercontent.com/helidon-io/helidon/main/webserver/webserver/src/main/java/io/helidon/webserver/WebServer.java
- Spark 문서: https://sparkjava.com/documentation
- Spark `Service.java`: https://raw.githubusercontent.com/perwendel/spark/master/src/main/java/spark/Service.java
- Express API 개요: https://expressjs.com/en/5x/api.html
- Express `application.js`: https://raw.githubusercontent.com/expressjs/express/master/lib/application.js
- Express listen 보조 설명(2차): https://masteringjs.io/tutorials/node/http-server
- NanoHTTPD 소스: https://raw.githubusercontent.com/NanoHttpd/nanohttpd/master/core/src/main/java/org/nanohttpd/protocols/http/NanoHTTPD.java

한계: WebFetch가 중간 요약 모델을 거치므로 인용 일부는 원문과 다를 수 있고, 위에서 "(확인 못함)"으로 표시한 항목은 1차 자료를 읽지 못했다. Express 공식 `app.listen` 절은 직접 읽지 못해 시그니처를 일반 지식으로 적었다. 확정 설계에 쓰기 전에 해당 항목은 재확인이 필요하다.
