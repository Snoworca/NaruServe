package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.testing.TestApp
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore
import com.snoworca.naruserve.utils.auth.audit.AuditEvent
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy
import com.snoworca.naruserve.utils.auth.testing.AuthTesting
import com.snoworca.naruserve.utils.auth.user.AuthUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 00 §3 Kotlin minimal path and the Kotlin property syntax of the auth value objects (CLAUDE.md T11). */
class AuthWave11KotlinTest {

    // CON-AUTH-004 AC-5: the 00 §3 Kotlin minimal path compiles and runs: Auth.builder(...).mode(AuthMode.fromEnv())
    // .user(...).roles(...).permit("/health").build(), auth.install(app), app.post("/posts", auth.require("posts:write"))
    // { req, res -> ... }, app.onError(auth.errors()); without a token the request is 401, with an AuthTesting token 201
    @Test
    fun `CON-AUTH-004 AC-5 the Kotlin minimal path`() {
        val t = TestApp.create()
        val app = t.app()
        val auth = Auth.builder(AuthTestSupport.SECRET)
            .mode(AuthMode.fromEnv())
            .user("admin", "an admin passphrase", "admin")
            .roles(RolePolicy.builder().role("writer", "posts:write").build())
            .permit("/health")
            .build()
        auth.install(app)
        app.get("/health") { _, res -> res.send("ok") }
        app.post("/posts", auth.require("posts:write")) { req, res ->
            res.status(201).send("created by " + auth.user(req).username)
        }
        app.onError(auth.errors())
        t.start()
        assertEquals(AuthMode.fromEnv(), auth.mode())
        assertEquals(200, t.send("GET", "/health").status())
        assertEquals(401, t.send("POST", "/posts").status())
        val token = AuthTesting.tokenFor(auth, "alice", "writer")
        val created = t.send("POST", "/posts", "Authorization", "Bearer $token")
        assertEquals(201, created.status())
        assertEquals("created by alice", created.text())
    }

    // CON-AUTH-004 AC-6: the value objects expose getX()/isX() accessors, so
    // Kotlin property syntax compiles here: user.id, user.isServiceAccount, key.expiresAt, event.type and the others
    @Test
    fun `CON-AUTH-004 AC-6 Kotlin property syntax on the value objects`() {
        val auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).apiKeys(InMemoryApiKeyStore()).build()
        val user: AuthUser = auth.userStore().create(
            AuthUser.builder().username("bot").serviceAccount(true).roles("admin").attribute("team", "ci").build())
        val id: String? = user.id
        assertTrue(user.isServiceAccount)
        assertTrue(user.isEnabled)
        assertEquals("bot", user.username)
        assertEquals(0L, user.tokenVersion)
        assertEquals(setOf("admin"), user.roles)
        assertEquals("ci", user.attributes["team"])
        assertNull(user.passwordHash)

        val issued = auth.apiKeys().issue(id!!, "ci", System.currentTimeMillis() + 86_400_000L, "posts:read")
        val key = issued.key
        val expiresAt: Long? = key.expiresAt
        assertTrue(expiresAt != null && expiresAt > key.createdAt)
        assertEquals(id, key.ownerUserId)
        assertEquals("ci", key.name)
        assertEquals(setOf("posts:read"), key.scopes)
        assertNull(key.lastUsedAt)
        assertTrue(issued.plainKey.startsWith("nk_" + key.prefix))

        val pair = auth.tokens().issue(auth.userStore().findById(id)!!)
        assertTrue(pair.accessToken.isNotEmpty() && pair.refreshToken.isNotEmpty())
        assertEquals("Bearer", pair.tokenType)
        assertTrue(pair.expiresIn > 0 && pair.refreshExpiresIn > pair.expiresIn)

        val event = AuditEvent.builder(AuditEvent.RECOVERY_USED, "POST /auth/login").actorId(id).build()
        assertEquals(AuditEvent.RECOVERY_USED, event.type)
        assertEquals(id, event.actorId)
        assertEquals("POST /auth/login", event.action)
        assertFalse(event.timestampMillis < 0)
    }
}
