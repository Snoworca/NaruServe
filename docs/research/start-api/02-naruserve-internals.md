# `start` API 내부 제약 조사: NaruServe 구조 안에 "정적 `start` 한 줄로 서버 기동"을 넣을 때

조사 기준: 2026-10-23, 저장소 `/Users/beom/work/git/NaruServe` (브랜치 main, HEAD 7d7831b). 코드·SRS·build 파일은 읽기만 했고 수정하지 않았다.
표기: **[확인]** 은 코드나 SRS를 직접 읽고 `파일:줄` 또는 Requirement ID로 근거를 단 사실, **[추정]** 은 근거에서 끌어낸 판단이나 JDK/Kotlin 일반 지식이라 이 저장소에서 실행해 보지 않은 것이다. **(미확정)** 은 사양서에 이름이 없는 항목이다(CLAUDE.md 규칙).

---

## 1. 요약

1. **`start`는 Core(`NaruServe-api`)의 `RestApp` static 메서드로 넣어도 Core 금지 목록을 어기지 않는다.** `RestApp.create()`가 이미 ServiceLoader로 엔진 경계를 넘고 있어서(`RestApp.java:35`, `AppFactoryLoader.java:20-40`), `start`는 `create()` → 설정 람다 → `listen()`만 부르면 된다. 엔진·Gson 타입, `java.nio.file`, `android.*`는 한 줄도 필요 없다. [확인+추정]
2. 따라서 후보 (a) `RestApp.start(...)`가 기술적으로 가장 단순하다. (b) `NaruServe` 모듈의 새 클래스는 동작은 같지만 발견성이 떨어지고 docs 컴파일 테스트 classpath가 바뀐다. (c) 둘 다 두는 안은 같은 일을 하는 API가 둘이 되어 권하지 않는다.
3. `listen()`은 **블로킹이 아니다.** 바인딩까지 동기로 끝낸 뒤 반환하고(`DefaultRestApp.java:464-481`), `main`이 끝나도 non-daemon keeper 스레드가 JVM을 붙잡는다(`KeeperThread.java:11-30`, FR-ENGINE-006 AC-4/AC-5). `start`도 블로킹하지 않고 `ServerHandle`을 돌려주는 것이 현재 계약과 맞다.
4. **가장 큰 설계 문제는 반환값이다.** 지금 `ServerHandle`에는 `port()`·`address()`·`state()`뿐이고 종료 수단이 없다(`ServerHandle.java:5-15`, IR-APP-005 AC-3이 "정확히 이 셋"으로 고정). `start`가 `RestApp`을 숨기면 호출자가 `shutdown`을 부를 방법이 없다. 선택지는 `ServerHandle`에 종료 메서드 추가, `RestApp` 반환, JVM 종료 훅만 의존 세 가지이며 각각 고정된 AC나 테스트를 건드린다(§3, §8).
5. **고정 테스트 충돌이 실제로 있다.** `HttpApiSurfaceTest`가 `RestApp`의 static 메서드를 `[builder, create]`로 못박고 있다(`HttpApiSurfaceTest.java:370-377`; 근거 AC는 IR-ENGINE-001 AC-7, IR-STATIC-002 AC-5). `start`를 추가하면 이 테스트와 해당 AC 문구를 요구사항 변경으로 고쳐야 한다. 테스트를 약화하는 것이 아니라 새 요구사항이 기존 AC를 대체하는 절차다(wave 5~7이 이미 같은 방식으로 이 테스트를 재지정했다).
6. 이름은 `start`가 안전하다. `run`은 Kotlin stdlib 범위 함수와 같은 이름이라 혼동 여지가 있고, `listen`은 인스턴스 메서드와 이름이 겹친다. 시그니처 `start(int, Configurer<RestApp>)`와 `start(Configurer<RestApp>)`는 Kotlin 후행 람다·Java 람다 모두 모호하지 않다(arity가 다르다). 단 같은 arity에 다른 함수형 인터페이스 overload를 더하면 Java가 모호해진다.
7. `start`가 감싸면 안 되는 것: 바인드 주소 기본값(`127.0.0.1`), 보안 경고, Host allowlist, `trustProxy`, `listen`의 예외 종류. 감싸야 할 것: 설정 람다가 던질 때의 앱 정리(`shutdown`으로 NEW → TERMINATED)와 (선택) JVM 종료 훅.
8. 필요한 SRS 변경: DX 스코프(prefix DX)에 새 FR 1건과, 고정 AC 문구를 바꿀 기존 ID 4~6건(IR-APP-005, IR-ENGINE-001, IR-STATIC-002, IR-APP-002, FR-APP-001 등). `docs/api/`는 `app.md`, `lifecycle.md`, `README.md`, `kotlin.md`, `express-migration.md`, `defaults.md`를 건드린다. 새 Requirement ID는 `add_requirement`가 할당하므로 이 문서는 ID를 짓지 않는다.
9. 영향받는 테스트·예제는 많지 않다. 깨지는 것은 `HttpApiSurfaceTest`(static 목록)와, `ServerHandle`을 늘릴 경우 `AppLifecycleApiTest.java:197`뿐이고, 나머지는 새 테스트·새 문서 블록 추가 의무(FR-KT-004의 Kotlin 문서 예제 마커, `DocsExamplesCompileTest`)다. README Quick start를 바꾸면 OPS-REL-001 AC-2의 README↔docs/api/README 동일성 테스트가 걸린다.

---

## 2. 현재 생명주기 사실표

