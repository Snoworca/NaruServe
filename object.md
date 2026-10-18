 ---
title: Android 5.0 호환 경량 REST 프레임워크 상세 설계사양서
subtitle: Express 스타일 Java/Kotlin HTTP Runtime · REST · WebSocket · SSE · Multipart · Static · Lifecycle
version: 1.0.0
status: 설계 기준선 (구현 전)
date: 2026-10-18
language: ko-KR
---

# Android 5.0 호환 경량 REST 프레임워크 상세 설계사양서

**문서 버전:** 1.0.0  
**기준일:** 2026-10-18 (KST)  
**문서 상태:** 설계 기준선 / 코드 구현 및 실기기 검증 전  
**대상:** Java 8, Kotlin, Android 5.0(API 21)+, 일반 JVM, 선택적 Servlet 환경  
**임시 프로젝트 이름:** `RestApp` (정식 아티팩트·패키지명 미확정)

> **해석 원칙:** 본 문서에서 **필수(MUST)** 는 최초 구현·릴리스에서 충족할 기능, **권장(SHOULD)** 는 충분한 근거 없이는 변경하지 않을 정책, **선택(MAY)** 은 확장 시 구현할 기능을 뜻합니다. 대화에서 명시 확정된 요구사항은 `확정`, 앞선 대답에서 권고한 내용은 `설계 채택` 또는 `검토/선택`으로 구분합니다. 제시한 API 코드는 **구현 완료된 코드가 아니라 구현 목표 계약**입니다.

---

## 1. 요약 및 목표

### 1.1 비전

Spring Boot의 DI/컴포넌트 스캔 없이, **Servlet 수준의 실용적 HTTP 기능**과 **Express.js처럼 간결한 라우팅 문법**을 제공하는 경량 서버 프레임워크를 만든다. 하나의 임베디드 HTTP 포트에서 REST/JSON, Multipart, WebSocket, SSE, 정적 리소스 서버를 서비스한다. Android 5.0부터 최신 Android까지, 그리고 일반 Java 8 JVM에서 **하나의 공개 Java API**를 Kotlin과 함께 사용한다.

### 1.2 설계 원칙

1. **Android 5.0(API 21)이 절대 최소 런타임**이다. JVM은 Java 8 이상을 지원한다.
2. 공개 Core는 `android.*`, `javax.servlet.*` 및 특정 HTTP 서버에 의존하지 않는다.
3. Java 8 함수형 인터페이스(SAM)로 Java 람다와 Kotlin 람다를 모두 지원한다.
4. 외부 공개 API는 가능한 한 `app.get`, `app.route`, `app.use`, `req.body`, `res.json`, `app.ws`, `app.staticFiles` 등 짧고 예측 가능해야 한다.
5. 재사용 가능한 객체는 시작 시 **명시적 등록**하고 요청·WebSocket·SSE에서 동일 인스턴스를 조회한다. 의존성 자동 주입/자동 스캔은 하지 않는다.
6. 모든 네트워크 입출력, 업로드, 스트리밍은 크기·시간·연결 수 한도가 있어야 한다. DoS 방어가 편의성보다 우선이다.
7. JSON, HTTP 서버, 로거는 SPI로 교체 가능하게 한다.
8. **기본 안전 설정**: 디렉터리 목록 비활성, 숨김 파일 차단, 요청 본문·업로드 제한, 민감 로그 마스킹, 기본 localhost 바인딩.
9. JNI/네이티브 필수 구성요소, Python 및 Node.js 런타임을 요구하지 않는다.
10. 배포되는 모든 의존성에 대한 라이선스(SBOM 포함)와 CVE 점검을 수행한다.

### 1.3 범위 밖 / 향후 가능성

- Spring 스타일 IoC, @Controller, @Autowired, 자동 스캔, AOP 프록시: **범위 밖**.
- **JavaScript V8 바인딩**: 최초 대화에서 검토한 가능한 확장; 기본 릴리스 필수 기능은 아님. 추가 시 Java의 Router/Request/Response API를 JS로 노출하고 Node.js Express 패키지를 실행하지 않는다. V8 isolate 스레드 안전성·호스트 API 권한 통제를 별도로 설계한다.
- Kotlin Coroutine/suspend 라우팅, 인증 구현체, OpenAPI 자동 생성, WebDAV, HTTP/2, gRPC, HTTP 클라이언트, 프록시, 클러스터링: **선택/향후**.
- 서비스 자동 재시작과 장시간 백그라운드 실행 정책은 Android 앱의 별도 책임이다.

---

## 2. 용어 및 아키텍처

| 용어 | 의미 |
|---|---|
| `RestApp` | 구성·라우트 등록·서버 기동/종료 진입점 |
| `Router` | HTTP Method + 정규화된 Path 매칭 및 디스패치 |
| `Method` | `GET`, `POST`, `DELETE` 등 HTTP Method 상수/팩토리 |
| `MethodSet` | 불변 HTTP Method 집합. Fluent 조합 및 제외 |
| `Request` | 요청 경로/헤더/쿠키/본문/속성/서비스 조회 추상화 |
| `Response` | 상태/헤더/JSON/파일/스트리밍 응답 추상화 |
| `Middleware` | 요청 전후 공통 처리 체인, `Next` 진행 제어 |
| `ApplicationContext` | 애플리케이션 전역 속성·서비스 레지스트리·로거 |
| `ServiceRegistry` | `Class<T>` 키 기반의 타입 안전한 서비스 저장소 |
| `HttpAdapter` | NanoHTTPD 포크 또는 Servlet 등의 전송/연결 처리 구현 |
| `JsonCodec` | JSON 직렬화·역직렬화 SPI |
| `UploadFile` | 단일 Multipart 파트의 메타데이터와 스풀 파일/스트림 |
| `SseClient` | EventSource용 장기 HTTP 응답 세션 |
| `WebSocketSession` | RFC 6455 웹소켓 연결 단위 세션 |
| `StaticMount` | URL prefix ↔ File/ResourceResolver 매핑 |
| `CachePolicy` | Cache-Control/Validator 정책 값 객체 |
| `Logger` / `AccessLog` | 내부 이벤트 로그와 HTTP 접근 로그(서로 별개) |

### 2.1 계층 및 의존성 방향

```text
+-----------------------------------------------------------------------+
| 사용자 애플리케이션: Java 8 / Kotlin / (향후 JavaScript V8 Bridge)   |
| app.route, app.ws, app.use, app.staticFiles, onStart/onStop           |
+-------------------------------+---------------------------------------+
                                |
+-------------------------------v---------------------------------------+
| rest-core: RestApp, Router, Request, Response, MethodSet,             |
| Middleware, ApplicationContext, ServiceRegistry, Lifecycle, Logging   |
+---------------+--------------------+---------------------+-------------+
                |                    |                     |
       +--------v--------+  +--------v---------+ +---------v---------+
       | rest-json       |  | rest-multipart    | | rest-static        |
       | JsonCodec/Gson  |  | parser/spool      | | cache/mime/range  |
       +-----------------+  +------------------+ +-------------------+
                                |
                  +-------------v------------------+
                  | rest-stream (SSE / WebSocket)  |
                  +-------------+------------------+
                                |
                   +------------v--------------+
                   | HttpAdapter / Network I/O |  <--- 외부 의존성 분리
                   +-----+---------------+------+
                         |               |
                +--------v----+     +----v-------------+
                | Nano 포크   |     | Servlet Adapter  |
                | Android/JVM |     | JVM 전용         |
                +-------------+     +------------------+
```

`rest-core`에서 HTTP-specific 타입을 참조하는 것은 허용하지만, Android/Servlet 서버 구체 클래스와 Gson 구현 클래스를 참조하는 것은 금지한다. **`RestApp.create()`의 기본 엔진·Gson 자동 선택은 배포용 `rest-bootstrap`/통합 패키지에서 조립**하고, `rest-core`만 사용하는 개발자는 `RestApp.builder().httpAdapter(...).jsonCodec(...).build()`에 상응하는 명시 초기화 진입점을 사용한다. 논리적 모듈은 유지하되 릴리스에서는 필요 시 소수 JAR로 패키징할 수 있다.

### 2.2 기본 네트워크 엔진

- **Android/JVM 기본 후보:** 보안 검토·수정한 **NanoHTTPD 포크 된 TinyHttpd**. /Users/beom/work/git/TinyHttpd 경로를 따른다. 
- **Servlet 선택 모듈:** Servlet 3.1+의 async/multipart 기능과 WAS WebSocket API는 컨테이너별 능력 협상을 필요로 한다. `javax`/`jakarta` 버전은 별도 모듈 분리한다. Android Core에 Servlet API가 유입되어서는 안 된다. Servlet 위에 올려서 사용할 수 있어야 한다. 


---

## 3. 공개 API: 부트스트랩·라우트·Method

### 3.1 사용자 경험의 기준선

```java
RestApp app = RestApp.create();

app.get("/users", (req, res) -> res.json(userService.findAll()));
app.get("/users/:id", (req, res) -> res.json(userService.findById(req.param("id"))));
app.post("/users", (req, res) -> {
    User user = req.body(User.class);
    res.status(201).json(userService.create(user));
});
app.delete("/users/:id", (req, res) -> {
    userService.delete(req.param("id"));
    res.status(204).end();
});
app.listen(8080);
```

### 3.2 동일 Path에서 GET/DELETE 등 여러 Method 사용 — 확정

```java
app.route(Method.get().delete().post(), "/users/:id", (req, res) -> {
    switch (req.method()) {
        case GET:
            res.json(find(req.param("id"))); break;
        case DELETE:
            delete(req.param("id")); res.status(204).end(); break;
        case POST:
            res.status(201).json(create(req)); break;
        default:
            res.status(405).end();
    }
});

app.route(Method.any().except(Method.DELETE), "/resource", commonHandler);
app.route(Method.GET, "/health", healthHandler);
```

`Method`는 Java enum (`GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS` 등)으로 제공한다. **정적 메서드** `Method.get()` / `Method.any()` / `Method.of(...)`가 **`MethodSet`**을 반환하고, `MethodSet.delete()` / `.post()` / `.except(Method.DELETE)`가 **새 불변 `MethodSet`**을 반환한다. enum의 `Method.delete()` 같은 인스턴스 메서드를 억지로 구현하지 않는다. `TRACE`·`CONNECT`는 기본 라우팅 노출을 비활성화하며 명시 정책으로만 활성화한다.

