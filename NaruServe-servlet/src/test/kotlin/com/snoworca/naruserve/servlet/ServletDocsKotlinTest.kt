package com.snoworca.naruserve.servlet

import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.gson.GsonJsonCodec
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

// docs-example: servlet.md#1
// import com.snoworca.naruserve.servlet.ServletConfigurer
class AppConfigurer : ServletConfigurer {
    override fun configure(builder: RestApp.Builder): RestApp {
        val app = builder.build()
        app.get("/users/:id") { req, res -> res.json(mapOf("id" to req.param("id"))) }
        return app
    }
}

/** The Kotlin ServletConfigurer of docs/api/servlet.md compiles without a DSL and serves its route (FR-KT-004). */
class ServletDocsKotlinTest {

    @Test
    fun `OPS-SVL-001 AC-5 servlet md Kotlin configurer builds an app that serves its route`() {
        val adapter = InMemoryHttpAdapter()
        val app = AppConfigurer().configure(RestApp.builder().httpAdapter(adapter).jsonCodec(GsonJsonCodec()))
        app.listen(0)
        try {
            val r = adapter.send(ClientRequest.of("GET", "/users/7"))
            assertEquals(200, r.status())
            assertEquals("{\"id\":\"7\"}", String(r.body(), Charsets.UTF_8))
        } finally {
            app.shutdown(2, TimeUnit.SECONDS)
        }
    }
}