| 항목 | 현재 동작 | 근거 |
|---|---|---|
| 진입점 | `RestApp.create()`는 `AppFactory`를 `ServiceLoader.load(AppFactory.class, RestApp.class.getClassLoader())`로 찾는다. 컨텍스트 클래스로더는 쓰지 않는다. 없으면 `IllegalStateException`(메시지에 `com.snoworca.naruserve:NaruServe`와 `RestApp.builder()` 안내), 둘 이상이면 provider 클래스명을 나열한 `IllegalStateException` | `RestApp.java:35-37`, `AppFactoryLoader.java:20-40`, FR-APP-001 AC-2/3/4 |
| 기본 조립 | `DefaultAppFactory.create()` = `RestApp.builder()` + `new TinyHttpdAdapter()` + `new GsonJsonCodec()` + 로거(Android면 Logcat, 아니면 Console) | `DefaultAppFactory.java:26-29`, FR-ENGINE-009 |
| 명시 조립 | `RestApp.builder().httpAdapter(..).jsonCodec(..).logger(..).build()`; adapter·codec 없으면 `IllegalStateException` | `RestApp.java:41-43, 237-277`, IR-APP-001 AC-2 |
| `listen(int)` 계약 | 포트 `0..65535` 밖이면 상태 변경 전에 `IllegalArgumentException`. 어댑터 없으면 `IllegalStateException`. `NEW→STARTING`은 compareAndSet 승자 하나만 통과, 둘째 호출은 `IllegalStateException` | `DefaultRestApp.java:464-472`, `AppLifecycle.java:55-59`, FR-APP-009 AC-1, IR-APP-005 AC-4 |
| `listen` 내부 순서 | `onStart` 콜백을 등록 순서로(콜백마다 pending stop 검사) → 컨텍스트 freeze → 라우트 테이블 freeze → `ServerOptions.freeze(port, ..)` → 비루프백이면 경고 1회 → `adapter.bind` → `markRunning` → `ServerHandle` 반환 | `DefaultRestApp.java:483-531`, FR-APP-009 AC-2, FR-ENGINE-002 AC-3 |
| **블로킹 여부** | **블로킹 아님.** 바인드가 끝나면 호출 스레드로 반환한다. accept/worker는 TinyHttpd 스레드(daemon) | `DefaultRestApp.java:464-531`, object.md:220("기동 및 바인딩은 동기적으로 완료한 뒤 호출 스레드를 반환") |
| JVM 생존 | 바인드 성공 직후 non-daemon `naruserve-keeper-<port>` 스레드 1개를 띄워 어댑터 `stop`까지 `CountDownLatch`로 대기시킨다. TinyHttpd 자체 스레드는 daemon이라 keeper가 없으면 `main`만 `listen`하고 끝나는 프로세스가 즉시 종료한다. 바인드 실패 시 keeper는 만들지 않는다 | `KeeperThread.java:6-30`, `TinyHttpdAdapter.java:73-80`, FR-ENGINE-006 AC-4/AC-5, 자식 JVM 테스트 `JvmLifetimeTest.java:24-37` |
| `ServerHandle` | `port()`, `address()`, `state()`만 있다. 종료·대기 메서드 없음. 익명 클래스 구현 | `ServerHandle.java:5-15`, `DefaultRestApp.java:515-530`, IR-APP-005 AC-3(`AppLifecycleApiTest.java:197`이 정확히 이 셋을 단언) |
| `AppState` | `NEW, STARTING, RUNNING, STOPPING, FAILED, TERMINATED` | `AppState.java:5-7`, IR-APP-005 AC-1 |
| 시작 실패 | `onStart` 예외, freeze 검증 실패, `bind` 실패 모두 `failStart`: `FAILED` → (bind 시도했다면) `adapter.stop(1s)`+`release` → 소유 리소스 역순 close → 로거 flush/close → `TERMINATED`. `onStop`은 실행하지 않는다. unchecked 원인은 그대로, checked 원인은 `AppStartException`으로 감싸 던진다. 포트는 남지 않는다 | `DefaultRestApp.java:552-591`, FR-APP-010 AC-1~6, FR-ENGINE-002 AC-6 |
| 시작 중 shutdown | `STARTING`이면 pending stop 기록 후 `listen`이 다음 검사 지점에서 `AppStartException("shutdown was requested while starting")` | `DefaultRestApp.java:644-655, 539-541`, FR-APP-012 AC-2/4 |
| `shutdown(long, TimeUnit)` | 음수 timeout은 `IllegalArgumentException`, null unit은 NPE. NEW면 즉시 `TERMINATED`(콜백 없이 로거 flush/close만). RUNNING이면 `beginStop` 승자 하나가 시퀀스 실행, 나머지는 `TERMINATED`까지 자기 timeout 안에서 대기. TERMINATED면 즉시 반환. 반복 호출 안전 | `DefaultRestApp.java:629-686`, FR-APP-011 AC-5, FR-APP-012 AC-1/5 |
| 종료 시퀀스 | before-wait 참가자(WS/SSE 알림 등) → `adapter.stop(남은 시간)` → force-close 참가자 → `onStop` 콜백(서비스 아직 살아 있음) → after-onStop 참가자(임시 업로드 삭제) → 소유 리소스 역순 close → 로거 flush/close → `adapter.release` → `TERMINATED`. 각 단계 실패는 ERROR 로그 후 계속, 첫 `Error`만 TERMINATED 뒤 재던짐 | `DefaultRestApp.java:701-751`, FR-APP-011 AC-1~8, FR-APP-020 |
| 요청 스레드에서 shutdown | 별도 non-daemon `naruserve-shutdown` 스레드에서 시퀀스 실행하고 즉시 반환 | `DefaultRestApp.java:656-672`, FR-APP-011 AC-9 |
| 종료 훅 | 라이브러리는 JVM shutdown hook을 **등록하지 않는다**(main 소스에서 `addShutdownHook` 사용처 없음). 예제와 docs가 사용자 코드로 등록한다 | `examples/java/.../JavaExample.java:74`, `examples/kotlin/.../KotlinExample.kt:68`, `docs/api/app.md:252` |
| 재시작 | 종료된 앱은 재시작 불가, 새 `RestApp`을 만든다 | FR-APP-012 AC-6, object.md:766, `docs/api/lifecycle.md` 상태 절("종료된 앱 대신 새 `RestApp`을 만드세요") |
| 설정 가능 시점 | 모든 등록·설정 메서드는 `NEW`에서만. 이후는 `IllegalStateException("<method> not allowed in state <state>")` | `AppLifecycle.java:37-45`, FR-APP-002 AC-3/4 |
| 스레드 | `DefaultRestApp` 생성자는 스레드를 만들지 않는다(WS idle scheduler는 freeze 이후 생성). 따라서 `create()`만 하고 `listen`하지 않은 앱은 정리할 스레드가 없다 | `DefaultRestApp.java:101-170`, `:1001` |
| 기본 바인드 | `127.0.0.1` 명시 바인드(`InetAddress.getLoopbackAddress()` 금지) | `RestApp.java:122-123`, `ServerOptions.java:14`, FR-ENGINE-002 AC-1, IR-ENGINE-001 AC-5 |
| 상태 다이어그램 | object.md가 이미 `NEW --listen/start--> STARTING`으로 적었다 | object.md:727 |

---

## 3. 배치 후보 a/b/c 비교

공통 전제 **[확인]**: `NaruServe-api`는 `android/`, `jakarta/servlet/`, `com/google/gson/`, `com/snoworca/tinyhttpd/`, `kotlin/`, `org/slf4j/`를 참조하면 빌드가 실패한다(`build.gradle:137-143`의 `classRefPrefixes`, `checkClassReferences`). 일곱 개 Android 대상 모듈은 animal-sniffer API 21 검사를 받는다(`build.gradle:19, 307-315`). `NaruServe` 부트스트랩 모듈은 `api: NaruServe-api`, `implementation: tinyhttpd·gson·logcat`이다(`build.gradle:67-68`).

