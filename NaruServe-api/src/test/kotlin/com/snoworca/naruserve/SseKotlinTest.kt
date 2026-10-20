package com.snoworca.naruserve

import com.snoworca.naruserve.internal.DefaultRestApp
import com.snoworca.naruserve.testing.TestApp
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** res.sse(), SseClient.onClose and app.sse as Kotlin SAM lambdas, without a DSL. */
class SseKotlinTest {

    // SDS-AC-14 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-9.sds.md
    @Test
    fun `SDS-AC-14 IR-SSE-001 AC-7 sse and onClose compile as SAM lambdas with non-platform types`() {
        val t = TestApp.create()
        var closed = 0
        val app: RestApp = t.app().get("/events") { req, res ->
            val c: SseClient = res.sse()
            val sent: Boolean = c.send("ready")
            c.onClose { closed++ }
            c.close()
            req.attribute("sent", sent)
        }
        var seen: SseOptions? = null
        val same: RestApp = app.sse { o ->
            val chained: SseOptions = o.heartbeatInterval(5, TimeUnit.SECONDS)
            seen = chained
        }
        val event: SseEvent = SseEvent.event("x")
        assertSame(event, event.id("1"))
        assertSame(t.app(), same)
        val options = (t.app() as DefaultRestApp).sseOptions()
        assertSame(options, seen)
        assertEquals(5000L, options.heartbeatMillis())
        val r = t.send("GET", "/events")
        assertEquals("data: ready\n\n", r.text())
        assertEquals(1, closed)
    }
}
