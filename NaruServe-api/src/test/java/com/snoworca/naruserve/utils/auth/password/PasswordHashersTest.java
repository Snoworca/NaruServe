package com.snoworca.naruserve.utils.auth.password;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** PasswordHashers.chain, digest and saltedDigest (FR-TOKEN-003; the login ACs come with the login endpoint). */
// @req FR-TOKEN-003
public class PasswordHashersTest {

    private static final String MD5 = "5f4dcc3b5aa765d61d8327deb882cf99"; // MD5("password")
    private static final String SHA1 = "5baa61e4c9b93f3f0682250b6cf8331b7ee68fd8";
    private static final String SHA256 = "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8";
    private static final String SHA256_BASE64 = "XohImNooBHFR0OVvjcYpJ3NgPQ1qq73WKhHvch0VQtg=";
    /** SHA-256("Xy7q$9" + "password") and SHA-256("password" + "Xy7q$9"); the salt holds a '$'. */
    private static final String SALT_FIRST = "17bb8b4f92a41efeef33b2be3373e4529ed6533cc1aabbd22ec2ccb38fa2ecd4";
    private static final String PASSWORD_FIRST = "df7578d086f9063e0cf78eda2b584d7c89b3a56990f29de3363b57785e6d6476";
    /** MD5 of the UTF-8 bytes of a non-ASCII password. */
    private static final String MD5_NON_ASCII = "88e4a473f9121558bc09aad79837459f";

    private static char[] pw(String s) {
        return s.toCharArray();
    }

