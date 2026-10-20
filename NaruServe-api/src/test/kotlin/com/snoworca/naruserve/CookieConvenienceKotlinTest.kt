package com.snoworca.naruserve

import com.snoworca.naruserve.testing.TestApp
import org.junit.Assert.assertEquals
import org.junit.Test

class CookieConvenienceKotlinTest {
    @Test
    fun `FR-DX-005 AC-1 AC-2 Kotlin configures cookies with a trailing lambda`() {
        val app = TestApp.create()
        app.app().get("/cookie") { _, res ->
            res.cookie("session", "token") { c ->
                c.path("/app").httpOnly(true).secure(true).sameSite(SameSite.LAX)
            }.end()
        }
        app.app().get("/clear") { _, res ->
            res.clearCookie("session") { c -> c.path("/app").maxAge(999) }.end()
        }
        try {
            assertEquals("session=token; Path=/app; Secure; HttpOnly; SameSite=Lax",
                app.send("GET", "/cookie").header("Set-Cookie"))
            assertEquals("session=; Path=/app; Max-Age=0", app.send("GET", "/clear").header("Set-Cookie"))
        } finally {
            app.app().shutdown(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
