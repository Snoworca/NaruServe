# NaruServe API 문서

NaruServe는 Express.js처럼 쓰는 Java 8 / Kotlin용 경량 HTTP 서버 라이브러리입니다.
Android 5.0(API 21)부터 최신 Android, 그리고 일반 JVM에서 같은 API로 동작합니다.

> **문서 상태:** v1과 API 사용성 개선 구현을 설명합니다. Express 대응과 실행 규칙은 [Express에서 오신 분께](express-migration.md)를 참고하세요.

Express를 써 봤다면 [Express에서 오신 분께](express-migration.md)부터 읽으세요.

## 5분 시작

### Java

```java
import com.snoworca.naruserve.RestApp;

public class HelloServer {
    public static void main(String[] args) {
        RestApp.start(3000, app -> app.get("/hello", (req, res) -> res.send("Hello World")));
    }
}
```

### Kotlin

```kotlin
import com.snoworca.naruserve.RestApp

fun main() {
    RestApp.start(3000) { app -> app.get("/hello") { _, res -> res.send("Hello World") } }
}
```

위 코드는 import와 실행 진입점을 포함한 최소 서버입니다. `curl http://127.0.0.1:3000/hello`로 확인하세요. `RestApp.start`는 `127.0.0.1`에서만 받고(이 기기 전용) 시작 로그를 한 줄 남기며 Ctrl+C로 종료합니다. 다른 기기에 열려면 람다 안에서 `app.server(o -> o.bindAddress("0.0.0.0"))`를 호출하세요. 자세한 차이는 [start](app.md#start)에 있고, `RestApp.create()`와 `app.listen(...)`을 따로 쓰는 방법도 그대로 있습니다.

DTO 검증, 스레드 안전한 사용자 서비스, 업로드, 스트림 응답까지 포함한 [Java 예제](../../examples/java)와 [Kotlin 예제](../../examples/kotlin)는 [실행 안내](../../examples/README.md)를 따라 실행할 수 있습니다. 예제는 이 소스 체크아웃을 Gradle composite build로 사용합니다. 별도 소비자 프로젝트의 로컬 Maven 설치는 [루트 README](../../README.md#install)를 참고하세요.

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
HTTP 엔진은 TinyHttpd 체크아웃을 그대로 빌드하며, 커밋 `c000eb1`(TinyHttpd 3.0.0)에서 검증했습니다. 체크아웃 HEAD가 다르면 빌드는 실패하지 않고 경고만 냅니다.

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
