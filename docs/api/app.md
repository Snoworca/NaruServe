# RestApp

[English](en/app.md) | [문서 목록](README.md)

`RestApp`은 Express의 `app`에 해당합니다. 라우트·미들웨어·설정을 등록하고 서버를 시작하고 끕니다. 아래 예제는 API 조각입니다. import와 실행할 수 있는 진입점은 [5분 시작](README.md#5분-시작)을 보세요. `Database`, `UserService` 같은 타입은 앱이 만드는 것입니다.

이 페이지는 **NaruServe 0.11.0**의 일반 HTTP를 설명합니다. 설치는 [설치 안내](README.md)를 따르세요. TinyHttpd는 raw request-target을 그대로 넘기는 3.1.1이고, 기본 Gson은 2.14.0입니다. 코어 API는 Java 8/API 21을 대상으로 하며 기기 성능 검증을 뜻하지 않습니다.

## 앱 만들기

```java
RestApp app = RestApp.create();
```

`create()`는 기본 HTTP 엔진(TinyHttpd)과 기본 JSON 엔진(Gson 2.14.0)을 조립하고 로그는 콘솔 로거로 남깁니다.
이 조립은 기본 bootstrap 모듈 `NaruServe`가 맡습니다. `create()`를 쓰려면 이 모듈을 의존성에 넣습니다. Kotlin은 `NaruServe-kotlin`, Android 앱은 `NaruServe-android`를 넣어도 이 모듈이 함께 들어옵니다. `NaruServe-android`에서는 `create()`가 Logcat 로거를 씁니다.

```groovy
implementation 'com.snoworca.naruserve:naruserve:0.11.0'
```

엔진을 직접 고를 때는 builder를 씁니다. `NaruServe-api`만 의존하는 경우에도 이 방법을 씁니다. 기본 JSON 구현을 코드에서 직접 넘기려면 `new GsonJsonCodec()`을 `jsonCodec(...)`에 넘깁니다. `GsonJsonCodec`은 `NaruServe`에 함께 들어 있고, `NaruServe-api`만 쓸 때는 Gson 모듈 `com.snoworca.naruserve:naruserve-gson`을 넣습니다([JSON](json.md#기본-gson-설정)).

```java
RestApp app = RestApp.builder()
    .httpAdapter(myAdapter)   // HttpAdapter 구현
    .jsonCodec(myCodec)       // JsonCodec 구현
    .build();
```

`RestApp.builder()`는 `httpAdapter(HttpAdapter)`, `jsonCodec(JsonCodec)`, `logger(Logger)`, `build()`를 가지며 각 설정 메서드는 builder를 돌려줍니다. `httpAdapter`와 `jsonCodec`은 꼭 불러야 하고, 빠지면 `build()`가 `IllegalStateException`을 던집니다. `logger`를 부르지 않으면 새 `ConsoleLogger`를 씁니다. `build()`는 부를 때마다 `NEW` 상태의 새 앱을 만듭니다.
`builder()`라는 이름을 고른 이유는 Java·Kotlin에서 가장 흔한 빌더 관례이고 `create()`와 나란히 두면 진입점 두 개가 한눈에 보이기 때문입니다.

`create()`는 클래스패스에서 `com.snoworca.naruserve.spi.AppFactory` 구현을 `ServiceLoader`로 하나 찾아 그 앱을 돌려줍니다. 구현은 `NaruServe` 모듈이 TinyHttpd 어댑터, Gson 코덱과 함께 제공하며, 이 모듈이 클래스패스에 없으면 아래 예외가 납니다. 구현이 없으면 `IllegalStateException`을, 둘 이상이면 구현 클래스 이름을 담은 `IllegalStateException`을 던집니다. 없을 때의 메시지는 다음과 같습니다.

```text
No com.snoworca.naruserve.spi.AppFactory provider found. Add the com.snoworca.naruserve:naruserve module to use RestApp.create(), or assemble the app with RestApp.builder().httpAdapter(...).jsonCodec(...).build()
```

`create()`가 `AppFactory` SPI를 거치는 이유는 `NaruServe-api`가 TinyHttpd와 Gson에 컴파일 의존하지 않고도 `RestApp.create()`를 제공하기 위해서입니다.

```java
public interface AppFactory {   // com.snoworca.naruserve.spi
    RestApp create();
}
```

`RestApp` 하나가 서버 하나입니다. 한 프로세스에 여러 개를 만들 수 있고, 서로 상태를 공유하지 않습니다.

## 메서드 요약

### 라우트

| 메서드 | 설명 |
|---|---|
| `get(path, handler)` | GET 라우트. `HEAD`도 자동으로 받습니다. |
| `post(path, handler)` | POST 라우트 |
| `put(path, handler)` | PUT 라우트 |
| `patch(path, handler)` | PATCH 라우트 |
| `delete(path, handler)` | DELETE 라우트 |
| `head(path, handler)` | HEAD 라우트. 별도 Route끼리는 등록 순서 적용 |
| `options(path, handler)` | OPTIONS 라우트(자동 OPTIONS보다 우선) |
| `get(path, middleware, handler)` 등 | 이 라우트에만 적용할 미들웨어를 붙입니다. |
| `route(Method, path, handler)` | 메서드 하나 |
| `route(MethodSet, path, handler)` | 여러 메서드를 한 핸들러로 |
| `route(path)` | `RouteBuilder`를 돌려줍니다. `.get(h).delete(h2)`로 메서드별 핸들러를 붙입니다. |
| `ws(path, handler)` | WebSocket 엔드포인트. [WebSocket](websocket.md) |

모두 `RestApp`을 돌려주므로 이어서 호출할 수 있습니다(`route(path)`는 `RouteBuilder`를 돌려줍니다). 자세한 규칙은 [라우팅](routing.md).
라우트 미들웨어 오버로드(`get(path, middleware, handler)` 등)도 `RestApp`을 돌려줍니다. 미들웨어를 여러 개 붙일 때는 `route(path).use(m1).use(m2).get(handler)`를 씁니다([미들웨어](middleware.md#등록)).
`ws`의 반환 타입은 다른 라우트 메서드처럼 `RestApp`이므로 이어서 호출할 수 있습니다([WebSocket](websocket.md#핸들러)).

`RouteHandler`는 `(req, res) -> { ... }` 람다입니다.

```java
@FunctionalInterface
public interface RouteHandler {
    void handle(Request req, Response res) throws Exception;
}
```

핸들러는 checked exception을 그대로 던질 수 있습니다. 던진 예외는 [오류 처리](middleware.md#오류-처리)로 갑니다.

### 미들웨어·오류

| 메서드 | 설명 |
|---|---|
| `use(middleware)` | 모든 요청에 실행. 미들웨어가 `Installable`(예: auth)이면 미들웨어로 더하지 않고 `middleware.install(app)`을 불러 스스로 필요한 것을 등록하게 합니다 |
| `use(prefix, middleware)` | `prefix` 경로와 그 하위 경로에만 실행 (`/api`, `/api/users`; `/apix`는 제외) |
| `onError(handler)` | 처리되지 않은 예외를 받습니다. `(error, req, res) -> ...` |

[미들웨어와 오류 처리](middleware.md)

`Installable`은 메서드 하나 `void install(RestApp app)`의 인터페이스입니다. `app.use(x)`는 `x`가 `Middleware`이면서 `Installable`이면 `x.install(app)`을 한 번 부르고 앱을 돌려주며, 스스로는 등록 레코드를 남기지 않습니다(남는 것은 `install`이 등록한 것뿐). `Installable`은 앱 전체에 스스로 붙으므로 `use(prefix, x)`에 `""`·`"/"`가 아닌 경로를 주면 `IllegalArgumentException`을 던집니다. `use("/", x)`는 `use(x)`처럼 install하고(Express 습관 `app.use("/", mw)`), `use("", x)`와 라우터의 `use(x)`는 보통 미들웨어로 더합니다. Java와 Kotlin 모두 `app.use(auth)`처럼 씁니다(`auth.install(app)`과 같음, [auth](auth.md#install-순서)). Express 대응: `app.use(pkg)`가 자기 라우트까지 붙이는 패키지.

### 설정

| 메서드 | 설명 | 문서 |
|---|---|---|
| `jsonCodec(codec)` | JSON 엔진 교체 | [JSON](json.md) |
| `jsonLimit(bytes)` | JSON 요청 본문 한도(기본 1 MiB, 최대 1 GiB) | [JSON](json.md#한도) |
| `formLimit(bytes)` | urlencoded 본문 및 multipart 텍스트 필드 합계 한도(기본 1 MiB, 최대 1 GiB) | [Request](request.md#폼과-파일) |
| `cors(options -> ...)` | HTTP CORS 허용 출처·preflight 설정 | [HTTP CORS](middleware.md#http-cors) |
| `group(prefix, configurer)` | 공통 접두사와 미들웨어로 라우트 묶기 | [라우트 그룹](middleware.md#라우트-그룹) |
| `server(options -> ...)` | 바인딩 주소, 허용 Host, 크기·시간·연결 한도 | [서버 설정](#서버-설정) |
| `trustProxy(boolean)` | `X-Forwarded-For`·`X-Forwarded-Proto` 신뢰(기본 `false`). 맨 왼쪽 값을 쓰므로 위조됩니다 | [서버 설정](#서버-설정) |
| `trustProxyHops(n)` | 오른쪽에서 n번째 `X-Forwarded-For` 항목을 클라이언트 주소로 씁니다(홉 모드) | [프록시 신뢰](#프록시-신뢰) |
| `trustProxyCidrs(cidr...)` | 신뢰 CIDR 안의 프록시만 믿고, 오른쪽부터 그 밖의 첫 주소를 씁니다(CIDR 모드) | [프록시 신뢰](#프록시-신뢰) |
| `onRegistration(listener)` | 이후 등록마다 적용 전에 부르는 등록 감시자 | [등록 목록과 감시자](#등록-목록과-감시자) |
| `registrations()` | 지금까지의 등록 목록(읽기 전용, 모든 상태에서 읽기 가능) | [등록 목록과 감시자](#등록-목록과-감시자) |
| `multipart(config -> ...)` | 업로드 한도·임시 폴더 | [파일 업로드](multipart.md) |
| `webSocket(options -> ...)` | WebSocket `Origin` 허용 목록, 메시지·송신 큐 크기, Ping 주기, idle timeout | [WebSocket](websocket.md#설정) |
| `sse(options -> ...)` | SSE heartbeat·송신 큐 설정. 재접속 지연은 이벤트별 `SseEvent.retry(millis)`로 전송 | [SSE](sse.md) |
| `staticFiles(prefix, dir)` | 정적 파일 폴더 마운트 | [정적 파일](static.md) |
| `staticFiles(prefix, dir, options -> ...)` | 옵션과 함께 마운트 | [정적 파일](static.md) |
| `mimeTypes()` | 전역 MIME 등록부 | [정적 파일](static.md#mime) |
| `defaultCache(policy)` | Cache-Control을 지정하지 않은 응답의 기본 정책. `null`이면 생략 | [정적 파일](static.md#서버-기본-정책) |
| `accessLog(options -> ...)` | 접근 로그 설정 | [로깅](logging.md) |
| `logger(logger)` | 앱 로거 교체 | [로깅](logging.md) |
| `logger()` | 현재 앱 로거 | [로깅](logging.md) |
| `routerPolicy(RouterPolicy)` | 명시한 문자열 슬래시·대소문자 정책과 기존 WS 메서드 게이트 | [라우팅](routing.md#라우터-정책) |

### 서버 설정

`server(options -> ...)`는 `ServerOptions`를 받는 람다를 바로 실행하고 `RestApp`을 돌려줍니다. 여러 번 부르면 차례로 적용됩니다. Express에서 `app.listen(port, host)`와 `http.Server`의 `headersTimeout` 같은 속성으로 흩어져 있던 설정을 한곳에 모은 것입니다. 각 설정 메서드는 `ServerOptions`를 돌려주므로 이어서 부릅니다. 람다가 중간에 예외를 던지면 그 전까지 부른 설정은 이미 적용된 채 남습니다. `server`와 `accessLog`는 `multipart`·`webSocket`·`sse`와 달리 복사본이 아니라 앱의 옵션 객체 하나에 바로 적용하기 때문입니다. 값 범위를 먼저 확인한 뒤 부르세요.

| 설정 | 기본값 | 범위 |
|---|---|---|
| `bindAddress(address)` | `127.0.0.1` | 루프백이 아닌 주소면 `listen()`이 보안 경고를 남깁니다. |
| `allowedHosts(names...)` | 없음 | 바인딩 주소, `localhost`, IP 리터럴 밖에 받을 `Host` 이름 |
| `maxHeaderSize(bytes)` | 16384 | 1..1048576 |
| `maxHeaderCount(count)` | 100 | 1..10000 |
| `maxRequestLineLength(bytes)` | 8190 | 1..65534 |
| `headerTimeout(timeout, unit)` | 10초 | 1 ms..1시간 |
| `bodyReadTimeout(timeout, unit)` | 30초 | 1 ms..1시간 |
| `writeTimeout(timeout, unit)` | 30초 | 1 ms..1시간 |
| `minBodyRate(bytesPerSecond)` | 240 | 1..104857600 |
| `maxWorkers(workers)` | 64 | 1..1024 |
| `maxSseStreams(streams)` | 128 | 1..1024 |
| `maxConnections(connections)` | 512 | 1..65536 |
| `maxUpgradedConnections(connections)` | 128 | 1..65536 |
| `maxConnectionsPerAddress(connections)` | 256 | 1..65536 |

`maxWorkers`는 일반 HTTP 요청에 보장되는 작업 스레드 수입니다. 엔진 스레드 풀은 `maxWorkers + maxSseStreams`이고 SSE 스트림은 그중 `maxSseStreams`개까지만 쓰므로, 열린 SSE 스트림이 없으면 일반 요청이 최대 `maxWorkers + maxSseStreams`개 스레드(기본 192)에서 돌 수 있습니다. 스레드 수를 줄이려면 두 값을 함께 낮추세요.

범위 밖의 값은 설정 이름과 범위를 담은 `IllegalArgumentException`을 던지고, 무제한을 뜻하는 값은 없습니다. `maxConnections`가 `maxWorkers + maxSseStreams`보다 작으면 설정 메서드가 아니라 `listen()`이 `IllegalStateException`을 던집니다. 세 값을 어떤 순서로 바꿔도 되게 하려고 조합 검사는 시작할 때 합니다. 각 한도가 넘쳤을 때의 응답은 [기본값과 보안](defaults.md#시간연결-수-한도)에 있습니다.

같은 LAN의 다른 기기에서 접속하려면 모든 인터페이스에 바인딩하고, 기기 이름으로 접속한다면 그 이름을 허용 Host에 넣습니다.

```java
app.server(options -> options.bindAddress("0.0.0.0").allowedHosts("myphone.local"));
app.server(options -> options.headerTimeout(5, TimeUnit.SECONDS).maxWorkers(16));
```

Kotlin:

```kotlin
app.server { options -> options.bindAddress("0.0.0.0").allowedHosts("myphone.local") }
app.server { options -> options.headerTimeout(5, TimeUnit.SECONDS).maxWorkers(16) }
```

LAN에 열면 인증 여부와 상관없이 `listen()`이 보안 경고 로그를 남깁니다. 로그인·토큰·역할은 [auth](auth.md)를 `app.use(auth)`로 붙이세요.

리버스 프록시 뒤에서는 `app.trustProxy(true)`로 첫 `X-Forwarded-For` 헤더의 맨 왼쪽 값을 `req.remoteAddress()`에, 첫 `X-Forwarded-Proto` 값(`https`이면 `true`)을 `req.isSecure()`에 씁니다. Express의 `app.set('trust proxy', true)`에 해당합니다. 기본은 `false`이고, 값은 검증하지 않고 그대로 쓰므로 그 헤더를 덮어쓰는 프록시 뒤에서만 켜세요. `Forwarded` 헤더는 읽지 않습니다.

`trustProxy(true)`의 맨 왼쪽 값은 검증되지 않은, 클라이언트가 고를 수 있는 값이라 누구나 원하는 주소를 주장할 수 있습니다. 0.10.0 앱과의 호환을 위해 그대로 남겨 두었고, auth 모듈의 doctor는 `PROD`에서 이 설정을 FAIL로 보고합니다(R32). 프록시 뒤에서는 [`trustProxyHops`·`trustProxyCidrs`](#프록시-신뢰)를 쓰세요.

```java
app.trustProxy(true);
```

Kotlin:

```kotlin
app.trustProxy(true)
```

### 프록시 신뢰

프록시 뒤에서 클라이언트 주소를 안전하게 얻으려면 오른쪽부터 세는 두 모드 중 하나를 씁니다. 둘 다 `NEW`에서만 부를 수 있고, `trustProxy`·`trustProxyHops`·`trustProxyCidrs` 중 마지막 호출이 앞의 설정을 바꿉니다. `req.socketAddress()`는 어떤 설정에서도 실제 연결 주소입니다([Request](request.md#요청-정보)). WebSocket 업그레이드 요청도 일반 요청과 같은 설정을 씁니다.

| Express | NaruServe | 뜻 |
|---|---|---|
| `app.set('trust proxy', 1)` | `app.trustProxyHops(1)` | 소켓 상대(로드 밸런서)를 믿고, `X-Forwarded-For`의 오른쪽에서 1번째 항목이 클라이언트 |
| `app.set('trust proxy', 2)` | `app.trustProxyHops(2)` | 프록시 두 단계 뒤 |
| `app.set('trust proxy', 'loopback, 10.0.0.0/8')` | `app.trustProxyCidrs("127.0.0.0/8", "::1", "10.0.0.0/8")` | 신뢰 CIDR 안의 소켓 상대만 믿고, 오른쪽부터 신뢰 CIDR 밖의 첫 항목이 클라이언트 |

- **홉 모드** `trustProxyHops(n)`: `n`이 1 이상이면 소켓 상대를 믿고 `req.remoteAddress()`는 오른쪽에서 `n`번째 항목입니다. 항목이 `n`개보다 적으면 맨 왼쪽 항목입니다(Express와 같음). `0`은 아무것도 믿지 않아 어댑터 값을 그대로 씁니다. 음수는 `IllegalArgumentException`입니다. 이 노드에 로드 밸런서를 거쳐서만 접속할 수 있을 때만 쓰세요. 직접 접속한 클라이언트는 두 헤더를 모두 위조할 수 있습니다.
- **CIDR 모드** `trustProxyCidrs(cidr...)`: 소켓 상대가 신뢰 CIDR 안일 때만 믿고, `req.remoteAddress()`는 오른쪽부터 신뢰 CIDR 밖의 첫 항목입니다. 모든 항목이 신뢰 CIDR 안이면 맨 왼쪽 항목, `X-Forwarded-For`가 없으면 소켓 상대입니다. 믿지 않는 소켓 상대의 전달 헤더는 모두 무시합니다. 로드 밸런서를 거치지 않는 접속을 막을 수 없는 여러 노드 배포에는 이 모드를 권합니다.
- CIDR은 IPv4·IPv6 리터럴과 선택적 `/prefix`입니다(`10.0.0.0/8`, `fd00::/8`). 접두사가 없으면 그 주소 하나입니다. `null` 배열이나 원소는 `NullPointerException`, 그 밖의 값(`10.0.0.0/33`, `abc/8`, 호스트 이름)은 그 값을 담은 `IllegalArgumentException`이고, 이름을 DNS로 찾지 않습니다. 빈 배열은 아무것도 믿지 않습니다. `::ffff:10.0.0.1` 같은 IPv4 매핑 IPv6 주소는 IPv4 CIDR과 비교합니다.
- 두 모드 모두 `X-Forwarded-For` 헤더 줄을 도착 순서대로 합치고, 빈 항목은 건너뜁니다. IP가 아닌 항목은 신뢰 CIDR 밖으로 보고 그대로 돌려줍니다. `Forwarded` 헤더는 읽지 않습니다.
- `X-Forwarded-Proto`는 소켓 상대를 믿을 때만 읽습니다. 값이 하나면 그대로 쓰고(`https`는 대소문자 구분 없음), 여럿이면 클라이언트로 고른 `X-Forwarded-For` 항목과 오른쪽부터 같은 위치의 값을 씁니다. 값이 두 개 이상인데 개수가 `X-Forwarded-For`와 다르거나 그 위치가 없으면 `isSecure()`는 `false`입니다. 믿는 값은 어댑터의 TLS 여부를 양쪽으로 바꿉니다(TLS 연결이어도 `http`면 `false`). 헤더가 없으면 어댑터 값입니다.

nginx는 두 헤더를 이렇게 넘기세요. 프록시가 `X-Forwarded-Proto`를 덮어쓰지 않고 그대로 넘기는 구성은 클라이언트 값과 구별할 수 없어 지원하지 않습니다.

```nginx
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
```

현재 설정은 `trustProxy()`(`boolean`), `trustProxyHops()`(`int`, 홉 모드가 아니면 `0`), `trustProxyCidrs()`(수정할 수 없는 `List<String>`, CIDR 모드가 아니면 빈 목록)로 읽습니다. 셋 다 `null`이 아니고 `listen` 뒤에도 읽을 수 있습니다. 아무 신뢰도 없으면 `false`·`0`·`[]`입니다.

### 라우터 정책

`routerPolicy(RouterPolicy)`는 `RestApp`을 돌려줍니다. 명시하지 않은 일반 HTTP 기본은 대소문자 무시·끝 슬래시 허용이고 반복 슬래시는 의미가 있습니다. 명시한 정책은 자기 case·끝 슬래시·반복 슬래시 값을 적용하며, 모두 false인 정책은 엄격합니다. 기존 라우터/WS의 메서드 게이트는 별개입니다. [라우터 정책](routing.md#라우터-정책)

```java
app.routerPolicy(RouterPolicy.builder().ignoreTrailingSlash(true).build())
   .get("/users", (req, res) -> res.send("users"));   // /users/ 도 맞습니다
```

Kotlin:

```kotlin
app.routerPolicy(RouterPolicy.builder().ignoreTrailingSlash(true).build())
    .get("/users") { req, res -> res.send("users") }
```

### 수명주기

| 메서드 | 설명 |
|---|---|
| `onStart(ctx -> ...)` | 서버가 요청을 받기 전에 한 번 실행. 서비스 생성·등록 |
| `onStop(ctx -> ...)` | 정상 종료 때 한 번 실행. 서비스가 아직 살아 있습니다. |
| `listen(port)` | 서버 시작. `ServerHandle` 반환 |
| `RestApp.start(port, app -> ...)` | `create()`·설정·`listen`을 한 번에 하고 시작 로그와 JVM 종료 훅까지 붙인 정적 진입점. `ServerHandle` 반환 ([start](#start)) |
| `shutdown(timeout, unit)` | 정상 종료 |
| `state()` | 현재 상태: `NEW`, `STARTING`, `RUNNING`, `STOPPING`, `FAILED`, `TERMINATED` |

`onStart`/`onStop`의 콜백은 `LifecycleCallback`(`void handle(ApplicationContext ctx) throws Exception`), `onError`의 콜백은 `ErrorHandler`(`void handle(Throwable error, Request req, Response res) throws Exception`)이며, 둘 다 `RouteHandler`·`Middleware`처럼 `@FunctionalInterface`이고 `throws Exception`을 허용하는 이유는 콜백 안의 I/O 예외를 감싸지 않고 그대로 던지게 하려는 것입니다.
`ws`에 넘기는 콜백 인터페이스는 `WebSocketHandler`(`void handle(Request req, WebSocketSession ws) throws Exception`)이며, 같은 이유로 `@FunctionalInterface`이고 `throws Exception`을 허용합니다([WebSocket](websocket.md#핸들러)).

[수명주기와 서비스](lifecycle.md)

## listen

```java
ServerHandle server = app.listen(8080);
```

- **동기**로 동작합니다. `onStart` 실행 → 라우트 검사 → 포트 바인딩이 모두 끝난 뒤 반환합니다. Express와 달리 콜백을 받지 않습니다.
- 시작이 끝날 때까지 요청을 앱 핸들러로 전달하지 않습니다. 시작 중 연결을 수락한 경우에도 디스패치는 시작 완료를 기다립니다.
- `listen()`이 반환한 뒤 `main`이 끝나도 `shutdown(...)`을 부를 때까지 프로세스가 계속 돕니다. 기본 TinyHttpd 어댑터(`NaruServe-tinyhttpd`)가 daemon이 아닌 keeper 스레드 하나를 `shutdown`까지 띄워 두기 때문이며, TinyHttpd 자체의 스레드는 daemon으로 남습니다. 이렇게 정한 이유는 Express의 `app.listen()`처럼 서버만 띄우는 `main`이 바로 끝나지 않게 하면서, 작업 스레드가 JVM 종료를 막지 않게 하려는 것입니다.
- 바인딩 실패, `onStart` 예외, 잘못된 설정은 **예외로 던집니다**. 이때 포트는 열려 있지 않고, 이미 만든 리소스는 정리됩니다. 일반 HTTP의 중복·겹치는 라우트는 합법적인 순서 등록이고, 기존 WS 충돌은 여전히 시작 오류입니다.
- 기본으로 **`127.0.0.1`에만** 바인딩합니다. LAN에 열려면 [서버 설정](#서버-설정)의 `options.bindAddress("0.0.0.0")`을 씁니다.
- `ServerHandle`은 `port()`(실제 포트. `listen(0)`이면 고른 빈 포트), `address()`(바인딩에 쓴 주소. 설정값 그대로이며 기본 `127.0.0.1`), `state()`(부를 때마다 읽는 앱 상태)를 가집니다. Express의 `server.address().port` 대신 짧은 접근자를 둔 이유는 `req.path()`처럼 다른 API와 같은 이름 규칙을 쓰기 위해서입니다. `url()`은 열어 볼 주소(`http://127.0.0.1:3000`)이고 `0.0.0.0`·`::`에 바인딩했으면 `localhost`로, IPv6 주소는 대괄호로 감쌉니다. `shutdown(timeout, unit)`은 `app.shutdown`과 같은 규칙으로 앱을 끕니다(반복 호출 안전, 음수 timeout은 `IllegalArgumentException`, `null` unit은 `NullPointerException`).
- Android에서는 메인 스레드에서 호출하지 마세요. 작업 스레드에서 호출합니다.

## shutdown

```java
app.shutdown(10, TimeUnit.SECONDS);
```

1. `STOPPING`이 시작됩니다. 앱에 닿는 새 요청과 WebSocket Upgrade는 모두 `503`과 `Connection: close`로 답합니다.
2. 연결을 아직 받는 동안 최대 1초, 열린 SSE 스트림에는 종료 알림을 보내고 열린 WebSocket 세션에는 close `1001`을 보냅니다.
3. 연결 받기를 멈추고 진행 중인 요청이 끝나기를 timeout까지 기다립니다.
4. timeout까지 열려 있는 연결은 강제로 끊습니다.
5. `onStop` 콜백을 실행합니다.
6. `own`/`register`한 `AutoCloseable`을 생성·등록 역순으로 닫습니다.
7. 앱 로거를 flush하고 닫은 뒤 HTTP 어댑터를 놓습니다([로깅](logging.md#종료와-로거)).

여러 번 불러도 안전합니다. 종료된 앱은 다시 `listen`할 수 없습니다.
Android에서는 메인 스레드에서 호출하지 마세요. timeout은 2~4단계(최대 1초 또는 남은 시간까지 걸리는 작별 인사, 진행 중인 요청 대기, timeout 때의 강제 종료)가 함께 나눠 쓰는 하나의 시간이고, 그 뒤의 `onStop`, 리소스 닫기, 로거 flush에는 시간 제한이 없어 timeout보다 오래 막힐 수 있습니다. 작별 인사를 연결을 받는 동안 하는 이유와 반환 시간의 3초 여유는 [수명주기](lifecycle.md#종료-순서)에 있습니다.

## 등록과 설정의 수명주기

일반 HTTP 라우트·미들웨어·Router 등록과 기존 RouteBuilder 콜백은 RUNNING에서도 추가할 수 있습니다. 등록 위치와 순서는 유지하며 종료된 앱에서는 거부합니다. server·코덱·multipart·명시 CORS·group·onError·정적 마운트·WS 같은 설정은 여전히 NEW에서만 바꿉니다. 전체 경로 대안·콜백을 검증한 뒤 등록합니다.
일반 HTTP 중복 경로는 시작 충돌이 아닙니다. 직접 쓰는 기존 라우터·WS의 엄격 충돌 규칙은 유지합니다.

## 예제

```java
RestApp app = RestApp.create();

app.onStart(ctx -> {
    Database db = new Database();
    ctx.own(db);            // 초기화가 실패해도 닫히도록 먼저 소유 등록
    db.connect();
    ctx.register(UserService.class, new UserService(db));
});

app.use((req, res, next) -> {
    req.attribute("requestId", UUID.randomUUID().toString());
    next.run();
});

app.get("/api/users/:id", (req, res) -> {
    res.json(req.service(UserService.class).findById(req.param("id")));
});

app.staticFiles("/", new File("./www"));

ServerHandle server = app.listen(8080);
Runtime.getRuntime().addShutdownHook(new Thread(() -> app.shutdown(10, TimeUnit.SECONDS)));
```

## start

Express의 `app.listen(3000, () => ...)`처럼 한 문장으로 서버를 띄우는 정적 진입점입니다. `app` 변수를 따로 만들지 않고 설정 람다 안에서 라우트를 등록합니다.

```java
ServerHandle handle = RestApp.start(3000, app -> {
    app.get("/hello", (req, res) -> res.send("Hello World"));
});
System.out.println(handle.url());
```

Kotlin:

```kotlin
val handle = RestApp.start(3000) { app ->
    app.get("/hello") { _, res -> res.send("Hello World") }
}
println(handle.url())
```

`start(int port, Configurer<RestApp> routes)`는 다음 순서로 일하고 `ServerHandle`을 돌려줍니다.

1. `RestApp.create()`로 앱을 만듭니다. `NaruServe` 모듈이 없으면 `create()`의 `IllegalStateException`이 그대로 납니다.
2. `routes`를 한 번 실행합니다. 이때 앱은 `NEW`라서 `get`, `use`, `server`, `onStart`, `onStop`, `trustProxy` 같은 설정을 모두 할 수 있습니다. Kotlin에서 람다의 마지막 식이 `app.get(...)`처럼 값을 돌려줘도 무시됩니다.
3. `listen(port)`를 부릅니다.
4. 시작 로그를 한 줄 남깁니다. 예: `Listening on http://127.0.0.1:3000 (this device only). Press Ctrl+C to stop.` `start(0, ...)`이면 실제 포트가 찍힙니다. `0.0.0.0`에 바인딩했으면 `localhost`와 이 기기 IP로 여는 방법을 보여 줍니다.
5. JVM이면 JVM 종료 훅을 등록합니다. Ctrl+C나 SIGTERM이 오면 `Stopping (grace 5s)`를 남기고 `app.shutdown(5, TimeUnit.SECONDS)`를 부르므로 `onStop`이 실행됩니다. 훅은 `handle.shutdown(...)`이나 앱의 `shutdown`으로 끝날 때 다시 지워지고, 시작에 실패하면 등록하지 않습니다. 훅이 부른 종료 중 JVM이 이미 내려가는 중이면 훅 제거 실패는 무시합니다. Android에서는 훅을 등록하지 않고 시작 로그에도 `Press Ctrl+C`가 붙지 않습니다.

돌려받은 `ServerHandle`의 `url()`은 열어 볼 주소(`http://127.0.0.1:3000`)를, `shutdown(timeout, unit)`은 종료를 맡습니다. `start`로 시작하면 `RestApp`을 직접 들고 있지 않으므로 종료는 이 핸들로 합니다.

`start`는 블로킹하지 않습니다. `listen`처럼 바인딩이 끝나면 반환하고, `main`이 끝난 뒤에도 JVM이 계속 도는 것은 `listen`과 같은 이유입니다. `routes`나 `listen`이 예외를 던지면 앱을 `shutdown`으로 정리한 뒤 그 예외를 그대로 던집니다.

| | `start(port, routes)` | `create()` + `listen(port)` |
|---|---|---|
| 설정하는 곳 | `routes` 람다 안(앱이 `NEW`인 동안) | `listen` 앞의 어느 코드든 |
| 시작 로그 | `Listening on ...` 한 줄 | 없음 |
| JVM 종료 훅 | 자동 등록(Android 제외), Ctrl+C·SIGTERM에 `onStop` 실행 | 없음. 직접 `Runtime.addShutdownHook`으로 등록 |
| 블로킹 | 하지 않음 | 하지 않음 |
| 기본 바인딩 | `127.0.0.1`(바꾸지 않음) | `127.0.0.1` |
| LAN에 열기 | `routes` 안에서 `app.server(o -> o.bindAddress("0.0.0.0"))` | `listen` 전에 `app.server(...)` |
| 예외 | `listen`의 예외를 그대로 던짐 | 같음 |

`start`는 기본 포트도, `PORT` 환경변수 읽기도, 바인딩 주소를 바꾸는 인자도 없습니다. Express의 `app.listen(3000)`은 모든 인터페이스에서 받지만 `start(3000, ...)`는 `listen`처럼 이 기기에서만 받습니다. 다른 기기에서 접속하려면 람다 안에서 `app.server(o -> o.bindAddress("0.0.0.0"))`를 호출합니다(루프백이 아니면 `listen`이 보안 경고를 남기는 것도 같습니다).
Android에서는 `start`도 메인 스레드에서 부르지 마세요. `listen`을 포함하므로 바인딩과 `onStart`가 호출 스레드에서 돕니다. Android에서는 종료 훅이 없으니 `Service.onDestroy`에서 `handle.shutdown(...)`을 부릅니다.
테스트나 임베디드처럼 훅이 필요 없으면 `create()`와 `listen`을 그대로 쓰면 됩니다.


## 일반 HTTP 등록 API

`RestApp`과 독립 `com.snoworca.naruserve.Router`는 `HttpRoutes<T>`를 공유합니다. `route(Object)`는 문자열·Java Pattern·`RegexPathPattern`·중첩 배열/리스트 대안을 받습니다. `method(String, Object, Middleware...)`는 확장 메서드 토큰을 등록합니다. 고정 인자 세 인자 콜백 오버로드는 Kotlin 후행 람다를 지원하고 기존 두 인자 `RouteHandler`도 유지합니다.

`use(path, router)`는 live Router 마운트, `param(name, ParamHandler)`는 캡처 전처리, 네 인자 `ErrorMiddleware`는 등록 위치의 오류 처리입니다. `getAsync`·`methodAsync`·`useAsync`는 세 인자 `AsyncRouteHandler`가 Deferred를 반환하는 API입니다. resolve가 next/end를 자동 호출하지 않으며 반환한 pending 결과가 조기 응답 뒤 요청 리소스를 보유합니다. [라우팅](routing.md), [요청 뷰](request.md#마운트와-경로-뷰), [Deferred](middleware.md#deferred와-늦은-작업)를 보세요.

`send("text")`는 계속 text/plain이고 JSON 요청은 타입 지정·지연 파싱을 사용하며 정적 파일·WS·Host·프레이밍 보안은 유지합니다. 이 일반 HTTP 프로필이 Express 전체나 서드파티 미들웨어 API 전체를 뜻하지는 않습니다. RestApp을 직접 구현한 경우 확장된 등록 인터페이스를 반영해야 합니다. Request/Next/exchange의 추가 기본 메서드는 새 abstract 구현 의무를 만들지 않습니다.

## 등록 목록과 감시자

`registrations()`는 지금까지의 등록을 등록 순서대로 담은 읽기 전용 `List<Registration>`입니다. Express의 `app._router.stack`과 비슷하지만 레이어마다 종류가 붙어 있습니다. 부른 시점의 스냅샷이라 다른 스레드가 `listen` 뒤에 등록해도 순회 중에 `ConcurrentModificationException`이 나지 않고, 그 뒤의 등록은 다시 불러야 보입니다. 모든 상태에서 읽을 수 있고 `add`는 `UnsupportedOperationException`입니다.

`onRegistration(listener)`는 등록 감시자를 붙입니다. Express에는 해당 기능이 없습니다. `NEW`에서만 부를 수 있고(`listen` 뒤에는 `IllegalStateException`), `null`은 `NullPointerException`입니다. 등록 한 건은 **검증 → 감시자 → 적용 → 기록** 순서입니다. 입력이 잘못된 등록은 0.10.0과 같은 예외를 던지고 감시자에 오지 않습니다. 감시자는 등록하는 스레드에서 적용 전에 동기로 불리며, 이때 그 등록은 아직 목록에 없고 요청에도 보이지 않습니다. 감시자가 예외를 던지면 그 등록은 적용되지 않고 기록되지 않으며, 예외는 그대로 `get`·`use` 같은 호출자에게 갑니다. 마운트된 Router에 나중에 더한 등록도 같습니다. 감시자가 여럿이면 붙인 순서로 부르고, 하나가 던지면 뒤의 감시자는 부르지 않습니다. 감시자 안에서 다시 등록하거나 `listen`·`shutdown`을 부르면 `IllegalStateException`입니다. 감시자는 모든 앱이 함께 쓰는 등록 잠금을 쥔 채 돌므로 다른 스레드를 기다리면 안 되고, 감시자 안에서 다른 앱의 메서드를 부르면 교착 대신 바로 `IllegalStateException`입니다. 여러 앱에 마운트된 Router의 등록을 뒤 앱의 감시자가 거부해도 앞 앱의 감시자는 이미 그 기록을 본 뒤이며 되돌리는 호출은 없습니다. 종료된(`TERMINATED`) 앱은 자기가 마운트했던 Router의 이후 등록을 더 보지 않습니다. `listen` 뒤에도 받는 등록(라우트, `use`, Router 마운트, `param`, `use(ErrorMiddleware)`)은 감시자를 거쳐 기록되고, `cors`·`ws`·`staticFiles`·`group`·`onError`·`trustProxy`는 여전히 `NEW`에서만 받습니다.

| 종류 | 만드는 API |
|---|---|
| `ROUTE` | `get`·`post`·`put`·`patch`·`delete`·`head`·`options`(모든 오버로드), `route(Method, ...)`·`route(MethodSet, ...)`, `method(...)`, `methodAsync`, `getAsync`, `route(path)` 빌더의 메서드(`use(mw)` 포함) 하나마다 하나 |
| `WEBSOCKET` | `ws(path, handler)` |
| `STATIC` | `staticFiles(...)`. 안에서 미들웨어를 쓰지만 `MIDDLEWARE`로 따로 기록하지 않습니다 |
| `ROUTER_MOUNT` | `use(path, router)`, `use(router)` |
| `GROUP` | `group(prefix, configurer)`. 콜백이 정상으로 끝난 뒤 안의 라우트보다 먼저 기록합니다 |
| `CORS` | `cors(...)`. `MIDDLEWARE`로 따로 기록하지 않습니다 |
| `MIDDLEWARE` | 경로가 `""`·`"/"`인 미들웨어: `use(mw)`, `use("/", mw)`, `useAsync(handler)` |
| `PATH_MIDDLEWARE` | 그 밖의 경로, `Pattern`, 배열을 가진 `use(path, mw)`·`useAsync(path, handler)` |
| `ERROR_MIDDLEWARE` | `use(ErrorMiddleware)`, `use(path, ErrorMiddleware)`, `onError(handler)` |
| `PARAM` | `param(name, handler)` |

| 접근자 | 값 |
|---|---|
| `getKind()` | 위 표의 종류(`RegistrationKind`) |
| `getMethod()` | `ROUTE`의 대문자 메서드 토큰(`"GET"`, `"PURGE"`). `route(MethodSet, ...)` 한 번은 `"GET,POST"`처럼 쉼표로 잇습니다. 빌더의 `use(mw)`와 다른 종류는 `null` |
| `getPath()` | 마운트 경로를 앞에 붙인 전체 문자열 경로(`"/api/users"`). 경로 없는 등록(`use(mw)`, `cors`, `onError`, `param`)은 `""`이고 마운트 안이면 마운트 경로입니다. 이 경로나 감싼 마운트의 경로가 `Pattern`·배열이면 `null` |
| `isStringPath()` | `getPath()`가 `null`이 아니면 `true` |
| `getPathSource()` | 이 호출이 넘긴 경로 객체 그대로(문자열, `Pattern`, 배열). 넘기지 않았으면 `""` |
| `getTarget()` | 호출자가 등록한 객체: 미들웨어, Router, `ErrorMiddleware`, `onError`의 핸들러 자체, `useAsync`의 `AsyncRouteHandler`, `ws`의 `WebSocketHandler`, `param`의 핸들러, `cors`·`group`의 configurer, `staticFiles`의 디렉터리. 라우트는 마지막 콜백(핸들러)입니다. 프레임워크가 만든 래퍼는 아닙니다 |
| `getMount()` | 이 기록을 펼친 `ROUTER_MOUNT` 기록. 앱 수준이면 `null` |

마운트된 Router와 `group` 안의 등록은 마운트 경로를 붙여 펼칩니다. `app.use("/api", r)`은 `ROUTER_MOUNT /api` 뒤에 `r`의 등록을 `r`의 순서대로 `/api/...`로 기록하고, 중첩된 Router는 두 접두사를 모두 붙입니다. 이미 등록을 가진 Router를 마운트하면 마운트 기록과 펼친 기록이 모두 감시자를 거친 뒤에야 마운트가 적용되므로, 하나라도 거부되면 그 Router의 어느 라우트도 열리지 않습니다. 마운트 뒤에(`listen` 뒤 포함) Router에 더한 등록은 목록 끝에 붙고 `getMount()`로 그 Router의 마운트 기록을 가리킵니다. 같은 Router를 두 번 마운트하면 마운트마다 한 번씩 펼칩니다. Router를 자기 안에 마운트하면 `IllegalArgumentException`입니다. `route(path)` 빌더의 `use(mw)`도 빌더 메서드라 `getMethod()`가 `null`인 `ROUTE` 하나로 기록합니다(그 라우트의 모든 메서드에 실행).

```java
app.onRegistration(r -> {
    if (r.getKind() == RegistrationKind.ROUTE && !r.isStringPath()) {
        throw new IllegalStateException("regex routes are not allowed: " + r.getPathSource());
    }
});
app.get("/users", h);
for (Registration r : app.registrations()) {
    System.out.println(r.getKind() + " " + r.getMethod() + " " + r.getPath());
}
```

Kotlin:

```kotlin
app.onRegistration { r ->
    if (r.kind == RegistrationKind.ROUTE && !r.isStringPath) {
        throw IllegalStateException("regex routes are not allowed: ${r.pathSource}")
    }
}
app.get("/users") { _, res -> res.send("users") }
for (r in app.registrations()) println("${r.kind} ${r.method} ${r.path}")
```
