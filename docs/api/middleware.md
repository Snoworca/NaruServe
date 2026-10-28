# 미들웨어와 오류 처리

[English](en/middleware.md) | [문서 목록](README.md)

0.10.0 체크아웃의 일반 HTTP 스택입니다. 콜백·응답·오류를 Java 8/API 21에서 사용하며 Promise·Coroutine 라이브러리를 필수로 요구하지 않습니다.

## 미들웨어

기존 핵심 시그니처는 다음과 같습니다. 실제 `Next`에는 아래에서 설명하는 기본 메서드 `route()`·`router()`도 있습니다.

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

| 호출 | 동작 |
|---|---|
| `app.use(mw)` | 현재 위치에 전역 미들웨어 추가 |
| `app.use("/api", mw)` | 접두사가 맞을 때 실행. 콜백 안에서 매칭 접두사를 제거 |
| `app.get(path, mw1, mw2, ...)` | 세 인자 콜백들을 순서대로 추가 |
| `app.get(path, mw, handler)` | 기존 미들웨어 + 두 인자 최종 핸들러 편의 API |
| `app.route(path).use(mw).get(handler)` | 같은 Route에 콜백을 차례로 추가 |

`get(path, Middleware...)`와 고정 인자 오버로드는 Kotlin 후행 람다도 받습니다. null·빈 콜백 배열은 등록 전에 거부합니다. Route 안의 `use`는 모든 메서드에 적용되는 콜백이지만 등록 순서는 그대로입니다. 앞 핸들러가 끝내면 뒤에 붙인 `use`가 자동으로 실행되지 않습니다.

```java
Middleware adminOnly = (req, res, next) -> next.run();   // 위 예제의 adminOnly 자리

app.route("/api/admin")
   .use(auth)
   .use(adminOnly)
   .get((req, res) -> res.send("admin"));
```

```kotlin
app.route("/api/admin")
    .use(auth)
    .use(adminOnly)
    .get { req, res -> res.send("admin") }
```

### 요청 ID와 인증

아래 `auth`, `admin`, `User`는 앱에서 제공합니다. 응답한 뒤 계속할 의도가 없으면 `return`하고 `next.run()`을 부르지 않습니다.

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
    User user = req.attributeAs("user", User.class);
    if (user == null || !user.isAdmin()) {
        res.status(403).json("Forbidden");
        return;
    }
    next.run();
};
app.get("/api/admin", adminOnly, (req, res) -> res.json(admin.status()));
```

```kotlin
app.use("/api") { req, res, next ->
    if (!auth.isAuthorized(req)) {
        res.status(401).json("Unauthorized")
        return@use
    }
    next.run()
}
```

### HTTP CORS

브라우저 preflight를 인증보다 먼저 처리하려면 CORS를 먼저 등록합니다.

```java
app.cors(cors -> cors
    .allowedOrigins("https://app.example")
    .allowedMethods(Method.GET, Method.POST)
    .allowedHeaders("Content-Type", "Authorization")
    .credentials(true)
    .maxAge(600));
