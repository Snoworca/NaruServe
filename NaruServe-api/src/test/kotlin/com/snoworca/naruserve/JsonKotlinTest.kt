package com.snoworca.naruserve

import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.TestApp
import java.io.Reader
import java.io.Writer
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Decodes `null` to null and anything else to an empty instance of the raw target; encodes with toString. */
private class KotlinTestCodec : JsonCodec {
    var encoded: Any? = "<none>"

    override fun <T : Any> decode(reader: Reader, type: Type): T? {
        val text = reader.readText()
        if (text.trim() == "null") return null
        @Suppress("UNCHECKED_CAST")
        return when (type) {
            JsonKUser::class.java -> JsonKUser("u") as T
            else -> listOf(JsonKUser("l")) as T
        }
    }

    override fun encode(value: Any?, writer: Writer) {
        encoded = value
        writer.write(value.toString())
    }
}

data class JsonKUser(val name: String)

class JsonKotlinTest {

    private fun post(adapter: InMemoryHttpAdapter, path: String, body: String) =
        adapter.send(InMemoryHttpAdapter.ClientRequest.of("POST", path).header("Content-Type", "application/json")
            .body(body.toByteArray()))

    @Test
    fun `IR-JSON-001 AC-4 Kotlin object TypeRef captures List of String`() {
        val t = object : TypeRef<List<String>>() {}
        assertEquals(object : TypeRef<List<String>>() {}.type, t.type)
        // Kotlin writes its declaration-site variance into the supertype signature: List<out E> becomes
        // List<? extends String>, unlike the Java new TypeRef<List<String>>() {}.
        val p = t.type as ParameterizedType
        assertEquals(List::class.java, p.rawType)
        val arg = p.actualTypeArguments.single()
        assertEquals(String::class.java, if (arg is WildcardType) arg.upperBounds.single() else arg)
    }

    @Test
    fun `IR-JSON-002 AC-7 FR-JSON-002 AC-6 Kotlin reads nullable bodies and writes json`() {
        val adapter = InMemoryHttpAdapter()
        val t = TestApp.create(adapter)
        val codec = KotlinTestCodec()
        val seen = ArrayList<Any?>()
        t.app().jsonCodec(codec)
        t.app().post("/u") { req, res ->
            val u: JsonKUser? = req.body(JsonKUser::class.java)
            seen += u
            res.json(null)
        }
        t.app().post("/l") { req, res ->
            val l: List<JsonKUser>? = req.body(object : TypeRef<List<JsonKUser>>() {})
            seen += l
            res.json(mapOf("success" to true))
        }
        t.start()
        assertEquals("null", String(post(adapter, "/u", "{}").body()))
        assertEquals(null, codec.encoded)
        assertEquals("{success=true}", String(post(adapter, "/l", "[]").body()))
        assertEquals(mapOf("success" to true), codec.encoded)
        post(adapter, "/u", "null")
        assertEquals(listOf(JsonKUser("u"), listOf(JsonKUser("l")), null), seen)
    }
}