```java
public enum Method { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE, CONNECT;
    public static MethodSet get() { return MethodSet.of(GET); }
    public static MethodSet any() { return MethodSet.all(); }
    public static MethodSet of(Method... methods) { return MethodSet.of(methods); }
}
// MethodSet.of/all은 불변 집합 생성 팩토리이다.
// MethodSet.delete()/post()/except(Method)은 변경된 새 집합을 반환한다.
```

**별도 핸들러 등록**도 지원한다:

```java
app.route("/users/:id")
   .get(getUserHandler)
   .delete(deleteUserHandler);
// 동등한 표현: app.get(path, handler); app.delete(path, handler)
```

| 형식 | 용도 | 처리 |
|---|---|---|
| `app.get(path, handler)` | 단일 메서드 | 단일 핸들러 |
| `app.route(Method.get().post(), path, handler)` | 여러 메서드 공통 핸들러 | `req.method()` 분기 가능 |
| `app.route(path).get(h1).delete(h2)` | 동일 경로·서로 다른 핸들러 | 메서드별 각각 처리 |
| `Method.any().except(Method.DELETE)` | 특정 Method 제외 | 지원 집합 내 제외 |
| 옛 `app.serve(...)` | 과거 대안 | **폐기, `app.route(...)`로 통일** |

### 3.3 Path 매칭과 상태 코드

- 라우터 키: `HTTP Method + 정규화한 Path 패턴`.
- 경로 변수: `:id`. 쿼리 파라미터와 별개. `/users/me` 같은 정적 세그먼트가 `/users/:id`보다 우선한다.
- 같은 우선순위의 충돌 패턴·중복 Method+Path 등록은 시작 전 **설정 오류**로 처리한다.
- URL은 **정확히 한 번** 디코딩하고 부적절한 인코딩/`NUL`/경로 이탈을 거부한다. `//`, trailing slash, 대소문자 정책은 명시 설정이며 기본은 case-sensitive, trailing slash를 구분하는 엄격 일치.
- Path 자체가 없으면 `404`.
- Path가 있으나 Method가 허용되지 않으면 `405`, `Allow` 헤더를 포함한다.
- `GET` 등록 시 기본 `HEAD`를 자동 허용하고 응답 Body를 생략한다. 명시 HEAD 핸들러가 우선한다.
- `OPTIONS` 기본 응답은 Allow를 포함한 `204`, 명시 OPTIONS 핸들러가 우선한다.
- 구현되지 않은 알 수 없는 Method는 `501`을 고려한다(HTTP 의미론에 따라 구분).
- WebSocket 핸드셰이크는 **GET + Upgrade** 요청으로 구분한다. 동일 URL의 정상 GET 라우트와 공존할 수 있다. Upgrade 누락 시 WS-only 경로의 정책 응답은 `426 Upgrade Required`.
- **명시 라우트 우선, 정적 리소스 fallback 후 404**. Global middleware는 공통 선행 실행한다.

### 3.4 인터페이스 초안

```java
@FunctionalInterface
public interface RouteHandler {
    void handle(Request req, Response res) throws Exception;
}

public interface RestApp {
    // Java 8 interface static factory: RestApps는 부트스트랩 조립 클래스
    static RestApp create() { return RestApps.create(); }
    RestApp route(Method method, String path, RouteHandler handler);
    RestApp route(MethodSet methods, String path, RouteHandler handler);
    RouteBuilder route(String path);
    RestApp get(String path, RouteHandler handler);
    RestApp post(String path, RouteHandler handler);
    RestApp delete(String path, RouteHandler handler);
    // put/patch/head/options도 대응
    ServerHandle listen(int port);
    void shutdown(long timeout, TimeUnit unit);
}
```

`listen(int)`은 성공 시 사용 포트/주소와 상태를 가진 `ServerHandle`을 반환하고 **기동 및 바인딩은 동기적으로 완료한 뒤** 호출 스레드를 반환한다(장기적인 accept loop는 워커 스레드에서 실행). 시작 완료 전에 HTTP 요청이 수신되지 않아야 하며 bind 실패 시 예외를 전달한다. 종료/시작의 원자성과 thread-safety는 §11 참조.

---

## 4. Request / Response와 본문 모델

### 4.1 Request API

| 영역 | 예시 | 의미 |
|---|---|---|
| Request 정보 | `req.method()`, `req.path()`, `req.rawPath()` | HTTP Method / 경로 |
| 경로 변수 | `req.param("id")` | `:id` |
| Query | `req.query("page")`, `req.queries("tag")` | 중복 값 지원 |
| Header | `req.header("Authorization")`, `req.headers("X-Test")` | 헤더 대소문자 비구분 |
| JSON | `req.body(User.class)` | 명시적 타입 역직렬화 |
| JSON Generic | `req.body(new TypeRef<List<User>>() {})` | 제네릭 지원 |
| 폼 | `req.form("name")` | urlencoded/Multipart 텍스트 필드 |
| 파일 | `req.file("avatar")`, `req.files("attachments")` | 단일/복수 |
| Multipart | `req.parts()` | 전체 파트 |
| Cookie | `req.cookie("session")`, `req.getCookies()` | Servlet 유사 객체 |
| 요청 속성 | `req.attribute("requestId", value)` | Request Scope |
| 서비스 | `req.service(UserService.class)` | Application Singleton |
| 컨텍스트 | `req.application()` | App 전역 속성 |
| SSE 요청 | `req.lastEventId()` | Last-Event-ID |
| 연결 정보 | `req.remoteAddress()`, `req.isSecure()` | 프록시 헤더는 명시 신뢰 설정만 |

`req.body()`를 무인자 사용하거나 JavaScript처럼 임의 Map으로 항상 자동 변환하는 방식은 기본으로 강제하지 않는다. 타입 있는 Java API를 우선한다. 필요 시 `req.jsonTree()`/`req.jsonMap()` 선택 API를 추가한다.

**본문 소비 정책:** 동일 요청 본문은 단일 소유자이다. JSON/raw/form/multipart가 서로 중복 파싱하지 않도록 `BodyState`(`UNREAD → PARSING → CONSUMED/CLOSED`)를 둔다. 반복 요청은 **명시적 캐시 옵션이 없는 한** `BodyAlreadyConsumedException`으로 처리한다. 크기 한도는 파서 진입 이전부터 적용한다.

### 4.2 Response API

```java
res.status(201).json(created);
res.status(204).end();
res.header("X-Request-ID", requestId);
res.addCookie(new Cookie("session", "token"));
res.send("plain text");
res.json("hello");        // 본문 JSON 문자열: "\"hello\""
res.cache(CachePolicy.noStore()).json(data);
res.file(file);          // Content-Disposition 정책은 명시
res.download(file, "report.bin");
SseClient sse = res.sse();
```

- `res.json(object)`는 `application/json; charset=UTF-8` + 설정된 `JsonCodec` 적용.
- `res.send(String)`은 `text/plain; charset=UTF-8` 또는 명시 Content-Type을 따른다. HTML은 명시 `res.html(...)`가 있을 때만 반환한다.
- `res.send(byte[])`, `res.stream(InputStream, contentType)` 지원을 권장한다.
- 응답은 상태(`NEW`, `COMMITTED`, `STREAMING`, `COMPLETED`, `ABORTED`)로 관리한다. **단 한 번만 commit** 가능하며 응답 종료 후 header/body 변경 시 예외.
- `204`, `304`, HEAD 응답에는 메시지 본문을 보내지 않는다.
- 비동기 전송(SSE/파일/WebSocket)은 핸들러 반환과 응답 완료가 다르다.

### 4.3 표준 오류 형식

```json
{"error":{"code":"BAD_REQUEST","message":"Invalid JSON request","requestId":"..."}}
```

- 기본은 내부 stack trace를 클라이언트에 노출하지 않는다.
- 예외 매핑: 파싱 실패 `400`, 요청 크기 초과 `413`, 지원하지 않는 미디어 타입 `415`, 유효성 검증 `400` 또는 명시 `422`, 처리할 수 없는 요청 `404/405`, 서버 오류 `500`, 과부하 `503`.
- `Content-Type: application/problem+json`(RFC 9457)은 향후 선택 형식으로 남긴다.

---

## 5. JSON 직렬화 / 역직렬화

### 5.1 확정사항

**기본 JSON 엔진:** Gson **2.14.0**, Java 8·Android API 21 지원을 기준선으로 한다. Gson 2.15+은 공식 기준 Android API 24+이므로 API 21 지원 릴리스에서 버전 변경을 금지한다. JSON Core에 Gson 타입을 노출하지 않고 `JsonCodec` SPI로 캡슐화한다. 사용자는 Jackson/Moshi로 교체 가능하다. Android 21 빌드에서 Jackson 2.21 계열을 기본으로 사용하지 않는다(공식 Android API 26 이상).

```groovy
implementation 'com.google.code.gson:gson:2.14.0'
```

```java
public interface JsonCodec {
    <T> T decode(Reader reader, Type type) throws IOException;
    void encode(Object value, Writer writer) throws IOException;
}
```

```java
Gson gson = new GsonBuilder()
    .setStrictness(Strictness.STRICT)
    .disableJdkUnsafe()
    .create();
app.jsonCodec(new GsonJsonCodec(gson));
```

- `disableJdkUnsafe()`는 생성자를 우회한 인스턴스 생성을 피하지만 전체 보안 검증을 대신하지 않는다. DTO는 기본 생성자 또는 명시 TypeAdapter를 사용할 것을 권장.
- 직렬화 형식은 단일/배열/Map/POJO/문자열/원시값/`null`을 올바르게 JSON으로 변환해야 한다.
- JSON 중첩 깊이 **기본 64**, 요청 본문 **기본 1 MiB**(둘 다 설정 가능). `strict` 파싱과 UTF-8 오류 처리.
- 신뢰하지 않는 JSON에서 임의 클래스명으로 인스턴스를 생성하는 **다형성 자동 타입 바인딩 금지**.
- 민감한 도메인 객체를 직접 바인딩하지 않고 DTO + 검증 계층을 권장한다.
- Kotlin `data class` 기본 인수/nullable 지원은 Gson만으로 완전하지 않다. Kotlin 사용자에게는 Moshi(유효 버전 검증 후), 명시 TypeAdapter 또는 Kotlin 전용 확장 모듈을 안내한다.
- Release R8/ProGuard 빌드에서 필드명이 바뀌지 않도록 `@SerializedName` / keep 규칙을 적용하고 minify 활성 테스트를 필수로 한다.

