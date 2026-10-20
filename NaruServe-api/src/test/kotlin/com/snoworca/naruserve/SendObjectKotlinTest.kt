package com.snoworca.naruserve

import com.snoworca.naruserve.testing.TestApp
import org.junit.Assert.assertEquals
import org.junit.Test

class SendObjectKotlinTest {
    @Test
    fun `FR-DX-002 AC-1 AC-4 Kotlin accepts bare null and dispatches an Any string as text`() {
        val app = TestApp.create()
        val text: Any = "hello"
        app.app().get("/text") { _, res -> res.send(text) }
        app.app().get("/null") { _, res -> res.send(null) }
        try {
            assertEquals("hello", app.send("GET", "/text").text())
            assertEquals("text/plain; charset=UTF-8", app.send("GET", "/text").header("Content-Type"))
            assertEquals(0, app.send("GET", "/null").body().size)
        } finally {
            app.app().shutdown(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
