package com.snoworca.naruserve.proxy

import com.snoworca.naruserve.HttpException
import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
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

    /** One option row: its signature, default per language, the exceptions it names and its http-proxy-middleware cell. */
    private class OptionRow(val sig: String, val koDefault: String, val enDefault: String, val exceptions: List<String>,
        val counterpart: String)

    private val iae = listOf("`IllegalArgumentException`")
    private val iaeNpe = listOf("`IllegalArgumentException`", "`NullPointerException`")

    private val optionRows = listOf(
        OptionRow("`connectTimeout(long, TimeUnit)`", "| 10초 |", "| 10 seconds |", iaeNpe, "No counterpart"),
        OptionRow("`writeTimeout(long, TimeUnit)`", "| 60초 |", "| 60 seconds |", iaeNpe, "No counterpart"),
        OptionRow("`readTimeout(long, TimeUnit)`", "| 60초 |", "| 60 seconds |", iaeNpe, "`proxyTimeout`"),
        OptionRow("`totalTimeout(long, TimeUnit)`", "| 5분 |", "| 5 minutes |", iaeNpe, "`timeout`"),
        OptionRow("`retries(int)`", "| 0(재시도 없음) |", "| 0 (no retries) |", iae, "No counterpart"),
        OptionRow("`retryDelay(long, TimeUnit)`", "| 200밀리초 |", "| 200 milliseconds |", iaeNpe, "No counterpart"),
        OptionRow("`retryUnsafeMethods(boolean)`", "| `false` |", "| `false` |", emptyList(), "No counterpart"),
        OptionRow("`stripPrefix(boolean)`", "| `false`(접두사를 남깁니다) |", "| `false` (the prefix is kept) |", emptyList(),
            "`pathRewrite`"),
        OptionRow("`preserveHost(boolean)`", "| `false`(대상 호스트를 `Host`로 보냅니다) |",
            "| `false` (the target host is sent as `Host`) |", emptyList(), "`changeOrigin: false`"),
        OptionRow("`bodyLimit(long)`", "| 앱의 `rawBodyLimit`(기본 1 MiB) |", "| The app's `rawBodyLimit` (default 1 MiB) |",
            iae + "`1..1073741824`", "No counterpart"),
        OptionRow("`maxConcurrent(int)`", "| 한도 없음 |", "| No limit |", iae, "`maxSockets`"),
        OptionRow("`requestHeader(String, String)`", "| 없음 |", "| None |", iaeNpe, "`headers: { name: value }`"),
        OptionRow("`removeRequestHeader(String)`", "| 없음 |", "| None |", iaeNpe, "`proxyReq.removeHeader(name)`"),
        OptionRow("`responseHeader(String, String)`", "| 없음 |", "| None |", iaeNpe + "`Content-Length`",
            "`proxyRes.headers[name] = value`"),
        OptionRow("`removeResponseHeader(String)`", "| 없음 |", "| None |", iaeNpe + "`Content-Length`",
            "`delete proxyRes.headers[name]`"),
        OptionRow("`rewriteLocation(boolean)`", "| `true` |", "| `true` |", emptyList(), "`autoRewrite`"),
        OptionRow("`sslSocketFactory(SSLSocketFactory)`", "| JDK 기본 팩토리 |", "| The JDK default factory |",
            listOf("`NullPointerException`"), "`secure: false`"),
    )

    @Test
    fun `OPS-DX-010 AC-2 the option table lists every option with its types, default, range, exceptions and http-proxy-middleware counterpart`() {
        // Every public option method of Proxy has its row: the table is checked against the class, not a copied list.
        val publicOptions = Proxy::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.returnType == Proxy::class.java && it.name != "to" }
            .map { it.name }.toSet()
        assertEquals(publicOptions, optionRows.map { it.sig.substring(1, it.sig.indexOf('(')) }.toSet())
        for ((where, doc, heading) in listOf(Triple("proxy.md", ko(), "## 옵션"), Triple("en/proxy.md", en(), "## Options"))) {
            val s = section(doc, heading)
            for (o in optionRows) {
                val r = row(where, s, "| ${o.sig} |")
                has("$where ${o.sig}", r, if (where == "proxy.md") o.koDefault else o.enDefault, *o.exceptions.toTypedArray())
                val cells = r.split(" | ")
                assertEquals("$where ${o.sig} cells", 4, cells.size)
                assertTrue("$where ${o.sig} counterpart ${o.counterpart}: $r",
                    cells[3].contains(o.counterpart) || (o.counterpart == "No counterpart" && cells[3].contains("대응 옵션 없음")))
                if (o.exceptions.isEmpty()) {
                    has("$where ${o.sig}", r, if (where == "proxy.md") "모든 값을 받습니다" else "Accepts every value")
                }
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
        val app = RestApp.builder().httpAdapter(adapter).jsonCodec(MapCodec()).build()
        try {
            val url = freeUrl()
            app.use("/api", Proxy.to(url))
            app.get("/late") { req, res -> throw ProxyException(ProxyException.Kind.READ_TIMEOUT, null) }
            app.get("/bug") { req, res -> throw IllegalStateException("bug") }
            app.onError { error, req, res ->
                when (error) {
                    is ProxyException -> res.status(error.status()).json(mapOf("error" to "upstream", "kind" to error.kind().name))
                    is HttpException -> res.status(error.status()).json(mapOf("error" to "request"))
                    else -> res.status(500).json(mapOf("error" to "internal"))
                }
            }
            app.listen(0)

            val down = adapter.send(ClientRequest.of("GET", "/api/orders"))
            assertEquals(502, down.status())
            assertEquals("{\"error\":\"upstream\",\"kind\":\"CONNECT_FAILED\"}", down.text())
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
