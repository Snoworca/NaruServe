// docs-example: auth.md#41
// OPS-AUTH-007 AC-5: the HTTP test recipe of the auth guide, verbatim, runs as a real JUnit test over TinyHttpd
package com.snoworca.naruserve.kotlin

import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.utils.auth.Auth
import com.snoworca.naruserve.utils.auth.AuthMode
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PostsApiTest {
    private lateinit var app: RestApp
    private var port = 0

    @Before
    fun start() {
        val auth = Auth.builder(System.getenv("JWT_SECRET"))
            .mode(AuthMode.DEV)
            .userCacheTtl(0)
            .roles(RolePolicy.builder().role("editor", "posts:write").build())
            .user("alice", "alice-password-1", "editor")
            .build()
        app = RestApp.create()
        app.use(auth)
        app.post("/posts", auth.require("posts:write")) { _, res -> res.status(201).send("created") }
        app.onError(auth.errors())
        port = app.listen(0).port()
    }

    @After
    fun stop() {
        app.shutdown(5, TimeUnit.SECONDS)
    }

    @Test
    fun editorCanPost() {
        val login = call("POST", "/auth/login", null, """{"username":"alice","password":"alice-password-1"}""")
        val token = Regex("\"accessToken\":\"([^\"]+)\"").find(login)?.groupValues?.get(1)
        assertTrue(login, token != null)
        assertEquals("201 created", call("POST", "/posts", token, "{}"))
        assertTrue(call("POST", "/posts", null, "{}").startsWith("401"))
    }

    private fun call(method: String, path: String, token: String?, json: String?): String {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (json != null) { // 본문이 있으면 HttpURLConnection이 GET을 POST로 바꿉니다
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        val status = c.responseCode
        val body = (if (status >= 400) c.errorStream else c.inputStream).use { it.readBytes().toString(Charsets.UTF_8) }
        c.disconnect()
        return "$status $body"
    }
}
