package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.utils.auth.apikey.ApiKey
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStatus
import com.snoworca.naruserve.utils.auth.apikey.AuthApiKeys
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher
import com.snoworca.naruserve.utils.auth.spi.Authenticator
import com.snoworca.naruserve.utils.auth.spi.BreachedPasswordChecker
import com.snoworca.naruserve.utils.auth.testing.AuthTesting
import com.snoworca.naruserve.utils.auth.user.AuthUser
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin compile-and-run checks of the wave 8 public API (CLAUDE.md T11). */
class AuthWave8KotlinTest {
    private val secret = AuthTestSupport.SECRET

    // IR-APIKEY-002 AC-1: the facade from Kotlin: a nullable expiresAt, varargs scopes, non-platform results
    // FR-APIKEY-010 AC-1: AuthTesting.apiKeyFor from Kotlin with varargs scopes
    @Test
    fun `IR-APIKEY-002 AC-1 the key facade reads as Kotlin`() {
        val store = InMemoryUserStore()
        val bot = store.create(AuthUser.builder().username("bot").roles("reader").build())
        val auth = Auth.builder(secret).hashIterations(1000).users(store).apiKeys(InMemoryApiKeyStore())
            .roles(com.snoworca.naruserve.utils.auth.rbac.RolePolicy.builder().role("reader", "posts:read").build())
            .build()
        val keys: AuthApiKeys = auth.apiKeys()
        val issued: IssuedApiKey = keys.issue(bot.id!!, "ci-bot", null, "posts:read")
        val expires: Long? = issued.key.expiresAt
        assertNull(expires)
        val plain: String = issued.plainKey
        assertTrue(plain.startsWith("nk_"))
        val listed: List<ApiKey> = keys.list(bot.id!!)
        assertEquals(ApiKeyStatus.ACTIVE, listed.single().status)
        val testKey: String = AuthTesting.apiKeyFor(auth, bot.id!!, "posts:read")
        assertTrue(testKey.startsWith("nk_"))
        assertTrue(keys.revoke(listed.single().id))
        assertEquals(false, keys.revoke("k-404"))
    }

    // IR-TOKEN-001 AC-1: a Kotlin lambda replaces the built-in password authenticator through the builder
    // FR-TOKEN-004 AC-7: a Kotlin lambda is a BreachedPasswordChecker
    @Test
    fun `IR-TOKEN-001 AC-1 Kotlin lambdas for the password seams`() {
        val store = InMemoryUserStore()
        val alice = store.create(AuthUser.builder().username("alice").build())
        val authenticator = Authenticator { name, password, _ ->
            if (name == "alice" && String(password) == "from-directory") alice else null
        }
        val breached = BreachedPasswordChecker { pw -> String(pw) == "Password123!" }
        val policy: PasswordPolicy = PasswordPolicy.length(10, 64)
        assertEquals(10, policy.minLength)
        val adapter = InMemoryHttpAdapter("127.0.0.1", true)
        val app = RestApp.builder().httpAdapter(adapter).jsonCodec(AuthTestAccess.json()).build()
        val auth = Auth.builder(secret).hashIterations(1000).users(store).authenticator(authenticator)
            .breachedPasswordChecker(breached).passwordPolicy(policy).register(true).build()
        auth.install(app)
        app.listen(0)
        try {
            val ok = adapter.send(ClientRequest.json("POST", "/auth/login",
                "{\"username\":\"alice\",\"password\":\"from-directory\"}"))
            assertEquals(200, ok.status())
            val bad = adapter.send(ClientRequest.json("POST", "/auth/register",
                "{\"username\":\"bob\",\"password\":\"Password123!\"}"))
            assertEquals(422, bad.status())
            assertNull(store.findByUsername("bob"))
            assertTrue(Pbkdf2PasswordHasher(1000).hash("x".toCharArray()).isNotEmpty())
        } finally {
            app.shutdown(5, TimeUnit.SECONDS)
        }
    }