    // FR-TOKEN-003 AC-1: chain(pbkdf2, bcrypt, md5Digest).hash(pw) is always a hash Pbkdf2PasswordHasher accepts
    @Test
    public void FR_TOKEN_003_AC1_chainHashesWithPrimary() {
        Pbkdf2PasswordHasher pbkdf2 = new Pbkdf2PasswordHasher(1000);
        PasswordHasher chain = PasswordHashers.chain(new RecordingLogger(), pbkdf2, PasswordHashers.bcrypt(),
                PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX));
        for (int i = 0; i < 3; i++) {
            String stored = chain.hash(pw("correct horse " + i));
            assertTrue(stored, pbkdf2.accepts(stored));
            assertTrue(stored.startsWith(Pbkdf2PasswordHasher.PREFIX));
            assertTrue(chain.verify(pw("correct horse " + i), stored));
            assertFalse("a primary hash at the target cost needs no rehash", chain.needsRehash(stored));
        }
        // the primary of the chain is whatever comes first, even when a legacy member could hash
        PasswordHasher bcryptFirst = PasswordHashers.chain(new RecordingLogger(),
                BcryptPasswordHasher.builder().cost(4).build(), pbkdf2);
        assertTrue(bcryptFirst.hash(pw("x")).startsWith("$2b$"));
    }

    // FR-TOKEN-003 AC-2: "$2b$" goes to bcrypt and "legacy-md5$" to the digest member; an unknown prefix fails
    // and logs one ERROR per prefix with at most its first 4 characters
    @Test
    public void FR_TOKEN_003_AC2_chainDispatchesByAcceptsAndLogsUnknownPrefixOnce() {
        RecordingLogger log = new RecordingLogger();
        Pbkdf2PasswordHasher pbkdf2 = new Pbkdf2PasswordHasher(1000);
        PasswordHasher chain = PasswordHashers.chain(log, pbkdf2, PasswordHashers.bcrypt(),
                PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX));
        String bcrypt = BcryptPasswordHasher.builder().cost(4).build().hash(pw("password"));
        assertTrue(bcrypt.startsWith("$2b$"));
        String md5 = "legacy-md5$" + MD5;

        assertTrue(chain.accepts(bcrypt));
        assertTrue(chain.accepts(md5));
        assertTrue(chain.verify(pw("password"), bcrypt));
        assertTrue(chain.verify(pw("password"), md5));
        assertFalse(chain.verify(pw("Password"), bcrypt));
        assertFalse(chain.verify(pw("Password"), md5));
        assertTrue("a legacy member's success is marked for rehash", chain.needsRehash(bcrypt));
        assertTrue(chain.needsRehash(md5));
        assertEquals(0, log.count("ERROR"));

        String unknown = "sha512$c2FsdHNhbHQ$0123456789abcdef0123456789abcdef";
        assertFalse(chain.verify(pw("password"), unknown));
        assertFalse(chain.verify(pw("password"), unknown));
        assertFalse(chain.accepts(unknown));
        assertFalse(chain.needsRehash(unknown));
        assertFalse(chain.verify(pw("password"), "sha512$another-hash-with-the-same-prefix"));
        assertEquals("one ERROR for the prefix", 1, log.count("ERROR"));
        String line = log.lines.get(0);
        assertTrue(line, line.contains("\"sha5\""));
        assertFalse(line, line.contains("sha51"));
        assertFalse(line, line.contains(unknown));

        assertFalse(chain.verify(pw("password"), "{SSHA}abcdefgh"));
        assertEquals("a second unknown prefix logs its own ERROR", 2, log.count("ERROR"));
        assertTrue(log.lines.get(1), log.lines.get(1).contains("\"{SSH\""));
        // a stored string of 4 characters or fewer is never shown whole
        assertFalse(chain.verify(pw("password"), "abcd"));
        assertEquals(3, log.count("ERROR"));
        assertTrue(log.lines.get(2), log.lines.get(2).contains("\"abc\""));
        assertFalse(log.lines.get(2), log.lines.get(2).contains("abcd"));
    }

    // FR-TOKEN-003 AC-4: digest verifies unsalted MD5, SHA-1 and SHA-256 (hex either case, and base64)
    @Test
    public void FR_TOKEN_003_AC4_digestVerifiesUnsaltedMd5Sha1Sha256() {
        PasswordHasher md5 = PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX);
        PasswordHasher sha1 = PasswordHashers.digest("legacy-sha1$", "SHA-1", DigestEncoding.HEX);
        PasswordHasher sha256 = PasswordHashers.digest("legacy-sha256", "SHA-256", DigestEncoding.HEX);
        PasswordHasher sha256b64 = PasswordHashers.digest("legacy-sha256b", "SHA-256", DigestEncoding.BASE64);

        assertTrue(md5.verify(pw("password"), "legacy-md5$" + MD5));
        assertTrue(md5.verify(pw("password"), "legacy-md5$" + MD5.toUpperCase()));
        assertTrue("a trailing $ of the prefix is not doubled", sha1.verify(pw("password"), "legacy-sha1$" + SHA1));
        assertTrue(sha256.verify(pw("password"), "legacy-sha256$" + SHA256));
        assertTrue(sha256b64.verify(pw("password"), "legacy-sha256b$" + SHA256_BASE64));
        assertTrue(sha256b64.verify(pw("password"), "legacy-sha256b$" + SHA256_BASE64.replace("=", "")));
        assertTrue(md5.verify(pw("pässwörd-한글"), "legacy-md5$" + MD5_NON_ASCII));

        assertFalse(md5.verify(pw("passwordx"), "legacy-md5$" + MD5));
        assertFalse(sha1.verify(pw("passwor"), "legacy-sha1$" + SHA1));
        assertFalse(sha256.verify(pw("Password"), "legacy-sha256$" + SHA256));
        assertFalse("wrong algorithm length", md5.verify(pw("password"), "legacy-md5$" + SHA1));
        assertFalse("not hex", md5.verify(pw("password"), "legacy-md5$" + MD5.replace('f', 'g')));
        assertFalse("odd length", md5.verify(pw("password"), "legacy-md5$" + MD5.substring(1)));
        assertFalse("another prefix", md5.verify(pw("password"), "legacy-sha1$" + MD5));
        assertFalse("an empty password", md5.verify(pw(""), "legacy-md5$d41d8cd98f00b204e9800998ecf8427e"));
        assertTrue(md5.accepts("legacy-md5$" + MD5));
        assertFalse(md5.accepts("legacy-md5" + MD5));
        assertFalse(md5.accepts("$2b$04$..."));
        try {
            PasswordHashers.digest("legacy-x", "NO-SUCH-DIGEST", DigestEncoding.HEX);
            fail("an unknown algorithm was accepted");
        } catch (IllegalArgumentException expected) {
            // refused when built, not at the first login
        }
        try {
            PasswordHashers.digest("", "MD5", DigestEncoding.HEX);
            fail("an empty prefix would accept every stored string");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    // FR-TOKEN-003 AC-4: saltedDigest verifies salt+password and password+salt as configured
    @Test
    public void FR_TOKEN_003_AC4_saltedDigestVerifiesConfiguredOrder() {
        PasswordHasher saltFirst = PasswordHashers.saltedDigest("legacy-sha256", "SHA-256", DigestEncoding.HEX,
                SaltOrder.SALT_THEN_PASSWORD);
        PasswordHasher passwordFirst = PasswordHashers.saltedDigest("legacy-sha256", "SHA-256", DigestEncoding.HEX,
                SaltOrder.PASSWORD_THEN_SALT);
        String a = "legacy-sha256$Xy7q$9$" + SALT_FIRST;
        String b = "legacy-sha256$Xy7q$9$" + PASSWORD_FIRST;

        assertTrue(saltFirst.verify(pw("password"), a));
        assertFalse("the other order does not match", saltFirst.verify(pw("password"), b));
        assertTrue(passwordFirst.verify(pw("password"), b));
        assertFalse(passwordFirst.verify(pw("password"), a));
        assertFalse(saltFirst.verify(pw("passwore"), a));
        assertFalse("another salt", saltFirst.verify(pw("password"), "legacy-sha256$Xy7q$8$" + SALT_FIRST));
        assertFalse("no salt field", saltFirst.verify(pw("password"), "legacy-sha256$" + SALT_FIRST));
    }

    // FR-TOKEN-003 AC-4: both legacy verifiers compare through ConstantTime, never an early-exit comparison
    @Test
    public void FR_TOKEN_003_AC4_digestComparesThroughConstantTime() throws Exception {
        File source = new File("src/main/java/com/snoworca/naruserve/utils/auth/password/PasswordHashers.java");
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        int start = text.indexOf("private static final class Digest");
        assertTrue(start > 0);
        String digest = text.substring(start);
        assertTrue(digest.contains("ConstantTime.equals(md.digest(), expected)"));
        assertFalse(digest.contains("MessageDigest.isEqual"));
        assertFalse(digest.contains("Arrays.equals"));
        assertFalse(digest.contains(".equals(expected"));
    }

    // FR-TOKEN-003 AC-5: hash() of a digest or saltedDigest hasher throws and returns no hash string
    @Test
    public void FR_TOKEN_003_AC5_digestHashThrows() {
        PasswordHasher[] legacy = {
            PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX),
            PasswordHashers.digest("legacy-sha1", "SHA-1", DigestEncoding.BASE64),
            PasswordHashers.saltedDigest("legacy-sha256", "SHA-256", DigestEncoding.HEX, SaltOrder.SALT_THEN_PASSWORD),
        };
        for (PasswordHasher h : legacy) {
            String made = null;
            UnsupportedOperationException refused = null;
            try {
                made = h.hash(pw("password"));
            } catch (UnsupportedOperationException expected) {
                refused = expected;
            }
            // made is non-null exactly when hash() returned, so both checks can fail
            assertEquals(h + " made a hash: " + made, null, made);
            if (refused == null) {
                fail(h + " made a hash: " + made);
            }
            assertTrue(h + " always needs a rehash", h.needsRehash("legacy-md5$" + MD5));
        }
        // a chain whose primary is a digest refuses new hashes too
        PasswordHasher weak = PasswordHashers.chain(new RecordingLogger(), legacy[0]);
        try {
            weak.hash(pw("password"));
            fail("a digest primary made a hash");
        } catch (UnsupportedOperationException expected) {
            // refused
        }
    }
}