| | (a) `RestApp`의 static 메서드 (`NaruServe-api`) | (b) `NaruServe` 모듈의 새 클래스 | (c) 둘 다 |
|---|---|---|---|
| 구현 모양 | `static ServerHandle start(int, Configurer<RestApp>)` 등. 본문은 `create()` → `configure` → `listen`. 엔진을 직접 모름(ServiceLoader 경유) | `com.snoworca.naruserve.bootstrap` 아래 public final 클래스 + static 메서드. `new DefaultAppFactory().create()`를 직접 불러도 되고 ServiceLoader를 쓰지 않아도 된다 | (a)가 `create()`를 거치고 (b)는 같은 일을 하거나 엔진 전용 기능을 얹음 |
| Core 공개 API 금지 목록(엔진 타입, `java.nio.file`, `CompletableFuture`, `android.*`, `javax/jakarta.servlet.*`, Kotlin stdlib) | **위반 없음.** 시그니처에 `int`, `Configurer<RestApp>`, `ServerHandle`만 등장. JVM 종료 훅을 쓴다면 `java.lang.Runtime`뿐이라 API 21에도 있음 [추정: `Runtime.addShutdownHook`은 API 1, animal-sniffer가 빌드에서 확인할 것] | **위반 없음.** 이 모듈은 어차피 Core가 아니다. 단 public 시그니처에 TinyHttpd·Gson 타입이 새면 `checkDependencyRules`·`BuildLayoutTest.java:262-266`(TinyHttpd는 implementation-scope) 취지에 어긋난다 | (a)와 (b) 각각의 판정을 따른다 |
| 모듈 의존 규칙(`checkDependencyRules`, `checkClassReferences`) | 영향 없음. `NaruServe-api`가 `kotlin/`·`gson`·`tinyhttpd` 참조를 못 갖는 조건과 무관 | 영향 없음. `BuildLayoutTest.java:111,123-130`이 `NaruServe`의 모든 main 소스 패키지가 `com.snoworca.naruserve.bootstrap`(하위 포함) 아래여야 한다고 검사하므로 클래스는 그 패키지에 둬야 한다. 클래스 이름은 **(미확정)** | 위와 같음 |
| Kotlin 호환 규칙 | `Configurer`는 이미 `@FunctionalInterface`(`Configurer.java:5`), 패키지는 `@NullMarked`(`package-info.java`)라 platform type이 안 남는다. 새 키워드 이름 없음. Java 인터페이스 static 메서드를 Kotlin에서 `RestApp.start(..)`로 부르는 것은 `RestApp.create()`와 같은 경로라 선례가 있다(jvmTarget 1.8, `build.gradle:45`) | 같은 규칙 적용. 단 `NaruServe`는 `@NullMarked` 패키지 `bootstrap`(`package-info.java`)이라 어노테이션 정책은 동일. FR-KT-001이 검사하는 패키지는 api/spi/routing 세 곳이라 이 모듈은 자동 검사 대상이 아니다(수동 확인 필요) [확인: FR-KT-001 요구문] | 위와 같음 |
| Java 8 / API 21 | 인터페이스 static 메서드는 이미 `create()`·`builder()`가 쓰는 기능(Android 쪽은 desugaring 전제, object.md:13·§13). 새 위험 없음 | 클래스 static 메서드라 desugaring 의존도 더 낮음 | 위와 같음 |
| 기존 고정 AC·테스트와 충돌 | **충돌.** `HttpApiSurfaceTest.java:370-377`이 `RestApp`의 static 이름 목록을 `[builder, create]`로 단언. 근거 AC: IR-ENGINE-001 AC-7, IR-STATIC-002 AC-5(wave 6에서 "create()와 builder()가 여전히 유일한 static"으로 재지정). `ServerHandle`을 확장하면 IR-APP-005 AC-3과 `AppLifecycleApiTest.java:197`도 충돌. FR-APP-002 AC-5·FR-HTTP-008 AC-6의 "`RestApp`/`Builder`를 반환하는 public static은 builder·create뿐" 테스트(`DefaultRestAppTest.java:271-284`, `HttpApiSurfaceTest`의 `statics`)는 `start`가 `ServerHandle`을 반환하면 통과, `RestApp`을 반환하면 깨진다 | 충돌 없음. `RestApp`에 손대지 않는다. 단 `ServerHandle` 확장 선택 시 위 충돌은 동일 | 충돌은 (a)와 같음 |
| 발견성(주니어 관점) [추정] | 높음. `RestApp.`까지 치면 IDE 자동완성에 `create`, `builder`, `start`가 함께 뜬다. README Quick start가 `import com.snoworca.naruserve.RestApp` 한 줄로 끝난다 | 낮음. `import com.snoworca.naruserve.bootstrap.<클래스>`를 따로 알아야 한다. 부트스트랩 모듈의 문서상 역할은 "`RestApp.create()` 뒤의 SPI provider"(`bootstrap/package-info.java`, README 모듈표 `README.md:35`)라 역할 확장 문구도 고쳐야 한다 | 두 진입점이 공존해 "무엇을 써야 하나"가 생긴다 |
| docs/api 예제 컴파일 테스트 | `DocsExamplesCompileTest`는 `NaruServe-api` classpath로 java 블록을 컴파일한다(루트 `build.gradle:466-470`: `testImplementation project(':NaruServe-api')`, 부트스트랩은 `testRuntimeOnly`). `RestApp.start`는 그대로 컴파일된다 | docs 블록이 부트스트랩 클래스를 import하면 컴파일이 안 된다. 루트 `build.gradle`에 `testImplementation project(':NaruServe')`를 더하거나 skip 항목(`DocsExamplesCompileTest.java:76-91`)을 늘려야 한다 | (a)를 문서 기준으로 삼으면 영향 없음 |
| R8/ProGuard | 변화 없음 | 직접 참조라 keep 규칙은 불필요(`DefaultAppFactory`만 ServiceLoader 리플렉션 때문에 `naruserve-bootstrap.pro`가 필요: FR-ENGINE-009 AC-4) | 변화 없음 |
| 엔진 교체·Servlet 사용자 | `create()`가 provider를 하나만 허용하므로 `start`도 같다. 커스텀 엔진 사용자는 계속 `builder()`+`listen` | 부트스트랩이 클래스패스에 있어야만 의미가 있어 같은 제약 | 같음 |
| 판정 | **권장.** 기술 제약 위반 없음. 고칠 것은 고정 AC·테스트뿐 | 가능하지만 (a)가 불가능할 때의 대안. `RestApp` static 목록 고정을 건드리고 싶지 않을 때만 의미가 있다 | **비권장.** 같은 일을 하는 API 둘, 문서·테스트·Kotlin 마커가 두 배 |

### 3.1 (d) 변형 후보 (조사 중 발견, 참고용)

- **(d1) `RestApp.Builder`에 `start` 계열 추가**: `RestApp.builder().httpAdapter(a).jsonCodec(c).start(8080, app -> ...)` 같은 식. 명시 조립 사용자도 같은 한 줄 경험을 쓰지만 `Builder` 메서드가 늘고 주니어의 기본 경로(`create()`)와는 무관하다. [추정]
- **(d2) `NaruServe-kotlin`에 확장/최상위 함수**: Core에 Kotlin을 강제하지 않는 CLAUDE.md 규칙에는 맞지만 Java 사용자는 못 쓴다. `NaruServe-kotlin`은 `api: NaruServe-api` + kotlin-stdlib만 가진다(`build.gradle:69`). Java 주니어가 목표이므로 보조 수단에 그친다.

### 3.2 반환값 선택지 (후보 a/b/c 공통 문제)

| 반환 | 장점 | 충돌·위험 |
|---|---|---|
| R1. `ServerHandle` + `ServerHandle`에 종료 메서드 추가 (이름 **(미확정)**, 예: `shutdown(long, TimeUnit)`) | Express의 `server.close()`와 같은 모양, 기존 `listen`의 반환형과 동일해 일관됨 | IR-APP-005 AC-3("정확히 `port()`, `address()`, `state()`")과 `AppLifecycleApiTest.java:197` 수정 필요. `ServerHandle`은 interface라 외부 구현체가 있으면 깨지지만, 구현체는 `DefaultRestApp` 익명 클래스 하나뿐이다 [확인: 소스 검색] |
| R2. `RestApp` 반환 | `app.shutdown(..)`을 그대로 쓰고 `ServerHandle` 변경 없음 | `RestApp` 반환 static이 생겨 FR-APP-002 AC-5, FR-HTTP-008 AC-6의 "builder·create뿐" 단언(`DefaultRestAppTest.java:271-284`)을 깬다. 반환된 앱은 이미 `RUNNING`이라 `app.get(..)` 같은 설정 호출이 모두 `IllegalStateException`이 되는 함정이 있다(`AppLifecycle.java:37-45`). Express의 `app.listen`이 서버 객체를 돌려주는 관례와도 다르다 |
| R3. `void` | 가장 단순 | 포트 0을 쓰는 테스트가 포트를 알 수 없다. 종료는 JVM 훅 전용이 된다 |
| R4. `ServerHandle`을 그대로 두고 `start`가 JVM 훅을 자동 등록 | 변경이 가장 적다 | 테스트·임베디드 사용에서 중간 종료 수단이 없다. 훅이 앱 참조를 JVM 수명 동안 잡는다(§8 위험 4) |

