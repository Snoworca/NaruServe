# 미들웨어와 오류 처리

## 미들웨어

Express의 `(req, res, next) => {}`와 같습니다. 다음으로 넘길 때 `next()` 대신 `next.run()`을 부릅니다.

```java
@FunctionalInterface
public interface Middleware {
    void handle(Request req, Response res, Next next) throws Exception;
}

public interface Next {
    void run();                 // 다음 미들웨어 또는 핸들러 실행
    void error(Throwable error); // 오류 처리로 넘김 (Express: next(err))
}
```

### 등록

| 코드 | 적용 범위 |
|---|---|
| `app.use(mw)` | 모든 요청 |
| `app.use("/api", mw)` | `/api`와 그 아래 경로 (`/api`, `/api/users`). `/apix`에는 적용되지 않습니다. |
| `app.get("/admin", mw, handler)` | 이 라우트만. 미들웨어는 하나를 받습니다. |
| `app.route("/admin").use(mw1).use(mw2).get(handler)` | 이 경로에서 `use` 다음에 등록하는 메서드. `use` 순서대로 실행합니다. |

라우트 미들웨어를 여러 개 붙이는 방법은 `RouteBuilder.use(middleware)`입니다. `app.route(path).use(auth).use(adminOnly).get(handler)`는 `auth` → `adminOnly` → `handler` 순서로 실행하고, `use`는 그 뒤에 등록하는 메서드에만 적용됩니다(앞에 등록한 `.get(...)`에는 붙지 않습니다).
가변 인자 오버로드 대신 `RouteBuilder.use`를 고른 이유는 Java의 가변 인자는 맨 끝에만 올 수 있어 `get(path, Middleware..., RouteHandler)`를 만들 수 없고, Kotlin의 끝 람다 문법(`app.get("/x", auth) { req, res -> }`)과도 맞지 않기 때문입니다.

```java
Middleware adminOnly = (req, res, next) -> next.run();   // 위 예제의 adminOnly 자리

app.route("/api/admin")
   .use(auth)
   .use(adminOnly)
   .get((req, res) -> res.send("admin"));
```

Kotlin:

```kotlin
app.route("/api/admin")
    .use(auth)
    .use(adminOnly)
    .get { req, res -> res.send("admin") }
```

### 예: 요청 ID와 인증

```java
app.use((req, res, next) -> {
    req.attribute("requestId", UUID.randomUUID().toString());
    next.run();
});

app.use("/api", (req, res, next) -> {
    if (!auth.isAuthorized(req)) {
        res.status(401).json("Unauthorized");
        return;                  // next.run()을 부르지 않으면 여기서 끝
    }
    next.run();
});

Middleware adminOnly = (req, res, next) -> {
    User user = req.attribute("user", User.class);
    if (user == null || !user.isAdmin()) {
        res.status(403).json("Forbidden");
        return;
    }
    next.run();
};
app.get("/api/admin", adminOnly, (req, res) -> res.json(admin.status()));
```

Kotlin에서 람다 중간에 빠져나올 때는 `return@use`를 씁니다.

```kotlin
app.use("/api") { req, res, next ->
    if (!auth.isAuthorized(req)) {
        res.status(401).json("Unauthorized")
        return@use
    }
    next.run()
}
```

### 실행 순서

1. 전역 미들웨어 (`app.use(mw)`) — 등록 순서대로
2. prefix 미들웨어 (`app.use("/api", mw)`) — 등록 순서대로
3. 라우트 미들웨어 — 등록 순서대로
4. 라우트 핸들러

**Express와 다릅니다.** Express는 전역과 prefix를 구분하지 않고 등록 순서 하나로 실행합니다. NaruServe는 등록 위치와 상관없이 전역 미들웨어가 모두 prefix 미들웨어보다 먼저 실행됩니다.

```java
Middleware requestId = (req, res, next) -> next.run();   // 요청마다 ID를 붙이는 전역 미들웨어라고 합시다
app.use("/api", auth);       // 2번째로 실행
app.use(requestId);          // 1번째로 실행 (나중에 등록했지만 전역)
```