### 5.2 타입 추론과 제네릭

```java
User user = req.body(User.class);
List<User> users = req.body(new TypeRef<List<User>>() {});
res.json(users);
ws.sendJson(user);
```

`TypeRef<T>`는 `java.lang.reflect.Type`을 보존한다. `req.body(User.class)`는 해당 `Content-Type`이 JSON인 경우에만 자동 파싱한다. `+json` 미디어 타입도 지정된 허용 정책에 따라 지원한다.

### 5.3 라이브러리 선택지 기록

| 구현 | Android 21 | 특징 | 정책 |
|---|---|---|---|
| Gson 2.14.0 | 공식 지원 | POJO·작은 의존성·범용 Java | 기본 선택 |
| Moshi 1.15.x | 별도 버전 검증 | Kotlin codegen/nullability에 유리 | 선택 SPI |
| Jackson 2.21.x | 공식 기준 API 26+ | 풍부한 Java 기능 | Android 21 기본에서 제외, JVM 전용 선택 |
| kotlinx.serialization | Kotlin 기반 | Kotlin 컴파일 플러그인 코드 생성 | Kotlin 선택 확장 |

**보안 관리:** 공개 CVE 숫자가 적다는 사실만으로 안전성을 보증하지 않는다. 고정 버전 + SCA + SBOM + 실제 공격 표면 분석을 함께 적용한다. 예전 Gson CVE-2022-25647은 2.8.9 이전 범위로, 선택한 2.14.0에 그대로 해당하지 않는다. 신규 취약점 발견 시 Android 21 호환 패치/포크/대체 Codec을 평가한다.

---

## 6. Multipart 및 파일 업로드

### 6.1 공개 사용 API

```java
app.multipart(config -> {
    config.maxFileSize(1024L * 1024 * 1024);     // 1 GiB 예시
    config.maxRequestSize(2L * 1024 * 1024 * 1024); // 2 GiB 예시
    config.memoryThreshold(64 * 1024);           // 64 KiB 예시
    config.maxParts(100);
    config.tempDirectory(new File("./temp"));
});

app.post("/api/upload", (req, res) -> {
    String title = req.form("title");
    UploadFile file = req.file("file");
    List<UploadFile> others = req.files("attachments");
    if (file == null) { res.status(400).json("Missing file"); return; }
    file.save(new File("/data/upload.bin"));
    res.json(Collections.singletonMap("success", true));
});
```

상기 파일 크기 값은 **설정 예시이지 기본값이 아니다**. Android 5.0에서 무제한 업로드를 기본 허용하지 않는다. 기본은 보수적인 크기로 시작하고 앱 요구에 따라 명시적으로 올린다(권장 초기 기본: 파일 32 MiB, 요청 64 MiB, part 20개, threshold 64 KiB; 제품별 조정 필요).

### 6.2 UploadFile/Part 규약

```java
public interface UploadFile extends AutoCloseable {
    String name();           // form 필드명
    String filename();       // 클라이언트 제공: 신뢰 불가
    String contentType();    // 클라이언트 제공: 신뢰 불가
    long size();
    InputStream inputStream() throws IOException;
    void save(File destination) throws IOException;
    void transferTo(OutputStream target) throws IOException;
    byte[] bytes() throws IOException;  // 명시적 작은 크기 한도 안에서만
}
```

- 텍스트 필드 `req.form`, 단일 `req.file`, 다중 `req.files`, 전체 `req.parts` 지원.
- 파일은 **메모리→임시 디스크 스풀**로 전환하며, 가능한 한 요청 전체를 메모리에 올리지 않는다. Multipart parser는 `Content-Length` 미지정/Chunked에서도 크기를 누적 검사한다.
- `filename()`을 저장 경로로 직접 결합하지 않는다. 서버 생성 이름·허용 확장자·내용 검증·디렉터리 샌드박스가 필요하다.
- Content-Type 및 확장자는 공격자가 위조 가능하다.
- Multipart boundary, 수많은 파트, 중첩 multipart, 줄바꿈/헤더 길이 제한, 업로드 중단, 임시 파일 삭제를 테스트한다.
- Android API 21 Core에서는 `File`, `InputStream`, `OutputStream`을 사용한다. `java.nio.file.Path/Files`는 공개 Core에서 요구하지 않는다. SAF는 `ContentResolver.openOutputStream(uri)`로 연결할 수 있도록 `transferTo()`를 둔다.
- parser가 생성한 임시 파일은 Request 종료 또는 성공적인 `save()` 후 규약에 따라 정리된다. 사용자가 입력 스트림을 명시적으로 보유한 경우에도 요청 완료 후 접근이 불가능할 수 있음을 문서화한다.

---

## 7. WebSocket — 동일 포트, 기본 기능

### 7.1 API

```java
app.ws("/ws", (req, ws) -> {
    UserService users = req.service(UserService.class);
    ws.onText(message -> ws.sendText("Echo: " + message));
    ws.onBinary(bytes -> ws.sendBinary(bytes));
    ws.onJson(User.class, user -> ws.sendJson(users.process(user)));
    ws.onClose((code, reason) -> app.logger().info("WS closed"));
    ws.onError(error -> app.logger().error("WS failed", error));
});
```

`onJson`은 텍스트 메시지를 JSON Codec으로 읽고 파싱 실패 시 연결 종료 정책 또는 메시지 오류 콜백으로 전달한다. 바이너리는 `byte[]` 기반 기본 제공. 대용량 바이너리 스트리밍은 추후 옵션이며 최대 메시지 크기 제한을 반드시 적용한다.

### 7.2 최소 Session 계약

```java
public interface WebSocketSession {
    String id();
    void sendText(String message);
    void sendBinary(byte[] message);
    void sendJson(Object value);
    void ping();
    void close();
    void close(int code, String reason);
    boolean isOpen();
    void onText(TextListener listener);
    void onBinary(BinaryListener listener);
    <T> void onJson(Class<T> type, JsonListener<T> listener);
    void onClose(CloseListener listener);
    void onError(ErrorListener listener);
    void attribute(String key, Object value);
    <T> T attribute(String key, Class<T> type);
}
```

Listener는 모두 Java 8 SAM 인터페이스이며 Kotlin에서 람다로 호출할 수 있어야 한다. 세션의 `attribute`는 요청 속성과 다른 **연결 단위 저장소**다.

### 7.3 프로토콜/보안/스레드

- RFC 6455: `Upgrade`, `Connection`, `Sec-WebSocket-Key`, 버전 13 검증, `Sec-WebSocket-Accept` 생성, 101 응답.
- 클라이언트 프레임 마스킹 검증, 조각난 프레임 재조립, UTF-8 텍스트 검증, 제어 프레임 125바이트 이하/비분할, Ping에 대한 Pong, close handshake.
- `Origin` **allowlist** 및 인증/쿠키 검증을 Upgrade 전에 적용한다. 브라우저 WebSocket은 CORS로 보호되지 않으므로 Origin 정책이 중요하다. 비브라우저 클라이언트는 Origin을 위조할 수 있으므로 Origin이 인증을 대체하지 않는다.
- `app.use("/ws", authentication)`은 HTTP Upgrade 이전에 실행된다. Upgrade 완료 뒤에는 일반 HTTP middleware가 프레임마다 자동 실행되지 않는다.
- `sendText`/`sendBinary`/`sendJson`는 다중 송신자 스레드에 대해 안전한 **연결별 순서 보장, bounded outbound queue**를 사용한다. backpressure 실패 시 명시 예외/세션 종료 정책을 적용한다.
- 제한: 최대 프레임/메시지 크기, 큐 크기, 연결 수, idle timeout, Ping 주기/응답 시간, handshake timeout.
- WebSocket `Close 1001 Going Away`는 서버 정상 종료 시 기본 코드로 사용한다.
- 액세스 로그는 **HTTP 101 핸드셰이크**와 **세션 종료 이벤트**를 분리한다.
- NanoWSD 참조 가능하지만 동시성·마스킹·플러딩·닫기 처리 보안감사 후 사용한다.

---

## 8. SSE (Server-Sent Events)

```java
app.get("/api/events", (req, res) -> {
    SseClient client = res.sse();
    client.send("connected", "ready");
    client.send(SseEvent.event("progress")
        .id("100").data("{\"progress\":50}").retry(3000));
    client.onClose(() -> app.logger().info("SSE closed"));
});
```

- `res.sse()` 호출 시 `Content-Type: text/event-stream; charset=UTF-8`, `Cache-Control: no-cache, no-transform`, 스트리밍/flush 모드로 전환한다.
- `event`, `data`, `id`, `retry`, 여러 줄 `data:` 및 주석 heartbeat(`: ping`)를 규격에 맞게 인코딩한다.
- `req.lastEventId()`로 `Last-Event-ID` 헤더를 읽어 애플리케이션 차원의 재전송 지원. 프레임워크가 자동 영속 이벤트 로그를 갖는 것은 아니다.
- 핸들러가 반환되어도 응답 세션은 활성 상태. bounded queue, heartbeat, 연결 종료 콜백, backpressure, 취소가 필수.
- 블로킹 OutputStream을 무제한 동시 전용 스레드에 묶는 설계를 피하고, Nano 어댑터에서 bounded 워커 전략을 검증한다. Servlet 어댑터는 `AsyncContext` 또는 비차단 쓰기를 사용한다.
- WebSocket과 별도의 프로토콜이며 SSE는 단방향(서버 → 클라이언트), 웹소켓은 양방향이다.

---

## 9. 정적 파일·MIME·파일 다운로드·HTTP 캐시

### 9.1 정적 디렉터리 마운트

```java
app.staticFiles("/", new File("./www"), options -> {
    options.index("index.html");
    options.directoryListing(false);
    options.spaFallback(true);
    options.cache("**/*.html", CachePolicy.noCache());
    options.cache("/assets/**", CachePolicy.publicFor(365, TimeUnit.DAYS).immutable());
    options.mime(".data", "application/x-data");
});

app.mimeTypes()
   .register(".mydata", "application/x-mydata")
   .register(".custom", "text/plain");
```

