# Express에서 오신 분께

Express 코드의 구조(라우트·미들웨어·req/res)는 대부분 그대로 옮겨지지만, 아래 "꼭 알아야 할 차이"는 반드시 확인하세요. 이 문서는 대응표와 꼭 알아야 할 차이를 정리합니다.

## 대응표

### 앱

| Express | NaruServe (Java) | 비고 |
|---|---|---|
| `const app = express()` | `RestApp app = RestApp.create();` | |
| `app.listen(3000, () => ...)` | `ServerHandle h = app.listen(3000);` | 바인딩이 끝난 뒤 반환합니다. 콜백 없이 다음 줄이 "시작됨"입니다. 실패하면 예외를 던집니다. |
| `server.close()` | `app.shutdown(10, TimeUnit.SECONDS);` | 진행 중인 요청을 기다린 뒤 종료합니다. |
| `app.locals.x = v` | `ctx.attribute("x", v)` (`onStart` 안에서) | [수명주기](lifecycle.md) |
| `app.set('trust proxy', ...)` | **(미확정)** | 프록시 헤더는 명시 설정할 때만 신뢰합니다. |

### 라우팅

| Express | NaruServe (Java) | 비고 |
|---|---|---|
| `app.get('/u/:id', h)` | `app.get("/u/:id", h)` | `post`, `put`, `patch`, `delete`, `head`, `options`도 같습니다. |
| `app.all('/x', h)` | `app.route(Method.any(), "/x", h)` | |
| `app.route('/b').get(h1).post(h2)` | `app.route("/b").get(h1).post(h2)` | 같습니다. |
| (없음) | `app.route(Method.get().post(), "/x", h)` | 여러 메서드를 한 핸들러로 받습니다. |
| (없음) | `app.route(Method.any().except(Method.DELETE), "/x", h)` | 특정 메서드만 뺍니다. |
| `app.get('/x', auth, h)` | `app.get("/x", auth, h)` | 라우트 미들웨어. 여러 개(`auth, admin, h`)를 넘기는 오버로드는 **(미확정)** |
| `express.Router()` + `app.use('/api', router)` | **(미확정)** | 지금은 `app.use("/api", mw)`와 전체 경로 등록으로 대신합니다. |
| `'/ab?cd'`, `'/a*'`, 정규식 경로 (Express 4) / `{/:opt}`, `/*splat` (Express 5) | 지원하지 않음 | `:name` 변수와 고정 세그먼트만 씁니다. |

### 요청 `req`

| Express | NaruServe (Java) | 비고 |
|---|---|---|
| `req.params.id` | `req.param("id")` | |
| `req.query.page` | `req.query("page")` | 없으면 `null` |
| `req.query.tag` (배열) | `req.queries("tag")` | `List<String>`, 없으면 빈 리스트 |
| `req.body` | `req.body(User.class)` | 타입을 지정합니다. |
| `req.body` (배열) | `req.body(new TypeRef<List<User>>() {})` | 제네릭 타입 |
| `req.body.name` (form) | `req.form("name")` | urlencoded와 multipart 텍스트 필드 |
| `req.get('Authorization')` | `req.header("Authorization")` | 대소문자 무시 |
| `req.cookies.session` | `req.cookie("session")` | `Cookie` 객체를 돌려줍니다. 값은 `.getValue()`. |
| `req.file` (multer) | `req.file("avatar")` | `UploadFile` |
| `req.files` (multer) | `req.files("attachments")` | `List<UploadFile>` |
| `req.method` | `req.method()` | 문자열이 아니라 `Method` enum |
| `req.path` | `req.path()` | |
| `req.originalUrl` | `req.rawPath()` | 디코딩 전 경로. Express `originalUrl`은 쿼리를 포함합니다. `rawPath()`의 쿼리 포함 여부는 **(미확정)** |
| `req.ip` | `req.remoteAddress()` | |
| `req.secure` | `req.isSecure()` | |
| `req.user = u` / `res.locals.user = u` | `req.attribute("user", u)` | 요청 범위 저장소 |
| `req.app.locals.x` | `req.application().attribute("x", String.class)` | |

### 응답 `res`