    private fun app(): Pair<RestApp, InMemoryHttpAdapter> {
        val adapter = InMemoryHttpAdapter("127.0.0.1", true)
        return RestApp.builder().httpAdapter(adapter).jsonCodec(AuthTestAccess.json()).build() to adapter
    }

    @Test
    fun `docs account endpoints`() {
        // docs-example: auth.md#43
        // adapted: a fixed test secret, cheap hashing and a logout through the in-memory adapter
        val (app, adapter) = app()
        val auth = Auth.builder(secret).hashIterations(1000).register(true).build()
        auth.install(app)
        app.listen(0)
        try {
            assertEquals(401, adapter.send(ClientRequest.of("POST", "/auth/logout?all=true")).status())
        } finally {
            app.shutdown(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `docs trusted origins`() {
        // docs-example: auth.md#44
        // adapted: a fixed test secret
        val auth = Auth.builder(secret).transport(TokenTransport.BOTH)
            .trustedOrigins("https://spa.example").build()
        assertEquals(setOf("https://spa.example"), auth.trustedOrigins())
    }

    @Test
    fun `docs api key authentication`() {
        // docs-example: auth.md#45
        // adapted: a fixed test secret, a stored owner and one key request through the in-memory adapter
        val (app, adapter) = app()
        val store = InMemoryUserStore()
        val bot = store.create(AuthUser.builder().username("bot").roles("reporter").build())
        val auth = Auth.builder(secret).hashIterations(1000).users(store).apiKeys(InMemoryApiKeyStore())
            .roles(com.snoworca.naruserve.utils.auth.rbac.RolePolicy.builder().role("reporter", "reports:read").build())
            .build()
        auth.install(app)
        app.get("/reports", auth.require("reports:read")) { req, res -> res.send(auth.user(req).id) }
        app.listen(0)
        try {
            val key = AuthTesting.apiKeyFor(auth, bot.id!!, "reports:read")
            val r = adapter.send(ClientRequest.of("GET", "/reports").header("Authorization", "ApiKey $key"))
            assertEquals(bot.id, r.text())
        } finally {
            app.shutdown(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `docs issuing api keys`() {
        // docs-example: auth.md#46
        // adapted: a fixed test secret and a service account as the owner
        val auth = Auth.builder(secret).hashIterations(1000).apiKeys(InMemoryApiKeyStore()).build()
        val botId = auth.users().create("ci-bot", null, { u -> u.toBuilder().serviceAccount(true).build() }).id!!
        val issued = auth.apiKeys().issue(botId, "ci-bot", null, "posts:read")
        val expires: Long? = issued.key.expiresAt
        assertNull(expires)
        assertTrue(auth.apiKeys().revoke(issued.key.id))
    }

    @Test
    fun `docs breached password checker`() {
        // docs-example: auth.md#47
        // adapted: a fixed test secret and a two-entry list
        val commonPasswords = setOf("Password123!", "qwertyuiop12")
        val auth = Auth.builder(secret).hashIterations(1000)
            .breachedPasswordChecker { pw -> String(pw) in commonPasswords }
            .build()
        try {
            auth.users().create("neo", "Password123!".toCharArray())
            throw AssertionError("a breached password was set")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("breached"))
        }
    }

    @Test
    fun `docs custom authenticator`() {
        // docs-example: auth.md#48
        // adapted: a fixed test secret and a map standing in for the directory
        val store = InMemoryUserStore()
        store.create(AuthUser.builder().username("alice").build())
        val directory = mapOf("alice" to "from-directory")
        val auth = Auth.builder(secret).hashIterations(1000)
            .users(store)
            .authenticator { name, password, _ -> if (String(password) == directory[name]) store.findByUsername(name) else null }
            .build()
        val (app, adapter) = app()
        auth.install(app)
        app.listen(0)
        try {
            assertEquals(200, adapter.send(ClientRequest.json("POST", "/auth/login",
                "{\"username\":\"alice\",\"password\":\"from-directory\"}")).status())
        } finally {
            app.shutdown(5, TimeUnit.SECONDS)
        }
    }
}
