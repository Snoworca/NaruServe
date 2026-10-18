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
| `app.get("/admin", mw, handler)` | 이 라우트만. 여러 개를 넘기는 오버로드(가변 인자)는 **(미확정)** 이므로 지금은 하나로 합쳐 넘기세요. |
| `app.route("/admin").use(mw).get(handler)` | 이 경로의 모든 메서드 (**(미확정)**: 사양서는 대안으로만 언급) |

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

- 응답도 `next.run()`도 없이 반환하면 응답이 나가지 않습니다. 프레임워크는 timeout까지 방치하지 않도록 개발자 경고를 남기고 요청을 정리합니다(정리 방식 **(미확정)**).
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
콜백 인터페이스 이름과 `throws Exception` 허용 여부는 **(미확정)** 입니다.

```java
app.onError((error, req, res) -> {
    if (res.isCommitted()) {   // 이미 보내기 시작했으면 덧쓰지 말고 끊습니다
        res.abort(error);
        return;
    }
    if (error instanceof NotFoundException) {
        res.status(404).json("Not Found");
    } else {
        app.logger().error("Unhandled", error);
        res.status(500).json("Internal Server Error");
    }
});
```

- 이미 응답을 보내기 시작했다면(`isCommitted()`) 프레임워크는 500 JSON을 덧쓰지 않습니다. 직접 처리할 때도 위처럼 `abort`하세요.
- `onError`를 등록하지 않으면 아래 표준 오류 형식으로 답합니다.
- 프레임워크가 만든 오류(JSON 파싱 `400`, 크기 초과 `413`, Content-Type `415` 등)도 `onError`로 옵니다. 그 예외 타입과 상태 코드를 읽는 방법은 **(미확정)** 입니다. 위 예제처럼 모르는 예외를 모두 `500`으로 보내면 이런 `4xx`도 `500`이 됩니다.
- 여러 번 등록하면 등록 순서대로 하나의 체인이 됩니다. 앞 핸들러가 응답했으면 뒤 핸들러는 실행되지 않습니다(**(미확정)**).

### 표준 오류 형식

```json
{"error":{"code":"BAD_REQUEST","message":"Invalid JSON request","requestId":"..."}}
```

stack trace는 클라이언트에 보내지 않습니다. 위는 `400` 예시입니다. 상태별 `code` 값(`500`의 `code` 등)과 404·405 본문을 바꾸는 방법은 **(미확정)** 입니다.

| 상황 | 상태 |
|---|---|
| JSON·폼 파싱 실패 | `400` |
| 유효성 검증 실패 | `400` 또는 명시하면 `422` |
| 경로 없음 | `404` |
| 메서드 없음 | `405` |
| 본문·업로드 크기 초과 | `413` |
| 지원하지 않는 Content-Type | `415` |
| 그 밖의 예외 | `500` |
| 서버 과부하(큐가 가득 참) | `503` |

`application/problem+json`(RFC 9457) 형식은 나중에 선택 기능으로 검토합니다.
