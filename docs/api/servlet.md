# Servlet 모듈 (`NaruServe-servlet`)

[한국어](servlet.md) | [English](en/servlet.md)

> **버전 범위:** 이 문서는 0.10.0 대상 체크아웃의 동작을 설명하며 Maven 배포 완료를 뜻하지 않습니다. 의존성 예제는 릴리스 버전 변경 전의 프로젝트 버전 `0.9.0`을 유지합니다. 아래 동작을 확인하려면 대상 체크아웃의 빌드를 사용하세요.

`NaruServe-servlet`은 NaruServe 앱을 Tomcat, Jetty 같은 Servlet 컨테이너(WAS) 위에서 실행하는 어댑터입니다. 내장 HTTP 엔진 대신 컨테이너가 연결을 받고, 라우트·미들웨어·`req`/`res` 코드는 **그대로** 씁니다.

> 이 모듈은 선택 어댑터입니다. Jetty 9.4에서만 확인했고 다른 컨테이너는 확인하지 않았습니다.

## 요구사항

- Servlet **3.1 이상** (`javax.servlet`)
- JVM 전용. Android에서는 쓰지 않습니다.
- v1에는 `jakarta.servlet` 모듈이 없으며, Servlet 5 이상(`jakarta.servlet`) 컨테이너는 지원하지 않습니다. `jakarta.servlet`용 모듈은 v1 이후 항목입니다.

```groovy
// Dependencies for a web application running in a Servlet container.
implementation 'com.snoworca.naruserve:naruserve-servlet:0.9.0'
// JSON codec; otherwise configure Builder.jsonCodec(...) yourself.
implementation 'com.snoworca.naruserve:naruserve-gson:0.9.0'
// The container provides the Servlet API.
compileOnly 'javax.servlet:javax.servlet-api:3.1.0'
```

## 등록

아래 코드는 웹 애플리케이션 설정 예제입니다. Java 코드의 `RestApp`과 `Collections`는 각각 `com.snoworca.naruserve.RestApp`, `java.util.Collections`를 import합니다.

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
| 지연 HTTP / SSE | 늦은 콜백과 입력 보존 | 지연 HTTP는 `AsyncContext`, SSE는 한도가 있는 어댑터 쓰기 스레드와 비동기 제한 시간 0 사용 |
| SSE 강제 종료 | 종료 기한에 소켓을 닫음 | 종료 기한에 `AsyncContext.complete()`. 막힌 쓰기가 풀리는 시점은 컨테이너가 정함 |
| WebSocket | 내장 | 이 어댑터에서는 지원하지 않음. `app.ws` 라우트는 `501` |
| 응답 헤더 순서 | 쓴 순서 | 컨테이너가 정함 |
| 스레드 총량 | 452개(D-416의 451에 keeper 1) | 적용하지 않음 |
| 확인한 컨테이너 | — | Jetty 9.4.58 / Servlet 3.1만 |

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
- 기한이 지나면 반환한 지연 요청과 입력을 보존 중인 완료 응답을 중단하고 전송 계층 보존을 해제하며 `AsyncContext`를 한 번 완료합니다. 실행 중인 컨테이너 콜백을 인터럽트하지 않으며, 돌아온 뒤 종료된 교환을 되살릴 수 없습니다. 남은 SSE 드레인을 취소하고 풀을 멈추되, 실행 중인 드레인의 풀 자리는 실제 종료까지 유지합니다.
- 마지막에 핸들러를 떼어 내므로 그 뒤의 요청은 모두 `503`입니다. 종료가 끝나고 2초 안에 `naruserve-servlet-sse-*` 스레드는 남지 않습니다.

"timeout 때 강제로 끊는다"는 SSE 약속은 Servlet 어댑터에서 컨테이너의 쓰기 제한 시간까지만 지켜집니다. 읽지 않는 클라이언트 때문에 소켓 쓰기에 막힌 스트림은 기한에 `AsyncContext.complete()`로 끊지만, 막힌 쓰기가 풀리는 시점은 컨테이너가 정합니다. Jetty 9.4는 바로 풀고, 다른 컨테이너는 자기 쓰기 제한 시간이 지나야 풀 수 있습니다.

## JSON 코덱

웹 애플리케이션 클래스 경로에 `com.snoworca.naruserve.gson.GsonJsonCodec`(`NaruServe-gson`)이 있으면 웹 애플리케이션의 클래스 로더로 리플렉션해 찾아 configure 전에 코덱으로 넣어 둡니다. configurer는 `Builder.jsonCodec(...)`으로 바꿀 수 있습니다. 둘 다 없으면 메시지에 `com.snoworca.naruserve.gson.GsonJsonCodec`과 `jsonCodec`을 담은 `ServletException`으로 시작이 실패합니다. 이 모듈 자체는 `NaruServe-api`에만 의존합니다.

