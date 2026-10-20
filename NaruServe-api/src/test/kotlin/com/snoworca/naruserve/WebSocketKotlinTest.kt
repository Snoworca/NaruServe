package com.snoworca.naruserve

import com.snoworca.naruserve.internal.DefaultRestApp
import com.snoworca.naruserve.testing.TestApp
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** app.ws, app.webSocket and HttpException.withHeader as Kotlin SAM lambdas, without a DSL. */
class WebSocketKotlinTest {

    @Test
    fun `IR-WS-001 AC-10 ws and webSocket compile as SAM lambdas with non-platform types`() {
        val t = TestApp.create()
        val app: RestApp = t.app().ws("/ws") { req, ws ->
            val id: String = ws.id()
            val room: String? = req.param("room")
            ws.onText { m -> ws.sendText(m) }
            ws.onClose { code, reason -> ws.attribute("closed", "$code $reason $id $room") }
        }
        var seen: WebSocketOptions? = null
        val same: RestApp = app.webSocket { o ->
            val chained: WebSocketOptions = o.maxMessageSize(1024).pingInterval(5, TimeUnit.SECONDS)
            seen = chained
        }
        assertSame(t.app(), same)
        val options = (t.app() as DefaultRestApp).webSocketOptions()
        assertSame(options, seen)
        assertEquals(1024, options.maxMessageSize())
        assertEquals(5000L, options.pingIntervalMillis())
        assertEquals(426, t.send("GET", "/ws").status())
    }

    @Test
    fun `IR-HTTP-008 AC-8 withHeader compiles inside a SAM handler and returns non-platform types`() {
        val t = TestApp.create()
        t.app().get("/busy") { _, _ ->
            throw HttpException(503).withHeader("Retry-After", "1")
        }
        val e: HttpException = HttpException(503).withHeader("Retry-After", "1")
        val headers: List<Map.Entry<String, String>> = e.headers()
        assertEquals("Retry-After", headers[0].key)
        val r = t.send("GET", "/busy")
        assertEquals(503, r.status())
        assertEquals("1", r.header("Retry-After"))
    }
}
