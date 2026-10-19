# Request

`Request`는 Express의 `req`에 해당합니다. Express는 `req.params.id`처럼 속성으로 읽지만, NaruServe는 `req.param("id")`처럼 메서드로 읽습니다.

`Request` 객체는 그 요청 안에서만 씁니다. 응답이 끝난 뒤 다른 스레드에 넘겨 쓰면 안 됩니다.

## 요청 정보

| 메서드 | 반환 | Express | 설명 |
|---|---|---|---|
| `method()` | `Method` | `req.method` | `Method.GET` 등 enum |
| `path()` | `String` | `req.path` | 디코딩된 경로. 쿼리 문자열 제외 (Express `req.path`는 퍼센트 디코딩하지 않습니다) |
| `rawPath()` | `String` | `req.path` (디코딩 전) | 디코딩 전 경로. 쿼리 문자열 제외(쿼리는 `req.query(...)`로 읽습니다). |
| `remoteAddress()` | `String` | `req.ip` | 접속한 상대 주소 |
| `isSecure()` | `boolean` | `req.secure` | TLS 연결이면 `true` |

`remoteAddress()`와 `isSecure()`는 기본으로 `X-Forwarded-For` 같은 프록시 헤더를 보지 않습니다. 프록시를 신뢰하도록 명시 설정할 때만 반영합니다(설정 API **(미확정)**).

## 경로 변수·쿼리·헤더

| 메서드 | 반환 | 없을 때 | Express |
|---|---|---|---|
| `param(name)` | `String` | 예외 (패턴에 없는 이름은 프로그래밍 오류) | `req.params.name` |
| `query(name)` | `String` | `null` | `req.query.name` |
| `queries(name)` | `List<String>` | 빈 리스트 | `req.query.name` (배열) |
| `header(name)` | `String` | `null` | `req.get(name)` |
| `headers(name)` | `List<String>` | 빈 리스트 | — |

- 값은 모두 디코딩된 문자열입니다. 숫자가 필요하면 직접 변환합니다.
- 같은 이름이 여러 번 오면 `query`/`header`는 첫 번째 값을, `queries`/`headers`는 모든 값을 돌려줍니다.
- 헤더 이름은 대소문자를 구분하지 않습니다.

```java
// GET /users/42/posts?page=2&tag=a&tag=b
app.get("/users/:id/posts", (req, res) -> {
    String id = req.param("id");                    // "42"
    String pageText = req.query("page");            // "2"
    int page = pageText == null ? 1 : Integer.parseInt(pageText);
    List<String> tags = req.queries("tag");         // ["a", "b"]
    String token = req.header("authorization");     // 대소문자 무시
    res.json(postService.find(id, page, tags));
});
```

잘못된 숫자로 `Integer.parseInt`가 실패하면 `NumberFormatException`이 오류 처리로 가서 `500`이 됩니다. 사용자 입력 오류는 직접 검사해 `400`으로 답하세요.

## 본문

### JSON

| 메서드 | 설명 |
|---|---|
| `body(Class<T> type)` | JSON 본문을 `type`으로 읽습니다. |
| `body(TypeRef<T> type)` | `List<User>` 같은 제네릭 타입으로 읽습니다. |

```java
User user = req.body(User.class);
List<User> users = req.body(new TypeRef<List<User>>() {});
```

- `Content-Type`이 `application/json`(또는 허용된 `+json` 타입)일 때만 읽습니다.
- Express의 `req.body`처럼 타입 없이 아무 Map으로 바꾸지 않습니다. 필요하면 `req.body(Map.class)`를 쓰세요. 기본 Gson에서는 숫자가 `Double`(`42` → `42.0`)로, 중첩 객체가 `Map`으로 들어옵니다. `req.jsonTree()`/`req.jsonMap()`은 선택 기능으로 검토 중입니다(**(미확정)**).
- JSON 엔진과 설정은 [JSON](json.md).

| 상황 | 결과 |
|---|---|
| `Content-Type`이 JSON이 아님 | `415` |
| JSON 문법 오류 | `400` (`BAD_REQUEST`) |
| 본문이 한도(기본 1 MiB)를 넘음 | `413` |
| 중첩 깊이가 한도(기본 64)를 넘음 | `400` |
| 본문을 이미 읽음 | `BodyAlreadyConsumedException` |

### 폼과 파일

