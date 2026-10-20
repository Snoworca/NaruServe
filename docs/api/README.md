# NaruServe API 문서

NaruServe는 Express.js처럼 쓰는 Java 8 / Kotlin용 경량 HTTP 서버 라이브러리입니다.
Android 5.0(API 21)부터 최신 Android, 그리고 일반 JVM에서 같은 API로 동작합니다.

> **문서 상태:** 이 문서는 v1 구현을 설명합니다. 남은 미결 항목은 [Express에서 오신 분께](express-migration.md)의 Express 호환 세 행(`express.Router()`, `res.redirect(url)`, `cors()`)뿐이며, 세 행 모두 v1 이후 항목이고 표에 대체 코드를 적었습니다.

Express를 써 봤다면 [Express에서 오신 분께](express-migration.md)부터 읽으세요.

## 5분 시작

### Java

```java
RestApp app = RestApp.create();

app.get("/hello", (req, res) -> res.send("Hello World"));

app.get("/users/:id", (req, res) -> {
    res.json(userService.findById(req.param("id")));
});

app.post("/users", (req, res) -> {
    User user = req.body(User.class);
    res.status(201).json(userService.create(user));
});

app.listen(3000);
```

### Kotlin

```kotlin
val app = RestApp.create()

app.get("/hello") { req, res -> res.send("Hello World") }

app.get("/users/:id") { req, res ->
    res.json(userService.findById(req.param("id")))
}

app.post("/users") { req, res ->
    val user = req.body(User::class.java) ?: throw HttpException(400, "JSON body is null")
    res.status(201).json(userService.create(user))
}

app.listen(3000)
```

같은 코드를 Express로 쓰면 이렇습니다.

```javascript
const app = express();
app.use(express.json());
app.get('/hello', (req, res) => res.send('Hello World'));
app.get('/users/:id', (req, res) => res.json(userService.findById(req.params.id)));
app.post('/users', (req, res) => res.status(201).json(userService.create(req.body)));
app.listen(3000);
```