`app.staticFiles("/", new File("./www"))`만으로도 `/`→`index.html`, `/assets/app.js`→`www/assets/app.js` 매핑이 동작한다. `GET`/`HEAD` 지원. 디렉터리 목록은 기본 금지. SPA fallback은 실제 파일이 없고 **HTML 탐색 요청**일 때만 `index.html`로 전환하며 `/api/*`, `/ws/*`, 의도한 404와 충돌하지 않는다.

### 9.2 MIME Registry

**우선순위:** `StaticMount 별 custom MIME > app 전역 custom MIME > 내장 MIME 테이블 > application/octet-stream`.

| 확장자 | MIME |
|---|---|
| `.html`, `.htm` | `text/html` |
| `.css` | `text/css` |
| `.js`, `.mjs` | `text/javascript` |
| `.json`, `.map` | `application/json` |
| `.webmanifest` | `application/manifest+json` |
| `.wasm` | `application/wasm` |
| `.svg` | `image/svg+xml` |
| `.png` | `image/png` |
| `.jpg`, `.jpeg` | `image/jpeg` |
| `.webp` | `image/webp` |
| `.avif` | `image/avif` |
| `.woff`, `.woff2` | `font/woff`, `font/woff2` |
| `.mp4` | `video/mp4` |
| `.webm` | `video/webm` |
| `.pdf` | `application/pdf` |
| 알 수 없는 확장자 | `application/octet-stream` |

정적 파일에는 기본 `X-Content-Type-Options: nosniff` 설정. 텍스트 MIME에 적절한 charset을 부여한다. Content-Type은 실제 파일의 안전성을 보장하지 않는다. Android `MimeTypeMap`은 선택적 fallback 정도로만 사용한다.

### 9.3 파일 제공/보안/범위 요청

- 큰 파일은 `InputStream`/`RandomAccessFile`로 스트리밍한다. 전체 파일을 heap에 올리지 않는다.
- `Range: bytes=...`, `If-Range`, `Accept-Ranges`, 유효 단일 범위의 `206 Partial Content` + `Content-Range`, 범위 불충족 시 `416` + `Content-Range: bytes */length` 지원.
- 복수 Range는 초기 구현에서 지원하지 않을 수 있다. 이 경우 규격상 합법적으로 `Range`를 **무시하고 200 전체 응답**하는 정책을 택하며 유효한 요청에 잘못된 `416`을 반환하지 않는다.
- 캐시 조건부 요청은 `If-None-Match` 우선, `If-Modified-Since`, `ETag`, `Last-Modified` 등을 처리한다. `304`는 body 없음. **약한 ETag(`W/`)는 강한 콘텐츠 동일성 검증으로 오용하지 않는다**. `If-Range`에 ETag를 적용하는 경우 강한 Validator만 인정하거나 Last-Modified 기반 검증의 적용 조건을 명확히 한다.
- 정규화된 경로가 루트 밖으로 나갈 수 없다. `%2e`, `%2f`, 이중 인코딩, `..`, `NUL`, 정규화 이슈, symlink/TOCTOU를 테스트한다. symlink는 기본 deny로 지정한다.
- 정적 파일에서 dotfile/숨김 파일, 내부 DB, 키, 설정 파일 접근은 기본 차단하고 명시적으로 허용된 루트만 노출한다.
- 대용량 영상, NAS 다운로드 특성상 Range와 Cache의 조합 테스트가 필수다.
- SAF/`content://` 등 Android 파일 공급자를 장기적으로 수용하려면 `ResourceResolver`(`open`, `length`, `etag`, `modified`) SPI를 두며, 초기 내장 File 리졸버와 별도 구현을 구분한다.

### 9.4 캐시 API

```java
res.cache(CachePolicy.noStore()).json(privateData);
res.cache(CachePolicy.noCache()).json(data);
res.cache(CachePolicy.privateFor(10, TimeUnit.MINUTES)).json(data);
res.cache(CachePolicy.publicFor(1, TimeUnit.HOURS)).json(data);
```

| 캐시 API | 표준 헤더 의미 |
|---|---|
| `noStore()` | `Cache-Control: no-store` (저장 금지) |
| `noCache()` | `Cache-Control: no-cache` (재사용 전 재검증) |
| `privateFor(10, MINUTES)` | `private, max-age=600` |
| `publicFor(1, HOURS)` | `public, max-age=3600` |
| `.immutable()` | `immutable` 추가. **콘텐츠 해시 파일에만** 사용 권장 |

기본 예시 정책: 인증된 REST `no-store`, HTML `no-cache + ETag`, 해시된 JS/CSS `public, max-age=31536000, immutable`, SSE `no-cache, no-transform`, 개인/인증 파일 `private` 또는 `no-store`. 캐시 기본값은 서버 전역 보안 정책으로 설정 가능하다. 사용자 신원에 따라 달라지는 리소스에 `public`을 부주의하게 적용하지 않는다.

---

## 10. Cookie · Middleware · 에러 핸들링 · 로깅

### 10.1 Servlet 유사 Cookie 객체

```java
Cookie cookie = new Cookie("session", "token123");
cookie.setHttpOnly(true);
cookie.setSecure(true);
cookie.setPath("/");
cookie.setMaxAge(3600);
cookie.setSameSite(SameSite.LAX);
res.addCookie(cookie);

Cookie fromRequest = req.cookie("session");
Cookie[] allCookies = req.getCookies();

// 삭제: 생성 때와 동일한 Path/Domain을 지정
Cookie expired = new Cookie("session", "");
expired.setPath("/");
expired.setMaxAge(0);
res.addCookie(expired);
```

- Core 자체 `Cookie` 타입 사용; Servlet `javax.servlet.http.Cookie`에 의존하지 않는다.
- 기본 지원: name/value, path, domain, maxAge, expires(선택), Secure, HttpOnly, SameSite(`STRICT`, `LAX`, `NONE`). `SameSite=None`과 Secure의 브라우저 정책 고려.
- `Cookie` 요청 헤더에는 `HttpOnly`/`Secure`/`SameSite` 속성이 전송되지 않는다. **req.getCookies() 값에서 그러한 플래그를 신뢰하지 않는다**.
- 쿠키 이름·값에 CR/LF 및 헤더 인젝션 문자를 차단, 유효한 Set-Cookie 직렬화를 수행.

### 10.2 app.use Middleware

```java
app.use((req, res, next) -> {
    req.attribute("requestId", UUID.randomUUID().toString());
    next.run();
});

app.use("/api", (req, res, next) -> {
    if (!auth.isAuthorized(req)) {
        res.status(401).json("Unauthorized");
        return;
    }
    next.run();
});

app.get("/api/admin", adminOnly, (req, res) -> res.json(admin.status()));

app.onError((error, req, res) -> {
    if (!res.isCommitted()) res.status(500).json("Internal Server Error");
    else res.abort(error);
});
```

```java
@FunctionalInterface
public interface Middleware {
    void handle(Request req, Response res, Next next) throws Exception;
}
public interface Next {
    void run();
    void error(Throwable error);
}
```

- `app.use(global)`, `app.use("/prefix", middleware)`, 개별 route middleware는 모두 지원.
- **등록 순서 보장**; Global/Prefix 미들웨어를 Route 앞에서 실행하고 Route-specific 미들웨어를 이어서 실행한다. 같은 종류끼리는 등록 순서를 따른다. `app.use`는 시작 이전 설정으로 제한한다.
- `next.run()`은 한번만 호출 가능. 중복 호출, 이미 응답 완료, 종료 후 호출 시 명시적 오류 처리.
- `next.run()`이 없고 아직 응답하지 않은 경우는 요청 타임아웃까지 방치하지 않도록 developer warning/guard를 둔다.
- 첫 버전은 **동기 미들웨어 체인**. 비동기 미들웨어는 `AsyncNext`/response lifecycle 설계 이후 선택적 지원. `next.run()` 후단 코드를 실행하는 before/after 패턴은 동기 경로에서 가능하지만 SSE/WS 완료를 의미하지 않는다.
- 라우트별 `adminOnly` 지원을 위해 `get(String, Middleware, RouteHandler)` 등의 시그니처 또는 `RouteBuilder.use(middleware)` 제공.
- 예외는 공통 onError 체인으로 전달. 이미 commit 된 응답에서는 500 JSON을 덧쓰기하지 않는다.

### 10.3 Access Logging과 App Logging

```java
app.accessLog(options -> {
    options.enabled(true);
    options.level(LogLevel.INFO);
    options.includeRequestId(true);
    options.includeDuration(true);
    options.includeBytes(true);
    options.exclude("/health");
    options.exclude("/favicon.ico");
});
app.logger(customLogger);
app.logger().info("Server initialized");
```

| 로거 | 로그 대상 | 타이밍 |
|---|---|---|
| `AccessLog` | Method, Path, status, latency, 송수신 바이트, requestId | HTTP 응답 완료/Abort 시 |
| `WebSocketAccessLog` | 101 handshake, close code, duration | 연결 수락/종료 시 각각 |
| `SseAccessLog` | SSE 시작, 최종 종료/에러 | 응답 완료 시 |
| `Logger` | app 및 framework 이벤트 | 호출 시 |

```java
public interface Logger {
    void trace(String m); void debug(String m); void info(String m);
    void warn(String m); void error(String m); void error(String m, Throwable t);
}
```

- Android는 Logcat Adapter, 일반 JVM은 Console, 옵션으로 SLF4J Adapter/Rotating File Logger를 제공한다. Core는 SLF4J 필수 의존성을 갖지 않는다.
- 파일 로그 예시: `directory ./logs`, `maxFileSize 5 MiB`, `maxFiles 5`. 실패해도 HTTP 서비스가 중단되어서는 안 된다. 로그 큐의 bounded/backpressure/drop 카운터를 제공한다.
- 기본적으로 Authorization/Cookie/비밀번호/토큰/요청·응답 본문/Multipart 원문/전체 query string을 Access Log에 기록하지 않는다. 개행 등을 이스케이프하여 로그 인젝션을 막는다.
- 시간 형식, 레벨, requestId, 바이트의 정의(전송 실측 기준인지 등)와 종료 중 생략 로그를 일관되게 정의한다.

---

## 11. ApplicationContext · ServiceRegistry · Lifecycle — 필수

### 11.1 목적

