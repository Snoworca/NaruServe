package com.snoworca.naruserve.kotlin

import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.TestApp
import java.io.Reader
import java.io.Writer
import java.lang.reflect.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reified Request.body extension of RequestExtensions.kt over the in-memory adapter. */
class RequestBodyExtensionKotlinTest {

    class User(val name: String)

    /** Decodes `null` to null, `{"name":"x"}` to User(x) and anything else to an empty list; records each Type. */
    private class RecordingCodec : JsonCodec {
        val types = ArrayList<Type>()

        override fun <T : Any> decode(reader: Reader, type: Type): T? {
            types += type
            val text = reader.readText().trim()
            @Suppress("UNCHECKED_CAST")
            return when {
                text == "null" -> null
                text.startsWith("{\"name\":\"") -> User(text.removePrefix("{\"name\":\"").removeSuffix("\"}")) as T
                else -> ArrayList<Any>() as T
            }
        }

        override fun encode(value: Any?, writer: Writer) {
            writer.write(value.toString())
        }
    }

    private val codec = RecordingCodec()
    private val adapter = InMemoryHttpAdapter()
    private val decoded = ArrayList<Any>()

    private fun start(register: (com.snoworca.naruserve.RestApp) -> Unit) {
        val t = TestApp.create(adapter)
        t.app().jsonCodec(codec)
        register(t.app())
        t.start()
    }

    private fun post(path: String, body: String) = adapter.send(InMemoryHttpAdapter.ClientRequest.of("POST", path)
        .header("Content-Type", "application/json").body(body.toByteArray()))

    private fun userRoute() = start { app ->
        app.post("/users") { req, res ->
            val user: User = req.body()
            decoded += user
            res.status(201).json(user.name)
        }
    }

    @Test
    fun `SDS-AC-6 IR-KT-002 AC-1 val user User = req body compiles non-null inside app post`() {
        userRoute()
        assertEquals(201, post("/users", "{\"name\":\"a\"}").status())
        assertEquals(listOf<Type>(User::class.java), codec.types)
    }

    @Test
    fun `SDS-AC-7 IR-KT-002 AC-2 the body decodes to the instance and JSON null answers 400`() {
        userRoute()
        val ok = post("/users", "{\"name\":\"a\"}")
        assertEquals(201, ok.status())
        assertEquals("a", ok.text())
        assertEquals("a", (decoded.single() as User).name)

        val rejected = post("/users", "null")
        assertEquals(400, rejected.status())
        assertTrue(rejected.text(), rejected.text().contains("\"code\":\"BAD_REQUEST\""))
        assertTrue(rejected.text(), rejected.text().contains("\"message\":\"JSON body is null\""))
        assertEquals(1, decoded.size)
    }

    @Test
    fun `SDS-AC-8 IR-KT-002 AC-3 req body of List of User passes the raw java util List`() {
        start { app ->
            app.post("/many") { req, res ->
                val users = req.body<List<User>>()
                decoded.add(users)
                res.status(204).end()
            }
        }
        assertEquals(204, post("/many", "[]").status())
        assertEquals(1, codec.types.size)
        assertEquals("java.util.List", (codec.types[0] as Class<*>).name)
    }
}
