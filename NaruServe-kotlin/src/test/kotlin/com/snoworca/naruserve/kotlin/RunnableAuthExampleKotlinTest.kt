package com.snoworca.naruserve.kotlin

import com.snoworca.naruserve.RestApp
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** examples/kotlin AuthExample over real HTTP (OPS-AUTH-007 AC-7). */
class RunnableAuthExampleKotlinTest {

    private fun call(port: Int, method: String, path: String, token: String?, json: String?): String {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 10000
        c.requestMethod = method
        c.setRequestProperty("Connection", "close")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (json != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val status = c.responseCode
            val body = (if (status >= 400) c.errorStream else c.inputStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return "$status $body"
        } finally {
            c.disconnect()
        }
    }

    private fun login(port: Int, user: String, password: String): String {
        val answer = call(port, "POST", "/auth/login", null, """{"username":"$user","password":"$password"}""")
        assertTrue(answer, answer.startsWith("200 "))
        val token = Regex("\"accessToken\":\"([^\"]+)\"").find(answer)
        assertTrue(answer, token != null)
        return token!!.groupValues[1]
    }

    // OPS-AUTH-007 AC-7: the Kotlin auth example starts on port 0, serves /health in public, logs alice in over HTTP,
    // answers the protected /me with her Bearer token, lets the editor write and refuses bob, who has no role
    @Test
    fun OPS_AUTH_007_AC7_kotlinAuthExampleLogsInOverHttp() {
        // loaded by name: the build-check copy of the checkout compiles this module without examples/kotlin
        val app = Class.forName("example.AuthExample").getMethod("create").invoke(null) as RestApp
        try {
            val port = app.listen(0).port()
            assertEquals("200 ok", call(port, "GET", "/health", null, null))
            assertTrue(call(port, "GET", "/me", null, null).startsWith("401 "))
            val alice = login(port, "alice", "alice-password-1")
            assertEquals("200 alice", call(port, "GET", "/me", alice, null))
            assertEquals("201 created by alice", call(port, "POST", "/posts", alice, "{}"))
            val bob = login(port, "bob", "bob-password-1")
            assertEquals("200 bob", call(port, "GET", "/me", bob, null))
            val refused = call(port, "POST", "/posts", bob, "{}")
            assertTrue(refused, refused.startsWith("403 ") && refused.contains("insufficient_scope"))
        } finally {
            app.shutdown(5, TimeUnit.SECONDS)
        }
    }
}
