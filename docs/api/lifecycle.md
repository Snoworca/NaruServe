# 수명주기와 서비스

Express에서는 모듈 변수나 `app.locals`에 DB 연결 같은 공유 객체를 둡니다. NaruServe는 시작할 때 한 번 만들어 **등록**하고, 요청·WebSocket·SSE에서 **같은 객체를 꺼내 씁니다**. Servlet의 `ServletContext`(application scope)와 같은 역할입니다.

Spring 같은 의존성 주입이나 자동 스캔은 없습니다. 등록과 조회를 코드로 직접 씁니다.

## 예제

```java
RestApp app = RestApp.create();

app.onStart(ctx -> {
    Database db = new Database();
    ctx.own(db);                // 아래 connect()가 실패해도 db가 닫히도록 먼저 등록
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
    UserService users = req.service(UserService.class);   // 같은 객체
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
- `onStart`에서 예외가 나면 포트를 열지 않고, 이미 `own`/`register`한 리소스를 역순으로 닫은 뒤 `listen()`이 그 예외(checked 예외라면 이를 감싼 unchecked 예외, 정확한 타입은 **(미확정)**)를 던집니다.
- `onStop`은 `RUNNING`까지 정상으로 올라간 앱에서만 실행됩니다. 시작에 실패한 앱에서는 실행되지 않습니다.

## ApplicationContext

`onStart`/`onStop`의 `ctx`, 그리고 `req.application()`이 돌려주는 객체입니다.

```java
public interface ServiceRegistry {
    <T> void register(Class<T> type, T instance);
    <T> T get(Class<T> type);           // 없으면 ServiceNotFoundException
    <T> T getOrNull(Class<T> type);     // 없으면 null
    boolean contains(Class<?> type);
}

public interface ApplicationContext extends ServiceRegistry {
    void attribute(String key, Object value);
    <T> T attribute(String key, Class<T> type);
    void own(AutoCloseable resource);
    Logger logger();
}
```

`req.service(UserService.class)`는 `req.application().get(UserService.class)`와 같습니다.

### register

- `onStart` 안에서만 부를 수 있습니다. 서버가 `RUNNING`이 되면 등록부는 바뀌지 않습니다. 그래서 요청 스레드에서 동시에 읽어도 안전합니다.
- 같은 `Class`를 두 번 등록하면 **설정 오류**입니다. 덮어쓰지 않습니다.
- 인터페이스와 구현 클래스로 같은 객체를 두 번 등록해도 됩니다. 종료할 때 한 번만 닫습니다.

```java
JdbcUserService impl = new JdbcUserService(db);
ctx.register(UserService.class, impl);
ctx.register(JdbcUserService.class, impl);   // 같은 객체, 닫기는 한 번
```

같은 타입의 서비스가 둘 필요하면(예: DB 두 개) 감싸는 클래스를 만드세요. 이름으로 구분하는 `ServiceKey`는 v1 이후에 검토합니다.

### own

`AutoCloseable`을 프레임워크 소유로 등록합니다. 종료할 때, 또는 시작이 실패할 때 닫습니다.
**만든 직후, 초기화하기 전에** 부르세요. 그래야 초기화 중 실패해도 새지 않습니다.

### 닫히는 순서

`own`과 `register`한 `AutoCloseable`은 등록한 **역순**으로 닫힙니다. 위 예제에서는 `UserService`(`AutoCloseable`일 때) → `Database` 순서입니다. 닫다가 예외가 나도 나머지를 계속 닫습니다.

외부에서 만든 객체(다른 곳과 공유하는 커넥션 풀 등)를 자동으로 닫지 않게 등록하는 방법은 **(미확정)** 입니다(`registerExternal` 또는 `Ownership.EXTERNAL` 검토 중).

### attribute

앱 전역 문자열 키 저장소입니다. Express의 `app.locals`에 해당합니다. 실행 중에도 읽고 쓸 수 있고 스레드 안전합니다.

```java
ctx.attribute("appVersion", "1.0.0");
String v = req.application().attribute("appVersion", String.class);
```

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
NEW ──listen──> STARTING ──성공──> RUNNING ──shutdown──> STOPPING ──> TERMINATED
 │                 │
 │                 └──실패──> FAILED ──정리──> TERMINATED
 └──shutdown──────────────────────────────────────────> TERMINATED
```

| 상태 | 의미 |
|---|---|
| `NEW` | 설정 중. 라우트·미들웨어·설정을 바꿀 수 있는 유일한 상태 |
| `STARTING` | `onStart` 실행·검사·바인딩 중. 요청을 받지 않음 |
| `RUNNING` | 요청 처리 중 |
| `STOPPING` | 종료 중. 새 요청·Upgrade를 거부 |
| `FAILED` | 시작 실패. 정리 후 `TERMINATED` |
| `TERMINATED` | 끝. 다시 시작할 수 없음 |

`listen()`을 두 번 부르거나 동시에 부를 수 없습니다. 종료된 앱 대신 새 `RestApp`을 만드세요.

## Android

- `RestApp`은 Android `Service` 안에서 만들고 관리하세요.
- `listen()`과 `shutdown()`은 **메인 스레드에서 부르지 마세요.** 네트워크 작업이고, `shutdown`은 timeout만큼 기다릴 수 있습니다.
- 프로세스가 강제 종료되면 `onDestroy`도 `onStop`도 실행되지 않을 수 있습니다. 잃으면 안 되는 데이터는 요청 처리 중에 저장하세요.
- 서비스를 다시 시작할 때는 새 `RestApp`을 만드세요.
- 백그라운드 실행, Foreground Service 정책은 앱이 책임집니다. 프레임워크가 서버의 상시 실행을 보장하지 않습니다.

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
            } catch (RuntimeException e) {   // 포트 사용 중 등. 잡지 않으면 앱 프로세스가 죽습니다.
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