ServletContext의 Application Scope처럼 **시작 시 한 번 생성한 서비스 객체를 공유**하고, 각 HTTP 요청·WebSocket Upgrade/세션·SSE 핸들러에서 동일 객체를 `req.service(Class<T>)`로 조회한다. Spring DI/자동 주입이 아닌 명시적 초기화/조회 방식이다.

```java
RestApp app = RestApp.create();

app.onStart(ctx -> {
    DatabaseService db = new DatabaseService();
    ctx.own(db);                // 초기화 도중 실패하더라도 정리 대상으로 등록
    db.connect();

    UserService users = new UserService(db);
    FileService files = new FileService(db);

    ctx.register(DatabaseService.class, db);
    ctx.register(UserService.class, users);
    ctx.register(FileService.class, files);
    ctx.attribute("appVersion", "1.0.0");
    ctx.logger().info("Services initialized");
});

app.get("/api/users/:id", (req, res) -> {
    UserService users = req.service(UserService.class);
    res.json(users.findById(req.param("id")));
});

app.ws("/ws", (req, ws) -> {
    UserService users = req.service(UserService.class);
    ws.onText(message -> ws.sendJson(users.findById(message)));
});

app.onStop(ctx -> ctx.logger().info("Server stopping"));
ServerHandle server = app.listen(8080);
// Android에서는 worker thread에서: app.shutdown(10, TimeUnit.SECONDS);
```

### 11.2 ServiceRegistry API / 저장소 범위

```java
public interface ServiceRegistry {
    <T> void register(Class<T> type, T instance);
    <T> T get(Class<T> type);        // 없으면 ServiceNotFoundException
    <T> T getOrNull(Class<T> type);
    boolean contains(Class<?> type);
}

public interface ApplicationContext extends ServiceRegistry {
    void attribute(String key, Object value);
    <T> T attribute(String key, Class<T> type);
    void own(AutoCloseable resource);   // 중복 소유시 1회 닫기
    Logger logger();
}
```

| 범위 | 자료 | 생성/제거 시점 | 스레드 안전성 |
|---|---|---|---|
| App/ServiceRegistry | `Class<T> → singleton` | STARTING→TERMINATED | 준비 완료 후 immutable, 동시 읽기 안전 |
| Application attribute | 문자열→객체 | App 생명주기 | ConcurrentMap 또는 동등한 동기화 |
| Request attribute | 문자열→객체 | 요청 수명 | 요청별 분리, 다중 쓰기 시 동기화 |
| WebSocket session attribute | 문자열→객체 | 연결 수명 | ConcurrentMap |
| SSE session state | 송신 큐·close listener | SSE 연결 수명 | 안전한 상태 관리 |

- `ctx.register`는 **STARTING 상태의 onStart 단계에서만** 허용. 서버 RUNNING 전 immutable snapshot으로 고정.
- 동일 `Class`에 중복 등록 시 기본 **설정 오류**(덮어쓰기 금지). 인터페이스와 구현 클래스를 별도 키로 등록한 동일 객체는 해제 시 **identity 기준으로 한 번만** close.
- 서비스는 앱 전체에서 공유되므로 **서비스 내부 mutable state의 thread-safety는 개발자 책임**이다.
- App Context는 동일 `RestApp` 안에서 단 하나이며, 복수 앱 인스턴스 간 전역 static 싱글턴이 아니다.
- 타입만으로 구분이 안 되는 두 서비스가 필요한 경우 `ServiceKey<T>` 또는 명시 별칭 기능은 후속 확장으로 검토한다.

### 11.3 상태 기계

```text
NEW --listen/start--> STARTING --onStart/bind/commit--> RUNNING
 |                       |                                   |
 |                       +--실패--> FAILED ----cleanup------> TERMINATED
 |                                                           |
 +--shutdown----------------------> TERMINATED               |
                                                             v
                                              STOPPING --> TERMINATED
```

**불변식**: `RUNNING` 전 요청 서비스 금지, `STOPPING` 이후 새 요청·Upgrade 거부, `onStart` 실패 시 공개 포트가 남아 있지 않음, `onStop`은 정상적으로 RUNNING에 도달한 앱의 정상 종료에서 한 번만 실행. 시작 실패 시에는 **오류/cleanup 콜백 또는 등록 리소스 정리**를 실행한다. 서비스 정리는 등록/소유 **역순**으로 수행한다.

### 11.4 Start 및 Stop 순서

**시작:**

1. `NEW`에서만 설정·라우트·미들웨어 추가 허용.
2. `listen(port)` → `STARTING` (동시 시작 금지).
3. onStart 콜백 등록 순서대로 실행: 서비스 객체 생성 → `ctx.own` → 초기화 → `ctx.register`.
4. Router/Registry/옵션 스냅샷 고정 및 정합성 검사.
5. HTTP 소켓 bind 및 accept 준비, 성공 시 `RUNNING` 전환, 그 다음 연결 수락.
6. 실패하면 더는 요청받지 않고 이미 보유한 리소스를 역순 정리, 포트 close, `FAILED` 처리 후 `TERMINATED`로 종료 경로 진행. 호출자에게 원인 exception 전달.

**종료 (`app.shutdown(10, SECONDS)`):**

1. 호출이 처음이면 `STOPPING`으로 원자 전환. 반복 호출은 안전(idempotent).
2. 신규 연결/HTTP 요청/WS Upgrade 수락 중지. keep-alive의 추가 요청도 정책대로 차단.
3. 진행 중인 짧은 HTTP 요청 완료 대기. SSE/WS에는 종료 통지를 보내고 별도 시간 제한으로 종료.
4. 시간 제한 초과 시 진행 요청/연결을 취소하고 소켓 강제 종료.
5. `onStop(ctx)`는 **등록된 서비스가 살아 있는 동안** 한 번 호출하여 사용자 지정 정리 수행.
6. 프레임워크가 소유한 `AutoCloseable`을 생성·등록 역순으로 닫는다. close 예외가 나도 계속 정리하고 오류들을 수집한다.
7. 로그 writer flush/close, Adapter shutdown, `TERMINATED`.

**선택 정책:** 외부 생성 객체를 등록할 때는 자동 close하지 않는 `registerExternal` 또는 `ctx.register(..., Ownership.EXTERNAL)`를 두어 실수로 외부 서비스 풀을 종료하지 않는다. `ctx.own`은 초기화 실패 시 누수를 방지한다.

### 11.5 Android Lifecycle

- Android `Service`에서 `RestApp`을 만들고 관리하되 `onCreate`/`onDestroy`의 네트워크 기동·종료는 작업 스레드로 넘긴다. Android UI/Main Thread에서 서버 IO나 10초 graceful shutdown 대기 금지.
- Android 프로세스 강제 종료에는 `onDestroy`가 항상 실행되지 않는다. 반드시 flush해야 할 데이터는 요청 처리 경로에서 영속 저장.
- Android API/target SDK/Foreground Service 및 백그라운드 실행 정책은 Android 앱 계층에서 별도로 적합성 검토. 이 프레임워크가 상시 실행을 보장한다고 설명하지 않는다.
- `stopSelf`, 앱 재실행 이후에는 **새 RestApp 인스턴스**를 생성한다. 종료된 기존 객체 재시작은 v1에서 지원하지 않는다.

---

## 12. Kotlin 호환성 및 선택 확장

- 모든 Callback을 Java `@FunctionalInterface`로 공개하여 Kotlin SAM 변환을 지원한다.
- Kotlin 사용자가 별도 DSL을 설치하지 않아도 `app.route(...) { req, res -> ... }`를 사용할 수 있어야 한다.
- Nullable 반환(`req.file`)은 Java의 `@Nullable` / `@NonNull` 어노테이션 정책을 정하고 Kotlin 컴파일 테스트를 수행한다.
- Java 표준 `List/Map`, `File`, `InputStream/OutputStream`, `TimeUnit` 사용. Android API 21에서 없는 `CompletableFuture`, `java.net.http`, `java.nio.file`를 Core 공개 타입에 노출하지 않는다.

```kotlin
val app = RestApp.create()
app.route(Method.get().post(), "/api/users") { req, res ->
    res.json(req.service(UserService::class.java).findAll())
}
app.ws("/api/ws") { req, ws ->
    ws.onText { message -> ws.sendText("Echo: $message") }
}
app.post("/api/upload") { req, res ->
    req.file("file")?.save(File("/data/upload.bin"))
    res.json(mapOf("success" to true))
}
app.staticFiles("/", File("./www"))
app.listen(8080)
```

`rest-kotlin` 선택 확장:

```kotlin
inline fun <reified T : Any> Request.body(): T = body(T::class.java)
```

주의: 위 확장만으로 `List<User>` 등 제네릭 정보를 보존하지 않는다. 제네릭은 별도 reified TypeRef 생성 기능을 구현해야 한다. Coroutine/suspend 지원은 async response/취소 수명주기를 확정한 뒤 별도 모듈로 제공한다. **`runBlocking`을 기본 비동기 어댑터로 사용하지 않는다.**

---

## 13. Java 8 / Android 5.0 ABI·빌드 규칙

| 영역 | 기본 전략 | 이유 |
|---|---|---|
| Lambda/Method reference | Java 8 source/target + Android desugaring | API 21 가능 |
| `java.io.File`/Stream | Core 사용 | API 21 호환 |
| `java.util.concurrent` 기본 기능 | Core 사용 | 작업 큐·종료 |
| `java.nio.file.Path`/`Files` | Core public API 금지 | API 21에 없음 |
| `CompletableFuture` | Core 사용 금지 | API 21 기본 없음 |
| `java.net.http.HttpClient` | 금지 | Java 11 API |
| `javax.servlet.*`/`jakarta.servlet.*` | Servlet Adapter에만 | Android 런타임 미탑재 |
| `android.*` | Android Adapter에만 | 순수 JVM 호환 |
| Kotlin stdlib | Core에 강제하지 않음 | Java 소비자 경량성 |
| JSON | Gson 2.14.0 | Android 21 공식 지원 |
| 스레드 | 제한된 Executor/큐 | 저사양 구형 폰 메모리 보호 |

