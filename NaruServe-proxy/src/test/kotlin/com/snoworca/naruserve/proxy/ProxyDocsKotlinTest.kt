package com.snoworca.naruserve.proxy

import com.snoworca.naruserve.HttpException
import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.testing.RecordingLogger
import com.snoworca.naruserve.testing.TestApp
import java.io.File
import java.io.Reader
import java.io.Writer
import java.lang.reflect.Type
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/api/proxy.md and its English mirror document Proxy, its options, ProxyException and every limit and warning of
 * the proxy (OPS-DX-010): the stated facts are read from both pages, and the pages' Kotlin blocks run here against
 * raw test backends (KotlinDocsMarkers ordinals of proxy.md).
 */
class ProxyDocsKotlinTest {

    private val t = TestApp.create()
    private val app: RestApp = t.app()
    private var started = false
    private val backends = ArrayList<RawBackend>()

    @After
    fun tearDown() {
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS)
        }
        for (b in backends) {
            b.close()
        }
    }

    private fun backend(raw: String): RawBackend {
        val b = RawBackend.answering(raw)
        backends.add(b)
        return b
    }

    private fun send(method: String, target: String, vararg headerPairs: String) =
        t.send(method, target, *headerPairs).also { started = true }

    private fun root(): File = File(System.getProperty("user.dir")).parentFile

    private fun read(path: String): String = String(Files.readAllBytes(File(root(), path).toPath()), Charsets.UTF_8)

    private fun ko(): String = read("docs/api/proxy.md")

    private fun en(): String = read("docs/api/en/proxy.md")

    /** The text from the heading line up to the next heading of the same or a higher level outside a code fence. */
    private fun section(doc: String, heading: String): String {
        val start = doc.indexOf("\n$heading\n")
        assertTrue("missing heading $heading", start >= 0)
        val level = heading.takeWhile { it == '#' }.length
        val out = StringBuilder()
        var fence = false
        for ((i, line) in doc.substring(start + 1).split("\n").withIndex()) {
            if (line.startsWith("```")) fence = !fence
            if (i > 0 && !fence && line.startsWith("#")) {
                val l = line.takeWhile { it == '#' }.length
                if (l in 1..level && line.length > l && line[l] == ' ') break
            }
            out.append(line).append('\n')
        }
        return out.toString()
    }

    private fun has(where: String, text: String, vararg needles: String) {
        for (n in needles) {
            assertTrue("$where lacks: $n", text.contains(n))
        }
    }

    /** The one table line that starts with [prefix]. */
    private fun row(where: String, doc: String, prefix: String): String {
        val rows = doc.split("\n").filter { it.startsWith(prefix) }
        assertEquals("$where rows starting with $prefix", 1, rows.size)
        return rows[0]
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

    /** A code block with its `//` comments and trailing spaces removed, for comparing the two languages' code. */
    private fun code(block: String): String =
        block.split("\n").joinToString("\n") { it.replace(Regex("\\s*//.*$"), "").trimEnd() }.trim()

    private fun freeUrl(): String {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    @Test
    fun `OPS-DX-010 AC-1 both pages start with their navigation line and name the module, artifact, package and the Express counterpart`() {
        assertEquals("[English](en/proxy.md) | [문서 목록](README.md)", ko().split("\n")[2])
        assertEquals("[한국어](../proxy.md) | [API index](README.md)", en().split("\n")[2])
        has("proxy.md", section(ko(), "## 모듈"), "| 모듈 | `NaruServe-proxy` |",
            "| 아티팩트 | `com.snoworca.naruserve:naruserve-proxy` |", "| 의존성 | `NaruServe-api`만 |",
            "| 패키지 | `com.snoworca.naruserve.proxy` |", "`NaruServe-proxy`는 `NaruServe-api`에만 의존하고",
            "`import com.snoworca.naruserve.proxy.Proxy;`",
            "| `app.use(path, createProxyMiddleware({ target }))` | `app.use(path, Proxy.to(url))` |",
            "Express (`http-proxy-middleware`)")
        has("en/proxy.md", section(en(), "## Module"), "| Module | `NaruServe-proxy` |",
            "| Artifact | `com.snoworca.naruserve:naruserve-proxy` |", "| Dependencies | `NaruServe-api` only |",
            "| Package | `com.snoworca.naruserve.proxy` |", "`NaruServe-proxy` depends on `NaruServe-api` only",
            "`import com.snoworca.naruserve.proxy.Proxy;`",
            "| `app.use(path, createProxyMiddleware({ target }))` | `app.use(path, Proxy.to(url))` |",
            "Express (`http-proxy-middleware`)")
    }

    /**
     * The cells of a Markdown table line: the leading and trailing `|` are removed and the rest is split at every `|`
     * outside a backtick code span (a `\|` stays in its cell). A line that does not start and end with `|` fails.
     */
    private fun cells(where: String, line: String): List<String> {
        assertTrue("$where: a table line starts and ends with |: $line", line.length >= 2 && line.startsWith("|") && line.endsWith("|"))
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var code = false
        val inner = line.substring(1, line.length - 1)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length && inner[i + 1] == '|') {
                cell.append("\\|")
                i += 2
                continue
            }
            if (c == '`') code = !code
            if (c == '|' && !code) {
                out.add(cell.toString().trim())
                cell.setLength(0)
            } else {
                cell.append(c)
            }
            i++
        }
        assertFalse("$where: a code span is left open: $line", code)
        out.add(cell.toString().trim())
        return out
    }

    /**
     * The rows of the one table of [text] whose head line is [head], as cells: every line after the delimiter line
     * (`|---`) up to the first line that does not start with `|` is a row, and each row has as many cells as the head.
     */
    private fun table(where: String, text: String, head: String): List<List<String>> {
        val lines = text.split("\n")
        val heads = lines.withIndex().filter { it.value == head }.map { it.index }
        assertEquals("$where tables headed $head", 1, heads.size)
        assertTrue("$where: the line after $head is the delimiter line", lines.getOrElse(heads[0] + 1) { "" }.startsWith("|---"))
        val width = cells(where, head).size
        val out = ArrayList<List<String>>()
        for (line in lines.drop(heads[0] + 2)) {
            if (!line.startsWith("|")) break
            val c = cells(where, line)
            assertEquals("$where cells of the table row $line", width, c.size)
            out.add(c)
        }
        return out
    }

    /**
     * One option row: its signature cell and, per language, the whole default, range and exceptions, and
     * http-proxy-middleware cells as the page states them.
     */
    private class OptionRow(val sig: String, val ko: List<String>, val en: List<String>)

    // OPS-DX-010 AC-2 accepted ranges, as Proxy (FR-PROXY-002) checks them: timeouts above 0, retries and retryDelay
    // 0 or more, bodyLimit 1..MAX_BODY_LIMIT, maxConcurrent 1 or more, header names checked by HeaderRules; each range
    // cell names the exceptions the option throws
    private val positiveKo = "0보다 커야 합니다. 0 이하는 `IllegalArgumentException`, `unit`이 `null`이면 `NullPointerException`"
    private val positiveEn = "Must be above 0. 0 or less throws `IllegalArgumentException`, a `null` `unit` throws `NullPointerException`"
    private val anyKo = "모든 값을 받습니다"
    private val anyEn = "Accepts every value"

    private val optionRows = listOf(
        OptionRow("`connectTimeout(long, TimeUnit)`",
            ko = listOf("10초", positiveKo,
                "대응 옵션 없음"),
            en = listOf("10 seconds", positiveEn,
                "No counterpart")),
        OptionRow("`writeTimeout(long, TimeUnit)`",
            ko = listOf("60초", positiveKo,
                "대응 옵션 없음"),
            en = listOf("60 seconds", positiveEn,
                "No counterpart")),
        OptionRow("`readTimeout(long, TimeUnit)`",
            ko = listOf("60초", positiveKo,
                "`proxyTimeout`"),
            en = listOf("60 seconds", positiveEn,
                "`proxyTimeout`")),
        OptionRow("`totalTimeout(long, TimeUnit)`",
            ko = listOf("5분", positiveKo,
                "대응 옵션 없음(`timeout`은 들어오는 소켓의 유휴 제한입니다)"),
            en = listOf("5 minutes", positiveEn,
                "No counterpart (`timeout` is the idle limit of the incoming socket)")),
        OptionRow("`retries(int)`",
            ko = listOf("0(재시도 없음)", "0 이상. 음수는 `IllegalArgumentException`",
                "대응 옵션 없음(재시도 기능이 없습니다)"),
            en = listOf("0 (no retries)", "0 or more. A negative value throws `IllegalArgumentException`",
                "No counterpart (it has no retries)")),
        OptionRow("`retryDelay(long, TimeUnit)`",
            ko = listOf("200밀리초", "0 이상. 음수는 `IllegalArgumentException`, `unit`이 `null`이면 `NullPointerException`",
                "대응 옵션 없음"),
            en = listOf("200 milliseconds", "0 or more. A negative value throws `IllegalArgumentException`, a `null` `unit` throws `NullPointerException`",
                "No counterpart")),
        OptionRow("`retryUnsafeMethods(boolean)`",
            ko = listOf("`false`", anyKo,
                "대응 옵션 없음"),
            en = listOf("`false`", anyEn,
                "No counterpart")),
        OptionRow("`stripPrefix(boolean)`",
            ko = listOf("`false`(접두사를 남깁니다)", anyKo,
                "`pathRewrite`, 또는 3.x에서 마운트 경로를 빼는 `app.use(path, ...)`"),
            en = listOf("`false` (the prefix is kept)", anyEn,
                "`pathRewrite`, or the 3.x `app.use(path, ...)` that removes the mount path")),
        OptionRow("`preserveHost(boolean)`",
            ko = listOf("`false`(대상 호스트를 `Host`로 보냅니다)", anyKo,
                "`changeOrigin: false`(http-proxy-middleware의 기본값). NaruServe의 기본값은 `changeOrigin: true`에 해당합니다"),
            en = listOf("`false` (the target host is sent as `Host`)", anyEn,
                "`changeOrigin: false` (the http-proxy-middleware default). The NaruServe default corresponds to `changeOrigin: true`")),
        OptionRow("`bodyLimit(long)`",
            ko = listOf("앱의 `rawBodyLimit`(기본 1 MiB)", "`1..1073741824`(1 GiB). 밖이면 `IllegalArgumentException`",
                "대응 옵션 없음"),
            en = listOf("The app's `rawBodyLimit` (default 1 MiB)", "`1..1073741824` (1 GiB). Outside it throws `IllegalArgumentException`",
                "No counterpart")),
        OptionRow("`maxConcurrent(int)`",
            ko = listOf("한도 없음", "1 이상. 0 이하는 `IllegalArgumentException`",
                "대응 옵션 없음(`agent`의 `maxSockets`는 넘친 요청을 기다리게 합니다)"),
            en = listOf("No limit", "1 or more. 0 or less throws `IllegalArgumentException`",
                "No counterpart (the `maxSockets` of an `agent` makes excess requests wait)")),
        OptionRow("`requestHeader(String, String)`",
            ko = listOf("없음", "이름은 HTTP 토큰이면서 프록시가 관리하는 이름이 아니어야 하고, 값은 헤더 값이어야 합니다. 어기면 `IllegalArgumentException`, `null`이면 `NullPointerException`",
                "`headers: { name: value }`"),
            en = listOf("None", "The name must be an HTTP token that the proxy does not manage, and the value a header value. Otherwise `IllegalArgumentException`; `null` throws `NullPointerException`",
                "`headers: { name: value }`")),
        OptionRow("`removeRequestHeader(String)`",
            ko = listOf("없음", "이름은 HTTP 토큰이면서 프록시가 관리하는 이름이 아니어야 합니다. 어기면 `IllegalArgumentException`, `null`이면 `NullPointerException`",
                "`on.proxyReq`에서 `proxyReq.removeHeader(name)`"),
            en = listOf("None", "The name must be an HTTP token that the proxy does not manage. Otherwise `IllegalArgumentException`; `null` throws `NullPointerException`",
                "`proxyReq.removeHeader(name)` in `on.proxyReq`")),
        OptionRow("`responseHeader(String, String)`",
            ko = listOf("없음", "이름은 HTTP 토큰이면서 hop-by-hop 헤더나 `Content-Length`가 아니어야 하고, 값은 헤더 값이어야 합니다. 어기면 `IllegalArgumentException`, `null`이면 `NullPointerException`",
                "`on.proxyRes`에서 `proxyRes.headers[name] = value`"),
            en = listOf("None", "The name must be an HTTP token that is neither a hop-by-hop header nor `Content-Length`, and the value a header value. Otherwise `IllegalArgumentException`; `null` throws `NullPointerException`",
                "`proxyRes.headers[name] = value` in `on.proxyRes`")),
        OptionRow("`removeResponseHeader(String)`",
            ko = listOf("없음", "이름은 HTTP 토큰이면서 hop-by-hop 헤더나 `Content-Length`가 아니어야 합니다. 어기면 `IllegalArgumentException`, `null`이면 `NullPointerException`",
                "`on.proxyRes`에서 `delete proxyRes.headers[name]`"),
            en = listOf("None", "The name must be an HTTP token that is neither a hop-by-hop header nor `Content-Length`. Otherwise `IllegalArgumentException`; `null` throws `NullPointerException`",
                "`delete proxyRes.headers[name]` in `on.proxyRes`")),
        OptionRow("`rewriteLocation(boolean)`",
            ko = listOf("`true`", anyKo,
                "`autoRewrite`, `hostRewrite`, `protocolRewrite`"),
            en = listOf("`true`", anyEn,
                "`autoRewrite`, `hostRewrite`, `protocolRewrite`")),
        OptionRow("`sslSocketFactory(SSLSocketFactory)`",
            ko = listOf("JDK 기본 팩토리", "`null`이면 `NullPointerException`. `http` 대상도 받지만 쓰지 않습니다",
                "`agent`(예: `new https.Agent({ ca })`). `secure: false`에 해당하는 옵션은 없습니다"),
            en = listOf("The JDK default factory", "`null` throws `NullPointerException`. An `http` target accepts it and never uses it",
                "`agent` (for example `new https.Agent({ ca })`). Nothing corresponds to `secure: false`")),
    )

    @Test
    fun `OPS-DX-010 AC-2 the option table lists every option with its types, default, range, exceptions and http-proxy-middleware counterpart`() {
        // Every public option method of Proxy has its row: the table is checked against the class, not a copied list.
        val publicOptions = Proxy::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.returnType == Proxy::class.java && it.name != "to" }
            .map { it.name }.toSet()
        assertEquals(publicOptions, optionRows.map { it.sig.substring(1, it.sig.indexOf('(')) }.toSet())
        for ((where, s, head) in listOf(
                Triple("proxy.md", section(ko(), "## 옵션"), "| 옵션 | 기본값 | 허용 범위와 예외 | http-proxy-middleware |"),
                Triple("en/proxy.md", section(en(), "## Options"),
                    "| Option | Default | Accepted range and exceptions | http-proxy-middleware |"))) {
            // OPS-DX-010 AC-2: the whole table block is one row per option, in order, and every cell is compared whole,
            // so a range with a different bound or an added exception ("... except `true`") fails here
            val rows = table(where, s, head)
            assertEquals("$where option table rows", optionRows.map { it.sig }, rows.map { it[0] })
            for ((o, r) in optionRows.zip(rows)) {
                val expected = if (where == "proxy.md") o.ko else o.en
                assertEquals("$where ${o.sig} default", expected[0], r[1])
                assertEquals("$where ${o.sig} accepted range and exceptions", expected[1], r[2])
                assertEquals("$where ${o.sig} http-proxy-middleware counterpart", expected[2], r[3])
            }
        }
        has("proxy.md", section(ko(), "## 기본 사용"), "`Proxy`는 `Middleware`를 구현하므로",
            "옵션 메서드는 모두 새 `Proxy`를 돌려주고 받는 쪽은 그대로 두므로", "`Proxy`는 바뀌지 않는(immutable) 값입니다")
        has("en/proxy.md", section(en(), "## Basic use"), "`Proxy` implements `Middleware`",
            "Every option method returns a new `Proxy` and leaves its receiver unchanged", "A `Proxy` is immutable")
        has("proxy.md", section(ko(), "## 옵션"), "`Connection`, `Keep-Alive`, `Proxy-Connection`, `TE`, `Transfer-Encoding`",
            "`Host`, `Content-Length`, `Expect`, `Forwarded`, `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host`",
            "`requestHeader(\"host\", \"h\")`도 `IllegalArgumentException`",
            "밀리초로 올림해서 적어도 1 ms, 많아야 `Integer.MAX_VALUE` ms")
        has("en/proxy.md", section(en(), "## Options"), "`Connection`, `Keep-Alive`, `Proxy-Connection`, `TE`, `Transfer-Encoding`",
            "`Host`, `Content-Length`, `Expect`, `Forwarded`, `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host`",
            "`requestHeader(\"host\", \"h\")` also throws `IllegalArgumentException`",
            "rounded up to at least 1 ms and at most `Integer.MAX_VALUE` ms")
    }

    @Test
    fun `OPS-DX-010 AC-2 the stated ranges and defaults match the Proxy class`() {
        val p = Proxy.to("http://127.0.0.1:1")
        assertEquals(TimeUnit.SECONDS.toNanos(10), p.connectTimeoutNanos())
        assertEquals(TimeUnit.SECONDS.toNanos(60), p.writeTimeoutNanos())
        assertEquals(TimeUnit.SECONDS.toNanos(60), p.readTimeoutNanos())
        assertEquals(TimeUnit.MINUTES.toNanos(5), p.totalTimeoutNanos())
        assertEquals(0, p.retries())
        assertEquals(TimeUnit.MILLISECONDS.toNanos(200), p.retryDelayNanos())
        assertFalse(p.retryUnsafeMethods() || p.stripPrefix() || p.preserveHost())
        assertTrue(p.rewriteLocation())
        assertEquals(Proxy.NO_CONCURRENCY_LIMIT, p.maxConcurrent())
        assertEquals(Proxy.NO_BODY_LIMIT, p.bodyLimit())
        assertEquals(1073741824L, Proxy.MAX_BODY_LIMIT)
        // the lower and upper ends of each stated range are accepted
        assertEquals(0, p.retries(0).retries())
        assertEquals(0L, p.retryDelay(0, TimeUnit.MILLISECONDS).retryDelayNanos())
        assertEquals(1, p.maxConcurrent(1).maxConcurrent())
        assertEquals(1L, p.bodyLimit(1).bodyLimit())
        assertEquals(Proxy.MAX_BODY_LIMIT, p.bodyLimit(1073741824).bodyLimit())
        assertEquals(1L, p.connectTimeout(1, TimeUnit.NANOSECONDS).connectTimeoutNanos())
        for (bad in listOf<() -> Any>({ p.connectTimeout(0, TimeUnit.SECONDS) }, { p.retries(-1) },
                { p.retryDelay(-1, TimeUnit.MILLISECONDS) }, { p.bodyLimit(0) }, { p.bodyLimit(1073741825) },
                { p.maxConcurrent(0) }, { p.requestHeader("host", "h") }, { p.responseHeader("Content-Length", "1") })) {
            try {
                bad()
                throw AssertionError("no IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `OPS-DX-010 AC-3 the error section documents ProxyException, its kinds and statuses, the plain HttpException answers and the abort after the response`() {
        val kinds = ProxyException.Kind.values()
        val statuses = mapOf("CONNECT_FAILED" to 502, "CONNECT_TIMEOUT" to 504, "WRITE_TIMEOUT" to 504,
            "READ_TIMEOUT" to 504, "TOTAL_TIMEOUT" to 504, "INVALID_RESPONSE" to 502, "CONCURRENCY_LIMIT" to 503)
        assertEquals(kinds.map { it.name }.toSet(), statuses.keys)
        for (k in kinds) {
            assertEquals(k.name, statuses[k.name], ProxyException(k, null).status())
        }
        for ((where, doc, heading) in listOf(Triple("proxy.md", ko(), "## 오류"), Triple("en/proxy.md", en(), "## Errors"))) {
            val s = section(doc, heading)
            for ((name, status) in statuses) {
                row(where, s, "| `$name` | $status |")
            }
        }
        val k = section(ko(), "## 오류")
        has("proxy.md errors", k, "`ProxyException`은 `HttpException`의 하위 클래스이고 `kind()`가",
            "메시지(`getMessage()`)는 언제나 `null`입니다", "실패의 원인은 `getCause()`에 있습니다",
            "`400`(잘못된 메서드·경로·헤더·프레이밍), `405`(`CONNECT`·`TRACE`), `501`(지원하지 않는 전송 코딩)은 `ProxyException`이 아닌 보통 `HttpException`",
            "원인을 클래스 이름으로만 보여 주므로", "앱 로거(`req.application().logger()`)에 한 번 기록하고, 프레임워크가 클라이언트 연결을 중단합니다",
            "`new ProxyException(kind, cause)`", "`err.code`")
        val e = section(en(), "## Errors")
        has("en/proxy.md errors", e, "`ProxyException` is a subclass of `HttpException`, and `kind()` returns",
            "The message (`getMessage()`) is always `null`", "The underlying failure is in `getCause()`",
            "`400` (bad method, path, header or framing), `405` (`CONNECT`, `TRACE`) and `501` (unsupported transfer coding) are plain `HttpException`, not `ProxyException`",
            "shows the causes of a `ProxyException` by class name only", "logs a `ProxyException` of that kind once to the app logger (`req.application().logger()`), and the framework aborts the client connection",
            "`new ProxyException(kind, cause)`", "`err.code`")
        oneJavaAndOneKotlin("proxy.md errors", k, listOf("app.onError(", "instanceof ProxyException", "e.kind().name()", ".json("),
            listOf("app.onError {", "is ProxyException ->", "error.kind().name", ".json("))
        oneJavaAndOneKotlin("en/proxy.md errors", e, listOf("app.onError(", "instanceof ProxyException", "e.kind().name()", ".json("),
            listOf("app.onError {", "is ProxyException ->", "error.kind().name", ".json("))
    }

    // OPS-DX-010 AC-3: a failure before the response, without onError, is read from a proxy to a closed port with a
    // recording app logger: neither the proxy nor the default error flow (which logs status 500 only) writes an entry,
    // so both pages must say so and their onError examples log the ProxyException themselves.
    @Test
    fun `OPS-DX-010 AC-3 a failure before the response leaves no logger entry and the pages leave logging to onError`() {
        val log = RecordingLogger()
        app.logger(log)
        app.use("/api", Proxy.to(freeUrl()))
        val r = send("GET", "/api/x")
        assertEquals(502, r.status())
        assertEquals("a 502 without onError writes no logger error or warning: ${log.lines}", emptyList<String>(),
            log.lines.filter { !it.startsWith("INFO ") })
        assertTrue("no Throwable reached the app logger: ${log.errors}", log.errors.isEmpty())

        val k = section(ko(), "### 응답을 보내기 전의 실패")
        val e = section(en(), "### Failures before the response")
        for ((where, s) in listOf("proxy.md" to k, "en/proxy.md" to e)) {
            for (wrong in listOf("로깅은 오류 흐름에 맡깁니다", "앱 로거와 `getCause()`에는 원인이 그대로 남습니다",
                    "logging is left to the error flow", "The app logger and `getCause()` keep the full cause")) {
                assertFalse("$where still promises a log the default flow never writes: $wrong", s.contains(wrong))
            }
            for (lang in listOf("java", "kotlin")) {
                val b = blocks(s, lang)
                assertEquals("$where $lang blocks", 1, b.size)
                assertTrue("$where $lang onError example logs the ProxyException: ${b[0]}",
                    b[0].contains("req.application().logger().error("))
            }
        }
        has("proxy.md before the response", k,
            "프록시도 기본 오류 흐름도 이 실패를 앱 로거에 기록하지 않습니다",
            "기본 오류 흐름은 상태가 `500`인 실패만 기록하고, `ProxyException`은 `502`·`503`·`504`입니다",
            "기록이 필요하면 `onError`에서 `req.application().logger()`로 직접 기록하세요",
            "원인은 `getCause()`에 그대로 남습니다")
        has("en/proxy.md before the response", e,
            "Neither the proxy nor the default error flow writes this failure to the app logger",
            "The default error flow logs only failures with status `500`, and a `ProxyException` is `502`, `503` or `504`",
            "To get a log entry, log it yourself in `onError` through `req.application().logger()`",
            "The cause stays whole in `getCause()`")
    }

    @Test
    fun `OPS-DX-010 AC-4 the basic use section shares one Proxy, mounts the no-retry path first and states that no next() follows a forward`() {
        val java = listOf("Proxy.to(url).readTimeout(30, TimeUnit.SECONDS)", ".totalTimeout(10, TimeUnit.MINUTES)",
            ".requestHeader(\"X-Gateway-Source\", \"gateway\")", "app.use(\"/api/payments\", api);", "app.use(\"/api\", api.retries(2));")
        val kotlin = listOf("val api = Proxy.to(url).readTimeout(30, TimeUnit.SECONDS)", ".totalTimeout(10, TimeUnit.MINUTES)",
            ".requestHeader(\"X-Gateway-Source\", \"gateway\")", "app.use(\"/api/payments\", api)", "app.use(\"/api\", api.retries(2))")
        val k = section(ko(), "## 기본 사용")
        val e = section(en(), "## Basic use")
        oneJavaAndOneKotlin("proxy.md basic use", k.substring(0, k.indexOf("\n### ")), java, kotlin)
        oneJavaAndOneKotlin("en/proxy.md basic use", e.substring(0, e.indexOf("\n### ")), java, kotlin)
        for (s in listOf(k, e)) {
            val kt = blocks(s, "kotlin")[0]
            assertTrue(kt.indexOf("\"/api/payments\"") < kt.indexOf("\"/api\", api.retries(2)"))
        }
        has("proxy.md basic use", k, "프록시는 요청을 백엔드로 넘긴 뒤에는 `next()`를 부르지 않습니다",
            "같은 접두사 아래에서는 **먼저 등록한 마운트**가 요청을 처리하고", "감싸는 함수나 확장 없이")
        has("en/proxy.md basic use", e, "The proxy never calls `next()` after it forwarded a request",
            "under one prefix the **mount registered first** handles the request", "without a wrapper function or extension")
    }

    @Test
    fun `OPS-DX-010 AC-5 the page warns that address-based checks are not reliable behind the proxy and advises against trustProxy(true)`() {
        has("proxy.md", section(ko(), "### 주소로 하는 검사는 프록시 뒤에서 믿을 수 없습니다"),
            "소켓 주소를 보는 백엔드에는 모든 요청이 프록시에서 온 것으로 보입니다(같은 호스트면 `127.0.0.1`)",
            "`X-Forwarded-For`의 맨 왼쪽 값을 보는 백엔드는 속을 수 있습니다", "클라이언트가 보낸 값을 그대로 둔 채",
            "\"localhost에서 온 요청만 허용\" 같은 검사는 외부 클라이언트에게도 통과됩니다", "주소가 아닌 수단으로 옮기세요",
            "관리자 키", "밖에 열지 않은 별도 포트", "레거시 `trustProxy(true)`는 프록시와 함께 쓰지 마세요",
            "클라이언트가 주소를 위조할 수 있습니다")
        has("en/proxy.md", section(en(), "### Address-based checks are not reliable behind the proxy"),
            "A backend that looks at the socket address sees every request coming from the proxy (`127.0.0.1` on the same host)",
            "A backend that looks at the leftmost `X-Forwarded-For` value can be fooled", "it keeps the values the client sent",
            "A check such as \"allow only localhost\" lets outside clients through", "to means other than the address",
            "an admin key", "a separate port that is not exposed outside", "Do not use the legacy `trustProxy(true)` with the proxy",
            "clients can forge the address")
    }

    @Test
    fun `OPS-DX-010 AC-6 the page warns against representation headers set before the proxy and names the gateway's Date and reason phrase`() {
        has("proxy.md", section(ko(), "## 응답 전달"), "**프록시 앞에서 표현 메타데이터 헤더를 정하지 마세요.**",
            "`Content-Type`, `Content-Encoding`, `ETag`", "공개 `Response`에는 헤더를 지우는 메서드가 없으므로",
            "앱의 값이 백엔드 본문에 그대로 붙어 나갑니다",
            "클라이언트가 보는 `Date` 헤더와 상태 줄의 reason phrase는 백엔드의 것이 아니라 게이트웨이 어댑터가 쓴 것입니다")
        has("en/proxy.md", section(en(), "## Response relay"), "**Do not set representation metadata headers before the proxy.**",
            "`Content-Type`, `Content-Encoding` or `ETag`", "The public `Response` has no method that removes a header",
            "the app's value stays on the relayed backend body",
            "The `Date` header and the reason phrase of the status line that clients see come from the gateway's adapter, not from the backend")
    }

    @Test
    fun `OPS-DX-010 AC-7 the timeout section states the four defaults, that none is unlimited, the first reached and the two cases the total timeout cannot release`() {
        val k = section(ko(), "## 타임아웃")
        row("proxy.md", k, "| `connectTimeout` | 10초 |")
        row("proxy.md", k, "| `writeTimeout` | 60초 |")
        row("proxy.md", k, "| `readTimeout` | 60초 |")
        row("proxy.md", k, "| `totalTimeout` | 5분 |")
        has("proxy.md timeouts", k, "무제한을 뜻하는 값은 없고", "먼저 닿은 타임아웃이 적용됩니다",
            "응답을 넘기기 전에 닿으면 `504`가 오류 흐름으로 가고, 응답을 넘기기 시작한 뒤에 닿으면 클라이언트 연결을 중단하고",
            "기본 전체 타임아웃 5분은 긴 다운로드도 자릅니다", "`totalTimeout`을 늘리세요",
            "소켓이 생기기 전의 이름 풀이(DNS)는 JDK 리졸버를 따르고",
            "클라이언트와의 I/O에 막혀 있는 시간(천천히 도착하는 요청 본문, 응답을 천천히 읽는 클라이언트)은 어댑터의 시간 제한에 맡깁니다")
        val e = section(en(), "## Timeouts")
        row("en/proxy.md", e, "| `connectTimeout` | 10 seconds |")
        row("en/proxy.md", e, "| `writeTimeout` | 60 seconds |")
        row("en/proxy.md", e, "| `readTimeout` | 60 seconds |")
        row("en/proxy.md", e, "| `totalTimeout` | 5 minutes |")
        has("en/proxy.md timeouts", e, "No value means unlimited", "The first timeout reached applies",
            "Reached before the response is relayed, a timeout is a `504` through the error flow; reached after the relay started, it aborts the client connection",
            "The default 5-minute total timeout also cuts long downloads", "Raise `totalTimeout`",
            "name resolution (DNS) before the socket exists follows the JDK resolver",
            "time blocked in I/O with the client (a slowly arriving request body, a client that reads the response slowly) is left to the adapter's time limits")
        oneJavaAndOneKotlin("proxy.md timeouts", k, listOf(".totalTimeout(2, TimeUnit.HOURS)"), listOf(".totalTimeout(2, TimeUnit.HOURS)"))
        oneJavaAndOneKotlin("en/proxy.md timeouts", e, listOf(".totalTimeout(2, TimeUnit.HOURS)"), listOf(".totalTimeout(2, TimeUnit.HOURS)"))
    }

    @Test
    fun `OPS-DX-010 AC-8 the retry section states retries are off, what retries(n) and retryUnsafeMethods retry, and the side-effect GET warning`() {
        has("proxy.md", section(ko(), "## 재시도"), "재시도는 기본으로 꺼져 있습니다(`retries(0)`)",
            "새 연결로 최대 `n`번 더 보냅니다", "TCP 수준의 연결 실패(연결 거부, 이름 풀이 실패, 경로 없음) 뒤에는 모든 메서드를 재시도합니다",
            "연결 타임아웃과 TLS 실패는 재시도하지 않습니다",
            "백엔드에 보낸 본문이 0바이트(chunked가 아니고 길이가 0)이고 메서드가 `GET`, `HEAD`, `OPTIONS`일 때만 재시도합니다",
            "`retryUnsafeMethods(true)`면 본문이 0바이트인 모든 메서드를 재시도합니다", "`retryDelay`(기본 200밀리초)",
            "**안전한 메서드는 HTTP 의미의 구분일 뿐입니다.**", "부수 효과가 있는 `GET` 엔드포인트", "재시도로 두 번 실행될 수 있습니다",
            "재시도를 끈 프록시를 따로 두고")
        has("en/proxy.md", section(en(), "## Retries"), "Retries are off by default (`retries(0)`)",
            "sent up to `n` more times, each time over a new connection",
            "After a TCP-level connect failure (connection refused, unresolved name, no route) every method is retried",
            "A connect timeout and a TLS failure are not retried",
            "only when the body sent to the backend was zero bytes (not chunked and length 0) and the method is `GET`, `HEAD` or `OPTIONS`",
            "With `retryUnsafeMethods(true)` every method with a zero-byte body is retried", "`retryDelay` (default 200 milliseconds)",
            "**Safe methods are an HTTP semantics distinction only.**", "A `GET` endpoint with side effects", "may run twice under retries",
            "Give such a path its own proxy with retries off")
    }

    @Test
    fun `OPS-DX-010 AC-9 the thread model section states the bound worker thread, the late disconnect, the pool exhaustion and what bounds the threads`() {
        has("proxy.md", section(ko(), "## 스레드 모델"), "프록시를 거치는 요청은 끝날 때까지 작업 스레드 하나를 잡고 있습니다",
            "어댑터는 응답을 보내기 시작할 때에야 그것을 알 수 있습니다",
            "스레드는 백엔드가 답하거나 읽기 타임아웃이나 전체 타임아웃이 끝날 때까지 묶여 있습니다",
            "작업 스레드 풀(`maxWorkers`, 기본 64)을 다 써서 프록시가 아닌 라우트까지 기다리게 만들 수 있습니다",
            "읽기 타임아웃을 길게 잡을수록 더 그렇습니다",
            "기본으로는 `maxConcurrent` 한도가 없으므로, 프록시가 잡는 스레드 수를 묶는 것은 `maxWorkers`와 `totalTimeout`입니다")
        has("en/proxy.md", section(en(), "## Thread model"), "A proxied request holds one worker thread until it ends",
            "the adapter may notice only when the response starts",
            "the thread stays bound until the backend answers, the read timeout or the total timeout",
            "Slow backends can use up the worker pool (`maxWorkers`, default 64) and make non-proxy routes wait",
            "more so with a longer read timeout",
            "Since there is no `maxConcurrent` limit by default, `maxWorkers` and `totalTimeout` are what bound the threads a proxy holds")
    }

    @Test
    fun `OPS-DX-010 AC-10 the concurrency section states the shared counter rules, the immediate 503 and the maxConcurrent(16) example`() {
        val java = listOf("Proxy p = Proxy.to(url).maxConcurrent(16);", "app.use(\"/search\", p);", "app.use(\"/export\", p.retries(0));")
        val kotlin = listOf("val p = Proxy.to(url).maxConcurrent(16)", "app.use(\"/search\", p)", "app.use(\"/export\", p.retries(0))")
        val k = section(ko(), "## 동시 요청 한도")
        val e = section(en(), "## Concurrency limit")
        oneJavaAndOneKotlin("proxy.md concurrency", k, java, kotlin)
        oneJavaAndOneKotlin("en/proxy.md concurrency", e, java, kotlin)
        has("proxy.md concurrency", k, "`maxConcurrent`로 만든 객체와 그 객체에서 다른 옵션 메서드로 파생한 객체들은 카운터 하나를 함께 씁니다",
            "파생한 객체에 `maxConcurrent`를 다시 부르면 새 카운터가 생깁니다",
            "`Proxy.to`를 따로 불러 만든 `Proxy`는 같은 대상이라도 카운터를 나누지 않습니다", "한도가 없는 마운트의 요청은 세지 않습니다",
            "같은 객체를 두 앱에 붙이면 두 앱의 요청을 함께 셉니다",
            "한도를 넘는 요청은 기다리지 않고 곧바로 `503`(`CONCURRENCY_LIMIT`)이며", "p.retries(0)은 한도 16을 함께 씁니다")
        has("en/proxy.md concurrency", e, "An object created by `maxConcurrent` and the objects derived from it through other option methods share one counter",
            "Calling `maxConcurrent` again on a derived object starts a new counter",
            "A `Proxy` created by another `Proxy.to` call never shares a counter", "Requests of mounts without a limit are not counted",
            "The same object mounted in two apps counts the requests of both apps",
            "A request over the limit gets `503` (`CONCURRENCY_LIMIT`) at once, without waiting", "p.retries(0) share the limit of 16")
    }

    @Test
    fun `OPS-DX-010 AC-11 the backend connection section states the new connection per request, the TIME_WAIT warning and the JVM-wide watchdog`() {
        has("proxy.md", section(ko(), "## 백엔드 연결"), "새 연결을 열고 `Connection: close`를 보내며, 연결을 다시 쓰지 않습니다",
            "TIME_WAIT", "임시 포트(ephemeral port)가 바닥날 수 있습니다", "근본 해결은 연결 풀이며 이 버전에는 없습니다",
            "JVM 전체에 하나뿐인 스케줄러", "첫 프록시 요청이 필요할 때 만들고", "앱 수명주기 밖에 있어",
            "데몬 스레드 하나", "늦어도 30초 안에", "종료 훅이 필요 없습니다")
        has("en/proxy.md", section(en(), "## Backend connections"), "opens a new connection and sends `Connection: close`, with no reuse",
            "TIME_WAIT", "the ephemeral ports of the proxy host can run out", "The root remedy is a connection pool, which this version does not have",
            "one JVM-wide scheduler", "The first proxy request that needs it creates it lazily", "it lives outside the app lifecycle",
            "one daemon thread", "within 30 seconds at the latest", "it needs no shutdown hook")
    }

    @Test
    fun `OPS-DX-010 AC-12 the English page mirrors the Korean page section by section with the same code`() {
        fun headings(doc: String) = doc.split("\n").filter { it.startsWith("## ") || it.startsWith("### ") }.size
        assertEquals(headings(ko()), headings(en()))
        fun tableRows(doc: String) = doc.split("\n").count { it.startsWith("| ") }
        assertEquals(tableRows(ko()), tableRows(en()))
        for (lang in listOf("java", "kotlin")) {
            val kb = blocks(ko(), lang).map { code(it) }
            val eb = blocks(en(), lang).map { code(it) }
            assertEquals("$lang blocks", kb, eb)
            assertTrue("$lang blocks present", kb.size >= 5)
        }
    }

    // docs-example: proxy.md#1
    @Test
    fun `OPS-DX-010 AC-4 the basic example forwards with the fixed header and retries only the general mount`() {
        val backend = backend(RawBackend.response(503, "busy"))
        val url = backend.url()
        val api = Proxy.to(url).readTimeout(30, TimeUnit.SECONDS)
            .totalTimeout(10, TimeUnit.MINUTES)
            .requestHeader("X-Gateway-Source", "gateway")
        app.use("/api/payments", api)
        app.use("/api", api.retries(2))

        assertEquals(503, send("GET", "/api/payments/charge").status())
        assertEquals("the first mount handles it once, without retries", 1, backend.requestCount())
        assertEquals("GET /api/payments/charge HTTP/1.1", backend.requests()[0].requestLine())
        assertEquals(503, send("GET", "/api/orders/7").status())
        assertEquals("the /api mount retries twice", 4, backend.requestCount())
        for (r in backend.requests()) {
            assertEquals(listOf("gateway"), r.headerValues("X-Gateway-Source"))
        }
        assertEquals(TimeUnit.SECONDS.toNanos(30), api.readTimeoutNanos())
    }

    // docs-example: proxy.md#2
    @Test
    fun `OPS-DX-010 AC-2 the fixed header example strips the prefix and sets and removes the named headers`() {
        val backend = backend("HTTP/1.1 200 X\r\nServer: backend/1\r\nCache-Control: max-age=60\r\nContent-Length: 2\r\n\r\nok")
        val url = backend.url()
        val users = Proxy.to(url)
            .stripPrefix(true)
            .requestHeader("X-Api-Key", "internal-key")
            .removeRequestHeader("Cookie")
            .responseHeader("Cache-Control", "no-store")
            .removeResponseHeader("Server")
        app.use("/users", users)

        val r = send("GET", "/users/42", "X-Api-Key", "client-key", "Cookie", "sid=1")
        val received = backend.onlyRequest()
        assertEquals("GET /42 HTTP/1.1", received.requestLine())
        assertEquals(listOf("internal-key"), received.headerValues("X-Api-Key"))
        assertEquals(emptyList<String>(), received.headerValues("Cookie"))
        assertEquals("ok", r.text())
        assertEquals("no-store", r.header("Cache-Control"))
        assertNull(r.header("Server"))
    }

    /** Encodes a map of strings as a JSON object, enough for the onError example. */
    private class MapCodec : JsonCodec {
        override fun <T : Any> decode(reader: Reader, type: Type): T = throw UnsupportedOperationException("decode")

        override fun encode(value: Any?, writer: Writer) {
            val map = value as Map<*, *>
            writer.write(map.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" })
        }
    }

    // docs-example: proxy.md#3
    @Test
    fun `OPS-DX-010 AC-3 the onError example answers JSON by kind and plain HttpException answers apart`() {
        val adapter = InMemoryHttpAdapter()
        val log = RecordingLogger()
        val app = RestApp.builder().httpAdapter(adapter).jsonCodec(MapCodec()).logger(log).build()
        try {
            val url = freeUrl()
            app.use("/api", Proxy.to(url))
            app.get("/late") { req, res -> throw ProxyException(ProxyException.Kind.READ_TIMEOUT, null) }
            app.get("/bug") { req, res -> throw IllegalStateException("bug") }
            app.onError { error, req, res ->
                when (error) {
                    is ProxyException -> {
                        req.application().logger().error("upstream ${error.kind().name}", error)
                        res.status(error.status()).json(mapOf("error" to "upstream", "kind" to error.kind().name))
                    }
                    is HttpException -> res.status(error.status()).json(mapOf("error" to "request"))
                    else -> res.status(500).json(mapOf("error" to "internal"))
                }
            }
            app.listen(0)

            val down = adapter.send(ClientRequest.of("GET", "/api/orders"))
            assertEquals(502, down.status())
            assertEquals("{\"error\":\"upstream\",\"kind\":\"CONNECT_FAILED\"}", down.text())
            // OPS-DX-010 AC-3: the example's own log line is the only logger entry of the 502, and it keeps the cause
            assertEquals(listOf("ERROR upstream CONNECT_FAILED null"), log.lines.filter { !it.startsWith("INFO ") })
            val logged = log.errors.single() as ProxyException
            assertEquals(ProxyException.Kind.CONNECT_FAILED, logged.kind())
            assertTrue("the logged cause is the connect failure: ${logged.cause}", logged.cause is java.net.ConnectException)
            val late = adapter.send(ClientRequest.of("GET", "/late"))
            assertEquals(504, late.status())
            assertEquals("{\"error\":\"upstream\",\"kind\":\"READ_TIMEOUT\"}", late.text())
            val trace = adapter.send(ClientRequest.of("TRACE", "/api/orders"))
            assertEquals(405, trace.status())
            assertEquals("the proxy's own 405 is a plain HttpException", "{\"error\":\"request\"}", trace.text())
            val bug = adapter.send(ClientRequest.of("GET", "/bug"))
            assertEquals(500, bug.status())
            assertEquals("{\"error\":\"internal\"}", bug.text())
        } finally {
            app.shutdown(2, TimeUnit.SECONDS)
        }
    }

    // docs-example: proxy.md#4
    @Test
    fun `OPS-DX-010 AC-7 the download example raises the total timeout and forwards`() {
        val backend = backend(RawBackend.response(200, "file"))
        val url = backend.url()
        app.use("/downloads", Proxy.to(url)
            .totalTimeout(2, TimeUnit.HOURS)
            .connectTimeout(3, TimeUnit.SECONDS))

        val r = send("GET", "/downloads/a.bin")
        assertEquals(200, r.status())
        assertEquals("file", r.text())
        assertEquals("GET /downloads/a.bin HTTP/1.1", backend.onlyRequest().requestLine())
    }

    // docs-example: proxy.md#5
    @Test
    fun `OPS-DX-010 AC-10 the maxConcurrent example shares one counter between p and p retries(0) only`() {
        val backend = backend(RawBackend.response(200, "hit"))
        val url = backend.url()
        val p = Proxy.to(url).maxConcurrent(16)
        app.use("/search", p)
        app.use("/export", p.retries(0))
        app.use("/other", Proxy.to(url).maxConcurrent(16))

        assertEquals(16, p.maxConcurrent())
        assertSame(p.concurrencyCounter(), p.retries(0).concurrencyCounter())
        assertNotSame(p.concurrencyCounter(), Proxy.to(url).maxConcurrent(16).concurrencyCounter())
        assertNotSame(p.concurrencyCounter(), p.maxConcurrent(16).concurrencyCounter())
        for (path in listOf("/search/q", "/export/all", "/other/x")) {
            assertEquals("hit", send("GET", path).text())
        }
        assertEquals(3, backend.requestCount())
        assertEquals(0, p.concurrencyCounter()!!.get())
    }

    // docs-example: proxy.md#6
    @Test
    fun `OPS-DX-010 AC-2 the private CA example passes an SSLSocketFactory`() {
        val tls = SSLContext.getDefault()
        app.use("/internal", Proxy.to("https://backend.internal:8443").sslSocketFactory(tls.socketFactory))

        t.start()
        started = true
        assertNotNull(Proxy.to("https://backend.internal:8443").sslSocketFactory(tls.socketFactory).sslSocketFactory())
    }
}
