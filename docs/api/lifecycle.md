# 수명주기와 서비스

[English](en/lifecycle.md) | [문서 목록](README.md)

Express에서는 모듈 변수나 `app.locals`에 DB 연결 같은 공유 객체를 둡니다. NaruServe는 시작할 때 한 번 만들어 **등록**하고, 요청·WebSocket·SSE에서 **같은 객체를 꺼내 씁니다**. Servlet의 `ServletContext`(application scope)와 같은 역할입니다.

Spring 같은 의존성 주입이나 자동 스캔은 없습니다. 등록과 조회를 코드로 직접 씁니다.

## 예제

```java
RestApp app = RestApp.create();

app.onStart(ctx -> {
    Database db = new Database();
    ctx.own(db);                // Register before connect(), so failure still closes db
    db.connect();

    ctx.register(Database.class, db);
    ctx.register(UserService.class, new UserService(db));
    ctx.attribute("appVersion", "1.0.0");
    ctx.logger().info("Services initialized");
});

app.get("/api/users/:id", (req, res) -> {
    UserService users = req.service(UserService.class);
    res.json(users.findById(req.param("id")));
});

app.ws("/ws", (req, ws) -> {
    UserService users = req.service(UserService.class);   // The same instance
    ws.onText(id -> ws.sendJson(users.findById(id)));
});

app.onStop(ctx -> ctx.logger().info("Server stopping"));

app.listen(8080);
```

Kotlin:

```kotlin
app.onStart { ctx ->
    val db = Database()
    ctx.own(db)
    db.connect()
    ctx.register(Database::class.java, db)
    ctx.register(UserService::class.java, UserService(db))
}

app.get("/api/users/:id") { req, res ->
    res.json(req.service(UserService::class.java).findById(req.param("id")))
}
```

## onStart / onStop

| 콜백 | 실행 시점 | 할 일 |
|---|---|---|
| `onStart(ctx -> ...)` | `listen()` 안에서, 포트를 열기 **전**에 한 번 | 서비스 생성, `ctx.own`, `ctx.register` |
| `onStop(ctx -> ...)` | `shutdown()` 중, 서비스를 닫기 **전**에 한 번 | 사용자 정리 작업. 서비스를 아직 쓸 수 있습니다. |

- 여러 번 등록하면 등록 순서대로 실행합니다.
- `onStart`에서 예외가 나면 포트를 열지 않고, 이미 `own`/`register`한 리소스를 역순으로 닫은 뒤 `listen()`이 그 예외를 던집니다. 정리 중에 난 예외는 던지는 예외의 `getSuppressed()`에 붙습니다.
- unchecked 예외는 그대로, checked 예외는 `AppStartException`(unchecked)으로 감싸 던지고, 시작 도중 `shutdown()`으로 시작이 멈춘 경우도 `AppStartException`인데, 감싸는 이유는 `listen()`에 `throws Exception`을 두지 않아 Java·Kotlin 호출부를 단순하게 하려는 것입니다.
- `onStop`은 `RUNNING`까지 정상으로 올라간 앱에서만 실행됩니다. 시작에 실패한 앱에서는 실행되지 않습니다.
- `onStop`에서 예외가 나면 `ERROR`로 기록하고 남은 `onStop`과 정리 단계를 모두 실행해 `TERMINATED`까지 갑니다. `Error`(예: `AssertionError`, `NoClassDefFoundError`)도 똑같이 정리를 모두 마치지만, 시작 실패 때처럼 `TERMINATED`가 된 뒤 `shutdown()`이 처음 난 `Error`를 다시 던집니다(요청 안에서 불러 별도 종료 스레드에서 돌았다면 그 스레드의 uncaught 예외가 됩니다).

## ApplicationContext

`onStart`/`onStop`의 `ctx`, 그리고 `req.application()`이 돌려주는 객체입니다. 아래의 `@Nullable`은 `org.jspecify.annotations.Nullable`이며, Kotlin에서는 `T?`가 됩니다.

