// Another package than the extension's, so the import below is what brings req.body<T>() in, as for an app.
package com.snoworca.naruserve.kotlin.docs

import com.snoworca.naruserve.kotlin.body
import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.TestApp
import java.io.Reader
import java.io.Writer
import java.lang.reflect.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The docs/api blocks that call req.body<T>(), run against the module extension (FR-KT-004 AC-5). */
class KotlinDocsBodyKotlinTest {

    class User(val name: String)

    private class UserCodec : JsonCodec {
        override fun <T : Any> decode(reader: Reader, type: Type): T? {
            val text = reader.readText().trim()
            @Suppress("UNCHECKED_CAST")
            return if (text == "null") null else User(text) as T
        }

        override fun encode(value: Any?, writer: Writer) {
            writer.write(value.toString())
        }
    }

    private fun post(adapter: InMemoryHttpAdapter, body: String) = adapter.send(
        InMemoryHttpAdapter.ClientRequest.of("POST", "/r").header("Content-Type", "application/json")
            .body(body.toByteArray()))

    // docs-example: kotlin.md#8
    // adapted: the extension definition is the module code of RequestExtensions.kt and its import heads this file
    @Test
    fun `SDS-AC-5 FR-KT-004 AC-5 kotlin md reified extension block runs against the module extension`() {
        val adapter = InMemoryHttpAdapter()
        val t = TestApp.create(adapter)
        t.app().jsonCodec(UserCodec())
        t.app().post("/r") { req, res ->
            val user = req.body<User>()
            res.status(201).json(user.name)
        }
        t.start()
        val ok = post(adapter, "\"a\"")
        assertEquals(201, ok.status())
        assertEquals("\"a\"", ok.text())
        // kotlin.md: a JSON null body is a client error, HttpException(400).
        val rejected = post(adapter, "null")
        assertEquals(400, rejected.status())
        assertTrue(rejected.text(), rejected.text().contains("JSON body is null"))
    }
}
