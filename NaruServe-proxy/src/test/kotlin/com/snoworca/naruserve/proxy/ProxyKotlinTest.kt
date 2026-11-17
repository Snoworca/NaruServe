package com.snoworca.naruserve.proxy

import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.testing.TestApp
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Proxy from Kotlin source: the Java option chain compiles and runs with no wrapper or extension (IR-PROXY-001). */
class ProxyKotlinTest {

    private val t = TestApp.create()
    private val app: RestApp = t.app()
    private val backend = RawBackend.answering(RawBackend.response(200, "user 42"))
    private var started = false

    @After
    fun tearDown() {
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS)
        }
        backend.close()
    }

    // IR-PROXY-001 AC-6: app.use("/api/users", Proxy.to(url).requestHeader(...).connectTimeout(...)
    // .readTimeout(...).retries(2)) compiles and runs from Kotlin with no wrapper or extension function
    @Test
    fun ir_PROXY_001_AC6_kotlinMountsTheOptionChainWithoutWrapper() {
        app.use(
            "/api/users",
            Proxy.to(backend.url())
                .requestHeader("X-Gateway-Source", "gateway")
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retries(2)
        )
        started = true

        val r = t.send("GET", "/api/users/42")

        assertEquals("GET /api/users/42 HTTP/1.1", backend.onlyRequest().requestLine())
        assertEquals(200, r.status())
        assertEquals("user 42", r.text())
    }

    // IR-PROXY-001 AC-6: the Kotlin chain configures the values it names, read through the package-private accessors
    @Test
    fun ir_PROXY_001_AC6_kotlinChainConfiguresTheNamedValues() {
        val p = Proxy.to(backend.url())
            .requestHeader("X-Gateway-Source", "gateway")
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retries(2)

        assertEquals(2, p.retries())
        assertEquals(TimeUnit.SECONDS.toNanos(3), p.connectTimeoutNanos())
        assertEquals(TimeUnit.SECONDS.toNanos(30), p.readTimeoutNanos())
    }
}
