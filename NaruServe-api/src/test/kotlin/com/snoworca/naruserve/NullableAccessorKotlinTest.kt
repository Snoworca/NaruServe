package com.snoworca.naruserve

import com.snoworca.naruserve.spi.JsonCodec
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.testing.TestApp
import java.io.File
import java.io.Reader
import java.io.Writer
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One function per row of the nullable-accessor table of docs/api/kotlin.md, named after the accessor
 * (`accessor_<javaName>`, read by NullableAccessorDocsTest). Every local carries the Kotlin type of its row and the
 * runtime value is asserted; the annotations themselves are checked by KotlinNullabilityTest over the api jar.
 */
class NullableAccessorKotlinTest {

    class User(val name: String)

    class UserService

    /** Decodes `null` to null, a list type to users split at commas, and anything else to one user. */
    private object UserCodec : JsonCodec {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> decode(reader: Reader, type: Type): T? {
            val text = reader.readText().trim()
            if (text == "null") {
                return null
            }
            if (type is ParameterizedType && type.rawType == List::class.java) {
                return text.split(',').map { User(it) } as T
            }
            return User(text) as T
        }

        override fun encode(value: Any?, writer: Writer) = throw UnsupportedOperationException("encode")
    }

    /** One app whose route on [path] stores what [read] returns for each request. */
    private class Probe(method: String, path: String = "/r", read: (Request) -> Any?) {
        val adapter = InMemoryHttpAdapter()
        val t: TestApp = TestApp.create(adapter)
        val seen = ArrayList<Any?>()

        init {
            t.app().jsonCodec(UserCodec)
            t.app().route(Method.valueOf(method), path) { req, res ->
                seen += read(req)
                res.send("ok")
            }
        }

        fun send(method: String, target: String, body: ByteArray? = null, vararg headers: String): Any? {
            val request = ClientRequest.of(method, target)
            for (i in headers.indices step 2) {
                request.header(headers[i], headers[i + 1])
            }
            if (body != null) {
                request.body(body)
            }
            t.start()
            val response = adapter.send(request)
            assertEquals(response.text(), 200, response.status())
            return seen.removeAt(0)
        }

        fun get(target: String, vararg headers: String): Any? = send("GET", target, null, *headers)
    }

    private val boundary = "XyZ"

    private fun multipart(vararg parts: Pair<String, String?>): ByteArray {
        val sb = StringBuilder()
        for ((name, filename) in parts) {
            sb.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(name)
                .append('"')
            if (filename != null) {
                sb.append("; filename=\"").append(filename).append("\"\r\nContent-Type: text/plain")
            }
            sb.append("\r\n\r\n").append(filename ?: "field").append("\r\n")
        }
        return sb.append("--").append(boundary).append("--\r\n").toString().toByteArray()
    }

    private val mp = "multipart/form-data; boundary=XyZ"

    // SDS-AC-6; FR-KT-005 AC-2
    @Test
    fun `FR-KT-005 AC-2 accessor_param is String and throws IllegalArgumentException for a name the pattern lacks`() {
        val p = Probe("GET", "/u/:id") { req ->
            val id: String = req.param("id")
            val missing = runCatching { val other: String = req.param("other"); other }.exceptionOrNull()
            listOf(id, missing?.javaClass)
        }
        assertEquals(listOf("7", IllegalArgumentException::class.java), p.get("/u/7"))
    }

    // OPS-REL-008 AC-3; FR-HTTP-027 AC-1 AC-6; FR-KT-005 AC-4.
    @Test
    fun `accessor_paramOrNull is String nullable for absent optionals and a decoded scalar when present`() {
        val absent = Probe("GET", "/optional{/:id}") { req ->
            val value: String? = req.paramOrNull("id")
            val undeclared: String? = req.paramOrNull("missing")
            listOf(value, undeclared)
        }
        try {
            assertEquals(listOf(null, null), absent.get("/optional"))
        } finally {
            absent.t.app().shutdown(0, TimeUnit.SECONDS)
        }
        val present = Probe("GET", "/optional{/:id}") { req ->
            val value: String? = req.paramOrNull("id")
            value
        }
        try {
            assertEquals("a/b", present.get("/optional/a%2Fb"))
        } finally {
            present.t.app().shutdown(0, TimeUnit.SECONDS)
        }
    }