## 설정

컨테이너가 연결·스레드·가상 호스트를 맡으므로 아래에 나열한 내장 엔진의 연결 관련 `ServerOptions` 값은 이 어댑터에서 효과가 없습니다. `maxSseStreams`는 적용됩니다. `listen`의 포트 인자는 무시합니다.

- 무시하는 `ServerOptions` 설정: `bindAddress`, `allowedHosts`, `maxHeaderSize`, `maxHeaderCount`, `maxRequestLineLength`, `headerTimeout`, `bodyReadTimeout`, `writeTimeout`, `minBodyRate`, `maxWorkers`, `maxConnections`, `maxUpgradedConnections`, `maxConnectionsPerAddress`
- 적용하는 설정: `jsonLimit`, `formLimit`, `app.multipart`, `maxSseStreams`, `app.sse`, `trustProxy`

본문 한도는 코어가 검사합니다. 어댑터는 컨테이너 쪽 한도를 걸지 않으므로 `jsonLimit`, `formLimit`, `app.multipart` 한도를 넘는 본문은 공통 오류 경로의 `413`이며 기본 본문은 HTML입니다. `allowedHosts`가 동작하지 않으므로 Host 제한은 컨테이너의 가상 호스트 설정으로 거세요. LAN 경고와 `ServerHandle.address()`는 설정한 `bindAddress` 값을 말할 뿐 컨테이너 커넥터의 주소가 아닙니다. 기본 설정(`127.0.0.1`)이면 LAN 경고는 나오지 않습니다.

## 스레드

내장 엔진의 스레드 총량 452개(D-416의 451에 `naruserve-keeper` 1)는 Servlet 어댑터에 적용되지 않습니다. 이 어댑터의 스레드는 컨테이너의 스레드에 SSE 스레드 최대 `maxSseStreams`개를 더한 것입니다.

## 경로 처리

라우트가 보는 경로(`req.rawPath()`)는 컨테이너가 디코딩·정규화한 `getServletPath()`/`getPathInfo()`가 아니라 원본 `getRequestURI()`에서 컨텍스트 경로와 url-pattern 접두사를 뺀 나머지입니다. Express에서 `app.use('/api', router)` 아래 라우터가 `/api`를 뺀 경로를 보는 것과 같습니다.

- 공통 HTTP 경로 매칭 동안 퍼센트 인코딩을 그대로 둡니다. 참여한 캡처만 엄격 UTF-8로 한 번 디코딩하며, 잘못된 캡처는 순서가 있는 오류 경로의 `400`입니다. `;name=value` 경로 파라미터도 경로에 남습니다.
- 컨텍스트 경로 `/app`, 패턴 `/api/*`에서 `/app/api/users/%41;v=1`은 `/users/%41;v=1`이 되고 `/users/:id`의 `id`는 `A;v=1`입니다.
- 패턴 `/*`와 `/`는 컨텍스트 경로 뒤를 그대로 씁니다. 남는 경로가 비면 `/`입니다.
- 원본 URI가 컨텍스트 경로 + 접두사로 경로 구분자(`/`) 경계에서 시작하지 않으면(예: `/app/%61pi/users`) 어댑터가 표준 오류 본문으로 `404`를 답합니다. 앱이 아직 시작되지 않았거나 종료 중이면 `503`과 `Connection: close`입니다.
- 컨테이너 재사용 전에 원본 쿼리·메서드·프로토콜·원격 주소·보안 플래그·헤더를 스냅샷으로 보존합니다. 이름의 대소문자가 섞인 중복 헤더도 각 값을 한 번씩 이름 안의 순서대로 제공합니다. Servlet API는 이름 사이의 순서를 주지 않습니다. 앱의 원본 URL은 컨텍스트·매핑 접두사를 제거한 뒤 시작하며 코어 라우터 마운트 동안 유지됩니다.
- `getParameter*`는 쓰지 않습니다. 폼 본문을 먼저 읽어 버리기 때문입니다. 입력은 지연해서 열며 메타데이터 스냅샷은 `getInputStream()`이나 본문 미리 읽기를 사용하지 않습니다.

