# Servlet 모듈 (`NaruServe-servlet`)

`NaruServe-servlet`은 NaruServe 앱을 Tomcat, Jetty 같은 Servlet 컨테이너(WAS) 위에서 실행하는 어댑터입니다. 내장 HTTP 엔진 대신 컨테이너가 연결을 받고, 라우트·미들웨어·`req`/`res` 코드는 **그대로** 씁니다.

> 이 모듈은 사양서에서 **선택 기능(P5)** 입니다. Jetty 9.4에서만 확인했고 다른 컨테이너는 확인하지 않았습니다.

## 요구사항

- Servlet **3.1 이상** (`javax.servlet`)
- JVM 전용. Android에서는 쓰지 않습니다.
- v1에는 `jakarta.servlet` 모듈이 없으며, Servlet 5 이상(`jakarta.servlet`) 컨테이너는 지원하지 않습니다. `jakarta.servlet`용 모듈은 v1 이후 항목입니다.

```groovy
// group과 버전은 임시값입니다.
implementation 'com.snoworca.naruserve:NaruServe-servlet:0.1.0-SNAPSHOT'
// JSON 코덱. 없으면 configurer가 Builder.jsonCodec(...)으로 직접 넣어야 합니다.
implementation 'com.snoworca.naruserve:NaruServe-gson:0.1.0-SNAPSHOT'
// Servlet API는 컨테이너가 제공합니다.
compileOnly 'javax.servlet:javax.servlet-api:3.1.0'
```

## 등록

웹 애플리케이션에 `ServletConfigurer`를 구현한 클래스 하나를 두고, 컨텍스트 init-parameter `naruserve.configurer`에 그 클래스 이름을 적습니다. 나머지는 `NaruServeInitializer`가 합니다.

- `NaruServeInitializer`는 jar의 `META-INF/services/javax.servlet.ServletContainerInitializer`로 컨테이너가 자동으로 찾습니다. 서블릿 클래스를 `web.xml`에 적거나 클래스 경로를 스캔할 필요가 없습니다.
- `naruserve.configurer`가 없으면 아무것도 하지 않습니다. NaruServe를 쓰지 않는 웹 애플리케이션에 jar가 들어 있어도 안전합니다.
- configurer 클래스는 public이고 인자 없는 public 생성자가 있어야 합니다. 받은 `RestApp.Builder`에는 이미 `ServletAdapter`가 들어 있습니다. 코덱·로거를 정하고 `build()`한 앱에 라우트를 등록해 돌려주세요.
- 서블릿은 이름 `naruserve`, `asyncSupported=true`, `MultipartConfig` 없이 `naruserve.urlPattern`에 등록됩니다.

| init-parameter | 기본값 | 설명 |
|---|---|---|
| `naruserve.configurer` | 없음 | `ServletConfigurer` 구현 클래스의 이름. 없으면 NaruServe를 올리지 않음 |
| `naruserve.urlPattern` | `/*` | 서블릿 매핑. `/*`, `/`, `/접두사/*`만 허용 |
| `naruserve.shutdownTimeoutSeconds` | `10` | 웹 애플리케이션을 내릴 때 `app.shutdown`의 timeout(초). 1~300 정수 |

잘못된 값은 매개변수 이름과 값을 담은 `ServletException`으로 컨텍스트 시작을 실패시킵니다.

`web.xml`:

```xml
<web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee" version="3.1">
  <context-param>
    <param-name>naruserve.configurer</param-name>
    <param-value>com.example.AppConfigurer</param-value>
  </context-param>
  <context-param>
    <param-name>naruserve.urlPattern</param-name>
    <param-value>/api/*</param-value>
  </context-param>
</web-app>
```

Java configurer:

```java
import com.snoworca.naruserve.servlet.ServletConfigurer;

public class AppConfigurer implements ServletConfigurer {
    @Override
    public RestApp configure(RestApp.Builder builder) {
        RestApp app = builder.build();
        app.get("/users/:id", (req, res) -> res.json(Collections.singletonMap("id", req.param("id"))));
        return app;
    }
}
```

Kotlin:

```kotlin
// import com.snoworca.naruserve.servlet.ServletConfigurer
class AppConfigurer : ServletConfigurer {
    override fun configure(builder: RestApp.Builder): RestApp {
        val app = builder.build()
        app.get("/users/:id") { req, res -> res.json(mapOf("id" to req.param("id"))) }
        return app
    }
}
```