명시 라우트가 없으면 4단계에서 정적 파일, 그다음 404를 찾습니다. 404·405 응답 전에도 1·2단계 미들웨어는 실행됩니다.
`app.use`는 `listen()` 전에만 부를 수 있습니다.

### next.run() 규칙

- **한 번만** 부릅니다. 두 번 부르거나, 응답이 끝난 뒤나 요청이 끝난 뒤에 부르면 예외가 납니다.
- `next.run()`은 **동기**입니다. 반환되면 뒤쪽 미들웨어와 핸들러가 모두 실행된 뒤입니다. 그래서 앞뒤 처리를 한곳에 쓸 수 있습니다.

```java
app.use((req, res, next) -> {
    long start = System.nanoTime();
    next.run();
    long ms = (System.nanoTime() - start) / 1_000_000;
    app.logger().debug(req.path() + " " + ms + "ms");
});
```

단, SSE·WebSocket·파일 스트리밍은 `next.run()`이 반환된 뒤에도 전송이 이어집니다. 위 시간은 전송 완료 시간이 아닙니다.

- 응답도 `next.run()`도 없이 반환하면 프레임워크가 메서드와 경로를 담은 경고(`response not finished by the handler: GET /x`)를 앱 로거에 한 번 남기고, 그때까지 정한 상태 코드와 헤더로 `end()`해서 요청을 끝냅니다. 이렇게 정한 이유는 timeout까지 연결을 붙잡지 않으면서 실수를 로그로 알리려는 것입니다.
- 비동기 미들웨어(나중에 `next`를 부르는 방식)는 v1에서 지원하지 않습니다.

### WebSocket과 미들웨어

`app.use("/ws", auth)`는 WebSocket **Upgrade 요청**(HTTP)에 실행됩니다. 연결된 뒤 오가는 메시지마다 실행되지는 않습니다. 인증은 여기서 하세요. [WebSocket](websocket.md)

## 오류 처리

### 예외를 던지면

핸들러나 미들웨어에서 던진 예외, `next.error(e)`로 넘긴 예외는 모두 오류 처리로 갑니다.

```java
app.get("/users/:id", (req, res) -> {
    User user = users.findById(req.param("id"));
    if (user == null) throw new NotFoundException();   // 앱이 정의한 예외
    res.json(user);
});
```

### app.onError

Express의 오류 미들웨어 `(err, req, res, next)`에 해당합니다. 인자는 `(error, req, res)` 세 개입니다. 순서는 Express와 같고 `next`만 없습니다.
콜백은 `ErrorHandler`입니다. `throws Exception`을 허용하므로 핸들러 안에서 checked exception을 그대로 던질 수 있습니다.

```java
@FunctionalInterface
public interface ErrorHandler {
    void handle(Throwable error, Request req, Response res) throws Exception;
}
```

```java
app.onError((error, req, res) -> {
    if (res.isCommitted()) {   // 이미 보내기 시작했으면 덧쓰지 말고 끊습니다
        res.abort(error);
        return;
    }
    if (error instanceof HttpException) {   // 프레임워크가 던진 4xx와 앱이 던진 HttpException
        HttpException http = (HttpException) error;
        res.status(http.status()).send(http.code());
    } else {
        app.logger().error("Unhandled", error);
        res.status(500).send("Internal Server Error");
    }
});
```

