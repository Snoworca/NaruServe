// Own package: the docs example types (User, Todo, UserService ...) must not clash with other test classes.
package com.snoworca.naruserve.jsondocs

import com.snoworca.naruserve.HttpException
import com.snoworca.naruserve.Request
import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.TypeRef
import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.TestApp
import java.io.File
import java.io.Reader
import java.io.Writer
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Example types the docs leave to the app.
class User(var name: String? = null)
class Todo(var title: String? = null)
class CreateUserRequest {
    var name: String? = null
    var email: String? = null
}

class UserService {
    fun create(user: User): User = user
}

class UserStore {
    fun create(name: String, email: String?): String = "$name/$email"
}

class TodoService {
    fun create(todo: Todo): Todo = todo
}

// Test-local copy of the NaruServe-kotlin extension shown in kotlin.md (the module extension arrives in wave 8).
inline fun <reified T : Any> Request.body(): T = body(object : TypeRef<T>() {}) ?: throw HttpException(400, "JSON body is null")

/** `null` decodes to null; anything else to a fresh instance of the raw target (or a one-element list). */
private class DocsCodec : JsonCodec {
    override fun <T : Any> decode(reader: Reader, type: Type): T? {
        val text = reader.readText().trim()
        if (text == "null") return null
        val raw = if (type is ParameterizedType) type.rawType else type
        @Suppress("UNCHECKED_CAST")
        return when {
            raw == CreateUserRequest::class.java && text == "{\"name\":\"a\"}" ->
                CreateUserRequest().apply { name = "a" } as T
            raw == List::class.java -> listOf(User("l")) as T
            else -> (raw as Class<*>).getDeclaredConstructor().newInstance() as T
        }
    }

    override fun encode(value: Any?, writer: Writer) {
        writer.write(value.toString())
    }
}

class JsonDocsKotlinTest {

    private val userService = UserService()
    private val users = UserStore()

    private fun doc(name: String) = File(System.getProperty("user.dir"), "../docs/api/$name").readText()

    private fun app(register: (RestApp) -> Unit): InMemoryHttpAdapter {
        val adapter = InMemoryHttpAdapter()
        val t = TestApp.create(adapter)
        t.app().jsonCodec(DocsCodec())
        register(t.app())
        t.start()
        return adapter
    }

    private fun post(adapter: InMemoryHttpAdapter, path: String, body: String) =
        adapter.send(InMemoryHttpAdapter.ClientRequest.json("POST", path, body)).status()

    // docs-example: README.md#1
    // adapted: only the JSON post route runs here; RestApp.create(), the get routes and listen are the opening
    // example that KotlinDocsExamplesKotlinTest runs for kotlin.md#1
    // SDS-AC-5; OPS-JSON-001 AC-4, AC-5 — README.md Kotlin example
    @Test
    fun `SDS-AC-5 OPS-JSON-001 AC-5 README post users example runs`() {
        assertTrue(doc("README.md").contains(
            "    val user = req.body(User::class.java) ?: throw HttpException(400, \"JSON body is null\")"))
        val adapter = app { app ->
            app.post("/users") { req, res ->
                val user = req.body(User::class.java) ?: throw HttpException(400, "JSON body is null")
                res.status(201).json(userService.create(user))
            }
        }
        assertEquals(201, post(adapter, "/users", "{}"))
        assertEquals(400, post(adapter, "/users", "null"))
    }

    // docs-example: kotlin.md#6
    // SDS-AC-5; OPS-JSON-001 AC-3, AC-5 — kotlin.md type-argument examples
    @Test
    fun `SDS-AC-5 OPS-JSON-001 AC-3 kotlin nullable type argument examples run`() {
        val d = doc("kotlin.md")
        assertTrue(d.contains("val user: User? = req.body(User::class.java)"))
        assertTrue(d.contains("val users: List<User>? = req.body(object : TypeRef<List<User>>() {})"))
        assertTrue(d.contains("| `req.body(type)` | `T?` |"))
        val seen = ArrayList<Any?>()
        val adapter = app { app ->
            app.post("/one") { req, res ->
                val user: User? = req.body(User::class.java)
                seen += user?.name
                res.status(204).end()
            }
            app.post("/many") { req, res ->
                val users: List<User>? = req.body(object : TypeRef<List<User>>() {})
                seen += users?.map { it.name }
                res.status(204).end()
            }
        }
        assertEquals(204, post(adapter, "/one", "null"))
        assertEquals(204, post(adapter, "/many", "[]"))
        assertEquals(204, post(adapter, "/many", "null"))
        assertEquals(listOf(null, listOf("l"), null), seen)
    }

    // SDS-AC-5; OPS-JSON-001 AC-3, AC-5 — kotlin.md reified extension
    @Test
    fun `SDS-AC-5 OPS-JSON-001 AC-3 reified extension answers 400 for a JSON null body`() {
        assertTrue(doc("kotlin.md").contains("inline fun <reified T : Any> Request.body(): T = "
            + "body(object : TypeRef<T>() {}) ?: throw HttpException(400, \"JSON body is null\")"))
        assertTrue(doc("kotlin.md").contains("val user = req.body<User>()"))
        val adapter = app { app ->
            app.post("/r") { req, res ->
                val user = req.body<User>()
                res.status(201).json(user.name)
            }
        }
        assertEquals(201, post(adapter, "/r", "{}"))
        assertEquals(400, post(adapter, "/r", "null"))
    }

    // docs-example: kotlin.md#10
    // adapted: CreateUserRequest is declared at the top of this file, and users is the UserStore property
    // SDS-AC-5; OPS-JSON-001 AC-3, AC-5 — kotlin.md JSON section
    @Test
    fun `SDS-AC-5 OPS-JSON-001 AC-3 kotlin CreateUserRequest example uses safe calls`() {
        val d = doc("kotlin.md")
        assertTrue(d.contains("    val name = body?.name"))
        assertTrue(d.contains("    res.status(201).json(users.create(name, body?.email))"))
        val adapter = app { app ->
            app.post("/users") { req, res ->
                val body = req.body(CreateUserRequest::class.java)
                val name = body?.name
                if (name.isNullOrBlank()) {
                    res.status(400).json("name is required")
                    return@post
                }
                res.status(201).json(users.create(name, body?.email))
            }
        }
        assertEquals(400, post(adapter, "/users", "null"))
        assertEquals(400, post(adapter, "/users", "{}"))
        assertEquals(201, post(adapter, "/users", "{\"name\":\"a\"}"))
    }

    // docs-example: express-migration.md#1
    // adapted: only the onStart registration and the JSON post route run here; the other routes, staticFiles and
    // listen are the same calls the kotlin.md examples run
    // SDS-AC-5; OPS-JSON-001 AC-4, AC-5 — express-migration.md Kotlin todo example
    @Test
    fun `SDS-AC-5 OPS-JSON-001 AC-5 express migration todo example runs`() {
        assertTrue(doc("express-migration.md").contains(
            "    val todo = req.body(Todo::class.java) ?: throw HttpException(400, \"JSON body is null\")"))
        val adapter = app { app ->
            app.onStart { ctx -> ctx.register(TodoService::class.java, TodoService()) }
            app.post("/api/todos") { req, res ->
                val todo = req.body(Todo::class.java) ?: throw HttpException(400, "JSON body is null")
                res.status(201).json(req.service(TodoService::class.java).create(todo))
            }
        }
        assertEquals(201, post(adapter, "/api/todos", "{}"))
        assertEquals(400, post(adapter, "/api/todos", "null"))
    }
}
