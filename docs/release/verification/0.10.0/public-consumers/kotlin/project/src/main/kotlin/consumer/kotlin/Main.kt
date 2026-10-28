package consumer.kotlin

import com.google.gson.Gson
import com.snoworca.naruserve.Deferred
import com.snoworca.naruserve.Request
import com.snoworca.naruserve.Response
import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.Router
import com.snoworca.naruserve.ServerHandle
import com.snoworca.naruserve.kotlin.body
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
private fun open(port: Int, method: String, target: String, body: String? = null): Socket {
    val socket = Socket()
    socket.connect(InetSocketAddress("127.0.0.1", port), 5000)
    socket.soTimeout = 5000
    val bytes = body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
    var head = "$method $target HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n"
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
fun main() {
    check(System.getProperty("java.specification.version") == "1.8") { "actual Java8 runtime required" }
    val app = RestApp.create()
    val producer = Executors.newSingleThreadExecutor()
    var handle: ServerHandle? = null
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
        report["javaVersion"] = System.getProperty("java.version")
        report["javaHome"] = System.getProperty("java.home")
        report["kotlinVersion"] = KotlinVersion.CURRENT.toString()
        report["checks"] = listOf("published Kotlin reified body metadata", "mounted Router named wildcard and decoded segment list", "typed List<String> and nullable String? absent capture", "three-argument Middleware", "getAsync returned Deferred and late HTTP response", "typed generic Gson List<Payload>", "first typed body read after wire202 with pending Deferred and cache identity")
        report["passed"] = true
    } finally {
        late.resolve()
        bodyWork.resolve()
        val server = handle
        if (server != null) server.shutdown(2, TimeUnit.SECONDS) else app.shutdown(0, TimeUnit.MILLISECONDS)
        producer.shutdownNow()
        check(producer.awaitTermination(5, TimeUnit.SECONDS)) { "producer thread leaked" }
    }
    val json = Gson().toJson(report)
    val target = Paths.get(System.getProperty("consumer.report", "build/evidence/runtime.json"))
    Files.createDirectories(target.toAbsolutePath().parent)
    Files.write(target, json.toByteArray(StandardCharsets.UTF_8))
    println(json)
}
