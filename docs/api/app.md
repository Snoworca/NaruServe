# RestApp

`RestApp`은 Express의 `app`에 해당합니다. 라우트·미들웨어·설정을 등록하고 서버를 시작하고 끕니다.

## 앱 만들기

```java
RestApp app = RestApp.create();
```

`create()`는 기본 HTTP 엔진(TinyHttpd)과 기본 JSON 엔진(Gson 2.14.0)을 조립합니다.
이 조립은 기본 bootstrap 모듈 `NaruServe`가 맡습니다. `create()`를 쓰려면 이 모듈을 의존성에 넣습니다.

```groovy
implementation 'com.snoworca.naruserve:NaruServe:0.1.0-SNAPSHOT'
```

엔진을 직접 고를 때는 builder를 씁니다. `NaruServe-api`만 의존하는 경우에도 이 방법을 씁니다.

```java
RestApp app = RestApp.builder()
    .httpAdapter(myAdapter)   // HttpAdapter 구현
    .jsonCodec(myCodec)       // JsonCodec 구현
    .build();
```

`RestApp.builder()`는 `httpAdapter(HttpAdapter)`, `jsonCodec(JsonCodec)`, `logger(Logger)`, `build()`를 가지며 각 설정 메서드는 builder를 돌려줍니다. `httpAdapter`와 `jsonCodec`은 꼭 불러야 하고, 빠지면 `build()`가 `IllegalStateException`을 던집니다. `logger`를 부르지 않으면 새 `ConsoleLogger`를 씁니다. `build()`는 부를 때마다 `NEW` 상태의 새 앱을 만듭니다.
`builder()`라는 이름을 고른 이유는 Java·Kotlin에서 가장 흔한 빌더 관례이고 `create()`와 나란히 두면 진입점 두 개가 한눈에 보이기 때문입니다.

`create()`는 클래스패스에서 `com.snoworca.naruserve.spi.AppFactory` 구현을 `ServiceLoader`로 하나 찾아 그 앱을 돌려줍니다. 구현은 `NaruServe` 모듈이 TinyHttpd 어댑터와 함께 제공하며(다음 단계에서 추가), 그 전에는 구현이 없어 아래 예외가 납니다. 구현이 없으면 `IllegalStateException`을, 둘 이상이면 구현 클래스 이름을 담은 `IllegalStateException`을 던집니다. 없을 때의 메시지는 다음과 같습니다.

```text
No com.snoworca.naruserve.spi.AppFactory provider found. Add the com.snoworca.naruserve:NaruServe module to use RestApp.create(), or assemble the app with RestApp.builder().httpAdapter(...).jsonCodec(...).build()
```

`create()`가 `AppFactory` SPI를 거치는 이유는 `NaruServe-api`가 TinyHttpd와 Gson에 컴파일 의존하지 않고도 `RestApp.create()`를 제공하기 위해서입니다.

```java
public interface AppFactory {   // com.snoworca.naruserve.spi
    RestApp create();
}
```

`RestApp` 하나가 서버 하나입니다. 한 프로세스에 여러 개를 만들 수 있고, 서로 상태를 공유하지 않습니다.

## 메서드 요약

### 라우트

| 메서드 | 설명 |
|---|---|
| `get(path, handler)` | GET 라우트. `HEAD`도 자동으로 받습니다. |
| `post(path, handler)` | POST 라우트 |
| `put(path, handler)` | PUT 라우트 |
| `patch(path, handler)` | PATCH 라우트 |
| `delete(path, handler)` | DELETE 라우트 |
| `head(path, handler)` | HEAD 라우트(자동 HEAD보다 우선) |
| `options(path, handler)` | OPTIONS 라우트(자동 OPTIONS보다 우선) |
| `get(path, middleware, handler)` 등 | 이 라우트에만 적용할 미들웨어를 붙입니다. |
| `route(Method, path, handler)` | 메서드 하나 |
| `route(MethodSet, path, handler)` | 여러 메서드를 한 핸들러로 |
| `route(path)` | `RouteBuilder`를 돌려줍니다. `.get(h).delete(h2)`로 메서드별 핸들러를 붙입니다. |
| `ws(path, handler)` | WebSocket 엔드포인트. [WebSocket](websocket.md) |

