package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher
import com.snoworca.naruserve.utils.auth.user.AuthUser
import com.snoworca.naruserve.utils.auth.user.FileUserStore
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore
import com.snoworca.naruserve.utils.auth.user.UserStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** A Kotlin data class decorator: its generated hashCode reads its own properties (N3-01). */
data class N301TenantStore(val tenant: String, val inner: UserStore) : ForwardingUserStore(inner)

/** The AuthFileKey API from Kotlin (CLAUDE.md T11): nullable fromEnv, the keyed FileUserStore and key file. */
class AuthFileKeyKotlinTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // FR-IAM-015 AC-9: val key: AuthFileKey? = AuthFileKey.fromEnv() compiles with no platform type; a FileUserStore
    // over AuthFileKey.of(ByteArray(32) { it.toByte() }) and KeySources.fromFile(keyFile, key) go to
    // Auth.builder(...).users(...).build(), and a password login through the in-memory adapter succeeds
    @Test
    fun `FR-IAM-015 AC-9 file keys from Kotlin`() {
        // a malformed NARU_AUTH_FILE_KEY on the test machine throws; the test then uses its fixed key
        val key: AuthFileKey? = try { AuthFileKey.fromEnv() } catch (e: IllegalArgumentException) { null }
        val fileKey: AuthFileKey = AuthFileKey.of(ByteArray(32) { it.toByte() })
        val dataDir = tmp.newFolder()
        val keyFile = File(dataDir, "naru-auth.key")
        val store = FileUserStore(File(dataDir, "users.json"), fileKey)
        store.create(AuthUser.builder().username("alice")
            .passwordHash(Pbkdf2PasswordHasher(1000).hash("correct horse battery".toCharArray())).build())
        val adapter = InMemoryHttpAdapter("127.0.0.1", true)
        val app = RestApp.builder().httpAdapter(adapter).jsonCodec(AuthTestAccess.json()).build()
        val nullable: com.snoworca.naruserve.utils.auth.spi.KeySource = KeySources.fromFile(keyFile, key)
        assertTrue(nullable.toString().contains("naru-auth.key"))
        val auth = Auth.builder(KeySources.fromFile(keyFile, key ?: fileKey)).hashIterations(1000).users(store).build()
        auth.install(app)
        app.listen(0)
        try {
            val r = adapter.send(ClientRequest.json("POST", "/auth/login",
                "{\"username\":\"alice\",\"password\":\"correct horse battery\"}"))
            assertEquals(r.text(), 200, r.status())
            assertTrue(File(dataDir, "users.json").readText().contains("\"passwordHash\":\"enc("))
            assertTrue(keyFile.readText().startsWith("enc("))
        } finally {
            app.shutdown(5, TimeUnit.SECONDS)
        }
    }

    // FR-IAM-015 AC-10: the docs' Kotlin example builds auth over the encrypted file stores
    @Test
    fun `docs file secrets example`() {
        // docs-example: auth.md#50
        // adapted: a temporary data directory and a fixed test key when NARU_AUTH_FILE_KEY is unset
        val dataDir = tmp.newFolder()
        val fromEnv: AuthFileKey? = try { AuthFileKey.fromEnv() } catch (e: IllegalArgumentException) { null }
        val key: AuthFileKey? = fromEnv ?: AuthFileKey.of(ByteArray(32) { (it * 3).toByte() })
        val auth = Auth.builder(KeySources.fromFile(File(dataDir, "naru-auth.key"), key))
            .users(FileUserStore(File(dataDir, "users.json"), key))
            .hashIterations(1000)
            .build()
        auth.users().create("alice", "pw-1234567890".toCharArray())
        assertTrue(File(dataDir, "users.json").readText().contains("enc("))
    }

    // FR-IAM-015 AC-3 (N3-01): a Kotlin data class extending ForwardingUserStore is constructible, and the
    // FileUserStore it wraps is held to the PROD key rule like a bare one
    @Test
    fun `FR-IAM-015 AC-3 data class decorator is held to the key rule`() {
        val usersFile = File(tmp.newFolder(), "users.json")
        val decorated = N301TenantStore("acme", FileUserStore(usersFile))
        try {
            Auth.builder(KeySources.of(AuthTestSupport.SECRET)).mode(AuthMode.PROD).hashIterations(1000)
                .users(decorated).build()
            fail("PROD built over a data class decorator of a FileUserStore without a key")
        } catch (e: AuthConfigException) {
            assertTrue(e.message, e.message!!.contains("FileUserStore(file, AuthFileKey)"))
        }
        assertEquals(N301TenantStore("acme", decorated.inner), decorated)
    }
}
