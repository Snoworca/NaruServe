package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.utils.auth.internal.Hs256TokenCodec
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher
import com.snoworca.naruserve.utils.auth.spi.DeviceToken
import com.snoworca.naruserve.utils.auth.spi.DeviceTrust
import com.snoworca.naruserve.utils.auth.spi.TokenCodec
import com.snoworca.naruserve.utils.auth.testing.AuthTesting
import com.snoworca.naruserve.utils.auth.user.AuthUser
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Kotlin compile-and-run checks of the wave 7 public API (CLAUDE.md T11). */
class AuthWave7KotlinTest {
    private val secret = AuthTestSupport.SECRET

    /** A DeviceTrust written in Kotlin. */
    class KotlinTrust : DeviceTrust {
        val calls = mutableListOf<String>()
        override fun issued(token: DeviceToken) {
            calls += "issued"
        }
        override fun isInvalidated(token: DeviceToken): Boolean {
            calls += "isInvalidated"
            return false
        }
        override fun recordFailure(token: DeviceToken, max: Int, windowMillis: Long): Boolean {
            calls += "recordFailure"
            return false
        }
    }

    private fun attempt(auth: Auth, name: String, pw: String, device: String?): PasswordLogin.Outcome {
        val out = AtomicReference<PasswordLogin.Outcome>()
        val done = CountDownLatch(1)
        auth.passwordLogin().attempt(name, pw.toCharArray(), "10.0.0.1", device) { out.set(it); done.countDown() }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        return out.get()
    }

    // SEC-FLOW-013 AC-9 (Kotlin half): a DeviceTrust implemented in Kotlin and passed to the builder compiles and is
    // the one a login calls
    @Test
    fun `SEC_FLOW_013_AC9 Kotlin DeviceTrust on the builder`() {
        val store = InMemoryUserStore()
        store.create(AuthUser.builder().username("al").passwordHash(Pbkdf2PasswordHasher(1000).hash("pw-pw".toCharArray())).build())
        val trust = KotlinTrust()
        val auth = Auth.builder(secret).hashIterations(1000).users(store).deviceTrust(trust).build()
        assertSame(trust, auth.deviceTrust())
        val ok = attempt(auth, "al", "pw-pw", null)
        val device = ok.deviceToken!!.token()
        attempt(auth, "al", "wrong", device)
        assertEquals(listOf("issued", "isInvalidated", "recordFailure"), trust.calls)
    }

    @Test
    fun `TokenCodec, reuse grace and AuthTesting from Kotlin`() {
        val keys = JwtKeys.of(secret.toByteArray())
        val inner = Hs256TokenCodec(keys) { System.currentTimeMillis() }
        val codec = object : TokenCodec {
            override fun encode(claims: JwtClaims): String = inner.encode(claims)
            override fun decode(token: String, expectedType: String): JwtClaims = inner.decode(token, expectedType)
        }
        val store = InMemoryUserStore()
        val auth = Auth.builder(secret).hashIterations(1000).users(store).tokenCodec(codec).reuseGrace(0).build()
        val token: String = AuthTesting.tokenFor(auth, "kim")
        val csrf: String = AuthTesting.csrfTokenFor(auth, token)
        assertTrue(csrf.contains("."))
        val id = store.findByUsername("kim")!!.id!!
        assertEquals(id, auth.tokens().verifyAccess(AuthTesting.tokenForUserId(auth, id)).subject)
        AuthTesting.verifyUserStore(InMemoryUserStore())
    }

    @Test
    fun `docs auth refresh reuse event`() {
        // docs-example: auth.md#36
        // adapted: a fixed test secret instead of JWT_SECRET; the listener collects instead of printing
        val seen = mutableListOf<String>()
        val auth = Auth.builder(secret).hashIterations(1000)
            .reuseGrace(0)
            .events(object : AuthEvents {
                override fun onRefreshTokenReused(userId: String, familyId: String, ctx: AuthEventContext) {
                    seen += "$userId/$familyId"
                }
            })
            .build()
        val user = (auth.userStore() as InMemoryUserStore).create(AuthUser.builder().username("re").passwordHash("x").build())
        val pair = auth.tokens().issue(user)
        auth.tokens().refresh(pair.refreshToken)
        try {
            auth.tokens().refresh(pair.refreshToken)
            fail("a reused refresh token was accepted")
        } catch (expected: AuthRejection) {
        }
        assertEquals(1, seen.size)
    }

