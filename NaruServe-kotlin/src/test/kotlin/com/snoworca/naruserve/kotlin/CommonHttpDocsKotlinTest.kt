package com.snoworca.naruserve.kotlin

import com.snoworca.naruserve.AppState
import com.snoworca.naruserve.Deferred
import com.snoworca.naruserve.Request
import com.snoworca.naruserve.RestApp
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Executes the exact bilingual0.10.0 Kotlin routing/retention example against bootstrap TinyHttpd/Gson. */
class CommonHttpDocsKotlinTest {
    // OPS-REL-008 AC-3; FR-HTTP-027 AC-3 AC-5; FR-ROUTE-020 AC-1 AC-3;
    // FR-ROUTE-021 AC-1; FR-APP-023 AC-3 AC-7 AC-8.
    @Test(timeout = 20000)
    fun `kotlin11 mounts preprocesses and reads typed body after early response before owned shutdown`() {
        val app = RestApp.create()
        val observed = ConcurrentHashMap<String, Request>()
        val stops = AtomicInteger()
        app.onStop { stops.incrementAndGet() }
        app.use { req, res, next ->
            observed[req.rawPath()] = req
            next.run()
        }

        // docs-example: kotlin.md#11
        val api = com.snoworca.naruserve.Router.create(true)
        api.param("id") { req, res, next, values, name ->
            req.attribute("seenId", values[0])
            next.run()
        }
        api.get("/users/:id{/:tab}") { req, res ->
            res.send(req.baseUrl() + ":" + req.param("id"))
        }
        app.use("/api", api)

        app.methodAsync("POST", "/work") { req, res, next ->
            val work = Deferred.pending()
            res.status(202).send("accepted")
            Thread({
                try {
                    val value = req.body(String::class.java)
                    req.attribute("processed", value)
                    work.resolve()
                } catch (error: Throwable) {
                    work.reject(error)
                }
            }, "retained-work-example").start()
            work
        }

        try {
            val handle = app.listen(0)
            assertEquals("127.0.0.1", handle.address())
            val mounted = request(handle.url(), "GET", "/api/users/7/profile")
            assertEquals(200, mounted.status)
            assertEquals("/api:7", mounted.body)
            assertEquals("text/plain; charset=UTF-8", mounted.contentType)
            assertEquals("6", mounted.contentLength)
            val mountedRequest = observed["/api/users/7/profile"]
            assertNotNull(mountedRequest)
            assertEquals("7", mountedRequest!!.attributeAs("seenId", String::class.java))

            val accepted = request(handle.url(), "POST", "/work", "\"hello\"")
            assertEquals(202, accepted.status)
            assertEquals("accepted", accepted.body)
            assertEquals("text/plain; charset=UTF-8", accepted.contentType)
            assertEquals("8", accepted.contentLength)
            assertEquals(0, stops.get())

            // Normal shutdown must finish the returned Deferred's producer before final resource release.
            app.shutdown(5, TimeUnit.SECONDS)
            assertEquals(AppState.TERMINATED, app.state())
            assertEquals(1, stops.get())
            val workRequest = observed["/work"]
            assertNotNull(workRequest)
            assertEquals("hello", workRequest!!.attributeAs("processed", String::class.java))
        } finally {
            if (app.state() != AppState.TERMINATED) app.shutdown(0, TimeUnit.SECONDS)
        }
    }

    private data class Reply(val status: Int, val body: String, val contentType: String?, val contentLength: String?)

    private fun request(baseUrl: String, method: String, path: String, body: String? = null): Reply {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        connection.requestMethod = method
        connection.setRequestProperty("Connection", "close")
        try {
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = input?.use { it.readBytes() } ?: byteArrayOf()
            return Reply(status, bytes.toString(Charsets.UTF_8), connection.getHeaderField("Content-Type"),
                connection.getHeaderField("Content-Length"))
        } finally {
            connection.disconnect()
        }
    }
}
