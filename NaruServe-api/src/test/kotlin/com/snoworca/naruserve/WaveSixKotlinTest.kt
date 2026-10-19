package com.snoworca.naruserve

import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.testing.TestApp
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Wave 6 docs examples compiled and run from Kotlin as SAM lambdas, without a DSL. */
class WaveSixKotlinTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `OPS-MULTIPART-001 AC-5 multipart limits and req file from Kotlin`() {
        val adapter = InMemoryHttpAdapter()
        val t = TestApp.create(adapter)
        val options: RestApp = t.app().multipart { o -> o.maxFileSize(1024L * 1024).maxParts(3) }
        options.formLimit(2048)
        t.app().post("/upload") { req, res ->
            val avatar: UploadFile? = req.file("avatar")
            val title: String? = req.form("title")
            val parts: List<UploadFile> = req.parts()
            res.send("${avatar?.filename()}|${avatar?.size()}|$title|${parts.size}")
        }
        t.start()
        val b = "XyZ"
        val body = ("--$b\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nhi\r\n" +
            "--$b\r\nContent-Disposition: form-data; name=\"avatar\"; filename=\"a.png\"\r\n" +
            "Content-Type: image/png\r\n\r\nabc\r\n--$b--\r\n").toByteArray(Charsets.UTF_8)
        val r = adapter.send(
            ClientRequest.of("POST", "/upload").body(body)
                .header("Content-Type", "multipart/form-data; boundary=$b")
                .header("Content-Length", body.size.toString()),
        )
        assertEquals(200, r.status())
        assertEquals("a.png|3|hi|2", String(r.body(), Charsets.UTF_8))
    }

    @Test
    fun `OPS-STATIC-001 AC-6 staticFiles options and CachePolicy from Kotlin`() {
        val dir = tmp.newFolder("www")
        File(dir, "main.html").writeText("<p>main</p>")
        val t = TestApp.create()
        t.app().staticFiles("/", dir) { o -> o.index("main.html").cache("*.html", CachePolicy.noCache()) }
        val p = CachePolicy.publicFor(365, TimeUnit.DAYS).immutable()
        assertEquals("public, max-age=31536000, immutable", p.headerValue)
        t.app().defaultCache(null)
        t.app().get("/api/x") { req, res -> res.cache(p).send("x") }
        val index = t.send("GET", "/")
        assertEquals(200, index.status())
        assertEquals("<p>main</p>", String(index.body(), Charsets.UTF_8))
        assertEquals("no-cache", index.header("Cache-Control"))
        val api = t.send("GET", "/api/x")
        assertEquals("public, max-age=31536000, immutable", api.header("Cache-Control"))
    }
}