다음은 기본 컨테이너 호환성 설정의 Jetty **9.4.58** / Servlet **3.1**과 공통 GET 라우트 `/a`, `/b`, `/a/b`, `/a/:x`, `/:x/a`로 확인한 결과입니다. 라우트는 `reached ` 뒤에 원본 raw 경로를 보냅니다. 매칭되지 않은 의심스러운 경로의 `404`는 전역 검증을 뜻하지 않으며, 잘못된 캡처는 아래에서 따로 확인합니다. 컨테이너가 거부한 행은 앱 미들웨어에 도달하지 않습니다. 정적 파일·WS의 엄격 경로 검사는 이 표와 별개입니다.

| 요청 | 경우 | 상태 | 응답 주체 / 결과 |
|---|---|---:|---|
| `GET /a%zz` | 잘못된 퍼센트 인코딩 | 400 | 컨테이너 |
| `GET /a%2` | 잘린 퍼센트 이스케이프 | 400 | 컨테이너 |
| `GET /a%C3%28` | 매칭되는 캡처 없음 | 404 | 공통 HTML: `Cannot GET /a%C3%28` |
| `GET /a%2Fb` | 매칭되는 라우트 없음 | 404 | 공통 HTML: `Cannot GET /a%2Fb` |
| `GET /a%00b` | 인코딩된 NUL | 400 | 컨테이너 |
| `GET /a<NUL>b` | 원본 NUL 바이트 | 400 | 컨테이너 |
| `GET /./a` | 원본 점 구간이 `/:x/a`에 매칭 | 200 | 앱: `reached /./a` |
| `GET /../a` | 루트 위 점 두 개 | 400 | 컨테이너 |
| `GET /a/../b` | 매칭되는 raw 라우트 없음 | 404 | 공통 HTML: `Cannot GET /a/../b` |
| `GET /%2e/a` | 인코딩된 점 캡처 | 200 | 앱: `reached /%2e/a` |
| `GET /%2e%2e/a` | 루트 위 인코딩된 점 두 개 | 400 | 컨테이너 |
| `GET /a/%2e%2e/b` | 매칭되는 raw 라우트 없음 | 404 | 공통 HTML: `Cannot GET /a/%2e%2e/b` |
| `OPTIONS *` | 앱 경로가 아님 | 404 | 어댑터 표준 JSON. 매핑은 `/`로 시작하는 경로가 필요 |

`req.param("x")`를 보내는 `/capture/:x` 라우트를 더하면 `GET /capture/a%2Fb`는 `200`과 `a/b`, `GET /capture/a%C3%28`는 `PathDecodeException`과 실패한 캡처를 담은 공통 HTML `400`입니다. `GET /a?x=%2F&v=1`은 여전히 `/a`로 매칭되고 원본 쿼리를 보존합니다. 유지되는 엄격 `routing.Router`는 위 열두 가지 과거 거부 경로를 모두 거부하며, 공통 매칭은 이 전역 거부를 상속하지 않습니다. 다른 컨테이너나 호환성 설정은 더 앞에서 거부할 수 있으므로 별도로 검증하세요.

## 지연 완료와 입력 보존

초기 콜백이 반환해도 끝나지 않은 응답은 대기합니다. 어댑터는 서블릿 반환 전에 `AsyncContext`를 만들며, 앱이 실행 중이면 나중의 `Next`·응답 쓰기·스트림·SSE 등록이 가능합니다. 일반 지연 HTTP에는 컨테이너 비동기 제한 시간이 유지되고, SSE 시작은 이를 0으로 바꿉니다. 서블릿과 앞의 모든 필터는 비동기를 지원해야 합니다.

`methodAsync`/`useAsync`에서 반환한 미완료 `Deferred`는 `res.send(...)`로 전송 응답을 끝낸 뒤에도 요청 작업을 보존합니다. 어댑터는 코어의 입력·업로드 정리와 작업 해제가 끝날 때까지 컨테이너 요청을 유지하므로, 응답 뒤의 첫 본문 읽기도 원본 POST를 디코딩할 수 있습니다. 요청 메타데이터는 다음 재사용 요청의 값이 아닌 불변 스냅샷입니다. 생산자의 마지막 자원 접근 뒤에 완료시키세요. 응답 완료가 `Deferred`를 완료하거나 `Next`를 실행하지는 않습니다.

정상 완료는 마지막 보존 해제 뒤 컨텍스트·요청 등록을 한 번 해제합니다. 감지된 연결/I/O 실패·비동기 시간 초과·강제 종료는 기한을 지키기 위해 전송 계층 보존을 해제할 수 있습니다. 늦은 해제는 무해하고 취소된 입력을 다시 열지 않습니다. 입력·업로드 정리는 실행 중인 코어 콜백의 소유권도 따릅니다. I/O 없는 유휴 연결의 종료를 즉시 감지한다는 보장은 없습니다.

