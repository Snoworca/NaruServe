# 리버스 프록시

[English](en/proxy.md) | [문서 목록](README.md)

> **버전 범위:** `NaruServe-proxy`는 0.12.0에서 추가된 모듈입니다. 0.11.0에는 없습니다.

`Proxy`는 받은 요청을 백엔드 HTTP 서버 하나로 넘기고 그 응답을 클라이언트에 그대로 돌려주는 리버스 프록시 미들웨어입니다. Express에서 `http-proxy-middleware`로 하던 일입니다.

## 모듈

| 항목 | 값 |
|---|---|
| 모듈 | `NaruServe-proxy` |
| 아티팩트 | `com.snoworca.naruserve:naruserve-proxy` |
| 의존성 | `NaruServe-api`만 |
| 패키지 | `com.snoworca.naruserve.proxy` |

`NaruServe-proxy`는 `NaruServe-api`에만 의존하고 HTTP 엔진을 가져오지 않습니다. 앱을 띄우는 `NaruServe`(또는 다른 어댑터) 옆에 이 아티팩트를 더하세요. 클래스 이름 `Proxy`는 `java.net.Proxy`, `java.lang.reflect.Proxy`와 같으므로 `import com.snoworca.naruserve.proxy.Proxy;`로 패키지를 밝혀 가져오세요.