Express에는 서블릿 컨테이너에 앱을 끼워 넣는 기능이 없고, 가장 가까운 것은 호스트 서버에 앱을 마운트하는 것입니다. `http.createServer(app)`처럼 이미 있는 서버에 `app`을 넘기는 자리에 컨테이너가 들어간다고 보면 됩니다.

## 내장 엔진과 다른 점

| 항목 | 내장 엔진 (`app.listen`) | Servlet 모듈 |
|---|---|---|
| 포트·TLS·스레드 풀 | NaruServe 설정 | 컨테이너 설정. `ServerHandle.port()`는 `0` |
| 시작·종료 | `app.listen()` / `app.shutdown()` | 컨테이너가 웹 애플리케이션을 올릴 때 `app.listen(0)`, 내릴 때 `app.shutdown(timeout, SECONDS)` |
| 경로 | 요청 경로 그대로 | `getRequestURI()`에서 컨텍스트 경로와 url-pattern 접두사를 뺀 나머지 (퍼센트 인코딩 유지) |
| 파일 업로드 | NaruServe 파서 | 같은 NaruServe 파서가 `getInputStream()`을 읽음. Servlet `Part` API와 `MultipartConfig`는 쓰지 않음 |
| SSE | 내장 스트리밍 | `AsyncContext`(제한 시간 0) 위에서 어댑터 전용 스레드가 스트림을 씀 |
| SSE 강제 종료 | 종료 기한에 소켓을 닫음 | 종료 기한에 `AsyncContext.complete()`. 막힌 쓰기가 풀리는 시점은 컨테이너가 정함 |
| WebSocket | 내장 | v1에서는 지원하지 않음. `app.ws` 라우트는 `501` |
| 응답 헤더 순서 | 쓴 순서 | 컨테이너가 정함 |
| 스레드 총량 | 452개(D-416의 451에 keeper 1) | 적용하지 않음 |
| 확인한 컨테이너 | — | Jetty 9.4만 |

## 포트와 시작·종료

- 포트는 컨테이너가 정합니다. `ServletAdapter.bind`는 핸들러만 받아 두고 소켓도 스레드도 열지 않으며, `ServerHandle.port()`는 항상 `0`입니다. `ServerHandle.address()`는 컨테이너의 주소가 아닙니다. 실제 포트와 주소는 컨테이너 설정에서 확인하세요.
- 컨테이너가 웹 애플리케이션을 올리면 configurer로 앱을 만든 뒤 첫 요청 전에 `app.listen(0)`을 부릅니다. `onStart`가 한 번 돌고 `app.state()`는 `RUNNING`이 됩니다. 앱 코드가 직접 `app.listen(8080)`을 불러도 같은 경로를 타며 아무것도 열지 않습니다. Express에서 `app.listen` 대신 서버에 `app`을 넘기는 것과 같습니다.
- `onStart`가 실패하면 그 예외(`AppStartException`)를 원인으로 담은 `ServletException`으로 컨텍스트 시작이 실패하고, 앱 스레드는 남지 않습니다.
- 컨테이너가 웹 애플리케이션을 내리면(destroy 콜백) `ServletContextListener`가 `app.shutdown(timeout, SECONDS)`를 한 번 부릅니다. `timeout`은 `naruserve.shutdownTimeoutSeconds`(기본 10초)입니다. `onStop`이 돌고 상태는 `TERMINATED`가 되며, 두 번째 종료 알림은 아무 일도 하지 않습니다.
- 로거는 configurer가 `Builder.logger(...)`로 정한 것을 쓰고, 정하지 않으면 `RestApp.Builder` 기본값인 `ConsoleLogger`입니다.

## 종료