Maven/Gradle bytecode target Java 8. Android 샘플 앱은 `minSdk 21`이며 R8 minify on/off, Debug/Release 모두 빌드/실행 확인. Desugaring이 지원하는 API와 미지원 API를 구분하고 필요한 경우 최소 Gradle/AGP 조합을 CI 문서에 고정한다. Android의 `coreLibraryDesugaring`을 사용하게 되더라도 **일반 Core 설계가 그것에 의존하지 않아야** 한다.

---

## 14. 스레드 모델·자원 제한·에러 정책

### 14.1 네트워크 및 요청 처리

- HTTP accept와 요청 처리 워커를 분리하고 bounded thread pool, bounded queue로 제어. Worker per connection은 구형 Android에서 위험하므로 장기 연결 별 별도 자원 예산을 둔다.
- 단일 `RestApp` 인스턴스는 concurrent 요청이 가능. HTTP 요청 객체는 다른 요청과 격리. `Request`/`Response` 객체를 완료 뒤 사용하는 것은 금지.
- 소켓 읽기/쓰기 타임아웃, 최대 Header 크기, Header 개수, URL 길이, 본문 길이, 대기 큐, 최대 동시 SSE/WS 연결 수를 설정할 수 있어야 한다.
- 정상 서버 종료·클라이언트 단절 시 업로드 임시 파일, SSE·WS 큐, OutputStream 모두 회수한다.
- 전역 인증 없이 LAN 바인딩하는 경우 보안 경고를 기록한다. 프레임워크는 앱 인증 정책 자체를 강제 구현하지 않지만 안전한 middleware hooking 제공.

### 14.2 설정 초깃값의 성격

다음 값은 **초안 기본값**이며 제품의 부하 시험으로 조정한다. '무제한'으로 기본 설정하지 않는다.

| 항목 | 초기 기본값(초안) | 변경 가능 |
|---|---:|---|
| JSON Request | 1 MiB | 예 |
| JSON depth | 64 | 예 |
| Multipart file | 32 MiB | 예 |
| Multipart request | 64 MiB | 예 |
| Multipart parts | 20 | 예 |
| Multipart memory threshold | 64 KiB | 예 |
| HTTP header total | 16 KiB | 예 |
| Request URL length | 8 KiB | 예 |
| WebSocket text/binary message | 1 MiB | 예 |
| WS outbound queue/session | 1 MiB | 예 |
| SSE outbound queue/session | 256 KiB | 예 |
| Stop timeout | 10 s (예시 API) | 예 |
| Directory listing | OFF | 예(명시적 opt-in) |
| LAN binding | OFF(localhost only) | 예 |
| Access log sensitive values | OFF | 금지 또는 극히 제한 |

숫자는 요구사항에서 명시된 확정 운영치가 아니므로 구성 클래스에 기본값으로 구현한 다음 Android 5 실기기에서 조정한다.

### 14.3 HTTP 보안

- RFC 9110/9112에 맞는 메시지 framing, Content-Length/Transfer-Encoding 충돌(HTTP Request Smuggling), 헤더/응답 분할 공격, oversize/slowloris 방어.
- 정적 경로 path traversal 및 symlink 이탈 방지, 파일 업로드 파일명 신뢰 금지, 업로드 제한.
- WebSocket Origin allowlist + 인증, CSRF 고려가 필요한 쿠키 기반 API는 애플리케이션 미들웨어가 CSRF 정책을 제공하도록 한다.
- `X-Content-Type-Options: nosniff` 기본, Cache-Control 민감 데이터 보호.
- TLS/WSS는 Adapter별 지원 검토. Android 5의 구형 TLS 프로바이더·인증서 이슈를 별도로 시험한다. 인터넷 노출에서 평문 서버를 안전하다고 간주하지 않는다.
- 보안 예외 발생 시 로그에 토큰·비밀번호·세션ID·파일 내용 등을 기록하지 않는다.

---

## 15. 주요 구성 인터페이스와 권장 API 목록

```java
// Http routes
app.get(path, handler);
app.post(path, handler);
app.put(path, handler);
app.patch(path, handler);
app.delete(path, handler);
app.route(Method.GET, path, handler);
app.route(Method.get().delete().post(), path, handler);
app.route(Method.any().except(Method.DELETE), path, handler);
app.route(path).get(getHandler).delete(deleteHandler);

// Middleware and errors
app.use(middleware);
app.use("/api", middleware);
app.get("/api/admin", adminOnly, handler);
app.onError((error, req, res) -> { /* ... */ });

// JSON and bodies
app.jsonCodec(codec);
req.body(User.class);
req.body(new TypeRef<List<User>>() {});
res.json(object);
res.send("plain");
res.status(204).end();

// Multipart
app.multipart(config -> { /* ... */ });
req.form("title");
req.file("file");
req.files("attachments");
req.parts();
file.save(new File("/data/upload.bin"));
file.transferTo(outputStream);

// WebSocket and SSE
app.ws("/ws", (req, ws) -> { /* ... */ });
ws.sendText(text);
ws.sendBinary(bytes);
ws.sendJson(dto);
ws.ping();
ws.close(1001, "server shutdown");
SseClient client = res.sse();
client.send("event", "data");
req.lastEventId();

// Static, MIME, cache
app.staticFiles("/", new File("./www"));
app.staticFiles("/", new File("./www"), options -> { /* ... */ });
app.mimeTypes().register(".custom", "application/x-custom");
res.cache(CachePolicy.noStore()).json(dto);
res.download(file, "export.bin");

// Cookies
req.cookie("session");
req.getCookies();
res.addCookie(new Cookie("session", "value"));

// Logging
app.accessLog(options -> { /* ... */ });
app.logger(logger);
app.logger().info("ready");

// Lifecycle/services
app.onStart(ctx -> { ctx.register(UserService.class, users); });
app.onStop(ctx -> { /* ... */ });
req.service(UserService.class);
req.application().attribute("appVersion", String.class);
req.attribute("requestId", requestId);
ServerHandle server = app.listen(8080);
app.shutdown(10, TimeUnit.SECONDS);
app.state();
```

**표기 충돌 방지:** 문서에서 `Cache.noCache()`와 `CachePolicy.noCache()`가 혼용됐던 이전 예시는 공개 API를 **`CachePolicy`로 통일**한다. 구현 시 다른 별칭을 제공하는 것은 선택 사항이다. `File.save(Path)`는 API 21 제약으로 Core에서 `save(File)`로 변경한다. `app.serve`는 `app.route`로 변경한다. `res.json(...)` 결과 처리 중 동기 예외가 발생하면 응답을 완료하지 않은 경우에만 오류 매핑을 허용한다.

---

## 16. 시나리오별 실행 추적

### 16.1 HTTP API + Application Singleton

```text
Client GET /api/users/1
  -> Accept, limits, normalized path
  -> Global Middleware(requestId)
  -> /api Middleware(auth)
  -> Router [GET /api/users/:id]
  -> req.service(UserService.class) == ctx.register()의 동일 객체
  -> res.json(user): JsonCodec.encode
  -> Response commit/finish
  -> AccessLog(final status, latency, response size)
```

### 16.2 Multipart

```text
POST /api/upload
  -> Body size / header limits
  -> multipart boundary parsing (stream spool)
  -> req.form + req.file
  -> FileService.save(input stream/File)
  -> 201 JSON response
  -> Req resources + temp files cleanup
```

### 16.3 WebSocket

```text
GET /ws + Upgrade:websocket
  -> common auth middleware + Origin check
  -> RFC6455 handshake 101
  -> WebSocketSession CREATED/OPEN
  -> onText/onJson events use ApplicationContext services
  -> bounded send queue
  -> close callback + session attributes cleanup
  -> WS close access log
```

### 16.4 SSE

```text
GET /api/events
  -> auth middleware + req.lastEventId()
  -> res.sse() [text/event-stream; no-cache]
  -> EventSource session active AFTER handler returns
  -> bounded queue/heartbeats/flush
  -> onClose / shutdown cancellation
  -> final access log
```

### 16.5 정적/캐시/Range

```text
GET /assets/app.hash.js [If-None-Match, Range]
  -> routing: explicit routes miss -> StaticMount resolver
  -> canonical path under root, MIME registry
  -> If-None-Match first -> optional 304
  -> else Range/If-Range -> 206 or full 200
  -> ETag/Cache-Control/nosniff
  -> stream + cleanup
```

---

## 17. 검증 및 인수 테스트 계획

### 17.1 플랫폼 매트릭스

| 대상 | 필수 조건 |
|---|---|
| JVM Java 8 (Linux/Windows/macOS 지원 가능 환경) | 빌드/REST/JSON/WS/SSE/Multipart/Static/Stop |
| Android 5.0 API 21 (실기기 또는 공식 에뮬레이터) | **필수** 디버그/릴리스 + minify on/off |
| 최신 Android API (지원 대상 최신) | 동일 API Regression |
| Kotlin Android 프로젝트 | SAM·nullable·Multipart·WebSocket·서비스 API 컴파일/실행 |
| Servlet 지원 WAS (선택 모듈) | HTTP/multipart/async/SSE/WS 연결 호환 여부 별도 표시 |

### 17.2 기능 및 보안 테스트 케이스

아래 표의 ID는 인수 기준이며, **실행 결과가 아니라 구현 시 반드시 수행할 테스트 목록**이다.

