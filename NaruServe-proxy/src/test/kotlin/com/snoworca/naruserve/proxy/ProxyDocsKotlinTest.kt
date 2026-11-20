package com.snoworca.naruserve.proxy

import com.snoworca.naruserve.HttpException
import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.testing.RecordingLogger
import com.snoworca.naruserve.testing.StubJsonCodec
import com.snoworca.naruserve.testing.TestApp
import java.io.File
import java.io.Reader
import java.io.Writer
import java.lang.reflect.Type
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        // FR-PROXY-022 AC-7, OPS-DX-010 AC-2: both trustCertificates overloads, with their argument rules
        OptionRow("`trustCertificates(String)`",
            ko = listOf("JDK 기본 신뢰 저장소", "`CERTIFICATE` 블록이 하나 이상 든 PEM 텍스트. 블록이 없거나 블록의 base64나 DER을 읽을 수 없으면 `IllegalArgumentException`, `null`이면 `NullPointerException`. `http` 대상도 받지만 쓰지 않습니다",
                "`agent: new https.Agent({ ca })`의 `ca`"),
            en = listOf("The JDK default trust store", "A PEM text holding one or more `CERTIFICATE` blocks. No block, or a block whose base64 or DER does not parse, throws `IllegalArgumentException`; `null` throws `NullPointerException`. An `http` target accepts it and never uses it",
                "`ca` of `agent: new https.Agent({ ca })`")),
        OptionRow("`trustCertificates(X509Certificate...)`",
            ko = listOf("JDK 기본 신뢰 저장소", "인증서 하나 이상. 빈 배열은 `IllegalArgumentException`, 배열이나 원소가 `null`이면 `NullPointerException`. `http` 대상도 받지만 쓰지 않습니다",
                "`agent: new https.Agent({ ca })`의 `ca`"),
            en = listOf("The JDK default trust store", "One or more certificates. An empty array throws `IllegalArgumentException`; a `null` array or element throws `NullPointerException`. An `http` target accepts it and never uses it",
                "`ca` of `agent: new https.Agent({ ca })`")),
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
            "느린 백엔드는 어댑터의 요청 스레드를 다 써서 프록시가 아닌 라우트까지 기다리게 만들 수 있습니다",
            "읽기 타임아웃을 길게 잡을수록 더 그렇습니다",
            "기본으로는 `maxConcurrent` 한도가 없으므로, 프록시가 잡는 스레드 수를 묶는 것은 어댑터의 요청 스레드 한도(TinyHttpd에서는 `maxWorkers + maxSseStreams`, 기본 192)와 `totalTimeout`입니다")
        has("en/proxy.md", section(en(), "## Thread model"), "A proxied request holds one worker thread until it ends",
            "the adapter may notice only when the response starts",
            "the thread stays bound until the backend answers, the read timeout or the total timeout",
            "Slow backends can use up the adapter's request threads and make non-proxy routes wait",
            "more so with a longer read timeout",
            "Since there is no `maxConcurrent` limit by default, the adapter's request-thread bound (on TinyHttpd `maxWorkers + maxSseStreams`, 192 by default) and `totalTimeout` are what bound the threads a proxy holds")
    }

    // OPS-DX-010 AC-9 (loop P wave-21 r6 LP-1): over real TinyHttpd sockets with maxWorkers(2) and maxSseStreams(3), six
    // clients send to a proxy whose backend reads each request head and never answers: five requests reach the backend
    // at once and the sixth waits, so the request-thread pool that bounds a proxy is maxWorkers + maxSseStreams, not
    // maxWorkers. Both thread model sections must state that bound (64 + 128 = 192 by default), link the pool rule in
    // defaults.md and no longer name maxWorkers (default 64) as the pool or the bound.
    @Test(timeout = 60000)
    fun `OPS-DX-010 AC-9 over TinyHttpd maxWorkers plus maxSseStreams requests reach a holding backend and both pages state that bound`() {
        val backend = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val held = java.util.Collections.synchronizedList(ArrayList<Socket>())
        val reached = AtomicInteger()
        val closing = java.util.concurrent.atomic.AtomicBoolean()
        val acceptor = Thread({
            while (!backend.isClosed) {
                try {
                    val s = backend.accept()
                    readHead(s.getInputStream())
                    held.add(s)
                    reached.incrementAndGet()
                    if (closing.get()) s.close() // held without an answer until the test ends
                } catch (e: java.io.IOException) {
                    return@Thread
                }
            }
        }, "docs-holding-backend")
        acceptor.isDaemon = true
        acceptor.start()
        val app = RestApp.builder().httpAdapter(TinyHttpdAdapter()).jsonCodec(StubJsonCodec()).logger(RecordingLogger()).build()
        val clients = ArrayList<Socket>()
        try {
            app.server { it.maxWorkers(2).maxSseStreams(3) }
            app.use("/api", Proxy.to("http://127.0.0.1:${backend.localPort}").readTimeout(30, TimeUnit.SECONDS))
            val port = app.listen(0).port()
            for (i in 1..6) {
                val c = Socket()
                clients.add(c)
                c.connect(InetSocketAddress("127.0.0.1", port), 5000)
                c.getOutputStream().write("GET /api/x$i HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                c.getOutputStream().flush()
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (reached.get() < 5 && System.nanoTime() < deadline) {
                Thread.sleep(50)
            }
            Thread.sleep(1000)
            assertEquals("proxied requests held at the backend at once with maxWorkers(2), maxSseStreams(3)", 5, reached.get())
        } finally {
            closing.set(true)
            synchronized(held) { for (s in held) s.close() }
            for (c in clients) c.close()
            app.shutdown(2, TimeUnit.SECONDS)
            backend.close()
        }

        val k = section(ko(), "## 스레드 모델")
        has("proxy.md thread model", k, "`maxWorkers + maxSseStreams`(기본 64 + 128 = 192)",
            "`maxWorkers(2)`와 `maxSseStreams(3)`인 앱에서는 프록시 요청 다섯 개가 함께 백엔드를 기다릴 수 있습니다",
            "(defaults.md#시간연결-수-한도)",
            "어댑터의 요청 스레드 한도(TinyHttpd에서는 `maxWorkers + maxSseStreams`, 기본 192)와 `totalTimeout`입니다")
        val e = section(en(), "## Thread model")
        has("en/proxy.md thread model", e, "`maxWorkers + maxSseStreams` (64 + 128 = 192 by default)",
            "an app with `maxWorkers(2)` and `maxSseStreams(3)` can have five proxied requests waiting on the backend at once",
            "(defaults.md#time-and-connection-limits)",
            "the adapter's request-thread bound (on TinyHttpd `maxWorkers + maxSseStreams`, 192 by default) and `totalTimeout` are what bound the threads a proxy holds")
        for ((where, s, wrong) in listOf(Triple("proxy.md", k, "작업 스레드 풀(`maxWorkers`, 기본 64)"),
                Triple("proxy.md", k, "묶는 것은 `maxWorkers`와 `totalTimeout`입니다"),
                Triple("en/proxy.md", e, "worker pool (`maxWorkers`, default 64)"),
                Triple("en/proxy.md", e, "`maxWorkers` and `totalTimeout` are what bound"))) {
            assertFalse("$where still names maxWorkers alone as the pool or the bound: $wrong", s.contains(wrong))
        }
    }

    // C-005 (loop P wave-21 r6 LP-2, LP-3): every public method and constructor that Proxy and ProxyException declare,
    // enumerated by reflection, is called in a ```java block and in a ```kotlin block of both pages: `.name(` for a
    // method, `new ProxyException(` in Java and `ProxyException(` without `new` in Kotlin for a constructor.
    @Test
    fun `C-005 every public method and constructor of Proxy and ProxyException appears in a Java and a Kotlin block of both pages`() {
        val needles = ArrayList<Triple<String, Regex, Regex>>()
        for (c in listOf(Proxy::class.java, ProxyException::class.java)) {
            for (m in c.declaredMethods) {
                if (!java.lang.reflect.Modifier.isPublic(m.modifiers) || m.isSynthetic || m.isBridge) continue
                val call = Regex("\\." + Regex.escape(m.name) + "\\(")
                needles.add(Triple("${c.simpleName}.${m.name}", call, call))
            }
            for (k in c.declaredConstructors) {
                if (!java.lang.reflect.Modifier.isPublic(k.modifiers)) continue
                needles.add(Triple("new ${c.simpleName}(${k.parameterTypes.joinToString(", ") { it.simpleName }})",
                    Regex("\\bnew\\s+" + c.simpleName + "\\("), Regex("(?<![\\w.])(?<!new )" + c.simpleName + "\\(")))
            }
        }
        val labels = needles.map { it.first }.toSet()
        for (expected in listOf("Proxy.to", "Proxy.writeTimeout", "Proxy.retryDelay", "Proxy.retryUnsafeMethods",
                "Proxy.preserveHost", "Proxy.bodyLimit", "Proxy.rewriteLocation", "Proxy.handle", "ProxyException.kind",
                "new ProxyException(Kind, Throwable)")) {
            assertTrue("reflection found $expected: $labels", expected in labels)
        }
        val missing = ArrayList<String>()
        for ((where, doc) in listOf("proxy.md" to ko(), "en/proxy.md" to en())) {
            val java = blocks(doc, "java").joinToString("\n")
            val kotlin = blocks(doc, "kotlin").joinToString("\n")
            for ((label, j, k) in needles) {
                if (!j.containsMatchIn(java)) missing.add("$where java $label")
                if (!k.containsMatchIn(kotlin)) missing.add("$where kotlin $label")
            }
        }
        assertEquals("public Proxy and ProxyException members without a Java or Kotlin example", emptyList<String>(), missing)
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

    // docs-example: proxy.md#3
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

    // docs-example: proxy.md#6
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

    // docs-example: proxy.md#8
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

    // docs-example: proxy.md#10
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

    // docs-example: proxy.md#11
    @Test
    fun `OPS-DX-010 AC-2 the private CA example passes an SSLSocketFactory`() {
        val tls = SSLContext.getDefault()
        app.use("/internal", Proxy.to("https://backend.internal:8443").sslSocketFactory(tls.socketFactory))

        t.start()
        started = true
        assertNotNull(Proxy.to("https://backend.internal:8443").sslSocketFactory(tls.socketFactory).sslSocketFactory())
    }

    // FR-PROXY-022 AC-7, OPS-DX-010 AC-2: both pages document the two trustCertificates overloads in the https section
    // with the Express counterpart, one Java and one Kotlin example, the only-these-certificates rule, the host name
    // check that stays on and needs a matching subject alternative name, and an openssl command issuing one
    @Test
    fun `FR-PROXY-022 AC-7 the https section documents trustCertificates with its rules, examples and an openssl command`() {
        val java = listOf("Proxy.to(\"https://localhost:8443\").trustCertificates(pem)",
            "Proxy.to(\"https://billing.internal:8443\").trustCertificates(ca)",
            "CertificateFactory.getInstance(\"X.509\").generateCertificate(in)")
        val kotlin = listOf("val pem = File(\"cert.pem\").readText()",
            "Proxy.to(\"https://localhost:8443\").trustCertificates(pem)",
            "Proxy.to(\"https://billing.internal:8443\").trustCertificates(ca)")
        val openssl = "openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 365 " +
            "-subj \"/CN=localhost\" -addext \"subjectAltName=DNS:localhost,IP:127.0.0.1\""
        val k = section(ko(), "### 자체 서명 인증서와 사설 CA 믿기")
        val e = section(en(), "### Trusting a self-signed or private CA certificate")
        oneJavaAndOneKotlin("proxy.md trustCertificates", k, java, kotlin)
        oneJavaAndOneKotlin("en/proxy.md trustCertificates", e, java, kotlin)
        for ((where, s) in listOf("proxy.md" to k, "en/proxy.md" to e)) {
            val bash = blocks(s, "bash")
            assertEquals("$where bash blocks", 1, bash.size)
            assertEquals("$where openssl command", openssl, bash[0].trim())
        }
        has("proxy.md trustCertificates", k, "`trustCertificates(String pem)`", "`trustCertificates(X509Certificate... certificates)`",
            "그 마운트에서는 넘긴 인증서만 믿습니다", "JDK 기본 신뢰 저장소의 공인 CA는 믿지 않습니다",
            "자체 서명 인증서는 그 인증서 자신을, 사설 CA 인증서는 그 CA가 발급한 인증서를 믿게 합니다",
            "넘긴 인증서 가운데 연결할 때 유효 기간 안에 있는 것만 믿습니다",
            "백엔드가 보낸 인증서 체인은 그다음 플랫폼의 표준 검사(서명, CA 제약, 경로에 있는 각 인증서의 유효 기간)로 확인하므로",
            "경로에 유효 기간이 지났거나 아직 시작되지 않은 인증서가 있으면 `502`(`CONNECT_FAILED`)입니다",
            "경로에 필요 없는 인증서는 백엔드가 더 보내도, 넘긴 인증서에 섞여 있어도 연결에 영향이 없습니다",
            "유효 기간이 지났거나 아직 시작되지 않은 넘긴 인증서는 어떤 경우에도 쓰지 않습니다",
            "교차 서명된 중간 인증서가 만료된 옛 루트와 유효한 새 CA 양쪽으로 이어지면 새 CA로 통하고",
            "백엔드가 유효한 경로 옆에 만료된 옛 교차 서명 인증서를 함께 보내도 통하며",
            "오래된 인증서가 섞인 묶음 파일을 넘겨도 됩니다",
            "호스트 이름 검사도 그대로 켜져 있습니다", "대상 호스트와 맞는 주체 대체 이름(subjectAltName, SAN)이 있어야 하며",
            "CN만 있는 인증서는 `502`(`CONNECT_FAILED`)입니다",
            "`trustCertificates`와 `sslSocketFactory` 중 나중에 부른 것이 그 `Proxy`의 TLS 신뢰를 정합니다",
            "검사를 끄는 옵션이 아니라", "Node https 에이전트의 `ca` 옵션을 `http-proxy-middleware`에 넘기는 것",
            "`createProxyMiddleware({ target, agent: new https.Agent({ ca: fs.readFileSync('cert.pem') }) })`")
        has("en/proxy.md trustCertificates", e, "`trustCertificates(String pem)`", "`trustCertificates(X509Certificate... certificates)`",
            "On that mount only the given certificates are trusted", "the public CAs of the JDK default trust store are not",
            "A self-signed certificate trusts itself, and a private CA certificate trusts the certificates that CA issued",
            "Only the given certificates that are within their validity period at connection time are trusted",
            "The backend's chain is then checked by the platform's standard validation (signatures, CA constraints, and the validity of each certificate on the path)",
            "so an expired or not-yet-valid certificate on the path gets `502` (`CONNECT_FAILED`)",
            "Extra certificates the path does not need have no effect, whether the backend sends them or they are among the given certificates",
            "An expired or not-yet-valid given certificate is never used",
            "a cross-signed intermediate that leads both to an expired old root and to a valid new CA passes through the new CA",
            "a backend that also sends an expired old cross-sign next to a valid path is reached",
            "a bundle file that still holds old certificates can be passed",
            "The host name check stays on as well", "needs a subject alternative name (subjectAltName, SAN) that matches the target host",
            "a certificate with only a CN gets `502` (`CONNECT_FAILED`)",
            "Of `trustCertificates` and `sslSocketFactory`, the one called later decides the TLS trust of that `Proxy`",
            "It is not an option that turns verification off", "passing the `ca` option of Node's https agent to `http-proxy-middleware`",
            "`createProxyMiddleware({ target, agent: new https.Agent({ ca: fs.readFileSync('cert.pem') }) })`")
    }

    // docs-example: proxy.md#12
    // adapted: the PEM files are written to a temporary directory that stands in for the working directory
    @Test
    fun `FR-PROXY-022 AC-7 the trustCertificates example trusts a PEM file and a private CA certificate`() {
        val dir = Files.createTempDirectory("naruserve-proxy-docs-trust").toFile()
        try {
            File(dir, "cert.pem").writeText(TestCertificates.selfSignedPem())
            File(dir, "ca.pem").writeText(TestCertificates.selfSignedPem())

            val pem = File(dir, "cert.pem").readText()
            app.use("/internal", Proxy.to("https://localhost:8443").trustCertificates(pem))

            val ca = File(dir, "ca.pem").inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            app.use("/billing", Proxy.to("https://billing.internal:8443").trustCertificates(ca))

            t.start()
            started = true
            assertNotNull(Proxy.to("https://localhost:8443").trustCertificates(pem).sslSocketFactory())
            assertNotNull(Proxy.to("https://billing.internal:8443").trustCertificates(ca).sslSocketFactory())
        } finally {
            TestCertificates.delete(dir)
        }
    }

    /** Interrupts [worker] after [millis] milliseconds, from a daemon thread. */
    private fun interruptLater(worker: Thread, millis: Long) {
        val t = Thread({
            try {
                Thread.sleep(millis)
                worker.interrupt()
            } catch (e: InterruptedException) {
            }
        }, "docs-interrupter")
        t.isDaemon = true
        t.start()
    }

    /** A plain backend socket whose first connect interrupts the request thread 100 ms later. */
    private fun interruptingFirstConnect(fail: Boolean) = BackendClient.PlainSockets {
        object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                if (connectsSeen.getAndIncrement() == 0) {
                    interruptLater(Thread.currentThread(), 100)
                }
                if (fail) {
                    throw java.net.ConnectException("Connection refused (simulated)")
                }
                super.connect(endpoint, timeout)
            }
        }
    }

    private val connectsSeen = AtomicInteger()

    // FR-PROXY-018 AC-13 (OPS-DX-010 AC-8, AC-3): the three interrupt outcomes, run on worker threads of an in-memory app
    // whose onError records what reaches it, and then read from both retry sections and the plain HttpException bullet
    // and onError example comment of both error sections: a pending interrupt relays the 502 unchanged, an interrupted
    // wait after a connect failure throws that failure, an interrupted wait after a closed 502 answers through the error
    // flow with a plain HttpException of that status and never relays the backend response
    @Test
    fun `FR-PROXY-018 AC-13 the retry sections state the three interrupt outcomes and onError lists the plain 502 and 503`() {
        val bad = backend("HTTP/1.1 502 X\r\nX-From-Backend: yes\r\nContent-Length: 7\r\n\r\nbackend")
        val adapter = InMemoryHttpAdapter()
        val app = RestApp.builder().httpAdapter(adapter).jsonCodec(StubJsonCodec()).logger(RecordingLogger()).build()
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val keptInterrupt = java.util.Collections.synchronizedList(ArrayList<Boolean>())
        val pending = Proxy.to(bad.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS)
        try {
            // Each mount records and clears the interrupt status the proxy kept, so no worker thread stays interrupted.
            fun mount(path: String, proxy: Proxy, interruptFirst: Boolean) {
                app.use(path) { req, res, next ->
                    if (interruptFirst) Thread.currentThread().interrupt()
                    try {
                        proxy.handle(req, res, next)
                    } finally {
                        keptInterrupt.add(Thread.interrupted())
                    }
                }
            }
            mount("/pending", pending, true)
            mount("/wait502", Proxy.to(bad.url()).retries(2).retryDelay(2, TimeUnit.SECONDS), false)
            mount("/waitConnect", Proxy.to(freeUrl()).retries(2).retryDelay(2, TimeUnit.SECONDS), false)
            app.onError { error, req, res ->
                errors.add(error)
                res.status(if (error is HttpException) error.status() else 500).send("onError")
            }
            app.listen(0)
            fun get(path: String) = adapter.submit(ClientRequest.of("GET", path)).get(20, TimeUnit.SECONDS)

            val relayed = get("/pending/x")
            assertEquals(502, relayed.status())
            assertEquals("a pending interrupt relays the backend 502 unchanged", "yes", relayed.header("X-From-Backend"))
            assertEquals(1, bad.requestCount())
            assertEquals(listOf(true), keptInterrupt.toList())
            assertTrue(errors.isEmpty())

            BackendClient.plainSockets = interruptingFirstConnect(false)
            val answered = get("/wait502/x")
            assertEquals(502, answered.status())
            assertNull("the closed 502 is not relayed", answered.header("X-From-Backend"))
            assertEquals("onError", answered.text())
            assertEquals(2, bad.requestCount())
            val plain = errors.single()
            assertTrue("a plain HttpException: $plain", plain is HttpException && plain !is ProxyException)
            assertEquals(502, (plain as HttpException).status())

            errors.clear()
            connectsSeen.set(0)
            BackendClient.plainSockets = interruptingFirstConnect(true)
            val failed = get("/waitConnect/x")
            assertEquals(502, failed.status())
            assertEquals(1, connectsSeen.get())
            assertSame(ProxyException.Kind.CONNECT_FAILED, (errors.single() as ProxyException).kind())
            assertEquals("every interrupt status was kept", listOf(true, true, true), keptInterrupt.toList())
        } finally {
            BackendClient.plainSockets = BackendClient.SYSTEM_SOCKETS
            app.shutdown(2, TimeUnit.SECONDS)
        }

        has("proxy.md retries", section(ko(), "## 재시도"),
            "재시도를 정할 때 인터럽트 상태가 이미 켜져 있으면 재시도하지 않습니다",
            "마지막 실패는 그대로 오류 흐름으로 가고 `502`·`503` 백엔드 응답은 그대로 넘깁니다",
            "시도하는 도중에 인터럽트되면 그 시도의 응답을 그대로 넘깁니다",
            "연결 실패 뒤의 재시도 대기가 인터럽트되면 그 연결 실패를 던집니다",
            "`502`나 `503`을 받고 그 연결을 닫은 뒤의 재시도 대기가 인터럽트되면 백엔드 응답을 넘기지 않습니다",
            "그 상태의 보통 `HttpException`(`ProxyException`이 아님)이 오류 흐름(`onError`)으로 갑니다",
            "대기 중에 마감이 지났으면 그 마감 종류의 `ProxyException`이 갑니다")
        has("en/proxy.md retries", section(en(), "## Retries"),
            "When the interrupt status is already set as a retry is decided, no retry is made",
            "the last failure goes to the error flow as it is and a `502` or `503` backend response is relayed unchanged",
            "An interrupt during an attempt relays that attempt's response unchanged",
            "A retry wait interrupted after a connect failure throws that failure",
            "A retry wait interrupted after a `502` or `503`, whose connection is already closed, relays no backend response",
            "a plain `HttpException` of that status (not a `ProxyException`) goes to the error flow (`onError`)",
            "or a `ProxyException` of the kind of a deadline that fired during the wait")
        has("proxy.md errors", section(ko(), "### 응답을 보내기 전의 실패"),
            "재시도 대기가 인터럽트된 `502`·`503`도 보통 `HttpException`입니다")
        has("en/proxy.md errors", section(en(), "### Failures before the response"),
            "So is the `502` or `503` of an interrupted retry wait")
        for ((where, s) in listOf("proxy.md" to section(ko(), "### 응답을 보내기 전의 실패"),
                "en/proxy.md" to section(en(), "### Failures before the response"))) {
            val comment = blocks(s, "java").single().split("\n").single { it.contains("instanceof HttpException") }
            for (status in listOf("400", "405", "501", "413", "502", "503")) {
                assertTrue("$where onError comment of the plain HttpException branch lacks $status: $comment",
                    comment.contains(status))
            }
        }
    }

    // FR-PROXY-013 AC-11 (OPS-DX-010): the real proxy on app.use("/api/orders", Proxy.to(url)) turns a same-origin
    // Location whose rewritten path would read as a network-path reference into "/." + path, drops user information,
    // reads an empty port as omitted (80 here, so the value stays) and leaves a port the Proxy.to grammar refuses
    // unchanged; the Location rewrite section of both pages must state those results and show the "/." example
    @Test
    fun `FR-PROXY-013 AC-11 the Location sections state the slash-dot guard, the dropped user information, the empty port and the refused value`() {
        val holder = java.util.concurrent.atomic.AtomicReference<RawBackend>()
        val b = RawBackend.start { _, request, out ->
            val port = holder.get().port()
            val location = when (request.requestLine().split(" ")[1]) {
                "/api/orders/evil" -> "http://127.0.0.1:$port//evil.example/x"
                "/api/orders/userinfo" -> "http://user:pw@127.0.0.1:$port/api/orders/x"
                "/api/orders/emptyport" -> "http://127.0.0.1:/api/orders/x"
                else -> "http://127.0.0.1:99999/api/orders/x"
            }
            out.write("HTTP/1.1 302 X\r\nLocation: $location\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        holder.set(b)
        backends.add(b)
        val port = b.port()
        app.use("/api/orders", Proxy.to(b.url()))

        assertEquals("/.//evil.example/x", send("GET", "/api/orders/evil").header("Location"))
        assertEquals("/api/orders/x", send("GET", "/api/orders/userinfo").header("Location"))
        assertEquals("http://127.0.0.1:/api/orders/x", send("GET", "/api/orders/emptyport").header("Location"))
        assertEquals("http://127.0.0.1:99999/api/orders/x", send("GET", "/api/orders/refused").header("Location"))
        assertEquals("an empty port is the omitted one", "/x",
            LocationRewriter.rewrite("http://h:/x", TargetUrl.parse("http://h"), ""))
        assertTrue(port != 80)

        val ko = section(ko(), "### Location 다시 쓰기")
        val en = section(en(), "### Location rewrite")
        has("proxy.md Location", ko,
            "| 같은 마운트 | `http://127.0.0.1:8081//evil.example/x` | `/.//evil.example/x` |",
            "| 같은 마운트 | `http://user:pw@127.0.0.1:8081/api/orders/x` | `/api/orders/x` |",
            "탭·CR·LF를 빼고 처음 두 글자가 각각 `/`나 `\\`이면",
            "앞에 `/.`를 붙입니다",
            "사용자 정보(`user:pw@`)는 비교하기 전에 버리고 결과에도 넣지 않습니다",
            "빈 포트(`http://h:/x`의 `:`)는 포트를 생략한 것으로 봅니다",
            "호스트나 포트가 `Proxy.to`의 문법에 맞지 않는 값(예: 포트 `99999`)은 그대로 둡니다")
        has("en/proxy.md Location", en,
            "| The same mount | `http://127.0.0.1:8081//evil.example/x` | `/.//evil.example/x` |",
            "| The same mount | `http://user:pw@127.0.0.1:8081/api/orders/x` | `/api/orders/x` |",
            "its first two characters, tab, CR and LF skipped, are each `/` or `\\`",
            "it gets a leading `/.`",
            "User information (`user:pw@`) is dropped before the comparison and never reaches the result",
            "an empty port (the `:` of `http://h:/x`) counts as an omitted port",
            "A value whose host or port the `Proxy.to` grammar refuses (for example the port `99999`) stays unchanged")
        for ((where, s) in listOf("proxy.md" to ko, "en/proxy.md" to en)) {
            assertFalse("$where still predicts the open redirect: $s", s.contains("| `//evil.example/x` |"))
        }
    }

    // FR-PROXY-005 AC-2 (SEC-PROXY-002 AC-7, FR-PROXY-002 AC-4; OPS-DX-010): the real proxy drops the client's
    // Forwarded, X-Real-IP, X-Forwarded-Port and X-Forwarded-Prefix, sends the value .requestHeader sets for the last
    // three, and refuses Forwarded and X-Forwarded-For/-Proto/-Host at the call; the X-Forwarded-* table of both pages
    // must say, per header name, what happens to a client value and what .requestHeader does
    @Test
    fun `FR-PROXY-005 AC-2 the forwarding header table states per name what a client value and requestHeader do`() {
        val b = backend(RawBackend.response(200, "ok"))
        app.use("/plain", Proxy.to(b.url()))
        app.use("/api", Proxy.to(b.url()).stripPrefix(true)
            .requestHeader("X-Forwarded-Prefix", "/api")
            .requestHeader("X-Real-IP", "10.0.0.1")
            .requestHeader("X-Forwarded-Port", "443"))
        val spoofed = arrayOf("Forwarded", "for=6.6.6.6", "X-Real-IP", "6.6.6.6", "X-Forwarded-Port", "1",
            "X-Forwarded-Prefix", "/evil")

        assertEquals(200, send("GET", "/plain/x", *spoofed).status())
        assertEquals(200, send("GET", "/api/x", *spoofed).status())
        val (plain, api) = b.requests()
        for (name in listOf("Forwarded", "X-Real-IP", "X-Forwarded-Port", "X-Forwarded-Prefix")) {
            assertEquals("client $name without requestHeader", emptyList<String>(), plain.headerValues(name))
        }
        assertEquals(listOf("/api"), api.headerValues("X-Forwarded-Prefix"))
        assertEquals(listOf("10.0.0.1"), api.headerValues("X-Real-IP"))
        assertEquals(listOf("443"), api.headerValues("X-Forwarded-Port"))
        assertEquals(emptyList<String>(), api.headerValues("Forwarded"))
        for (name in listOf("Forwarded", "X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host", "x-forwarded-host")) {
            try {
                Proxy.to(b.url()).requestHeader(name, "x")
                throw AssertionError("requestHeader($name) was accepted")
            } catch (expected: IllegalArgumentException) {
                // a managed name is refused at the call
            }
        }

        val ko = section(ko(), "### X-Forwarded-* 헤더")
        val en = section(en(), "### X-Forwarded-* headers")
        has("proxy.md table head", ko, "| 헤더 | 클라이언트가 보낸 값 | `.requestHeader(name, value)` |")
        has("en/proxy.md table head", en, "| Header | A value the client sent | `.requestHeader(name, value)` |")
        for (name in listOf("`X-Forwarded-For`", "`X-Forwarded-Proto`", "`X-Forwarded-Host`", "`Forwarded`")) {
            has("proxy.md $name", row("proxy.md", ko, "| $name |"), "관리하는 이름이라 호출할 때 `IllegalArgumentException`")
            has("en/proxy.md $name", row("en/proxy.md", en, "| $name |"), "A managed name: `IllegalArgumentException` at the call")
        }
        val sent = "| `X-Real-IP`, `X-Forwarded-Port`, `X-Forwarded-Prefix` |"
        has("proxy.md sent row", row("proxy.md", ko, sent), "신뢰 설정과 상관없이 버립니다", "받습니다. 정한 값이 정확히 한 줄 백엔드에 갑니다")
        has("en/proxy.md sent row", row("en/proxy.md", en, sent), "Dropped whatever the trust setting",
            "Accepted: exactly one line with the configured value reaches the backend")
        has("proxy.md", ko, ".requestHeader(\"X-Forwarded-Prefix\", \"/api\")")
        has("en/proxy.md", en, ".requestHeader(\"X-Forwarded-Prefix\", \"/api\")")
        assertFalse("proxy.md still says always removed: $ko", ko.contains("언제나 뺍니다"))
        assertFalse("en/proxy.md still says always removed: $en", en.contains("Always removed"))
        has("proxy.md fixed headers", section(ko(), "### 고정 헤더"),
            "프록시가 버리는 `X-Real-IP`, `X-Forwarded-Port`, `X-Forwarded-Prefix`도")
        has("en/proxy.md fixed headers", section(en(), "### Fixed headers"),
            "the `X-Real-IP`, `X-Forwarded-Port` and `X-Forwarded-Prefix` the proxy drops")
    }

    /** Reads up to and including the blank line that ends an HTTP head. */
    private fun readHead(input: java.io.InputStream): String {
        val head = java.io.ByteArrayOutputStream()
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("closed inside the head: $head")
            head.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b == '\r'.code) 1 else 0
        }
        return String(head.toByteArray(), Charsets.ISO_8859_1)
    }

    // REL-PROXY-001 AC-1 (OPS-DX-010 AC-9, AC-10): over real TinyHttpd sockets on a .maxConcurrent(1) mount with a 4 s
    // read timeout, a client that resets while the backend has gone silent mid-body is not seen by the adapter, so the
    // slot stays held after the client is gone until the read timeout ends the relay; the concurrency bullet and the
    // thread model section of both pages must state that limit
    @Test(timeout = 60000)
    fun `REL-PROXY-001 AC-1 the pages state that a silent backend mid-body holds the slot after the client left until a timeout`() {
        val backend = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val held = java.util.Collections.synchronizedList(ArrayList<Socket>())
        val acceptor = Thread({
            while (!backend.isClosed) {
                try {
                    val s = backend.accept()
                    held.add(s)
                    readHead(s.getInputStream())
                    s.getOutputStream().write("HTTP/1.1 200 X\r\nContent-Length: 10000000\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    s.getOutputStream().write(ByteArray(262144))
                    s.getOutputStream().flush() // then silent, the connection held open
                } catch (e: java.io.IOException) {
                    return@Thread
                }
            }
        }, "docs-silent-backend")
        acceptor.isDaemon = true
        acceptor.start()
        val p = Proxy.to("http://127.0.0.1:${backend.localPort}").maxConcurrent(1).readTimeout(4, TimeUnit.SECONDS)
        val app = RestApp.builder().httpAdapter(TinyHttpdAdapter()).jsonCodec(StubJsonCodec()).logger(RecordingLogger()).build()
        try {
            app.use("/api", p)
            val port = app.listen(0).port()
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", port), 5000)
                client.soTimeout = 10000
                client.getOutputStream().write("GET /api/x HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                assertTrue(readHead(client.getInputStream()).startsWith("HTTP/1.1 200 "))
                client.soTimeout = 1000
                val buf = ByteArray(8192)
                var got = 0L
                try {
                    while (true) {
                        val n = client.getInputStream().read(buf)
                        if (n <= 0) break
                        got += n
                    }
                } catch (quiet: java.net.SocketTimeoutException) {
                    // every relayed byte arrived; the relay now waits on the silent backend
                }
                assertTrue("body bytes received $got", got > 0)
                Thread.sleep(300)
                assertEquals(1, p.concurrencyCounter()!!.get())
                client.setSoLinger(true, 0)
            }
            val gone = System.nanoTime()
            Thread.sleep(1000)
            assertEquals("the slot is still held 1 s after the client reset", 1, p.concurrencyCounter()!!.get())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (p.concurrencyCounter()!!.get() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(50)
            }
            assertEquals("the read timeout released the slot", 0, p.concurrencyCounter()!!.get())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - gone) >= 1000)
        } finally {
            app.shutdown(2, TimeUnit.SECONDS)
            backend.close()
            for (s in held) s.close()
        }

        has("proxy.md concurrency", section(ko(), "## 동시 요청 한도"),
            "클라이언트가 떠난 것을 어댑터가 알았을 때",
            "어댑터가 클라이언트가 떠난 것을 알지 못하는 동안에는 읽기 타임아웃이나 전체 타임아웃이 요청을 끝낼 때까지 슬롯이 잡혀 있습니다")
        has("en/proxy.md concurrency", section(en(), "## Concurrency limit"),
            "the adapter seeing the client gone",
            "While the adapter cannot see that the client is gone, the slot stays held until the read timeout or the total timeout ends the request")
        has("proxy.md thread model", section(ko(), "## 스레드 모델"),
            "응답 본문을 넘기는 도중에 백엔드가 말이 없어진 뒤 클라이언트가 연결을 끊어도 같습니다",
            "TinyHttpd 어댑터는 클라이언트에 무언가를 써야 연결이 끊긴 것을 알 수 있으므로",
            "슬롯과 작업 스레드와 백엔드 소켓은 읽기 타임아웃이나 전체 타임아웃이 넘기기를 끝낼 때까지 잡혀 있습니다")
        has("en/proxy.md thread model", section(en(), "## Thread model"),
            "The same holds in the middle of the response body: when the backend goes silent and the client then disconnects",
            "TinyHttpd cannot see the disconnect until something is written to the client",
            "the slot, the worker thread and the backend socket stay held until the read timeout or the total timeout ends the relay")
    }

    /**
     * Over real TinyHttpd sockets: a backend that answers `200` with `Content-Length: [declared]`, sends [sent] body
     * bytes and closes; returns everything the client received until the connection ended, and the app logger.
     */
    private fun cutOffOverTinyHttpd(declared: Long, sent: Int): Pair<ByteArray, RecordingLogger> {
        val backend = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val acceptor = Thread({
            while (!backend.isClosed) {
                try {
                    backend.accept().use { s ->
                        readHead(s.getInputStream())
                        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: $declared\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                        s.getOutputStream().write(ByteArray(sent) { 'x'.code.toByte() })
                        s.getOutputStream().flush()
                    }
                } catch (e: java.io.IOException) {
                    return@Thread
                }
            }
        }, "docs-cut-off-backend")
        acceptor.isDaemon = true
        acceptor.start()
        val logger = RecordingLogger()
        val app = RestApp.builder().httpAdapter(TinyHttpdAdapter()).jsonCodec(StubJsonCodec()).logger(logger).build()
        try {
            app.use("/api", Proxy.to("http://127.0.0.1:${backend.localPort}").readTimeout(10, TimeUnit.SECONDS))
            val port = app.listen(0).port()
            val got = java.io.ByteArrayOutputStream()
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", port), 5000)
                client.soTimeout = 15000
                client.getOutputStream().write("GET /api/x HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val buf = ByteArray(8192)
                try {
                    while (true) {
                        val n = client.getInputStream().read(buf)
                        if (n < 0) break
                        got.write(buf, 0, n)
                    }
                } catch (reset: java.net.SocketException) {
                    // an aborted connection may end in a reset; what arrived before it is kept
                }
            }
            return Pair(got.toByteArray(), logger)
        } finally {
            app.shutdown(2, TimeUnit.SECONDS)
            backend.close()
        }
    }

    // OPS-DX-010 AC-3 (FR-PROXY-016, FR-PROXY-014; loop P wave-21 r5 LP-26/LP-27): over TinyHttpd, a failure after the
    // proxy handed the response to the adapter aborts the client connection, and what the client already has depends
    // on how much the adapter wrote: a backend declaring Content-Length: 100 that closes after 10 bytes leaves the
    // client 0 bytes (no status line), one that closes after 64 KiB of a larger declared length leaves it the 200 and
    // a cut body. Both pages must say exactly that, and neither may promise a response cut short in every case.
    @Test(timeout = 90000)
    fun `OPS-DX-010 AC-3 the pages state that a failure after the hand-off leaves either no response or a status line with a cut body`() {
        val (small, smallLog) = cutOffOverTinyHttpd(100, 10)
        assertEquals("bytes the client received: ${String(small, Charsets.ISO_8859_1)}", 0, small.size)
        val (large, largeLog) = cutOffOverTinyHttpd(1048576, 65536)
        val text = String(large, Charsets.ISO_8859_1)
        assertTrue(text.substring(0, minOf(40, text.length)), text.startsWith("HTTP/1.1 200 "))
        val bodyStart = text.indexOf("\r\n\r\n") + 4
        assertTrue("a cut body: ${large.size - bodyStart} bytes", large.size - bodyStart in 1 until 1048576)
        for (log in listOf(smallLog, largeLog)) {
            val errors = synchronized(log.errors) { log.errors.filter { it is ProxyException } }
            assertEquals("${log.lines}", 1, errors.size)
            assertEquals(ProxyException.Kind.INVALID_RESPONSE, (errors[0] as ProxyException).kind())
        }

        val k = section(ko(), "### 응답을 보내기 시작한 뒤의 실패")
        has("proxy.md after the response started", k,
            "프록시가 응답을 어댑터에 넘긴 뒤 본문을 넘기다가 실패하면",
            "앱 로거(`req.application().logger()`)에 한 번 기록하고, 프레임워크가 클라이언트 연결을 중단합니다",
            "클라이언트가 그때까지 상태 줄과 본문 일부를 받았는지는 어댑터가 얼마나 썼는지에 달려 있습니다",
            "TinyHttpd 어댑터는 응답의 앞부분을 버퍼에 모아 두므로",
            "`Content-Length: 100`을 선언하고 10바이트를 보낸 뒤 연결을 닫은 백엔드",
            "클라이언트는 응답 없이 닫힌 연결을 받습니다",
            "64 KiB를 보낸 뒤 연결을 닫은 백엔드",
            "상태 줄(`200`)과 중간에 끊긴 본문을 받습니다",
            "어느 쪽이든 클라이언트가 온전해 보이는 응답을 받는 일은 없습니다")
        assertFalse("proxy.md still says the headers were sent", k.contains("응답 헤더를 보낸 뒤"))
        assertFalse("proxy.md still promises a cut response", k.contains("클라이언트는 응답이 중간에 끊긴 것을 봅니다"))
        val e = section(en(), "### Failures after the response started")
        has("en/proxy.md after the response started", e,
            "When relaying the body fails after the proxy handed the response to the adapter",
            "logs a `ProxyException` of that kind once to the app logger (`req.application().logger()`), and the framework aborts the client connection",
            "Whether the client has already received the status line and part of the body depends on how much the adapter has written",
            "The TinyHttpd adapter buffers the beginning of the response",
            "a backend that declares `Content-Length: 100`, sends 10 bytes and closes the connection",
            "the client gets a closed connection with no response",
            "a backend that closes the connection after sending 64 KiB",
            "the client gets the status line (`200`) and a body cut short",
            "Either way the client never gets a complete-looking response")
        assertFalse("en/proxy.md still says the headers were sent", e.contains("after the response headers were sent"))
        assertFalse("en/proxy.md still promises a cut response", e.contains("The client sees a response cut short."))
    }

    // FR-PROXY-001 AC-2 (loop P wave-21 r5 LP-28): an IPv4 literal host is four numbers joined by three dots, which
    // Proxy.to accepts (1.2.3.4) while five numbers (1.2.3.4.5, four dots) are refused; the Korean page must not
    // describe the accepted form as having four dots, and both pages state the same form
    @Test
    fun `FR-PROXY-001 AC-2 both pages describe the accepted IPv4 literal as four numbers joined by three dots`() {
        assertNotNull(Proxy.to("http://1.2.3.4"))
        assertNotNull(Proxy.to("http://1.2.3.4:8080"))
        try {
            Proxy.to("http://1.2.3.4.5")
            org.junit.Assert.fail("1.2.3.4.5 was accepted")
        } catch (expected: IllegalArgumentException) {
        }
        val k = section(ko(), "## 기본 사용")
        assertFalse("proxy.md still says four dots", k.contains("점 네 개"))
        has("proxy.md basic use", k, "숫자 네 개를 점 세 개로 이은 IPv4 리터럴(`1.2.3.4`)")
        val e = section(en(), "## Basic use")
        has("en/proxy.md basic use", e, "a dotted-quad IPv4 literal (four numbers joined by three dots, `1.2.3.4`)")
    }

    // docs-example: proxy.md#2
    @Test
    fun `C-005 IR-PROXY-001 the per-request backend example calls handle on the Proxy it chose`() {
        val stableBackend = backend(RawBackend.response(200, "stable"))
        val canaryBackend = backend(RawBackend.response(200, "canary"))
        val stableUrl = stableBackend.url()
        val canaryUrl = canaryBackend.url()
        val stable = Proxy.to(stableUrl)
        val canary = Proxy.to(canaryUrl)
        app.use("/api") { req, res, next ->
            val target = if (req.header("X-Canary") == "1") canary else stable
            target.handle(req, res, next)
        }

        assertEquals("stable", send("GET", "/api/orders").text())
        assertEquals("canary", send("GET", "/api/orders", "X-Canary", "1").text())
        assertEquals("GET /api/orders HTTP/1.1", stableBackend.onlyRequest().requestLine())
        assertEquals("GET /api/orders HTTP/1.1", canaryBackend.onlyRequest().requestLine())
    }

    // docs-example: proxy.md#4
    @Test
    fun `C-005 OPS-DX-010 AC-2 the upload example keeps the client Host and forwards a body over the app's raw body limit`() {
        val backend = backend(RawBackend.response(200, "stored"))
        val url = backend.url()
        val adapter = InMemoryHttpAdapter()
        val app = RestApp.builder().httpAdapter(adapter).jsonCodec(StubJsonCodec()).logger(RecordingLogger()).build()
        try {
            app.use("/upload", Proxy.to(url)
                .preserveHost(true)
                .bodyLimit(100L * 1024 * 1024)
                .writeTimeout(2, TimeUnit.MINUTES))
            app.listen(0)

            val body = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            val r = adapter.send(ClientRequest.of("PUT", "/upload/a.bin").header("Host", "files.example")
                .header("Content-Length", body.size.toString()).body(body))
            assertEquals(200, r.status())
            assertEquals("stored", r.text())
            val received = backend.onlyRequest()
            assertEquals("PUT /upload/a.bin HTTP/1.1", received.requestLine())
            assertEquals("preserveHost(true) sends the client's Host", listOf("files.example"), received.headerValues("Host"))
            assertTrue("the 2 MiB body passed the 1 MiB rawBodyLimit under bodyLimit", body.contentEquals(received.body))
        } finally {
            app.shutdown(2, TimeUnit.SECONDS)
        }
    }

    // docs-example: proxy.md#5
    @Test
    fun `C-005 FR-PROXY-013 the rewriteLocation(false) example relays the backend Location unchanged`() {
        var port = 0
        val legacy = RawBackend.start { _, _, out ->
            out.write("HTTP/1.1 302 X\r\nLocation: http://127.0.0.1:$port/legacy/new\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1))
        }
        backends.add(legacy)
        port = legacy.port()
        val url = legacy.url()
        app.use("/legacy", Proxy.to(url).rewriteLocation(false))
        app.use("/rewritten", Proxy.to(url))

        val kept = send("GET", "/legacy/old")
        assertEquals(302, kept.status())
        assertEquals("http://127.0.0.1:$port/legacy/new", kept.header("Location"))
        assertEquals("the default rewrites the same Location", "/legacy/new", send("GET", "/rewritten/old").header("Location"))
    }

    // docs-example: proxy.md#7
    @Test
    fun `C-005 IR-PROXY-002 the test double example passes a ProxyException built with the public constructor to onError`() {
        val adapter = InMemoryHttpAdapter()
        val app = RestApp.builder().httpAdapter(adapter).jsonCodec(MapCodec()).logger(RecordingLogger()).build()
        val seen = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        try {
            app.use("/api") { req, res, next ->
                next.error(ProxyException(ProxyException.Kind.CONNECT_FAILED, java.net.ConnectException("Connection refused")))
            }
            app.onError { error, req, res ->
                seen.add(error)
                res.status((error as HttpException).status()).json(mapOf("kind" to (error as ProxyException).kind().name))
            }
            app.listen(0)

            val r = adapter.send(ClientRequest.of("GET", "/api/orders"))
            assertEquals("the status follows the kind", 502, r.status())
            assertEquals("{\"kind\":\"CONNECT_FAILED\"}", r.text())
            val e = seen.single() as ProxyException
            assertNull("the message is always null", e.message)
            assertTrue("the cause stays in getCause(): ${e.cause}", e.cause is java.net.ConnectException)
        } finally {
            app.shutdown(2, TimeUnit.SECONDS)
        }
    }

    // docs-example: proxy.md#9
    @Test
    fun `C-005 FR-PROXY-018 the retry example waits retryDelay and retries a bodiless DELETE after a 503`() {
        val catalog = RawBackend.start { index, _, out ->
            val raw = if (index == 0) RawBackend.response(503, "busy") else RawBackend.response(200, "deleted")
            out.write(raw.toByteArray(Charsets.ISO_8859_1))
        }
        backends.add(catalog)
        val url = catalog.url()
        app.use("/catalog", Proxy.to(url)
            .retries(3)
            .retryDelay(500, TimeUnit.MILLISECONDS)
            .retryUnsafeMethods(true))

        val began = System.nanoTime()
        val r = send("DELETE", "/catalog/items/7")
        val tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        assertEquals(200, r.status())
        assertEquals("deleted", r.text())
        assertEquals("one retry of the DELETE after the 503", 2, catalog.requestCount())
        assertTrue("the retry waited retryDelay: $tookMillis ms", tookMillis >= 450)
    }
}
