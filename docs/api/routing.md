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
`RouteBuilder`는 `get`, `post`, `put`, `patch`, `delete`, `head`, `options`를 가집니다. 이 경로에만 적용할 `use(middleware)`는 **(미확정)** 입니다(사양서는 대안으로만 언급).

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
| `Method.any()` | 라우팅에 열린 모든 메서드 |
| `Method.any().except(Method.DELETE)` | DELETE를 뺀 나머지 |

- `Method.get()`, `Method.any()`, `Method.of(...)`는 `Method`의 **정적** 메서드입니다. `Method.GET.delete()` 같은 인스턴스 메서드는 없습니다.
- `MethodSet`에는 `get()`, `head()`, `post()`, `put()`, `patch()`, `delete()`, `options()`, `except(Method)`가 있습니다.
- `TRACE`, `CONNECT`는 기본으로 라우팅에 열리지 않습니다. `Method.any()`에도 들어가지 않습니다. 열려면 명시 정책이 필요합니다(설정 API **(미확정)**).
- Express `app.all(path, h)`는 `app.route(Method.any(), path, h)`입니다(`TRACE`/`CONNECT` 제외).
- 메서드 하나만 쓸 때는 `app.route(Method.GET, "/health", h)`처럼 enum을 바로 넘깁니다.

## 경로 패턴

| 패턴 | 맞는 요청 | 꺼내기 |
|---|---|---|
| `/users` | `/users` | |
| `/users/:id` | `/users/42`, `/users/abc` | `req.param("id")` → `"42"` |
| `/users/:userId/posts/:postId` | `/users/1/posts/9` | `req.param("userId")`, `req.param("postId")` |

- 변수는 `:이름` 한 세그먼트입니다. `/`를 넘지 않습니다.
- 쿼리 문자열은 경로 매칭에 쓰지 않습니다. `req.query(...)`로 읽습니다.
- Express의 `?`, `*`, `+`, `()`, 정규식 경로는 지원하지 않습니다.

### 매칭 규칙

- **대소문자를 구분합니다.** `/Users`는 `/users`에 맞지 않습니다.
- **끝 슬래시를 구분합니다.** `/users/`는 `/users`에 맞지 않습니다.
- `//` 처리, 끝 슬래시, 대소문자 정책은 설정으로 바꿀 수 있습니다(설정 API **(미확정)**).
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

명시한 `head(...)`, `options(...)` 핸들러가 있으면 자동 응답보다 우선합니다.
자동 HEAD 처리 때 `req.method()`가 돌려주는 값(`HEAD`/`GET`)은 **(미확정)** 입니다. 메서드로 분기하는 핸들러는 위 예제처럼 `HEAD`를 `GET`과 함께 처리하세요.
`404`/`405` 응답 본문은 [표준 오류 형식](middleware.md#표준-오류-형식)입니다.

## 정적 파일·WebSocket과의 관계

- 요청은 **명시 라우트 → 정적 파일 마운트 → 404** 순서로 찾습니다. [정적 파일](static.md)
- WebSocket은 `GET` + `Upgrade: websocket` 요청으로 구분합니다. 같은 경로에 일반 `GET` 라우트와 `app.ws`를 함께 둘 수 있습니다.
- `app.ws`만 있는 경로에 `Upgrade` 없이 오면 `426 Upgrade Required`입니다.
- 전역 미들웨어는 이 모두보다 먼저 실행됩니다. [미들웨어](middleware.md)