| Express | NaruServe (Java) | 비고 |
|---|---|---|
| `res.status(201).json(o)` | `res.status(201).json(o)` | 같습니다. |
| `res.send('text')` | `res.send("text")` | **`text/plain`** 입니다. Express는 `text/html`. |
| `res.send('<p>hi</p>')` | `res.html("<p>hi</p>")` | HTML은 명시적으로 보냅니다. |
| `res.send({a: 1})` | `res.json(map)` | `send`는 객체를 JSON으로 바꾸지 않습니다. |
| `res.send(buffer)` | `res.send(bytes)` | `byte[]` |
| `res.sendStatus(204)` | `res.status(204).end()` | |
| `res.end()` | `res.end()` | |
| `res.set('X-A', 'b')` | `res.header("X-A", "b")` | |
| `res.cookie('s', 'v', {httpOnly: true})` | `res.addCookie(cookie)` | [Cookie](cookie.md) |
| `res.clearCookie('s')` | `maxAge 0` 쿠키를 `addCookie` | [Cookie](cookie.md#삭제) |
| `res.sendFile(path)` | `res.file(file)` | `java.io.File` |
| `res.download(path, name)` | `res.download(file, name)` | |
| `stream.pipe(res)` | `res.stream(in, "video/mp4")` | |
| `res.headersSent` | `res.isCommitted()` | |
| `res.redirect(url)` | **(미확정)** | `res.status(302).header("Location", url).end()` |
| `res.type('png')` | **(미확정)** | `res.header("Content-Type", "image/png")` |
| `res.render(view)` | **(미확정)** | 사양서에 없습니다. HTML은 `res.html(...)`로 직접 보냅니다. |

### 미들웨어와 오류

| Express | NaruServe (Java) | 비고 |
|---|---|---|
| `app.use((req, res, next) => { ...; next() })` | `app.use((req, res, next) -> { ...; next.run(); })` | |
| `app.use('/api', mw)` | `app.use("/api", mw)` | Express는 마운트된 미들웨어 안에서 `req.path`에서 prefix를 뺍니다(`req.baseUrl`). NaruServe의 `req.path()`가 prefix를 빼는지는 **(미확정)** 이므로 전체 경로로 비교하세요. |
| `next(err)` | `next.error(err)` 또는 `throw` | |
| `app.use((err, req, res, next) => ...)` | `app.onError((err, req, res) -> ...)` | 인자 3개(`next` 없음), 순서는 Express와 같음 |
| 마지막 404 미들웨어 | 필요 없음 | 기본 404/405를 보냅니다. 본문을 바꾸는 방법은 **(미확정)** |
| `express.json()`, `express.urlencoded()` | 필요 없음 | `req.body(...)`, `req.form(...)`이 읽습니다. |
| `express.raw()`, `express.text()` | **(미확정)** | JSON이 아닌 원본 본문을 읽는 API는 사양서에 이름이 없습니다. |
| `cors()` | **(미확정)** | 전역 `app.use`에서 `Access-Control-Allow-*` 헤더를 설정합니다. preflight(`OPTIONS` + `Access-Control-Request-Method`)는 `res.status(204).end()`로 바로 끝내고 `next.run()`을 부르지 마세요. 그러지 않으면 `/api` 인증 미들웨어가 preflight를 401로 거부합니다. |
| `cookie-parser` | 필요 없음 | 내장. 서명 쿠키(`secret`)·값 자동 인코딩은 **(미확정)** |
| `multer` | `app.multipart(config -> ...)` | 한도 설정만 합니다. |
| `express.static('public')` | `app.staticFiles("/", new File("public"))` | |
| `morgan('combined')` | `app.accessLog(o -> o.enabled(true))` | |

### WebSocket·SSE

| Express | NaruServe (Java) | 비고 |
|---|---|---|
| `app.ws('/ws', (ws, req) => ...)` (express-ws) | `app.ws("/ws", (req, ws) -> ...)` | **인자 순서가 반대**입니다. |
| `ws.on('message', m => ...)` | `ws.onText(m -> ...)` | 바이너리는 `onBinary`, JSON은 `onJson` |
| `ws.send(text)` | `ws.sendText(text)` | |
| `ws.on('close', ...)` | `ws.onClose((code, reason) -> ...)` | |
| `res.writeHead(200, {'Content-Type': 'text/event-stream'})` + `res.write(...)` | `SseClient sse = res.sse(); sse.send("event", "data");` | |

## 꼭 알아야 할 차이

### 1. 핸들러는 여러 스레드에서 동시에 실행됩니다

Node.js는 이벤트 루프 하나에서 콜백을 차례로 실행합니다. NaruServe는 요청을 **스레드 풀**에서 동시에 처리합니다.

- 여러 요청이 공유하는 객체(서비스, 캐시, 카운터)는 스레드 안전해야 합니다. `ConcurrentHashMap`, `AtomicLong`, `synchronized` 등을 씁니다.
- 핸들러 안에서 DB 조회 같은 **블로킹 호출을 해도 됩니다**. 콜백이나 `await`가 필요 없습니다. 다만 스레드 풀 크기는 정해져 있으므로 오래 걸리는 작업은 피합니다.

```java
// Express: let count = 0; 은 안전하지만 Java에서는 아닙니다.
AtomicLong count = new AtomicLong();
app.get("/count", (req, res) -> res.json(count.incrementAndGet()));
```

### 2. 응답은 핸들러가 반환하기 전에 끝냅니다

Express에서는 콜백이나 Promise로 나중에 응답할 수 있습니다. NaruServe v1의 핸들러와 미들웨어는 **동기**입니다. 핸들러 안에서 응답을 끝내고 반환하세요.
예외는 SSE(`res.sse()`), WebSocket, 파일 스트리밍처럼 반환 뒤에도 계속 보내는 응답뿐입니다.

### 3. 본문은 타입을 지정해서 한 번만 읽습니다

```java
User user = req.body(User.class);                         // 객체
List<User> users = req.body(new TypeRef<List<User>>() {}); // 제네릭
```

- `Content-Type`이 JSON일 때만 읽습니다. 다른 타입이면 `415`입니다.
- 본문은 한 번만 읽을 수 있습니다. `req.body(...)`를 두 번 부르면 `BodyAlreadyConsumedException`입니다. 읽은 값을 변수에 담아 쓰세요.
- 크기 한도가 기본으로 켜져 있습니다(JSON 1 MiB). Express `express.json()`의 기본은 100 KB입니다.

### 4. 기본 바인딩은 localhost입니다

Express의 `app.listen(3000)`은 모든 네트워크 인터페이스에서 받습니다. NaruServe의 `app.listen(3000)`은 **localhost에서만** 받습니다.
같은 LAN의 다른 기기에서 접속하려면 `0.0.0.0` 바인딩을 명시해야 합니다(설정 API 이름 **(미확정)**).
LAN에 열 때는 인증 미들웨어를 함께 거세요. 전역 인증 없이 LAN에 열면 보안 경고 로그를 남깁니다.

### 5. 경로 매칭이 엄격합니다

| 항목 | Express 기본 | NaruServe 기본 |
|---|---|---|
| 대소문자 | 구분 안 함 (`/Users` = `/users`) | **구분함** |
| 끝 슬래시 | 구분 안 함 (`/users/` = `/users`) | **구분함** |
| 패턴 | `?`, `*`, `+`, 정규식 (Express 4), `{}` 선택 구간·`*name` (Express 5) | `:name`과 고정 세그먼트만 |
| 우선순위 | 등록 순서 | 고정 세그먼트가 변수보다 먼저 (`/users/me` > `/users/:id`) |
| 같은 경로 중복 등록 | 먼저 등록한 것이 이김 | 시작할 때 **설정 오류** |

### 6. 404, 405, HEAD, OPTIONS를 자동으로 처리합니다

- 경로가 없으면 `404`, 경로는 있는데 메서드가 없으면 `405`와 `Allow` 헤더.
- `GET`을 등록하면 `HEAD`도 받습니다(본문 없이).
- `OPTIONS`는 `Allow` 헤더와 함께 `204`로 답합니다(Express는 `200`).
- 정적 파일 마운트가 있으면 명시 라우트 → 정적 파일 → 404 순서로 찾습니다.

### 7. 오류 응답은 정해진 JSON입니다

처리되지 않은 예외와 파싱 오류는 stack trace 없이 이 형식으로 나갑니다. 아래는 JSON 파싱 오류(`400`) 예시이며, `500`의 `code` 값은 **(미확정)** 입니다.

```json
{"error":{"code":"BAD_REQUEST","message":"Invalid JSON request","requestId":"..."}}
```

`app.onError`로 바꿀 수 있습니다. [미들웨어와 오류 처리](middleware.md#오류-처리)

### 8. 설정은 `listen` 전에 끝냅니다

라우트, 미들웨어, 정적 마운트는 `listen()` 전에만 등록합니다. 서비스는 `onStart` 콜백 안(`listen()` 실행 중)에서만 `ctx.register`할 수 있습니다. 실행 중에 라우트를 추가할 수 없습니다.
종료한 앱은 다시 시작할 수 없습니다. 새 `RestApp`을 만드세요.

### 9. 의존성 주입 대신 서비스 등록을 씁니다

Express에서 모듈 변수나 `app.locals`에 두던 공유 객체는 `onStart`에서 등록하고 `req.service(...)`로 꺼냅니다.

```java
app.onStart(ctx -> ctx.register(UserService.class, new UserService()));
app.get("/users", (req, res) -> res.json(req.service(UserService.class).findAll()));
```

[수명주기와 서비스](lifecycle.md)

### 10. 전역 미들웨어가 prefix 미들웨어보다 먼저 실행됩니다

Express는 `app.use`를 등록 순서 하나로 실행합니다. NaruServe는 **전역 → prefix → 라우트 미들웨어 → 핸들러** 순서이고, 같은 종류 안에서만 등록 순서를 따릅니다.

```java
app.use("/api", auth);   // Express: 1번째, NaruServe: 2번째
app.use(requestId);      // Express: 2번째, NaruServe: 1번째
```

[미들웨어 실행 순서](middleware.md#실행-순서)

## 전체 예제: Express 앱 옮기기

Express:

```javascript
const express = require('express');
const app = express();
app.use(express.json());
app.use(express.static('public'));

app.use((req, res, next) => { req.requestId = crypto.randomUUID(); next(); });
app.use('/api', (req, res, next) => {
  if (!req.get('Authorization')) return res.status(401).json('Unauthorized');
  next();
});

app.get('/api/todos', (req, res) => res.json(todos.findAll()));
app.post('/api/todos', (req, res) => res.status(201).json(todos.create(req.body)));
app.delete('/api/todos/:id', (req, res) => { todos.delete(req.params.id); res.sendStatus(204); });

app.use((err, req, res, next) => res.status(500).json('Internal Server Error'));
app.listen(3000);
```

NaruServe (Java):

```java
RestApp app = RestApp.create();

app.onStart(ctx -> ctx.register(TodoService.class, new TodoService()));

app.staticFiles("/", new File("public"));

app.use((req, res, next) -> {
    req.attribute("requestId", UUID.randomUUID().toString());
    next.run();
});
app.use("/api", (req, res, next) -> {
    if (req.header("Authorization") == null) {
        res.status(401).json("Unauthorized");
        return;
    }
    next.run();
});

app.get("/api/todos", (req, res) -> res.json(req.service(TodoService.class).findAll()));
app.post("/api/todos", (req, res) -> {
    Todo todo = req.body(Todo.class);
    res.status(201).json(req.service(TodoService.class).create(todo));
});
app.delete("/api/todos/:id", (req, res) -> {
    req.service(TodoService.class).delete(req.param("id"));
    res.status(204).end();
});

app.onError((err, req, res) -> {
    if (!res.isCommitted()) res.status(500).json("Internal Server Error");
    else res.abort(err);
});

app.listen(3000);
```

NaruServe (Kotlin):

```kotlin
val app = RestApp.create()

app.onStart { ctx -> ctx.register(TodoService::class.java, TodoService()) }

app.staticFiles("/", File("public"))

app.use { req, res, next ->
    req.attribute("requestId", UUID.randomUUID().toString())
    next.run()
}
app.use("/api") { req, res, next ->
    if (req.header("Authorization") == null) {
        res.status(401).json("Unauthorized")
        return@use
    }
    next.run()
}

app.get("/api/todos") { req, res -> res.json(req.service(TodoService::class.java).findAll()) }
app.post("/api/todos") { req, res ->
    val todo = req.body(Todo::class.java)
    res.status(201).json(req.service(TodoService::class.java).create(todo))
}
app.delete("/api/todos/:id") { req, res ->
    req.service(TodoService::class.java).delete(req.param("id"))
    res.status(204).end()
}

app.onError { err, req, res ->
    if (!res.isCommitted) res.status(500).json("Internal Server Error")
    else res.abort(err)
}

app.listen(3000)
```

## Express에 있지만 아직 정해지지 않은 기능

다음은 사양서에 없어서 **(미확정)** 입니다. 필요하면 위 표의 대체 코드를 쓰세요.

- `express.Router()`와 라우터 마운트
- CORS 미들웨어(`cors()`)
- 원본 본문 읽기(`express.raw()`, `express.text()`)
- `res.redirect`, `res.type`, `res.location`, `res.format`, `res.sendStatus`
- `req.hostname`, `req.is(type)`, `req.accepts(...)`
- `app.param(...)`
- 비동기 핸들러(Promise/`async`에 해당하는 것). Kotlin coroutine 지원은 별도 모듈로 검토 중입니다.
- 프록시 신뢰(`trust proxy`)와 LAN 바인딩 설정 API