- 이미 응답을 보내기 시작했다면(`isCommitted()`) 프레임워크는 500 JSON을 덧쓰지 않습니다. 직접 처리할 때도 위처럼 `abort`하세요.
- `onError`를 등록하지 않으면 아래 표준 오류 형식으로 답합니다.
- 프레임워크가 상태를 정해 던지는 오류(잘못된 쿼리 문자열의 `400` 등)는 unchecked 예외 `HttpException`으로 `onError`에 옵니다. `status()`가 상태 코드(`400`~`599`), `code()`가 아래 표의 표준 `code` 값입니다. 앱도 `throw new HttpException(404, "User not found")`처럼 던질 수 있고, `HttpException`으로 상태를 알리는 이유는 Express의 `err.status`처럼 예외 하나로 상태와 메시지를 함께 넘기기 위해서입니다.
- `415`는 그 하위 클래스 `UnsupportedMediaTypeException`입니다. 위 예제처럼 `HttpException`을 먼저 확인하지 않고 모든 예외를 `500`으로 보내면 이런 `4xx`도 `500`이 됩니다.
- 여러 번 등록하면 등록 순서대로 하나의 체인이 되며, 첫 핸들러는 항상 실행되고 다음 핸들러는 앞 핸들러가 응답을 시작하지 않았을 때만 실행되는데, 이 규칙을 고른 이유는 Express의 `next(err)` 없이도 "응답했으면 끝"이라는 규칙 하나로 체인을 읽게 하려는 것입니다.
- 체인이 끝나도 응답이 시작되지 않았으면 표준 오류 형식으로 답합니다. 핸들러가 예외를 던지면 체인을 멈추고 `500` 표준 오류로 답합니다. 응답이 처음 상태로 도착했고 핸들러가 `stream(...)`을 시작했다면 그 스트림이 답이며 끝까지 보냅니다(중단하지 않습니다). 이미 보내기 시작한 응답에 오류가 나면 연결을 끊습니다(`abort`).

### 표준 오류 형식

```json
{"error":{"code":"BAD_REQUEST","message":"Invalid JSON request","requestId":"..."}}
```

stack trace는 클라이언트에 보내지 않습니다. 위는 `400` 예시입니다. `message`는 `HttpException`의 메시지(없으면 상태 문구)이고, `500`은 항상 `Internal Server Error`입니다. 본문은 프레임워크가 앱의 JSON 엔진 없이 직접 만듭니다.
`404`/`405` 본문은 v1에서 바꿀 수 없으며, 그 이유는 프레임워크가 앱의 JSON 엔진 없이 직접 만드는 본문이라 어느 앱에서나 같은 모양을 보장하기 위해서입니다. 다만 전역·prefix 미들웨어는 404·405 전에도 실행되고, 거기서 정한 헤더는 남습니다.

| 상황 | 상태 | `code` |
|---|---|---|
| JSON·폼 파싱 실패, 잘못된 경로·쿼리 | `400` | `BAD_REQUEST` |
| 유효성 검증 실패 | `400` 또는 명시하면 `422` | `BAD_REQUEST` / `UNPROCESSABLE_ENTITY` |
| 경로 없음 | `404` | `NOT_FOUND` |
| 메서드 없음 | `405` | `METHOD_NOT_ALLOWED` |
| 본문·업로드 크기 초과 | `413` | `PAYLOAD_TOO_LARGE` |
| 지원하지 않는 Content-Type | `415` | `UNSUPPORTED_MEDIA_TYPE` |
| WebSocket 전용 경로에 일반 요청 | `426` | `UPGRADE_REQUIRED` |
| 그 밖의 예외 | `500` | `INTERNAL_ERROR` |
| 서버가 모르는 메서드 | `501` | `NOT_IMPLEMENTED` |
| 앱이 종료 중(`STOPPING`)이거나 시작에 실패함 | `503` + `Connection: close` | `SERVICE_UNAVAILABLE` |
| 표에 없는 상태(`HttpException(418)` 등) | 그 상태 | `HTTP_<status>` (예: `HTTP_418`) |

서버 과부하로 대기 큐가 가득 차면 `503`이지만, 이 응답은 TinyHttpd가 전송 계층에서 바로 보내는 평문(`text/plain`) 본문이라 위 JSON 형식도 `onError`도 거치지 않습니다. 큐가 가득 찬 순간에는 앱 코드를 돌릴 스레드가 없기 때문입니다.

`application/problem+json`(RFC 9457) 형식은 나중에 선택 기능으로 검토합니다.
