package com.snoworca.naruserve.proxy

import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.testing.TestApp
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Proxy.trustCertificates from Kotlin source: both overloads compile and run with no wrapper (FR-PROXY-022 AC-6). */
class ProxyTrustCertificatesKotlinTest {

    private val t = TestApp.create()
    private val app: RestApp = t.app()
    private val backend = ProxyTlsTest.TlsBackend(TestCertificates.selfSignedServer())
    private var started = false

    @After
    fun tearDown() {
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS)
        }
        backend.close()
    }

    // FR-PROXY-022 AC-6: Proxy.to(url).trustCertificates(pem) with a String from Kotlin reaches the self-signed backend
    @Test(timeout = 30000)
    fun fr_PROXY_022_AC6_kotlinCallsTrustCertificatesWithAPemString() {
        val pem: String = TestCertificates.selfSignedPem()
        app.use("/api", Proxy.to("https://localhost:${backend.port()}").trustCertificates(pem))
        started = true

        val r = t.send("GET", "/api/x")

        assertEquals(200, r.status())
        assertEquals("ok", r.text())
    }

    // FR-PROXY-022 AC-6: the vararg overload takes one certificate, several certificates and a spread array from Kotlin
    @Test(timeout = 30000)
    fun fr_PROXY_022_AC6_kotlinCallsTheVarargOverload() {
        val cert: X509Certificate = TestCertificates.selfSigned()
        val certs: Array<X509Certificate> = arrayOf(cert, cert)
        assertNotNull(Proxy.to("https://localhost:8443").trustCertificates(cert, cert).sslSocketFactory())
        assertNotNull(Proxy.to("https://localhost:8443").trustCertificates(*certs).sslSocketFactory())
        app.use("/api", Proxy.to("https://localhost:${backend.port()}").trustCertificates(cert))
        started = true

        val r = t.send("GET", "/api/x")

        assertEquals(200, r.status())
        assertEquals("ok", r.text())
    }
}