    @Test
    fun `docs auth csrf guarded route`() {
        // docs-example: auth.md#37
        // adapted: a TestApp over a TLS in-memory adapter, COOKIE transport as the Java example, a policy declaring
        // posts:write, and two cookie-authenticated POSTs checking the guard
        val auth = Auth.builder(secret).hashIterations(1000).transport(TokenTransport.COOKIE)
            .roles(com.snoworca.naruserve.utils.auth.rbac.RolePolicy.builder().role("editor", "posts:write").build()).build()
        val t = com.snoworca.naruserve.testing.TestApp.create(
            com.snoworca.naruserve.testing.InMemoryHttpAdapter("127.0.0.1", true))
        val app = t.app()
        try {
            auth.install(app)
            app.post("/api/posts", auth.require("posts:write")) { _, rs -> rs.status(201).send("created") }
            val token = AuthTesting.tokenFor(auth, "alice", "editor")
            val csrf = AuthTesting.csrfTokenFor(auth, token)
            // a cookie-authenticated write without X-CSRF-Token is refused
            val refused = t.send("POST", "/api/posts", "Host", "app.test", "Origin", "https://app.test",
                "Cookie", "naru_at=$token; naru_csrf=$csrf")
            assertEquals(refused.text(), 403, refused.status())
            assertEquals("csrf_failed", AuthTestSupport.code(refused))
            // the same write with the matching X-CSRF-Token passes the guard
            val created = t.send("POST", "/api/posts", "Host", "app.test", "Origin", "https://app.test",
                "Cookie", "naru_at=$token; naru_csrf=$csrf", "X-CSRF-Token", csrf)
            assertEquals(created.text(), 201, created.status())
            assertEquals("created", created.text())
        } finally {
            app.shutdown(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `docs auth logging device trust`() {
        // docs-example: auth.md#38
        // adapted: a fixed test secret instead of JWT_SECRET
        class LoggingTrust : DeviceTrust {
            override fun isInvalidated(token: DeviceToken) = false
            override fun recordFailure(token: DeviceToken, max: Int, windowMillis: Long): Boolean {
                println("device ${token.tokenId} failed")
                return false
            }
        }
        val auth = Auth.builder(secret).deviceTrust(LoggingTrust()).build()
        assertTrue(auth.deviceTrust() is LoggingTrust)
    }

    @Test
    fun `docs auth cors before install`() {
        // docs-example: auth.md#39
        // adapted: a TestApp instead of RestApp.create(), a fixed test secret, and a preflight checking that the CORS
        // middleware registered before install still answers
        val t = com.snoworca.naruserve.testing.TestApp.create()
        val web = t.app()
        try {
            web.cors { c ->
                c.allowedOrigins("https://spa.example", "http://localhost")
                    .allowedHeaders("Authorization", "Content-Type", "X-CSRF-Token", "Naru-Device")
                    .credentials(true)
            }
            Auth.builder(secret).transport(TokenTransport.BOTH).build().install(web)
            val preflight = t.send("OPTIONS", "/auth/login", "Origin", "https://spa.example",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "X-CSRF-Token")
            assertEquals("https://spa.example", preflight.header("Access-Control-Allow-Origin"))
            assertEquals("true", preflight.header("Access-Control-Allow-Credentials"))
        } finally {
            web.shutdown(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `docs auth testing kit`() {
        // docs-example: auth.md#40
        // adapted: InMemoryUserStore stands in for MyUserStore(ds), and the bearer token is checked here
        AuthTesting.verifyUserStore(InMemoryUserStore())
        val auth = Auth.builder(secret).hashIterations(1000)
            .roles(com.snoworca.naruserve.utils.auth.rbac.RolePolicy.builder().role("editor", "posts:write").build()).build()
        val token = AuthTesting.tokenFor(auth, "alice", "editor")
        assertTrue(token.isNotEmpty())
    }
}
