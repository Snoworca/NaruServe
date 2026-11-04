package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.TestApp
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher
import com.snoworca.naruserve.utils.auth.user.AuthUser
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin compile-and-run checks of the wave 6 public API (CLAUDE.md T11). */
class AuthWave6KotlinTest {
    private val secret = AuthTestSupport.SECRET

    // SEC-IAM-001 AC-1: a Kotlin trailing lambda is a UserMutator: auth.users().update(id) { it.toBuilder()... }
    @Test
    fun `SEC_IAM_001_AC1 mutator lambda from Kotlin`() {
        val store = InMemoryUserStore()
        val auth = Auth.builder(secret).users(store).build()
        val id = store.create(AuthUser.builder().username("al").passwordHash("x").roles("reader").build()).id!!
        val updated = auth.users().update(id) { it.toBuilder().attribute("nickname", "n").build() }
        assertEquals("n", updated!!.getAttribute("nickname"))
        assertEquals(setOf("reader"), store.findById(id)!!.roles)
    }

    @Test
    fun `token service, lockout and websocket wrapper from Kotlin`() {
        val store = InMemoryUserStore()
        val user = store.create(AuthUser.builder().username("bo").passwordHash("x").build())
        store.create(AuthUser.builder().username("lo")
            .passwordHash(Pbkdf2PasswordHasher(1000).hash("pw-pw".toCharArray())).build())
        val auth = Auth.builder(secret).hashIterations(1000).users(store).lockout(Lockout.after(3).forMinutes(1))
            .accessTtl(60_000).refreshTtl(120_000).userCacheTtl(0).build()
        val first: TokenPair = auth.tokens().issue(user)
        val next: TokenPair = auth.tokens().refresh(first.refreshToken)
        assertEquals(60L, next.expiresIn)
        assertEquals(120L, next.refreshExpiresIn)
        assertNotEquals(first.accessToken, next.accessToken)
        assertEquals(user.id, auth.tokens().verifyAccess(next.accessToken).subject)
        // the lockout: the right password logs in; after three failures the same right password is refused
        assertEquals(PasswordLogin.Kind.SUCCESS, attempt(auth, "lo", "pw-pw").kind)
        repeat(3) { assertEquals(PasswordLogin.Kind.INVALID_CREDENTIALS, attempt(auth, "lo", "wrong").kind) }
        assertEquals(PasswordLogin.Kind.INVALID_CREDENTIALS, attempt(auth, "lo", "pw-pw").kind)
        val adapter = InMemoryHttpAdapter()
        val t = TestApp.create(adapter)
        val app = t.app()
        try {
            auth.install(app)
            app.ws("/ws", auth.webSocket { req, ws -> ws.sendText(auth.user(req).id) })
            assertTrue(auth.describe().contains("auth"))
            t.start()
            // the websocket wrapper: an upgrade without a credential gets 401 and no session
            val anon = adapter.send(InMemoryHttpAdapter.ClientRequest.upgrade("/ws"))
            assertEquals(401, anon.status())
            assertFalse(anon.upgradeHandedOver())
            // with the access token the wrapped handler runs and sees the principal
            val handshake = adapter.submit(InMemoryHttpAdapter.ClientRequest.upgrade("/ws",
                "Authorization", "Bearer " + next.accessToken))
            val ch = adapter.nextChannel(10, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (ch.sent().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            assertEquals(listOf<Any>(user.id!!), ch.sent())
            ch.clientClose(1000, "")
            assertEquals(101, handshake.get(10, TimeUnit.SECONDS).status())
        } finally {
            app.shutdown(5, TimeUnit.SECONDS)
        }
    }

    private fun attempt(auth: Auth, name: String, pw: String): PasswordLogin.Outcome {
        val out = AtomicReference<PasswordLogin.Outcome>()
        val done = CountDownLatch(1)
        auth.passwordLogin().attempt(name, pw.toCharArray(), "10.0.0.1", null) { out.set(it); done.countDown() }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        return out.get()
    }
}
