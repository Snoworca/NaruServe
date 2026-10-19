package com.snoworca.naruserve

import com.snoworca.naruserve.internal.DefaultRestApp
import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.TestApp
import java.io.Reader
import java.io.Writer
import java.lang.reflect.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Kotlin JsonCodec: `value: Any?` and `T` without `!` compile only because spi is @NullMarked. */
private object KotlinCodec : JsonCodec {
    override fun <T : Any> decode(reader: Reader, type: Type): T = throw UnsupportedOperationException("decode")
    override fun encode(value: Any?, writer: Writer) {
        writer.write(value?.toString() ?: "null")
    }
}

class AppEntryKotlinTest {

    @Test
    fun `SDS-AC-4 IR-APP-001 AC-5 Kotlin object JsonCodec goes through the builder chain`() {
        val adapter = InMemoryHttpAdapter()
        val builder: RestApp.Builder = RestApp.builder().httpAdapter(adapter).jsonCodec(KotlinCodec)
        val app: RestApp = builder.build()
        val d = app as DefaultRestApp
        assertSame(KotlinCodec, d.jsonCodec())
        assertSame(adapter, d.httpAdapter())
        val out = java.io.StringWriter()
        KotlinCodec.encode(null, out)
        assertEquals("null", out.toString())
    }

    @Test
    fun `SDS-AC-8 IR-APP-002 AC-6 IR-APP-003 AC-1 use, prefix use, route middleware and onError lambdas by SAM conversion`() {
        val t = TestApp.create()
        val app = t.app()
        val calls = ArrayList<String>()
        val mw = Middleware { _, _, next ->
            calls += "mw"
            next.run()
        }
        val global: RestApp = app.use { _, _, next -> next.run() }
        val prefixed: RestApp = app.use("/api") { _, _, next -> next.run() }
        val routed: RestApp = app.get("/x", mw) { _, res ->
            calls += "h"
            res.send("x")
        }
        val withError: RestApp = app.onError { _, _, res -> res.status(500).send("x") }
        for (r in listOf(global, prefixed, routed, withError)) assertSame(app, r)

        val response = t.send("GET", "/x")
        assertEquals(200, response.status())
        assertEquals("x", String(response.body(), Charsets.UTF_8))
        assertEquals(listOf("mw", "h"), calls)
    }
}
