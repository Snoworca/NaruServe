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

    private fun multipart(b: String, vararg parts: String): ByteArray =
        (parts.joinToString("") { "--$b\r\n$it\r\n" } + "--$b--\r\n").toByteArray(Charsets.UTF_8)

    private fun post(adapter: InMemoryHttpAdapter, path: String, type: String, body: ByteArray) =
        adapter.send(
            ClientRequest.of("POST", path).body(body)
                .header("Content-Type", type)
                .header("Content-Length", body.size.toString()),
        )

    // IR-MULTIPART-001 AC-7
    @Test
    fun `IR-MULTIPART-001 AC-7 multipart and formLimit as SAM lambdas with non-platform returns`() {
        val adapter = InMemoryHttpAdapter()
        val t = TestApp.create(adapter)
        val app: RestApp = t.app().multipart { o ->
            val same: MultipartOptions = o.maxFileSize(1024).maxParts(3)
            same
        }
        val sameApp: RestApp = app.formLimit(2048)
        assertEquals(app, sameApp)
        sameApp.post("/m") { req, res -> res.send("${req.parts().size}") }
        sameApp.post("/f") { req, res -> res.send("${req.form("a")?.length}") }
        t.start()
        val field = "Content-Disposition: form-data; name=\"x\"\r\n\r\nv"
        val three = post(adapter, "/m", "multipart/form-data; boundary=B", multipart("B", field, field, field))
        assertEquals(200, three.status())
        assertEquals("3", String(three.body(), Charsets.UTF_8))
        val four = post(adapter, "/m", "multipart/form-data; boundary=B", multipart("B", field, field, field, field))
        assertEquals(413, four.status())
        val big = "Content-Disposition: form-data; name=\"f\"; filename=\"a.bin\"\r\n\r\n" + "z".repeat(1025)
        assertEquals(413, post(adapter, "/m", "multipart/form-data; boundary=B", multipart("B", big)).status())
        val form = "application/x-www-form-urlencoded"
        val ok = post(adapter, "/f", form, ("a=" + "q".repeat(2046)).toByteArray(Charsets.UTF_8))
        assertEquals(200, ok.status())
        assertEquals("2046", String(ok.body(), Charsets.UTF_8))
        assertEquals(413, post(adapter, "/f", form, ("a=" + "q".repeat(2047)).toByteArray(Charsets.UTF_8)).status())
    }

    // IR-MULTIPART-002 AC-7
    @Test
    fun `IR-MULTIPART-002 AC-7 form file parts and use from Kotlin without platform types`() {
        val dest = File(tmp.root, "saved.png")
        val adapter = InMemoryHttpAdapter()
        val t = TestApp.create(adapter)
        t.app().post("/upload") { req, res ->
            val name: String = req.form("name") ?: ""
            val f: UploadFile? = req.file("avatar")
            val filename: String? = f?.filename()
            val seen = StringBuilder()
            for (p in req.parts()) {
                val part: UploadFile = p
                seen.append(part.name()).append(',')
            }
            f?.use { it.save(dest) }
            res.send("$name|$filename|$seen|${req.file("missing") == null}")
        }
        t.start()
        val body = multipart(
            "XyZ",
            "Content-Disposition: form-data; name=\"name\"\r\n\r\nkim",
            "Content-Disposition: form-data; name=\"avatar\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\nabc",
        )
        val r = post(adapter, "/upload", "multipart/form-data; boundary=XyZ", body)
        assertEquals(200, r.status())
        assertEquals("kim|a.png|name,avatar,|true", String(r.body(), Charsets.UTF_8))
        assertEquals("abc", dest.readText())
    }

    // IR-STATIC-001 AC-6
    @Test
    fun `IR-STATIC-001 AC-6 staticFiles with options lambda and without from Kotlin`() {
        val dir = tmp.newFolder("site")
        File(dir, "main.html").writeText("<p>main</p>")
        val t = TestApp.create()
        val app: RestApp = t.app().staticFiles("/", dir) { o ->
            val same: StaticOptions = o.index("main.html").spaFallback(true)
            same
        }
        app.staticFiles("/assets", File("public"))
        val r = t.send("GET", "/")
        assertEquals(200, r.status())
        assertEquals("<p>main</p>", String(r.body(), Charsets.UTF_8))
    }

    // IR-STATIC-002 AC-6
    @Test
    fun `IR-STATIC-002 AC-6 CachePolicy res cache and mimeTypes register from Kotlin`() {
        val p: CachePolicy = CachePolicy.publicFor(365, TimeUnit.DAYS).immutable()
        assertEquals("public, max-age=31536000, immutable", p.headerValue)
        val dir = tmp.newFolder("img")
        File(dir, "a.webp").writeText("w")
        val t = TestApp.create()
        val types: MimeTypes = t.app().mimeTypes().register("webp", "image/webp")
        assertEquals(t.app().mimeTypes(), types)
        t.app().staticFiles("/img", dir)
        t.app().get("/nc") { req, res -> res.cache(CachePolicy.noCache()).send("x") }
        val nc = t.send("GET", "/nc")
        assertEquals(200, nc.status())
        assertEquals("no-cache", nc.header("Cache-Control"))
        val img = t.send("GET", "/img/a.webp")
        assertEquals(200, img.status())
        assertEquals("image/webp", img.header("Content-Type"))
    }
}
