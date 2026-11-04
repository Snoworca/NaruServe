package com.snoworca.naruserve.utils.auth.password;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.internal.Blowfish;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** The in-house bcrypt verifier (SEC-TOKEN-008). */
public class BcryptPasswordHasherTest {

    /** Published jBCrypt / OpenBSD vectors: password, stored hash. */
    private static final String[][] VECTORS = {
        {"a", "$2a$06$m0CrhHm10qJ3lXRY.5zDGO3rS2KdeeWLuGmsfGlMfOxih58VYVfxe"},
        {"abc", "$2a$06$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i"},
        {"abcdefghijklmnopqrstuvwxyz", "$2a$06$.rCVZVOThsIa97pEDOxvGuRRgzG64bvtJ0938xuqzv18d3ZpQhstC"},
        {"~!@#$%^&*()      ~!@#$%^&*()PNBFRD", "$2a$06$fPIsBO8qRqkjj273rfaOI.HtSV9jLDpTbZn782DC6/t7qT67P6FfO"},
    };

    /** The PHP manual's password_verify sample ($2y$, cost 10). */
    private static final String PHP_SAMPLE = "$2y$10$.vGA1O9wmRjrwAVXD98HNOgsNpDczlqm3Jq7KnEd1rVAGv3Fykk1a";

    /** A crypt_blowfish ($2y$, Apache htpasswd -B) hash of a non-ASCII password. */
    private static final String NON_ASCII = "$2y$04$Dtl8shgT9X2bj77HZYZwkOf9zPLomILdueDakSwd.GJmQmZKrn.rW";
    private static final String NON_ASCII_PASSWORD = "pässwörd-한글";

    /** A crypt_blowfish cost-15 hash of "cost15pw". */
    private static final String COST_15 = "$2y$15$cKFA.ahIIQ04CTCFEXq.RuBKHCg8Q2m3/uea60Dd/ZuMPS4VzPPoe";

    // SEC-TOKEN-008 component: the Blowfish cipher of the bcrypt core matches the published cipher vectors
    @Test
    public void blowfishCipherVectors() {
        assertEquals(0x4EF997456198DD78L, Blowfish.encryptBlock(new byte[8], 0L));
        byte[] ones = {-1, -1, -1, -1, -1, -1, -1, -1};
        assertEquals(0x51866FD5B85ECB8AL, Blowfish.encryptBlock(ones, 0xFFFFFFFFFFFFFFFFL));
    }

    // SEC-TOKEN-008 AC-1: jBCrypt/OpenBSD vectors, a PHP password_hash $2y$ sample and a non-ASCII password
    @Test
    public void SEC_TOKEN_008_AC1_publishedVectorsPhpSampleAndNonAscii() {
        BcryptPasswordHasher h = PasswordHashers.bcrypt();
        for (String[] v : VECTORS) {
            assertTrue(v[0], h.verify(v[0].toCharArray(), v[1]));
            assertFalse(v[0], h.verify((v[0] + "x").toCharArray(), v[1]));
        }
        assertTrue(h.verify("rasmuslerdorf".toCharArray(), PHP_SAMPLE));
        assertFalse(h.verify("rasmuslerdorF".toCharArray(), PHP_SAMPLE));
        assertTrue(h.verify(NON_ASCII_PASSWORD.toCharArray(), NON_ASCII));
        assertFalse(h.verify("passwords".toCharArray(), NON_ASCII));
        String made = h.hash(NON_ASCII_PASSWORD.toCharArray());
        assertTrue(made, made.startsWith("$2b$10$") && made.length() == 60);
        assertTrue(h.verify(NON_ASCII_PASSWORD.toCharArray(), made));
    }

    // SEC-TOKEN-008 AC-2: $2a$, $2b$, $2y$ are accepted; $2x$ is not
    @Test
    public void SEC_TOKEN_008_AC2_variants() {
        BcryptPasswordHasher h = PasswordHashers.bcrypt();
        String body = VECTORS[1][1].substring(3);
        assertTrue(h.accepts("$2a" + body));
        assertTrue(h.accepts("$2b" + body));
        assertTrue(h.accepts("$2y" + body));
        assertFalse(h.accepts("$2x" + body));
        assertFalse(h.accepts("pbkdf2-sha256$1000$a$b"));
        assertTrue(h.verify("abc".toCharArray(), "$2b" + body));
        assertTrue(h.verify("abc".toCharArray(), "$2y" + body));
        assertFalse(h.verify("abc".toCharArray(), "$2x" + body));
    }