```java
public interface ServiceRegistry {
    <T> void register(Class<T> type, T instance);          // Closes AutoCloseable on shutdown
    <T> void registerExternal(Class<T> type, T instance);  // Does not close
    <T> T get(Class<T> type);           // ServiceNotFoundException if absent
    <T> @Nullable T getOrNull(Class<T> type);     // null if absent
    boolean contains(Class<?> type);
}

public interface ApplicationContext extends ServiceRegistry {
    void attribute(String key, @Nullable Object value);   // null removes the key
    <T> @Nullable T attributeAs(String key, Class<T> type);
    void own(AutoCloseable resource);
    Logger logger();
}
```

`req.service(UserService.class)`는 `req.application().get(UserService.class)`와 같습니다.

### register

- `onStart` 안에서만 부를 수 있습니다. `RUNNING`에서는 서비스 등록부가 고정되어 요청 스레드에서 동시에 읽을 수 있습니다. 이 제한은 일반 공통 HTTP 계층에는 적용되지 않습니다. listen 이후에도 라우트·미들웨어와 기존 RouteBuilder 콜백을 추가할 수 있습니다. 서버 옵션·수명주기 콜백·WebSocket 설정은 각각의 시작 시점 제한을 유지합니다.
- 같은 `Class`를 두 번 등록하면 **설정 오류**입니다. 덮어쓰지 않습니다.
- 인터페이스와 구현 클래스로 같은 객체를 두 번 등록해도 됩니다. 종료할 때 한 번만 닫습니다.

```java
JdbcUserService impl = new JdbcUserService(db);
ctx.register(UserService.class, impl);
ctx.register(JdbcUserService.class, impl);   // Same instance, closed once
```

같은 타입의 서비스가 둘 필요하면(예: DB 두 개) 감싸는 클래스를 만드세요. 이름으로 구분하는 `ServiceKey`는 v1 이후에 검토합니다.

### own

`AutoCloseable`을 프레임워크 소유로 등록합니다. 종료할 때, 또는 시작이 실패할 때 닫습니다.
**만든 직후, 초기화하기 전에** 부르세요. 그래야 초기화 중 실패해도 새지 않습니다.

### 닫히는 순서

`own`과 `register`한 `AutoCloseable`은 등록한 **역순**으로 닫힙니다. 위 예제에서는 `UserService`(`AutoCloseable`일 때) → `Database` 순서입니다. 닫다가 예외가 나도 나머지를 계속 닫습니다.

외부에서 만든 객체(다른 곳과 공유하는 커넥션 풀 등)는 `registerExternal`(`ctx.registerExternal(Type.class, instance)`)로 등록하면 조회는 `register`와 같고 프레임워크가 닫지 않으며, `Ownership.EXTERNAL` 같은 인자 대신 메서드를 나눈 이유는 호출부에서 소유권이 이름으로 바로 보이기 때문입니다.

```java
ctx.registerExternal(ConnectionPool.class, sharedPool);   // Not closed on shutdown
```

### attribute

앱 전역 문자열 키 저장소입니다. Express의 `app.locals`에 해당합니다. 실행 중에도 읽고 쓸 수 있고 스레드 안전합니다.

```java
ctx.attribute("appVersion", "1.0.0");
String v = req.application().attributeAs("appVersion", String.class);
ctx.attribute("appVersion", null);   // Remove
```

`attribute(key, null)`은 캐스팅 없이 값을 지웁니다. 명시적인 삭제는 `removeAttribute(key)`, 타입 지정 조회는 `attributeAs(key, type)`를 사용합니다.
저장된 값이 요청한 타입이 아니면 `ClassCastException`입니다.

## 저장소 범위

| 범위 | 쓰는 법 | 수명 | Express |
|---|---|---|---|
| 서비스 | `ctx.register` / `req.service` | 앱 시작 ~ 종료 | 모듈 변수 |
| 앱 속성 | `ctx.attribute` / `req.application().attribute` | 앱 시작 ~ 종료 | `app.locals` |
| 요청 속성 | `req.attribute` | 요청 하나 | `res.locals`, `req.xxx` |
| WebSocket 세션 속성 | `ws.attribute` | 연결 하나 | `ws.xxx` |

**서비스는 모든 요청이 동시에 씁니다.** 서비스 안의 변경 가능한 상태는 스레드 안전하게 만드세요. 프레임워크가 대신 막아 주지 않습니다.

## 상태

`app.state()`로 확인합니다.

