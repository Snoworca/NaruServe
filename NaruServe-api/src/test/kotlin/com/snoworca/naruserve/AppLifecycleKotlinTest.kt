package com.snoworca.naruserve

import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import java.io.Reader
import java.io.Writer
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

private object LifecycleCodec : JsonCodec {
    override fun <T : Any> decode(reader: Reader, type: Type): T = throw UnsupportedOperationException("decode")
    override fun encode(value: Any?, writer: Writer) = throw UnsupportedOperationException("encode")
}

private class UserService

/** Compiles only when `service` returns the non-null `T`: `T?` would not be assignable to `UserService`. */
@Suppress("unused")
private fun serviceOf(req: Request): UserService {
    val u = req.service(UserService::class.java)
    return u
}

class AppLifecycleKotlinTest {

    private fun newApp(adapter: InMemoryHttpAdapter = InMemoryHttpAdapter()): RestApp =
        RestApp.builder().httpAdapter(adapter).jsonCodec(LifecycleCodec).build()

    @Test
    fun `SDS-AC-4 IR-APP-004 AC-5 register and service are non-null in Kotlin`() {
        val s = UserService()
        var got: UserService? = null
        var missing: UserService? = UserService()
        val app = newApp()
        app.onStart { ctx ->
            ctx.register(UserService::class.java, s)
            val nonNull: UserService = ctx.get(UserService::class.java)
            got = nonNull
            missing = ctx.getOrNull(String::class.java)?.let { UserService() }
        }
        app.get("/u") { req, res -> res.send(serviceOf(req).toString()) }
        app.listen(0)
        app.shutdown(1, TimeUnit.SECONDS)
        assertSame(s, got)
        assertEquals(null, missing)
    }

    @Test
    fun `SDS-AC-8 IR-APP-005 AC-6 onStart, listen(0), port, state and shutdown without DSL`() {
        val adapter = InMemoryHttpAdapter()
        val app = newApp(adapter)
        var stored: Int? = null
        val same: RestApp = app.onStart { ctx ->
            ctx.attribute("k", 1)
            stored = ctx.attribute("k", Int::class.javaObjectType)
        }
        assertSame(app, same)
        val h = app.listen(0)
        assertEquals(adapter.boundPort(), h.port())
        assertEquals(AppState.RUNNING, app.state())
        app.shutdown(1, TimeUnit.SECONDS)
        assertEquals(AppState.TERMINATED, h.state())
        assertEquals(1, stored)
    }

    @Test
    fun `SDS-AC-14 IR-APP-007 AC-4 accessLog trailing lambda compiles and runs`() {
        val app = newApp()
        var seen: AccessLogOptions? = null
        val returned: RestApp = app.accessLog { it.exclude("/health") }
        app.accessLog { seen = it }
        assertSame(app, returned)
        assertEquals(true, seen.toString().contains("exclude=[/health]"))
    }
}
