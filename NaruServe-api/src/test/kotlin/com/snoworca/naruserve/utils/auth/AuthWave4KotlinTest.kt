package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.utils.auth.group.GroupStore
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys
import com.snoworca.naruserve.utils.auth.password.HashCost
import com.snoworca.naruserve.utils.auth.password.PasswordHasher
import com.snoworca.naruserve.utils.auth.password.PasswordHashers
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher
import com.snoworca.naruserve.utils.auth.rbac.RoleStore
import com.snoworca.naruserve.utils.auth.spi.ForwardingLoginThrottle
import com.snoworca.naruserve.utils.auth.spi.KeySource
import com.snoworca.naruserve.utils.auth.spi.LoginThrottle
import com.snoworca.naruserve.utils.auth.testing.AuthTesting
import com.snoworca.naruserve.utils.auth.user.AuthUser
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore
import com.snoworca.naruserve.utils.auth.user.UserStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wave-4 auth API from Kotlin: throttle, hashers, key sets, and the identity API (CON-IAM-001). */
class AuthWave4KotlinTest {

    /** A throttle with only the one-argument methods, written in Kotlin. */
    class KotlinThrottle : LoginThrottle {
        val calls = mutableListOf<String>()
        override fun retryAfter(key: String): Long = 3L.also { calls.add("retryAfter $key") }
        override fun recordFailure(key: String): Long = 5L.also { calls.add("recordFailure $key") }
        override fun reset(key: String) {
            calls.add("reset $key")
        }
    }

    // IR-FLOW-003 AC-1: a Kotlin throttle implementing only the one-argument methods compiles and the limit-argument
    // defaults return the one-argument results; usesLimitArguments() is false
    @Test
    fun `IR-FLOW-003 AC-1 Kotlin throttle with one-argument methods`() {
        val t = KotlinThrottle()
        assertEquals(t.retryAfter("k"), t.retryAfter("k", 100, 900_000L))
        assertEquals(t.recordFailure("k"), t.recordFailure("k", 100, 900_000L))
        assertFalse(t.usesLimitArguments())
        val decorated = object : ForwardingLoginThrottle(InMemoryLoginThrottle()) {
            override fun recordFailure(key: String, max: Int, windowMillis: Long): Long =
                super.recordFailure(key, max, windowMillis)
        }
        assertTrue(decorated.usesLimitArguments())
    }

    // The password and key API reads naturally from Kotlin: properties, SAM KeySource, no platform types
    @Test
    fun `hashers, cost and key sets from Kotlin`() {
        val hasher: PasswordHasher = Pbkdf2PasswordHasher(1000)
        val stored: String = hasher.hash("pw".toCharArray())
        assertTrue(hasher.verify("pw".toCharArray(), stored))
        assertEquals(1000, (hasher as Pbkdf2PasswordHasher).iterations)
        assertEquals(14, PasswordHashers.bcrypt().maxCost)
        val cost = HashCost.calibrate(250)
        assertEquals(250L, cost.targetMillis)
        assertFalse(cost.isFixed)
        val keys = JwtKeys.builder().signingKey("k2", ByteArray(32) { it.toByte() }).build()
        assertEquals("k2", keys.signingKid)
        val source = KeySource { ByteArray(32) { (it * 7).toByte() } }
        val kid: String? = source.signingKid()
        assertEquals(null, kid)
        assertNotNull(source.keys().verificationKey(source.keys().signingKid))
    }

    // CON-IAM-001 AC-1: AuthUser properties read in Kotlin without platform types (Kotlin accepts T! in a typed val
    // silently, so the absence of platform types is proven by the @NullMarked reflection check CON_IAM_001_AC4; this
    // test proves the property syntax and the declared types)
    @Test
    fun `CON-IAM-001 AC-1 AuthUser properties`() {
        val user = AuthUser.builder().id("u-1").username("alice").roles("editor").attribute("team", "a").build()
        val id: String? = user.id
        val name: String = user.username
        val enabled: Boolean = user.isEnabled
        val service: Boolean = user.isServiceAccount
        val roles: Set<String> = user.roles
        val attributes: Map<String, String> = user.attributes
        assertEquals("u-1", id)
        assertEquals("alice", name)
        assertTrue(enabled)
        assertFalse(service)
        assertEquals(setOf("editor"), roles)
        assertEquals(mapOf("team" to "a"), attributes)
    }

    // CON-IAM-001 AC-2: auth.users().update(id) { ... } and RoleStore { role -> ... } compile as SAM lambdas and run
    @Test
    fun `CON-IAM-001 AC-2 SAM lambdas`() {
        val roles = RoleStore { role -> if (role == "reader") setOf("posts:read") else setOf() }
        val store = InMemoryUserStore()
        val auth = AuthTestAccess.builder(KeySource { "k9Qv2Lm7Xr4Tz8Wb1Nc6Ys3Hd5Gf0Ja-Pq".toByteArray() })
            .users(store).roles(roles).build()
        val created = auth.users().create("alice", "pw-pw-pw-pw".toCharArray())
        val id: String = created.id!!
        val updated = auth.users().update(id) { it.toBuilder().attribute("k", "v").build() }
        assertEquals("v", updated?.getAttribute("k"))
        assertEquals("v", store.findById(id)?.getAttribute("k"))
        assertEquals(setOf("posts:read"), roles.scopesOf("reader"))
    }

    /** A Kotlin decorator of a user store; ForwardingUserStore forwards everything else. */
    class CountingStore(inner: UserStore) : ForwardingUserStore(inner) {
        var reads = 0
        override fun findByUsername(username: String): AuthUser? {
            reads++
            return super.findByUsername(username)
        }
    }

    // CON-IAM-001 AC-3: a Kotlin object GroupStore and a Kotlin class extending ForwardingUserStore pass their contract
    // checks at runtime
    @Test
    fun `CON-IAM-001 AC-3 Kotlin GroupStore and ForwardingUserStore`() {
        val groups = object : GroupStore {
            override fun groupsOfUser(userId: String): Set<String> = if (userId == "u") setOf("backend") else setOf()
            override fun parentsOf(groupIds: Set<String>): Set<String> = if ("backend" in groupIds) setOf("eng") else setOf()
        }
        assertEquals(setOf("backend", "eng"), groups.effectiveGroupsOfUser("u"))
        assertEquals(setOf<String>(), groups.effectiveGroupsOfUser("v"))

        val store = CountingStore(InMemoryUserStore())
        val row = store.create(AuthUser.builder().username("neo").passwordHash("h").build())
        val id: String = row.id!!
        assertEquals("neo", store.findByUsername("neo")?.username)
        assertEquals(1, store.reads)
        assertTrue(store.setEnabled(id, false))
        assertFalse(store.findById(id)!!.isEnabled)
        assertTrue(store.bumpTokenVersion(id))
        assertEquals(1L, store.findById(id)!!.tokenVersion)
        assertTrue(store.delete(id))
        assertEquals(null, store.findById(id))
        assertEquals(store.supportsAtomicFieldWrites(), InMemoryUserStore().supportsAtomicFieldWrites())
    }

    // CON-IAM-001 AC-3: the Kotlin class extending ForwardingUserStore passes the store contract check itself
    @Test
    fun `CON-IAM-001 AC-3 Kotlin ForwardingUserStore passes verifyUserStore`() {
        val store = CountingStore(InMemoryUserStore())
        AuthTesting.verifyUserStore(store)
        assertTrue(store.reads > 0)
    }
}