```text
NEW ──listen/start──> STARTING ──성공──> RUNNING ──shutdown──> STOPPING ──> TERMINATED
 │                 │
 │                      └──실패──> FAILED ──정리──> TERMINATED
 └──shutdown──────────────────────────────────────────> TERMINATED
```

| 상태 | 의미 |
|---|---|
| `NEW` | 초기 설정 중. 서버 옵션·서비스·WebSocket 설정은 시작 시점의 제한을 따릅니다 |
| `STARTING` | `onStart` 실행·검사·바인딩 중. 바인딩 뒤 도착한 요청은 `RUNNING`이 될 때까지 기다렸다가 처리되고, 시작이 실패하면 기다리던 요청은 `503`과 `Connection: close`로 답을 받습니다(멈춤 요청이 이미 있으면 기다리지 않고 바로 `503`) |
| `RUNNING` | 요청 처리 중 |
| `STOPPING` | 종료 중. 새 요청·Upgrade를 `503` + `Connection: close`(표준 오류 본문)로 거부 |
| `FAILED` | 시작 실패. 정리 후 `TERMINATED` |
| `TERMINATED` | 끝. 다시 시작할 수 없음 |

`listen()`을 두 번 부르거나 동시에 부를 수 없습니다. 종료된 앱 대신 새 `RestApp`을 만드세요.

`STARTING`에서 기다리게 한 이유는 `listen()`이 반환하기 직전에 들어온 요청을 거절하지 않으려는 것입니다.

### 종료 순서

