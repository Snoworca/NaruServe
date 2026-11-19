package com.snoworca.naruserve.proxy

import com.snoworca.naruserve.HttpException
import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.testing.RecordingLogger
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The §7 gateway of docs/naruserve-proxy-request.md written in Kotlin, over a real TinyHttpd socket: the same Java
 * option chain, two mounts sharing one `Proxy`, and an `onError` that answers JSON by `ProxyException.kind()` with a
 * `when` on the error type (IR-PROXY-001 AC-6, FR-PROXY-015 AC-3).
 */
class GatewayEndToEndKotlinTest {

    private val apps = mutableListOf<RestApp>()
    private val backends = mutableListOf<RawBackend>()

    @After
    fun tearDown() {
        apps.forEach { it.shutdown(2, TimeUnit.SECONDS) }
        backends.forEach { it.close() }
    }

    private fun gateway(url: String): Int {
        val app = RestApp.builder().httpAdapter(TinyHttpdAdapter()).jsonCodec(GatewayEndToEndTest.MapJsonCodec())
            .logger(RecordingLogger()).build()
        apps.add(app)
        app.server { it.allowedHosts("gw.example") }

        val orders = Proxy.to(url)
            .requestHeader("X-Gateway-Source", "gateway")
            .readTimeout(2, TimeUnit.SECONDS)
            .totalTimeout(20, TimeUnit.SECONDS)
        app.use("/api/orders/jobs", orders)
        app.use("/api/orders", orders.retries(2))

        app.onError { error, _, res ->
            when (error) {
                is ProxyException -> res.status(error.status()).json(mapOf("error" to "upstream", "kind" to error.kind().name))
                is HttpException -> res.status(error.status()).json(mapOf("error" to "request"))
                else -> res.status(500).json(mapOf("error" to "internal"))
            }
        }
        return app.listen(0).port()
    }

    // IR-PROXY-001 AC-6, FR-PROXY-006 AC-1: the Kotlin §7 gateway forwards POST /api/orders/jobs/run with the JSON body
    // byte for byte and the fixed X-Gateway-Source, and relays the backend's answer
    @Test(timeout = 60000)
    fun ir_PROXY_001_AC6_kotlinGatewayForwardsTheJsonBodyByteIdentical() {
        val backend = RawBackend.answering(RawBackend.response(202, "queued")).also { backends.add(it) }
        val port = gateway(backend.url())
        val json = "{\"limit\" : 5}".toByteArray(StandardCharsets.UTF_8)

        val reply = GatewayEndToEndTest.call(
            port,
            "POST /api/orders/jobs/run HTTP/1.1\r\nHost: gw.example\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${json.size}\r\nConnection: close\r\n\r\n",
            json,
        )

        assertEquals(202, reply.status)
        assertEquals("queued", reply.text())
        val received = backend.onlyRequest()
        assertEquals("POST /api/orders/jobs/run HTTP/1.1", received.requestLine())
        assertArrayEquals(json, received.body)
        assertEquals(listOf("gateway"), received.headerValues("X-Gateway-Source"))
    }

    // FR-PROXY-015 AC-3: with the backend down the Kotlin onError answers 502 with the JSON body naming CONNECT_FAILED
    @Test(timeout = 60000)
    fun fr_PROXY_015_AC3_kotlinGatewayAnswersJsonByKind() {
        val down = ServerSocket(0).use { it.localPort }
        val port = gateway("http://127.0.0.1:$down")

        val reply = GatewayEndToEndTest.call(
            port, "GET /api/orders/x HTTP/1.1\r\nHost: gw.example\r\nConnection: close\r\n\r\n", null,
        )

        assertEquals(502, reply.status)
        assertEquals("{\"error\":\"upstream\",\"kind\":\"CONNECT_FAILED\"}", reply.text())
    }
}