    // OPS-REL-008 AC-3; FR-HTTP-027 AC-1 AC-6; FR-KT-005 AC-4.
    @Test
    fun `accessor_paramValues is immutable List String for absent scalar and wildcard captures`() {
        val cases = listOf(
            Triple("/files{/*parts}", "/files", Pair(emptyList<String>(), false)),
            Triple("/files{/*parts}", "/files/a%2Fb/c", Pair(listOf("a/b", "c"), true)),
            Triple("/files{/*parts}", "/files/one", Pair(listOf("one"), true)),
            Triple("/scalar/:parts", "/scalar/one", Pair(listOf("one"), false))
        )
        for ((path, target, expected) in cases) {
            val probe = Probe("GET", path) { req ->
                val values: List<String> = req.paramValues("parts")
                val mutation = runCatching { (values as MutableList<String>).add("mutated") }.exceptionOrNull()
                listOf(values, mutation?.javaClass, req.paramValues("parts"), req.pathParams()["parts"] is List<*>)
            }
            try {
                assertEquals(target, listOf(expected.first, UnsupportedOperationException::class.java,
                    expected.first, expected.second), probe.get(target))
            } finally {
                probe.t.app().shutdown(0, TimeUnit.SECONDS)
            }
        }
    }

    // SDS-AC-5; FR-KT-005 AC-1
    @Test
    fun `FR-KT-005 AC-1 accessor_query is String? and the elvis default applies when absent`() {
        val p = Probe("GET") { req ->
            val raw: String? = req.query("page")
            val page: Int = req.query("page")?.toIntOrNull() ?: 1
            listOf(raw, page)
        }
        assertEquals(listOf(null, 1), p.get("/r"))
        assertEquals(listOf("3", 3), p.get("/r?page=3"))
    }

    // SDS-AC-5; FR-KT-005 AC-1
    @Test
    fun `FR-KT-005 AC-1 accessor_form is String? and null when the field is absent`() {
        val p = Probe("POST") { req ->
            val name: String? = req.form("name")
            name
        }
        val url = "application/x-www-form-urlencoded"
        assertEquals(null, p.send("POST", "/r", "other=1".toByteArray(), "Content-Type", url))
        assertEquals("Ann", p.send("POST", "/r", "name=Ann".toByteArray(), "Content-Type", url))
    }

    // SDS-AC-5; FR-KT-005 AC-1
    @Test
    fun `FR-KT-005 AC-1 accessor_header is String? and null when the header is absent`() {
        val p = Probe("GET") { req ->
            val auth: String? = req.header("Authorization")
            auth
        }
        assertEquals(null, p.get("/r"))
        assertEquals("Bearer t", p.get("/r", "Authorization", "Bearer t"))
    }

    // SDS-AC-5; FR-KT-005 AC-1
    @Test
    fun `FR-KT-005 AC-1 accessor_cookie is Cookie? and the safe call gives null when absent`() {
        val p = Probe("GET") { req ->
            val cookie: Cookie? = req.cookie("session")
            val value: String? = req.cookie("session")?.value
            listOf(cookie?.name, value)
        }
        assertEquals(listOf(null, null), p.get("/r"))
        assertEquals(listOf("session", "abc"), p.get("/r", "Cookie", "session=abc"))
    }

