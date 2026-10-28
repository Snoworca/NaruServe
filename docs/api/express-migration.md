# Express에서 옮기기

[English](en/express-migration.md) | [문서 목록](README.md)

**0.10.0 체크아웃의 일반 HTTP 라우팅 프로필**은 Express5의 흔한 문자열 경로·등록 순서·Router·param·next·오류 흐름을 Java API로 제공합니다. Express 전체, Node 런타임, 모든 미들웨어, JavaScript 정규식 호환을 주장하지 않습니다. NaruServe의 타입 지정 본문·text/plain 문자열 응답·기존 WS/SSE/파일·보안 확장은 유지합니다. 현재 아티팩트 사용법은 [설치 안내](README.md)를 보세요.

## API 대응

### 앱과 라우팅

| Express | NaruServe | 경계 |
|---|---|---|
| `express()` | `RestApp.create()` | 기본 TinyHttpd 3.1.1 RAW + Gson 2.14.0 + 콘솔 로거 |
| `app.listen(port, callback)` | `app.listen(port)` / `RestApp.start(port, routes)` | 바인딩 완료 후 반환, 실패는 예외. 기본 127.0.0.1 |
| `server.close()` | `app.shutdown(timeout, unit)` | 활성 작업·리소스 정리와 deadline |
| `app.get/post/...` | 같은 이름의 두 인자 handler 또는 세 인자 Middleware 콜백 | 가변 콜백·Kotlin 고정 인자 오버로드 |
| `app.route(path)` | `app.route(path)` | 호출 시점에 하나의 Route 식별자·위치 생성 |
| `express.Router(options)` | `com.snoworca.naruserve.Router.create(mergeParams, caseSensitive, strict)` | 기본 false,false,false |
| `app.use('/api', router)` | `app.use("/api", router)` | 접두사 제거·baseUrl 누적·복원, 같은 Router 재마운트 |
| `app.param(name, callback)` | `app.param(name, ParamHandler)` / `router.param` | 원래 디코딩 캡처를 List<String>으로 받음 |
| `app.all`/확장 메서드 등록 | `app.method("COPY", path, callbacks...)` 등 토큰별 등록 | `Method.any()`는 기존7개 집합, 모든 메서드가 아님 |
| 경로 배열 | 문자열·Pattern·RegexPathPattern의 중첩 Object[]/List | 원소 전체 검증 후 첫 매칭 선택 |
| Express5 `/:id`, `/*parts`, `{/:id}` | 같은 문자열 형태 | raw 매칭 후 참여 캡처만 한 번 디코딩 |
| JavaScript RegExp | Java Pattern / RegexPathPattern | 검색 매칭, Java 플래그. ECMAScript/lastIndex 에뮬레이션 없음 |
| `app.use('/api', mw)` | `app.use("/api", mw)` | 콜백 안의 path/url은 제거된 뷰 |
| `next()`, `next(err)` | `next.run()`, `next.error(err)` | Java에서는 null 오류 거부 |
| `next('route')`, `next('router')` | `next.route()`, `next.router()` | Route 건너뛰기 / 가까운 Router 이탈 |
| 오류 미들웨어 | 네 인자 ErrorMiddleware를 `use` | 등록 위치, next로 전파·교체·정상 재개 |
| 기존 편의 오류 핸들러 | `onError(error, req, res)` | 세 인자, listen 전 구성 |

### 요청과 응답

| Express | NaruServe | 차이 |
|---|---|---|
| `req.params.id` | `param`, `paramOrNull`, `paramValues`, `pathParams` | Map의 scalar는 String, wildcard는 불변 List |
| `req.method` | `methodToken()` | 기존 enum `method()`는 확장 메서드에서 예외 |
| `req.path`, `req.url`, `req.originalUrl`, `req.baseUrl` | `path()`, `url()`, `originalUrl()`, `baseUrl()` | raw 뷰. 원래 rawPath는 별도 유지 |
| `req.query.page/tag` | `query`, `queries`, `queryInt`, `requireQuery` | 평평한 문자열/반복 값, 명시 입력 검증 |
| `req.get` | `header`, `headers`, `requireHeader` | 대소문자 무시, 중복 보존 |
| `req.body` | `body(User.class)` / `body(TypeRef)` | 타입 지정·on-demand 파싱. null 반환 가능 |
| 폼·multer 파일 | `form`, `file`, `files`, `parts`, `requireFile` | multipart·임시 파일·크기 제한 내장 |
| `req.cookies` | `cookie`, `getCookies` | Cookie 방어적 복사, signed cookie 없음 |
| `req.user` / `res.locals` | `attribute`, `attributeAs`, `removeAttribute` | 요청 범위 |
| `res.send('text')` | `res.send("text")` | **text/plain**, Express의 기본 HTML과 다름 |
| `res.send('<p>hi</p>')` | `res.html("<p>hi</p>")` | HTML은 명시 |
| 객체·리스트·숫자·boolean 전송 | `send(Object)` / `json(Object)` | null send는 빈 본문, json(null)은 JSON null |
| `status`, `end`, `set`, `headersSent` | `status`, `end`, `header`, `isCommitted` | CR/LF·framing 안전성 유지 |
| `sendStatus`, `redirect`, `type`, `location` | 같은 편의 메서드 | redirect 기본302, 원하는 status 명시 가능 |
| `cookie`, `clearCookie` | 같은 이름 | 삭제 시 기존 Path/Domain 일치 필요 |
| `sendFile`, `download`, stream pipe | `file`, `download`, `stream` | java.io.File/InputStream 소유·안전 규칙 |

