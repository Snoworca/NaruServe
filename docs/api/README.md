# NaruServe API 문서

NaruServe는 Express.js처럼 쓰는 Java 8 / Kotlin용 경량 HTTP 서버 라이브러리입니다.
Android 5.0(API 21)부터 최신 Android, 그리고 일반 JVM에서 같은 API로 동작합니다.

> **문서 상태:** 이 문서는 `object.md`(설계사양서)를 기준으로 한 **구현 목표 계약**입니다. 아직 구현되지 않았습니다.
> 사양서에 이름이 정해지지 않은 부분은 **(미확정)** 으로 표시했습니다.

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
    val user = req.body(User::class.java)
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

| 모듈 | 내용 | Android |
|---|---|---|
| `NaruServe-api` | 공개 API(`RestApp`, `Request`, `Response` …)와 SPI(`HttpAdapter`, `JsonCodec`, `Logger`) | 지원(API 21+) |
| `NaruServe-servlet` | Servlet 3.1+ 컨테이너 위에서 NaruServe 앱을 실행하는 어댑터 | 미지원(JVM 전용) |

`RestApp.create()`는 기본 HTTP 엔진(TinyHttpd)과 Gson을 자동으로 조립합니다. 이 조립을 담당하는 배포 모듈의 이름은 **(미확정)** 입니다.
엔진과 JSON 구현을 직접 고르려면 `RestApp.builder()`(이름 **(미확정)**)를 씁니다([RestApp](app.md#앱-만들기) 참고).

```groovy
// group과 버전은 임시값입니다.
// 이 두 줄만으로는 서버를 실행할 수 없습니다.
// RestApp.create()에 필요한 배포 모듈, TinyHttpd HttpAdapter와 GsonJsonCodec이 들어 있는 모듈의 이름은 (미확정)입니다.
implementation 'com.snoworca.naruserve:NaruServe-api:0.1.0-SNAPSHOT'
implementation 'com.google.code.gson:gson:2.14.0'
```

## 지원 환경

- Java 8 이상 JVM
- Android 5.0(API 21) 이상
- Kotlin(Java 람다를 그대로 씁니다. 별도 DSL이 필요 없습니다.)