비동기가 불가능하면 아직 응답을 시작하지 않은 지연 요청은 기존 어댑터 JSON `500`을 받은 뒤 종료 취소로 코어 작업·원래 입력·업로드 파일을 정리합니다. 이미 `200 ack`를 보냈지만 보존 작업이 남았다면 두 번째 응답 없이 중단하고 작업을 취소합니다. 남은 보존이 없는 동기 완료 요청은 비동기 지원이 필요 없습니다. 지연 작업을 사용한다면 서블릿과 앞의 모든 필터에 `asyncSupported=true`를 설정하세요.

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
- `app.multipart`의 파일 크기·파트 수·전체 크기 한도, `formLimit`, `jsonLimit`을 넘으면 공통 오류 경로의 복구 가능한 `413`이며 기본은 HTML입니다. 요청 작업과 입력 소유권이 정리를 허용할 때 임시 파일을 지웁니다. 응답이 먼저 끝나도 미완료 생산자가 소유한 파일을 지우지 않습니다. 업로드 중 단절의 정리도 활성 요청 콜백의 소유권 해제 뒤 완료됩니다.

## SSE

- `res.sse()`는 `startAsync()`로 비동기 처리를 시작하고 `AsyncContext.setTimeout(0)`을 겁니다. 컨테이너 기본 비동기 제한 시간(Jetty 9.4는 30초)이 긴 스트림을 끊지 않습니다. 서블릿은 `asyncSupported=true`로 등록되며, 앞에 둔 필터도 `asyncSupported=true`여야 합니다.
- 열린 스트림 수는 어댑터가 `maxSseStreams`(기본 128)로 셉니다. 한도에 닿은 공통 HTTP `res.sse()`는 응답 시작 전에 오류 경로의 `503`을 받으며 기본 본문은 HTML입니다. 실행 중인 드레인은 완료·중단 후에도 실제로 끝날 때까지 풀 자리를 유지합니다. 설정 실패와 시작 전 취소는 자리를 한 번 해제합니다.
- 초기 서블릿 호출이 반환한 뒤에도 SSE를 시작할 수 있으며 기존 비동기 컨텍스트와 한도가 있는 쓰기 소유권을 사용합니다.
- 스트림 루프는 컨테이너 스레드가 아니라 어댑터가 가진 데몬 스레드 `naruserve-servlet-sse-<n>`(최대 `maxSseStreams`개)에서 돕니다. 핸들러가 돌아오면 컨테이너의 요청 스레드는 바로 풀립니다.
- 이벤트와 `: ping` 하트비트는 쓸 때마다 flush하므로 컨테이너 버퍼에 머물지 않습니다. 핸들러 스레드가 루프 시작 전에 보낸 이벤트도 바로 갑니다.
- 스트림이 끝나면 `AsyncContext.complete()`를 한 번 부르고 `onClose` 리스너가 한 번 돕니다. 클라이언트가 소켓을 닫으면 다음 쓰기나 하트비트에서 알아채고 접근 로그에 `cause=client`를 남깁니다.
- 종료 때의 동작과 강제 종료의 예외는 [종료](#종료)에 있습니다.

## WebSocket

Servlet 어댑터는 WebSocket을 지원하지 않습니다. `app.ws` 라우트는 이 어댑터에서 `501`을 답하며, WebSocket은 내장 엔진(`app.listen`)이 제공합니다.

- 올바른 업그레이드 요청(`Upgrade: websocket`, `Connection: Upgrade`, `Sec-WebSocket-Version: 13`, 키)은 `101`도 `500`도 아닌 표준 오류 본문과 요청 ID의 `501`을 받고, 세션은 만들어지지 않습니다.
- 코어가 먼저 답하는 경우는 그대로입니다. WebSocket 전용 경로의 일반 GET·HEAD 요청에 `Upgrade`가 없으면 `Upgrade: websocket`과 함께 `426`, 허용하지 않은 `Origin`은 `403`, GET이 아닌 메서드는 내장 엔진과 같은 답입니다.

## 쓰는 모습

앱 구성은 내장 엔진과 같고, `listen` 대신 configurer에서 돌려준 앱을 컨테이너가 올립니다.

```java
// Same application configuration as with the embedded engine
static void configure(RestApp app) {
    app.onStart(ctx -> ctx.register(UserService.class, new UserService()));
    app.get("/api/users", (req, res) -> res.json(req.service(UserService.class).findAll()));
}
```