---

## 4. 시그니처·이름 분석

### 4.1 이름 충돌 점검

| 이름 | 기존 API와의 관계 | 판정 |
|---|---|---|
| `start` | `RestApp`에 같은 이름 메서드 없음. 그러나 `onStart(LifecycleCallback)`(`RestApp.java:115`)와 어근이 같다. `onStart`는 "`listen` 안에서 포트를 열기 전에 실행되는 콜백 등록"이고 `start`는 "앱 생성+설정+`listen`"이라 뜻이 다르다. 상태 기계에는 `STARTING`이 있고 object.md:727이 `listen/start`를 이미 같은 화살표에 적었다 | **사용 가능.** 문서에서 `onStart`(콜백)와 `start`(진입점)를 구분해 써야 한다 [추정] |
| `run` | `Next.run()`(object.md:603, `next.run()`)이 이미 있다. Kotlin stdlib에도 `run { }`/`T.run { }` 범위 함수가 있어 `RestApp.run { }`이나 `with(app) { run { } }`에서 어느 쪽이 불리는지 헷갈릴 수 있다. Spring Boot의 `SpringApplication.run`과는 닮는다 | **피한다** [추정: Kotlin 이름 해석 규칙에서의 판단이며 이 저장소에서 컴파일해 확인하지 않음] |
| `listen` | 인스턴스 `listen(int)`가 이미 있다. 같은 이름 static을 더하면 Java 문법상 가능하지만 `RestApp.listen(8080, ..)`와 `app.listen(8080)`이 다른 일(앱 생성 여부)을 하게 되어 오해 소지가 크다. Express의 `app.listen`과도 의미가 달라진다 | **피한다** |
| `ServerHandle.shutdown`/`stop`/`close` (R1 채택 시) | `RestApp.shutdown(long, TimeUnit)`과 같은 이름·시그니처를 쓰면 일관된다. `close()`는 `AutoCloseable` 구현 문제(checked `Exception`)가 따라온다 | `shutdown(long, TimeUnit)` 우선 **(미확정)** |

Kotlin 하드 키워드(`as, fun, in, is, object, typealias, typeof, val, var, when`)와 겹치는 후보는 없다(FR-KT-002 AC-1이 reflection으로 자동 검사, `KotlinContractChecks.java:109-110`).

### 4.2 시그니처 후보

| 후보 | Java 호출 | Kotlin 호출 | 모호성 판정 |
|---|---|---|---|
| S1. `static ServerHandle start(int port, Configurer<RestApp> configurer)` | `RestApp.start(8080, app -> { app.get("/hello", (req, res) -> res.send("hi")); });` | `RestApp.start(8080) { app -> app.get("/hello") { _, res -> res.send("hi") } }` | 인자 2개 overload가 이것 하나뿐이면 모호하지 않다 |
| S2. `static ServerHandle start(Configurer<RestApp> configurer)` (포트 생략) | `RestApp.start(app -> ...)` | `RestApp.start { app -> ... }` | 인자 수가 S1과 달라 모호하지 않다. 그러나 **기본 포트가 무엇인지**가 문제(§5, §8). object.md 예제는 8080(`object.md:131, 688`), README·docs는 3000(`README.md:55`, `docs/api/README.md:21`)으로 갈린다 |
| S3. `static ServerHandle start(int port)` (설정 없이) | `RestApp.start(8080)` | `RestApp.start(8080)` | 라우트가 없는 앱을 띄우는 것이라 쓸모가 거의 없고, 주니어가 "왜 404만 나오지" 하게 된다 [추정]. 만들지 않는 편이 낫다 |
| S4. `start(int port, String host, Configurer<RestApp>)` | | | IR-ENGINE-001 Rationale이 이미 **거부한 대안**이다("Express-style `app.listen(port, host)`는 LAN 바인딩과 allowed host names를 갈라놓음, 사용자가 요구하면 `bindAddress`를 설정하는 overload로 재검토"). 이 조사에서 요구가 새로 나온 것은 아니므로 넣지 않는다 |

**Kotlin 후행 람다·SAM 변환 근거 [확인+추정]**
- `Configurer<T>`는 `@FunctionalInterface`이며 `void configure(T)` 하나만 가진다(`Configurer.java:5-9`).
- 반환값이 있는 식을 마지막에 둔 람다가 `void` SAM에 들어가는 선례가 이미 통과 중이다: `app.server { o -> o.bindAddress("0.0.0.0").allowedHosts("myphone.local").maxHeaderSize(32768) }`는 `ServerOptions`를 반환하는 체인이 마지막 식이고, IR-ENGINE-001 AC-6이 이 Kotlin 컴파일·실행을 검증한다. 따라서 `RestApp.start(3000) { app -> app.get(..) { .. } }`처럼 `RestApp`을 반환하는 체인이 마지막 식이어도 Unit 코어션으로 컴파일된다.
- FR-KT-003의 콜백 목록(`CallbackMatrix.kt:15-31`)에는 이미 `Configurer`가 있어 **새 콜백 타입이 늘지 않는다**. 인벤토리 테스트는 그대로 통과한다. 다만 FR-KT-003 AC-3이 나열하는 호출 목록에 `start`용 Kotlin 테스트를 하나 추가하는 것이 CLAUDE.md의 "공개 API 변경 시 Kotlin 컴파일·실행 테스트 유지(T11)"에 맞다.
- **Java 쪽 주의 [추정: JLS의 암시적 람다 적용 가능성 규칙]**: 같은 arity에서 `start(int, Configurer<RestApp>)`와 `start(int, LifecycleCallback)`처럼 함수형 인터페이스만 다른 overload를 두면 암시적 타입 람다(`app -> ...`)는 어느 쪽인지 컴파일러가 못 가려 모호 오류가 난다. `start` 계열에는 overload를 인자 수로만 나누는 것이 안전하다.
- 설정 람다 안에서 예외를 던지려면 `Configurer.configure`가 `throws`를 선언하지 않아 unchecked만 가능하다. 반면 `RouteHandler`·`LifecycleCallback`은 `throws Exception`을 허용한다(`docs/api/app.md` 수명주기 절). `start`용으로 `Configurer`를 쓰면 checked 예외는 람다 안에서 직접 처리해야 한다. 이것을 허용하는 새 함수형 인터페이스를 만들면 CLAUDE.md의 "모든 콜백은 `@FunctionalInterface`", FR-KT-003 인벤토리·`CallbackMatrix` 갱신 의무가 생기므로, `Configurer` 재사용을 기본으로 보는 것이 비용이 작다 [추정].

### 4.3 호출 순서(문서에 그대로 적을 내용)

`create()`(NEW, 스레드 없음) → 설정 람다(NEW라서 `get/use/server/onStart/trustProxy/...` 모두 허용, `AppLifecycle.java:37-45`) → `listen(port)`(`onStart` → freeze → 경고 → bind → RUNNING). 설정 람다 안에서 `app.onStart(..)`를 등록할 수 있다는 점이 중요하다. 서비스 등록(`ctx.register`)은 여전히 `onStart` 안에서만 가능하다(FR-APP-007, `docs/api/lifecycle.md` register 절).

---

## 5. 기존 기능과의 중복과 감싸기 규칙

