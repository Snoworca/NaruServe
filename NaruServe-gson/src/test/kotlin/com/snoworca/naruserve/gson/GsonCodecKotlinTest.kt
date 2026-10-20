package com.snoworca.naruserve.gson

import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.TestApp
import org.junit.Assert.assertEquals
import org.junit.Test

class GsonCodecKotlinTest {

    @Test
    fun `SDS-AC-8 FR-JSON-003 AC-8 Kotlin sets a GsonJsonCodec with depth 32 on the app`() {
        val adapter = InMemoryHttpAdapter()
        val t = TestApp.create(adapter)
        t.app().jsonCodec(GsonJsonCodec(GsonJsonCodec.defaultGson(), 32))
        t.app().post("/echo") { req, res -> res.json(req.body(Any::class.java)) }
        t.start()
        fun post(body: String) = adapter.send(InMemoryHttpAdapter.ClientRequest.json("POST", "/echo", body))
        val ok = post("[".repeat(32) + "]".repeat(32))
        assertEquals(200, ok.status())
        assertEquals("[".repeat(32) + "]".repeat(32), String(ok.body()))
        assertEquals(400, post("[".repeat(33) + "]".repeat(33)).status())
    }
}
