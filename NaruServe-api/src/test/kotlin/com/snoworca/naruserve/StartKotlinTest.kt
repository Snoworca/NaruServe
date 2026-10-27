package com.snoworca.naruserve

import com.snoworca.naruserve.internal.AppStarter
import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.testing.SettingsCapturingAdapter
import java.io.Reader
import java.io.Writer
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private object StartCodec : JsonCodec {
    override fun <T : Any> decode(reader: Reader, type: Type): T = throw UnsupportedOperationException("decode")
    override fun encode(value: Any?, writer: Writer) = throw UnsupportedOperationException("encode")
}

/** FR-DX-012 AC-8 and FR-DX-013 AC-4 in the engine-free NaruServe-api test run; the real engine run is in NaruServe-kotlin. */
class StartKotlinTest {

    @Test
    fun `FR-DX-012 AC-8 start compiles with a trailing lambda and without a provider throws the create error`() {
        try {
            // The chain app.get(...) is the last expression: its RestApp value is dropped by the void SAM.
            val handle: ServerHandle = RestApp.start(0) { app -> app.get("/x") { _, res -> res.send("ok") } }
            handle.shutdown(1, TimeUnit.SECONDS)
            fail("no AppFactory provider is on this class path")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("com.snoworca.naruserve:naruserve"))
        }
    }

    @Test
    fun `FR-DX-013 AC-4 url and shutdown are non-null and callable on the start handle`() {
        val adapter = SettingsCapturingAdapter()
        val app = RestApp.builder().httpAdapter(adapter).jsonCodec(StartCodec).build()
        val handle = AppStarter.start(app, 0, { a -> a.get("/x") { _, res -> res.send("ok") } },
            AppStarterKotlinSupport.loader(), AppStarterKotlinSupport.noHooks())
        val url: String = handle.url()
        assertEquals("http://127.0.0.1:8080", url)
        handle.shutdown(1, TimeUnit.SECONDS)
        assertEquals(AppState.TERMINATED, handle.state())
    }
}

/** Keeps the internal starter's registry out of the real JVM in the test above. */
private object AppStarterKotlinSupport {
    fun loader(): ClassLoader = StartKotlinTest::class.java.classLoader

    fun noHooks(): AppStarter.Hooks = object : AppStarter.Hooks {
        override fun add(hook: Thread) {}
        override fun remove(hook: Thread) {}
    }
}