모두 `RestApp`을 돌려주므로 이어서 호출할 수 있습니다(`route(path)`는 `RouteBuilder`를 돌려줍니다). 자세한 규칙은 [라우팅](routing.md).
라우트 미들웨어 오버로드(`get(path, middleware, handler)` 등)도 `RestApp`을 돌려줍니다. 미들웨어를 여러 개 붙일 때는 `route(path).use(m1).use(m2).get(handler)`를 씁니다([미들웨어](middleware.md#등록)).
`ws`의 반환 타입은 **(미확정)** 입니다.

`RouteHandler`는 `(req, res) -> { ... }` 람다입니다.

```java
@FunctionalInterface
public interface RouteHandler {
    void handle(Request req, Response res) throws Exception;
}
```

핸들러는 checked exception을 그대로 던질 수 있습니다. 던진 예외는 [오류 처리](middleware.md#오류-처리)로 갑니다.

### 미들웨어·오류

| 메서드 | 설명 |
|---|---|
| `use(middleware)` | 모든 요청에 실행 |
| `use(prefix, middleware)` | `prefix` 경로와 그 하위 경로에만 실행 (`/api`, `/api/users`; `/apix`는 제외) |
| `onError(handler)` | 처리되지 않은 예외를 받습니다. `(error, req, res) -> ...` |

[미들웨어와 오류 처리](middleware.md)

### 설정

| 메서드 | 설명 | 문서 |
|---|---|---|
| `jsonCodec(codec)` | JSON 엔진 교체 | [JSON](json.md) |
| `multipart(config -> ...)` | 업로드 한도·임시 폴더 | [파일 업로드](multipart.md) |
| `staticFiles(prefix, dir)` | 정적 파일 폴더 마운트 | [정적 파일](static.md) |
| `staticFiles(prefix, dir, options -> ...)` | 옵션과 함께 마운트 | [정적 파일](static.md) |
| `mimeTypes()` | 전역 MIME 등록부 | [정적 파일](static.md#mime) |
| `accessLog(options -> ...)` | 접근 로그 설정 | [로깅](logging.md) |
| `logger(logger)` | 앱 로거 교체 | [로깅](logging.md) |
| `logger()` | 현재 앱 로거 | [로깅](logging.md) |
| `routerPolicy(RouterPolicy)` | `//`·끝 슬래시·대소문자 정책과 `TRACE`/`CONNECT` 켜기 | [라우팅](routing.md#라우터-정책) |

### 라우터 정책

`routerPolicy(RouterPolicy)`는 `RestApp`을 돌려줍니다. 기본은 모든 스위치가 꺼진 엄격 정책입니다. Express의 `app.set('strict routing', ...)`, `app.set('case sensitive routing', ...)`에 해당합니다.

```java
app.routerPolicy(RouterPolicy.builder().ignoreTrailingSlash(true).build())
   .get("/users", (req, res) -> res.send("users"));   // /users/ 도 맞습니다
```

Kotlin:

```kotlin
app.routerPolicy(RouterPolicy.builder().ignoreTrailingSlash(true).build())
    .get("/users") { req, res -> res.send("users") }
```

### 수명주기

| 메서드 | 설명 |
|---|---|
| `onStart(ctx -> ...)` | 서버가 요청을 받기 전에 한 번 실행. 서비스 생성·등록 |
| `onStop(ctx -> ...)` | 정상 종료 때 한 번 실행. 서비스가 아직 살아 있습니다. |
| `listen(port)` | 서버 시작. `ServerHandle` 반환 |
| `shutdown(timeout, unit)` | 정상 종료 |
| `state()` | 현재 상태: `NEW`, `STARTING`, `RUNNING`, `STOPPING`, `FAILED`, `TERMINATED` |

`onStart`/`onStop`의 콜백은 `LifecycleCallback`(`void handle(ApplicationContext ctx) throws Exception`), `onError`의 콜백은 `ErrorHandler`(`void handle(Throwable error, Request req, Response res) throws Exception`)이며, 둘 다 `RouteHandler`·`Middleware`처럼 `@FunctionalInterface`이고 `throws Exception`을 허용하는 이유는 콜백 안의 I/O 예외를 감싸지 않고 그대로 던지게 하려는 것입니다.
`ws`에 넘기는 콜백 인터페이스의 이름은 **(미확정)** 입니다.

[수명주기와 서비스](lifecycle.md)

## listen

```java
ServerHandle server = app.listen(8080);
```

- **동기**로 동작합니다. `onStart` 실행 → 라우트 검사 → 포트 바인딩이 모두 끝난 뒤 반환합니다. Express와 달리 콜백을 받지 않습니다.
- 반환 전에는 요청을 받지 않습니다.
- `listen()`이 반환한 뒤 `main`이 끝나도 `shutdown(...)`을 부를 때까지 프로세스가 계속 돕니다. 서버 스레드는 daemon이 아니며, 이는 기본 TinyHttpd 어댑터(`NaruServe-tinyhttpd`, 다음 단계에서 추가)가 보장합니다. 이렇게 정한 이유는 Express의 `app.listen()`처럼 서버만 띄우는 `main`이 바로 끝나지 않게 하려는 것입니다.
- 바인딩 실패, `onStart` 예외, 라우트 충돌 같은 설정 오류는 **예외로 던집니다**. 이때 포트는 열려 있지 않고, 이미 만든 리소스는 정리됩니다.
- 기본으로 **localhost에만** 바인딩합니다. LAN 바인딩 설정 API는 **(미확정)** 입니다.
- `ServerHandle`은 `port()`(실제 포트. `listen(0)`이면 고른 빈 포트), `address()`(`127.0.0.1`), `state()`(부를 때마다 읽는 앱 상태)를 가집니다. Express의 `server.address().port` 대신 짧은 접근자를 둔 이유는 `req.path()`처럼 다른 API와 같은 이름 규칙을 쓰기 위해서입니다.
- Android에서는 메인 스레드에서 호출하지 마세요. 작업 스레드에서 호출합니다.

## shutdown

```java
app.shutdown(10, TimeUnit.SECONDS);
```

1. 새 연결·요청·WebSocket Upgrade를 받지 않습니다.
2. 진행 중인 요청이 끝나기를 기다립니다. SSE·WebSocket에는 종료를 알립니다(WebSocket close 코드 `1001`).
3. 시간 안에 안 끝나면 연결을 강제로 끊습니다.
4. `onStop` 콜백을 실행합니다.
5. `own`/`register`한 `AutoCloseable`을 생성·등록 역순으로 닫습니다.
6. 앱 로거를 flush하고 닫은 뒤 HTTP 어댑터를 놓습니다([로깅](logging.md#종료와-로거)).

여러 번 불러도 안전합니다. 종료된 앱은 다시 `listen`할 수 없습니다.
Android에서는 메인 스레드에서 호출하지 마세요. timeout은 2단계(진행 중인 요청을 기다리는 시간)에만 걸리고, 그 뒤의 `onStop`, 리소스 닫기, 로거 flush에는 시간 제한이 없어 timeout보다 오래 막힐 수 있습니다.

## 설정은 listen 전에

라우트, 미들웨어, 정적 마운트, `multipart`, `jsonCodec` 같은 설정은 상태가 `NEW`일 때만 바꿀 수 있습니다. `listen()` 뒤에 호출하면 예외가 납니다.
`listen()`할 때 같은 메서드+경로 중복, 우선순위가 같은 충돌 패턴 같은 문제를 검사해 설정 오류로 알려 줍니다.

## 예제

```java
RestApp app = RestApp.create();

app.onStart(ctx -> {
    Database db = new Database();
    ctx.own(db);            // 초기화가 실패해도 닫히도록 먼저 소유 등록
    db.connect();
    ctx.register(UserService.class, new UserService(db));
});

app.use((req, res, next) -> {
    req.attribute("requestId", UUID.randomUUID().toString());
    next.run();
});

app.get("/api/users/:id", (req, res) -> {
    res.json(req.service(UserService.class).findById(req.param("id")));
});

app.staticFiles("/", new File("./www"));

ServerHandle server = app.listen(8080);
Runtime.getRuntime().addShutdownHook(new Thread(() -> app.shutdown(10, TimeUnit.SECONDS)));
```
