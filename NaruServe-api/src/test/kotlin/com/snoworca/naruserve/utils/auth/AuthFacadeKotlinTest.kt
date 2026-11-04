package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.testing.RecordingLogger
import com.snoworca.naruserve.testing.TestApp
import com.snoworca.naruserve.utils.TimeSource
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy
import com.snoworca.naruserve.utils.auth.spi.AuthResponses
import com.snoworca.naruserve.utils.auth.user.AuthUser
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore
import com.snoworca.naruserve.utils.auth.user.UserPage
import com.snoworca.naruserve.utils.auth.user.UserStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The facade as Kotlin uses it: SAM lambdas, trailing-lambda routes and a Kotlin UserStore (CLAUDE.md T11). */
class AuthFacadeKotlinTest {

    private fun builder(store: UserStore): Auth.Builder =
        AuthTestSupport.builder(AuthTestSupport.TestCredentials(), RecordingLogger()).users(store)

    // IR-AUTH-001 AC-1: TimeSource is a SAM Kotlin builds with a lambda
    @Test
    fun `IR-AUTH-001 AC-1 TimeSource lambda`() {
        val t = TimeSource { 1_000L }
        assertEquals(1000L, t.nowMillis())
        assertTrue(Auth.builder(AuthTestSupport.SECRET).timeSource(t).build().toString().startsWith("Auth{"))
    }

    // IR-AUTH-004 AC-3: app.post(path, auth.require(...)) { req, res -> } compiles as a Kotlin trailing lambda
    @Test
    fun `IR-AUTH-004 AC-3 require with a trailing lambda`() {
        val store = InMemoryUserStore()
        val editor = store.create(AuthUser.builder().username("ed").roles("editor").build())
        val viewer = store.create(AuthUser.builder().username("vi").roles("viewer").build())
        val auth = builder(store).roles(RolePolicy.builder().role("editor", "posts:read", "posts:write")
            .role("viewer", "posts:read").build()).build()
        val t = TestApp.create()
        auth.install(t.app())
        t.app().post("/posts", auth.require("posts:write")) { req, res ->
            res.status(201).send(auth.user(req).id)
        }
        val ok = t.send("POST", "/posts", AuthTestSupport.HEADER, "${editor.id}:0")
        assertEquals(201, ok.status())
        assertEquals(editor.id, ok.text())
        assertEquals(403, t.send("POST", "/posts", AuthTestSupport.HEADER, "${viewer.id}:0").status())
    }

    // IR-AUTH-004 AC-8: auth.check { p, req -> ... } compiles as a Kotlin SAM lambda
    @Test
    fun `IR-AUTH-004 AC-8 check with a Kotlin lambda`() {
        val store = InMemoryUserStore()
        val alice = store.create(AuthUser.builder().username("alice").roles("admin").build())
        val auth = builder(store).build()
        val t = TestApp.create()
        auth.install(t.app())
        val seenKeyIds = java.util.ArrayList<String?>()
        t.app().get("/x", auth.check { p, req ->
            // typed probe on the real principal: apiKeyId is a nullable Kotlin property (String?, not String!)
            val k: String? = p.apiKeyId
            seenKeyIds.add(k)
            p.hasScope("posts:read") && req.path() == "/x"
        }) { _, res ->
            res.send("allowed")
        }
        assertEquals("allowed", t.send("GET", "/x", AuthTestSupport.HEADER, "${alice.id}:0").text())
        assertEquals("allowed", t.send("GET", "/x", AuthTestSupport.HEADER, "${alice.id}:0:k1:posts:read").text())
        // a user credential carries no key id; a key credential carries its id
        assertEquals(listOf<String?>(null, "k1"), seenKeyIds)
    }

    // FR-AUTH-006 AC-6: a Kotlin AuthResponses lambda reshapes the body; the status stays
    @Test
    fun `FR-AUTH-006 AC-6 Kotlin AuthResponses`() {
        val responses = AuthResponses { _, res, _, code, _ -> res.type("application/json").send("{\"err\":\"$code\"}") }
        val t = TestApp.create()
        builder(InMemoryUserStore()).responses(responses).build().install(t.app())
        val r = t.send("GET", "/x")
        assertEquals(401, r.status())
        assertEquals("{\"err\":\"unauthenticated\"}", r.text())
        val obj = object : AuthResponses {
            override fun error(req: com.snoworca.naruserve.Request, res: com.snoworca.naruserve.Response,
                status: Int, code: String, message: String) {
                res.type("application/json").send("{\"e\":$status}")
            }
        }
        val t2 = TestApp.create()
        builder(InMemoryUserStore()).responses(obj).build().install(t2.app())
        assertEquals("{\"e\":401}", t2.send("GET", "/y").text())
    }

    /** IR-IAM-001 AC-1: only the five required methods, in Kotlin. */
    class KotlinStore : UserStore {
        private val rows = linkedMapOf<String, AuthUser>()
        override fun findByUsername(username: String): AuthUser? = rows.values.firstOrNull { it.username == username }
        override fun findById(id: String): AuthUser? = rows[id]
        override fun create(user: AuthUser): AuthUser {
            val stored = user.toBuilder().id((rows.size + 1).toString()).build()
            rows[stored.id!!] = stored
            return stored
        }
        override fun update(user: AuthUser): AuthUser? = if (rows.containsKey(user.id)) user.also { rows[it.id!!] = it } else null
        override fun delete(id: String): Boolean = rows.remove(id) != null
        override fun list(offset: Int, limit: Int, search: String?): UserPage =
            UserPage(rows.values.drop(offset).take(limit), rows.size.toLong(), offset, limit)
    }

    /** Exactly the five required methods. */
    class FiveOnly : UserStore {
        override fun findByUsername(username: String): AuthUser? = null
        override fun findById(id: String): AuthUser? = null
        override fun create(user: AuthUser): AuthUser = user.toBuilder().id("1").build()
        override fun update(user: AuthUser): AuthUser? = null
        override fun delete(id: String): Boolean = false
    }

    // IR-IAM-001 AC-1, AC-2: a Kotlin store compiles; list(...) reads into a non-null UserPage; defaults work
    @Test
    fun `IR-IAM-001 AC-1 AC-2 Kotlin store and UserPage`() {
        val five: UserStore = FiveOnly()
        assertEquals("1", five.create(AuthUser.builder().username("x").build()).id)
        assertEquals(false, five.setEnabled("1", true))
        val s = KotlinStore()
        val a = s.create(AuthUser.builder().username("a").build())
        s.create(AuthUser.builder().username("b").build())
        val page: UserPage = s.list(0, 1, null)
        val items: List<AuthUser> = page.items
        assertEquals(1, items.size)
        assertEquals(2L, page.total)
        assertTrue(s.setEnabled(a.id!!, false))
        assertEquals(false, s.findById(a.id!!)!!.isEnabled)
        assertEquals(false, s.supportsAtomicFieldWrites())
    }
}
