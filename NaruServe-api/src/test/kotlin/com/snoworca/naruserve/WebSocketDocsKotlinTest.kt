package com.snoworca.naruserve

import com.snoworca.naruserve.internal.DefaultRestApp
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.testing.TestApp
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The docs/api/websocket.md Kotlin examples, compiled and run over the in-memory adapter (T11). */
class WebSocketDocsKotlinTest {

    // docs-example: websocket.md#2
    // SDS-AC-6 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md; OPS-WS-001 AC-7
    @Test(timeout = 20000)
    fun `OPS-WS-001 AC-7 the echo and options examples compile and run and echo hi`() {
        val adapter = InMemoryHttpAdapter()
        val t = TestApp.create(adapter)
        val app = t.app()
        // The two docs/api/websocket.md lines, verbatim.
        app.ws("/ws") { req, ws -> ws.onText { m -> ws.sendText("Echo: $m") } }
        app.webSocket { o -> o.allowedOrigins("https://app.example.com").idleTimeout(1, TimeUnit.MINUTES) }
        assertEquals(60000L, (app as DefaultRestApp).webSocketOptions().idleTimeoutMillis())
        t.start()

        val request = ClientRequest.of("GET", "/ws").header("Connection", "Upgrade").header("Upgrade", "websocket")
            .header("Origin", "https://app.example.com")
        val response = adapter.submit(request)
        val channel = adapter.nextChannel(5, TimeUnit.SECONDS)
        try {
            channel.clientText("hi")
            // The reply goes through the app's sender thread, so wait for it instead of reading at once.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!channel.sent().contains("Echo: hi") && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(channel.sent().toString(), channel.sent().contains("Echo: hi"))
        } finally {
            channel.clientClose(1000, "")
        }
        assertEquals(101, response.get(10, TimeUnit.SECONDS).status())
    }
}
