package com.snoworca.naruserve

import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.testing.TestApp
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The docs/api/sse.md Kotlin progress example, compiled and run over the in-memory adapter (T11). */
class SseDocsKotlinTest {

    // docs-example: sse.md#1
    // SDS-AC-8 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-13.sds.md; OPS-SSE-001 AC-5
    @Test(timeout = 20000)
    fun `OPS-SSE-001 AC-5 the progress example compiles and runs and sends connected ready`() {
        val adapter = InMemoryHttpAdapter()
        val t = TestApp.create(adapter)
        val app = t.app()
        val clients = LinkedBlockingQueue<SseClient>()
        // The docs/api/sse.md Kotlin lines, verbatim, plus handing the client to the test.
        app.get("/api/events") { req, res ->
            val c = res.sse()
            c.send("connected", "ready")
            c.onClose { }
            clients.add(c)
        }
        t.start()
        try {
            val response = adapter.submit(ClientRequest.of("GET", "/api/events"))
            val client = clients.poll(5, TimeUnit.SECONDS)
            assertNotNull("the handler did not open the stream", client)
            client!!.close()
            assertEquals("event: connected\ndata: ready\n\n", response.get(10, TimeUnit.SECONDS).text())
        } finally {
            app.shutdown(2, TimeUnit.SECONDS)
        }
    }
}