`Request`는 같은 타입의 성공한 본문 결과를 캐시합니다. 다른 타입·JSON/폼 혼용·실패 후 재시도는 `BodyAlreadyConsumedException`입니다. 기본 UTF-8 JSON 한도1 MiB, 깊이64와 엄격한 문법·trailing data 검사를 유지합니다. [Request](request.md), [JSON](json.md), [응답](response.md)

### 내장 확장과 보안

- `express.json()`/`urlencoded()` 미들웨어 설치 대신 `body(...)`/`form(...)`이 필요할 때 읽습니다. `jsonLimit`, `formLimit`, `multipart`로 제한합니다. `express.raw()`/`text()`에 해당하는 별도 공개 raw/text 본문 파서는 없습니다. JSON 문자열은 `body(String.class)`로 읽을 수 있습니다.
- `cors()` 대신 명시 `app.cors(...)`를 사용합니다. preflight204와 일반 OPTIONS200을 구분하고 WS Origin은 별도로 설정합니다. CORS 오류는 추적한 grant/Vary만 남기고 앱 헤더를 지우는 개인정보 보호 확장을 유지합니다.
- `express.static`은 `staticFiles`입니다. 이제 일반 HTTP 등록 순서에 참여합니다. 정적 파일 canonical containment·symlink 거부·Range/조건부 응답은 유지합니다. cache(pattern, policy)는 먼저 맞는 정책을 씁니다.
- multipart 기본 한도는 파일32 MiB·요청64 MiB·파트20개·메모리 임계64 KiB이며 무제한 설정을 추가하지 않습니다. `file.save(...)`로 보존하지 않은 임시 파일은 보유 중인 요청 작업이 끝난 뒤 정리합니다.
- `accessLog`와 등록 서비스의 로깅·종료 소유권은 유지합니다. [로깅](logging.md), [수명주기](lifecycle.md)
- WS의 `(req, ws)` 인자 순서는 express-ws의 `(ws, req)`와 다릅니다. onText/onBinary/onJson·close·Origin·큐/크기·Ping/idle 제한은 그대로입니다. WS 라우트에 일반 HTTP 고급 경로 문법을 적용하지 않습니다. SSE는 `res.sse()`와 `SseEvent`를 사용합니다.

## 옮길 때 확인할 동작

### 1. 요청은 여러 스레드에서 실행됩니다

Node의 단일 event loop와 달리 요청들이 병행합니다. 공유 서비스·카운터·캐시는 동기화가 필요합니다. 작업 스레드 수는 제한되어 있습니다.

```java
// Express: let count = 0; 은 안전하지만 Java에서는 아닙니다.
AtomicLong count = new AtomicLong();
app.get("/count", (req, res) -> res.json(count.incrementAndGet()));
```

### 2. 응답과 next를 나중에 호출할 수 있습니다

