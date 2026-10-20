package example

import com.snoworca.naruserve.HttpException
import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.kotlin.body
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Complete JVM example. @req OPS-DX-001 */
object KotlinExample {
    // Default Gson invokes this constructor. Validate nullable fields before use.
    class CreateUserRequest {
        var name: String? = null
    }

    private class UserService {
        private val nextId = AtomicInteger()
        private val names = ConcurrentHashMap<String, String>()
        fun create(name: String): Map<String, String> {
            val id = nextId.incrementAndGet().toString()
            names[id] = name
            return user(id)
        }
        fun user(id: String): Map<String, String> {
            val name = names[id] ?: throw HttpException(404, "User not found")
            return mapOf("id" to id, "name" to name)
        }
    }

    @JvmStatic
    fun create(uploads: File): RestApp {
        if (!uploads.isDirectory && !uploads.mkdirs() && !uploads.isDirectory) {
            throw IOException("Cannot create upload directory: $uploads")
        }
        val app = RestApp.create()
        val users = UserService()
        app.get("/hello") { _, res -> res.send("Hello World") }
        app.get("/page") { req, res -> res.send(req.queryInt("page", 1).toString()) }
        app.get("/users/:id") { req, res -> res.send(users.user(req.param("id"))) }
        app.post("/users") { req, res ->
            val input = req.body<CreateUserRequest>()
            val name = input.name?.trim()
            if (name.isNullOrEmpty()) throw HttpException(400, "name is required")
            res.status(201).send(users.create(name))
        }
        app.post("/upload") { req, res ->
            val file = req.file("file")
            if (file == null || file.size() == 0L) throw HttpException(400, "Missing file")
            file.save(File(uploads, "${UUID.randomUUID()}.bin"))
            res.status(201).send(mapOf("size" to file.size()))
        }
        app.get("/stream") { _, res ->
            // NaruServe closes the stream after transfer. Do not wrap it in use { }.
            res.stream(ByteArrayInputStream("stream example".toByteArray(Charsets.UTF_8)), "text/plain; charset=UTF-8")
        }
        return app
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val app = create(File(args.getOrNull(1) ?: "./uploads"))
        val port = args.getOrNull(0)?.toInt() ?: 3000
        Runtime.getRuntime().addShutdownHook(Thread { app.shutdown(5, TimeUnit.SECONDS) })
        println("Listening on http://127.0.0.1:${app.listen(port).port()}")
    }
}
