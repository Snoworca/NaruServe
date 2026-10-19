# 라우팅

## 라우트 등록 세 가지 방법

| 방법 | 언제 | 예 |
|---|---|---|
| `app.get(path, handler)` | 메서드 하나에 핸들러 하나 | `app.get("/users", list)` |
| `app.route(path).get(h1).delete(h2)` | 같은 경로에 메서드별로 다른 핸들러 | 아래 참고 |
| `app.route(MethodSet, path, handler)` | 여러 메서드를 한 핸들러에서 분기 | 아래 참고 |

### 메서드별 핸들러 (Express `app.route`와 같음)

```java
app.route("/users/:id")
   .get(getUser)
   .put(updateUser)
   .delete(deleteUser);
```

`app.get(path, getUser); app.put(path, updateUser); app.delete(path, deleteUser);`와 같습니다.
`RouteBuilder`는 `get`, `post`, `put`, `patch`, `delete`, `head`, `options`와 `use(middleware)`를 가지며 모두 같은 `RouteBuilder`를 돌려줍니다. `use(middleware)`는 라우트 미들웨어를 여러 개 붙이는 방법으로, `app.route("/admin").use(auth).use(adminOnly).get(h)`는 `auth` → `adminOnly` → `h` 순서로 실행하고 `use` 뒤에 등록하는 메서드에만 적용됩니다. [미들웨어](middleware.md#등록)
가변 인자 오버로드 대신 `RouteBuilder.use`를 고른 이유는 Java의 가변 인자가 맨 끝에만 올 수 있어 `get(path, Middleware..., RouteHandler)`를 만들 수 없기 때문입니다.

### 한 핸들러로 여러 메서드

```java
app.route(Method.get().post().delete(), "/users/:id", (req, res) -> {
    switch (req.method()) {
        case GET:
        case HEAD:   res.json(find(req.param("id"))); break;
        case POST:   res.status(201).json(create(req)); break;
        case DELETE: remove(req.param("id")); res.status(204).end(); break;
        default:     res.status(405).end();
    }
});
```

Kotlin:

```kotlin
app.route(Method.get().post().delete(), "/users/:id") { req, res ->
    when (req.method()) {
        Method.GET, Method.HEAD -> res.json(find(req.param("id")))
        Method.POST -> res.status(201).json(create(req))
        Method.DELETE -> { remove(req.param("id")); res.status(204).end() }
        else -> res.status(405).end()
    }
}
```

## Method와 MethodSet

`Method`는 HTTP 메서드 enum입니다.

```java
public enum Method { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE, CONNECT }
```

`MethodSet`은 메서드의 **불변** 집합입니다. 조합 메서드는 항상 새 집합을 돌려줍니다.

| 코드 | 집합 |
|---|---|
| `Method.get()` | `{GET}` |
| `Method.get().post()` | `{GET, POST}` |
| `Method.get().delete().post()` | `{GET, DELETE, POST}` |
| `Method.of(Method.PUT, Method.PATCH)` | `{PUT, PATCH}` |
| `Method.any()` | `{GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS}` (`TRACE`/`CONNECT`는 정책으로 켜도 빠짐) |
| `Method.any().except(Method.DELETE)` | DELETE를 뺀 나머지 |

- `Method.get()`, `Method.any()`, `Method.of(...)`는 `Method`의 **정적** 메서드입니다. `Method.GET.delete()` 같은 인스턴스 메서드는 없습니다.
- `MethodSet`에는 `get()`, `head()`, `post()`, `put()`, `patch()`, `delete()`, `options()`, `except(Method)`가 있습니다. `with(Method)`는 임의의 메서드(`TRACE`, `CONNECT` 포함)를 더한 새 집합을 돌려줍니다.
- 이미 들어 있는 메서드를 더하거나 없는 메서드를 빼면 같은 값의 집합이 돌아옵니다. `Method.of(...)`에 `null` 배열이나 `null` 원소를 넘기면 `NullPointerException`입니다.
- `TRACE`, `CONNECT`는 기본으로 라우팅에 열리지 않습니다. `Method.any()`에도 들어가지 않습니다. 열려면 [`app.routerPolicy(...)`](#라우터-정책)에서 `enableTrace(true)`/`enableConnect(true)`를 켭니다.
- Express `app.all(path, h)`는 `app.route(Method.any(), path, h)`입니다(`TRACE`/`CONNECT` 제외).
- 메서드 하나만 쓸 때는 `app.route(Method.GET, "/health", h)`처럼 enum을 바로 넘깁니다.

### MethodSet 조회

| 메서드 | 돌려주는 값 |
|---|---|
| `contains(Method)` | 그 메서드가 집합에 있으면 `true` |
| `iterator()` | 멤버를 `Method` 선언 순서(`ordinal()`)로 돌려주는 반복자. `remove()`는 `UnsupportedOperationException` |
| `size()` | 멤버 수 |

- `MethodSet`은 `Iterable<Method>`라서 for 문에 바로 씁니다.
- 같은 멤버를 가진 두 집합은 `equals`가 `true`이고 `hashCode`가 같습니다. `Method.get().post()`와 `Method.of(Method.POST, Method.GET)`은 같습니다.
- `toString()`은 멤버를 선언 순서로 `", "`로 이어 붙입니다(`"GET, POST"`). `Allow` 헤더 값과 같은 형태입니다.
- Express에는 대응하는 API가 없습니다. Express는 메서드 집합 타입을 두지 않습니다.

```java
MethodSet set = Method.of(Method.DELETE, Method.GET, Method.POST);
boolean canPost = set.contains(Method.POST);   // true
for (Method m : set) {                         // GET, POST, DELETE 순서
    System.out.println(m);
}
int n = set.size();                            // 3
```

Kotlin (`contains`는 `in` 연산자로, `iterator()`는 for 문으로 씁니다):

```kotlin
val set = Method.get().post().except(Method.POST)
val canGet = Method.GET in set                 // true
for (m in Method.of(Method.DELETE, Method.GET)) {  // GET, DELETE 순서
    println(m)
}
val n = set.size()                             // 1
```

## 경로 패턴

| 패턴 | 맞는 요청 | 꺼내기 |
|---|---|---|
| `/users` | `/users` | |
| `/users/:id` | `/users/42`, `/users/abc` | `req.param("id")` → `"42"` |
| `/users/:userId/posts/:postId` | `/users/1/posts/9` | `req.param("userId")`, `req.param("postId")` |

- 변수는 `:이름` 한 세그먼트입니다. `/`를 넘지 않습니다.
- 변수 이름은 `[A-Za-z_][A-Za-z0-9_]*`이고 세그먼트 전체를 차지해야 합니다. `/:id.json`, `/:a-b`, `/:from-:to`는 등록할 때 설정 오류입니다. Express 4는 `/:id.json`처럼 변수 뒤에 고정 글자를 붙일 수 있지만 NaruServe는 허용하지 않습니다. `/files/:name`으로 받아 핸들러에서 나누세요.
- 고정 세그먼트는 디코딩된 글자로 씁니다(`/café`). 퍼센트 인코딩(`/a%20b`), 그대로인 공백, 제어 문자, `#`, `.`·`..` 세그먼트는 어떤 요청에도 맞을 수 없으므로 등록할 때 설정 오류입니다. Express는 `/a%20b`를 인코딩된 경로와 비교하므로 옮길 때 고쳐야 합니다.
- 쿼리 문자열은 경로 매칭에 쓰지 않습니다. `req.query(...)`로 읽습니다.
- Express의 `?`, `*`, `+`, `()`, 정규식 경로는 지원하지 않습니다.

### 매칭 규칙

- **대소문자를 구분합니다.** `/Users`는 `/users`에 맞지 않습니다.
- **끝 슬래시를 구분합니다.** `/users/`는 `/users`에 맞지 않습니다.
- `//` 처리, 끝 슬래시, 대소문자 정책은 [`app.routerPolicy(RouterPolicy)`](#라우터-정책)로 바꿉니다.
- URL은 **정확히 한 번** 디코딩합니다. 잘못된 퍼센트 인코딩, `NUL`, 경로 이탈(`..`)이 있는 요청은 거부합니다.

### 우선순위

고정 세그먼트가 변수보다 먼저 맞습니다. 등록 순서와 상관없습니다.

```java
app.get("/users/:id", getUser);
app.get("/users/me", getMe);     // /users/me 요청은 항상 여기로
```

다음과 같은 등록은 `listen()`할 때 **설정 오류**입니다.

- 같은 메서드 + 같은 경로를 두 번 등록
- 우선순위가 같아서 어느 쪽인지 정할 수 없는 패턴 (`/a/:x`와 `/a/:y`를 같은 메서드로)

## 자동 응답

| 상황 | 응답 |
|---|---|
| 맞는 경로가 없음 | `404` |
| 경로는 있지만 메서드가 없음 | `405` + `Allow` 헤더 |
| `GET` 라우트에 `HEAD` 요청 | GET 핸들러를 실행하고 본문은 보내지 않음 |
| `OPTIONS` 요청 (핸들러 없음) | `204` + `Allow` 헤더 |
| 서버가 모르는 메서드 | `501` |
| 앱이 종료 중(`STOPPING`) | `503` + `Connection: close`, 본문은 [표준 오류 형식](middleware.md#표준-오류-형식)(`SERVICE_UNAVAILABLE`) |
| 대기 큐가 가득 참(과부하) | `503`. TinyHttpd가 전송 계층에서 바로 보내는 평문 본문이며 라우팅·미들웨어를 거치지 않습니다 |

명시한 `head(...)`, `options(...)` 핸들러가 있으면 자동 응답보다 우선합니다.
자동 HEAD 처리 때 `req.method()`는 `HEAD`를 돌려줍니다. GET 핸들러가 실행되더라도 `GET`이 아닙니다. 메서드로 분기하는 핸들러는 위 예제처럼 `HEAD`를 `GET`과 함께 처리하세요.
`404`/`405` 응답 본문은 [표준 오류 형식](middleware.md#표준-오류-형식)입니다.

## 정적 파일·WebSocket과의 관계

- 요청은 **명시 라우트 → 정적 파일 마운트 → 404** 순서로 찾습니다. [정적 파일](static.md)
- WebSocket은 `GET` + `Upgrade: websocket` 요청으로 구분합니다. 같은 경로에 일반 `GET` 라우트와 `app.ws`를 함께 둘 수 있습니다.
- `app.ws`만 있는 경로에 `Upgrade` 없이 오면 `426 Upgrade Required`입니다(`OPTIONS`는 자동 OPTIONS 응답). 자세한 순서는 [찾기 결과와 순서](#찾기-결과와-순서)를 보세요.
- 전역 미들웨어는 이 모두보다 먼저 실행됩니다. [미들웨어](middleware.md)

## 라우터 정책

`app.routerPolicy(RouterPolicy)`로 경로 형태 정책과 `TRACE`/`CONNECT`를 켭니다. 정책은 `RouterPolicy.builder()`로 만듭니다. Express의 `app.set('strict routing', ...)`, `app.set('case sensitive routing', ...)`에 해당하지만 기본값이 다릅니다(NaruServe는 끝 슬래시와 대소문자를 모두 구분하는 엄격 기본값).

| builder 메서드 | 켜면 | Express |
|---|---|---|
| `mergeSlashes(boolean)` | `/a//b`를 `/a/b`로 봅니다 | (없음) |
| `ignoreTrailingSlash(boolean)` | 끝 슬래시 하나를 무시합니다. `/`는 그대로 | `strict routing` 꺼짐(Express 기본) |
| `caseInsensitive(boolean)` | 고정 세그먼트를 대소문자 없이 비교합니다 | `case sensitive routing` 꺼짐(Express 기본) |
| `enableTrace(boolean)` | `TRACE` 라우트를 등록하고 라우팅합니다 | (없음) |
| `enableConnect(boolean)` | `CONNECT` 라우트를 등록하고 라우팅합니다 | (없음) |

- 모든 스위치의 기본은 꺼짐입니다. `RouterPolicy.strict()`는 모두 꺼진 정책이고, `routerPolicy`를 부르지 않은 앱의 정책입니다.
- 다른 설정처럼 앱 상태가 `NEW`일 때(`listen()` 전)만 부를 수 있습니다. 라우트 테이블을 고정한 뒤(`listen()` 뒤)에 부르면 `IllegalStateException`입니다. `null`을 넘기면 `NullPointerException`입니다.
- 다시 부르면 앞의 정책을 바꿉니다. 정책은 라우트 테이블을 고정할 때 모든 라우트에 적용되므로 라우트 등록 전후 어디서 불러도 같습니다.
- `RouterPolicy`는 불변입니다. `build()`는 그때까지 정한 값을 복사합니다.

```java
app.routerPolicy(RouterPolicy.builder()
    .mergeSlashes(true)
    .ignoreTrailingSlash(true)
    .caseInsensitive(false)
    .enableTrace(false)
    .enableConnect(false)
    .build());
```

Kotlin:

```kotlin
app.routerPolicy(
    RouterPolicy.builder()
        .ignoreTrailingSlash(true)
        .caseInsensitive(true)
        .build()
)
```

## 라우팅 규칙 (wave 2 결정)

라우터가 지키는 규칙을 모았습니다. 정책은 `app.routerPolicy(RouterPolicy)`로 켜고 끕니다([라우터 정책](#라우터-정책)).

### 경로 형태 정책

세 가지 정책 스위치가 있고, 모두 기본으로 꺼져 있습니다(**엄격 기본값**). 각각 따로 켤 수 있고 라우터를 만들 때 고정됩니다.

| 스위치 | 켜면 | 기본(꺼짐) |
|---|---|---|
| 반복 슬래시 합치기 | `/a//b`가 `/a/b`에 맞음 | `/a//b`는 `/a/:x/b`에도 `/a/b`에도 맞지 않음 |
| 끝 슬래시 무시 | `/users/`가 `/users`에, `/users`가 `/users/` 패턴에 맞음. 루트 `/`는 빈 경로로 줄이지 않음. `/users//`는 반복 슬래시 합치기도 켜야 `/users`에 맞음 | `/users/`는 `/users/` 패턴에만 맞음 |
| 대소문자 무시 | 고정 세그먼트를 로캘과 무관하게 비교(`/USERS/Bob`이 `/users/:name`에 맞고 `name` = `Bob`) | `/Users`는 `/users`에 맞지 않음 |

경로 변수 값은 어느 스위치에서도 받은 그대로(한 번 디코딩한 값) 돌려줍니다.

### TRACE와 CONNECT

- 기본으로 `TRACE`, `CONNECT`는 경로와 상관없이 **지원하지 않는 메서드** 결과입니다.
- 정책으로 각각 따로 켭니다. 켠 메서드는 다른 메서드처럼 라우팅되지만 이름으로 등록해야만 들어갑니다(`Method.of(Method.TRACE)`). `Method.any()`로 등록한 라우트는 켠 `TRACE`/`CONNECT`를 처리하지 않습니다.
- 꺼진 상태에서 `TRACE`/`CONNECT` 라우트를 등록하면 라우트 테이블을 고정할 때 설정 오류입니다.

### 찾기 결과와 순서

찾기는 메서드 토큰, 원래(디코딩 전) 경로, 업그레이드 표시를 받아 다음 중 **하나**를 돌려줍니다. 위에서부터 먼저 해당하는 것이 이깁니다.

| 순서 | 결과 | 언제 |
|---|---|---|
| 1 | 지원하지 않는 메서드 | 토큰이 `Method` 상수 이름과 정확히 같지 않음, 또는 꺼진 `TRACE`/`CONNECT` |
| 2 | 맞는 경로 없음 | 어떤 종류의 패턴도 경로에 맞지 않음(`OPTIONS`도 이 결과) |
| 3 | 매칭 | 요청 메서드의 라우트가 맞음 |
| 4 | 자동 OPTIONS | `OPTIONS` 요청인데 `OPTIONS` 라우트가 없음 |
| 5 | 자동 HEAD | `HEAD` 요청인데 `HEAD` 라우트가 없고 HTTP `GET` 라우트가 맞음(WebSocket 라우트는 쓰지 않음) |
| 6 | WebSocket 전용 | 업그레이드 표시 없는 `GET`/`HEAD`인데 맞는 라우트에 WebSocket은 있고 HTTP `GET`은 없음, 또는 맞는 라우트가 WebSocket뿐 |
| 7 | 메서드 불일치 | 경로는 맞지만 요청 메서드의 라우트가 없음 |

- WebSocket 전용 결과는 wave 3 디스패처가 `426 Upgrade Required`(`Upgrade: websocket`, `Connection: Upgrade`)로 응답합니다.
- 업그레이드 표시가 있는 `GET`은 WebSocket 라우트를 먼저 찾습니다. 맞는 WebSocket 패턴이 없으면 업그레이드 없이 보통 `GET`으로 찾습니다(`GET` 라우트, 아니면 메서드 불일치나 맞는 경로 없음). 업그레이드 표시가 없으면 WebSocket 패턴은 매칭되지 않습니다.

### Allow 집합

메서드 불일치와 자동 OPTIONS 결과는 같은 `Allow` 집합을 가집니다.

- 경로에 맞는 모든 라우트의 메서드 합집합입니다. WebSocket 라우트는 `GET`으로 셉니다.
- `GET`이 있으면 `HEAD`를 더하고, `OPTIONS`는 항상 더합니다.
- 예: `Method.any().except(Method.DELETE)`를 `/r`에 등록하면 `DELETE /r`의 `Allow`는 `GET, HEAD, POST, PUT, PATCH, OPTIONS`입니다. `POST /p`만 있으면 `GET /p`의 `Allow`는 `POST, OPTIONS`입니다(`HEAD` 없음).

### 메서드별 우선순위

- 우선순위는 **요청 메서드의 라우트끼리만** 비교합니다. 왼쪽부터 세그먼트를 비교해 처음 다른 세그먼트에서 고정 세그먼트가 변수를 이깁니다. 등록 순서와 상관없습니다.
- `GET /a/:x/c`와 `GET /a/b/:y`가 있으면 `GET /a/b/c`는 `/a/b/:y`로 갑니다.
- `GET /users/me`와 `DELETE /users/:id`가 있으면 `DELETE /users/me`는 `DELETE /users/:id`로 갑니다. 다른 메서드의 더 구체적인 패턴은 고려하지 않습니다.

### 패턴 검사와 충돌

- 등록할 때 패턴을 검사합니다. Express 문법(`?`, `*`, `+`, `(`, `)`), 빈 변수 이름(`/a/:`), 한 패턴 안에서 반복한 변수 이름(`/a/:id/:id`), 빈 세그먼트(`/a//b`), 규칙에 맞지 않는 변수 이름(`/:id.json`), 맞을 수 없는 고정 세그먼트(`/a%20b`, `/a b`, `/a#b`, `/a/..`)는 그 자리에서 설정 오류입니다. `/`와 끝 슬래시 하나(`/users/`)는 허용합니다.
- 충돌은 라우트 테이블을 고정할 때(`listen()` 안, 소켓을 열기 전) 한꺼번에 검사합니다. 같은 라우트 종류, 같은 메서드에서 패턴이 같거나 변수 이름만 다르면 충돌이고, 오류 메시지는 충돌한 두 등록의 메서드와 패턴을 모두 적습니다.
- 충돌은 켜진 정책으로 정규화한 패턴으로 비교합니다. 대소문자 무시가 켜지면 `GET /Users`와 `GET /users`가, 끝 슬래시 무시가 켜지면 `GET /a`와 `GET /a/`가 충돌합니다. 엄격 기본값에서는 둘 다 충돌이 아닙니다.
- 같은 경로의 WebSocket 라우트와 `GET` 라우트는 충돌이 아닙니다.

### 메서드 토큰과 업그레이드 표시

- 메서드 토큰은 `Method` 상수 이름과 대소문자까지 정확히 비교합니다. `get`, `Get`, `FOO`, `PROPFIND`는 경로와 상관없이 지원하지 않는 메서드입니다.
- 업그레이드 표시는 `GET` 요청이고, `Connection` 헤더에 `upgrade` 토큰이, `Upgrade` 헤더에 `websocket` 토큰이 있을 때만 켜집니다. 두 헤더 모두 쉼표로 나눈 목록 안에서 대소문자 없이 비교합니다(`Connection: keep-alive, Upgrade`와 `Upgrade: WebSocket`은 켜짐, `Upgrade: h2c`나 `POST`는 꺼짐).

### 경로 거부와 디코딩

찾기 전에 원래 경로를 검사하고, 아래에 해당하면 요청을 거부합니다. 쿼리 문자열(첫 `?`부터)은 검사와 매칭에 쓰지 않습니다.

- 잘못된 퍼센트 인코딩(`/a%2`, `/a%zz`), UTF-8이 아닌 인코딩 바이트(`/a%C3`, `/a%FF`)
- 인코딩된 `/`(`%2F`, `%2f`), 인코딩되거나 그대로인 `NUL`(`%00`)
- 그대로 또는 인코딩된 `.`, `..` 세그먼트(`/./a`, `/a/..`, `/a/%2e`, `/a/.%2e/b`). `/a/.hidden`은 허용합니다.
- 그대로인 공백, 제어 문자(U+0001..U+001F, U+007F), `#`, U+007F보다 큰 문자

통과한 경로는 세그먼트마다 UTF-8로 **정확히 한 번** 퍼센트 디코딩합니다. `+`는 `+` 그대로입니다. `/files/%252e`를 `/files/:name`에 맞추면 `name`은 `%2e`이고, `/caf%C3%A9`는 고정 패턴 `/café`에 맞습니다.
