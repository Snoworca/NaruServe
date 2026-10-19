package com.snoworca.naruserve

import com.snoworca.naruserve.routing.RouterPolicy
import com.snoworca.naruserve.testing.TestApp
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed-value Request written in Kotlin; the `T : Any` bound shows Kotlin reads the package as @NullMarked. */
private class FixedRequest : Request {
    private val attributes = HashMap<String, Any>()
    override fun method(): Method = Method.GET
    override fun path(): String = "/a b"
    override fun rawPath(): String = "/a%20b"
    override fun param(name: String): String = if (name == "id") "7" else throw IllegalArgumentException(name)
    override fun query(name: String): String? = if (name == "q") "x" else null
    override fun queries(name: String): List<String> = if (name == "q") listOf("x", "y") else emptyList()
    override fun header(name: String): String? = if (name.equals("Host", true)) "h" else null
    override fun headers(name: String): List<String> = if (name.equals("Host", true)) listOf("h") else emptyList()
    override fun remoteAddress(): String = "127.0.0.1"
    override fun isSecure(): Boolean = false
    override fun lastEventId(): String? = null
    override fun attribute(key: String, value: Any?) {
        if (value == null) attributes.remove(key) else attributes[key] = value
    }
    override fun <T : Any> attribute(key: String, type: Class<T>): T? = attributes[key]?.let { type.cast(it) }
}

/** Response that records each call so the test can check what the Kotlin lambda did. */
private class RecordingResponse : Response {
    val calls = ArrayList<String>()
    override fun status(code: Int): Response = apply { calls += "status $code" }
    override fun header(name: String, value: String): Response = apply { calls += "header $name=$value" }
    override fun send(body: String) { calls += "send $body" }
    override fun send(body: ByteArray) { calls += "sendBytes ${body.size}" }
    override fun html(body: String) { calls += "html $body" }
    override fun stream(input: InputStream, contentType: String) {
        input.use { calls += "stream ${it.readBytes().size} $contentType" }
    }
    override fun end() { calls += "end" }
    override fun isCommitted(): Boolean = calls.isNotEmpty()
    override fun abort(error: Throwable?) { calls += "abort ${error?.message}" }
}

class HttpApiKotlinTest {

    @Test
    fun `SDS-AC-4 IR-HTTP-001 AC-5 Kotlin lambda calls every wave 3 Request and Response method`() {
        val seen = ArrayList<Any?>()
        val handler = RouteHandler { req, res ->
            val q: String? = req.query("q")
            val missing: String? = req.query("none")
            val p: String = req.path()
            seen.addAll(listOf(req.method(), p, req.rawPath(), req.param("id"), q, missing, req.queries("q"),
                req.header("host"), req.headers("HOST"), req.remoteAddress(), req.isSecure(), req.lastEventId()))
            req.attribute("user", "kim")
            val user: String? = req.attribute("user", String::class.java)
            req.attribute("user", null)
            seen.addAll(listOf(user, req.attribute("user", String::class.java)))
            val chained: Response = res.status(201).header("X-A", "1")
            chained.send("ok")
            res.send(byteArrayOf(1, 2))
            res.html("<p/>")
            res.stream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "text/plain")
            seen += res.isCommitted()
            res.end()
            res.abort(null)
            res.abort(IllegalStateException("x"))
        }
        val res = RecordingResponse()
        handler.handle(FixedRequest(), res)

        assertEquals(listOf(Method.GET, "/a b", "/a%20b", "7", "x", null, listOf("x", "y"), "h", listOf("h"),
            "127.0.0.1", false, null, "kim", null, true), seen)
        assertEquals(listOf("status 201", "header X-A=1", "send ok", "sendBytes 2", "html <p/>",
            "stream 3 text/plain", "end", "abort null", "abort x"), res.calls)
    }

    @Test
    fun `SDS-AC-5 IR-HTTP-002 AC-2 Kotlin lambda is a RouteHandler without an adapter type`() {
        var called = false
        val h: RouteHandler = RouteHandler { _, _ -> called = true }
        h.handle(FixedRequest(), RecordingResponse())
        assertTrue(called)
    }

    @Test
    fun `SDS-AC-4 IR-HTTP-005 AC-6 app get with a trailing lambda returns RestApp`() {
        val registered = ArrayList<Any?>()
        // A JDK proxy stands in for the app implementation of a later fragment; it returns itself, as RestApp does.
        val app = Proxy.newProxyInstance(RestApp::class.java.classLoader, arrayOf(RestApp::class.java)) { proxy, m, args ->
            registered += m.name
            registered.add(args?.toList())
            proxy
        } as RestApp
        val result: RestApp = app.get("/x") { _, res -> res.send("ok") }
        assertSame(app, result)
        assertEquals("get", registered[0])
        val handler = (registered[1] as List<*>)[1] as RouteHandler
        val res = RecordingResponse()
        handler.handle(FixedRequest(), res)
        assertEquals(listOf("send ok"), res.calls)
        assertSame(app, app.routerPolicy(RouterPolicy.strict()))
    }

    @Test
    fun `SDS-AC-4 IR-HTTP-005 AC-6 Kotlin trailing lambdas register on the real app and route through TestApp`() {
        val t = TestApp.create()
        val app: RestApp = t.app()
        assertSame(app, app.get("/x") { _, res -> res.send("ok") })
        app.route("/u/:id").post { _, res -> res.end() }
        // Wave 3 part 3 has no DefaultRequest/DefaultResponse yet, so only automatic replies are routed here.
        val mismatch = t.send("DELETE", "/x")
        assertEquals(405, mismatch.status())
        assertEquals("GET, HEAD, OPTIONS", mismatch.header("Allow"))
        assertEquals(404, t.send("GET", "/none").status())
        assertEquals("POST, OPTIONS", t.send("OPTIONS", "/u/1").header("Allow"))
    }
}
