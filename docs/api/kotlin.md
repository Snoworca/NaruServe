# Kotlin

[한국어](kotlin.md) | [English](en/kotlin.md)

NaruServe는 Java로 작성되었지만 Kotlin에서 별도 DSL 없이 씁니다. 콜백이 모두 Java 함수형 인터페이스(`@FunctionalInterface`)라서 Kotlin 람다가 그대로 들어갑니다.

이 문서는 0.10.0 체크아웃을 설명합니다. 아래 0.9.0 의존성은 이전 배포 예제이며 새 0.10.0 API를 제공하지 않습니다. [현재 설치 절차](README.md)를 따르세요. 코어는 Java 8/API 21 호환이며 Kotlin 라이브러리는 선택 Kotlin 모듈에 둡니다.

```kotlin
val app = RestApp.create()

app.route(Method.get().post(), "/api/users") { req, res ->
    res.json(req.service(UserService::class.java).findAll())
}

app.ws("/api/ws") { req, ws ->
    ws.onText { message -> ws.sendText("Echo: $message") }
}

val uploadDir = File("./uploads")
check(uploadDir.isDirectory || uploadDir.mkdirs()) { "Cannot create upload directory: $uploadDir" }
app.post("/api/upload") { req, res ->
    val file = req.file("file")
    if (file == null || file.size() == 0L) throw HttpException(400, "Missing file")
    file.save(File(uploadDir, "${java.util.UUID.randomUUID()}.bin"))
    res.status(201).json(mapOf("size" to file.size()))
}

app.staticFiles("/", File("./www"))
app.listen(8080)
```

위 코드와 아래 예제는 앱 구성 일부입니다. `UserService`, `users`, DTO와 `File` 등은 앱에서 정의하거나 import합니다. 전체 import와 `main`은 [Kotlin 예제](../../examples/kotlin/src/main/kotlin/example/KotlinExample.kt)를 보세요.

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

공개 API는 `@NullMarked`(기본 non-null)이고 null일 수 있는 값에만 `@Nullable`이 붙어 있어 Kotlin이 타입을 정확히 압니다.

| Java | Kotlin 타입 | 없을 때 |
|---|---|---|
| `req.param("id")` | `String` | 실제 매칭에서 빠진 선택 값도 예외 |
| `req.paramOrNull("id")` | `String?` | null |
| `req.paramValues("parts")` | `List<String>` | 빈 리스트 |
| `req.query("page")` | `String?` | `null` |
| `req.form(name)` | `String?` | `null` |
| `req.header(name)` | `String?` | `null` |
| `req.cookie(name)` | `Cookie?` | `null` |
| `req.file(name)` | `UploadFile?` | `null` |
| `req.lastEventId()` | `String?` | `null` |
| `req.attributeAs(key, type)` | `T?` | `null` |
| `req.queries(name)` | `List<String>` | 빈 리스트 |
| `req.files(name)` | `List<UploadFile>` | 빈 리스트 |
| `req.service(type)` | `T` | 예외 |
| `req.body(type)` | `T?` | 본문이 JSON `null`이면 `null` |

```kotlin
val page = req.queryInt("page", 1)
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

`reified`를 쓰는 Kotlin 확장 함수를 선택 모듈 `NaruServe-kotlin`으로 제공합니다. `kotlin-stdlib`은 이 모듈만 사용합니다. 이 모듈은 기본 조립 모듈 `NaruServe`(TinyHttpd + Gson)를 포함하므로, Kotlin 프로젝트는 이 모듈 하나로 `RestApp.create()`까지 씁니다.

```groovy
implementation 'com.snoworca.naruserve:naruserve-kotlin:0.9.0'
```

```kotlin
import com.snoworca.naruserve.kotlin.body

inline fun <reified T : Any> Request.body(): T = body(object : TypeRef<T>() {}) ?: throw HttpException(400, "JSON body is null")