`app.shutdown(timeout, unit)`의 순서는 내장 엔진과 같습니다([shutdown](app.md#shutdown)). 어댑터가 맡는 부분은 다음과 같습니다.

- 종료가 시작되면 새 요청은 코어 핸들러에 닿기 전에 어댑터가 표준 오류 본문과 요청 ID로 `503`과 `Connection: close`를 답합니다.
- 열린 SSE 스트림은 코어가 종료 알림(`event: shutdown`)을 보내고 닫습니다. 어댑터는 진행 중인 요청과 열린 스트림이 모두 끝날 때까지 종료 기한까지 기다리고, 모두 끝나면 기한 전에 돌아옵니다.
- 기한이 지나도 끝나지 않은 요청은 그대로 둡니다. 컨테이너의 요청 스레드를 인터럽트하지 않습니다. 기한까지 열려 있는 스트림은 `AsyncContext.complete()`로 끝내고, SSE 스레드 풀을 멈춥니다.
- 마지막에 핸들러를 떼어 내므로 그 뒤의 요청은 모두 `503`입니다. 종료가 끝나고 2초 안에 `naruserve-servlet-sse-*` 스레드는 남지 않습니다.

"timeout 때 강제로 끊는다"는 SSE 약속은 Servlet 어댑터에서 컨테이너의 쓰기 제한 시간까지만 지켜집니다. 읽지 않는 클라이언트 때문에 소켓 쓰기에 막힌 스트림은 기한에 `AsyncContext.complete()`로 끊지만, 막힌 쓰기가 풀리는 시점은 컨테이너가 정합니다. Jetty 9.4는 바로 풀고, 다른 컨테이너는 자기 쓰기 제한 시간이 지나야 풀 수 있습니다.

## JSON 코덱

웹 애플리케이션 클래스 경로에 `com.snoworca.naruserve.gson.GsonJsonCodec`(`NaruServe-gson`)이 있으면 웹 애플리케이션의 클래스 로더로 리플렉션해 찾아 configure 전에 코덱으로 넣어 둡니다. configurer는 `Builder.jsonCodec(...)`으로 바꿀 수 있습니다. 둘 다 없으면 메시지에 `com.snoworca.naruserve.gson.GsonJsonCodec`과 `jsonCodec`을 담은 `ServletException`으로 시작이 실패합니다. 이 모듈 자체는 `NaruServe-api`에만 의존합니다.

## 설정

컨테이너가 연결·스레드·가상 호스트를 맡으므로 내장 엔진을 설정하는 `ServerOptions` 값은 이 어댑터에서 효과가 없습니다. `listen`의 포트 인자도 무시합니다.

- 무시하는 `ServerOptions` 설정: `bindAddress`, `allowedHosts`, `maxHeaderSize`, `maxHeaderCount`, `maxRequestLineLength`, `headerTimeout`, `bodyReadTimeout`, `writeTimeout`, `minBodyRate`, `maxWorkers`, `maxConnections`, `maxUpgradedConnections`, `maxConnectionsPerAddress`
- 적용하는 설정: `jsonLimit`, `formLimit`, `app.multipart`, `maxSseStreams`, `app.sse`, `trustProxy`

본문 한도는 코어가 검사합니다. 어댑터는 컨테이너 쪽 한도를 걸지 않으므로 `jsonLimit`, `formLimit`, `app.multipart` 한도를 넘는 본문은 표준 오류 본문과 함께 `413`입니다. `allowedHosts`가 동작하지 않으므로 Host 제한은 컨테이너의 가상 호스트 설정으로 거세요. LAN 경고와 `ServerHandle.address()`는 설정한 `bindAddress` 값을 말할 뿐 컨테이너 커넥터의 주소가 아닙니다. 기본 설정(`127.0.0.1`)이면 LAN 경고는 나오지 않습니다.

## 스레드

내장 엔진의 스레드 총량 452개(D-416의 451에 `naruserve-keeper` 1)는 Servlet 어댑터에 적용되지 않습니다. 이 어댑터의 스레드는 컨테이너의 스레드에 SSE 스레드 최대 `maxSseStreams`개를 더한 것입니다.

## 경로 처리

라우트가 보는 경로(`req.rawPath()`)는 컨테이너가 디코딩·정규화한 `getServletPath()`/`getPathInfo()`가 아니라 원본 `getRequestURI()`에서 컨텍스트 경로와 url-pattern 접두사를 뺀 나머지입니다. Express에서 `app.use('/api', router)` 아래 라우터가 `/api`를 뺀 경로를 보는 것과 같습니다.

- 퍼센트 인코딩은 그대로 두고 코어가 한 번만 디코딩합니다. `;name=value` 경로 파라미터도 경로에 남습니다.
- 컨텍스트 경로 `/app`, 패턴 `/api/*`에서 `/app/api/users/%41;v=1`은 `/users/%41;v=1`이 되고 `/users/:id`의 `id`는 `A;v=1`입니다.
- 패턴 `/*`와 `/`는 컨텍스트 경로 뒤를 그대로 씁니다. 남는 경로가 비면 `/`입니다.
- 원본 URI가 컨텍스트 경로 + 접두사로 경로 구분자(`/`) 경계에서 시작하지 않으면(예: `/app/%61pi/users`) 어댑터가 표준 오류 본문으로 `404`를 답합니다. 앱이 아직 시작되지 않았거나 종료 중이면 `503`과 `Connection: close`입니다.
- 쿼리는 `getQueryString()` 원본, 헤더는 이름마다 모든 값을 받은 순서대로 넘깁니다. Servlet API는 이름 사이의 순서를 주지 않습니다.
- `getParameter*`는 쓰지 않습니다. 폼 본문을 먼저 읽어 버리기 때문입니다.

Jetty 9.4에서 잘못된 요청 경로를 원본 요청 줄로 보내 관찰한 상태입니다. 어느 것도 라우트에 닿지 않고 `2xx`가 되지 않습니다.

| 요청 | 경우 | 상태 | 응답한 쪽 |
|---|---|---|---|
| `GET /a%zz` | 잘못된 퍼센트 인코딩 | 400 | 컨테이너 |
| `GET /a%2` | 끝이 잘린 퍼센트 인코딩 | 400 | 컨테이너 |
| `GET /a%C3%28` | 잘못된 UTF-8 | 400 | NaruServe 표준 본문 |
| `GET /a%2Fb` | 인코딩된 `/` | 400 | NaruServe 표준 본문 |
| `GET /a%00b` | 인코딩된 NUL | 400 | 컨테이너 |
| `GET /a<NUL>b` | 원본 NUL 바이트 | 400 | 컨테이너 |
| `GET /./a` | `.` 세그먼트 | 400 | NaruServe 표준 본문 |
| `GET /../a` | 루트 위 `..` 세그먼트 | 400 | 컨테이너 |
| `GET /a/../b` | `..` 세그먼트 | 400 | NaruServe 표준 본문 |
| `GET /%2e/a` | 인코딩된 `.` 세그먼트 | 400 | NaruServe 표준 본문 |
| `GET /%2e%2e/a` | 루트 위 인코딩된 `..` | 400 | 컨테이너 |
| `GET /a/%2e%2e/b` | 인코딩된 `..` 세그먼트 | 400 | NaruServe 표준 본문 |
| `OPTIONS *` | 서버 전체 OPTIONS | 404 | NaruServe 표준 본문 (경로가 `/`로 시작하지 않음) |

## 응답 쓰기

- 상태는 `setStatus`, 헤더는 `addHeader`로 주어진 순서대로 쓰고 `Content-Length`는 코어가 정한 값을 `setHeader`로 넘깁니다. `sendError`와 `setContentLength`는 부르지 않으므로 `404`·`500`에도 컨테이너 오류 페이지가 나오지 않습니다.
- 응답 헤더가 전송되는 순서는 컨테이너가 정합니다. 이름 사이의 순서에 기대는 코드를 쓰지 마세요.
- 컨테이너는 작은 본문을 버퍼에 모아 길이를 붙여 보낼 수 있습니다. 길이를 모르는 `res.stream` 본문이 버퍼보다 크면 chunked로 갑니다. Jetty는 `Content-Type`의 `;` 뒤 공백을 지워 다시 씁니다.
- `HEAD`는 `GET` 본문의 `Content-Length`만 보내고, `204`와 `304`는 본문이 없습니다.
- 연결을 끊는 일은 컨테이너 몫입니다. 이벤트 스트림이 아닌 응답이 비정상 종료(`abort`)되면 핸들러가 돌아온 뒤 서블릿이 `IOException`을 던지므로, 컨테이너는 이미 커밋된 응답을 끝 청크 없이 끊고 커밋 전이면 500으로 답합니다. 비정상 종료(`abort`) 뒤의 쓰기는 `IOException`으로 실행되기 전에 실패하고, `abort`는 이미 실행 중인 쓰기가 끝나기를 최대 250ms 기다린 뒤 `AsyncContext`를 완료하므로 완료가 쓰기와 겹치지 않습니다. 그보다 오래 소켓에 막힌 쓰기는 완료로 끊기거나 컨테이너의 쓰기 제한 시간이 지나야 끝납니다.

앱 코드는 `javax.servlet` 타입을 보지 않습니다. `Cookie`도 NaruServe `Cookie`이고 `javax.servlet.http.Cookie`가 아닙니다.

## 파일 업로드와 폼 본문

- multipart, urlencoded, JSON 본문은 모두 내장 엔진과 같은 NaruServe 파서가 `getInputStream()`에서 읽습니다. `req.file`, `req.files`, `req.form`, `req.parts` 값은 내장 엔진·인메모리 어댑터와 같습니다.
- 어댑터는 `getParts()`, `getPart()`, `getParameter*`를 부르지 않고 서블릿을 `MultipartConfig` 없이 등록합니다. 컨테이너가 본문을 먼저 읽거나 임시 파일을 만들지 않습니다.
- `app.multipart`의 파일 크기·파트 수·전체 크기 한도, `formLimit`, `jsonLimit`을 넘는 본문은 표준 오류 본문과 함께 `413`이고, 응답 뒤 `tempDirectory`에 업로드 임시 파일이 남지 않습니다. 업로드 중에 클라이언트가 연결을 끊어도 컨테이너의 요청 스레드가 돌아온 뒤 임시 파일은 없습니다.

## SSE

- `res.sse()`는 `startAsync()`로 비동기 처리를 시작하고 `AsyncContext.setTimeout(0)`을 겁니다. 컨테이너 기본 비동기 제한 시간(Jetty 9.4는 30초)이 긴 스트림을 끊지 않습니다. 서블릿은 `asyncSupported=true`로 등록되며, 앞에 둔 필터도 `asyncSupported=true`여야 합니다.
- 열린 스트림 수는 어댑터가 `maxSseStreams`(기본 128)로 셉니다. 한도에 닿으면 응답을 시작하기 전에 표준 오류 본문과 요청 ID로 `503`을 답합니다. 스트림이 끝나면(클라이언트 종료, `close()`, 쓰기 실패, `abort`) 자리가 돌아옵니다.
- 스트림 루프는 컨테이너 스레드가 아니라 어댑터가 가진 데몬 스레드 `naruserve-servlet-sse-<n>`(최대 `maxSseStreams`개)에서 돕니다. 핸들러가 돌아오면 컨테이너의 요청 스레드는 바로 풀립니다.
- 이벤트와 `: ping` 하트비트는 쓸 때마다 flush하므로 컨테이너 버퍼에 머물지 않습니다. 핸들러 스레드가 루프 시작 전에 보낸 이벤트도 바로 갑니다.
- 스트림이 끝나면 `AsyncContext.complete()`를 한 번 부르고 `onClose` 리스너가 한 번 돕니다. 클라이언트가 소켓을 닫으면 다음 쓰기나 하트비트에서 알아채고 접근 로그에 `cause=client`를 남깁니다.
- 종료 때의 동작과 강제 종료의 예외는 [종료](#종료)에 있습니다.

## WebSocket

Servlet 어댑터는 v1에서 WebSocket을 지원하지 않습니다. `app.ws` 라우트는 이 어댑터에서 `501`을 답하며, WebSocket은 내장 엔진(`app.listen`)이 제공합니다.

- 올바른 업그레이드 요청(`Upgrade: websocket`, `Connection: Upgrade`, `Sec-WebSocket-Version: 13`, 키)은 `101`도 `500`도 아닌 표준 오류 본문과 요청 ID의 `501`을 받고, 세션은 만들어지지 않습니다.
- 코어가 먼저 답하는 경우는 그대로입니다. `Upgrade` 없는 요청은 `Upgrade: websocket`과 함께 `426`, 허용하지 않은 `Origin`은 `403`, GET이 아닌 메서드는 내장 엔진과 같은 답입니다.

## 쓰는 모습

앱 구성은 내장 엔진과 같고, `listen` 대신 configurer에서 돌려준 앱을 컨테이너가 올립니다.

```java
// 앱 구성: 내장 엔진과 같은 코드
static void configure(RestApp app) {
    app.onStart(ctx -> ctx.register(UserService.class, new UserService()));
    app.get("/api/users", (req, res) -> res.json(req.service(UserService.class).findAll()));
}
```
