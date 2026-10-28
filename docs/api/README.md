# NaruServe API 문서

한국어 | [English](en/README.md)

NaruServe는 Express.js처럼 쓰는 Java 8 / Kotlin용 경량 HTTP 서버 라이브러리입니다.
Android 5.0(API 21)부터 최신 Android, 그리고 일반 JVM에서 같은 API로 동작합니다.

> **문서 상태:** 0.10.0 공통 HTTP 프로파일과 기존 확장 API를 설명합니다. 설치 좌표와 저장소 가용성에 따른 소스 빌드는 [설치 안내](../../README.ko.md#설치)를 따르세요. 라우팅·오류·비동기 계약과 Java Pattern 경계는 [Express에서 오신 분께](express-migration.md)를 참고하세요.

Express를 써 봤다면 [Express에서 오신 분께](express-migration.md)부터 읽으세요.

## 읽는 순서

이 문서는 라이브러리를 사용하는 Java/Kotlin 개발자를 위한 가이드입니다. NaruServe에서 직접 경로를 등록하므로 고정된 REST 엔드포인트 목록은 없습니다.

1. [설치와 프로젝트 구조](../../README.ko.md)를 확인하고 아래 최소 서버를 실행합니다.
2. [RestApp](app.md)과 [라우팅](routing.md)에서 앱 조립과 경로 등록을 익힙니다.
3. [Request](request.md), [Response](response.md), [미들웨어](middleware.md)에서 입력 검증, 응답, 오류 처리를 확인합니다.
4. 필요에 따라 JSON, 업로드, 정적 파일, WebSocket, SSE, Kotlin, Servlet 가이드를 선택합니다.

가이드의 `app`, `req`, `res`를 사용하는 코드는 기존 앱이나 핸들러 내부에 넣는 예제입니다. 인터페이스 선언은 API 설명용 발췌입니다. import와 실행 진입점을 갖춘 전체 앱은 아래 예제 링크를 사용하세요.

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

DTO 검증, 스레드 안전한 사용자 서비스, 업로드, 스트림 응답까지 포함한 [Java 예제](../../examples/java)와 [Kotlin 예제](../../examples/kotlin)는 [실행 안내](../../examples/README.ko.md)를 따라 실행할 수 있습니다. 예제는 이 소스 체크아웃을 Gradle composite build로 사용합니다. 별도 소비자 프로젝트의 설치 좌표와 소스 빌드 대안은 [한국어 README](../../README.ko.md#설치)를 참고하세요. Maven Central에서 버전이 조회되지 않으면 소스 체크아웃을 로컬에 게시해 사용하세요.

## 문서 목록

| 문서 | Express에서 해당하는 것 | 내용 |
|---|---|---|
| [Express에서 오신 분께](express-migration.md) | — | Express ↔ NaruServe 대응표, 꼭 알아야 할 차이 |
| [RestApp](app.md) | `express()`, `app` | 앱 생성, 라우트 등록, 설정, `listen`, 종료 |
| [라우팅](routing.md) | `app.METHOD`, `app.route`, `express.Router` | 문자열·Java Pattern 경로, `Route`·`Router`, 마운트·매개변수 콜백, 메서드·HEAD·OPTIONS |
| [Request](request.md) | `req` | 경로 뷰, 문자열·목록 매개변수, query, 헤더, 본문, 속성, 서비스 조회 |
| [Response](response.md) | `res` | 상태, JSON, 텍스트, 파일, 스트림, 캐시 |
| [미들웨어와 오류 처리](middleware.md) | `app.use`, `next`, 오류 미들웨어 | 등록 순서, 지연 `Next`·`Deferred`, route/router 건너뛰기, HTML 최종 오류·명시적 JSON |
| [JSON](json.md) | `express.json()` | `JsonCodec`, `TypeRef`, Gson 설정, 교체 |
| [파일 업로드](multipart.md) | `multer` | `app.multipart`, `req.file`, `UploadFile` |
| [정적 파일·캐시](static.md) | `express.static`, `res.sendFile` | `app.staticFiles`, MIME, `CachePolicy`, Range |
| [Cookie](cookie.md) | `res.cookie`, `cookie-parser` | `Cookie`, `SameSite` |
| [WebSocket](websocket.md) | `express-ws`, `ws` | `app.ws`, `WebSocketSession` |
| [SSE](sse.md) | 직접 `res.write` | `res.sse()`, `SseClient`, `SseEvent` |
| [수명주기와 서비스](lifecycle.md) | `app.locals` | 요청 자원 보존·취소, `onStart`/`onStop`, `ApplicationContext`, `ServiceRegistry` |
| [로깅](logging.md) | `morgan` | `app.accessLog`, `Logger` |
| [기본값과 보안](defaults.md) | — | 크기·시간 한도 기본값, 기본 보안 정책 |
| [Kotlin](kotlin.md) | — | Kotlin에서 쓰는 법, null 처리, JSON 주의 |
| [Servlet 모듈](servlet.md) | — | `NaruServe-servlet`으로 WAS 위에서 실행 |

## 모듈

모든 모듈의 Maven group은 `com.snoworca.naruserve`, 버전은 `0.10.0`입니다. artifactId는 모듈 이름을 소문자로 쓴 것입니다(`NaruServe-api` → `naruserve-api`).

| 모듈 | 좌표 | 내용 | Android |
|---|---|---|---|
| `NaruServe-api` | `com.snoworca.naruserve:naruserve-api` | 공개 API(`RestApp`, `Request`, `Response` …)와 SPI(`HttpAdapter`, `JsonCodec`, `Logger`). 외부 실행 의존성 없음(JSpecify는 compile-only) | 지원(API 21+) |
| `NaruServe` | `com.snoworca.naruserve:naruserve` | `RestApp.create()` 기본 조립(TinyHttpd + Gson + 콘솔 로거). 일반 JDK와 Android 어디서나 씁니다 | 지원(API 21+) |
| `NaruServe-tinyhttpd` | `com.snoworca.naruserve:naruserve-tinyhttpd` | TinyHttpd `HttpAdapter` | 지원(API 21+) |
| `NaruServe-gson` | `com.snoworca.naruserve:naruserve-gson` | `GsonJsonCodec`(`com.google.code.gson:gson:2.14.0`) | 지원(API 21+) |
| `NaruServe-kotlin` | `com.snoworca.naruserve:naruserve-kotlin` | `NaruServe` + `reified` 등 Kotlin 전용 확장. `kotlin-stdlib`는 이 모듈만 씁니다 | 지원(API 21+) |
| `NaruServe-android` | `com.snoworca.naruserve:naruserve-android` | `NaruServe` + Logcat 로거 + `android.permission.INTERNET` 권한을 담은 Android 라이브러리(AAR) | 전용(API 21+) |
| `NaruServe-logcat` | `com.snoworca.naruserve:naruserve-logcat` | Logcat `Logger`(Android SDK 없이 빌드, 리플렉션으로 `android.util.Log` 호출) | 지원(API 21+) |
| `NaruServe-filelog` | `com.snoworca.naruserve:naruserve-filelog` | 파일 로테이션 `Logger` | 지원(API 21+) |
| `NaruServe-slf4j` | `com.snoworca.naruserve:naruserve-slf4j` | SLF4J `Logger` 어댑터(`org.slf4j:slf4j-api:1.7.36`) | 미지원(JVM 전용) |
| `NaruServe-servlet` | `com.snoworca.naruserve:naruserve-servlet` | Servlet 3.1+ 컨테이너 위에서 NaruServe 앱을 실행하는 어댑터 | 미지원(JVM 전용) |

"지원(API 21+)" 모듈은 빌드 때 animal-sniffer `android-api-level-21` 검사를 받습니다.
`NaruServe-android`는 Android Gradle Plugin으로 만드는 AAR이라 Android SDK가 있을 때만 빌드됩니다(`local.properties`의 `sdk.dir`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` 순서로 찾습니다). SDK가 없으면 경고만 내고 나머지 아홉 모듈을 빌드합니다. 이 모듈에는 자체 코드가 없고 `minSdk`는 21입니다.
기본 HTTP 엔진 빌드는 TinyHttpd 체크아웃을 사용하며, 커밋 `e73cd5d`(TinyHttpd 3.1.1, 태그 `v3.1.1`: RAW 요청 대상 정책)에서 검증했습니다. 체크아웃 HEAD가 다르면 빌드는 실패하지 않고 경고만 냅니다. `-PtinyHttpdFromMaven=true`를 지정하면 체크아웃 없이 Maven Central의 3.1.1 JAR로 빌드합니다. 어댑터는 `RequestTargetPolicy.RAW`로 인코딩된 경로와 원본 쿼리를 보존합니다. HTTP 구문·Host·프레이밍 검사와 WebSocket 경로 검사는 유지됩니다.

`RestApp.create()`는 `NaruServe` 모듈이 기본 HTTP 엔진(TinyHttpd)과 Gson을 자동으로 조립합니다.
엔진과 JSON 구현을 직접 고르려면 `RestApp.builder()`에 `httpAdapter`, `jsonCodec`(필수)과 `logger`(선택)를 넘기고 `build()`합니다([RestApp](app.md#앱-만들기) 참고).

`NaruServe` 하나로 `GsonJsonCodec`과 Gson도 컴파일 시점에 함께 들어옵니다. TinyHttpd 엔진은 실행 시점에만 들어오고, 로그는 콘솔 로거로 남깁니다.
`NaruServe-kotlin`은 `NaruServe`를 포함합니다. Kotlin 프로젝트는 이 모듈 하나만 넣으면 됩니다.
Android 앱은 `NaruServe-android`를 넣습니다. `NaruServe`에 Logcat 로거와 `android.permission.INTERNET` 권한이 더해집니다.

```groovy
// 일반 JDK(Java 8+): RestApp.create()로 바로 시작합니다.
implementation 'com.snoworca.naruserve:naruserve:0.10.0'

// Kotlin: NaruServe와 reified 확장
implementation 'com.snoworca.naruserve:naruserve-kotlin:0.10.0'

// Android 앱: NaruServe와 Logcat 로거, INTERNET 권한
implementation 'com.snoworca.naruserve:naruserve-android:0.10.0'
```

Android 앱 모듈의 `build.gradle`에는 아래 설정도 넣습니다. TinyHttpd 3.1.1의 core와 websocket JAR에 같은 경로, 같은 내용의 라이선스 고지가 들어 있어, 이 설정이 없으면 앱 빌드의 리소스 병합(`mergeDebugJavaResource`)이 중복 파일로 실패합니다. 두 사본의 내용이 같으므로 하나만 남겨도 고지는 그대로 APK에 들어갑니다.

```groovy
android {
    packaging {
        resources {
            pickFirsts += ['META-INF/LICENSE-tinyhttpd.txt', 'META-INF/LICENSE-nanohttpd.txt', 'META-INF/NOTICE-tinyhttpd.txt']
        }
    }
}
```

## 지원 환경

- Java 8 이상 JVM
- Android 5.0(API 21) 이상
- Kotlin(Java 람다를 그대로 씁니다. 별도 DSL이 필요 없습니다.)

## 실행 계약과 문제 해결

- 라우트와 설정은 `listen` 이전에 등록합니다. 핸들러가 동시에 실행되므로 공유 가변 상태를 보호하세요.
- `next.run()`은 동기 호출이며 한 번만 호출할 수 있습니다.
- `res.stream`에 넘긴 스트림은 프레임워크가 전송 후 닫습니다. 업로드 임시 파일은 요청이 끝나기 전에 저장하세요.
- `naruserve-api`만으로 `RestApp.create()`를 호출하면 기본 앱 팩토리가 없습니다. 기본 조립 모듈을 추가하거나 [명시적 builder](app.md#앱-만들기)를 사용하세요.
- 소스 빌드는 JDK 17과 Gradle 8.14.3 wrapper를 사용합니다. 실행 대상 Java 8과 빌드 JDK를 구분하세요. 설치·엔진 해석·Android 리소스 병합 문제는 [README 문제 해결](../../README.ko.md#문제-해결)을 참고하세요.
