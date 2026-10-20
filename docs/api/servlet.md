# Servlet 모듈 (`NaruServe-servlet`)

`NaruServe-servlet`은 NaruServe 앱을 Tomcat, Jetty 같은 Servlet 컨테이너(WAS) 위에서 실행하는 어댑터입니다. 내장 HTTP 엔진 대신 컨테이너가 연결을 받고, 라우트·미들웨어·`req`/`res` 코드는 **그대로** 씁니다.

> 이 모듈은 사양서에서 **선택 기능(P5)** 입니다. 컨테이너에 등록하는 방법과 클래스 이름은 **(미확정)** 입니다.

## 요구사항

- Servlet **3.1 이상** (`javax.servlet`)
- JVM 전용. Android에서는 쓰지 않습니다.
- `jakarta.servlet`(Servlet 5+)용은 별도 모듈로 나눌 예정입니다(**(미확정)**).

```groovy
// group과 버전은 임시값입니다.
implementation 'com.snoworca.naruserve:NaruServe-servlet:0.1.0-SNAPSHOT'
// Servlet API는 컨테이너가 제공합니다.
compileOnly 'javax.servlet:javax.servlet-api:3.1.0'
```

## 내장 엔진과 다른 점

| 항목 | 내장 엔진 (`app.listen`) | Servlet 모듈 |
|---|---|---|
| 포트·TLS·스레드 풀 | NaruServe 설정 | 컨테이너 설정 |
| 시작·종료 | `app.listen()` / `app.shutdown()` | 컨테이너가 앱을 올리고 내릴 때 (연결 방식 **(미확정)**) |
| 경로 | 요청 경로 그대로 | `getRequestURI()`에서 컨텍스트 경로와 url-pattern 접두사를 뺀 나머지 (퍼센트 인코딩 유지) |
| 파일 업로드 | NaruServe 파서 | Servlet 3.1 multipart 사용 여부와 `app.multipart` 한도 적용 방식 **(미확정)** |
| SSE | 내장 스트리밍 | `AsyncContext` 또는 비차단 쓰기 |
| WebSocket | 내장 | 컨테이너의 WebSocket API. 컨테이너마다 지원 여부를 확인하고 연결 (**(미확정)**) |

## 경로 처리

라우트가 보는 경로(`req.rawPath()`)는 컨테이너가 디코딩·정규화한 `getServletPath()`/`getPathInfo()`가 아니라 원본 `getRequestURI()`에서 컨텍스트 경로와 url-pattern 접두사를 뺀 나머지입니다. Express에서 `app.use('/api', router)` 아래 라우터가 `/api`를 뺀 경로를 보는 것과 같습니다.

- 퍼센트 인코딩은 그대로 두고 코어가 한 번만 디코딩합니다. `;name=value` 경로 파라미터도 경로에 남습니다.
- 컨텍스트 경로 `/app`, 패턴 `/api/*`에서 `/app/api/users/%41;v=1`은 `/users/%41;v=1`이 되고 `/users/:id`의 `id`는 `A;v=1`입니다.
- 패턴 `/*`와 `/`는 컨텍스트 경로 뒤를 그대로 씁니다. 남는 경로가 비면 `/`입니다.
- 원본 URI가 컨텍스트 경로 + 접두사로 경로 구분자(`/`) 경계에서 시작하지 않으면(예: `/app/%61pi/users`) 어댑터가 표준 오류 본문으로 `404`를 답합니다. 앱이 아직 시작되지 않았으면 `503`과 `Connection: close`입니다.
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
- 연결을 끊는 일은 컨테이너 몫입니다. 비정상 종료(`abort`) 뒤의 쓰기는 `IOException`으로 실패하고, 이미 소켓에 막힌 쓰기는 컨테이너의 쓰기 제한 시간이 지나야 끝납니다.

앱 코드는 `javax.servlet` 타입을 보지 않습니다. `Cookie`도 NaruServe `Cookie`이고 `javax.servlet.http.Cookie`가 아닙니다.

## 쓰는 모습

앱 구성은 내장 엔진과 같고, `listen` 대신 컨테이너에 등록합니다.

```java
// 앱 구성: 내장 엔진과 같은 코드
static void configure(RestApp app) {
    app.onStart(ctx -> ctx.register(UserService.class, new UserService()));
    app.get("/api/users", (req, res) -> res.json(req.service(UserService.class).findAll()));
}
```

컨테이너 등록 코드(서블릿 클래스, `web.xml` 또는 `ServletContainerInitializer`)는 모듈 구현 때 확정합니다.
