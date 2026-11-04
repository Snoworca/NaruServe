package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStatus
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore
import com.snoworca.naruserve.utils.auth.user.AuthUser
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kotlin compile-and-run checks of the wave 9 public API (CLAUDE.md T11). */
class AuthWave9KotlinTest {

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    private fun label(status: ApiKeyStatus): String = when (status) {
        ApiKeyStatus.ACTIVE -> "active"
        ApiKeyStatus.EXPIRED -> "expired"
        ApiKeyStatus.STALE -> "stale"
        else -> "unknown" // values may be added in later versions
    }

    // IR-APIKEY-003 AC-6: a Kotlin when over ApiKeyStatus keeps an else branch for values added later
    @Test
    fun `IR-APIKEY-003 AC-6 a when over the key status has an else branch`() {
        val store = InMemoryUserStore()
        val bot = store.create(AuthUser.builder().username("bot").roles("reader").build())
        val auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(store)
            .apiKeys(InMemoryApiKeyStore())
            .roles(com.snoworca.naruserve.utils.auth.rbac.RolePolicy.builder().role("reader", "posts:read").build())
            .build()
        auth.apiKeys().issue(bot.id!!, "ci", null, "posts:read")
        val statuses: List<String> = auth.apiKeys().list(bot.id!!).map { label(it.status) }
        assertEquals(listOf("active"), statuses)
        assertEquals("stale", label(ApiKeyStatus.STALE))
    }
}