| 메서드 | 반환 | 없을 때 | 설명 |
|---|---|---|---|
| `form(name)` | `String` | `null` | `application/x-www-form-urlencoded` 또는 multipart의 텍스트 필드 |
| `file(name)` | `UploadFile` | `null` | multipart 파일 하나 |
| `files(name)` | `List<UploadFile>` | 빈 리스트 | 같은 이름의 파일 여러 개 |
| `parts()` | `List<…>` | 빈 리스트 | 모든 파트. 원소 타입과 텍스트 필드 포함 여부는 **(미확정)** |

[파일 업로드](multipart.md)

### 본문은 한 번만 읽습니다

요청 본문은 한 가지 방식으로 한 번만 읽습니다. `body(...)`로 읽은 뒤 다시 `body(...)`나 `form(...)`으로 읽으면 `BodyAlreadyConsumedException`입니다.
같은 방식의 `form`/`file`/`files`/`parts`는 처음 호출할 때 한 번 파싱하고 그 결과를 함께 씁니다.

```java
User user = req.body(User.class);   // 한 번 읽고
validate(user);                     // 변수로 넘깁니다
service.save(user);
```

크기 한도는 파싱을 시작하기 전부터 적용됩니다. 한도는 [기본값과 보안](defaults.md).

## Cookie

| 메서드 | 반환 | 없을 때 |
|---|---|---|
| `cookie(name)` | `Cookie` | `null` |
| `getCookies()` | `Cookie[]` | 빈 배열 |

```java
Cookie session = req.cookie("session");
if (session != null) {
    String token = session.getValue();
}
```

요청 쿠키에는 `HttpOnly`, `Secure`, `SameSite` 같은 속성이 오지 않습니다. 브라우저는 이름과 값만 보냅니다. 요청에서 읽은 `Cookie`의 그런 속성값을 믿지 마세요. [Cookie](cookie.md)

## 요청 속성

Express에서 `req.user = user`나 `res.locals.user = user`로 미들웨어끼리 값을 넘기던 것을 `attribute`로 합니다.

| 메서드 | 설명 |
|---|---|
| `attribute(key, value)` | 값 저장. `value`가 `null`이면 지웁니다 |
| `attribute(key, Class<T> type)` | 값 읽기. 없으면 `null` |

```java
app.use((req, res, next) -> {
    req.attribute("user", auth.authenticate(req.header("Authorization")));
    next.run();
});

app.get("/me", (req, res) -> {
    User user = req.attribute("user", User.class);
    res.json(user);
});
```

요청 속성은 그 요청이 끝나면 사라집니다. 다른 요청과 공유되지 않습니다.
저장된 값이 `type`이 아니면 `ClassCastException`이 납니다. `int.class`나 Kotlin의 `Int::class.java` 같은 기본형 클래스는 래퍼 타입(`Integer`)으로 읽습니다.

값을 지우려면 타입이 있는 `null` 변수를 저장합니다. `req.attribute("user", null)`처럼 `null` 리터럴을 넘기면 Java가 읽기 오버로드 `attribute(key, Class<T> type)`를 고르고, `type`이 `null`이라 `NullPointerException`이 납니다. `(Object) null`로 캐스팅해도 지워집니다.

```java
Object none = null;
req.attribute("user", none);                       // "user" 속성을 지웁니다
String left = req.attribute("user", String.class); // null
```

Kotlin:

```kotlin
req.attribute("user", null as Any?)                // "user" 속성을 지웁니다
val left = req.attribute("user", String::class.java)
```

## 서비스와 앱 전역 값

| 메서드 | 반환 | 설명 |
|---|---|---|
| `service(Class<T> type)` | `T` | `onStart`에서 등록한 서비스. 없으면 `ServiceNotFoundException` |
| `application()` | `ApplicationContext` | 앱 전역 속성과 서비스 저장소 |

```java
app.get("/users", (req, res) -> {
    UserService users = req.service(UserService.class);
    res.json(users.findAll());
});

String version = req.application().attribute("appVersion", String.class);
```

모든 요청이 같은 서비스 객체를 씁니다. 서비스 안의 공유 상태는 스레드 안전해야 합니다. [수명주기와 서비스](lifecycle.md)

## SSE

| 메서드 | 반환 | 없을 때 |
|---|---|---|
| `lastEventId()` | `String` | `null` |

`Last-Event-ID` 헤더 값입니다. EventSource가 재접속할 때 보냅니다. [SSE](sse.md)
