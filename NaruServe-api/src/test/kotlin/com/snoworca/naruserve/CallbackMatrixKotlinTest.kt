package com.snoworca.naruserve

import com.snoworca.naruserve.spi.ErrorRenderer
import com.snoworca.naruserve.spi.ExchangeHandler
import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.spi.ServerSettings
import com.snoworca.naruserve.spi.StreamDrain
import com.snoworca.naruserve.spi.UpgradeHandler
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.testing.TestApp
import java.io.IOException
import java.io.Reader
import java.io.Writer
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * A Kotlin trailing lambda, without a receiver DSL, for every interface of CallbackMatrix.CALLBACK_TYPES, each reached
 * by a request or a session over the in-memory adapter. The last test (sorted by name) checks that every listed
 * interface was exercised.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CallbackMatrixKotlinTest {

    companion object {
        private val exercised: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private fun hit(type: Class<*>) {
            exercised.add(type.name)
        }

        private fun await(what: String, done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!done() && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(what, done())
        }
    }

    /** Decodes any text to itself, except `bad`, which fails so that onError hears of it. */
    private object TextCodec : JsonCodec {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> decode(reader: Reader, type: Type): T? {
            val text = reader.readText()
            if (text == "bad") {
                throw IOException("bad json")
            }
            return text as T
        }

        override fun encode(value: Any?, writer: Writer) = throw UnsupportedOperationException("encode")
    }

    // SDS-AC-3; FR-KT-003 AC-3
    @Test
    fun `FR-KT-003 AC-3 a RouteHandler Middleware and ErrorHandler lambdas are reached by requests`() {
        val t = TestApp.create()
        val app = t.app()
        app.get("/get") { _, res -> hit(RouteHandler::class.java); res.send("get") }
        app.post("/post") { _, res -> res.send("post") }
        app.route("/route").get { _, res -> res.send("route") }
        app.use("/mw") { _, _, next -> hit(Middleware::class.java); next.run() }
        app.get("/mw/x") { _, res -> res.send("mw") }
        app.get("/boom") { _, _ -> throw IllegalStateException("boom") }
        app.onError { err, _, res -> hit(ErrorHandler::class.java); res.status(500).send("handled " + err.message) }

        assertEquals("get", t.send("GET", "/get").text())
        assertEquals("post", t.send("POST", "/post").text())
        assertEquals("route", t.send("GET", "/route").text())
        assertEquals("mw", t.send("GET", "/mw/x").text())
        assertEquals("handled boom", t.send("GET", "/boom").text())
    }

    // SDS-AC-3; FR-KT-003 AC-3
    @Test
    fun `FR-KT-003 AC-3 b LifecycleCallback and Configurer lambdas run on start, stop and configuration`() {
        val t = TestApp.create()
        val app = t.app()
        var started = false
        var stopped = false
        app.onStart { _ -> hit(LifecycleCallback::class.java); started = true }
        app.onStop { _ -> stopped = true }
        val configured = ArrayList<String>()
        app.server { o -> hit(Configurer::class.java); configured += o.javaClass.simpleName }
        app.multipart { o -> configured += o.javaClass.simpleName }
        app.webSocket { o -> configured += o.javaClass.simpleName }
        app.sse { o -> configured += o.javaClass.simpleName }
        app.get("/x") { _, res -> res.send("x") }
        assertEquals("x", t.send("GET", "/x").text())
        app.shutdown(5, TimeUnit.SECONDS)
        assertTrue(started)
        assertTrue(stopped)
        assertEquals(4, configured.size)
    }

    // SDS-AC-3; FR-KT-003 AC-3
    @Test(timeout = 30000)
    fun `FR-KT-003 AC-3 c WebSocketHandler and the session listener lambdas are reached by a session`() {
        val adapter = InMemoryHttpAdapter()
        val t = TestApp.create(adapter)
        val app = t.app().jsonCodec(TextCodec)
        val heard = ConcurrentHashMap.newKeySet<String>()
        app.ws("/ws") { _, ws ->
            hit(WebSocketHandler::class.java)
            ws.onText { m -> hit(WebSocketSession.TextListener::class.java); heard += "text:$m" }
            ws.onBinary { b -> hit(WebSocketSession.BinaryListener::class.java); heard += "binary:${b.size}" }
            ws.onJson(String::class.java) { v -> hit(WebSocketSession.JsonListener::class.java); heard += "json:$v" }
            ws.onError { e -> hit(WebSocketSession.ErrorListener::class.java); heard += "error:${e.message}" }
            ws.onClose { code, reason ->
                hit(WebSocketSession.CloseListener::class.java)
                heard += "close:$code $reason"
            }
        }
        t.start()
        val response = adapter.submit(ClientRequest.upgrade("/ws"))
        val channel = adapter.nextChannel(10, TimeUnit.SECONDS)
        channel.clientText("hi")
        channel.clientBinary(byteArrayOf(1, 2))
        channel.clientText("bad")
        channel.clientClose(1000, "bye")
        assertEquals(101, response.get(10, TimeUnit.SECONDS).status())
        val expected = setOf("text:hi", "json:hi", "binary:2", "text:bad", "error:bad json", "close:1000 bye")
        await("heard $heard") { heard.containsAll(expected) }
    }

    // SDS-AC-3; FR-KT-003 AC-3
    @Test
    fun `FR-KT-003 AC-3 d SseClient CloseListener lambda runs when the stream closes`() {
        val t = TestApp.create()
        var closed = 0
        t.app().get("/events") { _, res ->
            val c: SseClient = res.sse()
            c.onClose { hit(SseClient.CloseListener::class.java); closed++ }
            c.send("ready")
            c.close()
        }
        assertEquals("data: ready\n\n", t.send("GET", "/events").text())
        assertEquals(1, closed)
    }

    // SDS-AC-3; FR-KT-003 AC-3
    @Test(timeout = 30000)
    fun `FR-KT-003 AC-3 e SPI ExchangeHandler StreamDrain UpgradeHandler and ErrorRenderer lambdas`() {
        val settings = ServerSettings.builder()
            .logger(ConsoleLogger())
            .errorRenderer { status -> hit(ErrorRenderer::class.java); "error $status" }
            .build()
        val adapter = InMemoryHttpAdapter()
        adapter.bind(settings) { ex ->
            hit(ExchangeHandler::class.java)
            when (ex.rawPath()) {
                "/drain" -> ex.eventStream(200, emptyList()) { out ->
                    hit(StreamDrain::class.java)
                    out.write("drained".toByteArray())
                }
                "/upgrade" -> ex.upgrade(emptyList()) { _, out ->
                    hit(UpgradeHandler::class.java)
                    out.write("upgraded".toByteArray())
                }
                else -> {
                    val body = settings.errorRenderer().render(404).toByteArray()
                    ex.commit(404, emptyList(), body.size.toLong()).write(body)
                    ex.finish()
                }
            }
        }
        try {
            assertEquals("drained", adapter.submit(ClientRequest.of("GET", "/drain")).get(10, TimeUnit.SECONDS).text())
            assertEquals("upgraded", adapter.send(ClientRequest.of("GET", "/upgrade")).text())
            val missing = adapter.send(ClientRequest.of("GET", "/other"))
            assertEquals(404, missing.status())
            assertEquals("error 404", missing.text())
        } finally {
            adapter.stop(1, TimeUnit.SECONDS)
            adapter.release()
        }
    }

    // FR-KT-003 AC-3 AC-4; FR-ROUTE-021 AC-1; FR-APP-023 AC-1 AC-7; FR-APP-024 AC-1
    @Test
    fun `FR-KT-003 AC-3 h common routing callback lambdas run on the actual request`() {
        val t = TestApp.create()
        val app = t.app()
        val trace = ArrayList<String>()
        app.param("id") { request, _, next, values, name ->
            hit(ParamHandler::class.java)
            request.param(name, values[0] + "!")
            trace += "param"
            next.run()
        }
        app.getAsync("/async/:id") { request, _, _ ->
            hit(AsyncRouteHandler::class.java)
            val result = Deferred.pending()
            result.onRejected { failure ->
                hit(Deferred.FailureHandler::class.java)
                trace += "rejected " + failure.message
            }
            result.reject(IllegalStateException(request.param("id")))
            result
        }
        app.use { failure, _, response, _ ->
            hit(ErrorMiddleware::class.java)
            trace += "error"
            response.status(409).send(failure.message!!)
        }
        val response = t.send("GET", "/async/value")
        assertEquals(409, response.status())
        assertEquals("value!", response.text())
        assertEquals(listOf("param", "rejected value!", "error"), trace)
    }

    // SDS-AC-3; FR-KT-003 AC-3
    @Test
    fun `FR-KT-003 AC-3 z every CALLBACK_TYPES interface was exercised`() {
        assertEquals(CallbackMatrix.CALLBACK_TYPES.sorted(), exercised.sorted())
    }
}