| Express (`http-proxy-middleware`) | NaruServe |
|---|---|
| `app.use(path, createProxyMiddleware({ target }))` | `app.use(path, Proxy.to(url))` |
| `on: { error: (err, req, res) => ... }`와 `err.code` | `app.onError`와 `ProxyException.kind()`([오류](#오류)) |

## 기본 사용

`Proxy.to(url)`은 프록시를 만드는 유일한 방법입니다. `url`은 `http` 또는 `https` 원점(origin)이고 뒤에 기본 경로를 붙일 수 있습니다(`http://127.0.0.1:8081`, `https://backend.example/v1`). 끝의 `/`는 지웁니다. `null`이면 `NullPointerException`, 쿼리·프래그먼트·사용자 정보가 있는 URL, 다른 스킴, ASCII 이름이나 숫자 네 개를 점 세 개로 이은 IPv4 리터럴(`1.2.3.4`)이나 괄호로 감싼 IPv6 리터럴(zone id 없이)이 아닌 호스트, `1..65535` 밖의 포트, 요청 경로 검사를 통과하지 못하는 기본 경로는 `IllegalArgumentException`입니다. 모두 `Proxy.to`를 부를 때 검사하므로 잘못된 설정은 첫 요청이 아니라 시작할 때 드러납니다.

`Proxy`는 `Middleware`를 구현하므로(`Installable`은 아닙니다) `app.use(path, proxy)`나 라우터의 `use`로 경로에 붙입니다. `Proxy`는 바뀌지 않는(immutable) 값입니다. 옵션 메서드는 모두 새 `Proxy`를 돌려주고 받는 쪽은 그대로 두므로, 설정한 값 하나를 여러 마운트가 나눠 써도 됩니다.

아래 예제는 공통 설정을 담은 `Proxy` 하나를 두 마운트가 함께 씁니다. 모든 요청에 고정 헤더 `X-Gateway-Source`를 붙이고, 읽기 타임아웃은 30초로 줄이고 전체 타임아웃은 10분으로 늘립니다. 결제처럼 같은 요청이 두 번 실행되면 안 되는 경로는 기본값(재시도 없음)으로 먼저 붙이고, 나머지 `/api` 요청은 `retries(2)`로 붙입니다.

```java
import com.snoworca.naruserve.proxy.Proxy;

String url = "http://127.0.0.1:8081";      // 백엔드 주소
Proxy api = Proxy.to(url).readTimeout(30, TimeUnit.SECONDS)
        .totalTimeout(10, TimeUnit.MINUTES)
        .requestHeader("X-Gateway-Source", "gateway");
app.use("/api/payments", api);             // 재시도하면 안 되는 경로: 기본값, 먼저 등록
app.use("/api", api.retries(2));           // 나머지 /api 요청: 재시도 2번
```

Kotlin(`url`은 백엔드 주소 문자열):

```kotlin
val api = Proxy.to(url).readTimeout(30, TimeUnit.SECONDS)
    .totalTimeout(10, TimeUnit.MINUTES)
    .requestHeader("X-Gateway-Source", "gateway")
app.use("/api/payments", api)
app.use("/api", api.retries(2))
```

Kotlin에서도 감싸는 함수나 확장 없이 Java 옵션 체인을 그대로 씁니다.

프록시는 요청을 백엔드로 넘긴 뒤에는 `next()`를 부르지 않습니다. 그래서 같은 접두사 아래에서는 **먼저 등록한 마운트**가 요청을 처리하고, 뒤의 마운트는 그 요청을 보지 못합니다. 위 예제에서 `/api/payments/charge`는 첫 마운트가 재시도 없이 넘기고, `/api` 마운트에는 오지 않습니다. 순서를 바꾸면 `/api` 마운트가 결제 요청까지 재시도합니다. 예외는 WebSocket 업그레이드 요청 하나입니다. 프록시는 WebSocket을 넘기지 않고 `next()`로 넘기므로 같은 경로의 `app.ws` 라우트가 받습니다.

### 넘기는 경로

기본으로는 마운트 접두사를 남긴 원래 경로(`req.originalUrl()`)를 대상 URL의 기본 경로 뒤에 붙여 보냅니다. `stripPrefix(true)`면 접두사를 뺀 나머지(`req.url()`)를 붙입니다. 경로와 쿼리는 클라이언트가 인코딩한 그대로 보냅니다.

| 마운트 | 클라이언트 요청 | 백엔드가 받는 요청 대상 |
|---|---|---|
| `app.use("/api", Proxy.to("http://127.0.0.1:8081"))` | `/api/orders?x=1` | `/api/orders?x=1` |
| `app.use("/api", Proxy.to("http://127.0.0.1:8081").stripPrefix(true))` | `/api/orders?x=1` | `/orders?x=1` |
| `app.use("/api", Proxy.to("http://127.0.0.1:8081/v1").stripPrefix(true))` | `/api/orders` | `/v1/orders` |

Express에서 `app.use('/api', createProxyMiddleware(...))`로 붙이면 `req.url`에서 마운트 경로가 빠지므로(http-proxy-middleware 3.x) `stripPrefix(true)`가 그 동작에 가깝습니다. NaruServe의 기본값은 접두사를 남깁니다.

## 옵션

옵션 메서드는 모두 새 `Proxy`를 돌려주고, 값은 메서드를 부를 때 검사합니다. 시간 옵션은 `(long, TimeUnit)`을 받고 `java.time.Duration`은 받지 않습니다(Android 5.0에는 `java.time`이 없습니다). 소켓에는 밀리초로 올림해서 적어도 1 ms, 많아야 `Integer.MAX_VALUE` ms로 넘기고, 전체 타임아웃은 나노초 정밀도(`Long.MAX_VALUE`에서 포화)로 잽니다.

| 옵션 | 기본값 | 허용 범위와 예외 | http-proxy-middleware |
|---|---|---|---|
| `connectTimeout(long, TimeUnit)` | 10초 | 0보다 커야 합니다. 0 이하는 `IllegalArgumentException`, `unit`이 `null`이면 `NullPointerException` | 대응 옵션 없음 |
| `writeTimeout(long, TimeUnit)` | 60초 | 0보다 커야 합니다. 0 이하는 `IllegalArgumentException`, `unit`이 `null`이면 `NullPointerException` | 대응 옵션 없음 |
| `readTimeout(long, TimeUnit)` | 60초 | 0보다 커야 합니다. 0 이하는 `IllegalArgumentException`, `unit`이 `null`이면 `NullPointerException` | `proxyTimeout` |
| `totalTimeout(long, TimeUnit)` | 5분 | 0보다 커야 합니다. 0 이하는 `IllegalArgumentException`, `unit`이 `null`이면 `NullPointerException` | 대응 옵션 없음(`timeout`은 들어오는 소켓의 유휴 제한입니다) |
| `retries(int)` | 0(재시도 없음) | 0 이상. 음수는 `IllegalArgumentException` | 대응 옵션 없음(재시도 기능이 없습니다) |
| `retryDelay(long, TimeUnit)` | 200밀리초 | 0 이상. 음수는 `IllegalArgumentException`, `unit`이 `null`이면 `NullPointerException` | 대응 옵션 없음 |
| `retryUnsafeMethods(boolean)` | `false` | 모든 값을 받습니다 | 대응 옵션 없음 |
| `stripPrefix(boolean)` | `false`(접두사를 남깁니다) | 모든 값을 받습니다 | `pathRewrite`, 또는 3.x에서 마운트 경로를 빼는 `app.use(path, ...)` |
| `preserveHost(boolean)` | `false`(대상 호스트를 `Host`로 보냅니다) | 모든 값을 받습니다 | `changeOrigin: false`(http-proxy-middleware의 기본값). NaruServe의 기본값은 `changeOrigin: true`에 해당합니다 |
| `bodyLimit(long)` | 앱의 `rawBodyLimit`(기본 1 MiB) | `1..1073741824`(1 GiB). 밖이면 `IllegalArgumentException` | 대응 옵션 없음 |
| `maxConcurrent(int)` | 한도 없음 | 1 이상. 0 이하는 `IllegalArgumentException` | 대응 옵션 없음(`agent`의 `maxSockets`는 넘친 요청을 기다리게 합니다) |
| `requestHeader(String, String)` | 없음 | 이름은 HTTP 토큰이면서 프록시가 관리하는 이름이 아니어야 하고, 값은 헤더 값이어야 합니다. 어기면 `IllegalArgumentException`, `null`이면 `NullPointerException` | `headers: { name: value }` |
| `removeRequestHeader(String)` | 없음 | 이름은 HTTP 토큰이면서 프록시가 관리하는 이름이 아니어야 합니다. 어기면 `IllegalArgumentException`, `null`이면 `NullPointerException` | `on.proxyReq`에서 `proxyReq.removeHeader(name)` |
| `responseHeader(String, String)` | 없음 | 이름은 HTTP 토큰이면서 hop-by-hop 헤더나 `Content-Length`가 아니어야 하고, 값은 헤더 값이어야 합니다. 어기면 `IllegalArgumentException`, `null`이면 `NullPointerException` | `on.proxyRes`에서 `proxyRes.headers[name] = value` |
| `removeResponseHeader(String)` | 없음 | 이름은 HTTP 토큰이면서 hop-by-hop 헤더나 `Content-Length`가 아니어야 합니다. 어기면 `IllegalArgumentException`, `null`이면 `NullPointerException` | `on.proxyRes`에서 `delete proxyRes.headers[name]` |
| `rewriteLocation(boolean)` | `true` | 모든 값을 받습니다 | `autoRewrite`, `hostRewrite`, `protocolRewrite` |
| `sslSocketFactory(SSLSocketFactory)` | JDK 기본 팩토리 | `null`이면 `NullPointerException`. `http` 대상도 받지만 쓰지 않습니다 | `agent`(예: `new https.Agent({ ca })`). `secure: false`에 해당하는 옵션은 없습니다 |

`retries`와 `maxConcurrent`는 `int`, `bodyLimit`은 `long`, 켜고 끄는 옵션은 `boolean`을 받습니다. 설정한 값을 읽는 공개 메서드는 없습니다.

프록시가 관리하는 요청 헤더는 `Connection`, `Keep-Alive`, `Proxy-Connection`, `TE`, `Transfer-Encoding`, `Upgrade`, `Proxy-Authenticate`, `Proxy-Authorization`, `Trailer`(hop-by-hop 헤더), `Host`, `Content-Length`, `Expect`, `Forwarded`, `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host`입니다. 대소문자를 가리지 않으므로 `requestHeader("host", "h")`도 `IllegalArgumentException`입니다. 응답 쪽에서 관리하는 이름은 hop-by-hop 헤더와 `Content-Length`입니다.

### 고정 헤더

- `requestHeader(name, value)`는 그 이름의 헤더를 정확히 한 줄 보냅니다. 클라이언트가 보낸 같은 이름의 줄은 대소문자와 상관없이 모두 대신합니다. 이 줄은 전달용 헤더(`X-Forwarded-*`) 뒤에, 이름마다 남는 마지막 호출의 순서로 붙습니다.
- `removeRequestHeader(name)`은 클라이언트가 보낸 그 이름의 줄을 모두 버립니다.
- 두 옵션은 hop-by-hop 헤더 제거, `Host` 처리, 전달용 헤더 다음에 적용됩니다. 그래서 클라이언트가 `Connection`에 이름을 올린 헤더도, 프록시가 버리는 `X-Real-IP`, `X-Forwarded-Port`, `X-Forwarded-Prefix`도 `requestHeader`로 정하면 그 값이 한 줄 갑니다. 프록시가 더하지 않는 `Via`도 `requestHeader("Via", "1.1 gw")`로 정하면 그대로 보냅니다.
- `responseHeader(name, value)`는 백엔드 헤더를 복사하고 `Location`을 다시 쓴 뒤에 `res.header`로 값을 정하므로, 백엔드 값과 앱이 프록시 앞에서 정한 값을 모두 대신합니다.
- `removeResponseHeader(name)`은 백엔드가 보낸 그 이름의 줄만 버립니다. 앱이 프록시 앞에서 정한 값은 남습니다.
- 이름은 대소문자를 가리지 않고, 같은 이름에 여러 번 부르면(`requestHeader`와 `removeRequestHeader`를 섞어도) 마지막 호출만 남습니다. 응답 쪽도 같습니다.

```java
import com.snoworca.naruserve.proxy.Proxy;

String url = "http://127.0.0.1:8081";
Proxy users = Proxy.to(url)
        .stripPrefix(true)                             // /users/42 → 백엔드의 /42
        .requestHeader("X-Api-Key", "internal-key")    // 클라이언트의 X-Api-Key를 대신합니다
        .removeRequestHeader("Cookie")                 // 쿠키는 백엔드로 보내지 않습니다
        .responseHeader("Cache-Control", "no-store")   // 백엔드 값과 앱 값을 대신합니다
        .removeResponseHeader("Server");               // 백엔드의 Server 줄을 버립니다
app.use("/users", users);
```

Kotlin:

```kotlin
val users = Proxy.to(url)
    .stripPrefix(true)
    .requestHeader("X-Api-Key", "internal-key")
    .removeRequestHeader("Cookie")
    .responseHeader("Cache-Control", "no-store")
    .removeResponseHeader("Server")
app.use("/users", users)
```

## 요청 전달

- 메서드 토큰은 받은 그대로 HTTP/1.1로 보냅니다(확장 메서드 포함). 토큰이 아닌 메서드는 `400`, `CONNECT`와 `TRACE`(대소문자 무관)는 `405`와 `Allow: GET, HEAD, POST, PUT, DELETE, PATCH, OPTIONS`로 백엔드에 연결하지 않고 답합니다.
- WebSocket 업그레이드 요청은 백엔드에 연결하지 않고 `next()`로 넘깁니다. 다른 `Upgrade` 요청은 `Upgrade` 헤더를 빼고 넘깁니다.
- 경로 검사: `..`과 `.` 세그먼트(`%2e%2e` 포함), 인코딩된 `/`·`\`(`%2f`, `%5c`), `NUL`, 인코딩할 필요가 없는 문자를 인코딩한 것(`%70rivate`), 세그먼트 안의 `;`, 위 인코딩을 한 번 더 인코딩한 것(`%252e`, `%252f`), 빈 세그먼트(`//`), 제어 문자·공백·ASCII 밖의 바이트·`#`·잘못된 `%` 인코딩이 있는 요청은 백엔드에 연결하지 않고 `400`입니다. 검사는 `stripPrefix`와 상관없이 원래 경로(`req.originalUrl()`)에 대해 합니다. 연결하는 호스트는 언제나 설정한 대상 하나입니다.
- `Host`는 대상 호스트(기본 포트가 아니면 `:port` 포함)로 바꿔 보내고, 받은 `Host`는 `X-Forwarded-Host`로 보냅니다. `preserveHost(true)`면 받은 `Host`를 그대로 보냅니다. `Host` 줄이 둘 이상이면 `400`입니다.
- 받은 헤더는 순서와 중복을 지켜 보내고 hop-by-hop 헤더, `Connection`에 이름이 오른 헤더, `Expect`는 빼며, 여러 `Cookie` 줄은 `; `로 이어 한 줄로 만듭니다. `Via`는 더하지 않습니다. 요청 줄과 헤더는 ISO-8859-1로 씁니다. 이름이 토큰이 아니거나 값에 쓸 수 없는 문자가 든 헤더, `Content-Length`와 `Transfer-Encoding`이 함께 있는 요청, 하나의 값으로 맞지 않는 `Content-Length` 목록, `chunked` 하나로 끝나지 않는 `Transfer-Encoding`은 `400`이고, `chunked` 앞에 다른 전송 코딩이 있으면 `501`입니다. 모두 백엔드에 연결하기 전에 답합니다.
- 본문은 버퍼에 모으지 않고 흘려 보냅니다. `bodyLimit(n)`이 있으면 `req.bodyStream(n)`, 없으면 `req.bodyStream()`(앱의 `rawBodyLimit`)으로 읽으므로, 선언한 길이가 한도를 넘으면 백엔드에 연결하기 전에 `413`입니다. chunked 본문이 넘기는 도중에 한도를 넘으면 백엔드 연결을 닫고 `413`이며, `onError`가 받는 예외는 원인(`getCause()`)이 `BodyTooLargeException`인 `HttpException`입니다. 앞선 미들웨어가 본문을 이미 읽었으면 연결하지 않고 `500`입니다.

### X-Forwarded-* 헤더

| 헤더 | 클라이언트가 보낸 값 | `.requestHeader(name, value)` |
|---|---|---|
| `X-Forwarded-For` | 클라이언트 연결이 [신뢰한 프록시](app.md#프록시-신뢰)에서 왔으면 받은 줄들의 값을 받은 순서대로 `, `로 이어 한 줄로 만들고 뒤에 소켓 주소를 붙입니다. 아니면 클라이언트 값을 버리고 소켓 주소 하나만 보냅니다. 소켓 주소는 대괄호와 `%zone` 없이 씁니다. 대괄호와 `%zone`을 뺀 소켓 주소가 IPv4·IPv6 리터럴이 아니면(빈 값, `unknown`, 호스트 이름) 신뢰 설정이나 클라이언트 값과 상관없이 이 줄을 보내지 않습니다 | 관리하는 이름이라 호출할 때 `IllegalArgumentException` |
| `X-Forwarded-Proto` | 언제나 버리고 `req.isSecure()`에 따라 `https` 또는 `http`를 보냅니다. 신뢰한 프록시가 보낸 값은 `req.isSecure()`를 거쳐서만 반영됩니다 | 관리하는 이름이라 호출할 때 `IllegalArgumentException` |
| `X-Forwarded-Host` | 신뢰 설정과 상관없이 버리고 어댑터가 알려 준 `Host` 헤더 값을 보냅니다(`Host`가 없으면 보내지 않습니다). 요청 대상의 authority에서 가져오지 않습니다 | 관리하는 이름이라 호출할 때 `IllegalArgumentException` |
| `Forwarded` | 신뢰 설정과 상관없이 버리고, 프록시가 따로 만들지도 않습니다 | 관리하는 이름이라 호출할 때 `IllegalArgumentException` |
| `X-Real-IP`, `X-Forwarded-Port`, `X-Forwarded-Prefix` | 신뢰 설정과 상관없이 버립니다 | 받습니다. 정한 값이 정확히 한 줄 백엔드에 갑니다 |

관리하는 이름은 `removeRequestHeader`에서도 `IllegalArgumentException`입니다. 백엔드가 마운트 경로를 알아야 하면 `.requestHeader("X-Forwarded-Prefix", "/api")`처럼 직접 정하세요. 고정 헤더는 전달용 헤더 다음에 적용되므로 클라이언트가 보낸 `X-Forwarded-Prefix`는 버려지고 정한 값만 갑니다.

Express 대응: `xfwd: true`.

### 주소로 하는 검사는 프록시 뒤에서 믿을 수 없습니다

백엔드가 접근을 주소로 가르면 프록시 뒤에서는 그 검사가 무너집니다.

- 소켓 주소를 보는 백엔드에는 모든 요청이 프록시에서 온 것으로 보입니다(같은 호스트면 `127.0.0.1`). "localhost에서 온 요청만 허용" 같은 검사는 외부 클라이언트에게도 통과됩니다.
- `X-Forwarded-For`의 맨 왼쪽 값을 보는 백엔드는 속을 수 있습니다. 게이트웨이가 클라이언트 연결을 신뢰하는 모드에서는 클라이언트가 보낸 값을 그대로 둔 채 뒤에 주소를 붙이므로, 맨 왼쪽은 클라이언트가 고른 값일 수 있습니다.

그러니 관리용 경로 보호처럼 주소에 기대던 검사는 주소가 아닌 수단으로 옮기세요. 관리자 키를 요구하거나, 밖에 열지 않은 별도 포트로 관리용 앱을 띄우는 방법이 있습니다. 백엔드가 클라이언트 주소를 알아야 하면 게이트웨이만 믿도록 `trustProxyHops(1)`이나 `trustProxyCidrs(...)`를 쓰세요([프록시 신뢰](app.md#프록시-신뢰)). 레거시 `trustProxy(true)`는 프록시와 함께 쓰지 마세요. 이 설정에서는 클라이언트가 주소를 위조할 수 있습니다.

## 응답 전달

- 백엔드의 최종 상태 코드를 그대로 보냅니다. `3xx`도 따라가지 않고 그대로 넘기며 `4xx`·`5xx`도 그대로입니다.
- 백엔드 응답 헤더는 받은 순서와 중복을 지켜 복사합니다. 이름의 첫 줄은 `res.header`로 정해 앱이 정한 값을 대신하고, 뒤의 줄은 `res.append`로 더합니다. `Set-Cookie`와 `Vary`는 언제나 더하므로 앱의 쿠키와 `Vary`가 남습니다.
- hop-by-hop 헤더와 `Connection`에 이름이 오른 헤더는 복사하지 않습니다. `Content-Length`도 복사하지 않고 `res.relay`의 길이 인자로만 넘깁니다. 백엔드가 보내지 않은 `Content-Type`은 더하지 않습니다.
- 본문은 `res.relay`로 흘려 보냅니다. `HEAD` 요청과 `204`·`304` 응답에는 본문을 보내지 않습니다.
- 클라이언트가 보는 `Date` 헤더와 상태 줄의 reason phrase는 백엔드의 것이 아니라 게이트웨이 어댑터가 쓴 것입니다. 백엔드가 `HTTP/1.1 404 Nope`을 보내도 클라이언트는 어댑터가 `404`에 쓰는 표준 문구를 받습니다.
- 백엔드 헤더는 ISO-8859-1로 읽어 그대로 어댑터에 넘깁니다. TinyHttpd 3.1.1은 헤더 문자를 UTF-8로 쓰므로, 백엔드 헤더 값의 바이트 `0xE9`는 클라이언트에 `0xC3 0xA9`로 갑니다. 어댑터에 따른 한계입니다.

**프록시 앞에서 표현 메타데이터 헤더를 정하지 마세요.** 공개 `Response`에는 헤더를 지우는 메서드가 없으므로, 앱이 프록시 앞에서 `Content-Type`, `Content-Encoding`, `ETag` 같은 헤더를 정했는데 백엔드가 그 헤더를 보내지 않으면 앱의 값이 백엔드 본문에 그대로 붙어 나갑니다. 예를 들어 앞선 미들웨어의 `Content-Encoding: gzip`이 압축하지 않은 백엔드 본문에 붙습니다. 이런 헤더는 백엔드가 정하게 두거나 `responseHeader`로 정하세요.

### Location 다시 쓰기

`rewriteLocation`(기본 `true`)이 켜져 있으면, 백엔드의 `Location`이 대상과 원점(스킴·호스트·포트)이 같은 절대 URL(또는 대상의 스킴으로 읽는 `//host/...`)이고 경로가 대상의 기본 경로와 같거나 기본 경로 뒤에 `/`가 이어질 때, 원점과 기본 경로를 클라이언트 쪽 접두사로 바꿉니다. 접두사는 `stripPrefix(true)`면 `req.baseUrl()`(바깥 라우터의 마운트 경로까지 포함해 일치한 접두사)이고 아니면 빈 문자열입니다. 기본 경로 뒤의 나머지 경로·쿼리·프래그먼트는 받은 그대로 두고(퍼센트 인코딩도 풀지 않습니다), 결과 경로가 비면 `/`입니다. 결과에 원점이나 받은 `Host`·`X-Forwarded-Host`를 넣지 않습니다. 상대 참조와 다른 원점의 URL은 그대로 둡니다.

원점을 비교할 때 스킴과 호스트는 대소문자를 가리지 않고, 생략한 포트는 `http`면 80, `https`면 443과 같습니다. 빈 포트(`http://h:/x`의 `:`)는 포트를 생략한 것으로 봅니다. 사용자 정보(`user:pw@`)는 비교하기 전에 버리고 결과에도 넣지 않습니다. 기본 경로는 글자 그대로 비교하므로 기본 경로 `/base`에 `/basement/y`는 이어지지 않습니다. 호스트나 포트가 `Proxy.to`의 문법에 맞지 않는 값(예: 포트 `99999`)은 그대로 둡니다.

다시 쓴 값을 클라이언트가 다른 호스트를 가리키는 네트워크 경로 참조로 읽을 수 있으면, 즉 탭·CR·LF를 빼고 처음 두 글자가 각각 `/`나 `\`이면, 앞에 `/.`를 붙입니다. 그러면 값이 클라이언트 원점의 경로로 남아 열린 리디렉트가 되지 않습니다. 예를 들어 대상 `http://h`에서 `Location: http://h//evil.example/x`는 `//evil.example/x`가 아니라 `/.//evil.example/x`가 됩니다.

| 대상과 마운트 | 백엔드 `Location` | 클라이언트가 받는 `Location` |
|---|---|---|
| `app.use("/api/orders", Proxy.to("http://127.0.0.1:8081"))` | `http://127.0.0.1:8081/api/orders/x` | `/api/orders/x` |
| 같은 마운트에 `stripPrefix(true)` | `http://127.0.0.1:8081/x` | `/api/orders/x` |
| 같은 마운트 | `http://user:pw@127.0.0.1:8081/api/orders/x` | `/api/orders/x` |
| 같은 마운트 | `http://127.0.0.1:8081//evil.example/x` | `/.//evil.example/x` |
| 같은 마운트 | `http://other.example/x` | 그대로 |
| 같은 마운트에 `rewriteLocation(false)` | `http://127.0.0.1:8081/api/orders/x` | 그대로 |

## 오류

### 응답을 보내기 전의 실패

백엔드 쪽 실패는 `ProxyException`으로 앱의 오류 흐름(`onError`, `ErrorMiddleware`)에 갑니다. 프록시는 자기 오류 본문을 쓰지 않습니다. 프록시도 기본 오류 흐름도 이 실패를 앱 로거에 기록하지 않습니다. 기본 오류 흐름은 상태가 `500`인 실패만 기록하고, `ProxyException`은 `502`·`503`·`504`입니다. 기록이 필요하면 `onError`에서 `req.application().logger()`로 직접 기록하세요(아래 예).

`ProxyException`은 `HttpException`의 하위 클래스이고 `kind()`가 실패의 종류(`ProxyException.Kind`)를 돌려줍니다. 상태 코드는 종류에서 정해집니다.

| `kind()` | 상태 | 뜻 |
|---|---|---|
| `CONNECT_FAILED` | 502 | 연결할 수 없음(거부, 이름 풀이 실패, 타임아웃이 아닌 TLS 실패) |
| `CONNECT_TIMEOUT` | 504 | TCP 연결이 연결 타임아웃을 넘기거나 TLS 핸드셰이크가 연결 타임아웃 동안 진척 없이 멈춤 |
| `WRITE_TIMEOUT` | 504 | 백엔드 쓰기가 쓰기 타임아웃 동안 진척 없음 |
| `READ_TIMEOUT` | 504 | 응답을 읽는 동안 읽기 타임아웃을 넘김 |
| `TOTAL_TIMEOUT` | 504 | 요청 전체가 전체 타임아웃을 넘김 |
| `INVALID_RESPONSE` | 502 | 백엔드 응답이 올바른 HTTP 응답이 아니거나 헤더 블록이 끝나기 전에 연결이 끊김 |
| `CONCURRENCY_LIMIT` | 503 | `maxConcurrent` 한도에 걸림 |

- 메시지(`getMessage()`)는 언제나 `null`입니다. 기본 오류 응답이 백엔드 주소를 내보내지 않게 하려는 것입니다. 실패의 원인은 `getCause()`에 있습니다(예: `java.net.ConnectException`, `java.net.UnknownHostException`).
- `onError`가 없을 때의 기본 오류 페이지는 `ProxyException`의 원인을 클래스 이름으로만 보여 주므로 대상 호스트와 포트가 드러나지 않습니다. 원인은 `getCause()`에 그대로 남습니다.
- 프록시가 스스로 답하는 `400`(잘못된 메서드·경로·헤더·프레이밍), `405`(`CONNECT`·`TRACE`), `501`(지원하지 않는 전송 코딩)은 `ProxyException`이 아닌 보통 `HttpException`이고 메시지는 `null`입니다. 한도를 넘은 본문의 `413`도 보통 `HttpException`입니다. 재시도 대기가 인터럽트된 `502`·`503`도 보통 `HttpException`입니다([재시도](#재시도)).
- 공개 생성자 `new ProxyException(kind, cause)`가 있으므로 테스트에서 직접 만들어 `next.error(e)`로 `onError`를 시험할 수 있습니다.

Express 대응: `createProxyMiddleware({ on: { error: (err, req, res) => ... } })`의 `err.code`(`ECONNREFUSED` 등)를 `kind()`로 비교합니다.

`kind()`에 따라 JSON으로 답하고 `ProxyException`을 앱 로거에 기록하는 `onError`:

```java
import com.snoworca.naruserve.proxy.ProxyException;

app.onError((error, req, res) -> {
    if (error instanceof ProxyException) {             // 백엔드 쪽 실패: 502, 503, 504
        ProxyException e = (ProxyException) error;
        req.application().logger().error("upstream " + e.kind().name(), e);   // 원인(getCause())까지 기록합니다
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "upstream");
        body.put("kind", e.kind().name());
        res.status(e.status()).json(body);
    } else if (error instanceof HttpException) {       // 프록시의 400, 405, 501, 413과 재시도 대기가 인터럽트된 502, 503도 여기로 옵니다
        res.status(((HttpException) error).status()).json(Collections.singletonMap("error", "request"));
    } else {
        res.status(500).json(Collections.singletonMap("error", "internal"));
    }
});
```

Kotlin:

```kotlin
app.onError { error, req, res ->
    when (error) {
        is ProxyException -> {
            req.application().logger().error("upstream ${error.kind().name}", error)
            res.status(error.status()).json(mapOf("error" to "upstream", "kind" to error.kind().name))
        }
        is HttpException -> res.status(error.status()).json(mapOf("error" to "request"))
        else -> res.status(500).json(mapOf("error" to "internal"))
    }
}
```

`ProxyException`을 `HttpException`보다 먼저 검사하세요. `ProxyException`도 `HttpException`이기 때문입니다.

### 응답을 보내기 시작한 뒤의 실패

프록시가 응답을 어댑터에 넘긴 뒤 본문을 넘기다가 실패하면(선언한 길이 전에 연결이 끊김, 잘못된 chunk, 본문 바이트 사이의 읽기 타임아웃, 전체 타임아웃) 상태 코드를 바꿀 수 없습니다. 프록시는 그 종류의 `ProxyException`을 앱 로거(`req.application().logger()`)에 한 번 기록하고, 프레임워크가 클라이언트 연결을 중단합니다. 클라이언트가 그때까지 상태 줄과 본문 일부를 받았는지는 어댑터가 얼마나 썼는지에 달려 있습니다. TinyHttpd 어댑터는 응답의 앞부분을 버퍼에 모아 두므로, 넘긴 양이 그 버퍼 안에 있을 때 실패하면(예: `Content-Length: 100`을 선언하고 10바이트를 보낸 뒤 연결을 닫은 백엔드) 클라이언트는 응답 없이 닫힌 연결을 받습니다. 그보다 많이 넘긴 뒤 실패하면(예: 더 긴 길이를 선언하고 64 KiB를 보낸 뒤 연결을 닫은 백엔드) 클라이언트는 상태 줄(`200`)과 중간에 끊긴 본문을 받습니다. 어느 쪽이든 클라이언트가 온전해 보이는 응답을 받는 일은 없습니다.

## 타임아웃

| 옵션 | 기본값 | 재는 구간 | 넘겼을 때의 `kind()` |
|---|---|---|---|
| `connectTimeout` | 10초 | TCP 연결, 그리고 따로 TLS 핸드셰이크 | `CONNECT_TIMEOUT` |
| `writeTimeout` | 60초 | 진척 없이 막힌 백엔드 소켓 쓰기 하나. 클라이언트 본문을 기다리는 시간은 세지 않습니다 | `WRITE_TIMEOUT` |
| `readTimeout` | 60초 | 요청을 보낸 뒤 첫 응답 바이트까지, 그리고 응답 바이트 사이 | `READ_TIMEOUT` |
| `totalTimeout` | 5분 | 첫 시도부터 요청이 끝날 때까지(재시도와 재시도 대기 포함) | `TOTAL_TIMEOUT` |

- 네 타임아웃은 모두 유한합니다. 무제한을 뜻하는 값은 없고 0 이하는 `IllegalArgumentException`입니다.
- 먼저 닿은 타임아웃이 적용됩니다. `readTimeout(60, TimeUnit.SECONDS).totalTimeout(1, TimeUnit.SECONDS)`에서 대답하지 않는 백엔드는 1초 뒤 `TOTAL_TIMEOUT`입니다.
- 응답을 넘기기 전에 닿으면 `504`가 오류 흐름으로 가고, 응답을 넘기기 시작한 뒤에 닿으면 클라이언트 연결을 중단하고 앱 로거에 기록합니다.
- 기본 전체 타임아웃 5분은 긴 다운로드도 자릅니다. 큰 파일을 넘기는 마운트는 `totalTimeout`을 늘리세요.
- 전체 타임아웃도 풀지 못하는 경우가 둘 있습니다. 소켓이 생기기 전의 이름 풀이(DNS)는 JDK 리졸버를 따르고, 클라이언트와의 I/O에 막혀 있는 시간(천천히 도착하는 요청 본문, 응답을 천천히 읽는 클라이언트)은 어댑터의 시간 제한에 맡깁니다. 천천히 도착하는 본문은 마감 뒤 첫 본문 읽기에서 `TOTAL_TIMEOUT`으로 끝납니다.

큰 파일을 넘기는 마운트:

```java
import com.snoworca.naruserve.proxy.Proxy;

String url = "http://127.0.0.1:8081";
app.use("/downloads", Proxy.to(url)
        .totalTimeout(2, TimeUnit.HOURS)               // 5분 기본값은 긴 다운로드를 자릅니다
        .connectTimeout(3, TimeUnit.SECONDS));
```

Kotlin:

```kotlin
app.use("/downloads", Proxy.to(url)
    .totalTimeout(2, TimeUnit.HOURS)
    .connectTimeout(3, TimeUnit.SECONDS))
```

Express 대응: `proxyTimeout`은 `readTimeout`처럼 나가는 요청의 소켓 유휴 제한이고, 나머지 셋에 해당하는 옵션은 없습니다.

## 재시도

재시도는 기본으로 꺼져 있습니다(`retries(0)`). `retries(n)`을 주면 실패한 요청을 새 연결로 최대 `n`번 더 보냅니다.

- TCP 수준의 연결 실패(연결 거부, 이름 풀이 실패, 경로 없음) 뒤에는 모든 메서드를 재시도합니다. 백엔드가 요청을 받지 못한 경우이기 때문입니다. 연결 타임아웃과 TLS 실패는 재시도하지 않습니다.
- 백엔드가 `502`나 `503`으로 답하면, 백엔드에 보낸 본문이 0바이트(chunked가 아니고 길이가 0)이고 메서드가 `GET`, `HEAD`, `OPTIONS`일 때만 재시도합니다. `retryUnsafeMethods(true)`면 본문이 0바이트인 모든 메서드를 재시도합니다. 본문이 있는 요청은 본문을 다시 보낼 수 없으므로 재시도하지 않습니다.
- 쓰기·읽기·전체 타임아웃, `INVALID_RESPONSE`, 그 밖의 상태 코드는 재시도하지 않습니다.
- 재시도 전마다 `retryDelay`(기본 200밀리초)를 기다립니다. 그 대기가 전체 타임아웃의 마감을 넘기게 되면 기다리지 않고 마지막 실패를 돌려줍니다.
- 마지막 재시도 뒤에도 `502`나 `503`이면 그 백엔드 응답을 그대로 넘깁니다.
- 요청 스레드가 인터럽트되면 재시도를 멈추고 인터럽트 상태를 유지합니다. 결과는 인터럽트된 때에 따라 다릅니다.
- 재시도를 정할 때 인터럽트 상태가 이미 켜져 있으면 재시도하지 않습니다. 마지막 실패는 그대로 오류 흐름으로 가고 `502`·`503` 백엔드 응답은 그대로 넘깁니다. 시도하는 도중에 인터럽트되면 그 시도의 응답을 그대로 넘깁니다.
- 연결 실패 뒤의 재시도 대기가 인터럽트되면 그 연결 실패를 던집니다(`CONNECT_FAILED`, 대기 중에 마감이 지났으면 그 마감의 종류).
- `502`나 `503`을 받고 그 연결을 닫은 뒤의 재시도 대기가 인터럽트되면 백엔드 응답을 넘기지 않습니다. 응답은 이미 닫혔으므로 그 상태의 보통 `HttpException`(`ProxyException`이 아님)이 오류 흐름(`onError`)으로 갑니다. 대기 중에 마감이 지났으면 그 마감 종류의 `ProxyException`이 갑니다.

**안전한 메서드는 HTTP 의미의 구분일 뿐입니다.** 부수 효과가 있는 `GET` 엔드포인트(예: `GET /api/payments/charge?id=1`)는 재시도로 두 번 실행될 수 있습니다. 그런 경로는 [기본 사용](#기본-사용)의 예제처럼 재시도를 끈 프록시를 따로 두고 일반 마운트보다 먼저 등록하세요.

Express 대응: http-proxy-middleware에는 재시도 기능이 없습니다.

## 동시 요청 한도

`maxConcurrent(n)`은 처리 중인 요청 수의 한도를 정합니다. 기본은 한도가 없습니다.

- `maxConcurrent`로 만든 객체와 그 객체에서 다른 옵션 메서드로 파생한 객체들은 카운터 하나를 함께 씁니다.
- 파생한 객체에 `maxConcurrent`를 다시 부르면 새 카운터가 생깁니다.
- `Proxy.to`를 따로 불러 만든 `Proxy`는 같은 대상이라도 카운터를 나누지 않습니다.
- 한도가 없는 마운트의 요청은 세지 않습니다.
- 같은 객체를 두 앱에 붙이면 두 앱의 요청을 함께 셉니다.
- 한도를 넘는 요청은 기다리지 않고 곧바로 `503`(`CONCURRENCY_LIMIT`)이며 백엔드에 연결하지 않습니다.
- 슬롯은 모든 검사와 `req.bodyStream(...)`을 통과한 뒤 백엔드에 연결하기 직전에 잡고, 요청이 끝날 때(응답 본문을 다 넘기거나 클라이언트가 떠난 것을 어댑터가 알았을 때) 돌려줍니다. 어댑터가 클라이언트가 떠난 것을 알지 못하는 동안에는 읽기 타임아웃이나 전체 타임아웃이 요청을 끝낼 때까지 슬롯이 잡혀 있습니다([스레드 모델](#스레드-모델)).

```java
import com.snoworca.naruserve.proxy.Proxy;

String url = "http://127.0.0.1:8081";
Proxy p = Proxy.to(url).maxConcurrent(16);
app.use("/search", p);                               // p와
app.use("/export", p.retries(0));                    // p.retries(0)은 한도 16을 함께 씁니다
app.use("/other", Proxy.to(url).maxConcurrent(16));  // 따로 만든 객체는 자기 한도 16을 따로 씁니다
```

Kotlin:

```kotlin
val p = Proxy.to(url).maxConcurrent(16)
app.use("/search", p)
app.use("/export", p.retries(0))
app.use("/other", Proxy.to(url).maxConcurrent(16))
```

Express 대응: 없습니다. `agent`의 `maxSockets`가 가장 가깝지만 넘친 요청을 거절하지 않고 기다리게 합니다.

## 스레드 모델

- 프록시는 요청 스레드에서 동기로 돕니다. 프록시를 거치는 요청은 끝날 때까지 작업 스레드 하나를 잡고 있습니다.
- 프록시가 백엔드를 기다리는 동안 클라이언트가 연결을 끊어도, 어댑터는 응답을 보내기 시작할 때에야 그것을 알 수 있습니다. 그래서 스레드는 백엔드가 답하거나 읽기 타임아웃이나 전체 타임아웃이 끝날 때까지 묶여 있습니다.
- 응답 본문을 넘기는 도중에 백엔드가 말이 없어진 뒤 클라이언트가 연결을 끊어도 같습니다. TinyHttpd 어댑터는 클라이언트에 무언가를 써야 연결이 끊긴 것을 알 수 있으므로, 슬롯과 작업 스레드와 백엔드 소켓은 읽기 타임아웃이나 전체 타임아웃이 넘기기를 끝낼 때까지 잡혀 있습니다. 백엔드가 계속 보내는 동안 클라이언트가 끊으면 다음 쓰기에서 알아채고 곧바로 돌려줍니다.
- 느린 백엔드는 작업 스레드 풀(`maxWorkers`, 기본 64)을 다 써서 프록시가 아닌 라우트까지 기다리게 만들 수 있습니다. 읽기 타임아웃을 길게 잡을수록 더 그렇습니다.
- 기본으로는 `maxConcurrent` 한도가 없으므로, 프록시가 잡는 스레드 수를 묶는 것은 `maxWorkers`와 `totalTimeout`입니다. 느릴 수 있는 백엔드에는 `maxConcurrent`로 마운트마다 한도를 두세요.

## 백엔드 연결

- 프록시는 `java.net.Socket` 위의 자체 HTTP/1.1 클라이언트로 백엔드와 이야기합니다(`HttpURLConnection`이나 `java.net.http`를 쓰지 않습니다).
- 백엔드 요청마다(재시도도 시도마다) 새 연결을 열고 `Connection: close`를 보내며, 연결을 다시 쓰지 않습니다. 백엔드가 연결을 열어 두어도 응답과 슬롯 반환을 늦추지 않습니다.
- **처리량이 높으면 포트가 바닥날 수 있습니다.** 연결마다 닫힌 소켓이 TIME_WAIT 상태로 남으므로, 초당 요청이 많으면 프록시 호스트의 임시 포트(ephemeral port)가 바닥날 수 있습니다. 근본 해결은 연결 풀이며 이 버전에는 없습니다.
- 쓰기 타임아웃과 전체 타임아웃은 JVM 전체에 하나뿐인 스케줄러가 지킵니다. 첫 프록시 요청이 필요할 때 만들고, 앱 수명주기 밖에 있어 앱을 멈춰도 멈추지 않습니다. 스레드는 데몬 스레드 하나이며 프록시 요청이 없으면 10초쯤 쉬다가 끝나므로(늦어도 30초 안에), 종료 훅이 필요 없습니다.

## https 백엔드

`https` 대상에는 평문 소켓을 연결 타임아웃으로 연결한 뒤 TLS를 씌웁니다(SNI에 대상 호스트 이름이 실립니다). 인증서 체인 검사와 호스트 이름 검사는 언제나 켜져 있고 끄는 옵션이 없습니다. 호스트 이름은 `sslSocketFactory`를 주었을 때도 핸드셰이크 뒤에 프록시가 직접 확인합니다. 와일드카드 `*`는 맨 왼쪽 레이블 전체로만 쓰여 레이블 하나에 맞고, IP 리터럴 대상은 `iPAddress` 대체 이름에만 맞으며, 주체의 CN으로 되돌아가지 않습니다. 검사가 실패하면 요청 바이트를 보내지 않고 `502`(`CONNECT_FAILED`)입니다.

사설 CA를 믿게 하려면 그 CA를 담은 `SSLSocketFactory`를 넘기세요.

```java
import com.snoworca.naruserve.proxy.Proxy;
import javax.net.ssl.SSLContext;

SSLContext tls = SSLContext.getDefault();   // 실제로는 사설 CA를 담은 TrustManager로 초기화한 SSLContext
app.use("/internal", Proxy.to("https://backend.internal:8443").sslSocketFactory(tls.getSocketFactory()));
```

Kotlin:

```kotlin
val tls = SSLContext.getDefault()
app.use("/internal", Proxy.to("https://backend.internal:8443").sslSocketFactory(tls.socketFactory))
```

Express 대응: `agent: new https.Agent({ ca })`. 검사를 끄는 `secure: false`에 해당하는 옵션은 없습니다.

## 접근 로그

프록시를 거친 요청도 다른 요청과 같은 형식으로 접근 로그에 남습니다. `in=`에는 백엔드로 넘긴 요청 본문 바이트 수가 들어갑니다.
