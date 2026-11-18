package com.snoworca.naruserve

import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse
import com.snoworca.naruserve.testing.RecordingLogger
import com.snoworca.naruserve.testing.TestApp
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * docs/api/request.md, docs/api/app.md and their English mirrors document the raw request API (req.bodyStream,
 * the two over-limit forms for onError, req.headerNames, req.rawHeaders, req.isFromTrustedProxy, app.rawBodyLimit)
 * and the 0.12.0 compatibility changes: the stated facts are read from the four pages, and the pages' Kotlin blocks
 * for those members run here over the in-memory adapter (KotlinDocsMarkers ordinals of request.md and app.md).
 */
class RawRequestDocsKotlinTest {

    private val adapter = InMemoryHttpAdapter()
    private val t = TestApp.create(adapter)
    private val app: RestApp = t.app()
    private var started = false
    private val others = ArrayList<RestApp>()

    @After
    fun tearDown() {
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS)
        }
        for (o in others) {
            o.shutdown(2, TimeUnit.SECONDS)
        }
    }

    private fun send(request: ClientRequest): ClientResponse {
        if (!started) {
            t.start()
            started = true
        }
        return adapter.send(request)
    }

    private fun root(): File = File(System.getProperty("user.dir")).parentFile

    private fun read(path: String): String = String(Files.readAllBytes(File(root(), path).toPath()), Charsets.UTF_8)

    private fun ko(): String = read("docs/api/request.md")

    private fun en(): String = read("docs/api/en/request.md")

    private fun koApp(): String = read("docs/api/app.md")

    private fun enApp(): String = read("docs/api/en/app.md")

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

    /** Asserts that [s] holds exactly one Java and one Kotlin block and that each holds every [needles] entry. */
    private fun oneJavaAndOneKotlin(where: String, s: String, java: List<String>, kotlin: List<String>) {
        for ((lang, needles) in listOf("java" to java, "kotlin" to kotlin)) {
            val b = blocks(s, lang)
            assertEquals("$where $lang blocks", 1, b.size)
            for (n in needles) {
                assertTrue("$where $lang block lacks $n: ${b[0]}", b[0].contains(n))
            }
        }
    }

    private fun bytes(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun `OPS-DX-008 AC-1 request md documents bodyStream and bodyStream maxBytes with their rules and examples`() {
        val k = section(ko(), "## 원시 본문 스트림")
        has("request.md bodyStream", k, "`req.bodyStream()`", "`req.bodyStream(maxBytes)`",
            "Express에서 `req`를 스트림으로 읽던 것", "`express.raw({ limit })`",
            "chunked 전송 코딩만 벗기고 `Content-Encoding`(예: gzip)은 풀지 않습니다",
            "`null`이 아닌 빈 스트림", "첫 `read()`가 `-1`",
            "`body(...)`, `form(...)`, `parts()`가 `BodyAlreadyConsumedException`",
            "`body(...)`가 본문을 읽은 뒤에 부른 `bodyStream`도 `BodyAlreadyConsumedException`",
            "같은 한도로 다시 부르면 같은 스트림 객체를 돌려주고", "다른 한도로 부르면 `IllegalStateException`",
            "한도와 상관없이 `BodyAlreadyConsumedException`",
            "`bodyStream()`의 한도는 `app.rawBodyLimit(bytes)`", "`1..1073741824`", "`IllegalArgumentException`",
            "프레임워크가 닫습니다")
        val e = section(en(), "## Raw body stream")
        has("en/request.md bodyStream", e, "`req.bodyStream()`", "`req.bodyStream(maxBytes)`",
            "reading Express `req` as a stream", "`express.raw({ limit })`",
            "removes only the chunked transfer coding and keeps `Content-Encoding` (for example gzip)",
            "an empty, non-null stream", "its first `read()` returns `-1`",
            "`body(...)`, `form(...)` and `parts()` throw `BodyAlreadyConsumedException`",
            "a `bodyStream` call after `body(...)` read the body throws `BodyAlreadyConsumedException`",
            "a repeated call with the same limit returns the same stream object", "another limit throws `IllegalStateException`",
            "throws `BodyAlreadyConsumedException` whatever the limit",
            "the limit of `bodyStream()` is `app.rawBodyLimit(bytes)`", "`1..1073741824`", "`IllegalArgumentException`",
            "the framework closes it")
        for ((where, s) in listOf("request.md" to k, "en/request.md" to e)) {
            oneJavaAndOneKotlin("$where bodyStream", s, listOf("req.bodyStream()", "req.bodyStream(64L * 1024 * 1024)"),
                listOf("req.bodyStream()", "req.bodyStream(64L * 1024 * 1024)"))
        }
        has("request.md", row("request.md", ko(), "| `bodyStream()` |"), "`InputStream`", "`req`")
        has("request.md", row("request.md", ko(), "| `bodyStream(maxBytes)` |"), "`express.raw({ limit })`")
        has("en/request.md", row("en/request.md", en(), "| `bodyStream()` |"), "`InputStream`", "`req`")
        has("en/request.md", row("en/request.md", en(), "| `bodyStream(maxBytes)` |"), "`express.raw({ limit })`")
    }

    @Test
    fun `OPS-DX-008 AC-2 request md states the two over-limit forms and how onError recognises them`() {
        val k = section(ko(), "## 한도를 넘은 원시 본문")
        has("request.md over-limit", k, "두 가지 모양", "선언한 `Content-Length`가 한도보다 큼",
            "`status()`가 `413`인 `HttpException`", "`bodyStream` 호출 자체", "한 바이트도 읽기 전에",
            "길이를 선언하지 않은 본문", "`BodyTooLargeException`",
            "`onError`에서 본문 한도 초과를 알아보려면 두 모양을 모두 확인하세요",
            "감싸지 않은 채 핸들러 밖으로 나간 `BodyTooLargeException`은 `413` 응답",
            "다른 예외로 감싼 것", "일반 예외로 처리되어 `500`")
        val e = section(en(), "## Raw body over the limit")
        has("en/request.md over-limit", e, "two forms", "the declared `Content-Length` exceeds the limit",
            "`HttpException` with `status()` `413`", "the `bodyStream` call itself", "before any byte is read",
            "a body without a declared length", "`BodyTooLargeException`",
            "an `onError` handler that wants to recognise an over-limit body must check both forms",
            "an unwrapped `BodyTooLargeException` that leaves the handler becomes a `413` response",
            "wrapped in another exception", "handled as an ordinary exception and answered `500`")
        for ((where, s) in listOf("request.md" to k, "en/request.md" to e)) {
            oneJavaAndOneKotlin("$where over-limit", s,
                listOf("instanceof BodyTooLargeException", "instanceof HttpException", "status() == 413", "app.onError("),
                listOf("is BodyTooLargeException", "is HttpException", "status() == 413", "app.onError {"))
        }
        // Each form sits in its own table row, so swapped rows fail.
        has("request.md", row("request.md", k, "| 선언한 `Content-Length`가 한도보다 큼 |"),
            "`status()`가 `413`인 `HttpException`", "`bodyStream` 호출 자체", "한 바이트도 읽기 전에")
        has("request.md", row("request.md", k, "| 길이를 선언하지 않은 본문(chunked)이 한도를 넘음 |"),
            "`BodyTooLargeException`", "`IOException`", "`limit()`", "`read`가 던집니다")
        has("en/request.md", row("en/request.md", e, "| the declared `Content-Length` exceeds the limit |"),
            "`HttpException` with `status()` `413`", "the `bodyStream` call itself", "before any byte is read")
        has("en/request.md", row("en/request.md", e, "| a body without a declared length (chunked) passes the limit |"),
            "`BodyTooLargeException`", "`IOException`", "`limit()`", "the `read` that would return a byte beyond the limit throws")
    }

    // OPS-DX-008 AC-2: the default answer to an unwrapped overrun is read from a default app (no onError) and both
    // pages must describe that answer: the common HTTP default error page, not a JSON body with a code, and no log.
    @Test
    fun `OPS-DX-008 AC-2 both pages describe the default 413 of an unwrapped overrun as the app gives it`() {
        val log = RecordingLogger()
        app.logger(log)
        app.rawBodyLimit(10)
        app.post("/hooks") { req, res ->
            val raw = req.bodyStream().use { it.readBytes() }
            res.send("received ${raw.size} bytes")
        }
        val r = send(ClientRequest.of("POST", "/hooks").body(bytes(32)))
        assertEquals(413, r.status())
        val type = r.header("Content-Type")
        assertEquals("the default 413 is the common HTTP error page", "text/html; charset=utf-8", type)
        assertTrue("the page carries the escaped stack: ${r.text()}", r.text().contains("BodyTooLargeException"))
        assertFalse("no JSON error code in the default 413: ${r.text()}", r.text().contains("PAYLOAD_TOO_LARGE"))
        assertFalse("no JSON error body in the default 413", r.text().trim().startsWith("{"))
        assertEquals("the default 413 writes no logger error or warning: ${log.lines}", emptyList<String>(),
            log.lines.filter { !it.startsWith("INFO ") })

        val k = section(ko(), "## 한도를 넘은 원시 본문")
        val e = section(en(), "## Raw body over the limit")
        for ((where, s) in listOf("request.md" to k, "en/request.md" to e)) {
            assertFalse("$where must not promise a JSON body with a code for the default 413", s.contains("PAYLOAD_TOO_LARGE"))
        }
        has("request.md over-limit default answer", k, "`onError`가 응답하지 않으면 이 `413`은 공통 HTTP 기본 오류 페이지입니다",
            "`$type`", "`code`가 있는 JSON 오류 본문이 아닙니다", "(defaults.md#기본-오류-본문과-노출-정보)",
            "앱 로거에 기록하지 않습니다")
        has("en/request.md over-limit default answer", e, "When `onError` does not answer, this `413` is the common HTTP default error page",
            "`$type`", "not a JSON error body with a `code`", "(defaults.md#default-error-body-and-exposed-details)",
            "is not written to the app logger")
    }

    @Test
    fun `OPS-DX-008 AC-3 request md documents headerNames and rawHeaders with their Express counterparts`() {
        val k = section(ko(), "## 헤더 목록")
        has("request.md headers", k, "`req.headerNames()`", "Express `Object.keys(req.headers)`",
            "`req.rawHeaders()`", "Express `req.rawHeaders`", "소문자로 바꾼 이름", "어댑터가 알려 준 순서",
            "대소문자와 중복을 그대로", "`UnsupportedOperationException`", "서블릿 어댑터",
            "컨테이너가 헤더 이름을 열거하는 순서대로 이름마다 값을 묶어")
        val e = section(en(), "## Header enumeration")
        has("en/request.md headers", e, "`req.headerNames()`", "Express `Object.keys(req.headers)`",
            "`req.rawHeaders()`", "Express `req.rawHeaders`", "lowercase names", "in the order the adapter reported",
            "keeps case and duplicates", "`UnsupportedOperationException`", "servlet adapter",
            "groups the values by name in the container's enumeration order")
        for ((where, s) in listOf("request.md" to k, "en/request.md" to e)) {
            oneJavaAndOneKotlin("$where headers", s, listOf("req.headerNames()", "req.rawHeaders()"),
                listOf("req.headerNames()", "req.rawHeaders()"))
        }
        // The header table of "Path parameters, query and headers" lists both with their Express counterparts.
        for ((where, doc) in listOf("request.md" to ko(), "en/request.md" to en())) {
            val rows = doc.split("\n")
            assertTrue("$where header table row of headerNames()",
                rows.any { it.startsWith("| `headerNames()` |") && it.contains("`Object.keys(req.headers)`") })
            assertTrue("$where header table row of rawHeaders()",
                rows.any { it.startsWith("| `rawHeaders()` |") && it.contains("`req.rawHeaders`") })
        }
    }

    @Test
    fun `OPS-DX-008 AC-4 request md documents isFromTrustedProxy with its Express counterpart and result table`() {
        val k = section(ko(), "## 프록시에서 온 요청")
        has("request.md trusted proxy", k, "`req.isFromTrustedProxy()`",
            "Express `app.get('trust proxy fn')(req.socket.remoteAddress, 0)`",
            "| 신뢰 설정 없음 | `false` |", "| `trustProxyHops(0)` | `false` |", "| 빈 `trustProxyCidrs()` | `false` |",
            "| `trustProxyHops(n)`, n ≥ 1 | `true` |", "| 레거시 `trustProxy(true)` | `true` |",
            "| `trustProxyCidrs(...)` | 직접 연결한 상대(`socketAddress()`)가 목록의 CIDR 안일 때만 `true` |")
        val e = section(en(), "## Requests from a trusted proxy")
        has("en/request.md trusted proxy", e, "`req.isFromTrustedProxy()`",
            "Express `app.get('trust proxy fn')(req.socket.remoteAddress, 0)`",
            "| no trust setting | `false` |", "| `trustProxyHops(0)` | `false` |", "| empty `trustProxyCidrs()` | `false` |",
            "| `trustProxyHops(n)`, n ≥ 1 | `true` |", "| legacy `trustProxy(true)` | `true` |",
            "| `trustProxyCidrs(...)` | `true` only when the direct peer (`socketAddress()`) lies inside a listed CIDR |")
        for ((where, s) in listOf("request.md" to k, "en/request.md" to e)) {
            oneJavaAndOneKotlin("$where trusted proxy", s, listOf("req.isFromTrustedProxy()"), listOf("req.isFromTrustedProxy"))
        }
        has("request.md", row("request.md", ko(), "| `isFromTrustedProxy()` |"), "`boolean`", "trust proxy fn")
        has("en/request.md", row("en/request.md", en(), "| `isFromTrustedProxy()` |"), "boolean", "trust proxy fn")
        // AuthCoreDocsTest pins these proxy-trust lines; the new section keeps them.
        has("request.md", ko(), "`remoteAddress()`는 프록시 신뢰 설정을 따릅니다", "`app.trustProxy(true)`")
        has("en/request.md", en(), "`remoteAddress()` follows the proxy trust setting", "app.trustProxy(true)")
    }

    /** What isFromTrustedProxy answers on a fresh app whose proxy trust is set by [configure], peer [peer]. */
    private fun trusted(peer: String, configure: (RestApp) -> Unit): Boolean {
        val a = InMemoryHttpAdapter(peer, false)
        val other = TestApp.create(a)
        configure(other.app())
        other.app().get("/t") { req, res -> res.send(req.isFromTrustedProxy.toString()) }
        other.start()
        others.add(other.app())
        return a.send(ClientRequest.of("GET", "/t")).text().toBoolean()
    }

    @Test
    fun `OPS-DX-008 AC-4 the result table of isFromTrustedProxy is what the code answers`() {
        assertFalse("no trust setting", trusted("10.1.2.3") { })
        assertFalse("trustProxyHops(0)", trusted("10.1.2.3") { it.trustProxyHops(0) })
        assertFalse("empty trustProxyCidrs()", trusted("10.1.2.3") { it.trustProxyCidrs() })
        assertTrue("trustProxyHops(1)", trusted("10.1.2.3") { it.trustProxyHops(1) })
        assertTrue("trustProxyHops(3)", trusted("10.1.2.3") { it.trustProxyHops(3) })
        assertTrue("legacy trustProxy(true)", trusted("10.1.2.3") { it.trustProxy(true) })
        assertTrue("peer inside a listed CIDR", trusted("10.1.2.3") { it.trustProxyCidrs("10.0.0.0/8") })
        assertFalse("peer outside every listed CIDR", trusted("192.168.0.1") { it.trustProxyCidrs("10.0.0.0/8") })
    }

    @Test
    fun `OPS-DX-008 AC-5 app md documents rawBodyLimit next to jsonLimit and formLimit with its Express counterpart`() {
        val k = section(koApp(), "## 원시 본문 한도")
        has("app.md rawBodyLimit", k, "`app.rawBodyLimit(bytes)`", "`jsonLimit`", "`formLimit`",
            "Express `express.raw({ limit })`", "`1..1073741824`", "기본값은 1 MiB", "`IllegalArgumentException`",
            "`jsonLimit`처럼 앱을 설정할 수 있는 동안(`NEW`)만 받습니다", "`IllegalStateException`",
            "앱 전체 한도를 올리지 말고", "`bodyStream(maxBytes)`")
        val e = section(enApp(), "## Raw body limit")
        has("en/app.md rawBodyLimit", e, "`app.rawBodyLimit(bytes)`", "`jsonLimit`", "`formLimit`",
            "Express `express.raw({ limit })`", "`1..1073741824`", "default is 1 MiB", "`IllegalArgumentException`",
            "only while the app is configurable (`NEW`), like `jsonLimit`", "`IllegalStateException`",
            "rather than raising the limit for the whole app", "`bodyStream(maxBytes)`")
        for ((where, s) in listOf("app.md" to k, "en/app.md" to e)) {
            oneJavaAndOneKotlin("$where rawBodyLimit", s, listOf("app.rawBodyLimit(256L * 1024)", "req.bodyStream(8L * 1024 * 1024)"),
                listOf("app.rawBodyLimit(256L * 1024)", "req.bodyStream(8L * 1024 * 1024)"))
        }
        // The configuration table lists rawBodyLimit right after jsonLimit and formLimit.
        for ((where, doc) in listOf("app.md" to koApp(), "en/app.md" to enApp())) {
            val lines = doc.split("\n")
            val json = lines.indexOfFirst { it.startsWith("| `jsonLimit(bytes)` |") }
            val form = lines.indexOfFirst { it.startsWith("| `formLimit(bytes)` |") }
            val raw = lines.indexOfFirst { it.startsWith("| `rawBodyLimit(bytes)` |") }
            assertTrue("$where configuration table rows: json $json form $form raw $raw", json >= 0 && form == json + 1 && raw == form + 1)
            has(where, lines[raw], "1 MiB", "1 GiB", "bodyStream")
        }
    }

    @Test
    fun `OPS-DX-008 AC-6 request md holds the 0_12_0 compatibility note on BodyTooLargeException`() {
        val k = section(ko(), "## 0.12.0 호환성 메모")
        has("request.md compatibility", k, "두 가지 동작", "감싸지 않은 `BodyTooLargeException`", "이제 `413`",
            "0.11.0에서는 `500`", "`com.snoworca.naruserve.internal`에서 `com.snoworca.naruserve`로 옮겨졌습니다",
            "import를 바꿔야 합니다")
        val e = section(en(), "## 0.12.0 compatibility notes")
        has("en/request.md compatibility", e, "two behaviour changes", "an unwrapped `BodyTooLargeException`",
            "now gives `413`", "0.11.0 gave `500`",
            "moved from `com.snoworca.naruserve.internal` to `com.snoworca.naruserve`", "must change its import")
        // The note is true of the code: the public class exists and the internal one is gone.
        assertEquals("com.snoworca.naruserve", BodyTooLargeException::class.java.`package`.name)
        try {
            Class.forName("com.snoworca.naruserve.internal.BodyTooLargeException")
            fail("the internal BodyTooLargeException still exists")
        } catch (expected: ClassNotFoundException) {
        }
    }

    @Test
    fun `OPS-DX-008 AC-7 C-005 both request pages document bodyStream headerNames rawHeaders and isFromTrustedProxy`() {
        // C-005 (wave-3 LP-3): a page that lacks any of these members fails here.
        for ((where, doc) in listOf("request.md" to ko(), "en/request.md" to en())) {
            for (member in listOf("bodyStream(", "bodyStream(maxBytes)", "headerNames(", "rawHeaders(", "isFromTrustedProxy(")) {
                assertTrue("$where lacks $member", doc.contains(member))
            }
        }
        for ((where, doc) in listOf("app.md" to koApp(), "en/app.md" to enApp())) {
            assertTrue("$where lacks rawBodyLimit(", doc.contains("rawBodyLimit("))
        }
    }

    @Test
    fun `OPS-DX-008 AC-7 the Kotlin blocks of request md and app md have one marker each in both languages`() {
        val docs = sortedMapOf("request.md" to ko(), "en/request.md" to en(), "app.md" to koApp(), "en/app.md" to enApp())
        val sources = sortedMapOf<String, String>()
        // the same Kotlin test roots KotlinDocsMarkersTest scans, so a marked run under any of them counts here too
        for (r in KotlinDocsMarkersTest.KOTLIN_ROOTS) {
            File(root(), r).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
                .forEach { sources["$r/${it.relativeTo(File(root(), r)).invariantSeparatorsPath}"] = it.readText() }
        }
        // Markers of other pages have no block in this four-page map, so only the request and app page failures count.
        val failures = KotlinDocsMarkers.failures(docs, sources).filter {
            it.startsWith("request.md#") || it.startsWith("en/request.md#") || it.startsWith("app.md#") || it.startsWith("en/app.md#")
        }
        assertEquals(failures.joinToString("\n"), emptyList<String>(), failures)
        assertTrue("request.md holds the raw request Kotlin blocks", blocks(ko(), "kotlin").size >= 7)
        assertEquals(blocks(ko(), "kotlin").size, blocks(en(), "kotlin").size)
        assertTrue("app.md holds the rawBodyLimit Kotlin block", blocks(koApp(), "kotlin").size >= 6)
        assertEquals(blocks(koApp(), "kotlin").size, blocks(enApp(), "kotlin").size)
    }

    // docs-example: request.md#4
    @Test
    fun `OPS-DX-008 AC-1 the bodyStream example reads the raw body under the app limit and a per-call limit`() {
        app.post("/webhook") { req, res ->
            val raw = req.bodyStream().use { it.readBytes() }
            res.send("received ${raw.size} bytes")
        }

        app.put("/backup") { req, res ->
            var size = 0L
            req.bodyStream(64L * 1024 * 1024).use { input ->
                val buf = ByteArray(8192)
                var n = input.read(buf)
                while (n != -1) {
                    size += n
                    n = input.read(buf)
                }
            }
            res.send("stored $size bytes")
        }
        assertEquals("received 5 bytes", send(ClientRequest.of("POST", "/webhook").body("{ }\n\n".toByteArray())).text())
        assertEquals("received 0 bytes", send(ClientRequest.of("POST", "/webhook")).text())
        // 2 MiB passes the per-call 64 MiB limit although the app's rawBodyLimit is 1 MiB.
        assertEquals("stored 2097152 bytes", send(ClientRequest.of("PUT", "/backup").body(bytes(2 * 1024 * 1024))).text())
        assertEquals(413, send(ClientRequest.of("POST", "/webhook").body(bytes(2 * 1024 * 1024))).status())
    }

    // docs-example: request.md#5
    @Test
    fun `OPS-DX-008 AC-2 the onError example recognises both over-limit forms and leaves a wrapped one to the 500 answer`() {
        app.rawBodyLimit(16)
        app.post("/hooks") { req, res ->
            val raw = req.bodyStream().use { it.readBytes() }
            res.send("received ${raw.size} bytes")
        }
        app.post("/wrapped") { req, res ->
            try {
                req.bodyStream().use { it.readBytes() }
            } catch (e: BodyTooLargeException) {
                throw RuntimeException(e)
            }
            res.send("unreachable")
        }
        val seen = ArrayList<Throwable>()
        app.use(ErrorMiddleware { error, _, _, next ->
            seen.add(error)
            next.error(error)
        })
        app.onError { error, req, res ->
            val tooLarge = error is BodyTooLargeException ||
                (error is HttpException && error.status() == 413)
            if (tooLarge) {
                res.status(413).send("body too large")
            }
        }
        val undeclared = send(ClientRequest.of("POST", "/hooks").body(bytes(32)))
        assertEquals(413, undeclared.status())
        assertEquals("body too large", undeclared.text())
        assertTrue("undeclared length overrun is BodyTooLargeException: $seen", seen.last() is BodyTooLargeException)
        assertEquals(16L, (seen.last() as BodyTooLargeException).limit())
        val declared = send(ClientRequest.of("POST", "/hooks").header("Content-Length", "32").body(bytes(32)))
        assertEquals(413, declared.status())
        assertEquals("body too large", declared.text())
        val declaredError = seen.last()
        assertTrue("declared length overrun is HttpException: $seen",
            declaredError is HttpException && declaredError.status() == 413)
        assertEquals("received 16 bytes", send(ClientRequest.of("POST", "/hooks").body(bytes(16))).text())
        assertEquals(500, send(ClientRequest.of("POST", "/wrapped").body(bytes(32))).status())
        assertTrue("the wrapped overrun reached the error layers wrapped: $seen",
            seen.last() is RuntimeException && seen.last().cause is BodyTooLargeException)
    }

    // docs-example: request.md#6
    @Test
    fun `OPS-DX-008 AC-3 the header enumeration example lists lowercase names and the raw lines`() {
        app.get("/headers") { req, res ->
            val names: Set<String> = req.headerNames()
            val raw: List<Map.Entry<String, String>> = req.rawHeaders()
            val lines = raw.joinToString("") { "${it.key}: ${it.value}\n" }
            res.send("$names\n$lines")
        }
        val response = send(ClientRequest.of("GET", "/headers").header("X-A", "1").header("x-a", "2").header("X-B", "3"))
        assertEquals("[x-a, x-b]\nX-A: 1\nx-a: 2\nX-B: 3\n", response.text())
    }

    // docs-example: request.md#7
    @Test
    fun `OPS-DX-008 AC-4 the isFromTrustedProxy example tells a forwarded request from a direct one`() {
        app.trustProxyHops(1)
        app.get("/via") { req, res ->
            if (req.isFromTrustedProxy) {
                res.send("forwarded for ${req.remoteAddress()} by ${req.socketAddress()}")
            } else {
                res.send("direct from ${req.socketAddress()}")
            }
        }
        val response = send(ClientRequest.of("GET", "/via").header("X-Forwarded-For", "203.0.113.7"))
        assertEquals("forwarded for 203.0.113.7 by 127.0.0.1", response.text())
    }

    // docs-example: app.md#6
    @Test
    fun `OPS-DX-008 AC-5 the rawBodyLimit example keeps a small app limit and raises it for one route`() {
        app.rawBodyLimit(256L * 1024).jsonLimit(64L * 1024)

        app.put("/archive") { req, res ->
            val bytes = req.bodyStream(8L * 1024 * 1024).use { it.readBytes() }
            res.send("stored ${bytes.size} bytes")
        }
        app.post("/small") { req, res ->
            val out = ByteArrayOutputStream()
            req.bodyStream().use { it.copyTo(out) }
            res.send("small ${out.size()}")
        }
        val body = bytes(300 * 1024)
        assertEquals("stored 307200 bytes", send(ClientRequest.of("PUT", "/archive").body(body)).text())
        assertEquals(413, send(ClientRequest.of("POST", "/small").body(body)).status())
        assertEquals("small 1024", send(ClientRequest.of("POST", "/small").body(bytes(1024))).text())
    }

    // C-005 (loop P wave-21 r5 LP-29): BodyTooLargeException is public API since 0.12.0, so both request pages give it
    // its own part of the over-limit section with the Express counterpart (raw-body's 413 `entity.too.large`
    // error), the public constructor and accessor read here by reflection, when the framework throws it, and one
    // Java and one Kotlin example that use the constructor and limit().
    @Test
    fun `C-005 OPS-DX-008 both request pages document BodyTooLargeException with its Express counterpart, constructor and accessor`() {
        val type = BodyTooLargeException::class.java
        assertTrue(java.lang.reflect.Modifier.isPublic(type.modifiers))
        assertTrue(java.lang.reflect.Modifier.isFinal(type.modifiers))
        assertEquals(java.io.IOException::class.java, type.superclass)
        val ctor = type.getConstructor(java.lang.Long.TYPE)
        assertEquals(1, type.constructors.size)
        val made = ctor.newInstance(10L)
        assertEquals(10L, made.limit())
        assertEquals("request body exceeds the limit of 10 bytes", made.message)
        assertEquals(java.lang.Long.TYPE, type.getMethod("limit").returnType)
        // thrown unwrapped by app code, the framework answers it 413 like its own overrun
        app.get("/thrown") { _, _ -> throw BodyTooLargeException(10) }
        assertEquals(413, send(ClientRequest.of("GET", "/thrown")).status())

        val k = section(ko(), "## 한도를 넘은 원시 본문")
        val kPart = k.substring(k.indexOf("\n### `BodyTooLargeException`\n").also {
            assertTrue("request.md has no ### `BodyTooLargeException` part in the over-limit section", it >= 0)
        })
        has("request.md BodyTooLargeException", kPart,
            "`com.snoworca.naruserve.BodyTooLargeException`", "`raw-body`", "`body-parser`", "`express.raw`",
            "`http-errors`의 `PayloadTooLargeError`", "`err.status`가 `413`", "`err.type`이 `'entity.too.large'`",
            "`limit()`은 Express의 `err.limit`에 해당합니다",
            "| `new BodyTooLargeException(long limit)` |", "| `limit()` | `long` |",
            "`request body exceeds the limit of <limit> bytes`", "`HttpException`이 아니므로 `status()`가 없습니다",
            "`bodyStream()`·`bodyStream(maxBytes)`가 돌려준 스트림",
            "`body(...)`, `form(...)`, `parts()`는 한도를 넘으면 이 예외가 아니라 `413` `HttpException`을 던집니다")
        val e = section(en(), "## Raw body over the limit")
        val ePart = e.substring(e.indexOf("\n### `BodyTooLargeException`\n").also {
            assertTrue("en/request.md has no ### `BodyTooLargeException` part in the over-limit section", it >= 0)
        })
        has("en/request.md BodyTooLargeException", ePart,
            "`com.snoworca.naruserve.BodyTooLargeException`", "`raw-body`", "`body-parser`", "`express.raw`",
            "the `http-errors` `PayloadTooLargeError`", "`err.status` `413`", "`err.type` `'entity.too.large'`",
            "`limit()` is the counterpart of Express `err.limit`",
            "| `new BodyTooLargeException(long limit)` |", "| `limit()` | `long` |",
            "`request body exceeds the limit of <limit> bytes`", "not an `HttpException`, so it has no `status()`",
            "the stream `bodyStream()` or `bodyStream(maxBytes)` returned",
            "`body(...)`, `form(...)` and `parts()` throw an `HttpException` `413` over their limits, not this exception")
        oneJavaAndOneKotlin("request.md BodyTooLargeException", kPart,
            listOf("throw new BodyTooLargeException(quota);", "((BodyTooLargeException) error).limit()"),
            listOf("throw BodyTooLargeException(quota)", "error.limit()"))
        oneJavaAndOneKotlin("en/request.md BodyTooLargeException", ePart,
            listOf("throw new BodyTooLargeException(quota);", "((BodyTooLargeException) error).limit()"),
            listOf("throw BodyTooLargeException(quota)", "error.limit()"))
    }
}
