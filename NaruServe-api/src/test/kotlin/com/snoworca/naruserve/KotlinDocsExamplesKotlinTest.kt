package com.snoworca.naruserve

import com.snoworca.naruserve.internal.DefaultRestApp
import com.snoworca.naruserve.routing.RouterPolicy
import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel
import com.snoworca.naruserve.testing.SettingsCapturingAdapter
import com.snoworca.naruserve.testing.StubJsonCodec
import com.snoworca.naruserve.testing.TestApp
import java.io.File
import java.io.FileInputStream
import java.io.Reader
import java.io.Writer
import java.lang.reflect.Type
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The docs/api ```kotlin blocks that no wave 5 or 7 docs test holds, one marked function each (FR-KT-004).
 * Each function runs its block over the in-memory adapter and asserts what the surrounding docs text states.
 */
class KotlinDocsExamplesKotlinTest {

    // App-defined types the docs leave to the app.
    class User(val id: String = "") {
        override fun toString() = "User($id)"
    }

    class Database : AutoCloseable {
        var connected = false
        var closed = false

        fun connect() {
            connected = true
        }

        override fun close() {
            closed = true
        }
    }

    class UserService(val db: Database? = null) {
        fun findAll(): List<String> = listOf("u1", "u2")
        fun findById(id: String): String = "user:$id"
    }

    class UserRepo {
        fun find(id: String): User? = if (id == "1") User("1") else null
    }

    class Event(private val id: String) {
        fun type(): String = "change"
        fun id(): String = id
        fun json(): String = "{\"id\":$id}"
    }

    class EventStore {
        private val events = listOf(Event("1"), Event("2"), Event("3"))
        fun after(lastId: String?): List<Event> = events.filter { lastId == null || it.id() > lastId }
    }

    /** `null` decodes to null and anything else to User(text); encodes with toString. */
    private class TextCodec : JsonCodec {
        override fun <T : Any> decode(reader: Reader, type: Type): T? {
            val text = reader.readText().trim()
            @Suppress("UNCHECKED_CAST")
            return if (text == "null") null else User(text) as T
        }

        override fun encode(value: Any?, writer: Writer) {
            writer.write(value.toString())
        }
    }

    private val adapter = InMemoryHttpAdapter()
    private val t = TestApp.create(adapter)
    private val app: RestApp = t.app().jsonCodec(TextCodec())
    private val users = UserRepo()
    private val eventStore = EventStore()
    private var started = false
    private val temp: File = Files.createTempDirectory("kotlin-docs").toFile()

    @After
    fun tearDown() {
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS)
        }
        temp.deleteRecursively()
    }

    private fun start() {
        if (!started) {
            t.start()
            started = true
        }
    }

    private fun send(method: String, target: String, vararg headerPairs: String, body: String? = null): ClientResponse {
        start()
        val request = ClientRequest.of(method, target)
        for (i in headerPairs.indices step 2) {
            request.header(headerPairs[i], headerPairs[i + 1])
        }
        if (body != null) {
            request.body(body.toByteArray())
        }
        return adapter.send(request)
    }

    private fun json(method: String, target: String, body: String) =
        send(method, target, "Content-Type", "application/json", body = body)

    /** A multipart/form-data body of (name, filename or null, content) parts. */
    private fun multipart(target: String, vararg parts: Triple<String, String?, String>): ClientResponse {
        val b = StringBuilder()
        for ((name, filename, content) in parts) {
            b.append("--XB\r\nContent-Disposition: form-data; name=\"").append(name).append('"')
            if (filename != null) {
                b.append("; filename=\"").append(filename).append("\"\r\nContent-Type: application/octet-stream")
            }
            b.append("\r\n\r\n").append(content).append("\r\n")
        }
        b.append("--XB--\r\n")
        return send("POST", target, "Content-Type", "multipart/form-data; boundary=XB", body = b.toString())
    }

    private fun sse(target: String, vararg headerPairs: String): String {
        start()
        val request = ClientRequest.of("GET", target)
        for (i in headerPairs.indices step 2) {
            request.header(headerPairs[i], headerPairs[i + 1])
        }
        return adapter.submit(request).get(10, TimeUnit.SECONDS).text()
    }

    private fun ws(target: String, vararg headerPairs: String): Pair<Future<ClientResponse>, InMemoryWebSocketChannel> {
        start()
        val response = adapter.submit(ClientRequest.upgrade(target, *headerPairs))
        return response to adapter.nextChannel(5, TimeUnit.SECONDS)
    }

    /** Waits for the app's sender thread to deliver what `done` looks for. */
    private fun await(channel: InMemoryWebSocketChannel, done: (List<Any>) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done(channel.sent()) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(channel.sent().toString(), done(channel.sent()))
    }

    // docs-example: app.md#1
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 app md server options reach the adapter settings`() {
        val capturing = SettingsCapturingAdapter()
        val app = RestApp.builder().httpAdapter(capturing).jsonCodec(StubJsonCodec()).build()
        app.server { options -> options.bindAddress("0.0.0.0").allowedHosts("myphone.local") }
        app.server { options -> options.headerTimeout(5, TimeUnit.SECONDS).maxWorkers(16) }
        app.listen(0)
        try {
            val s = capturing.settings!!
            assertEquals("0.0.0.0", s.bindAddress())
            assertTrue(s.allowedHosts().toString(), s.allowedHosts().contains("myphone.local"))
            assertEquals(5000L, s.headerTimeoutMillis())
            assertEquals(16, s.maxWorkers())
        } finally {
            app.shutdown(2, TimeUnit.SECONDS)
        }
    }

    // docs-example: app.md#2
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 app md trustProxy reads X-Forwarded-For`() {
        app.trustProxy(true)
        app.get("/ip") { req, res -> res.send(req.remoteAddress()) }
        assertEquals("203.0.113.7", send("GET", "/ip", "X-Forwarded-For", "203.0.113.7, 10.0.0.1").text())
    }

    // docs-example: app.md#3
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 app md routerPolicy chains a route that ignores the trailing slash`() {
        app.routerPolicy(RouterPolicy.builder().ignoreTrailingSlash(true).build())
            .get("/users") { req, res -> res.send("users") }
        assertEquals("users", send("GET", "/users/").text())
    }

    // docs-example: cookie.md#1
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 cookie md property syntax builds the Set-Cookie header`() {
        app.get("/login") { req, res ->
            val token = "abc"
            val cookie = Cookie("session", token).apply {
                isHttpOnly = true
                secure = true
                path = "/"
                maxAge = 3600
                sameSite = SameSite.LAX
            }
            res.addCookie(cookie)
            res.end()
        }
        val header = send("GET", "/login").header("Set-Cookie")!!
        for (part in listOf("session=abc", "Path=/", "Max-Age=3600", "Secure", "HttpOnly", "SameSite=Lax")) {
            assertTrue(header, header.contains(part))
        }
    }

    // docs-example: json.md#1
    // adapted: GsonJsonCodec lives in NaruServe-gson, which NaruServe-api tests cannot see; GsonCodecKotlinTest
    // runs that line
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 json md jsonLimit sets 256 KiB`() {
        app.jsonLimit(256L * 1024)
        assertEquals(262144L, (app as DefaultRestApp).jsonLimit())
    }

    // docs-example: json.md#2
    // A nested object: Kotlin generates the no-argument constructor of Profile as a member class; as a local
    // class it had none in this check.
    object JsonMd {
        data class User(val name: String, val role: String = "user")
        // 인자 없는 생성자가 없습니다. 기본 설정(Unsafe 끔)의 Gson은 이 클래스를 만들지 못하고 예외가 납니다.

        data class Profile(val name: String = "", val role: String = "user")
        // 모든 인자에 기본값이 있으면 인자 없는 생성자가 생겨 읽을 수 있습니다.
        // 하지만 {"name":null} 을 받으면 null 불가 타입인 name에 null이 들어갑니다.
    }

    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 json md only the all-default data class has a no-argument constructor`() {
        assertTrue(JsonMd.User::class.java.constructors.none { it.parameterCount == 0 })
        assertTrue(JsonMd.Profile::class.java.constructors.any { it.parameterCount == 0 })
    }

    // docs-example: kotlin.md#1
    // adapted: TestApp's in-memory app stands in for RestApp.create() and its start for app.listen(8080)
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 kotlin md opening example runs its routes`() {
        app.onStart { ctx -> ctx.register(UserService::class.java, UserService()) }

        app.route(Method.get().post(), "/api/users") { req, res ->
            res.json(req.service(UserService::class.java).findAll())
        }

        app.ws("/api/ws") { req, ws ->
            ws.onText { message -> ws.sendText("Echo: $message") }
        }

        app.post("/api/upload") { req, res ->
            req.file("file")?.save(File("/data/upload.bin"))
            res.json(mapOf("success" to true))
        }

        app.staticFiles("/", File("./www"))

        assertEquals("[u1, u2]", send("GET", "/api/users").text())
        assertEquals("[u1, u2]", send("POST", "/api/users").text())
        // No file part, so nothing is saved.
        assertEquals("{success=true}", multipart("/api/upload", Triple("note", null, "x")).text())
        val (response, channel) = ws("/api/ws")
        try {
            channel.clientText("hi")
            await(channel) { it.contains("Echo: hi") }
        } finally {
            channel.clientClose(1000, "")
        }
        assertEquals(101, response.get(10, TimeUnit.SECONDS).status())
    }

    // docs-example: kotlin.md#2
    @Test
    fun `SDS-AC-3 FR-KT-004 AC-3 kotlin md return at get gives 404 and return at use gives 401`() {
        app.get("/users/:id") { req, res ->
            val user = users.find(req.param("id"))
            if (user == null) {
                res.status(404).end()
                return@get
            }
            res.json(user)
        }

        app.use("/api") { req, res, next ->
            if (req.header("Authorization") == null) {
                res.status(401).end()
                return@use
            }
            next.run()
        }
        app.get("/api/me") { req, res -> res.send("me") }

        assertEquals("User(1)", send("GET", "/users/1").text())
        assertEquals(404, send("GET", "/users/2").status())
        assertEquals(401, send("GET", "/api/me").status())
        assertEquals("me", send("GET", "/api/me", "Authorization", "Bearer t").text())
    }

    // docs-example: kotlin.md#3
    @Test
    fun `SDS-AC-3 FR-KT-004 AC-3 kotlin md if else form gives the same 404 and 401 without return at`() {
        app.get("/users/:id") { req, res ->
            val user = users.find(req.param("id"))
            if (user == null) res.status(404).end() else res.json(user)
        }
        // The middleware of the previous block in the same if/else form.
        app.use("/api") { req, res, next -> if (req.header("Authorization") == null) res.status(401).end() else next.run() }
        app.get("/api/me") { req, res -> res.send("me") }

        assertEquals("User(1)", send("GET", "/users/1").text())
        assertEquals(404, send("GET", "/users/2").status())
        assertEquals(401, send("GET", "/api/me").status())
        assertEquals("me", send("GET", "/api/me", "Authorization", "Bearer t").text())
    }

    // docs-example: kotlin.md#4
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 kotlin md nullable accessors fall back with elvis and safe calls`() {
        app.get("/page") { req, res ->
            val page = req.query("page")?.toIntOrNull() ?: 1
            val token = req.cookie("session")?.value
            res.send("$page $token")
        }
        assertEquals("1 null", send("GET", "/page?page=x").text())
        assertEquals("3 abc", send("GET", "/page?page=3", "Cookie", "session=abc").text())
    }

    // docs-example: kotlin.md#5
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 kotlin md class java type arguments`() {
        val db = Database()
        app.onStart { ctx ->
            ctx.register(UserService::class.java, UserService(db))
        }
        app.post("/users") { req, res ->
            val user: User? = req.body(User::class.java)
            val users = req.service(UserService::class.java)
            res.json(users.findById(user?.id ?: "none"))
        }
        assertEquals("user:7", json("POST", "/users", "7").text())
        assertEquals("user:none", json("POST", "/users", "null").text())
    }

    // docs-example: kotlin.md#7
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 kotlin md elvis throw answers 400 for a JSON null body`() {
        app.post("/users") { req, res ->
            val user = req.body(User::class.java) ?: throw HttpException(400, "JSON body is null")
            res.status(201).json(user.id)
        }
        assertEquals(201, json("POST", "/users", "7").status())
        val rejected = json("POST", "/users", "null")
        assertEquals(400, rejected.status())
        assertTrue(rejected.text(), rejected.text().contains("JSON body is null"))
    }

    // docs-example: kotlin.md#9
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 kotlin md getters and setters are properties`() {
        app.get("/c") { req, res ->
            val token = "t"
            val cookie = Cookie("session", token).apply {
                isHttpOnly = true
                secure = true
                maxAge = 3600
            }
            val committed = res.isCommitted
            res.addCookie(cookie).send("committed=$committed")
        }
        val response = send("GET", "/c")
        assertEquals("committed=false", response.text())
        val header = response.header("Set-Cookie")!!
        for (part in listOf("session=t", "Max-Age=3600", "Secure", "HttpOnly")) {
            assertTrue(header, header.contains(part))
        }
    }

    // docs-example: lifecycle.md#1
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 lifecycle md onStart registers services and owns the database`() {
        var db: Database? = null
        app.onStart { ctx ->
            val db = Database()
            ctx.own(db)
            db.connect()
            ctx.register(Database::class.java, db)
            ctx.register(UserService::class.java, UserService(db))
        }
        app.get("/api/db") { req, res ->
            db = req.service(Database::class.java)
            res.end()
        }

        app.get("/api/users/:id") { req, res ->
            res.json(req.service(UserService::class.java).findById(req.param("id")))
        }

        assertEquals("user:7", send("GET", "/api/users/7").text())
        send("GET", "/api/db")
        assertTrue(db!!.connected)
        app.shutdown(2, TimeUnit.SECONDS)
        started = false
        assertTrue("ctx.own closes the database at shutdown", db!!.closed)
    }

    // docs-example: logging.md#1
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 logging md ConsoleLogger format names the level and the message`() {
        val line = ConsoleLogger.format(LogLevel.WARN, "disk almost full")
        assertTrue(line, line.contains("WARN"))
        assertTrue(line, line.contains("disk almost full"))
    }

    // docs-example: middleware.md#1
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 middleware md route builder runs its middleware in order`() {
        val auth = Middleware { req, res, next -> if (req.header("Authorization") == null) res.status(401).end() else next.run() }
        val adminOnly = Middleware { req, res, next -> if (req.header("X-Role") != "admin") res.status(403).end() else next.run() }
        app.route("/api/admin")
            .use(auth)
            .use(adminOnly)
            .get { req, res -> res.send("admin") }

        assertEquals(401, send("GET", "/api/admin").status())
        assertEquals(403, send("GET", "/api/admin", "Authorization", "t").status())
        assertEquals("admin", send("GET", "/api/admin", "Authorization", "t", "X-Role", "admin").text())
    }

    // docs-example: middleware.md#2
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 middleware md return at use answers 401 Unauthorized`() {
        val auth = object {
            fun isAuthorized(req: Request) = req.header("Authorization") == "ok"
        }
        app.use("/api") { req, res, next ->
            if (!auth.isAuthorized(req)) {
                res.status(401).json("Unauthorized")
                return@use
            }
            next.run()
        }
        app.get("/api/me") { req, res -> res.send("me") }

        val rejected = send("GET", "/api/me")
        assertEquals(401, rejected.status())
        assertEquals("Unauthorized", rejected.text())
        assertEquals("me", send("GET", "/api/me", "Authorization", "ok").text())
    }

    // docs-example: middleware.md#3
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 middleware md HttpException withHeader answers 503 with Retry-After`() {
        app.get("/api/busy") { req, res ->
            throw HttpException(503).withHeader("Retry-After", "1")
        }
        val response = send("GET", "/api/busy")
        assertEquals(503, response.status())
        assertEquals("1", response.header("Retry-After"))
    }

    // docs-example: multipart.md#1
    // adapted: uploadDir is a temporary folder instead of File("./uploads"), so the test writes nothing to the module
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 multipart md saves the file part and answers 400 without it`() {
        val uploadDir = temp

        app.post("/api/upload") { req, res ->
            val file = req.file("file")
            if (file == null || file.size() == 0L) {  // 브라우저의 빈 파일 입력은 크기 0인 파일로 옵니다
                res.status(400).json("Missing file")
                return@post
            }
            file.save(File(uploadDir, "${UUID.randomUUID()}.bin"))
            res.status(201).json(mapOf("size" to file.size()))
        }

        val missing = multipart("/api/upload", Triple("note", null, "x"))
        assertEquals(400, missing.status())
        assertEquals("Missing file", missing.text())
        val empty = multipart("/api/upload", Triple("file", "", ""))
        assertEquals(400, empty.status())
        assertEquals("Missing file", empty.text())
        val saved = multipart("/api/upload", Triple("file", "a.bin", "abc"))
        assertEquals(201, saved.status())
        assertEquals("{size=3}", saved.text())
        assertEquals("abc", uploadDir.listFiles()!!.single().readText())
    }

    // docs-example: request.md#1
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 request md form fields and file parts`() {
        var seen = ""
        app.formLimit(2L * 1024 * 1024)

        app.post("/profile") { req, res ->
            val name: String? = req.form("name")
            val avatar: UploadFile? = req.file("avatar")
            val photos: List<UploadFile> = req.files("photos")
            val fields = req.parts().count { it.filename() == null }
            seen = "$name ${avatar?.filename()} $fields"
            res.json(mapOf("photos" to photos.size))
        }

        val response = multipart("/profile", Triple("name", null, "kim"), Triple("avatar", "me.png", "a"),
            Triple("photos", "1.png", "b"), Triple("photos", "2.png", "c"))
        assertEquals("{photos=2}", response.text())
        assertEquals("kim me.png 1", seen)
    }

    // docs-example: request.md#2
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 request md attribute null clears the attribute`() {
        app.get("/a") { req, res ->
            req.attribute("user", "kim")
            req.attribute("user", null as Any?)                // "user" 속성을 지웁니다
            val left = req.attribute("user", String::class.java)
            res.send("left=$left")
        }
        assertEquals("left=null", send("GET", "/a").text())
    }

    // docs-example: response.md#1
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 response md json writes the map`() {
        app.get("/ok") { req, res ->
            res.json(mapOf("success" to true))
        }
        val response = send("GET", "/ok")
        assertEquals(200, response.status())
        assertEquals("{success=true}", response.text())
    }

    // docs-example: response.md#2
    // adapted: a temporary file stands in for File("/data/report.pdf")
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 response md file answers with the default cache policy`() {
        val report = File(temp, "report.pdf").apply { writeText("pdf") }
        app.defaultCache(CachePolicy.noCache())
        app.get("/report") { req, res -> res.file(report) }

        val response = send("GET", "/report")
        assertEquals(200, response.status())
        assertEquals("pdf", response.text())
        assertEquals(CachePolicy.noCache().headerValue, response.header("Cache-Control"))
    }

    // docs-example: response.md#3
    // adapted: a temporary file stands in for File("/data/video.mp4")
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 response md stream sends the input with its content type`() {
        val video = File(temp, "video.mp4").apply { writeText("mp4") }
        app.get("/video") { req, res ->
            res.stream(FileInputStream(video), "video/mp4")
        }
        val response = send("GET", "/video")
        assertEquals("video/mp4", response.header("Content-Type"))
        assertEquals("mp4", response.text())
    }

    private fun find(id: String) = "found:$id"
    private fun create(req: Request) = "created"
    private fun remove(id: String) {
        removed += id
    }

    private val removed = ArrayList<String>()

    // docs-example: routing.md#1
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 routing md one handler branches on the method`() {
        app.route(Method.get().post().delete(), "/users/:id") { req, res ->
            when (req.method()) {
                Method.GET, Method.HEAD -> res.json(find(req.param("id")))
                Method.POST -> res.status(201).json(create(req))
                Method.DELETE -> { remove(req.param("id")); res.status(204).end() }
                else -> res.status(405).end()
            }
        }
        assertEquals("found:1", send("GET", "/users/1").text())
        assertEquals(201, send("POST", "/users/1").status())
        assertEquals(204, send("DELETE", "/users/2").status())
        assertEquals(listOf("2"), removed)
        assertEquals(405, send("PUT", "/users/1").status())
    }

    // docs-example: routing.md#2
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 routing md MethodSet supports in for and size`() {
        val order = ArrayList<Method>()
        val set = Method.get().post().except(Method.POST)
        val canGet = Method.GET in set                 // true
        for (m in Method.of(Method.DELETE, Method.GET)) {  // GET, DELETE 순서
            println(m)
            order += m
        }
        val n = set.size()                             // 1
        assertTrue(canGet)
        assertEquals(listOf(Method.GET, Method.DELETE), order)
        assertEquals(1, n)
    }

    // docs-example: routing.md#3
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 routing md router policy ignores the trailing slash and the case`() {
        app.routerPolicy(
            RouterPolicy.builder()
                .ignoreTrailingSlash(true)
                .caseInsensitive(true)
                .build()
        )
        app.get("/users") { req, res -> res.send("users") }
        assertEquals("users", send("GET", "/USERS/").text())
    }

    // docs-example: sse.md#2
    @Test(timeout = 20000)
    fun `SDS-AC-2 FR-KT-004 AC-2 sse md send returns true and close ends the stream`() {
        app.get("/api/ticks") { req, res ->
            val client = res.sse()
            if (client.send("tick", "1")) {
                client.send("plain message")
            }
            client.close()
        }
        assertEquals("event: tick\ndata: 1\n\ndata: plain message\n\n", sse("/api/ticks"))
    }

    // docs-example: sse.md#3
    @Test(timeout = 20000)
    fun `SDS-AC-2 FR-KT-004 AC-2 sse md SseEvent carries event id data and retry`() {
        app.get("/api/progress") { req, res ->
            val client = res.sse()
            client.send(SseEvent.event("progress").id("100").data("{\"progress\":50}").retry(3000))
            client.close()
        }
        val text = sse("/api/progress")
        for (line in listOf("event: progress\n", "id: 100\n", "data: {\"progress\":50}\n", "retry: 3000\n")) {
            assertTrue(text, text.contains(line))
        }
    }

    // docs-example: sse.md#4
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 sse md options set the heartbeat and the send queue`() {
        app.sse { o -> o.heartbeatInterval(30, TimeUnit.SECONDS).sendQueueSize(512 * 1024) }
        val options = (app as DefaultRestApp).sseOptions()
        assertEquals(30000L, options.heartbeatMillis())
        assertEquals(524288, options.sendQueueSize())
    }

    // docs-example: sse.md#5
    @Test(timeout = 20000)
    fun `SDS-AC-2 FR-KT-004 AC-2 sse md Last-Event-ID resumes after the last event`() {
        app.get("/api/events") { req, res ->
            val lastId: String? = req.lastEventId()
            val client = res.sse()
            for (e in eventStore.after(lastId)) {
                client.send(SseEvent.event(e.type()).id(e.id()).data(e.json()))
            }
            client.close()
        }
        val text = sse("/api/events", "Last-Event-ID", "1")
        assertFalse(text, text.contains("id: 1\n"))
        assertTrue(text, text.contains("id: 2\n") && text.contains("id: 3\n"))
    }

    // docs-example: static.md#1
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 static md cache policy header values`() {
        val policy = CachePolicy.publicFor(365, TimeUnit.DAYS).immutable()
        println(policy.headerValue)   // public, max-age=31536000, immutable
        app.get("/version") { req, res ->
            res.cache(CachePolicy.noCache()).json(mapOf("version" to "1.0"))
        }
        assertEquals("public, max-age=31536000, immutable", policy.headerValue)
        val response = send("GET", "/version")
        assertEquals(CachePolicy.noCache().headerValue, response.header("Cache-Control"))
        assertEquals("{version=1.0}", response.text())
    }

    // docs-example: websocket.md#1
    @Test(timeout = 20000)
    fun `SDS-AC-2 FR-KT-004 AC-2 websocket md echo with onClose`() {
        val closed = java.util.concurrent.CountDownLatch(1)
        app.ws("/ws") { req, ws ->
            ws.onText { message -> ws.sendText("Echo: $message") }
            ws.onClose { code, reason -> app.logger().info("closed $code") }
            ws.onClose { code, reason -> closed.countDown() }
        }
        val (response, channel) = ws("/ws")
        channel.clientText("hi")
        await(channel) { it.contains("Echo: hi") }
        channel.clientClose(1000, "")
        assertTrue(closed.await(10, TimeUnit.SECONDS))
        assertEquals(101, response.get(10, TimeUnit.SECONDS).status())
    }

    // docs-example: websocket.md#3
    @Test(timeout = 20000)
    fun `SDS-AC-2 FR-KT-004 AC-2 websocket md sends text and JSON on open`() {
        app.ws("/notify") { req, ws ->
            ws.sendText("hello")
            ws.sendJson(mapOf("type" to "welcome"))
        }
        val (_, channel) = ws("/notify")
        try {
            await(channel) { it.size >= 2 }
            assertEquals(listOf<Any>("hello", "{type=welcome}"), channel.sent())
        } finally {
            channel.clientClose(1000, "")
        }
    }

    // docs-example: websocket.md#4
    @Test(timeout = 20000)
    fun `SDS-AC-2 FR-KT-004 AC-2 websocket md binary echo with onClose and onError`() {
        app.ws("/chat") { req, ws ->
            ws.onBinary { bytes -> ws.sendBinary(bytes) }
            ws.onClose { code, reason -> app.logger().info("closed $code $reason") }
            ws.onError { error -> app.logger().error("ws failed", error) }
        }
        val (_, channel) = ws("/chat")
        try {
            channel.clientBinary(byteArrayOf(1, 2, 3))
            await(channel) { it.isNotEmpty() }
            assertArrayEquals(byteArrayOf(1, 2, 3), channel.sent()[0] as ByteArray)
        } finally {
            channel.clientClose(1000, "")
        }
    }

    // docs-example: websocket.md#5
    @Test(timeout = 20000)
    fun `SDS-AC-2 FR-KT-004 AC-2 websocket md session attribute and close on bye`() {
        val user = java.util.concurrent.atomic.AtomicReference<String?>()
        app.ws("/session") { req, ws ->
            ws.attribute("user", req.header("X-User"))
            ws.onText { m -> if (m == "bye") ws.close(1000, "bye") }
            user.set(ws.attribute("user", String::class.java))
        }
        val (_, channel) = ws("/session", "X-User", "kim")
        channel.clientText("bye")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (channel.closeCode() != 1000 && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(1000, channel.closeCode())
        assertEquals("bye", channel.closeReason())
        assertEquals("kim", user.get())
    }

    // docs-example: websocket.md#6
    @Test(timeout = 20000)
    fun `SDS-AC-2 FR-KT-004 AC-2 websocket md allowedOrigins rejects another origin with 403`() {
        app.webSocket { o -> o.allowedOrigins("https://app.example.com") }
        app.ws("/ws") { req, ws -> }
        val rejected = send("GET", "/ws", "Connection", "Upgrade", "Upgrade", "websocket", "Origin", "https://evil.example")
        assertEquals(403, rejected.status())
        val (response, channel) = ws("/ws", "Origin", "https://app.example.com")
        channel.clientClose(1000, "")
        assertEquals(101, response.get(10, TimeUnit.SECONDS).status())
    }

    // docs-example: websocket.md#7
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 websocket md options chain sets the idle timeout`() {
        app.webSocket { o -> o.allowedOrigins("https://app.example.com").idleTimeout(1, TimeUnit.MINUTES) }
        val options = (app as DefaultRestApp).webSocketOptions()
        assertEquals(60000L, options.idleTimeoutMillis())
        assertEquals(listOf("https://app.example.com"), options.origins())
    }

    // Negative case of the marker check against this very file (it sits in the extent of the last marker).
    @Test
    fun `SDS-AC-2 FR-KT-004 AC-2 this file with a kotlin md block line removed fails naming the block`() {
        val path = "src/test/kotlin/com/snoworca/naruserve/KotlinDocsExamplesKotlinTest.kt"
        val source = File(System.getProperty("user.dir"), path).readText()
        val line = "            val page = req.query(\"page\")?.toIntOrNull() ?: 1\n"
        assertTrue(source.contains(line))
        val kotlinDoc = File(System.getProperty("user.dir"), "../docs/api/kotlin.md").readText()
        val failures = KotlinDocsMarkers.failures(mapOf("kotlin.md" to kotlinDoc), mapOf(path to source.replace(line, "")))
        assertTrue(failures.toString(), failures.any { it.startsWith("kotlin.md#4") && it.contains("toIntOrNull") })
        assertFalse(failures.toString(), KotlinDocsMarkers.failures(mapOf("kotlin.md" to kotlinDoc), mapOf(path to source))
            .any { it.startsWith("kotlin.md#4") })
    }
}
