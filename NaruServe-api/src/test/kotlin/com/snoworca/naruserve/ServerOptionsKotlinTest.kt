package com.snoworca.naruserve

import com.snoworca.naruserve.spi.ExchangeHandler
import com.snoworca.naruserve.spi.HttpAdapter
import com.snoworca.naruserve.spi.ServerSettings
import com.snoworca.naruserve.testing.StubJsonCodec
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerOptionsKotlinTest {

    private class SettingsAdapter : HttpAdapter {
        var settings: ServerSettings? = null

        override fun bind(settings: ServerSettings, handler: ExchangeHandler) {
            this.settings = settings
        }

        override fun bind(host: String, port: Int, handler: ExchangeHandler) = throw AssertionError("settings bind")

        override fun boundPort(): Int = 8080

        override fun stop(timeout: Long, unit: TimeUnit) {}

        override fun release() {}
    }

    @Test
    fun `IR-ENGINE-001 AC-6 Kotlin configures the server and proxy trust with lambdas`() {
        val adapter = SettingsAdapter()
        val app = RestApp.builder().httpAdapter(adapter).jsonCodec(StubJsonCodec()).build()
        app.server { o -> o.bindAddress("0.0.0.0").allowedHosts("myphone.local").maxHeaderSize(32768) }
        app.trustProxy(true)
        app.listen(8080)
        app.shutdown(1, TimeUnit.SECONDS)
        val s: ServerSettings = adapter.settings!!
        val address: String = s.bindAddress()
        assertEquals("0.0.0.0", address)
        assertEquals(listOf("myphone.local"), s.allowedHosts())
        assertEquals(32768, s.maxHeaderSize())
    }
}