| 기존 기능 | 현재 위치·계약 | `start`의 처리 규칙 |
|---|---|---|
| `RestApp.builder()` | 명시 조립 진입점(IR-APP-001). adapter·codec 필수 | **건드리지 않는다.** `start`는 `create()` 경로 전용이다. 커스텀 엔진·Servlet·테스트 하네스는 계속 `builder()`+`listen`. `start`가 builder를 받는 overload를 두면 DefaultAppFactory와 책임이 겹친다 |
| `app.server(Configurer<ServerOptions>)` | 바인드 주소·허용 Host·한도(IR-ENGINE-001). 호출 순서대로 하나의 옵션에 누적 | **감싸지 않는다.** 설정 람다가 `RestApp`을 받으므로 `app.server(o -> o.bindAddress("0.0.0.0"))`를 람다 안에서 그대로 쓴다. `start(port, host, ..)` 같은 중복 경로 금지(S4) |
| `app.trustProxy(boolean)` | 기본 false(FR-ENGINE-008) | 감싸지 않는다. 람다 안에서 `app.trustProxy(true)` |
| 기본 바인드 `127.0.0.1` | FR-ENGINE-002 AC-1, IR-ENGINE-001 AC-5, object.md:37·853 | **`start`가 기본값을 바꾸면 안 된다.** Express 사용자는 `0.0.0.0`을 기대하지만(`docs/api/express-migration.md:151` "Express의 `app.listen(3000)`은 모든 인터페이스에서 받습니다"), 사양의 "기본 안전 설정" 원칙(object.md §1.2-8)이 우선한다. `start`는 `bindAddress`를 설정하지 않는다 |
| LAN 경고 | 비루프백이면 `listen()`이 `warn` 1회(FR-ENGINE-002 AC-3) | `listen` 안에서 나오므로 `start`가 손대지 않아도 동일하게 나온다. `start`가 로그를 억제하거나 대신 남기지 않는다 |
| Host allowlist | 바인드 주소·`localhost`·IP 리터럴 외 `421`(FR-ENGINE-002 AC-4) | 변경 없음. `allowedHosts`는 람다 안에서 |
| 포트 0 | `listen(0)`은 빈 포트, `ServerHandle.port()`로 확인(FR-ENGINE-002 AC-5) | `start(0, ..)`를 허용하고 그대로 통과시킨다. 반환 `ServerHandle.port()`가 유일한 확인 수단이므로 `start`가 `ServerHandle`을 돌려주는 것이 필수에 가깝다 |
| `ServerSettings` | 어댑터가 받는 SPI 값(`spi/ServerSettings.java`, IR-ENGINE-002). `listen`이 `serverOptions.freeze(port, ..)`로 만든다(`DefaultRestApp.java:497`) | **`start`는 접근하지 않는다.** SPI는 엔진 경계이고 `start`는 사용자 경계다 |
| 로거 | `create()`가 Logcat/Console 선택(FR-ENGINE-009 AC-3) | `start`가 바꾸지 않는다. 람다 안에서 `app.logger(l)` 가능 |
| JSON codec | `create()`가 Gson 기본(FR-ENGINE-009 AC-2) | 바꾸지 않는다 |
| 예외 | `listen`: 포트 범위 `IllegalArgumentException`, 시작 실패 unchecked는 그대로·checked는 `AppStartException`(FR-APP-010, IR-APP-005 AC-4/5) | **그대로 전달한다.** 잡아서 로그만 남기거나 `System.exit`하지 않는다. `create()`의 `IllegalStateException`(provider 없음/중복)도 그대로 |
| 종료 | `shutdown(long, TimeUnit)`(FR-APP-011/012), 요청 스레드 안전(AC-9) | `start`가 새 종료 경로를 만들지 않는다. R1이면 `ServerHandle.shutdown`은 `app.shutdown`에 위임만 한다 |
| 블로킹 | 없음 | `start`도 블로킹하지 않는다. "서버가 끝날 때까지 `main`을 붙잡는다"는 필요 없다. keeper가 한다 |

**감싸야 하는 것(설계 제안, 구현 안 됨) [추정]**
1. **설정 람다가 예외를 던졌을 때 정리.** 이 시점 앱은 `NEW`이고 스레드·소켓이 없지만 로거가 열려 있을 수 있다. `app.shutdown(0, ..)`을 부르면 NEW → TERMINATED로 가며 로거를 flush/close한다(`DefaultRestApp.java:638-643`, FR-APP-012 AC-1). 그 뒤 원래 예외를 다시 던진다. `listen`이 던진 경우는 이미 TERMINATED이므로 `shutdown`이 즉시 반환한다(같은 switch의 `TERMINATED` 분기, `:675-676`). 단 포트 범위 오류(`IllegalArgumentException`)는 상태가 NEW인 채 던지므로(IR-APP-005 AC-4) 같은 정리가 필요하다.
2. **null 인자**는 `create()` 전에 NPE로 거른다(`IR-APP-005 AC-2`의 `onStart(null)` NPE 선례).
3. **JVM 종료 훅(선택).** Spring Boot처럼 SIGTERM/Ctrl-C에 우아하게 종료하려면 필요하다. 사용자가 지금은 예제마다 직접 쓴다. 쓴다면 §8의 열린 질문 3·4를 먼저 정해야 한다.

---

## 6. 필요한 SRS·docs/api 변경 목록