    // SEC-TOKEN-008 AC-3: cost 15 over the default maximum 14 is refused without the key schedule and logs one ERROR
    @Test(timeout = 120_000)
    public void SEC_TOKEN_008_AC3_costAboveTheMaximumIsRefusedBeforeWork() {
        RecordingLogger log = new RecordingLogger();
        BcryptPasswordHasher h = BcryptPasswordHasher.builder().logger(log).build();
        assertEquals(14, h.getMaxCost());
        // warm-up: class loading and S-box init happen before the timing
        assertTrue(h.verify("a".toCharArray(), VECTORS[0][1]));
        long schedules = Blowfish.keySchedules();
        long start = System.nanoTime();
        assertFalse(h.verify("cost15pw".toCharArray(), COST_15));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals("the refused cost-15 hash ran no key schedule", schedules, Blowfish.keySchedules());
        // a sanity bound only (the key-schedule count above is the proof): a cost-15 schedule takes over a second
        assertTrue("took " + millis + " ms; a cost-15 schedule takes over a second", millis < 1000);
        assertEquals(log.lines.toString(), 1, log.count("ERROR"));
        assertTrue(log.lines.get(0), log.lines.get(0).contains("cost 15"));
        BcryptPasswordHasher raised = BcryptPasswordHasher.builder().maxCost(15).logger(log).build();
        assertTrue(raised.accepts(COST_15));
        long before = Blowfish.keySchedules();
        assertTrue(raised.verify("cost15pw".toCharArray(), COST_15));
        assertTrue("a raised maximum runs the schedule", Blowfish.keySchedules() > before);
        assertEquals(1, log.count("ERROR"));
    }

    // SEC-TOKEN-008 AC-4: past 72 UTF-8 bytes nothing counts, as in every bcrypt
    @Test
    public void SEC_TOKEN_008_AC4_only72BytesCount() {
        BcryptPasswordHasher h = BcryptPasswordHasher.builder().cost(4).build();
        StringBuilder b = new StringBuilder();
        while (b.length() < 72) {
            b.append("0123456789");
        }
        String first72 = b.substring(0, 72);
        assertEquals(72, first72.getBytes(StandardCharsets.UTF_8).length);
        String stored = h.hash(first72.toCharArray());
        assertTrue(h.verify((first72 + "anything after").toCharArray(), stored));
        assertFalse(h.verify(first72.substring(0, 71).toCharArray(), stored));
        // a multi-byte character straddling byte 72 counts only with its first bytes
        String straddle = first72.substring(0, 71) + "한";
        String s2 = h.hash(straddle.toCharArray());
        assertTrue(h.verify((first72.substring(0, 71) + "한한").toCharArray(), s2));
    }

    // SEC-TOKEN-008 AC-5: a salt not 22 characters, a hash part not 31, or a character outside ./A-Za-z0-9 is refused
    @Test
    public void SEC_TOKEN_008_AC5_malformedStringsAreRefused() {
        BcryptPasswordHasher h = PasswordHashers.bcrypt();
        String good = VECTORS[1][1];
        String salt = good.substring(7, 29);
        String hash = good.substring(29);
        assertEquals(22, salt.length());
        assertEquals(31, hash.length());
        assertTrue(h.verify("abc".toCharArray(), good));
        String[] bad = {
            "$2a$06$" + salt.substring(1) + hash,            // salt of 21
            "$2a$06$" + salt + hash.substring(1),            // hash of 30
            "$2a$06$" + salt + hash + "x",                   // hash of 32
            "$2a$06$" + salt.substring(0, 21) + "!" + hash,  // outside the alphabet
            "$2a$06$" + salt + hash.substring(0, 30) + "=",  // padding
            "$2a$6$" + salt + hash,                          // one-digit cost
            "$2a$03$" + salt + hash,                         // cost below 4
            "$2a$06" + salt + hash,                          // no separator
        };
        for (String s : bad) {
            assertFalse(s, h.verify("abc".toCharArray(), s));
        }
    }

    // SEC-TOKEN-008 AC-7: the digest is compared through ConstantTime (asserted on the class file in
    // AuthArchitectureTest); here a digest one character off fails
    @Test
    public void oneCharacterOffFails() {
        BcryptPasswordHasher h = PasswordHashers.bcrypt();
        String good = VECTORS[0][1];
        char last = good.charAt(58);
        String off = good.substring(0, 58) + (last == 'a' ? 'b' : 'a') + good.charAt(59);
        assertFalse(h.verify("a".toCharArray(), off));
    }
}
