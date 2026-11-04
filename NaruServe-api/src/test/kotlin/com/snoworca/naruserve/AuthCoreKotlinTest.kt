package com.snoworca.naruserve

import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 0.11.0 Core accessors (C4, C6a, C6b, C7) from Kotlin, without a DSL and without platform types. */
class AuthCoreKotlinTest {

    // IR-APP-008 AC-6
    @Test
    fun `IR-APP-008 AC-6 watcher and registration list read with property syntax`() {
        val t = TestApp.create()
        val app = t.app()
        val kinds = ArrayList<RegistrationKind>()
        app.onRegistration { r -> kinds.add(r.kind) }
        val handler = RouteHandler { _, res -> res.send("ok") }
        app.get("/x") { _, res -> res.send("ok") }
        app.get("/y", handler)
        assertEquals(listOf(RegistrationKind.ROUTE, RegistrationKind.ROUTE), kinds)
        val first: Registration = app.registrations()[0]
        val kind: RegistrationKind = first.kind
        val path: String? = first.path
        val target: Any = first.target
        val stringPath: Boolean = first.isStringPath
        val mount: Registration? = first.mount
        val method: String? = first.method
        val source: Any = first.pathSource
        assertEquals(RegistrationKind.ROUTE, kind)
        assertEquals("/x", path)
        assertTrue(target is RouteHandler)
        assertTrue(stringPath)
        assertEquals(null, mount)
        assertEquals("GET", method)
        assertEquals("/x", source)
        assertSame(handler, app.registrations()[1].target)
        assertEquals("ok", t.send("GET", "/x").text())
    }

    // IR-ENGINE-003 AC-6
    @Test
    fun `IR-ENGINE-003 AC-6 trust settings and accessors are non-null Kotlin types`() {
        val hopsApp = TestApp.create().app()
        hopsApp.trustProxyHops(1)
        val hops: Int = hopsApp.trustProxyHops()
        val legacy: Boolean = hopsApp.trustProxy()
        assertEquals(1, hops)
        assertFalse(legacy)
        val cidrApp = TestApp.create().app()
        cidrApp.trustProxyCidrs("10.0.0.0/8", "fd00::/8")
        val cidrs: List<String> = cidrApp.trustProxyCidrs()
        assertEquals(listOf("10.0.0.0/8", "fd00::/8"), cidrs)
        assertEquals(0, cidrApp.trustProxyHops())
    }

    // IR-HTTP-010 AC-3
    @Test
    fun `IR-HTTP-010 AC-3 socketAddress and requestId are non-null Strings`() {
        val t = TestApp.create(InMemoryHttpAdapter("10.0.0.5", false))
        t.app().get("/who") { req, res ->
            val s: String = req.socketAddress()
            val id: String = req.requestId()
            res.send("$s|$id")
        }
        val parts = t.send("GET", "/who").text().split("|")
        assertEquals("10.0.0.5", parts[0])
        assertTrue(parts[1].isNotEmpty())
    }

    // IR-APP-008 AC-7: the Kotlin example of docs/api/app.md runs
    @Test
    fun `IR-APP-008 AC-7 docs example of the registration watcher`() {
        val t = TestApp.create()
        val app = t.app()
        // docs-example: app.md#5
        app.onRegistration { r ->
            if (r.kind == RegistrationKind.ROUTE && !r.isStringPath) {
                throw IllegalStateException("regex routes are not allowed: ${r.pathSource}")
            }
        }
        app.get("/users") { _, res -> res.send("users") }
        for (r in app.registrations()) println("${r.kind} ${r.method} ${r.path}")
        assertEquals("users", t.send("GET", "/users").text())
        try {
            app.get(java.util.regex.Pattern.compile("^/re$")) { _, res -> res.send("re") }
            throw AssertionError("regex route accepted")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("regex routes are not allowed"))
        }
    }

    // IR-HTTP-010 AC-4: the Kotlin example of docs/api/request.md runs
    @Test
    fun `IR-HTTP-010 AC-4 docs example of socketAddress and requestId`() {
        val t = TestApp.create(InMemoryHttpAdapter("10.0.0.5", false))
        val app = t.app()
        // docs-example: request.md#3
        app.get("/whoami") { req, res ->
            val peer: String = req.socketAddress()
            val client: String = req.remoteAddress()
            val id: String = req.requestId()
            res.send("$peer $client $id")
        }
        val parts = t.send("GET", "/whoami").text().split(" ")
        assertEquals("10.0.0.5", parts[0])
        assertEquals("10.0.0.5", parts[1])
        assertTrue(parts[2].isNotEmpty())
    }
}
