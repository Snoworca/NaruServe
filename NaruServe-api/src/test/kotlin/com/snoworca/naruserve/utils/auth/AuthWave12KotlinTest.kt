package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.utils.auth.spi.KeySource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/** The wave 12 public API from Kotlin: an app KeySource and the login limits (CLAUDE.md T11). */
class AuthWave12KotlinTest {

    private fun kid(token: String): String {
        val header = String(Base64.getUrlDecoder().decode(token.substringBefore('.')), Charsets.UTF_8)
        return Regex("\"kid\":\"([^\"]+)\"").find(header)!!.groupValues[1]
    }

    // SEC-TOKEN-004 AC-8 (the Kotlin half): a KeySource implemented in Kotlin, passed to Auth.builder(KeySource),
    // compiles and runs: the login's access token carries its kid vault-1 and authenticates a protected request
    @Test
    fun `SEC-TOKEN-004 AC-8 a Kotlin KeySource signs`() {
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val vault = object : KeySource {
            override fun signingKey(): ByteArray = key.copyOf()
            override fun signingKid(): String = "vault-1"
        }
        val w = W8()
        w.keys = vault
        try {
            w.user("alice", W8.PW, "reader")
            w.start { it }
            val at = w.loginBody("alice", W8.PW)["accessToken"] as String
            assertEquals("vault-1", kid(at))
            assertEquals(200, w.get("/api/x", "Authorization", "Bearer $at").status())
        } finally {
            w.stop()
        }
        // the SAM form: one lambda is a KeySource whose kid is derived from the key
        val lambda = KeySource { key.copyOf() }
        val auth = Auth.builder(lambda).hashIterations(1000).build()
        assertEquals(auth.keySet().signingKid, Auth.builder(KeySources.fromBytes(key)).hashIterations(1000).build()
            .keySet().signingKid)
    }

    // SEC-FLOW-011 AC-7 (from Kotlin): .loginFailureLimits(perUserAndIp, perIp, perUser, windowMillis) replaces the
    // defaults; with the (user, IP) limit at 3 the 4th attempt from one IP is 429 too_many_attempts
    @Test
    fun `SEC-FLOW-011 AC-7 login limits from Kotlin`() {
        val w = W8()
        try {
            w.user("alice", W8.PW, "reader")
            w.start { it.loginFailureLimits(3, 20, 10, 900_000L) }
            repeat(3) {
                assertEquals(401, w.post("/auth/login", W8.login("alice", "wrong")).status())
            }
            val limited = w.post("/auth/login", W8.login("alice", W8.PW))
            assertEquals(429, limited.status())
            assertEquals("too_many_attempts", W8.code(limited))
        } finally {
            w.stop()
        }
    }
}