| ID | 테스트 및 기대 결과 |
|---|---|
| T01 | GET+DELETE+POST가 같은 path에 등록되어 메서드별 공통 handler에 전달 |
| T02 | `Method.any().except(Method.DELETE)`에서 DELETE=405/Allow 정상 |
| T03 | 개별 `.route(path).get().delete()` 동작, 중복 등록 시 config 오류 |
| T04 | `/users/me`가 `/users/:id`보다 먼저 매칭 |
| T05 | GET→HEAD fallback은 body 없음, OPTIONS Allow 포함 |
| T06 | 없는 path=404, method 없음=405, unknown method=501 정책 |
| T07 | `req.param/query/header` 중복·UTF-8·오염 입력 처리 |
| T08 | POJO·List<User>·Map·문자열·null JSON 왕복 정확 |
| T09 | 잘못된 JSON=400, 다른 미디어 타입=415, 깊이/크기 초과 차단 |
| T10 | Gson 2.14.0 API21 실기기에서 NoClassDefFoundError 없이 실행 |
| T11 | Kotlin Java SAM lambda 컴파일, Kotlin data class Gson 한계 문서화 |
| T12 | R8 최적화 on, @SerializedName 모델 직렬화 일치 |
| T13 | Multipart 단일/다중 파일 및 텍스트 필드 혼합 처리 |
| T14 | 임계 크기 초과 시 디스크 스풀, 서버 heap 증가 제한 |
| T15 | 파일/요청/part 수 초과 시 413 및 임시 파일 정리 |
| T16 | filename `../../etc/passwd` 경로 이탈 불가 |
| T17 | 클라이언트 중도 종료·네트워크 실패 시 upload 임시파일 누수 없음 |
| T18 | `transferTo(OutputStream)` SAF OutputStream에 안전 복사 |
| T19 | WS upgrade handshake RFC6455 성공/거부 케이스 |
| T20 | WS 잘못 마스킹·bad UTF8·oversize·fragmented frame 차단 |
| T21 | WS Ping/Pong, close(1001), session attr 및 서비스 singleton 사용 |
| T22 | WS Origin 불허/인증 실패 시 Upgrade 이전 거부 |
| T23 | WS 느린 클라이언트 queue 초과 처리, 세션 누수 없음 |
| T24 | SSE event/id/retry/multiline data/Last-Event-ID 동작 |
| T25 | SSE handler 반환 후에도 연결 유지, heartbeat/close 정상 |
| T26 | WS+SSE 100개 이상 부하 시 스레드/메모리 증가 제한 준수(장치별 목표 조정) |
| T27 | `staticFiles` `/`/index/asset, 명시 route 우선, SPA fallback 충돌 없음 |
| T28 | global/custom/mount MIME 우선순위, 미지정 octet-stream |
| T29 | 숨김파일·symlink·double decode·encoded traversal 차단 |
| T30 | GET/HEAD static, ETag/Last-Modified/304 규격 준수 |
| T31 | Range/If-Range 206/416/200 처리, 비지원 multi-range 안전 처리 |
| T32 | HTML no-cache vs hashed assets immutable 정확; `no-store` 저장 금지 지시 |
| T33 | Cookie 생성·삭제 Path/Domain/SameSite/Secure/HttpOnly 직렬화 |
| T34 | `req.getCookies()`가 수신 불가능한 response-only flag를 임의 주장하지 않음 |
| T35 | Middleware 등록 순서, global/prefix/route scope, next 1회 보장 |
| T36 | Middleware 중단 후 응답 완료, throw/next.error 통합 오류 처리 |
| T37 | 미들웨어 응답 commit 후 exception 발생 시 중복 body 금지 |
| T38 | AccessLog가 일반 HTTP finish, SSE 종료, WS 101/close 구분 |
| T39 | Authorization/Cookie/query/body/파일 데이터가 로그에 노출되지 않음 |
| T40 | Logcat/Console/SLF4J/rotating file Adapter 의존성 격리 |
| T41 | onStart가 각 서비스를 1회 생성, req/WS/SSE가 같은 객체 참조 |
| T42 | 서비스 Registry RUNNING 후 등록 금지, 타입 충돌 오류 |
| T43 | 시작 실패 시 소켓 미노출·초기화된 리소스 역순 정리 |
| T44 | 종료 신규 요청 차단, 진행 요청 완료 대기, WS/SSE 종료, close 역순 |
| T45 | onStop에서 서비스 조회 가능, AutoCloseable close 정확히 1회 |
| T46 | shutdown 중복 호출·종료된 앱 재시작 정책 일치 |
| T47 | Android Service 메인스레드 IO 금지 및 강제 종료 경계 처리 |
| T48 | 헤더/URL/body slowloris, Content-Length/Transfer-Encoding 불일치 거부 |
| T49 | 0.0.0.0 명시 바인딩만 LAN 노출, 보안 경고 확인 |
| T50 | SBOM/의존성 취약점 점검, 비GPL 라이선스 검증 |

**비기능 평가 항목:** 성능·메모리·배터리 소비량은 하드웨어/네트워크/작업 부하에 따라 달라지므로 사전 수치 보증을 하지 않는다. 테스트 대상으로 Android API 21 저사양 기기, 10/100개의 동시 요청·WS/SSE 연결, 1 GiB 업로드(명시 설정), 네트워크 유실, 서버 종료, 반복 시작/종료를 포함한다.

---

## 18. 요구사항 추적 매트릭스 — 대화 내용 누락 방지

**출처 분류:** `U`는 사용자 직접 확정, `A`는 이전 설계 답변에서 제안하고 이후 대화에서 유지, `O`는 선택/후속 과제. 요구사항 ID는 문서 전체에서 고정한다.

| ID | 요구사항/대화 결정 | 출처 | 설계 위치 | 우선순위 |
|---|---|---|---|---|
| R-001 | Java Servlet처럼 REST API를 간결하게 구현, Express.js 패턴 | U | §1, §3 | 필수 |
| R-002 | Java 8 및 독립 JVM/임베드 지원 | A/U | §1, §13 | 필수 |
| R-003 | 동일 URL에 GET/DELETE 등 여러 HTTP Method | U | §3 | 필수 |
| R-004 | `app.route(Method.get().delete().post(),...)` | U | §3 | 필수 |
| R-005 | `Method.any().except(Method.DELETE)` | U | §3 | 필수 |
| R-006 | `serve` 대신 `route`로 이름 변경 | U | §3 | 필수 |
| R-007 | 개별 `app.get/post/delete` 및 Route Chaining | A | §3 | 필수 |
| R-008 | HEAD·OPTIONS·404/405·Allow·정적 vs 동적 우선순위 | A | §3 | 필수 |
| R-009 | JSON ↔ Java class 자동 변환 / Express 간편성 | U | §4, §5 | 필수 |
| R-010 | Android 5.0(API 21)이 최소 호환 | U | §1, §13 | 필수 |
| R-011 | Java/Kotlin 공용 코드, Java SAM 통한 Kotlin 람다 | U/A | §12 | 필수 |
| R-012 | Gson 2.14.0 기본 JSON, CVE 부담 낮게 | U/A | §5 | 필수 |
| R-013 | Jackson/Moshi/kotlinx 선택지, JsonCodec 교체 | A | §5 | 필수/선택 |
| R-014 | Multipart 파일/폼 기본 지원 | U | §6 | 필수 |
| R-015 | 단일/복수 파일, 스풀/파일 크기 제한, 스트리밍 | A | §6 | 필수 |
| R-016 | SSE 기본 지원 | U | §8 | 필수 |
| R-017 | Event/id/retry/Last-Event-ID/heartbeat/backpressure | A | §8 | 필수 |
| R-018 | Servlet처럼 Cookie 객체/읽기·쓰기·삭제 | U | §10 | 필수 |
| R-019 | SameSite, Secure, HttpOnly, Path, Domain, MaxAge | A | §10 | 필수 |
| R-020 | WebSocket 기본 지원 | U | §7 | 필수 |
| R-021 | WS text/binary/JSON/Ping/Pong/close/auth | A | §7 | 필수 |
| R-022 | 디렉터리 입력으로 정적 리소스 서빙 | U | §9 | 필수 |
| R-023 | 기본 MIME + 사용자 정의 MIME 타입 | U | §9 | 필수 |
| R-024 | SPA fallback/index/static MIME/보안 | A | §9 | 필수 |
| R-025 | HTTP 캐시 설정 쉬워야 함 | U | §9 | 필수 |
| R-026 | no-store/no-cache/private/public/immutable | A | §9 | 필수 |
| R-027 | ETag/Last-Modified/304, Range/206 다운로드 | A | §9 | 필수 |
| R-028 | `app.use` 미들웨어, prefix/route scope | U/A | §10 | 필수 |
| R-029 | 공통 오류 핸들러 | A | §10 | 필수 |
| R-030 | Access Log + App Logger / 교체 가능한 백엔드 | U/A | §10 | 필수 |
| R-031 | Android Logcat/JVM Console/File/SLF4J | A | §10 | 필수/선택 |
| R-032 | 시작과 셧다운 수명주기 | U | §11 | 필수 |
| R-033 | `onStart`에서 서비스 생성 후 공용 저장소 등록 | U | §11 | 필수 |
| R-034 | `req`로부터 서비스 인스턴스 조회 | U | §11 | 필수 |
| R-035 | DI는 불필요, ServiceRegistry/ApplicationContext | U/A | §11 | 필수 |
| R-036 | Application attribute와 Request attribute 구분 | A | §11 | 필수 |
| R-037 | 종료 이벤트, graceful shutdown, 자원 자동 close | U/A | §11 | 필수 |
| R-038 | NanoHTTPD 포크의 Android/JVM HTTP Adapter | A | §2, §13 | 필수 후보 |
| R-039 | Servlet Adapter 별도 구현, Core 비의존 | A | §2 | 선택 모듈 |
| R-040 | Android `File`/Stream 기본, Path/Files 비의존 | A | §6, §13 | 필수 |
| R-041 | Kotlin 편의 DSL 및 Coroutine 선택 확장 | A | §12 | 선택 |
| R-042 | JavaScript V8 기반 Express 바인딩 가능성 | A | §1.3 | 검토/선택 |
| R-043 | Service.onDestroy 비보장 및 Main thread 금지 | A | §11 | 필수 |
| R-044 | Android NAS/대형 파일·SAF·스트리밍 대비 | A | §6, §9 | 필수/확장 |
| R-045 | 로깅 민감정보 마스킹 및 보안 제한 | A | §10, §14 | 필수 |
| R-046 | WebSocket/SSE와 REST가 한 포트를 공유 | A | §1, §7, §8 | 필수 |
| R-047 | 시작·종료/응답 생명주기와 임계 오류의 일관성 | A | §4, §11 | 필수 |
| R-048 | 설계 문서 작성/검증·수정 및 다운로드 | U | §17–§20 | 문서 |

### 18.1 이전 대화에서 바뀐 제안들