모두 Stability `evolving`, Status `verified`다(IR-APP-001/002/005, FR-APP-001/002/009~012, FR-ENGINE-002/006/009, IR-ENGINE-001, OPS-ENGINE-002, FR-KT-003/004, OPS-DX-001/002, OPS-REL-001 확인). `draft`/`deprecated` 게이트(CLAUDE.md)에 걸리는 항목은 없다. 활성 target은 `api-usability`(epic #21)이고 DX 요구가 같은 방식으로 `RestApp`에 `group`, `cors`를 더했다(FR-DX-009/010).

### 6.1 새 Requirement (필요)

| 항목 | 내용 |
|---|---|
| 필요 여부 | **필요.** 기존 어떤 요구사항도 정적 `start`를 다루지 않는다. 사양서(object.md)와 SRS 전체에서 `start`라는 진입점 이름은 상태 기계의 `listen/start` 표기(object.md:727)뿐이다 |
| 위치 | Developer Experience 스코프(`16.developer-experience.srs.md`, prefix DX)가 자연스럽다. 이유: api-usability target의 주제와 같다. 대안은 Application 스코프(prefix APP, `IR-APP-005` 인접) |
| ID | **MCP `add_requirement`가 할당한다**(CLAUDE.md: ID 수기 변경 금지). 이 문서는 지어내지 않는다 |
| 내용에 들어갈 것 | 정적 진입점의 위치(a/b), 시그니처(S1/S2), 반환형(R1~R4), 설정 람다 예외 시 정리, 기본 바인드 불변, `create()` 오류 전달, 블로킹 없음, 종료 훅 정책, Kotlin 컴파일·실행 테스트 |
| TDD | 새 AC마다 실패하는 테스트 먼저(CLAUDE.md). 테스트 위치: 동작은 `NaruServe`(부트스트랩) 모듈 테스트, `NaruServe-api` 테스트에서는 provider 없음 오류 전달만 검증 가능(FR-APP-001 AC-3: `NaruServe-api` 테스트 실행에는 provider가 없다) |

### 6.2 기존 Requirement 중 AC 변경이 필요한 것

| ID | 바꿔야 할 AC | 이유 |
|---|---|---|
| **IR-ENGINE-001** | AC-7: "`create()` and `builder()` still the only static methods" | `start` 추가 시 거짓이 된다. `start`를 허용 목록에 넣는 새 문구가 필요 |
| **IR-STATIC-002** | AC-5: 같은 "create()와 builder()가 유일한 static" 재지정 문구 | 위와 동일(wave 6 재지정). 이후 wave의 같은 문구(IR-WS-001, IR-SSE-001 등)도 확인 필요 |
| **IR-APP-002** | AC-2: wave 4 `RestApp` 표면 테스트(`HttpApiSurfaceTest`)의 근원 | 표면 목록의 근원 요구. 새 요구가 "현재 표면" 정의를 이어받는지 정해야 한다 |
| **IR-APP-005** | AC-3: `ServerHandle`이 "정확히 `port()`, `address()`, `state()`" (R1 채택 시만) | R1이면 종료 메서드가 추가되어 AC-3 문구가 바뀐다. Requirement 본문에도 "`ServerHandle` with `int port()`, `String address()` and `AppState state()`"가 있다 |
| **FR-APP-001** | AC-1: "`RestApp.create()` is a public static ... and `RestApp` declares no other `create` overload" | `create` overload를 더하지 않으므로 그대로 참. 그러나 요구문의 "정적 진입점은 create/builder" 취지를 보충할 수 있다(선택) |
| **FR-APP-002** | AC-5, **FR-HTTP-008** AC-6: "`RestApp` 또는 `Builder`를 반환하는 public static은 builder/create뿐" | `start`가 `ServerHandle`을 반환(R1/R3/R4)하면 문구 변경 불필요. **R2(`RestApp` 반환)면 두 AC 모두 재지정** 필요 |
| FR-APP-009 | AC-2(순서), AC-1(동시 listen) | 변경 불필요. `start`는 `listen`을 부르는 위임이므로 시작 순서 계약을 그대로 상속한다. 보충 참조만 |
| FR-APP-010 | AC-1~6 | 변경 불필요. "설정 람다 실패는 `listen` 이전이라 이 요구의 범위 밖"임을 새 요구에서 명시 |
| FR-ENGINE-009 | AC-1~5 (`create()`의 provider 조립) | 변경 불필요. 단 부트스트랩 문서 문구("backing `RestApp.create()`")는 (b) 채택 시 갱신 |
| FR-ENGINE-006 | AC-4/5 (keeper, 자식 JVM) | 변경 불필요. `start` 경로로도 같은 자식 JVM 시나리오를 한 번 더 돌리는 테스트 추가 권장 |
| OPS-ENGINE-002 | AC-1 "keeper" 문구와 AC-6 defaults 비교 테스트 | `docs/api/app.md`에 `start` 절을 추가할 때 문구가 `WaveFiveDocsTest`의 `kept`/단언을 깨지 않는지 확인(§7) |
| OPS-DX-001 | AC: 실행 가능한 시작 예제 | 예제를 `start`로 바꿀지 결정. 바꾸지 않아도 요구는 만족 |
| OPS-DX-002 | 오류·기본값 문서의 모순 제거 | `start`가 `0.0.0.0`을 기본으로 하지 않는다는 문구가 `express-migration.md` §4와 일치해야 함 |
| OPS-REL-001 | AC-2: README Quick start == `docs/api/README.md` Quick start | Quick start를 `start`로 바꿀 때만 영향. 두 파일을 같이 고쳐야 한다 |
| FR-KT-003 / FR-KT-004 | AC-3(호출 매트릭스), AC-1·2(`docs-example` 마커) | 새 Kotlin 블록을 docs/api에 쓰면 마커+테스트가 필수. 새 콜백 타입은 없으므로 인벤토리는 불변 |

### 6.3 `docs/api/` 변경 목록

| 파일 | 변경 | 비고 |
|---|---|---|
| `app.md` | 수명주기 표(`listen`, `shutdown` 행 근처, `docs/api/app.md:176-185`)와 `## listen` 절 옆에 `start` 절 추가. `start`가 블로킹이 아니고 `listen`의 모든 규칙(`127.0.0.1`, 예외, keeper)을 그대로 따른다고 명시 | `DocsExamplesCompileTest`의 `app.md` skip 항목이 `#10`처럼 **서수 선택자**를 쓴다(`DocsExamplesCompileTest.java:77`). java 블록을 `app.md`의 앞쪽에 넣으면 서수가 밀린다 → 블록은 뒤쪽(`## 예제` 이후 등)에 두거나 선택자를 같이 고친다. 같은 이유로 Kotlin `docs-example: app.md#n` 마커(`KotlinDocsExamplesKotlinTest.kt:164,183,191`)도 서수에 의존한다 |
| `lifecycle.md` | 상태 다이어그램의 `NEW ──listen──>`를 `listen`/`start` 병기, "처음 실행하는 앱"과 Android 절에 "`start`도 메인 스레드에서 부르지 말 것" 추가 | `lifecycle.md`의 `ServerService` 예제는 v1 빌드가 컴파일하지 않는다(문서 내 명시) |
| `README.md`(docs/api) | 5분 시작 블록을 `start`로 바꿀지 결정(바꾸면 루트 `README.md`와 동일하게, OPS-REL-001 AC-2) | 현재 `RestApp app = RestApp.create(); ... app.listen(3000);` |
| `kotlin.md` | `RestApp.start(8080) { app -> ... }` 예제 추가 시 Kotlin 마커·테스트 의무(FR-KT-004) | 마지막 식의 Unit 코어션 한 줄 설명 권장 |
| `express-migration.md` | `app.listen(3000, () => ...)` 행(`:12`)에 `RestApp.start(3000, app -> ...)`를 병기. §4 "기본 바인딩은 localhost" 유지 | `start`가 Express 사용자에게 `0.0.0.0`을 기대하게 만들 위험이 커서 필수 |
| `defaults.md` | 변경 최소. 바인드 기본·경고 행(`:80`)에 `start`도 동일하다는 한 줄 | `WaveFiveDocsTest`가 이 파일의 표 값을 코드와 비교한다(OPS-ENGINE-002 AC-6) |
| `README.md`(루트) | 모듈표(`:35`)의 `NaruServe` 설명 문구는 (b) 채택 시만 변경. Quick start는 위와 연동 | |

**(미확정) 표시 대상**(사양서에 이름이 없는 것): 메서드 이름 `start`(사용자 요청이지만 사양서 미기재), 반환형(R1~R4), `ServerHandle` 종료 메서드 이름, 기본 포트 유무(S2), JVM 종료 훅의 기본값, 훅 대기 시간, (b) 채택 시 클래스 이름. CLAUDE.md 규칙에 따라 이 중 구현 중 정해지는 것은 사용자에게 묻지 않고 정한 뒤 표시를 지운다. `docs/api`에는 현재 `(미확정)` 표시가 하나도 남아 있지 않다(grep 결과 0건, wave 8에서 정리).

---

## 7. 영향받는 테스트·예제

### 7.1 깨지거나 수정이 필요한 테스트

| 테스트 | 위치 | 영향 | 조건 |
|---|---|---|---|
| `HttpApiSurfaceTest` (static 목록) | `NaruServe-api/src/test/java/com/snoworca/naruserve/HttpApiSurfaceTest.java:370-377` | `[builder, create]` 단언이 `[builder, create, start]`로 바뀌어야 함 | `start`를 `RestApp`에 static으로 추가(a, c) |
| `AppLifecycleApiTest` (ServerHandle 표면) | `NaruServe-api/src/test/java/com/snoworca/naruserve/AppLifecycleApiTest.java:197` | `port[]->int`, `address[]->String`, `state[]->AppState` 정확 일치 단언 변경 | R1 |
| `DefaultRestAppTest` / `HttpApiSurfaceTest`의 "RestApp 반환 public static" | `DefaultRestAppTest.java:271-284`, `HttpApiSurfaceTest.java:437-445`(`sdsAc5_FR_APP_002_AC5_...`) | `setOf("builder->Builder", "create->RestApp")` 일치 | R2에서만 깨짐 |
| `AppLifecycleGuardTest` (리플렉션으로 RestApp 설정 메서드 순회) | `.../internal/AppLifecycleGuardTest.java:214-222` | **영향 없음.** 이 테스트는 static 메서드를 건너뛰고(`Modifier.isStatic` → `continue`) `RestApp` 반환 메서드만 본다 | |
| FR-KT-001/002 자동 검사(nullability, 키워드) | `KotlinContractChecks.java:109-110` 등 | 영향 없음. `start`는 `@NullMarked` 패키지의 non-null 시그니처이고 키워드 이름이 아니다 | |
| `CallbackInventoryTest` / `CallbackMatrix.kt` | `CallbackMatrix.kt:15-31` | 영향 없음. `Configurer` 재사용이면 새 콜백 타입이 없다. 새 함수형 인터페이스를 만들면 매트릭스 갱신 필수 | |
| `BuildLayoutTest` (모듈별 패키지) | `src/test/java/.../build/BuildLayoutTest.java:111,123-130` | (b) 채택 시 새 클래스를 `com.snoworca.naruserve.bootstrap` 밖에 두면 실패 | (b) |
| `DocsExamplesCompileTest` | `src/test/java/.../docs/DocsExamplesCompileTest.java:76-91, 546` | 새 java 블록이 컴파일되어야 함(NaruServe-api classpath). (b)의 클래스를 쓰는 블록은 컴파일 불가(루트 `build.gradle:466-470`) | (b)이면 build.gradle 변경 필요 |
| `ReadmeDocsTest` (Quick start 동일성) | `src/test/java/.../docs/ReadmeDocsTest.java:104-112` | Quick start를 `start`로 바꿀 때: 두 README 블록 동일, `public static void main(String[] args)`와 `import com.snoworca.naruserve.RestApp;` 포함 유지 | Quick start 변경 시 |
| `JsonDocsKotlinTest` README 마커 | `NaruServe-api/src/test/kotlin/.../JsonDocsKotlinTest.kt:82-89` | README Kotlin 블록의 `fun main() {`, import 문자열을 단언한다. Quick start 변경 시 이 단언도 같이 점검 | Quick start 변경 시 |
| `WaveFiveDocsTest`, `AppDocsTest` | `src/test/java/.../docs/WaveFiveDocsTest.java`, `NaruServe-api/.../AppDocsTest.java` | `app.md`·`defaults.md` 문구 단언(`kept`, `lineWith`). 기존 문장을 지우지 않고 추가만 하면 영향 없음 [추정: 전 문장을 읽지는 않았다] | docs 편집 시 |
| FR-KT-004 마커 검사 | `NaruServe-api` docs 테스트 | docs/api에 새 ```kotlin 블록을 더하면 `// docs-example: <file>#<n>` 마커+테스트가 없을 때 빌드 실패 | 새 Kotlin 블록 |

### 7.2 새로 필요한 테스트(제안)

- `start`가 `create()`→람다→`listen` 순서로 호출하고, 반환 `ServerHandle.port()`가 실제 포트인지(부트스트랩 모듈 테스트, 실제 소켓).
- 설정 람다가 던질 때 앱이 TERMINATED이고 원래 예외가 전달되는지, 포트 범위 오류도 같은지.
- `NaruServe-api` 테스트(provider 없음)에서 `start`가 `create()`의 `IllegalStateException`(`com.snoworca.naruserve:NaruServe` 포함)을 그대로 던지는지(FR-APP-001 AC-3 선례, `AppDocsTest.java:153`).
- 자식 JVM에서 `main`이 `start`만 부르고 끝나도 프로세스가 사는지(FR-ENGINE-006 AC-5의 `JvmLifetimeTest.Child`를 `start` 경로로 복제). `JvmLifetimeTest.java:24-37`이 현재 `create()`+`listen(0)`을 쓴다.
- 기본 바인드가 `127.0.0.1`이고 `start`가 `bindAddress`를 바꾸지 않는지.
- Kotlin: `RestApp.start(0) { app -> app.get("/x") { _, res -> res.send("ok") } }`가 컴파일·실행되는지(T11).
- 종료 훅을 넣는다면: 훅 등록·제거, 이미 종료된 앱에서 훅이 아무것도 안 하는지, 여러 번 `start`해도 훅이 누적되지 않는지.

### 7.3 예제

- `examples/java/.../JavaExample.java:71-76`, `examples/kotlin/.../KotlinExample.kt:65-69`: `main`이 `create(...)`로 앱을 만든 뒤 훅을 직접 등록하고 `app.listen(port).port()`를 부른다. **`start`로 바꿀 필요는 없다.** 이 예제는 `create(File)`이 `RestApp`을 돌려주는 구조를 테스트가 재사용한다(`sourceSets.test.java.srcDir 'examples/java/src/main/java'`, 루트 `build.gradle`). 바꾸면 `RunnableJavaExampleTest` 구조를 같이 바꿔야 한다. 바꾸지 않아도 OPS-DX-001은 유지된다.
- `examples/README.md`는 "기본 포트 3000"을 설명한다. `start`가 기본 포트를 정한다면(S2) 이 숫자와 충돌하지 않게 맞춰야 한다.
- 테스트 픽스처 `TestApp`(`NaruServe-api` testFixtures)는 `RestApp.builder()`와 in-memory 어댑터를 쓴다(FR-APP-009 AC-7). `start`는 `create()`→TinyHttpd를 쓰므로 픽스처와 무관하다. 영향 없음.

---

## 8. 위험·열린 질문 (우선순위 순)

| # | 질문·위험 | 영향 | 권고(추정) |
|---|---|---|---|
| 1 | **`start` 반환값과 종료 수단.** `ServerHandle`이 `port/address/state`뿐이라 `start`가 앱을 숨기면 호출자는 `shutdown`을 못 부른다. R1(핸들에 종료 메서드)이면 IR-APP-005 AC-3과 `AppLifecycleApiTest.java:197` 수정, R2(`RestApp` 반환)이면 FR-APP-002 AC-5·FR-HTTP-008 AC-6 재지정과 "RUNNING 앱을 설정 호출하면 예외" 함정, R3/R4면 테스트·임베디드 종료 불가 | 가장 큼 | R1 권장. `ServerHandle.shutdown(long, TimeUnit)`이 `RestApp.shutdown`과 같은 모양이고 `RestApp` static 목록 규칙을 지킨다. 이름은 (미확정) |
| 2 | **`RestApp` static 목록 고정 해제.** `[builder, create]`가 IR-ENGINE-001 AC-7, IR-STATIC-002 AC-5 등 여러 wave의 AC에 반복해서 박혀 있다. 근원 AC들을 모두 찾아 같은 문구로 갱신해야 일관된다. 일부를 놓치면 문서·SRS가 서로 모순 | 큼 | 새 요구 하나가 "정적 진입점은 create, builder, start"로 이 문구들을 대체하도록 supersede 절차를 쓴다(MCP `supersede_requirement` 또는 AC 교체). SRS grep으로 "only static" 계열 전수 확인 필요(이 조사에서 확인한 것: IR-ENGINE-001 AC-7, IR-STATIC-002 AC-5, FR-APP-002 AC-5, IR-APP-002 AC-2; wave 7~8 요구의 같은 문구는 전수 확인 못 함) |
| 3 | **JVM 종료 훅을 `start`가 자동 등록하는가.** 장점: SIGTERM/Ctrl-C에 `onStop`과 리소스 정리가 돈다(Spring Boot와 유사). 위험: 훅이 `main`을 안 끝내는 keeper와는 무관하게 JVM 종료 때만 돈다 | 중 | 기본 on 권장하되 끌 방법 필요. 단 `start`가 숨은 부작용을 가지면 "`listen`은 순수, `start`는 편의"라는 구분이 생긴다. 훅 timeout은 10초가 object.md의 예시값(`object.md:850` "Stop timeout 10 s (예시 API)", 사양서 §11.1·docs 예제는 5초와 10초가 섞여 있음) |
| 4 | **훅의 누수와 JDK 제약.** 훅이 앱을 JVM 수명 동안 참조해 테스트에서 `start`를 여러 번 부르면 앱이 누적된다. `Runtime.removeShutdownHook`은 JVM 종료가 이미 진행 중이면 `IllegalStateException`을 던진다 [추정: JDK 문서 사실, 이 저장소에서 실행 확인 안 함]. `onStop`에서 훅을 제거하려면 그 예외를 삼켜야 하고, `onStop`은 시작 실패 앱에선 돌지 않으므로 `start`의 catch 경로에서도 제거해야 한다 | 중 | 훅을 넣는다면 위 세 경로(정상 종료, 시작 실패, 훅 자신이 호출한 종료)를 모두 테스트. Android에서 `onDestroy`가 항상 돌지 않는다는 object.md:764 취지와 같이 훅도 보장 수단이 아니라고 문서화 |
| 5 | **`start` 안의 설정 람다 예외 정리 누락.** 정리를 안 하면 `create()`가 만든 로거가 열린 채 남는다. 스레드·소켓은 없다(`DefaultRestApp` 생성자가 스레드를 만들지 않음) | 소 | §5의 shutdown 정리 + 원 예외 재던짐. 새 AC로 못박는다 |
| 6 | **기본 포트 유무.** S2를 두면 8080(object.md 예제)과 3000(README·docs·examples) 중 하나를 골라야 하고 포트 충돌이 숨은 동작이 된다. 환경변수(`PORT`) 읽기는 사양서에 없다 | 중 | 기본 포트를 두지 않는다(S1만). 이미 `listen(int)`가 포트를 명시하게 한 설계와 일치. 둔다면 (미확정) 표시 후 결정 |
| 7 | **Express 사용자의 `0.0.0.0` 기대.** `start(3000, ..)`는 Express `app.listen(3000)`과 가장 닮아 보이므로 "왜 외부에서 안 되지"가 늘 수 있다 | 중 | `start` Javadoc과 `express-migration.md` §4에 `127.0.0.1` 기본 명시. 기본값은 바꾸지 않는다(§5) |
| 8 | **Android 메인 스레드.** `start`는 `listen`을 포함하므로 소켓 바인드가 호출 스레드에서 일어난다. 문서가 이미 "`listen()`과 `shutdown()`은 메인 스레드에서 부르지 말 것"이라고 한다(`docs/api/lifecycle.md` Android 절) | 중 | `start`에도 같은 경고를 문서화. 훅은 API 21에서 쓸 수 있지만 animal-sniffer 결과로 확인 |
| 9 | **(a)/(b) 선택 시 "Core는 엔진을 모른다"의 오독.** `start`가 Core에 있어도 엔진 타입은 없지만, 클래스패스에 `NaruServe` 모듈이 없으면 런타임에 `IllegalStateException`이다. 컴파일 타임 의존이 아니라는 점이 FR-APP-001의 설계다 | 소 | 오류 메시지를 그대로 전달해 `com.snoworca.naruserve:NaruServe` 추가 안내가 나가게 한다(FR-APP-001 AC-3) |
| 10 | **docs 서수 선택자 취약성.** `app.md`의 java 블록 skip(`#10`), Kotlin `docs-example: app.md#n` 마커가 서수 기반이라 블록을 앞에 끼우면 어긋난다 | 소 | 블록을 파일 뒤쪽에 추가하거나 선택자를 함께 갱신 |
| 11 | **Quick start 교체 여부.** 바꾸면 OPS-REL-001 AC-2, `ReadmeDocsTest`, `JsonDocsKotlinTest` README 단언, README 두 곳을 모두 맞춰야 한다. 주니어 목표에는 가장 효과가 큰 변경이다 | 소~중 | `start` 구현·검증이 끝난 뒤 마지막 단계로 |
| 12 | **`ServerHandle`을 `AutoCloseable`로 할지.** try-with-resources/`use`가 자연스럽지만 `close()`가 checked `Exception`을 던지지 않게 `void close()`로 재선언해야 하고, `close`가 기다리는 timeout 기본값이 숨은 정책이 된다 | 소 | 이번 범위에선 보류 권장 |
| 13 | **조사하지 못한 것.** wave 7~8 SRS 전체의 "only static" 계열 문구 전수, `WaveFiveDocsTest`/`AppDocsTest`의 모든 단언 줄, TinyHttpd 쪽 코드(`/Users/beom/work/git/TinyHttpd`)의 daemon 기본값 재확인(FR-ENGINE-006 Rationale의 "Measured fact"에 의존했다) | — | 구현 단계의 TDD가 위 항목을 자연히 드러낸다. 특히 SRS grep 전수는 요구 작성 전에 수행 |

---

### 부록: 근거 파일 목록

- `/Users/beom/work/git/NaruServe/CLAUDE.md`, `/Users/beom/work/git/NaruServe/object.md`(§1.2, §3.4:197-220, §11.3-11.5:724-766, §12, §13, §14.2:833-855), `/Users/beom/work/git/NaruServe/docs/spec/00.index.md`
- 코드: `NaruServe-api/src/main/java/com/snoworca/naruserve/{RestApp,ServerHandle,AppState,AppStartException,Configurer,LifecycleCallback,ServerOptions}.java`, `.../internal/{DefaultRestApp,AppLifecycle,AppFactoryLoader}.java`, `.../spi/AppFactory.java`, `NaruServe/src/main/java/com/snoworca/naruserve/bootstrap/DefaultAppFactory.java`, `NaruServe-tinyhttpd/src/main/java/com/snoworca/naruserve/tinyhttpd/{KeeperThread,TinyHttpdAdapter}.java`
- 빌드: `/Users/beom/work/git/NaruServe/build.gradle`(19, 53-70, 137-153, 307-315, 323-384, 460-470), `/Users/beom/work/git/NaruServe/settings.gradle`
- SRS: `docs/spec/05.application.srs.md`(IR-APP-001/005, FR-APP-001/002/009/010/011/012), `06.engine-adapter.srs.md`(IR-ENGINE-001, FR-ENGINE-002/006/009, OPS-ENGINE-002), `13.kotlin-compatibility.srs.md`(FR-KT-001~004), `16.developer-experience.srs.md`, `10.static-files.srs.md`(IR-STATIC-002 AC-5)
- 테스트: `HttpApiSurfaceTest`, `AppLifecycleApiTest`, `AppLifecycleGuardTest`, `DefaultRestAppTest`, `BuildLayoutTest`, `DocsExamplesCompileTest`, `ReadmeDocsTest`, `JsonDocsKotlinTest`, `JvmLifetimeTest`, `KotlinContractChecks`, `CallbackMatrix.kt`
- docs: `docs/api/{app,lifecycle,README,express-migration,defaults,kotlin}.md`, `README.md`, `examples/{README.md,java,kotlin}`
