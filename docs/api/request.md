# Request

[English](en/request.md) | [문서 목록](README.md)

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

`remoteAddress()`와 `isSecure()`는 기본으로 `X-Forwarded-For` 같은 프록시 헤더를 보지 않습니다. 리버스 프록시 뒤에서 `app.trustProxy(true)`를 켜면 첫 `X-Forwarded-For` 헤더의 맨 왼쪽 값과 첫 `X-Forwarded-Proto` 값(`https`이면 `true`)을 씁니다. Express의 `app.set('trust proxy', true)`에 해당하며, 값은 검증하지 않으므로 그 헤더를 덮어쓰는 프록시 뒤에서만 켜세요. `Forwarded` 헤더는 읽지 않습니다. [서버 설정](app.md#서버-설정)

## 경로 변수·쿼리·헤더

| 메서드 | 반환 | 없을 때 | Express |
|---|---|---|---|
| `param(name)` | `String` | 예외 (패턴에 없는 이름은 프로그래밍 오류) | `req.params.name` |
| `query(name)` | `String` | `null` | `req.query.name` |
| `queries(name)` | `List<String>` | 빈 리스트 | `req.query.name` (배열) |
| `header(name)` | `String` | `null` | `req.get(name)` |
| `headers(name)` | `List<String>` | 빈 리스트 | — |

- 경로 변수와 쿼리는 URL 디코딩된 문자열입니다. 헤더 값은 URL 디코딩하지 않습니다. 숫자는 아래의 `queryInt`·`paramInt` 등으로 읽거나 직접 검증해 변환합니다.
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

## 입력 검증 편의 함수

| 호출 | 동작 |
|---|---|
| `req.requireQuery("name")` | 쿼리 필수값. 누락·빈 문자열·공백뿐이면 `400` |
| `req.requireHeader("Authorization")` | 헤더 필수값. 누락·빈 값이면 `400` |
| `req.requireForm("name")` | 폼 필수값. 누락·빈 값이면 `400` |
| `req.requireFile("file")` | 필수 업로드. 파일이 없거나 0바이트이면 `400` |
| `req.queryInt("page", 1)` | 쿼리가 없으면 1, 숫자 형식 오류·32비트 범위 초과이면 `400` |
| `req.formInt("count", 1)` | 폼 필드가 없으면 1, 잘못된 숫자는 `400` |
| `req.paramInt("id")` | 경로 변수를 정수로 읽고 잘못된 숫자는 `400` |

숫자 기본값은 누락된 값에만 적용합니다. 빈 문자열, 소수, 공백이 붙은 숫자는 오류입니다. 음수는 유효한 정수이며, 양수만 허용하는 등 업무상 범위는 앱에서 검사합니다. 선언하지 않은 경로 변수 이름은 프로그래밍 오류입니다. 필수 문자열은 검사 후 원래 값을 그대로 반환합니다.

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

위 두 줄은 서로 다른 요청 본문 타입을 읽는 대안입니다. 한 요청에서 두 줄을 연속 실행하면 두 번째 호출은 `BodyAlreadyConsumedException`을 던집니다.

- `Content-Type`이 `application/json` 또는 `application/<이름>+json`(예: `application/merge-patch+json`)일 때만 읽습니다.
- 본문은 UTF-8로만 읽습니다. `charset` 파라미터가 있으면 `utf-8`(대소문자 무관)이어야 하고, 다른 charset(예: `charset=iso-8859-1`)은 Express body-parser처럼 `415`입니다. 잘못된 UTF-8 바이트는 `400`입니다.
- 본문 맨 앞의 UTF-8 BOM(`EF BB BF`) 하나는 기본 Gson 코덱이 건너뛰므로, Express body-parser처럼 BOM이 붙은 본문도 읽힙니다. BOM이 두 번 이상이면 `400`입니다. 직접 만든 `JsonCodec`에서는 BOM 처리를 그 코덱이 정합니다.
- 본문이 JSON `null`이면 `null`을 돌려줍니다(`@Nullable`). 빈 본문은 `null`이 아니라 `400`입니다.
- Express의 `req.body`처럼 타입 없이 아무 Map으로 바꾸지 않습니다. 필요하면 `req.body(Map.class)`를 쓰세요. 기본 Gson에서는 숫자가 `Double`(`42` → `42.0`)로, 중첩 객체가 `Map`으로 들어옵니다.
- v1에는 `jsonTree()`/`jsonMap()`이 없습니다. 타입 없는 값은 `req.body(Map.class)`로 읽습니다. 이렇게 정한 이유는 JSON 트리 타입을 공개 API에 두면 Gson 같은 특정 JSON 라이브러리의 타입이 드러나거나 별도 트리 모델을 하나 더 만들어야 하기 때문입니다.
- JSON 엔진과 설정은 [JSON](json.md).
- `req.body(...)`, `req.cookie(...)`, `req.getCookies()`와 `res.json(...)`, `res.addCookie(...)`는 인터페이스의 default 메서드입니다. 테스트용으로 `Request`/`Response`를 직접 구현하고 이 메서드를 재정의하지 않으면 `UnsupportedOperationException`을 던집니다. 이전 버전에 맞춰 만든 구현이 그대로 컴파일되게 하려는 것입니다.

| 상황 | 결과 |
|---|---|
| `Content-Type`이 JSON이 아님, `charset`이 UTF-8이 아님 | `415` |
| JSON 문법 오류, 값 뒤의 다른 데이터, 빈 본문, 잘못된 UTF-8 | `400` (`BAD_REQUEST`) |
| 본문이 한도(기본 1 MiB, `app.jsonLimit(bytes)`)를 넘음 | `413` |
| 중첩 깊이가 한도(기본 64)를 넘음 | `400` |
| 다른 타입으로 읽거나 JSON·폼을 혼용함 | `BodyAlreadyConsumedException` |

### 폼과 파일

| 메서드 | 반환 | 없을 때 | 설명 |
|---|---|---|---|
| `form(name)` | `String` | `null` | `application/x-www-form-urlencoded` 또는 multipart의 텍스트 필드 |
| `file(name)` | `UploadFile` | `null` | multipart 파일 하나 |
| `files(name)` | `List<UploadFile>` | 빈 리스트 | 같은 이름의 파일 여러 개 |
| `parts()` | `List<UploadFile>` | 빈 리스트 | multipart의 모든 파트를 본문 순서대로. 텍스트 필드도 포함하며, 텍스트 필드는 `filename()`이 `null`입니다 |

`parts()`가 텍스트 필드도 `UploadFile`로 돌려주는 이유는 파트 하나를 한 타입으로 다뤄 본문 순서를 그대로 보여 주기 위해서입니다.

| 설정 | 기본값 | Express | 설명 |
|---|---|---|---|
| `app.formLimit(bytes)` | 1 MiB | `express.urlencoded({ limit })` | urlencoded 본문의 최대 크기이자, 한 요청의 multipart 텍스트 필드 내용 합계 한도입니다. 범위는 1 – 1073741824(1 GiB)이고, 넘는 요청은 `413`입니다 |

`jsonLimit`과 같은 모양으로 `formLimit`이라는 이름을 정한 이유는 Express의 `express.json({ limit })`/`express.urlencoded({ limit })` 짝을 그대로 옮기기 위해서입니다.

```java
app.formLimit(2L * 1024 * 1024);   // 2 MiB

app.post("/profile", (req, res) -> {
    String name = req.form("name");                  // 텍스트 필드
    UploadFile avatar = req.file("avatar");          // 파일 하나, 없으면 null
    List<UploadFile> photos = req.files("photos");   // 같은 이름의 파일 여러 개
    int fields = 0;
    for (UploadFile part : req.parts()) {            // 텍스트 필드도 포함
        if (part.filename() == null) {
            fields++;
        }
    }
    res.json(Collections.singletonMap("photos", photos.size()));
});
```

Kotlin:

```kotlin
app.formLimit(2L * 1024 * 1024)

app.post("/profile") { req, res ->
    val name: String? = req.form("name")
    val avatar: UploadFile? = req.file("avatar")
    val photos: List<UploadFile> = req.files("photos")
    val fields = req.parts().count { it.filename() == null }
    res.json(mapOf("photos" to photos.size))
}
```

[파일 업로드](multipart.md)

### JSON 본문은 같은 타입으로 재사용합니다

요청 본문 스트림은 한 번 읽습니다. 성공한 `body(...)` 결과는 같은 타입이면 null을 포함해 다시 조회할 수 있습니다. 다른 타입으로 읽거나 `form(...)`과 혼용하면 `BodyAlreadyConsumedException`입니다.
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
| `attributeAs(key, Class<T> type)` | 값 읽기. 없으면 `null` |

```java
app.use((req, res, next) -> {
    req.attribute("user", auth.authenticate(req.header("Authorization")));
    next.run();
});

app.get("/me", (req, res) -> {
    User user = req.attributeAs("user", User.class);
    res.json(user);
});
```

요청 속성은 그 요청이 끝나면 사라집니다. 다른 요청과 공유되지 않습니다.
저장된 값이 `type`이 아니면 `ClassCastException`이 납니다. `int.class`나 Kotlin의 `Int::class.java` 같은 기본형 클래스는 래퍼 타입(`Integer`)으로 읽습니다.

`attribute(key, null)`은 캐스팅 없이 값을 지웁니다. 명시적인 삭제는 `removeAttribute(key)`, 타입 지정 조회는 `attributeAs(key, type)`를 사용합니다.

```java
Object none = null;
req.attribute("user", none);                       // "user" 속성을 지웁니다
String left = req.attributeAs("user", String.class); // null
```

Kotlin:

```kotlin
req.attribute("user", null)                // "user" 속성을 지웁니다
val left = req.attributeAs("user", String::class.java)
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

String version = req.application().attributeAs("appVersion", String.class);
```

모든 요청이 같은 서비스 객체를 씁니다. 서비스 안의 공유 상태는 스레드 안전해야 합니다. [수명주기와 서비스](lifecycle.md)

## SSE

| 메서드 | 반환 | 없을 때 |
|---|---|---|
| `lastEventId()` | `String` | `null` |

`Last-Event-ID` 헤더 값입니다. EventSource가 재접속할 때 보냅니다. [SSE](sse.md)
