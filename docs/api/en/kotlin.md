# Kotlin

[한국어](../kotlin.md) | [English](kotlin.md)

NaruServe is written in Java. Use it from Kotlin without a separate DSL: all callbacks are Java functional interfaces (`@FunctionalInterface`), so Kotlin lambdas work directly.

This guide describes the 0.10.0 checkout. The 0.9.0 dependency example below identifies the previous release; it does not provide the new 0.10.0 APIs. Use the [current installation procedure](README.md). Core remains Java 8/API 21-compatible; Kotlin libraries belong to the optional Kotlin module.

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

These are application configuration fragments. Define application services, DTOs and `users` in your app, and import types such as `File`. The [complete Kotlin example](../../../examples/kotlin/src/main/kotlin/example/KotlinExample.kt) includes imports and `main`.

## Return early from a lambda

A bare `return` tries to exit the enclosing function and does not compile here. Use `return@functionName` to exit the callback:

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

An `if/else` expression needs no labeled return:

```kotlin
app.get("/users/:id") { req, res ->
    val user = users.find(req.param("id"))
    if (user == null) res.status(404).end() else res.json(user)
}
```

## Nullability

The public API is `@NullMarked` (non-null by default); values that can be absent carry `@Nullable`.

| Java | Kotlin type | When absent |
|---|---|---|
| `req.param("id")` | `String` | Exception when absent from the actual match, including an omitted optional |
| `req.paramOrNull("id")` | `String?` | null |
| `req.paramValues("parts")` | `List<String>` | Empty list |
| `req.query("page")` | `String?` | `null` |
| `req.form(name)` | `String?` | `null` |
| `req.header(name)` | `String?` | `null` |
| `req.cookie(name)` | `Cookie?` | `null` |
| `req.file(name)` | `UploadFile?` | `null` |
| `req.lastEventId()` | `String?` | `null` |
| `req.attributeAs(key, type)` | `T?` | `null` |
| `req.queries(name)` | `List<String>` | Empty list |
| `req.files(name)` | `List<UploadFile>` | Empty list |
| `req.service(type)` | `T` | Exception |
| `req.body(type)` | `T?` | `null` for a JSON `null` body |

```kotlin
val page = req.queryInt("page", 1)
val token = req.cookie("session")?.value
```

## Type arguments

Use `::class.java` when an API takes a Java `Class`:

```kotlin
val user: User? = req.body(User::class.java)
val users = req.service(UserService::class.java)
ctx.register(UserService::class.java, UserService(db))
```

Use `TypeRef` for a generic body, just as in Java:

```kotlin
val users: List<User>? = req.body(object : TypeRef<List<User>>() {})
```

`req.body(...)` returns `null` for JSON `null`, so its Kotlin return type is `T?`. Handle it explicitly when you need a non-null value:

```kotlin
val user = req.body(User::class.java) ?: throw HttpException(400, "JSON body is null")
```

### Optional NaruServe-kotlin extensions

The `NaruServe-kotlin` module provides `reified` extensions. It is the only module that uses `kotlin-stdlib`. It includes the `NaruServe` bootstrap (TinyHttpd + Gson), so one dependency also provides `RestApp.create()`:

```groovy
implementation 'com.snoworca.naruserve:naruserve-kotlin:0.9.0'
```

Import the provided extension:

```kotlin
import com.snoworca.naruserve.kotlin.body

inline fun <reified T : Any> Request.body(): T = body(object : TypeRef<T>() {}) ?: throw HttpException(400, "JSON body is null")

val user = req.body<User>()
```

The inline definition above shows the module's implementation; you do not need to copy it into your application. This extension returns non-null `T` and throws `HttpException(400)` for JSON `null`: a null body is a client error when the caller requires a value. Use `req.body(User::class.java)` when null is allowed.

The extension preserves element types in nested generics such as `List<User>` and `Map<String, List<User>>`: `val users = req.body<List<User>>()`.

## Property syntax

Java methods named `getX()`, `setX()` or `isX()` support Kotlin property syntax:

```kotlin
val cookie = Cookie("session", token).apply {
    isHttpOnly = true
    secure = true
    maxAge = 3600
}
val committed = res.isCommitted
```

Express-style short methods such as `req.param("id")`, `req.path()` and `res.status(201)` remain ordinary method calls with parentheses.

## JSON

The [Kotlin example](../../../examples/kotlin/src/main/kotlin/example/KotlinExample.kt) contains complete DTO, validation and service code.

Gson does not enforce Kotlin non-null types or understand Kotlin default arguments. See [JSON](json.md).

1. Implement `JsonCodec` with Moshi code generation or kotlinx.serialization and pass it to `app.jsonCodec(...)` if you need those semantics.
2. With Gson, make request DTO fields nullable and validate them explicitly.

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

## Coroutines

There is no native `suspend` handler or required coroutine dependency. The 0.10.0 common HTTP profile supports later Next callbacks and three-argument AsyncRouteHandler returning Deferred. You may bridge an application-owned coroutine/executor explicitly; completion, cancellation and the returned Deferred lease must follow the request lifetime. `runBlocking` occupies its calling thread and is not the framework's asynchronous adapter.

## Start with one call

`RestApp.start(3000) { app -> ... }` is a Java static method. Its configuration callback is the last parameter, so Kotlin trailing-lambda syntax works. The callback is a `void` SAM; a final expression such as `app.get(...)` has its value discarded, so you do not need an explicit `Unit`. See [RestApp start](app.md#start) for examples and the differences from `listen`.

## Deferred work and request retention

The new Java functional interfaces also support Kotlin SAM conversion: `AsyncRouteHandler(req, res, next) -> Deferred`, `ErrorMiddleware(error, req, res, next)`, `ParamHandler(req, res, next, values, name)` and `Deferred.FailureHandler(error)`. Three-argument HTTP route callbacks have fixed-arity overloads for trailing lambdas; existing two-argument callbacks and explicit callback arrays remain available. Qualify/import the public Router to avoid the retained `routing.Router` name.

The following configuration mounts a router with optional `:tab`, then demonstrates an early 202 before the **first JSON body read**. POST `/work` with JSON `"hello"` is read as a typed String while its returned pending result owns the request. Resolution occurs after the last request access; it does not send another response or call next. The Thread is a small demonstration; production scheduling should use an application-managed bounded executor and an explicit cancellation policy.

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

`Router.create(true)` merges parent captures; local captures drive param hooks. Inside `/api`, path/url are trimmed and baseUrl contains `/api`; originalUrl/rawPath remain original. `next.route()` skips the current Route and `next.router()` exits the nearest router. Four-argument error middleware occupies its registration position; `next.run()` there clears the error, whereas `next.error(error)` propagates/replaces it.

A pending returned Deferred lease keeps request body/uploads/context until last use or terminal cancellation. A custom adapter must implement physical retainRequest semantics; its default no-op is only compatibility. Nothing promises prefetch of a body before the producer reads it. `resolve`/`reject` settle once; subscribe through `onRejected`/`onSettled`, outside internal locks. Merely starting a thread or storing Request does not create a lease. See [middleware](middleware.md#deferred-and-late-work), [request views](request.md#mounts-and-path-views) and [lifecycle](lifecycle.md).
