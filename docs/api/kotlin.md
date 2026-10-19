# Kotlin

NaruServe는 Java로 작성되었지만 Kotlin에서 별도 DSL 없이 씁니다. 콜백이 모두 Java 함수형 인터페이스(`@FunctionalInterface`)라서 Kotlin 람다가 그대로 들어갑니다.

```kotlin
val app = RestApp.create()

app.route(Method.get().post(), "/api/users") { req, res ->
    res.json(req.service(UserService::class.java).findAll())
}

app.ws("/api/ws") { req, ws ->
    ws.onText { message -> ws.sendText("Echo: $message") }
}

app.post("/api/upload") { req, res ->
    req.file("file")?.save(File("/data/upload.bin"))
    res.json(mapOf("success" to true))
}

app.staticFiles("/", File("./www"))
app.listen(8080)
```

## 람다에서 일찍 빠져나오기

람다 안에서 `return`만 쓰면 바깥 함수에서 빠져나가려 해서 컴파일되지 않습니다. 람다를 받은 함수 이름을 붙여 `return@함수이름`을 씁니다.

```kotlin
app.get("/users/:id") { req, res ->
    val user = users.find(req.param("id"))
    if (user == null) {
        res.status(404).end()
        return@get
    }
    res.json(user)
}

app.use("/api") { req, res, next ->
    if (req.header("Authorization") == null) {
        res.status(401).end()
        return@use
    }
    next.run()
}
```

`if/else` 식으로 쓰면 `return@`이 필요 없습니다.

```kotlin
app.get("/users/:id") { req, res ->
    val user = users.find(req.param("id"))
    if (user == null) res.status(404).end() else res.json(user)
}
```

## null 처리

공개 API에는 `@Nullable` / `@NonNull`이 붙어 있어 Kotlin이 타입을 정확히 압니다.

| Java | Kotlin 타입 | 없을 때 |
|---|---|---|
| `req.param("id")` | `String` | 예외 (패턴에 없는 이름) |
| `req.query("page")` | `String?` | `null` |
| `req.form(name)` | `String?` | `null` |
| `req.header(name)` | `String?` | `null` |
| `req.cookie(name)` | `Cookie?` | `null` |
| `req.file(name)` | `UploadFile?` | `null` |
| `req.lastEventId()` | `String?` | `null` |
| `req.attribute(key, type)` | `T?` | `null` |
| `req.queries(name)` | `List<String>` | 빈 리스트 |
| `req.files(name)` | `List<UploadFile>` | 빈 리스트 |
| `req.service(type)` | `T` | 예외 |
| `req.body(type)` | `T?` | 본문이 JSON `null`이면 `null` |

```kotlin
val page = req.query("page")?.toIntOrNull() ?: 1
val token = req.cookie("session")?.value
```

## 타입 인자

Kotlin에서는 `Class` 대신 `::class.java`를 씁니다.

```kotlin
val user: User? = req.body(User::class.java)
val users = req.service(UserService::class.java)
ctx.register(UserService::class.java, UserService(db))
```

제네릭 본문은 Java와 같이 `TypeRef`를 씁니다.

```kotlin
val users: List<User>? = req.body(object : TypeRef<List<User>>() {})
```

`req.body(...)`는 본문이 JSON `null`이면 `null`을 돌려주므로 Kotlin 타입은 `T?`입니다. null 불가 값이 필요하면 `?:`로 처리합니다.

```kotlin
val user = req.body(User::class.java) ?: throw HttpException(400, "JSON body is null")
```

### NaruServe-kotlin 선택 확장

`reified`를 쓰는 Kotlin 확장 함수를 선택 모듈 `NaruServe-kotlin`으로 제공합니다. `kotlin-stdlib`에 의존하는 모듈은 이것 하나뿐입니다.

```groovy
implementation 'com.snoworca.naruserve:NaruServe-kotlin:0.1.0-SNAPSHOT'
```

```kotlin
inline fun <reified T : Any> Request.body(): T = body(T::class.java) ?: throw HttpException(400, "JSON body is null")

val user = req.body<User>()
```

이 확장은 null 불가 `T`를 돌려주고, 본문이 JSON `null`이면 `HttpException`(`400`)을 던집니다. null 불가 값을 달라고 한 호출자에게 JSON `null`은 클라이언트 오류이기 때문입니다. `null`을 받아야 하면 `req.body(User::class.java)`를 쓰세요.
이 확장은 `List<User>`의 원소 타입을 보존하지 않습니다. 제네릭은 `TypeRef`를 쓰세요.

## 프로퍼티 문법

`getX()`/`setX()`/`isX()` 형식의 Java 메서드는 Kotlin 프로퍼티로 씁니다.

```kotlin
val cookie = Cookie("session", token).apply {
    isHttpOnly = true
    secure = true
    maxAge = 3600
}
val committed = res.isCommitted
```

`req.param("id")`, `req.path()`, `res.status(201)`처럼 Express식 짧은 이름은 일반 메서드라 괄호를 붙여 부릅니다.

## JSON

Gson은 Kotlin의 기본 인자와 null 불가 타입을 이해하지 못합니다. [JSON](json.md#kotlin-주의)

권장:

1. Kotlin 앱은 Moshi(codegen)나 kotlinx.serialization으로 `JsonCodec`을 구현해 `app.jsonCodec(...)`에 넘깁니다.
2. Gson을 쓴다면 요청 DTO의 필드를 nullable로 두고 직접 검증합니다.

```kotlin
class CreateUserRequest {
    var name: String? = null
    var email: String? = null
}

app.post("/users") { req, res ->
    val body = req.body(CreateUserRequest::class.java)
    val name = body?.name
    if (name.isNullOrBlank()) {
        res.status(400).json("name is required")
        return@post
    }
    res.status(201).json(users.create(name, body?.email))
}
```

## Coroutine

`suspend` 핸들러는 v1에서 지원하지 않습니다. v1 핸들러는 동기이므로 suspend 함수를 부르려면 `runBlocking`이 필요합니다. 그동안 요청 스레드를 점유하니 오래 걸리는 작업은 피하세요. 프레임워크는 `runBlocking`을 기본 비동기 어댑터로 쓰지 않으며, Coroutine 지원은 별도 모듈로 검토 중입니다.
