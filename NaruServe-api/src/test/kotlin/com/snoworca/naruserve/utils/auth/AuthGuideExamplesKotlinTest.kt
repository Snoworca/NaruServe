package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.testing.TestApp
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy
import com.snoworca.naruserve.utils.auth.testing.AuthTesting
import com.snoworca.naruserve.utils.auth.user.AuthUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The Kotlin blocks the task-oriented auth guide added (OPS-AUTH-007), compiled and run (FR-KT-004 markers). */
class AuthGuideExamplesKotlinTest {

    // OPS-AUTH-007 AC-2: the AuthEvents example keeps an app table in sync on create, update, delete and login
    @Test
    fun `OPS-AUTH-007 AC-2 events keep an app table in sync`() {
        // docs-example: auth.md#10
        // adapted: a fixed test secret instead of JWT_SECRET and cheap hashing; an in-memory app drives the login
        val profiles = ConcurrentHashMap<String, String>()
        val lastLogin = ConcurrentHashMap<String, Long>()
        val sync = object : AuthEvents {
            override fun onUserCreated(user: AuthUser, ctx: AuthEventContext) {
                profiles[user.id!!] = user.username
            }

            override fun onUserUpdated(before: AuthUser, after: AuthUser, changes: Set<UserChange>, ctx: AuthEventContext) {
                if (UserChange.USERNAME in changes) profiles[after.id!!] = after.username
            }

            override fun onUserDeleted(user: AuthUser, ctx: AuthEventContext) {
                profiles.remove(user.id!!)
                lastLogin.remove(user.id!!)
            }

            override fun onLogin(user: AuthUser, ctx: AuthEventContext) {
                lastLogin[user.id!!] = ctx.timestamp
            }
        }
        val auth = AuthTestAccess.fastBuilder(AuthTestSupport.SECRET).events(sync).build()

        val adapter = InMemoryHttpAdapter("127.0.0.1", true)
        val app = RestApp.builder().httpAdapter(adapter).jsonCodec(AuthTestAccess.json()).build()
        app.use(auth)
        app.listen(0)
        try {
            val ann = auth.users().create("ann", "ann-password-1".toCharArray())
            val id = ann.id!!
            assertEquals("ann", profiles[id])
            auth.users().rename(id, "annie")
            assertEquals("annie", profiles[id])
            val login = adapter.send(ClientRequest.json("POST", "/auth/login",
                "{\"username\":\"annie\",\"password\":\"ann-password-1\"}"))
            assertEquals(login.text(), 200, login.status())
            // onLogin runs on the login's completion path, just after the answer is handed over
            val deadline = System.currentTimeMillis() + 5_000
            while (!lastLogin.containsKey(id) && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(lastLogin.containsKey(id))
            assertTrue(auth.users().delete(id))
            assertFalse(profiles.containsKey(id))
            assertFalse(lastLogin.containsKey(id))
        } finally {
            app.shutdown(5, TimeUnit.SECONDS)
        }
    }

    // OPS-AUTH-007 AC-4: the glossary example shows principal, role, grant and scope in code
    @Test
    fun `OPS-AUTH-007 AC-4 glossary terms in code`() {
        // docs-example: auth.md#51
        // adapted: a fixed test secret instead of JWT_SECRET; a TestApp and an AuthTesting token drive the route
        val auth = Auth.builder(AuthTestSupport.SECRET)
            .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
            .build()
        val t = TestApp.create()
        val app = t.app()
        app.use(auth)
        app.get("/me") { req, res ->
            val p = auth.user(req)
            res.send(p.username + " " + p.hasRole("editor") + " " + p.hasScope("posts:write"))
        }
        val token = AuthTesting.tokenFor(auth, "alice", "editor")
        val me = t.send("GET", "/me", "Authorization", "Bearer $token")
        assertEquals(me.text(), 200, me.status())
        assertEquals("alice true true", me.text())
    }
}