| 이전 표현 또는 예시 | 최종 결정 | 이유 |
|---|---|---|
| `app.serve(...)` | `app.route(...)` | 사용자 직접 변경 |
| `Method.get().delete()` 구현 방식 미정 | `Method.get()`→`MethodSet`, Fluent 불변 집합 | Java 메서드 설계 충돌 방지 |
| `file.save(Path)` | `file.save(File)`, `transferTo(OutputStream)` | Android 21 호환 |
| `Cache.noCache()` | `CachePolicy.noCache()` | 공개 API 이름 통일 |
| Jackson을 Android 기본 JSON 엔진으로 검토 | Gson 2.14.0 기본, JsonCodec SPI | Android 21 기준·보안 운영 |
| `app.listen(8080)` 호출 동작 미정 | 비차단 반환·성공 후만 요청 수락 | Android 메인스레드 안전성 |
| 임의 라우트/정적 등록 순서 | 명시 라우트 우선, 정적 fallback | `/api`와 SPA 충돌 회피 |
| 서비스 auto-close만 설명 | `ctx.own` + 실패 시 cleanup + 역순 close | 초기화 중 리소스 누수 방지 |
| ws/SSE 로그 단순 HTTP 요청으로 기록 | 업그레이드·스트림 완료 각각 이벤트 | 수명주기 차이 해결 |

---

## 19. 단계별 구현 순서 및 완료 기준

| 단계 | 구현 범위 | 진행·릴리스 Gate |
|---|---|---|
| P0 | Core API, Router/MethodSet, Request/Response, Context/Lifecycle, Logging SPI | Java 8·API21 빌드, T01~08 |
| P1 | Nano 포크 HTTP Adapter + Gson JSON + Cookie + Error + AccessLog | T09–T12/T33–T40/T48 |
| P2 | Multipart + Static/MIME/Cache/Range | T13–T18/T27–T32 |
| P3 | WebSocket + SSE + shutdown/backpressure | T19–T26/T43–T46 |
| P4 | Kotlin ergonomics, Android real-device QA, hardening, docs/SBOM | T10/T11/T26/T47/T49/T50 |
| P5 선택 | Servlet Adapter, Kotlin Coroutine, JS/V8 bridge, SAF Resolver, richer metrics | 별도 RFC/테스트 |

**최초 일반 배포 Gate:** 위 표의 T01–T50 필수 테스트 통과, API 21 Debug/Release 성공, Release R8 성공, 심각도 High/Critical 미해결 의존성 취약점 없음(예외 승인 절차 별도), 영구적인 리소스 누수 없음, README·API 문서·라이선스 목록 존재.

---

## 20. 자체 검증 기록·남은 미결정사항

### 20.1 검증 방식/범위

이 문서는 현재 대화에서 언급된 요구를 수집하여 작성했다. 자동 검사는 **요구사항 ID/장 위치/키워드/API 시그니처/파일 형식**에 적용하고, 설계 관점에서는 **API 일관성, 생명주기/동시성, 보안/Android 호환**을 별도 체크리스트로 여러 차례 점검한다. **독립 외부 서브에이전트가 실제로 실행되었다는 주장을 하지 않는다.** 본 문서의 검증은 구현 또는 실기기 성능/보안 침투시험 결과가 아니다.

### 20.2 발견한 모호점 및 설계상 개선

1. **부트스트랩/Core 결합 해결:** Gson은 기본 배포에만 포함하고 core에는 JsonCodec/HttpAdapter SPI만 둔다.
2. **시작 순서 불일치 해결:** `onStart` 완료와 Registry 고정 이후에만 socket accept를 시작한다.
3. **초기화 실패 누수 해결:** `ctx.own`으로 생성 직후 자원을 등록하여 실패 경로에서도 close한다.
4. **장기 연결 응답 오류 해결:** SSE/WS의 handler 반환과 전송 종료를 분리하고 AccessLog 타이밍을 조정한다.
5. **Android API 제약 해결:** `Path/Files`, Java 11 API를 Core에서 제외하고 `File/Stream`을 사용한다.
6. **설정 충돌 해결:** `MethodSet`, `CachePolicy`, `route` 이름 통일.
7. **다중 Range 오류 회피:** 미구현시 정상 Range 요청을 `416`으로 잘못 처리하지 않는다.
8. **Kotlin JSON 한계 명시:** Gson이 Kotlin default/nullability를 완전히 보호하지 않는다고 명시.
9. **인터넷 노출 위험 표기:** 기본 localhost, LAN/TLS/Origin/auth opt-in 규정.
10. **자동 종료 ownership 문제 해결:** 외부 객체는 자동 close에서 제외하는 정책 확보.
11. **미들웨어 order 모호성 해결:** 전역→prefix→route middleware 후 handler, 명시 route 우선/정적 fallback 규정.

### 20.3 추가 결정이 필요한 항목 (사용자 요구사항 누락이 아니라 구현 결정)

- Java 패키지/아티팩트/프레임워크 명칭.
- exact HTTP Adapter 포크 기준 커밋, 라이선스 헤더 유지 방식, 보안 패치 지속 운영 주체.
- Servlet Adapter `javax`/`jakarta` 및 WebSocket 컨테이너 전략, 버전별 지원 범위.
- API 21에서 실제 TLS/WSS 인증서/암호군·최신 브라우저 상호운용성.
- Kotlin JSON 기본 모델 변환 정책(Gson 공통 vs Kotlin 선택 Moshi).
- `listen()` 시작 실패 예외·시작 이벤트의 동기/비동기 계약을 API 테스트로 최종 고정.
- JSON/Multipart/WS/SSE 등의 기본 한도를 NAS 워크로드에 맞게 실기기에서 튜닝.
- HTTP/WS route priority 및 인증 미들웨어의 실물 구현 확정.
- 서비스 컨테이너 명명 등록(`ServiceKey`)와 async middleware는 v1 이후 검토.

---

## 21. 기술·보안 근거 자료 (확인일: 2026-10-18)

1. Gson 공식 README — Java/Android 최소 지원 버전 및 Gson 2.14.0 예시: https://github.com/google/gson/blob/main/README.md
2. Gson User Guide — POJO, Generic, R8 등: https://github.com/google/gson/blob/main/UserGuide.md
3. Jackson 공식 2.21 릴리스 — Android SDK 26: https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21
4. Moshi 프로젝트 — Kotlin adapter/codegen 및 1.15.x: https://github.com/square/moshi
5. Android Desugaring 및 NIO 지원 범위: https://developer.android.com/studio/write/java11-nio-support-table
6. NanoHTTPD 공식 저장소 — HTTP/WS 구조, modified BSD, 보안 설정 기본 부재: https://github.com/NanoHttpd/nanohttpd
7. RFC 9110 (HTTP semantics, HEAD, 405, 206, Range, conditional): https://www.rfc-editor.org/rfc/rfc9110
8. RFC 6455 (WebSocket protocol): https://www.rfc-editor.org/rfc/rfc6455
9. WHATWG HTML Living Standard (SSE): https://html.spec.whatwg.org/multipage/server-sent-events.html
10. OWASP File Upload Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html
11. OWASP Logging Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
12. NVD CVE-2022-25647 (Gson before 2.8.9): https://nvd.nist.gov/vuln/detail/CVE-2022-25647
13. Android Main Thread Network exception: https://developer.android.com/reference/android/os/NetworkOnMainThreadException

**참고자료의 목적**은 호환성·프로토콜·보안 선택에 대한 사실 확인이다. 구현 리포지터리의 잠금 파일/SBOM, 정적 분석, 실제 API 21 동작 결과를 대체하지 않는다.

---

## 부록 A. Java 8 전체 조합 샘플(계약 예제)

```java
RestApp app = RestApp.create();

app.onStart(ctx -> {
    DatabaseService db = new DatabaseService();
    ctx.own(db);
    db.connect();
    ctx.register(DatabaseService.class, db);
    ctx.register(UserService.class, new UserService(db));
    ctx.register(FileService.class, new FileService(db));
});

app.onStop(ctx -> ctx.logger().info("stopping"));

app.use((req, res, next) -> {
    req.attribute("requestId", UUID.randomUUID().toString());
    next.run();
});

app.use("/api", authMiddleware);
app.onError((err, req, res) -> {
    if (!res.isCommitted()) res.status(500).json("Internal Server Error");
    else res.abort(err);
});

app.route(Method.get().delete().post(), "/api/files/:id", (req, res) -> {
    FileService files = req.service(FileService.class);
    if (req.method() == Method.GET) res.json(files.find(req.param("id")));
    else if (req.method() == Method.DELETE) {
        files.delete(req.param("id")); res.status(204).end();
    } else res.status(201).json(files.create(req));
});

app.post("/api/upload", (req, res) -> {
    UploadFile upload = req.file("file");
    if (upload == null) { res.status(400).json("Missing file"); return; }
    req.service(FileService.class).save(upload);
    res.status(201).json(Collections.singletonMap("filename", upload.filename()));
});

app.ws("/ws", (req, ws) -> {
    FileService files = req.service(FileService.class);
    ws.onText(text -> ws.sendJson(files.query(text)));
    ws.onClose((code, reason) -> app.logger().info("WS closed"));
});

app.get("/api/events", (req, res) -> {
    SseClient client = res.sse();
    client.send("connected", "ready");
});

app.mimeTypes().register(".mydata", "application/x-mydata");
app.staticFiles("/", new File("./www"), options -> {
    options.index("index.html");
    options.spaFallback(true);
    options.cache("**/*.html", CachePolicy.noCache());
    options.cache("/assets/**", CachePolicy.publicFor(365, TimeUnit.DAYS).immutable());
});

app.accessLog(options -> {
    options.enabled(true);
    options.exclude("/health");
});

ServerHandle handle = app.listen(8080);
// app.shutdown(10, TimeUnit.SECONDS);
```

**정합성 주의:** 위 파일 서비스 메서드(`find`, `create`, `query` 등)는 **앱 사용자 정의 서비스의 예시**이며 프레임워크에 포함된 것으로 간주하지 않는다. `app.onStart`에서 등록한 모든 서비스와 `authMiddleware`는 실제 애플리케이션에서 구현해야 한다.

## 부록 B. 단순 JavaScript Express 스타일 확장(비필수)

```javascript
// 향후 V8 bridge 설계의 목표 호출 형태 - Node.js Express 설치 요구 없음
app.get('/api/users/:id', (req, res) => {
  res.json({ id: req.params.id });
});
app.post('/api/calculate', (req, res) => {
  res.json({ result: req.body.a + req.body.b });
});
```

위 API는 Java Core의 Router를 호출하는 브리지일 뿐이다. V8 컨텍스트 동시 호출, JS 실행 시간/heap 제한, host object의 스레드 접근, 접근 권한 통제, 비동기 콜백 취소와 JSON 변환은 **별도 설계 및 보안 검증이 필요**하다.