Java에서는 `express.json()` 같은 본문 파서를 따로 등록하지 않습니다. `req.body(User.class)`를 호출할 때 읽습니다.
`req.body(...)`는 본문이 JSON `null`이면 `null`을 돌려주므로 Kotlin에서는 `?:`로 처리합니다([Kotlin](kotlin.md#null-처리)).

## 문서 목록

| 문서 | Express에서 해당하는 것 | 내용 |
|---|---|---|
| [Express에서 오신 분께](express-migration.md) | — | Express ↔ NaruServe 대응표, 꼭 알아야 할 차이 |
| [RestApp](app.md) | `express()`, `app` | 앱 생성, 라우트 등록, 설정, `listen`, 종료 |
| [라우팅](routing.md) | `app.METHOD`, `app.route`, `app.all` | 경로 패턴, `Method`/`MethodSet`, 404/405/HEAD/OPTIONS |
| [Request](request.md) | `req` | 경로 변수, query, 헤더, 본문, 속성, 서비스 조회 |
| [Response](response.md) | `res` | 상태, JSON, 텍스트, 파일, 스트림, 캐시 |
| [미들웨어와 오류 처리](middleware.md) | `app.use`, `next`, 오류 미들웨어 | 실행 순서, `next.run()`, `app.onError`, 오류 JSON |
| [JSON](json.md) | `express.json()` | `JsonCodec`, `TypeRef`, Gson 설정, 교체 |
| [파일 업로드](multipart.md) | `multer` | `app.multipart`, `req.file`, `UploadFile` |
| [정적 파일·캐시](static.md) | `express.static`, `res.sendFile` | `app.staticFiles`, MIME, `CachePolicy`, Range |
| [Cookie](cookie.md) | `res.cookie`, `cookie-parser` | `Cookie`, `SameSite` |
| [WebSocket](websocket.md) | `express-ws`, `ws` | `app.ws`, `WebSocketSession` |
| [SSE](sse.md) | 직접 `res.write` | `res.sse()`, `SseClient`, `SseEvent` |
| [수명주기와 서비스](lifecycle.md) | `app.locals` | `onStart`/`onStop`, `ApplicationContext`, `ServiceRegistry` |
| [로깅](logging.md) | `morgan` | `app.accessLog`, `Logger` |
| [기본값과 보안](defaults.md) | — | 크기·시간 한도 기본값, 기본 보안 정책 |
| [Kotlin](kotlin.md) | — | Kotlin에서 쓰는 법, null 처리, JSON 주의 |
| [Servlet 모듈](servlet.md) | — | `NaruServe-servlet`으로 WAS 위에서 실행 |

## 모듈

모든 모듈의 Maven group은 `com.snoworca.naruserve`, 버전은 `0.1.0-SNAPSHOT`입니다.

| 모듈 | 좌표 | 내용 | Android |
|---|---|---|---|
| `NaruServe-api` | `com.snoworca.naruserve:NaruServe-api` | 공개 API(`RestApp`, `Request`, `Response` …)와 SPI(`HttpAdapter`, `JsonCodec`, `Logger`). 외부 의존성 없음 | 지원(API 21+) |
| `NaruServe` | `com.snoworca.naruserve:NaruServe` | `RestApp.create()` 기본 조립(TinyHttpd + Gson + Logcat 로거) | 지원(API 21+) |
| `NaruServe-tinyhttpd` | `com.snoworca.naruserve:NaruServe-tinyhttpd` | TinyHttpd `HttpAdapter` | 지원(API 21+) |
| `NaruServe-gson` | `com.snoworca.naruserve:NaruServe-gson` | `GsonJsonCodec`(`com.google.code.gson:gson:2.14.0`) | 지원(API 21+) |
| `NaruServe-kotlin` | `com.snoworca.naruserve:NaruServe-kotlin` | `reified` 등 Kotlin 전용 확장. `kotlin-stdlib`는 이 모듈만 씁니다 | 지원(API 21+) |
| `NaruServe-logcat` | `com.snoworca.naruserve:NaruServe-logcat` | Logcat `Logger`(Android SDK 없이 빌드, 리플렉션으로 `android.util.Log` 호출) | 지원(API 21+) |
| `NaruServe-filelog` | `com.snoworca.naruserve:NaruServe-filelog` | 파일 로테이션 `Logger` | 지원(API 21+) |
| `NaruServe-slf4j` | `com.snoworca.naruserve:NaruServe-slf4j` | SLF4J `Logger` 어댑터(`org.slf4j:slf4j-api:1.7.36`) | 미지원(JVM 전용) |
| `NaruServe-servlet` | `com.snoworca.naruserve:NaruServe-servlet` | Servlet 3.1+ 컨테이너 위에서 NaruServe 앱을 실행하는 어댑터 | 미지원(JVM 전용) |

"지원(API 21+)" 모듈은 빌드 때 animal-sniffer `android-api-level-21` 검사를 받습니다.
HTTP 엔진은 TinyHttpd 체크아웃을 그대로 빌드하며, 커밋 `46b973e`에서 검증했습니다. 체크아웃 HEAD가 다르면 빌드는 실패하지 않고 경고만 냅니다.

`RestApp.create()`는 `NaruServe` 모듈이 기본 HTTP 엔진(TinyHttpd)과 Gson을 자동으로 조립합니다.
엔진과 JSON 구현을 직접 고르려면 `RestApp.builder()`에 `httpAdapter`, `jsonCodec`(필수)과 `logger`(선택)를 넘기고 `build()`합니다([RestApp](app.md#앱-만들기) 참고).

```groovy
// RestApp.create()로 바로 시작합니다. 컴파일 시점에는 NaruServe-api가,
// 실행 시점에는 TinyHttpd 엔진·Gson 2.14.0·Logcat 로거가 함께 들어옵니다.
// GsonJsonCodec이나 로거 클래스를 코드에서 직접 쓰려면 그 모듈(NaruServe-gson 등)을 따로 넣으세요.
implementation 'com.snoworca.naruserve:NaruServe:0.1.0-SNAPSHOT'
```

## 지원 환경

- Java 8 이상 JVM
- Android 5.0(API 21) 이상
- Kotlin(Java 람다를 그대로 씁니다. 별도 DSL이 필요 없습니다.)