app.use(auth);
```

- `allowedOrigins`는 경로·끝 슬래시 없는 정확한 HTTP(S) origin 목록입니다. 기본은 없음이며 `*`는 모든 origin, 문자열 `null`은 opaque origin을 허용합니다.
- 기본 메서드는 GET, HEAD, POST입니다. `allowedMethods`는 집합을 바꾸고 `allowedHeaders`는 대소문자를 구분하지 않는 허용 목록(기본 빈 목록)을 바꿉니다.
- `credentials` 기본은 false입니다. `credentials(true)`와 `*`를 함께 쓰면 등록 오류입니다. `maxAge`는 0 이상의 초이며 기본0은 캐시 헤더를 생략합니다.
- 허용 origin에는 `Access-Control-Allow-Origin`을, credentials를 켰으면 `Access-Control-Allow-Credentials: true`를 붙입니다. 보통 요청의 비허용 origin은 grant 없이 계속합니다. CORS 자체가 인증은 아닙니다.
- `Origin`과 `Access-Control-Request-Method`가 있는 OPTIONS는 preflight입니다. 허용 메서드·헤더이면 본문 없는204로 종료하고 인증 콜백은 실행하지 않습니다. 잘못되거나 비허용이면403입니다. 일반 자동 OPTIONS200과 구분하세요.
- Origin이 없는 요청도 `Vary: Origin`을 설정합니다. 보통 응답의 기존 Vary(`*` 포함)는 합치며 preflight는 메서드·요청 헤더 차원도 더합니다.
- **이 명시적 CORS 확장의 오류 응답**은 앱의 일반 헤더를 지우고 프레임워크가 추적한 CORS grant·Vary만 복원합니다. credentials도 유지하며 앱/`HttpException`의 헤더가 grant를 덮어쓰지 못합니다. CORS를 설치하지 않은 일반 HTTP 오류는 보통 앱 헤더를 유지합니다.
- 옵션은 복사하며 `cors`는 listen 전에 설정합니다. WS Upgrade에는 이 정책 대신 `app.webSocket(o -> o.allowedOrigins(...))`를 씁니다.

### 라우트 그룹

`group(prefix, configurer)`는 안전한 문자열 접두사와 공통 미들웨어를 묶는 기존 편의 API입니다. 독립 `Router`의 재마운트·Pattern 경로 API와 구분하세요.

```java
Middleware adminOnly = (req, res, next) -> {
    if (!"admin".equals(req.header("X-Role"))) {
        res.status(403).end();
        return;
    }
    next.run();
};
Middleware audit = (req, res, next) -> {
    req.application().logger().info("audit " + req.path());
    next.run();
};
app.group("/api", api -> {
    api.use(auth);
    api.get("/users", (req, res) -> res.send("users"));
    api.group("/admin", admin -> {
        admin.use(adminOnly);
        admin.route("/reports").use(audit).get((req, res) -> res.send("reports"));
    });
});
```

위 `/api/admin/reports`는 그룹 auth → adminOnly → audit → handler 순서입니다. 그룹의 `use`는 구성 콜백 안의 위치와 무관하게 그 그룹·하위 그룹 라우트 앞에 적용됩니다. 부모 미들웨어가 자식보다 먼저입니다. 그룹 밖의 같은 접두사 라우트에는 자동으로 붙지 않습니다.

그룹의 `route(path)`도 하나의 지속적인 Route builder이며 `.use`·메서드 콜백은 그 안에서 등록 순서대로 실행됩니다. 경로는 `/`로 시작하고 경계 슬래시를 합칩니다(`/api/` + `/users` → `/api/users`, `/api` + `/` → `/api`). dot/dot-dot, 쿼리, fragment, 역슬래시와 인코딩된 구분자를 거부합니다. `group`는 listen 전에 부르고, 그룹·그룹 builder는 구성 콜백 안에서만 사용하세요. 콜백이 실패하면 그 그룹의 라우트를 일부만 등록하지 않습니다.

### 실행 순서

일반 HTTP의 전역/prefix `use`, Route, 마운트 Router, 정적 파일, 오류 레이어는 **하나의 등록 순서**를 따릅니다. 뒤에 등록한 전역 미들웨어를 앞으로 옮기지 않습니다. 앞 레이어가 계속할 때만 다음으로 갑니다. 정적 파일 미스와 전체 스택 종료의404도 이 순서를 따릅니다.

```java
Middleware requestId = (req, res, next) -> next.run();   // 요청마다 ID를 붙이는 전역 미들웨어라고 합시다
app.use(requestId);          // 1번째로 실행
app.use("/api", auth);       // /api 경로에서 2번째로 실행
```

### next.run 규칙

같은 실행 스레드의 `next.run()`은 일반적으로 후속 콜백을 실행하고 돌아오므로 전후 코드를 둘 수 있습니다. 깊은 체인은 스택 한도를 위해 실행을 넘길 수 있고 다른 스레드의 호출은 안전하게 큐에 들어갑니다. 어떤 경우에도 사용자 콜백을 요청 모니터 안에서 실행하지 않습니다. `next`를 호출한 자식 스레드를 원래 콜백이 기다린다는 이유만으로 교착하지 않습니다.

```java
app.use((req, res, next) -> {
    long start = System.nanoTime();
    next.run();
    long ms = (System.nanoTime() - start) / 1_000_000;
    app.logger().debug(req.path() + " " + ms + "ms");
});
```

이 시간은 전체 네트워크 전송 시간이 아닙니다. 스트림·SSE·WS 작업은 뒤에 계속될 수 있습니다. 반복된 `next`나 응답 후 명시적 `next`를 일괄 금지하지 않습니다. 실제 커서의 다음 단계로 진행하므로 여러 호출을 중복 작업 방지 수단으로 사용하지 마세요. 중단·강제 취소·종료 후 호출은 닫힌 리소스나 앱 서비스를 되살리지 않습니다.

| 호출 | 일반 HTTP에서의 의미 |
|---|---|
| `next.run()` | 정상 흐름 계속; 오류 미들웨어 안에서는 오류를 해제하고 정상 흐름 재개 |
| `next.error(error)` | 뒤의 오류 레이어로 전달. null은 허용하지 않음 |
| `next.route()` | 현재 Route의 나머지 콜백을 건너뜀. 일반 use에서는 정상 계속 |
| `next.router()` | 가장 가까운 Router를 나감. 최상위에서는 앱 스택을 나감 |

같은 라우터에서 다음으로 진행한 뒤의 캡처를 무조건 옛 값으로 되돌리지 않습니다. 자식 마운트를 나갈 때는 부모의 path/url/baseUrl/params 뷰를 복원합니다. `next.route()`는 경로를 바꾸거나 특정 라우트를 이름으로 선택하는 API가 아닙니다.

응답도 next도 없이 반환하면 **경고 후 자동 empty200으로 끝내지 않고 대기**합니다. 저장한 콜백에서 나중에 next·send·stream·SSE를 시작할 수 있습니다. 앱이 완결·시간 제한·취소 경로를 책임지세요.

### WebSocket과 미들웨어

기존 `app.use("/ws", auth)`는 HTTP Upgrade 인증에 쓰며 연결 뒤의 매 메시지에는 실행되지 않습니다. 유효한 기존 전역/문자열 접두사 미들웨어와 WS 엄격 경로·Origin 규칙은 유지합니다. HTTP 전용 고급 경로나 listen 이후 추가한 HTTP 레이어의 동작을 WS로 일반화하지 마세요. [WebSocket](websocket.md)

## 오류 처리

### 예외를 던지면

동기 예외·`next.error(e)`·반환한 Deferred의 rejection은 현재 위치 **뒤**의 오류 레이어로 진행합니다. 일반 레이어를 건너뛰고 앞의 오류 핸들러를 다시 시작하지 않습니다.

```java
app.get("/users/:id", (req, res) -> {
    User user = users.findById(req.param("id"));
    if (user == null) throw new NotFoundException();   // 앱이 정의한 예외
    res.json(user);
});
```

### app.onError

기존 `ErrorHandler`는 세 인자이고 checked exception을 허용합니다.

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

`onError`는 listen 전에 설정하며 **등록된 위치**의 오류 레이어로 작동합니다. 이미 응답했으면 뒤 오류 핸들러를 자동 실행하지 않고, 응답하지 않으면 같은 오류를 계속 전달합니다. 콜백이 새 예외를 던지면 뒤의 오류 레이어로 그 예외가 갑니다. 위 예제의 앱 로깅은 앱이 추가한 로깅이며 프레임워크의 한 번500 로깅과 별개입니다.

세 인자 `onError` 대신 네 인자 `ErrorMiddleware`를 `app.use((error, req, res, next) -> ...)` 또는 `app.use(path, errorMiddleware)`로 등록할 수 있습니다. `next.error(e)`는 전파/교체, `next.run()`은 정상 흐름 재개입니다. 이미 보낸 응답에 다른 오류 본문을 덧쓰지 않습니다. 늦은 오류는 로그·중단 경로로 가고 두 번째 commit을 하지 않습니다.

### 거부 응답 헤더

`HttpException.status()`는400..599이고 `code()`는 기존 표준 코드 편의 값입니다. `UnsupportedMediaTypeException`은415 하위 타입입니다. 상태를 구분하지 않고 모두500으로 바꾸지 마세요. `withHeader(name, value)`는 같은 예외에 헤더를 추가하고 `headers()`는 순서 있는 읽기 전용 목록입니다.

```java
app.get("/api/busy", (req, res) -> {
    throw new HttpException(503).withHeader("Retry-After", "1");
});
```

```kotlin
app.get("/api/busy") { req, res ->
    throw HttpException(503).withHeader("Retry-After", "1")
}
```

일반 HTTP 최종 처리에서는 예외 헤더를 적용하고, 본문에 맞춰 Content-Encoding·Content-Language·Content-Range를 제거합니다. 명시 CORS의 개인정보 보호 규칙은 위와 같습니다. 이름/값 CR·LF 등 잘못된 헤더는 `IllegalArgumentException`이고 framing 헤더는 응답 작성자가 정합니다. 같은 이름을 여러 번 추가할 수 있으므로 매 요청 새 예외를 만드세요.

### 표준 오류 형식

일반 HTTP 기본은 **HTML**입니다. 404는 `Cannot METHOD /raw/path`, 오류는 `HttpException` 상태를, 그 외에는 이미 선택된 유효한400..599 응답 상태를, 모두 아니면500을 사용하고 Java 예외/stack trace를 HTML 이스케이프합니다. `Content-Type: text/html; charset=utf-8`, `Content-Security-Policy: default-src 'none'`, `X-Content-Type-Options: nosniff`와 UTF-8 바이트 길이를 보냅니다. HEAD는 같은 길이를 유지하고 본문을 보내지 않습니다. 기본 오류가 stack trace를 클라이언트에 노출할 수 있으므로 공개 서비스에서 내부 오류를 감추려면 앱 오류 핸들러로 안전한 응답을 직접 보내세요.

다음 JSON은 **기존 표준 오류 확장 형식의 예**이며 일반 HTTP의 기본 응답 예가 아닙니다. pre-request 검증·수명주기 거부·보존된 WS/legacy 경로에서 이 형식이 남을 수 있습니다. `HttpException.code()`도 이 기본 HTML을 JSON으로 바꾸지 않습니다. 임의의 공개 표준 JSON 렌더러 API가 있다고 가정하지 말고 앱의 `onError`/`ErrorMiddleware`에서 `res.json(...)`을 명시하세요.

```json
{"error":{"code":"BAD_REQUEST","message":"Invalid JSON request","requestId":"..."}}
```

| 원인 | 상태 |
|---|---|
| 잘못된 참여 캡처·쿼리·JSON/폼 | 400 |
| 본문/업로드 한도 초과 | 413 |
| 지원하지 않는 Content-Type/charset | 415 |
| 일반 HTTP 최종 경로·메서드 미스 | 404 |
| 미처리 서버 예외 | 500 |
| 앱 수명주기 거부 | 503 + `Connection: close`, 기존 오류 프로필 |

전송 계층이 앱에 도달하기 전에 거부한 Host·프레이밍·과부하는 별도 평문/연결 종료 결과일 수 있습니다. 모든400을 위 HTML이나 앱 `onError`가 만들지는 않습니다. [기본값과 보안](defaults.md)

## Deferred와 늦은 작업

`AsyncRouteHandler`는 `Deferred handle(Request, Response, Next) throws Exception`입니다. `getAsync(path, handler)`, `methodAsync(token, path, handler)`, `useAsync(path, handler)`를 사용합니다. 반환한 pending Deferred는 응답이 먼저 끝나도 명시적 요청 작업 lease를 보유합니다. 단순히 저장한 Request나 임의 스레드 자체가 이 lease를 만들지는 않습니다.

| Deferred API | 계약 |
|---|---|
| `new Deferred()` / `Deferred.pending()` | 미완료 결과 |
| `Deferred.resolved()` / `Deferred.rejected(error)` | 이미 결정된 결과 |
| `resolve()` / `reject(error)` | 최초 결정만 true. 이후 호출 false. null 오류는 거부 |
| `onRejected(FailureHandler)` | 원래 오류 관찰. 늦은 등록도 통지 |
| `onSettled(Runnable)` | 성공·실패 결정 관찰. 늦은 등록도 통지 |
| `isDone()` | 결정 여부 |

resolve는 **next나 응답 종료를 자동 호출하지 않습니다**. producer의 마지막 요청·업로드 접근이 끝난 뒤 결정하세요. 여러 lease는 모두 결정되거나 terminal 취소될 때까지 정리를 늦춥니다. observer는 내부 잠금 밖에서 실행되며 하나의 observer 예외가 나머지를 막지 않습니다. 결정 전에 등록된 rejection observer 뒤에 settlement observer가 실행되고, 늦은 rejection observer는 병행할 수 있습니다.

내장 어댑터와 코어는 `HttpExchange.retainRequest()`로 응답 전송 완료와 요청 입력/context 해제를 분리합니다. 요청 본문을 미리 읽겠다는 약속은 아닙니다. 조기 응답 뒤 첫 `req.body(...)`가 필요한 작업은 **반환한 pending Deferred** 안에서 보유하고 마지막 사용 후 결정하세요. 사용자 어댑터의 기본 retainRequest는 호환용 no-op이므로, 물리적 입력/context 보유는 사용자 구현이 제공해야 합니다. 강제 취소·timeout은 보유를 철회할 수 있고 종료된 입력을 다시 열지 않습니다. [수명주기](lifecycle.md), [Kotlin 예제](kotlin.md#deferred-작업과-요청-보유)

## 조기 응답 뒤 첫 본문 읽기

POST /work에 JSON 문자열 "hello"를 보냅니다. 설명용 Thread는 조기202 뒤 본문을 처음 읽고 마지막 요청 접근 후 결정합니다. 실제 부하에서는 제한된 앱 관리 executor와 취소 정책을 쓰세요. 이미 보낸 응답의 rejection은 뒤 오류 처리로 넘기고 두 번째 JSON을 쓰지 않습니다.

```java
app.methodAsync("POST", "/work", (request, response, next) -> {
    Deferred work = Deferred.pending();
    response.status(202).send("accepted");
    new Thread(() -> {
        try {
            String value = request.body(String.class);
            request.attribute("processed", value);
            work.resolve();
        } catch (Throwable error) {
            work.reject(error);
        }
    }, "retained-work-example").start();
    return work;
});
app.use((error, request, response, next) -> {
    if (response.isCommitted()) {
        next.error(error);
        return;
    }
    response.status(500).json(Collections.singletonMap("error", "Request failed"));
});
```
