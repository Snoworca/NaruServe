package com.snoworca.naruserve.utils.auth.password;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import com.snoworca.naruserve.utils.auth.internal.Passwords;
import java.util.regex.Pattern;
import org.junit.Test;

/** Pbkdf2PasswordHasher (SEC-TOKEN-007) and its rehash thresholds (PERF-TOKEN-001). */
public class Pbkdf2PasswordHasherTest {

    private static final Pattern FORMAT = Pattern.compile("pbkdf2-sha256\\$1000\\$[A-Za-z0-9_-]{22}\\$[A-Za-z0-9_-]{43}");

    // SEC-TOKEN-007 AC-2: pbkdf2-sha256$<iter>$<salt>$<hash>, 16-byte salt, 32-byte key, two hashes differ
    @Test
    public void SEC_TOKEN_007_AC2_formatSaltKeyAndFreshSalt() {
        Pbkdf2PasswordHasher h = new Pbkdf2PasswordHasher(1000);
        String a = h.hash("correct horse".toCharArray());
        String b = h.hash("correct horse".toCharArray());
        assertTrue(a, FORMAT.matcher(a).matches());
        String[] parts = a.split("\\$");
        assertEquals(16, Base64Url.decode(parts[2]).length);
        assertEquals(32, Base64Url.decode(parts[3]).length);
        assertNotEquals(a, b);
        assertNotEquals(a.split("\\$")[2], b.split("\\$")[2]);
    }

    // SEC-TOKEN-007 AC-3: verify true for the password, false for another, with the stored iteration count
    @Test
    public void SEC_TOKEN_007_AC3_verifyUsesTheStoredIterationCount() {
        String stored = new Pbkdf2PasswordHasher(1200).hash("s3cret".toCharArray());
        Pbkdf2PasswordHasher other = new Pbkdf2PasswordHasher(50_000);
        assertTrue(other.verify("s3cret".toCharArray(), stored));
        assertFalse(other.verify("s3cret!".toCharArray(), stored));
        assertFalse(other.verify("S3cret".toCharArray(), stored));
        // a hash written by the wave-3 internal format is the same format and still verifies
        String legacy = new Passwords(1000).hash("old".toCharArray());
        assertTrue(other.verify("old".toCharArray(), legacy));
    }

    // SEC-TOKEN-007 AC-4: accepts by prefix only; verify on a foreign string is false without throwing
    @Test
    public void SEC_TOKEN_007_AC4_acceptsByPrefixAndForeignVerifyIsFalse() {
        Pbkdf2PasswordHasher h = new Pbkdf2PasswordHasher(1000);
        assertTrue(h.accepts("pbkdf2-sha256$1000$abc$def"));
        assertFalse(h.accepts("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234"));
        assertFalse(h.accepts("pbkdf2-sha1$1000$a$b"));
        assertFalse(h.accepts(""));
        for (String foreign : new String[] {"$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234", "", "x",
            "pbkdf2-sha256$", "pbkdf2-sha256$x$y$z", "pbkdf2-sha256$1000$!!$!!", "pbkdf2-sha256$-5$AAAA$AAAA"}) {
            assertFalse(foreign, h.verify("pw".toCharArray(), foreign));
        }
        try {
            h.hash(new char[0]);
            fail("hashed an empty password");
        } catch (IllegalArgumentException expected) {
            // SecretKeySpec cannot take an empty key; the hasher refuses before hashing
        }
    }

    // PERF-TOKEN-001 AC-4: target 100,000: rehash below 80,000 and above 125,000 only
    @Test
    public void PERF_TOKEN_001_AC4_rehashThresholds() {
        Pbkdf2PasswordHasher target = new Pbkdf2PasswordHasher(100_000);
        String salt = Base64Url.encode(new byte[16]);
        String key = Base64Url.encode(new byte[32]);
        for (int stored : new int[] {79_999, 125_001}) {
            assertTrue(String.valueOf(stored), target.needsRehash("pbkdf2-sha256$" + stored + "$" + salt + "$" + key));
        }
        for (int stored : new int[] {80_000, 100_000, 125_000}) {
            assertFalse(String.valueOf(stored), target.needsRehash("pbkdf2-sha256$" + stored + "$" + salt + "$" + key));
        }
        assertTrue(target.needsRehash("$2b$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234"));
    }
}
