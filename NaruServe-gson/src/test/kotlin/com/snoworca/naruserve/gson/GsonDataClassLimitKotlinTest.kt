package com.snoworca.naruserve.gson

import com.google.gson.JsonIOException
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.TestApp
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The default GsonJsonCodec (no Unsafe) against a Kotlin data class and the nullable-var DTO of kotlin.md. */
class GsonDataClassLimitKotlinTest {

    // No parameter has a default, so the JVM class has no no-argument constructor.
    data class User(val name: String)

    class CreateUserRequest {
        var name: String? = null
    }

    private val codec = GsonJsonCodec()

    @Test
    fun `SDS-AC-15 FR-KT-006 AC-1 decoding a data class throws JsonIOException`() {
        try {
            codec.decode<User>(StringReader("{\"name\":\"a\"}"), User::class.java)
            fail("decoded a data class without a no-argument constructor")
        } catch (e: JsonIOException) {
            assertTrue(e.message, e.message!!.contains(User::class.java.name))
        }
    }

    @Test
    fun `FR-KT-006 AC-2 CON-APP-010 AC-4 data class failure answers 500 common HTML`() {
        val adapter = InMemoryHttpAdapter()
        val t = TestApp.create(adapter)
        t.app().jsonCodec(GsonJsonCodec())
        t.app().post("/users") { req, res ->
            val user = req.body(User::class.java)
            res.status(201).json(user?.name)
        }
        t.start()
        val response = adapter.send(InMemoryHttpAdapter.ClientRequest.json("POST", "/users", "{\"name\":\"a\"}"))
        assertEquals(500, response.status())
        // FR-APP-024 AC-3; CON-APP-010 AC-4 AC-5 versions the old JSON envelope only.
        // The unchecked JsonIOException and disabled Unsafe allocation remain server failures.
        assertEquals("text/html; charset=utf-8", response.header("Content-Type"))
        assertEquals("default-src 'none'", response.header("Content-Security-Policy"))
        assertEquals("nosniff", response.header("X-Content-Type-Options"))
        val body = response.text()
        assertTrue(body, body.startsWith("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n" +
            "<meta charset=\"utf-8\">\n<title>Error</title>\n</head>\n<body>\n<pre>"))
        assertTrue(body, body.contains(JsonIOException::class.java.name))
        assertTrue(body, body.contains(User::class.java.name))
        assertTrue(body, body.contains("usage of JDK Unsafe is disabled"))
        assertTrue(body, body.endsWith("</pre>\n</body>\n</html>\n"))
        assertEquals(body.toByteArray(Charsets.UTF_8).size.toString(), response.header("Content-Length"))
    }

    @Test
    fun `SDS-AC-17 FR-KT-006 AC-3 a nullable var DTO decodes a name and an empty object`() {
        val named = codec.decode<CreateUserRequest>(StringReader("{\"name\":\"a\"}"), CreateUserRequest::class.java)
        assertEquals("a", named!!.name)
        val empty = codec.decode<CreateUserRequest>(StringReader("{}"), CreateUserRequest::class.java)
        assertNull(empty!!.name)
    }
}