반환만 하면 자동으로 empty200을 보내지 않습니다. saved Next로 재개하거나 나중에 응답하세요. 세 인자 AsyncRouteHandler가 반환한 pending Deferred는 조기 응답 뒤에도 요청 작업 lease를 유지합니다. 성공 resolve는 next/end를 자동 호출하지 않고 rejection은 뒤 오류 레이어로 갑니다. 단순히 스레드를 만들거나 Request를 저장한 것은 lease가 아닙니다. 본문 선읽기를 보장하지 않습니다. [Deferred](middleware.md#deferred와-늦은-작업), [Kotlin 예제](kotlin.md#deferred-작업과-요청-보유)

### 3. JSON은 타입을 명시합니다

다음은 서로 다른 본문 형태의 **대안**입니다. 한 요청에서 연속 호출하지 마세요.

```java
User user = req.body(User.class);                         // 객체
List<User> users = req.body(new TypeRef<List<User>>() {}); // 제네릭
```

Java의 body(Class/TypeRef)는 JSON null을 null로 돌려줍니다. 선택 Kotlin `body<T>()`는 non-null T를 요구하므로 JSON null이면400입니다. Gson은 Kotlin 기본 인자·null 제약을 이해하지 못하며 기본 codec은 Unsafe 할당을 끕니다. 필드가 nullable인 기본 생성자 DTO를 검증해서 사용하세요.

### 4. 바인딩과 검증 기본값은 유지합니다

기본은127.0.0.1입니다. LAN은 `app.server(o -> o.bindAddress("0.0.0.0"))`를 명시하고 인증을 설치하세요. 루프백 밖은 보안 경고를 남깁니다. proxy 신뢰 기본 false이며 `trustProxy(true)`는 검증되지 않은 첫 X-Forwarded-For/Proto 값을 사용하므로 헤더를 덮어쓰는 신뢰 프록시 뒤에서만 켭니다. Host·request-line·프레이밍 제한은 별개로 유지합니다.

### 5. 문자열 기본은 case-insensitive·non-strict, 등록 순서입니다

중복·겹치는 라우트는 합법이고 더 구체적인 경로가 먼저 오지 않습니다. 한 Route 안의 HEAD 존재 여부와 앞 Route의 GET fallback을 구분하세요. live HTTP 등록·기존 builder 추가는 RUNNING에서도 가능하지만 server·codecs·CORS·groups·static·onError·WS 설정은 NEW에서만 바꿉니다. 종료된 앱은 재시작/추가하지 않습니다.

### 6. 일반 최종 응답은404·HTML·OPTIONS200입니다

메서드만 다른 경로도 미처리이면404입니다. 자동 OPTIONS는 실제 순회한 현재 라우터의 메서드만 모아200·Allow·같은 평문 본문을 보내며 Content-Type은 charset 없는 text/plain입니다. CORS preflight 204·OPTIONS *·WS legacy 프로필과 구분하세요. HEAD는 GET 길이를 유지하고 본문 바이트를 보내지 않습니다.

### 7. 기본 오류는 HTML이고 Java stack이 보일 수 있습니다

오류는 등록 위치 뒤의 ErrorMiddleware/onError로 진행합니다. 미처리 오류는 HTML 이스케이프한 Java 예외 표현이며 기본적으로 내부 stack이 보일 수 있습니다. 안전한 공개 오류는 앱이 JSON/평문으로 명시 처리하세요. 이미 보낸 응답에 두 번째 오류 본문을 commit하지 않습니다.

아래는 보존된 **표준 JSON 확장**의 형식 예입니다. 일반 HTTP 기본 오류라고 해석하지 마세요. pre-request·수명주기·WS/legacy 경로에서 이 프로필이 남습니다.

```json
{"error":{"code":"BAD_REQUEST","message":"Invalid JSON request","requestId":"..."}}
```

### 8. 서비스는 명시 등록합니다

서비스 등록은 onStart에서 하고 요청에서 같은 객체를 조회합니다. 소유한 AutoCloseable은 종료 때 역순으로 닫습니다.

```java
app.onStart(ctx -> ctx.register(UserService.class, new UserService()));
app.get("/users", (req, res) -> res.json(req.service(UserService.class).findAll()));
```

### 9. 인증·CORS·정적 파일 순서를 점검합니다

use와 라우트/마운트/오류 레이어 전체가 하나의 순서입니다. 정적 파일을 인증보다 먼저 등록하면 인증 없이 응답할 수 있습니다. 뒤에 등록한 전역 미들웨어가 앞 라우트보다 먼저 실행되지는 않습니다.

```java
Middleware requestId = (req, res, next) -> next.run();   // 요청마다 ID를 붙이는 전역 미들웨어라고 합시다
app.use(requestId);      // 1번째
app.use("/api", auth);   // /api 경로에서 2번째
```

## 예: Express 앱 옮기기

아래는 앱 구성 조각입니다. Todo·TodoService·todos·auth와 import는 앱에서 제공합니다. [실행 가능한 예제](../../examples/README.md)는 완전한 진입점과 실제 서비스를 포함합니다. Java와 Kotlin의 body null 처리 차이도 확인하세요.

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
    val todo = req.body(Todo::class.java) ?: throw HttpException(400, "JSON body is null")
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

## 범위 밖의 기능

템플릿 엔진/`res.render`, `res.format`, `req.hostname`·`req.is`·`req.accepts`, signed cookie, 별도 raw/text 본문 파서, 네이티브 Kotlin suspend handler는 제공하지 않습니다. 공개된 API·기존 확장의 범위로 비교하세요. Java Pattern은 Java 의미이고 WS는 기존 엄격 프로필이며, 문자열 `send`·타입 본문·보안 기본값도 Express와 동일하다고 주장하지 않습니다.
