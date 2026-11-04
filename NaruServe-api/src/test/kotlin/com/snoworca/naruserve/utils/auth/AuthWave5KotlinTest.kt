package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.utils.auth.apikey.ApiKey
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStatus
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey
import com.snoworca.naruserve.utils.auth.audit.AuditEvent
import com.snoworca.naruserve.utils.auth.audit.AuditSink
import com.snoworca.naruserve.utils.auth.audit.AuditSinks
import com.snoworca.naruserve.utils.auth.jwt.Jwt
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims
import com.snoworca.naruserve.utils.auth.jwt.JwtException
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys
import com.snoworca.naruserve.utils.auth.spi.RefreshRecord
import com.snoworca.naruserve.utils.auth.spi.RefreshTokenStore
import com.snoworca.naruserve.utils.auth.user.FileUserStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The wave-5 auth API from Kotlin: Jwt, the API key types and store, audit and the refresh-token SPI (T11). */
class AuthWave5KotlinTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    // SEC-TOKEN-003 from Kotlin: sign and verify without an Auth; failures are a JwtException with a reason
    @Test
    fun `Jwt signs and verifies from Kotlin`() {
        var now = 1_761_700_900_000L
        val jwt = Jwt.builder(JwtKeys.of("k1", ByteArray(32) { it.toByte() })).timeSource { now }.build()
        val token = jwt.sign(JwtClaims.builder().subject("device-7").type("device").expiresAt(now + 60_000L)
            .claim("room", "lab").build())
        val claims = jwt.verify(token, "device")
        val subject: String? = claims.subject
        val room: String? = claims.getString("room")
        val exp: Long = claims.expiresAt
        assertEquals("device-7", subject)
        assertEquals("lab", room)
        assertEquals(now + 60_000L, exp)
        now += 3_600_000L
        try {
            jwt.verify(token)
            fail("an expired token verified")
        } catch (e: JwtException) {
            assertEquals(JwtException.Reason.EXPIRED, e.reason)
        }
    }

    /** An ApiKeyStore written in Kotlin; findByPrefix and findById return nullable types (IR-APIKEY-004 AC-7). */
    class KotlinKeys : ApiKeyStore {
        val rows = mutableListOf<StoredApiKey>()
        override fun findByPrefix(prefix: String): StoredApiKey? = rows.firstOrNull { it.prefix == prefix }
        override fun findById(keyId: String): StoredApiKey? = rows.firstOrNull { it.id == keyId }
        override fun listByOwner(ownerUserId: String): List<StoredApiKey> = rows.filter { it.ownerUserId == ownerUserId }
        override fun insertIfUnderLimit(key: StoredApiKey, maxLiveKeys: Int, nowMillis: Long): Boolean = rows.add(key)
        override fun deleteByIdAndOwner(keyId: String, ownerUserId: String): Boolean =
            rows.removeIf { it.id == keyId && it.ownerUserId == ownerUserId }
        override fun deleteById(keyId: String): Boolean = rows.removeIf { it.id == keyId }
        override fun deleteAllByOwner(ownerUserId: String): Int {
            val n = rows.count { it.ownerUserId == ownerUserId }
            rows.removeIf { it.ownerUserId == ownerUserId }
            return n
        }
        override fun deleteStaleByOwner(ownerUserId: String, currentOwnerVer: Long): Int = 0
        override fun deleteExpired(nowMillis: Long): Int = 0
    }

    // DR-APIKEY-001 AC-6 and IR-APIKEY-004 AC-7: key.expiresAt is Long?, stored.ownerVer is Long; the store's two
    // finders are nullable and every other return is non-null
    @Test
    fun `DR-APIKEY-001 AC-6 IR-APIKEY-004 AC-7 Kotlin nullability of the API key types`() {
        val stored = StoredApiKey.builder().id("k1").prefix("Ab3dE5gH").secretHash(hash).ownerUserId("u1").ownerVer(4L)
            .name("ci").scopes(setOf("posts:read")).createdAt(1_000L).build()
        val ownerVer: Long = stored.ownerVer
        val key = ApiKey.of(stored, ApiKeyStatus.ACTIVE)
        val expiresAt: Long? = key.expiresAt
        val lastUsedAt: Long? = key.lastUsedAt
        val issuedBy: String? = key.issuedByUserId
        val created: Long = key.createdAt
        assertEquals(4L, ownerVer)
        assertNull(expiresAt)
        assertNull(lastUsedAt)
        assertNull(issuedBy)
        assertEquals(1_000L, created)
        val label = when (key.status) {
            ApiKeyStatus.ACTIVE -> "active"
            else -> "other"
        }
        assertEquals("active", label)
        val store: ApiKeyStore = KotlinKeys()
        store.insertIfUnderLimit(stored, 20, 2_000L)
        val found: StoredApiKey? = store.findByPrefix("Ab3dE5gH")
        val missing: StoredApiKey? = store.findById("nope")
        val listed: List<StoredApiKey> = store.listByOwner("u1")
        val future: Int = store.deleteFutureByOwner("u1", 4L)
        val admins: Int = store.countKeysWithAdminScopes()
        assertEquals(stored, found)
        assertNull(missing)
        assertEquals(1, listed.size)
        assertEquals(0, future)
        assertEquals(-1, admins)
        // the Java declarations carry exactly these nullable returns (what makes Kotlin see Long? and StoredApiKey?)
        fun nullable(c: Class<*>, name: String) = c.getMethod(name, *c.getMethod(name).parameterTypes)
            .annotatedReturnType.isAnnotationPresent(org.jspecify.annotations.Nullable::class.java)
        for (m in ApiKey::class.java.declaredMethods.filter { it.name.startsWith("get") && it.parameterCount == 0 }) {
            val expected = m.name in setOf("getExpiresAt", "getLastUsedAt", "getIssuedByUserId")
            assertEquals(m.name, expected, nullable(ApiKey::class.java, m.name))
        }
        assertEquals(false, nullable(StoredApiKey::class.java, "getOwnerVer"))
        for (m in ApiKeyStore::class.java.declaredMethods) {
            val n = m.annotatedReturnType.isAnnotationPresent(org.jspecify.annotations.Nullable::class.java)
            assertEquals(m.name, m.name == "findByPrefix" || m.name == "findById", n)
        }
        assertTrue(ApiKeyStore::class.java.`package`.isAnnotationPresent(org.jspecify.annotations.NullMarked::class.java))
        val issued = IssuedApiKey(key, "nk_Ab3dE5gH_s3cretS3cretS3cretS3cretS3cret32")
        assertTrue(issued.toString().contains("nk_Ab3dE5gH_***"))
    }

    // FR-AUTH-009 from Kotlin: AuditSink is a SAM lambda and AuditEvent reads with property syntax
    @Test
    fun `audit sink lambda and event properties`() {
        val seen = mutableListOf<String?>()
        val sink = AuditSink { e -> seen += e.actorId }
        val event: AuditEvent = AuditEvent.builder(AuditEvent.GRANT_DENIED, "setRoles").actorId("u1").build()
        AuditSinks.all(sink, AuditSinks.none()).record(event)
        assertEquals(listOf<String?>("u1"), seen)
        val reason: String? = event.reason
        assertNull(reason)
    }

    // FR-TOKEN-002 from Kotlin: a refresh store in memory and on file share one SPI
    @Test
    fun `refresh token stores from Kotlin`() {
        val record = RefreshRecord.builder().tokenId("t1").familyId("f1").userId("u1").tokenHash(hash).createdAt(1L)
            .expiresAt(9_000L).build()
        val stores: List<RefreshTokenStore> = listOf(InMemoryRefreshTokenStore(),
            FileRefreshTokenStore(java.io.File(tmp.root, "rt.json")))
        for (s in stores) {
            s.save(record)
            assertTrue(s.markUsed("t1", 2L))
            val used: Long? = s.find("t1")?.usedAt
            assertEquals(2L, used)
            assertEquals(0, s.purgeExpired(1_000L))
        }
        val auth = Auth.builder("k9Qv2Lm7Xr4Tz8Wb1Nc6Ys3Hd5Gf0Ja-Pq").users(FileUserStore(java.io.File(tmp.root, "u.json"), AuthTestSupport.FILE_KEY))
            .build()
        assertTrue(auth.describe().contains("FileRefreshTokenStore"))
    }
}
