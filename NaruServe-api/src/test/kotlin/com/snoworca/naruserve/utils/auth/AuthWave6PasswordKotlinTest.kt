package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.testing.RecordingLogger
import com.snoworca.naruserve.utils.auth.password.DigestEncoding
import com.snoworca.naruserve.utils.auth.password.PasswordHasher
import com.snoworca.naruserve.utils.auth.password.PasswordHashers
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher
import com.snoworca.naruserve.utils.auth.password.SaltOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wave-6 password API from Kotlin: a Kotlin PasswordHasher inside PasswordHashers.chain, digest helpers (T11). */
// @req FR-TOKEN-003
class AuthWave6PasswordKotlinTest {

    /** A legacy format of the app: "legacy-x$" + the password reversed. */
    class ReversedHasher : PasswordHasher {
        var verified = 0

        override fun accepts(stored: String): Boolean = stored.startsWith("legacy-x$")

        override fun hash(password: CharArray): String = throw UnsupportedOperationException("verify only")

        override fun verify(password: CharArray, stored: String): Boolean {
            verified++
            return accepts(stored) && stored.removePrefix("legacy-x$") == String(password).reversed()
        }

        override fun needsRehash(stored: String): Boolean = true
    }

    // FR-TOKEN-003 from Kotlin: a Kotlin hasher in the chain verifies its own format and new hashes are PBKDF2
    @Test
    fun `a Kotlin PasswordHasher verifies through PasswordHashers chain`() {
        val pbkdf2 = Pbkdf2PasswordHasher(1000)
        val custom = ReversedHasher()
        val chain: PasswordHasher = PasswordHashers.chain(pbkdf2, custom)

        assertTrue(chain.verify("secret".toCharArray(), "legacy-x\$terces"))
        assertFalse(chain.verify("wrong".toCharArray(), "legacy-x\$terces"))
        assertEquals(2, custom.verified)
        assertTrue(chain.needsRehash("legacy-x\$terces"))

        val stored: String = chain.hash("secret".toCharArray())
        assertTrue(pbkdf2.accepts(stored))
        assertTrue(chain.verify("secret".toCharArray(), stored))
        assertFalse(chain.needsRehash(stored))
        assertEquals(2, custom.verified)
    }

    // FR-TOKEN-003 from Kotlin: digest and saltedDigest with an explicit logger for unknown prefixes
    @Test
    fun `digest helpers and a chain logger from Kotlin`() {
        val log = RecordingLogger()
        val chain = PasswordHashers.chain(log, Pbkdf2PasswordHasher(1000),
            PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX),
            PasswordHashers.saltedDigest("legacy-sha256", "SHA-256", DigestEncoding.HEX, SaltOrder.PASSWORD_THEN_SALT))
        assertTrue(chain.verify("password".toCharArray(), "legacy-md5\$5f4dcc3b5aa765d61d8327deb882cf99"))
        assertTrue(chain.verify("password".toCharArray(),
            "legacy-sha256\$Xy7q\$9\$df7578d086f9063e0cf78eda2b584d7c89b3a56990f29de3363b57785e6d6476"))
        assertFalse(chain.verify("password".toCharArray(), "plain:password"))
        assertEquals(1, log.count("ERROR"))
    }
}