    // SDS-AC-5; FR-KT-005 AC-1
    @Test
    fun `FR-KT-005 AC-1 accessor_file is UploadFile? and save runs only when present`() {
        val dir = Files.createTempDirectory("naru-file").toFile()
        try {
            val dest = File(dir, "saved.txt")
            val p = Probe("POST") { req ->
                val file: UploadFile? = req.file("file")
                req.file("file")?.save(dest)
                file?.name()
            }
            assertEquals(null, p.send("POST", "/r", multipart("name" to null), "Content-Type", mp))
            assertTrue(!dest.exists())
            assertEquals("file", p.send("POST", "/r", multipart("file" to "a.txt"), "Content-Type", mp))
            assertEquals("a.txt", dest.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    // SDS-AC-5; FR-KT-005 AC-1
    @Test
    fun `FR-KT-005 AC-1 accessor_lastEventId is String? and null without Last-Event-ID`() {
        val p = Probe("GET") { req ->
            val id: String? = req.lastEventId()
            id
        }
        assertEquals(null, p.get("/r"))
        assertEquals("42", p.get("/r", "Last-Event-ID", "42"))
    }

    // SDS-AC-5; FR-KT-005 AC-1
    @Test
    fun `FR-KT-005 AC-1 accessor_attributeAs is T? and null when unset`() {
        val p = Probe("GET") { req ->
            if (req.query("set") != null) {
                req.attribute("k", "v")
            }
            val k: String? = req.attributeAs("k", String::class.java)
            k
        }
        assertEquals(null, p.get("/r"))
        assertEquals("v", p.get("/r?set=1"))
    }

    // SDS-AC-6; FR-KT-005 AC-2
    @Test
    fun `FR-KT-005 AC-2 accessor_queries is a non-null List and empty when absent`() {
        val p = Probe("GET") { req ->
            val tags: List<String> = req.queries("tag")
            tags
        }
        assertEquals(emptyList<String>(), p.get("/r"))
        assertEquals(listOf("a", "b"), p.get("/r?tag=a&tag=b"))
    }

    // SDS-AC-6; FR-KT-005 AC-2
    @Test
    fun `FR-KT-005 AC-2 accessor_files is a non-null List and empty when absent`() {
        val p = Probe("POST") { req ->
            val files: List<UploadFile> = req.files("f")
            files.map { it.name() }
        }
        assertEquals(emptyList<String>(), p.send("POST", "/r", multipart("name" to null), "Content-Type", mp))
        assertEquals(listOf("f", "f"),
            p.send("POST", "/r", multipart("f" to "a.txt", "f" to "b.txt"), "Content-Type", mp))
    }

    // SDS-AC-6; FR-KT-005 AC-2
    @Test
    fun `FR-KT-005 AC-2 accessor_service is T and throws for an unregistered type`() {
        val service = UserService()
        val p = Probe("GET") { req ->
            val registered: UserService = req.service(UserService::class.java)
            val missing = runCatching { val u: User = req.service(User::class.java); u }.exceptionOrNull()
            listOf(registered, missing?.javaClass)
        }
        p.t.app().onStart { ctx -> ctx.register(UserService::class.java, service) }
        assertEquals(listOf(service, ServiceNotFoundException::class.java), p.get("/r"))
    }

    // SDS-AC-7; FR-KT-005 AC-3
    @Test
    fun `FR-KT-005 AC-3 accessor_body is T? and null for a JSON null body through both overloads`() {
        val json = "application/json"
        val one = Probe("POST") { req ->
            val user: User? = req.body(User::class.java)
            user?.name
        }
        assertEquals(null, one.send("POST", "/r", "null".toByteArray(), "Content-Type", json))
        assertEquals("Ann", one.send("POST", "/r", "Ann".toByteArray(), "Content-Type", json))

        val many = Probe("POST") { req ->
            val users: List<User>? = req.body(object : TypeRef<List<User>>() {})
            users?.map { it.name }
        }
        assertEquals(null, many.send("POST", "/r", "null".toByteArray(), "Content-Type", json))
        assertEquals(listOf("Ann", "Bo"), many.send("POST", "/r", "Ann,Bo".toByteArray(), "Content-Type", json))
    }
}
