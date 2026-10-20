package com.snoworca.naruserve

import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.TestApp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kotlin property syntax on Cookie, Response and Request, next to the Express-style calls with parentheses. */
class PropertySyntaxKotlinTest {

    // SDS-AC-10; IR-KT-001 AC-2
    @Test
    fun `IR-KT-001 AC-2 six cookie properties assign, read back and reach Set-Cookie`() {
        val t = TestApp.create()
        val read = ArrayList<Any?>()
        t.app().get("/c") { _, res ->
            val c = Cookie("session", "t")
            c.maxAge = 60
            c.isHttpOnly = true
            c.secure = true
            c.path = "/"
            c.domain = null
            c.sameSite = SameSite.LAX
            val name: String = c.name
            val value: String = c.value
            val maxAge: Int = c.maxAge
            val httpOnly: Boolean = c.isHttpOnly
            val secure: Boolean = c.secure
            val path: String? = c.path
            val domain: String? = c.domain
            val sameSite: SameSite? = c.sameSite
            read.add(listOf(name, value, maxAge, httpOnly, secure, path, domain, sameSite))
            res.addCookie(c).send("ok")
        }
        val r = t.send("GET", "/c")
        assertEquals(listOf("session", "t", 60, true, true, "/", null, SameSite.LAX), read[0])
        assertEquals("session=t; Path=/; Max-Age=60; Secure; HttpOnly; SameSite=Lax", r.header("Set-Cookie"))
    }

    // SDS-AC-11; IR-KT-001 AC-3
    @Test
    fun `IR-KT-001 AC-3 isCommitted and isSecure read as properties while method and path stay calls`() {
        for (secure in listOf(false, true)) {
            val t = TestApp.create(InMemoryHttpAdapter("127.0.0.1", secure))
            val read = ArrayList<Any?>()
            t.app().get("/p") { req, res ->
                val before: Boolean = res.isCommitted
                val isSecure: Boolean = req.isSecure
                val method: Method = req.method()
                val path: String = req.path()
                res.send("x")
                val after: Boolean = res.isCommitted
                read.add(listOf(before, after, isSecure, method, path))
            }
            assertEquals("x", t.send("GET", "/p").text())
            assertEquals(listOf(false, true, secure, Method.GET, "/p"), read[0])
        }
    }
}