val user = req.body<User>()
```

이 확장은 null 불가 `T`를 돌려주고, 본문이 JSON `null`이면 `HttpException`(`400`)을 던집니다. null 불가 값을 달라고 한 호출자에게 JSON `null`은 클라이언트 오류이기 때문입니다. `null`을 받아야 하면 `req.body(User::class.java)`를 쓰세요.
이 확장은 `List<User>`와 `Map<String, List<User>>` 같은 중첩 제네릭의 원소 타입도 보존합니다. 예: `val users = req.body<List<User>>()`.

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

실행 가능한 전체 DTO·검증·서비스 코드는 [Kotlin 예제](../../examples/kotlin/src/main/kotlin/example/KotlinExample.kt)를 보세요.

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

네이티브 `suspend` 핸들러나 필수 coroutine 의존성은 없습니다. 0.10.0 일반 HTTP는 늦은 Next 콜백과 Deferred를 반환하는 세 인자 AsyncRouteHandler를 지원합니다. 앱이 관리하는 coroutine/executor에 연결하려면 완료·취소·반환한 Deferred lease를 요청 수명에 맞춰 연결하세요. `runBlocking`은 호출 스레드를 점유하며 프레임워크의 비동기 어댑터가 아닙니다.

## 한 줄로 시작하기

`RestApp.start(3000) { app -> ... }`는 Java 정적 메서드이고 설정 람다가 마지막 인자라서 Kotlin 후행 람다로 씁니다. 람다의 마지막 식이 `app.get(...)`처럼 `RestApp`을 돌려줘도 `void` SAM이라 값은 버려지니 `Unit`을 따로 돌려줄 필요가 없습니다. 예제와 `start`와 `listen`의 차이는 [RestApp의 start](app.md#start)에 있습니다.


## Deferred 작업과 요청 보유

새 Java 함수형 인터페이스도 Kotlin SAM 변환을 지원합니다. `AsyncRouteHandler(req, res, next) -> Deferred`, `ErrorMiddleware(error, req, res, next)`, `ParamHandler(req, res, next, values, name)`, `Deferred.FailureHandler(error)`입니다. 세 인자 HTTP 라우트 콜백은 후행 람다용 고정 인자 오버로드를 가지며 기존 두 인자 콜백과 명시 콜백 배열도 유지합니다. 기존 `routing.Router`와 이름이 겹치지 않도록 공개 Router를 명시하거나 import하세요.

아래 구성은 선택 `:tab`이 있는 Router를 마운트하고 **첫 JSON 본문 읽기보다 먼저202를 보내는** 작업을 보여 줍니다. POST `/work`에 JSON `"hello"`를 보내면 반환한 pending 결과가 요청을 보유하는 동안 타입 지정 String으로 읽습니다. 마지막 요청 접근 뒤 resolve하며 다른 응답이나 next를 자동 실행하지 않습니다. Thread는 작은 설명용 예제입니다. 실제 부하에는 앱이 관리하는 제한된 executor와 명시 취소 정책을 사용하세요.

```kotlin
val api = com.snoworca.naruserve.Router.create(true)
api.param("id") { req, res, next, values, name ->
    req.attribute("seenId", values[0])
    next.run()
}
api.get("/users/:id{/:tab}") { req, res ->
    res.send(req.baseUrl() + ":" + req.param("id"))
}
app.use("/api", api)

app.methodAsync("POST", "/work") { req, res, next ->
    val work = Deferred.pending()
    res.status(202).send("accepted")
    Thread({
        try {
            val value = req.body(String::class.java)
            req.attribute("processed", value)
            work.resolve()
        } catch (error: Throwable) {
            work.reject(error)
        }
    }, "retained-work-example").start()
    work
}
```

`Router.create(true)`는 부모 캡처를 합치며 지역 캡처가 param 훅을 실행합니다. `/api` 안의 path/url은 접두사를 뺀 값이고 baseUrl은 `/api`, originalUrl/rawPath는 원래 값입니다. `next.route()`는 현재 Route를 건너뛰고 `next.router()`는 가까운 Router를 나갑니다. 네 인자 오류 미들웨어는 등록 위치에서 처리하고 거기의 `next.run()`은 오류를 해제하며 `next.error(error)`는 전파/교체합니다.

반환한 pending Deferred lease는 마지막 사용이나 terminal 취소까지 본문·업로드·context를 보유합니다. 사용자 어댑터는 물리적인 retainRequest 동작을 구현해야 하며 기본 no-op은 호환용입니다. producer가 읽기 전 본문을 미리 파싱한다는 약속은 없습니다. resolve/reject는 한 번만 결정하고 onRejected/onSettled observer는 내부 잠금 밖에서 실행됩니다. 스레드를 시작하거나 Request를 저장하는 것만으로 lease가 생기지 않습니다. [미들웨어](middleware.md#deferred와-늦은-작업), [요청 뷰](request.md#마운트와-경로-뷰), [수명주기](lifecycle.md)