단계는 [shutdown](app.md#shutdown)에 있습니다. WebSocket·SSE 작별 인사는 연결을 아직 받는 동안 최대 1초 동안 진행됩니다. 이 순서는 object.md §11.4와 다릅니다. 사양서는 2단계에서 새 연결과 Upgrade 받기를 먼저 멈춘 뒤 3단계에서 작별 인사를 하지만, NaruServe는 작별 인사 동안에도 연결을 받고 그 연결의 요청과 Upgrade를 `STOPPING`의 `503`과 `Connection: close`로 답하며, 받기는 작별 인사가 끝난 뒤 멈춥니다.
`shutdown(timeout, unit)`은 timeout에 3초를 더한 시간 안에 반환합니다(`onStop` 콜백과 리소스가 바로 반환할 때). 3초는 TinyHttpd 중지가 timeout을 넘기는 최대 1.5초, HTTP 어댑터를 놓는 최대 1초, 여유 0.5초를 더한 값이며, `onStop`, 리소스 닫기, 로거 flush는 그대로 시간 제한 없이 실행되므로 이들이 오래 걸리면 그만큼 늦게 반환합니다.

### 시작 중 shutdown

`STARTING` 중에 `shutdown(timeout, unit)`을 부르면 멈춤 요청을 기록하고 `listen()`이 끝나기를 기다리며(`onStart` 콜백 안에서 부르면 기다리지 않고 바로 반환), timeout이 지나면 예외 없이 반환하지만 멈춤 요청은 그대로 남아 `listen()`은 다음 검사 지점(다음 `onStart` 콜백 전, 바인딩 전, `RUNNING`으로 바뀔 때)에서 시작을 멈추고 `AppStartException`을 던지는데, 멈춤 요청을 유지하는 이유는 timeout이 지났다고 멈춤을 취소하면 앱이 아무도 모르게 `RUNNING`으로 올라가기 때문입니다.

### 요청 핸들러 안에서 shutdown

라우트 핸들러나 미들웨어 안에서 `shutdown(timeout, unit)`을 부르면 종료 순서는 별도 스레드에서 돌고 호출은 기다리지 않고 바로 반환하며, 그 요청의 응답은 평소대로 끝까지 전송되는데, 종료 순서가 처리 중인 요청이 끝나기를 기다리므로 같은 스레드에서 기다리면 자기 자신을 timeout까지 기다린 뒤 응답을 강제로 끊게 되기 때문입니다(Express에서 `res.send()` 뒤 `server.close()`를 부르는 것과 같은 결과). `TERMINATED`가 되는 것을 확인하려면 다른 스레드에서 `app.state()`를 보세요. keep-alive 연결로 온 요청에서 부르면 TinyHttpd가 그 연결을 닫기까지 최대 약 2초 기다리므로 `TERMINATED`가 그만큼 늦어질 수 있습니다(엔진의 연결별 닫기 유예이며 `shutdown`의 timeout과 별개입니다).
다른 곳(신호 처리, 다른 요청, `Service.onDestroy`)에서 이미 시작한 종료가 진행 중(`STOPPING`)일 때 요청 핸들러에서 `shutdown(...)`을 불러도 기다리지 않고 바로 반환합니다. 종료 스레드를 만들 수 없으면(메모리 부족 등) 종료 순서는 호출한 요청 스레드에서 바로 돌며, 이때는 그 요청을 timeout까지 기다릴 수 있지만 앱은 반드시 `TERMINATED`가 됩니다.
SSE `onClose` 리스너 안에서 부른 `app.shutdown`도 요청 핸들러에서 부른 것처럼 바로 반환합니다. 종료 순서가 그 스트림이 끝나기를 기다리므로, 같은 이유로 별도 스레드에서 돕니다.

## 지연 콜백과 요청 자원

0.10.0 공통 HTTP 체크아웃은 콜백 반환·응답 완료·요청 정리를 구분합니다. 일반 콜백이 응답하거나 다음으로 진행하지 않고 반환하면 요청은 대기합니다. 나중의 콜백에서 `next.run()`을 부르거나 JSON을 보내거나 스트림/SSE를 시작할 수 있습니다. 작업 성공이 응답을 자동 종료하거나 다음 계층으로 진행시키지는 않습니다.

먼저 응답한 뒤에도 백그라운드 작업이 요청 자원을 사용해야 하면 `app.getAsync`나 `app.methodAsync`를 사용하세요. 그 작업의 미완료 `Deferred`를 반환해야 요청 보유권을 얻습니다. 객체만 만들고 반환하지 않으면 보유권이 생기지 않습니다. 작업은 앱이 소유한 executor에 예약합니다. 코어가 새 executor나 `CompletionStage` 의존성을 요구하지 않습니다.

1. `Deferred.pending()`을 만들고 생산자 작업을 준비한 뒤 async 콜백에서 그 결과를 반환합니다.
2. 보유권이 유효한 동안 생산자는 먼저 `res.send("ack")`한 뒤에도 같은 요청 본문이나 업로드를 읽을 수 있습니다. 기본 Tiny/Servlet 어댑터는 입력을 미리 읽지 않고 보존합니다.
3. 아직 응답하지 않았다면 명시적으로 보내거나 종료합니다. 먼저 응답을 완료했다면 두 번째 응답을 보내지 않습니다. 요청·업로드에 대한 마지막 접근을 마친 뒤 `work.resolve()`하고, 실패는 `work.reject(error)`로 이후 오류 경로에 전달합니다. 확정 이후의 reject는 두 번째 오류 응답을 만들지 않습니다.
4. 결과는 한 번만 확정됩니다. `onSettled(Runnable)`은 성공·실패와 늦은 등록을 포함해 한 번 관찰하며 응답을 종료하거나 다음으로 진행하지 않습니다. 정상 정리는 활성 콜백·대기 중인 continuation·모든 미완료 보유권을 기다립니다. 정리 이후에 쓸 데이터는 앱 소유 저장소에 저장하세요.

비정상 중단·async timeout·강제 shutdown은 미완료 보유권을 취소할 수 있습니다. 늦은 결과 확정이나 continuation으로 취소된 입력을 다시 열거나 완료된 exchange를 새 요청으로 되살릴 수 없습니다. 실제 실행 중인 콜백/drain은 종료할 때까지 활성 자원을 소유하므로 강제 종료가 임의의 사용자 코드를 멈춘다고 가정하지 마세요. 지연 콜백 재진입 시 요청 수명주기 문맥도 복원되어 그 콜백 안의 shutdown이 자기 자신을 기다리지 않습니다.

### 사용자 HTTP 어댑터

`HttpExchange.onAbort(Runnable)`과 `Runnable retainRequest()`는 기존 어댑터를 위한 기본 메서드입니다. 기본 abort 훅은 nonnull 리스너를 검증하고 알림은 하지 않으며, 기본 retention은 아무 작업도 하지 않는 해제 함수를 반환합니다. 이 호환성 기본값만으로 사용자 어댑터가 기본 어댑터의 지연 전송 보장을 얻지는 않습니다.

어댑터가 코어 밖에서 오류 응답을 직접 보냈다면 `responseStatus()`와 `responseBytesWritten()`으로 상태와 성공적으로 쓴 본문 바이트 수를 보고할 수 있습니다. 기본값 `0`·`-1`은 알 수 없다는 뜻이며 코어는 자체 로그 지표를 사용합니다. Servlet의 비동기 미지원 설정에서 생성한 500 응답도 이 방법으로 기록합니다. 종료 알림 전에 컨테이너 재조회 없이 읽을 수 있는 값을 게시하세요. 잘못된 값이나 관측 중 예외는 요청 정리를 막지 않으며 응답을 다시 쓰지도 않습니다.

retention을 구현한 어댑터는 `finish()`의 응답 전송과 별개로 정상 보유권이 모두 해제될 때까지 입력·컨테이너 상태를 유지해야 합니다. 각 해제 함수는 스레드 안전하고 여러 번 불러도 한 번만 적용되어야 합니다. 감지한 비정상 중단은 늦은 등록까지 포함해 각 리스너에 한 번, 어댑터 락 밖에서 알리며 리스너 실패가 정리를 막지 않아야 합니다. 정상 `finish()`는 abort를 알리지 않습니다. I/O 전의 유휴 연결 이탈을 즉시 감지할 필요는 없습니다. 종료 시 보유권을 취소할 수 있지만 늦은 해제로 자원을 되살릴 수 없으며, 제한된 stop 시간과 실제 I/O/drain 소유권을 유지해야 합니다. 구현할 때 `common.RequestRetentions`, `common.AbortListeners`, `common.SafeCallbacks`를 재사용하세요.

## Android

- `RestApp`은 Android `Service` 안에서 만들고 관리하세요.
- `RestApp.start(...)`도 `listen()`을 포함하므로 **메인 스레드에서 부르지 마세요.** Android에서는 `start`가 JVM 종료 훅을 등록하지 않으니 `Service.onDestroy`에서 `ServerHandle.shutdown(...)`이나 `app.shutdown(...)`을 부릅니다([start](app.md#start)).
- `listen()`과 `shutdown()`은 **메인 스레드에서 부르지 마세요.** 네트워크 작업이고, `shutdown`은 진행 중인 요청을 timeout까지 기다린 뒤 `onStop`, 리소스 닫기, 로거 flush를 시간 제한 없이 마저 실행하므로 timeout보다 오래 걸릴 수 있습니다.
- 프로세스가 강제 종료되면 `onDestroy`도 `onStop`도 실행되지 않을 수 있습니다. 잃으면 안 되는 데이터는 요청 처리 중에 저장하세요.
- 서비스를 다시 시작할 때는 새 `RestApp`을 만드세요.
- 백그라운드 실행, Foreground Service 정책은 앱이 책임집니다. 프레임워크가 서버의 상시 실행을 보장하지 않습니다.

아래 `ServerService` 예제는 문서로만 제공하는 Android 예제이며, Android 샘플 빌드가 v1 범위 밖이므로(2026-10-20 사용자 결정) v1 빌드는 이 예제를 컴파일하지 않습니다.

```java
public class ServerService extends Service {
    private RestApp app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onCreate() {
        app = RestApp.create();
        app.get("/ping", (req, res) -> res.send("pong"));
        worker.execute(() -> {
            try {
                app.listen(8080);
            } catch (RuntimeException e) {   // Port in use, etc.; uncaught failure can kill the process
                Log.e("Server", "start failed", e);
            }
        });
    }

    @Override public void onDestroy() {
        worker.execute(() -> app.shutdown(10, TimeUnit.SECONDS));
        worker.shutdown();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
```

## 처음 실행하는 앱

전체 import와 main, 종료 훅은 [Java](../../examples/java/src/main/java/example/JavaExample.java)·[Kotlin](../../examples/kotlin/src/main/kotlin/example/KotlinExample.kt) 예제에 있습니다. 사용자 서비스는 요청 스레드가 공유하므로 ConcurrentHashMap과 AtomicInteger로 상태를 관리합니다. 업로드 폴더는 listen 전에 준비하고 실패하면 시작을 중단합니다. 스트림 응답은 NaruServe가 전송을 마친 뒤 닫으므로 호출부에서 try-with-resources 또는 use로 먼저 닫지 않습니다.
