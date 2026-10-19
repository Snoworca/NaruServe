package com.snoworca.naruserve

import com.snoworca.naruserve.testing.TestApp
import org.junit.Assert.assertEquals
import org.junit.Test

/** SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-3.sds.md. */
class CookieKotlinTest {

    @Test
    fun `SDS-AC-12 IR-COOKIE-001 AC-7 Kotlin property syntax without platform types`() {
        val c = Cookie("sid", "v")
        c.maxAge = 60
        c.secure = true
        c.isHttpOnly = true
        c.sameSite = SameSite.LAX
        c.path = "/"
        val p: String? = c.path
        val n: String = c.name
        assertEquals("/", p)
        assertEquals("sid", n)
        assertEquals(60, c.maxAge)
    }

    @Test
    fun `FR-COOKIE-001 AC-7 FR-COOKIE-002 AC-7 Kotlin reads and writes cookies in a route lambda`() {
        val t = TestApp.create()
        val seen = ArrayList<Any?>()
        t.app().get("/c") { req, res ->
            val token: String? = req.cookie("session")?.value
            val all: Array<Cookie> = req.getCookies()
            seen += token
            seen += all.size
            res.addCookie(Cookie("s", "").apply { maxAge = 0; path = "/" })
        }
        val r = t.send("GET", "/c", "Cookie", "session=abc; x=1")
        assertEquals(listOf<Any?>("abc", 2), seen)
        assertEquals("s=; Path=/; Max-Age=0", r.header("Set-Cookie"))
        seen.clear()
        t.send("GET", "/c")
        assertEquals(listOf<Any?>(null, 0), seen)
    }
}
