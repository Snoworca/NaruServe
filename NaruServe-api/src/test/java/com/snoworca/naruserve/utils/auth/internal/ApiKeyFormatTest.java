package com.snoworca.naruserve.utils.auth.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.Test;

/** The nk_prefix8_secret32 key format and its SHA-256 storage form (SEC-APIKEY-003 component). */
public class ApiKeyFormatTest {

    private static final Pattern KEY = Pattern.compile("nk_[A-Za-z0-9]{8}_[A-Za-z0-9]{32}");

    // SEC-APIKEY-003 component: generated keys match the format and differ
    @Test
    public void generatedKeysMatchTheFormat() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        int[] counts = new int[128];
        for (int i = 0; i < 2000; i++) {
            ApiKeyFormat.Key k = ApiKeyFormat.generate(random);
            assertTrue(k.plain(), KEY.matcher(k.plain()).matches());
            assertEquals("nk_" + k.prefix() + "_" + k.secret(), k.plain());
            assertTrue(seen.add(k.plain()));
            for (char c : k.secret().toCharArray()) {
                counts[c]++;
            }
        }
        // every base62 character occurs: no character is starved
        for (char c : ApiKeyFormat.ALPHABET.toCharArray()) {
            assertTrue(String.valueOf(c), counts[c] > 0);
        }
        assertEquals(62, ApiKeyFormat.ALPHABET.length());
    }

    // SEC-APIKEY-003 component: only exact nk_<8>_<32> base62 values parse
    @Test
    public void parseAcceptsOnlyTheExactFormat() {
        String secret = "abcdefghijABCDEFGHIJ0123456789xy";
        ApiKeyFormat.Key k = ApiKeyFormat.parse("nk_ab12cd34_" + secret);
        assertNotNull(k);
        assertEquals("ab12cd34", k.prefix());
        assertEquals(secret, k.secret());
        for (String bad : new String[] {"abc", "nk_ab12cd3_" + secret, "nk_ab12cd345_" + secret,
                "nk_ab12cd34_" + secret.substring(1) + "-", "nk_ab12cd34_" + secret + "z", "NK_ab12cd34_" + secret,
                "nk-ab12cd34_" + secret, "nk_ab12cd34-" + secret, "nk_ab12cd3é_" + secret, "", "nk__" + secret}) {
            assertNull(bad, ApiKeyFormat.parse(bad));
        }
    }

    // SEC-APIKEY-003 component: the stored form is lower-case hex SHA-256 of the secret, 64 characters
    @Test
    public void secretHashIsLowerCaseHexSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApiKeyFormat.secretHash("abc"));
        String h = ApiKeyFormat.secretHash("abcdefghijABCDEFGHIJ0123456789xy");
        assertTrue(h, h.matches("[0-9a-f]{64}"));
        assertNotEquals(h, ApiKeyFormat.secretHash("abcdefghijABCDEFGHIJ0123456789xz"));
        assertFalse(h.contains("abcdefghij"));
    }
}
