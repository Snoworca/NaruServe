package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher
import java.util.concurrent.TimeUnit
import com.snoworca.naruserve.utils.auth.user.AuthUser
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Kotlin compile-and-run checks of the wave 10 public API (CLAUDE.md T11). */
class AuthWave10KotlinTest {

    // FR-FLOW-006 AC-2: .allowedAttributes takes Kotlin varargs and returns the builder; the profile changes through
    // auth.users().update keep working from Kotlin
    @Test
    fun `FR-FLOW-006 AC-2 allowedAttributes from Kotlin`() {
        val store = InMemoryUserStore()
        val bob = store.create(AuthUser.builder().username("bob").build())
        val builder: Auth.Builder = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(store)
            .apiKeys(InMemoryApiKeyStore())
            .allowedAttributes("displayName", "locale")
        val auth = builder.build()
        assertEquals(setOf("displayName", "locale"), auth.allowedAttributes())
        val updated = auth.users().update(bob.id!!) { it.toBuilder().attribute("displayName", "Bo").build() }
        assertEquals("Bo", updated?.attributes?.get("displayName"))
        try {
            Auth.builder(AuthTestSupport.SECRET).allowedAttributes("roles")
            fail("a security field name")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("roles"))
        }
    }

    // FR-APIKEY-004 AC-5: the docs' Kotlin example of the admin issuance runs: the admin issues a key for a service
    // account with its own password and is recorded as the issuer
    @Test
    fun `docs admin key issuance`() {
        // docs-example: auth.md#49
        // adapted: a fixed test secret, cheap hashing and the fetch call sent through the in-memory adapter
        val store = InMemoryUserStore()
        val pw = "an admin passphrase"
        val admin = store.create(AuthUser.builder().username("root").roles("admin")
            .passwordHash(Pbkdf2PasswordHasher(1000).hash(pw.toCharArray())).build())
        val adapter = InMemoryHttpAdapter("127.0.0.1", true)
        val app = RestApp.builder().httpAdapter(adapter).jsonCodec(AuthTestAccess.json()).build()
        val auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(store).apiKeys(InMemoryApiKeyStore()).build()
        auth.install(app)
        app.listen(0)
        try {
            val botId = store.create(AuthUser.builder().username("ci-bot").serviceAccount(true).build()).id!!
            val adminToken = auth.tokens().issue(store.findById(admin.id!!)!!).accessToken
            val r = adapter.send(ClientRequest.json("POST", "/auth/admin/apikeys",
                "{\"ownerUserId\":\"$botId\",\"name\":\"ci-bot\",\"scopes\":[\"posts:read\"],\"password\":\"$pw\"}")
                .header("Authorization", "Bearer $adminToken"))
            assertEquals(r.text(), 201, r.status())
            assertEquals(admin.id, auth.apiKeys().list(botId).single().issuedByUserId)
        } finally {
            app.shutdown(5, TimeUnit.SECONDS)
        }
    }
}
