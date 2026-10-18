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
| 경로 | 요청 경로 그대로 | 컨텍스트 경로와 서블릿 매핑을 뺀 나머지 (**(미확정)**) |
| 파일 업로드 | NaruServe 파서 | Servlet 3.1 multipart 사용 여부와 `app.multipart` 한도 적용 방식 **(미확정)** |
| SSE | 내장 스트리밍 | `AsyncContext` 또는 비차단 쓰기 |
| WebSocket | 내장 | 컨테이너의 WebSocket API. 컨테이너마다 지원 여부를 확인하고 연결 (**(미확정)**) |

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
