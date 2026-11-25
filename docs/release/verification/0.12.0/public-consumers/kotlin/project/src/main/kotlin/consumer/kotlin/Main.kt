package consumer.kotlin

import com.google.gson.Gson
import com.snoworca.naruserve.Deferred
import com.snoworca.naruserve.Request
import com.snoworca.naruserve.Response
import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.Router
import com.snoworca.naruserve.ServerHandle
import com.snoworca.naruserve.kotlin.body
import com.snoworca.naruserve.proxy.Proxy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Payload { var message: String? = null }
private data class Reply(val status: Int, val text: String)
private fun open(port: Int, method: String, target: String, body: String? = null, extraHeaders: String = ""): Socket {
    val socket = Socket()
    socket.connect(InetSocketAddress("127.0.0.1", port), 5000)
    socket.soTimeout = 5000
    val bytes = body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
    var head = "$method $target HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n$extraHeaders"
    if (body != null) head += "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n"
    socket.getOutputStream().apply { write((head + "\r\n").toByteArray(StandardCharsets.ISO_8859_1)); write(bytes); flush() }
    return socket
}
private fun read(socket: Socket): Reply {
    val input = socket.getInputStream()
    val head = ByteArrayOutputStream()
    var tail = 0
    while (tail != 0x0d0a0d0a) {
        val byte = input.read()
        check(byte >= 0) { "truncated response head" }
        head.write(byte)
        tail = (tail shl 8) or byte
    }
    val lines = String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n")
    val status = lines.first().split(" ")[1].toInt()
    val length = lines.first { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(':').trim().toInt()
    val body = ByteArrayOutputStream()
    repeat(length) { val byte = input.read(); check(byte >= 0) { "truncated response body" }; body.write(byte) }
    return Reply(status, String(body.toByteArray(), StandardCharsets.UTF_8))
}
/** Backend echo: reads the body with req.bodyStream() and answers with res.relay(in, length). */
private fun echo(request: Request, response: Response) {
    val received = request.bodyStream().use { it.readBytes() }
    val text = "method=${request.methodToken()} path=${request.rawPath()} x=${request.query("x")} host=${request.header("Host")}" +
        " xff=${request.header("X-Forwarded-For")} xfh=${request.header("X-Forwarded-Host")} body=${String(received, StandardCharsets.UTF_8)}"
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    response.type("text/plain; charset=utf-8")
    response.relay(ByteArrayInputStream(bytes), bytes.size.toLong())
}
fun main() {
    check(System.getProperty("java.specification.version") == "1.8") { "actual Java8 runtime required" }
    val app = RestApp.create()
    val producer = Executors.newSingleThreadExecutor()
    var handle: ServerHandle? = null
    val backend = RestApp.create()
    var backendHandle: ServerHandle? = null
    var backendStopped = false
    val late = Deferred.pending()
    val bodyWork = Deferred.pending()
    val laterResponse = AtomicReference<Response>()
    val retained = AtomicReference<Request>()
    val ready = CountDownLatch(1)
    val report = linkedMapOf<String, Any>()
    try {
        val router = Router.create()
        router.use { request, _, next -> request.attribute("three-argument-middleware", "yes"); next.run() }
        router.get("/files{/*parts}") { request, response ->
            val parts: List<String> = request.paramValues("parts")
            val first: String? = request.paramOrNull("parts")
            val absent: String? = request.paramOrNull("absent")
            check(absent == null)
            check(first == parts.firstOrNull())
            check(request.attributeAs("three-argument-middleware", String::class.java) == "yes")
            response.json(parts)
        }
        app.use("/api", router)
        app.post("/typed") { request, response ->
            val values: List<Payload> = request.body()
            response.json(values.map { checkNotNull(it.message) })
        }
        app.getAsync("/later") { _, response, _ -> laterResponse.set(response); ready.countDown(); late }
        app.methodAsync("POST", "/retained") { request, response, _ -> retained.set(request); response.status(202).send("ack"); bodyWork }
        backend.use { request, response, _ -> echo(request, response) }
        val backendServer = backend.listen(0)
        backendHandle = backendServer
        val backendPort = backendServer.port()
        check(backendPort > 0)
        val backendUrl = "http://127.0.0.1:$backendPort"
        app.use("/keep", Proxy.to(backendUrl))
        app.use("/strip", Proxy.to(backendUrl).stripPrefix(true))
        handle = app.listen(0)
        val port = handle.port()
        check(port > 0)
        open(port, "GET", "/API/files/a%2Fb/c").use { val value = read(it); check(value.status == 200 && value.text == "[\"a/b\",\"c\"]") { value } }
        open(port, "GET", "/api/files").use { val value = read(it); check(value.status == 200 && value.text == "[]") { value } }
        open(port, "POST", "/typed", "[{\"message\":\"typed Gson\"}]").use { val value = read(it); check(value.status == 200 && value.text == "[\"typed Gson\"]") { value } }
        open(port, "GET", "/later").use { socket ->
            check(ready.await(5, TimeUnit.SECONDS))
            val work = producer.submit { laterResponse.get().send("later"); late.resolve() }
            val value = read(socket)
            work.get(5, TimeUnit.SECONDS)
            check(value.status == 200 && value.text == "later") { value }
        }
        open(port, "POST", "/retained", "{\"message\":\"Kotlin after ack\"}").use { socket ->
            val ack = read(socket)
            check(ack.status == 202 && ack.text == "ack") { ack }
            check(!bodyWork.isDone)
            val payload: Payload = retained.get().body()
            check(payload.message == "Kotlin after ack")
            check(payload === retained.get().body<Payload>())
            check(bodyWork.resolve())
        }
        val backendHost = "127.0.0.1:$backendPort"
        val spoof = "X-Forwarded-For: 203.0.113.9\r\n"
        open(port, "GET", "/keep/items?x=1", extraHeaders = spoof).use { val value = read(it); check(value.status == 200 && value.text == "method=GET path=/keep/items x=1 host=$backendHost xff=127.0.0.1 xfh=localhost body=") { value } }
        open(port, "GET", "/strip/items?x=1", extraHeaders = spoof).use { val value = read(it); check(value.status == 200 && value.text == "method=GET path=/items x=1 host=$backendHost xff=127.0.0.1 xfh=localhost body=") { value } }
        open(port, "POST", "/keep/echo", "{\"message\":\"Kotlin proxied body\"}").use { val value = read(it); check(value.status == 200 && value.text == "method=POST path=/keep/echo x=null host=$backendHost xff=127.0.0.1 xfh=localhost body={\"message\":\"Kotlin proxied body\"}") { value } }
        backendServer.shutdown(2, TimeUnit.SECONDS)
        backendStopped = true
        open(port, "GET", "/keep/items").use { val value = read(it); check(value.status == 502) { value }; check(!value.text.contains(backendHost)) { "502 page revealed the backend address" } }
        report["javaVersion"] = System.getProperty("java.version")
        report["javaHome"] = System.getProperty("java.home")
        report["kotlinVersion"] = KotlinVersion.CURRENT.toString()
        report["checks"] = listOf("published Kotlin reified body metadata", "mounted Router named wildcard and decoded segment list", "typed List<String> and nullable String? absent capture", "three-argument Middleware", "getAsync returned Deferred and late HTTP response", "typed generic Gson List<Payload>", "first typed body read after wire202 with pending Deferred and cache identity",
            "naruserve-proxy Proxy.to relays GET to a second NaruServe backend with mount prefix and query kept", "Proxy stripPrefix(true) forwards only the path after the mount",
            "client X-Forwarded-For dropped without proxy trust, socket address 127.0.0.1 sent, Host replaced by target, X-Forwarded-Host from client Host",
            "POST JSON body relayed through the proxy", "backend raw access req.bodyStream() and res.relay(in, contentLength)", "stopped backend gives 502 without revealing the backend address")
        report["passed"] = true
    } finally {
        late.resolve()
        bodyWork.resolve()
        val server = handle
        if (server != null) server.shutdown(2, TimeUnit.SECONDS) else app.shutdown(0, TimeUnit.MILLISECONDS)
        if (!backendStopped) { val other = backendHandle; if (other != null) other.shutdown(2, TimeUnit.SECONDS) else backend.shutdown(0, TimeUnit.MILLISECONDS) }
        producer.shutdownNow()
        check(producer.awaitTermination(5, TimeUnit.SECONDS)) { "producer thread leaked" }
    }
    val json = Gson().toJson(report)
    val target = Paths.get(System.getProperty("consumer.report", "build/evidence/runtime.json"))
    Files.createDirectories(target.toAbsolutePath().parent)
    Files.write(target, json.toByteArray(StandardCharsets.UTF_8))
    println(json)
}
