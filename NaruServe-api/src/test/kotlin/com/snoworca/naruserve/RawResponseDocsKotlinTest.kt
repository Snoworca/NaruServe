package com.snoworca.naruserve

import com.snoworca.naruserve.common.HttpHeaderValues
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse
import com.snoworca.naruserve.testing.TestApp
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/api/response.md and its English mirror document the raw response API (res.append, res.onAbort, res.relay)
 * and HttpHeaderValues.isFieldValue: the stated facts are read from both pages, and the pages' Kotlin blocks for
 * those members run here over the in-memory adapter (KotlinDocsMarkers ordinals of response.md).
 */
class RawResponseDocsKotlinTest {

    private val adapter = InMemoryHttpAdapter()
    private val t = TestApp.create(adapter)
    private val app: RestApp = t.app()
    private var started = false
    private val temp: File = Files.createTempDirectory("raw-response-docs").toFile()

    @After
    fun tearDown() {
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS)
        }
        temp.deleteRecursively()
    }

    private fun send(method: String, target: String): ClientResponse {
        if (!started) {
            t.start()
            started = true
        }
        return adapter.send(ClientRequest.of(method, target))
    }

    private fun root(): File = File(System.getProperty("user.dir")).parentFile

    private fun read(path: String): String = String(Files.readAllBytes(File(root(), path).toPath()), Charsets.UTF_8)

    private fun ko(): String = read("docs/api/response.md")

    private fun en(): String = read("docs/api/en/response.md")

    /** The text from the `## heading` line up to the next `## ` heading outside a code fence. */
    private fun section(doc: String, heading: String): String {
        val start = doc.indexOf("\n$heading\n")
        assertTrue("missing heading $heading", start >= 0)
        val out = StringBuilder()
        var fence = false
        for ((i, line) in doc.substring(start + 1).split("\n").withIndex()) {
            if (line.startsWith("```")) fence = !fence
            if (i > 0 && !fence && line.startsWith("## ")) break
            out.append(line).append('\n')
        }
        return out.toString()
    }

    /** The one table line that starts with [prefix]. */
    private fun row(where: String, doc: String, prefix: String): String {
        val rows = doc.split("\n").filter { it.startsWith(prefix) }
        assertEquals("$where rows starting with $prefix", 1, rows.size)
        return rows[0]
    }

    private fun has(where: String, text: String, vararg needles: String) {
        for (n in needles) {
            assertTrue("$where lacks: $n", text.contains(n))
        }
    }

    /** The ```lang blocks of [text], in order. */
    private fun blocks(text: String, lang: String): List<String> {
        val out = ArrayList<String>()
        var current: StringBuilder? = null
        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (current == null && trimmed == "```$lang") {
                current = StringBuilder()
            } else if (current != null && trimmed.startsWith("```")) {
                out.add(current.toString())
                current = null
            } else {
                current?.append(line)?.append('\n')
            }
        }
        return out
    }

    /** The Javadoc comment that ends right before the first line holding [declaration]. */
    private fun javadocBefore(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        assertTrue("no declaration $declaration", at >= 0)
        val end = source.lastIndexOf("*/", at)
        val start = source.lastIndexOf("/**", end)
        assertTrue("no Javadoc before $declaration", start >= 0 && end > start)
        assertFalse("another declaration sits between the Javadoc and $declaration",
            source.substring(end, at).contains(";"))
        return source.substring(start, end).replace(Regex("\\s*\\n\\s*\\*\\s?"), " ")
    }

    @Test
    fun `OPS-DX-009 AC-1 response md documents res append with Express res append and two Set-Cookie lines`() {
        val k = section(ko(), "## 헤더 줄 더하기")
        has("response.md append", k, "`res.append(name, value)`", "Express `res.append`", "헤더 줄을 하나 더하고",
            "`Response`를 돌려줍니다", "`res.header`와 같은 이름·값 검사", "`IllegalArgumentException`",
            "`res.header`는 같은 이름의 헤더를 바꿔 씁니다(Express `res.set`)")
        val e = section(en(), "## Adding header lines")
        has("en/response.md append", e, "`res.append(name, value)`", "Express `res.append`", "adds a header line",
            "returns the `Response`", "the same name and value check as `res.header`", "`IllegalArgumentException`",
            "`res.header` keeps replacing a header of the same name (Express `res.set`)")
        for ((where, s) in listOf("response.md" to k, "en/response.md" to e)) {
            for (lang in listOf("java", "kotlin")) {
                val b = blocks(s, lang)
                assertEquals("$where $lang append blocks", 1, b.size)
                assertEquals("$where $lang block sends two Set-Cookie lines: ${b[0]}", 2,
                    Regex("\\.append\\(\"Set-Cookie\"").findAll(b[0]).count())
            }
        }
        has("response.md", row("response.md", ko(), "| `append(name, value)` |"), "`Response`", "`res.append(name, value)`")
        has("en/response.md", row("en/response.md", en(), "| `append(name, value)` |"), "Response", "`res.append(name, value)`")
    }

    @Test
    fun `OPS-DX-009 AC-2 response md documents res onAbort with its listener rules and Express res on close`() {
        val k = section(ko(), "## 중단 알림")
        has("response.md onAbort", k, "`res.onAbort(listener)`", "반환값은 `void`", "`null`을 넘기면 `NullPointerException`",
            "클라이언트가 연결을 끊거나 앱이 `res.abort`를 부르면", "한 번 실행됩니다",
            "정상적으로 완료된 응답에서는 실행되지 않습니다", "중단된 뒤에 등록한 리스너는 바로 실행됩니다",
            "요청 스레드가 아닌 스레드에서 실행될 수 있습니다", "나머지 리스너는 그대로 실행됩니다",
            "유휴 시간에 생긴 연결 끊김을 다음 I/O에서야 알릴 수 있습니다",
            "`res.on('close'", "`!res.writableFinished`")
        val e = section(en(), "## Abort notification")
        has("en/response.md onAbort", e, "`res.onAbort(listener)`", "returns `void`", "a `null` listener throws `NullPointerException`",
            "when the client disconnects or the app calls `res.abort`", "runs once",
            "never for a response that completed normally", "a listener registered after the abort runs at once",
            "may run on a thread other than the request thread", "the other listeners still run",
            "may report a disconnect during idle time only at the next I/O",
            "`res.on('close'", "`!res.writableFinished`")
        for ((where, s) in listOf("response.md" to k, "en/response.md" to e)) {
            for (lang in listOf("java", "kotlin")) {
                val b = blocks(s, lang)
                assertEquals("$where $lang onAbort blocks", 1, b.size)
                assertTrue("$where $lang block registers a listener: ${b[0]}", b[0].contains("res.onAbort"))
            }
        }
        has("response.md", row("response.md", ko(), "| `onAbort(listener)` |"), "`res.on('close'")
        has("en/response.md", row("en/response.md", en(), "| `onAbort(listener)` |"), "`res.on('close'")
    }

    @Test
    fun `OPS-DX-009 AC-3 response md states the SPI default of HttpExchange onAbort and the custom adapter limit`() {
        has("response.md onAbort", section(ko(), "## 중단 알림"), "`HttpExchange.onAbort`",
            "SPI 기본 구현은 인자를 검사하고 아무것도 하지 않습니다", "직접 만든 `HttpAdapter`",
            "`onAbort` 리스너가 한 번도 불리지 않을 수 있습니다",
            "TinyHttpd 어댑터와 서블릿 어댑터는 연결 끊김을 알립니다")
        has("en/response.md onAbort", section(en(), "## Abort notification"), "`HttpExchange.onAbort`",
            "the SPI default checks its argument and does nothing", "a custom `HttpAdapter`",
            "an `onAbort` listener may never be called when the client disconnects",
            "The TinyHttpd and servlet adapters report disconnects")
    }

    @Test
    fun `OPS-DX-009 AC-4 response md documents res relay and how it differs from res stream`() {
        val k = section(ko(), "## 길이를 아는 스트림 전달")
        has("response.md relay", k, "`res.relay(in, contentLength)`", "`res.stream(in, contentType)`",
            "`Content-Type`을 더하거나 바꾸지 않습니다",
            "`Content-Type`과 `Content-Length` 말고는 `relay`를 부르기 전에 앱이 정한 헤더만 보냅니다",
            "`app.defaultCache`를 붙이지 않습니다", "정확히 `contentLength` 바이트를 보내고",
            "스트림이 먼저 끝나면 응답을 중단합니다", "`-1`이면 길이를 정하지 않고 보냅니다",
            "`-1`보다 작으면 `IllegalArgumentException`", "`HEAD` 요청과 `304` 응답은 0 이상의 길이를 `Content-Length`로 두고 본문은 보내지 않습니다",
            "`204` 응답은 본문도 `Content-Length`도 보내지 않습니다", "스트림 소유권은 `res.stream`과 같습니다",
            "Express에는 대응 API가 없습니다")
        val e = section(en(), "## Relaying a stream with its length")
        has("en/response.md relay", e, "`res.relay(in, contentLength)`", "`res.stream(in, contentType)`",
            "adds or changes no `Content-Type`",
            "sends only the headers set before it besides `Content-Type` and `Content-Length`",
            "applies no `app.defaultCache`", "sends exactly `contentLength` bytes",
            "aborts when the stream ends early", "`-1` sends with an undetermined length",
            "below `-1` throws `IllegalArgumentException`",
            "`HEAD` requests and `304` responses keep a non-negative length as `Content-Length` without a body",
            "`204` responses send neither body nor `Content-Length`", "owns the stream as `res.stream` does",
            "Express has no counterpart")
        for ((where, s) in listOf("response.md" to k, "en/response.md" to e)) {
            for (lang in listOf("java", "kotlin")) {
                val b = blocks(s, lang)
                assertEquals("$where $lang relay blocks", 1, b.size)
                assertTrue("$where $lang block relays: ${b[0]}", b[0].contains("res.relay("))
            }
        }
        has("response.md", row("response.md", ko(), "| `relay(in, contentLength)` |"), "Express")
        has("en/response.md", row("en/response.md", en(), "| `relay(in, contentLength)` |"), "Express")
    }

    @Test
    fun `OPS-DX-009 AC-5 response md documents HttpHeaderValues isFieldValue next to isToken`() {
        val k = section(ko(), "## 헤더 검사")
        has("response.md header checks", k, "`com.snoworca.naruserve.common`", "`HttpHeaderValues.isToken(name)`",
            "`HttpHeaderValues.isFieldValue(value)`", "`res.header`와 `res.append`",
            "빈 문자열과 HTAB(탭)은 `true`", "CR, LF, NUL, DEL과 U+00FF를 넘는 문자는 `false`",
            "Express에는 없습니다")
        val e = section(en(), "## Header checks")
        has("en/response.md header checks", e, "`com.snoworca.naruserve.common`", "`HttpHeaderValues.isToken(name)`",
            "`HttpHeaderValues.isFieldValue(value)`", "`res.header` and `res.append`",
            "`true` for an empty string and for HTAB (tab)", "`false` for CR, LF, NUL, DEL and any character above U+00FF",
            "Express has none")
        for ((where, s) in listOf("response.md" to k, "en/response.md" to e)) {
            assertTrue("$where names isToken before isFieldValue",
                s.indexOf("HttpHeaderValues.isToken(") in 0 until s.indexOf("HttpHeaderValues.isFieldValue("))
            for (lang in listOf("java", "kotlin")) {
                val b = blocks(s, lang)
                assertEquals("$where $lang header-check blocks", 1, b.size)
                assertTrue("$where $lang block checks a value: ${b[0]}", b[0].contains("HttpHeaderValues.isFieldValue("))
            }
        }
        // The stated values are what the code answers.
        assertTrue(HttpHeaderValues.isFieldValue(""))
        assertTrue(HttpHeaderValues.isFieldValue("\t"))
        for (c in listOf('\r', '\n', '\u0000', '\u007f', 'Ā')) {
            assertFalse("isFieldValue U+%04X".format(c.code), HttpHeaderValues.isFieldValue("a${c}b"))
        }
        assertTrue(HttpHeaderValues.isFieldValue("ÿ"))
    }

    @Test
    fun `OPS-DX-009 AC-6 the raw response members appear on both pages and their Kotlin blocks have one marker each`() {
        // LP-10 / C-005: append(, onAbort( and relay( are documented on both pages.
        for ((where, doc) in listOf("response.md" to ko(), "en/response.md" to en())) {
            for (member in listOf("append(", "onAbort(", "relay(", "isFieldValue(")) {
                assertTrue("$where lacks $member", doc.contains(member))
            }
        }
        val docs = sortedMapOf("response.md" to ko(), "en/response.md" to en())
        val sources = sortedMapOf<String, String>()
        for (r in listOf("NaruServe-api/src/test/kotlin", "NaruServe-kotlin/src/test/kotlin", "NaruServe-servlet/src/test/kotlin")) {
            File(root(), r).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
                .forEach { sources["$r/${it.relativeTo(File(root(), r)).invariantSeparatorsPath}"] = it.readText() }
        }
        // Markers of other pages have no block in this two-page map, so only the response page failures count.
        val failures = KotlinDocsMarkers.failures(docs, sources)
            .filter { it.startsWith("response.md#") || it.startsWith("en/response.md#") }
        assertEquals(failures.joinToString("\n"), emptyList<String>(), failures)
        assertTrue("response.md holds the raw response Kotlin blocks", blocks(ko(), "kotlin").size >= 7)
        assertEquals(blocks(ko(), "kotlin").size, blocks(en(), "kotlin").size)
    }

    @Test
    fun `OPS-DX-009 AC-7 response md states the 304 Content-Length of res relay per adapter`() {
        has("response.md relay", section(ko(), "## 길이를 아는 스트림 전달"),
            "`304` 응답에서 `res.relay`는 길이를 어댑터에 넘기고",
            "클라이언트는 어댑터가 그 `Content-Length`를 쓸 때만 봅니다",
            "TinyHttpd 3.1.1은 `304`에 `Content-Length`를 쓰지 않으며(RFC 9110 §8.6이 허용합니다)",
            "서블릿 어댑터는 길이를 컨테이너에 넘깁니다",
            "`HEAD` 응답은 모든 기본 제공 어댑터에서 `Content-Length`를 유지합니다")
        has("en/response.md relay", section(en(), "## Relaying a stream with its length"),
            "On a `304` response `res.relay` hands its length to the adapter",
            "the client sees that `Content-Length` only where the adapter writes it",
            "TinyHttpd 3.1.1 omits it (allowed by RFC 9110 §8.6)",
            "the servlet adapter hands it to the container",
            "a `HEAD` response keeps it on every built-in adapter")
    }

    @Test
    fun `OPS-DX-009 carry-forward FR-HTTP-036 AC-5 the relay and defaultCache Javadocs say a relayed response never receives defaultCache`() {
        val relay = javadocBefore(read("NaruServe-api/src/main/java/com/snoworca/naruserve/Response.java"),
            "default void relay(InputStream in, long contentLength)")
        assertTrue(relay, relay.contains("never receives") && relay.contains("defaultCache"))
        val cache = javadocBefore(read("NaruServe-api/src/main/java/com/snoworca/naruserve/RestApp.java"),
            "RestApp defaultCache(@Nullable CachePolicy policy);")
        assertTrue(cache, cache.contains("never receives") && cache.contains("Response#relay"))
    }

    // docs-example: response.md#4
    @Test
    fun `OPS-DX-009 AC-1 the append example sends two Set-Cookie lines`() {
        app.get("/login") { req, res ->
            res.append("Set-Cookie", "theme=dark; Path=/")
                .append("Set-Cookie", "lang=ko; Path=/")
                .send("ok")
        }
        val response = send("GET", "/login")
        assertEquals(200, response.status())
        assertEquals(listOf("theme=dark; Path=/", "lang=ko; Path=/"),
            response.headers().filter { it.key.equals("Set-Cookie", true) }.map { it.value })
        assertEquals("ok", response.text())
    }

    // docs-example: response.md#5
    @Test(timeout = 20000)
    fun `OPS-DX-009 AC-2 the onAbort example cancels the job when the response is aborted`() {
        val jobs = LinkedBlockingQueue<Future<*>>()
        val responses = LinkedBlockingQueue<Response>()
        val workers = Executors.newCachedThreadPool()
        app.get("/report") { req, res ->
            val job = workers.submit(Runnable {
                Thread.sleep(5000)
                res.send("report")
            })
            res.onAbort { job.cancel(true) }
            jobs.add(job)
            responses.add(res)
        }
        try {
            if (!started) {
                t.start()
                started = true
            }
            val pending = adapter.submit(ClientRequest.of("GET", "/report"))
            val res = responses.poll(5, TimeUnit.SECONDS)
            assertNotNull("the handler did not run", res)
            // The in-memory adapter reports no disconnect, so the app's abort stands in for the client leaving.
            res!!.abort(null)
            val job = jobs.poll(5, TimeUnit.SECONDS)!!
            assertTrue("the abort listener cancelled the job", job.isCancelled)
            assertTrue(pending.get(10, TimeUnit.SECONDS).abnormallyClosed())
        } finally {
            workers.shutdownNow()
        }
    }

    // docs-example: response.md#6
    // adapted: a temporary file stands in for File("/data/backup.bin"), and the app sets a defaultCache to show
    // that the relayed response does not take it
    @Test
    fun `OPS-DX-009 AC-4 the relay example sends the file with its length and the app's Content-Type only`() {
        val backup = File(temp, "backup.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        app.defaultCache(CachePolicy.noCache())
        app.get("/backup") { req, res ->
            res.type("application/octet-stream")
            res.relay(FileInputStream(backup), backup.length())
        }
        val response = send("GET", "/backup")
        assertEquals(200, response.status())
        assertEquals("application/octet-stream", response.header("Content-Type"))
        assertEquals("5", response.header("Content-Length"))
        assertNull("a relayed response never receives defaultCache", response.header("Cache-Control"))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), response.body().toList())
    }

    // docs-example: response.md#7
    @Test
    fun `OPS-DX-009 AC-5 the header-check example answers 400 for a value isFieldValue refuses`() {
        app.get("/echo") { req, res ->
            val value = req.query("v")
            if (value == null || !HttpHeaderValues.isFieldValue(value)) {
                res.status(400).send("bad header value")
            } else {
                res.header("X-Echo", value).send("ok")
            }
        }
        val ok = send("GET", "/echo?v=abc")
        assertEquals(200, ok.status())
        assertEquals("abc", ok.header("X-Echo"))
        assertEquals(400, send("GET", "/echo?v=a%0D%0Ab").status())
        assertEquals(400, send("GET", "/echo?v=%C4%80").status())
        assertEquals(400, send("GET", "/echo").status())
    }
}
