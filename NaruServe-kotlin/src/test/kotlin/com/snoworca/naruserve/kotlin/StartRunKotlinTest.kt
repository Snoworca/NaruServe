package com.snoworca.naruserve.kotlin

import com.snoworca.naruserve.AppState
import com.snoworca.naruserve.RestApp
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-DX-012 AC-8, FR-DX-013 AC-4: the trailing-lambda form runs over the real bootstrap engine (T11). */
class StartRunKotlinTest {

    // docs-example: app.md#4
    // adapted: port 0 instead of 3000 and a /x route instead of /hello, so the test never fights over a port
    @Test
    fun `FR-DX-012 AC-8 RestApp start with a trailing lambda serves a request and the handle stops it`() {
        val handle = RestApp.start(0) { app -> app.get("/x") { _, res -> res.send("ok") } }
        try {
            assertTrue(handle.port() > 0)
            assertEquals("http://127.0.0.1:${handle.port()}", handle.url())
            val c = URL("${handle.url()}/x").openConnection() as HttpURLConnection
            assertEquals(200, c.responseCode)
            assertEquals("ok", c.inputStream.readBytes().toString(Charsets.UTF_8))
            c.disconnect()
        } finally {
            handle.shutdown(5, TimeUnit.SECONDS)
        }
        assertEquals(AppState.TERMINATED, handle.state())
    }
}
