package com.snoworca.naruserve.utils.auth.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** The enc(...) box of the auth file stores: payload layout, random IVs, purpose keys and fail-closed opens. */
public class SecretBoxTest {

    /** The fixed master key of the known-answer vectors: bytes 0..31. */
    private static byte[] master() {
        byte[] m = new byte[32];
        for (int i = 0; i < m.length; i++) {
            m[i] = (byte) i;
        }
        return m;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(Integer.toHexString((x & 0xff) | 0x100).substring(1));
        }
        return s.toString();
    }

    private static byte[] payload(String sealed) {
        assertTrue(sealed, sealed.matches("^enc\\([A-Za-z0-9_-]+\\)$"));
        return Base64Url.decode(sealed.substring(4, sealed.length() - 1));
    }

    // SEC-IAM-008 AC-2: the same value sealed twice gives two different enc(...) strings, and 1,000 seals of one value
    // carry 1,000 distinct 96-bit IVs
    @Test
    public void SEC_IAM_008_AC2_randomIvPerWrite() {
        SecretBox box = new SecretBox(master());
        byte[] hash = "$pbkdf2-sha256$1000$abc$def".getBytes(StandardCharsets.UTF_8);
        String a = box.seal(SecretBox.USERS_PASSWORD_HASH, "1", hash);
        String b = box.seal(SecretBox.USERS_PASSWORD_HASH, "1", hash);
        assertNotEquals(a, b);
        Set<String> ivs = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            byte[] p = payload(box.seal(SecretBox.USERS_PASSWORD_HASH, "1", hash));
            byte[] iv = new byte[12];
            System.arraycopy(p, 1, iv, 0, 12);
            ivs.add(hex(iv));
        }
        assertEquals(1000, ivs.size());
    }

    // SEC-IAM-008 AC-3: the purpose keys differ and match the known-answer vectors recorded here
    // (HMAC-SHA256(bytes 0..31, "naru-auth-file/v1/" + purpose))
    @Test
    public void SEC_IAM_008_AC3_purposeKeysAreSeparatedKnownAnswer() {
        SecretBox box = new SecretBox(master());
        byte[] users = box.subkey(SecretBox.USERS_PASSWORD_HASH);
        byte[] signing = box.subkey(SecretBox.SIGNING_KEY);
        assertEquals("5c5358643d758ed7af43bb9070fc563bd7f971857c83f3495345b217f85612f2", hex(users));
        assertEquals("a4b9db43fe44bc5d85a8afa5137830f200bddb24b269436f70d00cdff42a5ef8", hex(signing));
        assertFalse(java.util.Arrays.equals(users, signing));
        assertEquals("AES-256 keys", 32, users.length);
    }

    // SEC-IAM-008 AC-3: a value opens only for its own purpose and record
    @Test
    public void SEC_IAM_008_AC3_valuesAreBoundToPurposeAndRecord() {
        SecretBox box = new SecretBox(master());
        byte[] plain = "hash-of-alice".getBytes(StandardCharsets.UTF_8);
        String sealed = box.seal(SecretBox.USERS_PASSWORD_HASH, "1", plain);
        assertArrayEquals(plain, box.open(SecretBox.USERS_PASSWORD_HASH, "1", sealed));
        for (String[] other : new String[][] {{SecretBox.USERS_PASSWORD_HASH, "2"}, {SecretBox.SIGNING_KEY, "1"}}) {
            try {
                box.open(other[0], other[1], sealed);
                fail("opened for " + other[0] + "/" + other[1]);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("does not authenticate"));
            }
        }
    }

    // SEC-IAM-008 AC-4: a flipped byte, another master key, a version byte other than 1 and a malformed payload are
    // each refused with a message that does not hold the value
    @Test
    public void SEC_IAM_008_AC4_badValuesAreRefused() {
        SecretBox box = new SecretBox(master());
        String sealed = box.seal(SecretBox.USERS_PASSWORD_HASH, "1", "h".getBytes(StandardCharsets.UTF_8));
        byte[] p = payload(sealed);
        byte[] flipped = p.clone();
        flipped[p.length - 3] ^= 1;
        byte[] version = p.clone();
        version[0] = 2;
        byte[] other = master();
        other[0] = 99;
        String[] bad = {"enc(" + Base64Url.encode(flipped) + ")", "enc(" + Base64Url.encode(version) + ")",
                "enc(!!not-base64!!)", "enc(" + Base64Url.encode(new byte[10]) + ")", "plain-hash"};
        for (String v : bad) {
            try {
                box.open(SecretBox.USERS_PASSWORD_HASH, "1", v);
                fail("opened " + v);
            } catch (IllegalArgumentException e) {
                assertFalse(e.getMessage(), e.getMessage().contains(v));
            }
        }
        try {
            new SecretBox(other).open(SecretBox.USERS_PASSWORD_HASH, "1", sealed);
            fail("opened under another master key");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("does not authenticate"));
        }
        try {
            box.open(SecretBox.USERS_PASSWORD_HASH, "1", "enc(" + Base64Url.encode(version) + ")");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unknown format version 2"));
        }
    }

    // SEC-IAM-008 AC-6: the box and the key type use only javax.crypto (AES/GCM/NoPadding, GCMParameterSpec with a
    // 128-bit tag, HmacSHA256), SecureRandom and the internal Base64Url: no java.util.Base64 or java.nio.file; the
    // API 21 animal-sniffer check of the build (animalsnifferMain) covers every call, and NaruServe-api keeps no main
    // dependency
    @Test
    public void SEC_IAM_008_AC6_onlyApi21JdkApis() throws Exception {
        File main = new File(System.getProperty("user.dir"), "src/main/java/com/snoworca/naruserve/utils/auth");
        String box = new String(Files.readAllBytes(new File(main, "internal/SecretBox.java").toPath()),
                StandardCharsets.UTF_8);
        String key = new String(Files.readAllBytes(new File(main, "AuthFileKey.java").toPath()), StandardCharsets.UTF_8);
        for (String s : new String[] {box, key}) {
            assertFalse(s.contains("java.util.Base64"));
            assertFalse(s.contains("java.nio.file"));
        }
        assertTrue(box.contains("Cipher.getInstance(\"AES/GCM/NoPadding\")"));
        assertTrue(box.contains("new GCMParameterSpec(TAG_BITS, iv)"));
        assertEquals(128, SecretBox.TAG_BITS);
        assertEquals(12, SecretBox.IV_BYTES);
        assertTrue(box.contains("Mac.getInstance(\"HmacSHA256\")"));
        assertTrue(box.contains("new SecureRandom()"));
        assertTrue(box.contains("Base64Url.encode(payload)"));
        String build = new String(Files.readAllBytes(new File(System.getProperty("user.dir"), "../build.gradle")
                .toPath()), StandardCharsets.UTF_8);
        assertTrue(build.contains("':NaruServe-api'      : [:],"));
        assertTrue(build.contains("android-api-level-21"));
    }
}
