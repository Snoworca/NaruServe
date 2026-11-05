package com.snoworca.naruserve.utils.auth.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.ApiJarClassesAccess;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;

/** Base64Url, HmacSigner and ConstantTime (SEC-TOKEN-001). */
public class TokenPrimitivesTest {

    // SEC-TOKEN-001 AC-1: encode emits only the base64url alphabet and never '='
    @Test
    public void SEC_TOKEN_001_AC1_encodeAlphabet() {
        Random r = new Random(1);
        for (int n = 0; n <= 100; n++) {
            byte[] b = new byte[n];
            r.nextBytes(b);
            String s = Base64Url.encode(b);
            assertTrue(s, s.matches("[A-Za-z0-9_-]*"));
            assertEquals((n * 8 + 5) / 6, s.length());
        }
        assertEquals("-_8", Base64Url.encode(new byte[] {(byte) 0xfb, (byte) 0xff}));
        assertEquals("", Base64Url.encode(new byte[0]));
    }

    private static void refused(String s) {
        try {
            Base64Url.decode(s);
            fail("decoded " + s);
        } catch (IllegalArgumentException expected) {
            // strict
        }
    }

    // SEC-TOKEN-001 AC-2: strict decode, and decode(encode(b)) == b for lengths 0 to 64
    @Test
    public void SEC_TOKEN_001_AC2_strictDecodeAndRoundTrip() {
        Random r = new Random(2);
        for (int n = 0; n <= 64; n++) {
            for (int k = 0; k < 20; k++) {
                byte[] b = new byte[n];
                r.nextBytes(b);
                assertArrayEquals(b, Base64Url.decode(Base64Url.encode(b)));
            }
        }
        for (String bad : Arrays.asList("QQ==", "QQ=", "a+b/", "ab cd", "ab\ncd", "ab\tc", "A", "QR", "QUF", "QUJ")) {
            refused(bad);
        }
        // "QQ" is the canonical encoding of "A"; "QR" decodes to the same byte with non-zero unused bits
        assertArrayEquals("A".getBytes(StandardCharsets.US_ASCII), Base64Url.decode("QQ"));
        assertArrayEquals("AB".getBytes(StandardCharsets.US_ASCII), Base64Url.decode("QUI"));
    }

    // SEC-TOKEN-001 AC-2: '+' and '/' are each refused on their own, in any position of otherwise valid text
    @Test
    public void SEC_TOKEN_001_AC2_plusAndSlashAreEachRefused() {
        for (String bad : Arrays.asList("+bcd", "a+cd", "abc+", "/bcd", "a/cd", "abc/")) {
            refused(bad);
        }
        assertEquals(3, Base64Url.decode("abcd").length);
    }

    // SEC-TOKEN-001 AC-3: 16 threads over one key equal a single-threaded javax.crypto.Mac result
    @Test
    public void SEC_TOKEN_001_AC3_threadSafeHmac() throws Exception {
        final byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        final HmacSigner signer = new HmacSigner(key);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                final int seed = t;
                results.add(pool.submit(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        Random r = new Random(seed);
                        Mac mac = Mac.getInstance("HmacSHA256");
                        mac.init(new SecretKeySpec(key, "HmacSHA256"));
                        for (int i = 0; i < 500; i++) {
                            byte[] data = new byte[r.nextInt(200)];
                            r.nextBytes(data);
                            if (!Arrays.equals(mac.doFinal(data), signer.sign(data))) {
                                return false;
                            }
                        }
                        return true;
                    }
                }));
            }
            for (Future<Boolean> f : results) {
                assertTrue(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        byte[] sig = signer.sign("x".getBytes(StandardCharsets.UTF_8));
        assertEquals(32, sig.length);
        assertTrue(signer.verify("x".getBytes(StandardCharsets.UTF_8), sig));
        assertFalse(signer.verify("y".getBytes(StandardCharsets.UTF_8), sig));
    }

    private static byte[] classBytes(Class<?> c) throws Exception {
        try (InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    // SEC-TOKEN-001 AC-4: equal only for the same length and content, and no MessageDigest.isEqual
    @Test
    public void SEC_TOKEN_001_AC4_constantTimeEquals() throws Exception {
        assertTrue(ConstantTime.equals(new byte[] {1, 2, 3}, new byte[] {1, 2, 3}));
        assertTrue(ConstantTime.equals(new byte[0], new byte[0]));
        assertFalse(ConstantTime.equals(new byte[] {1, 2, 3}, new byte[] {1, 2, 4}));
        assertFalse(ConstantTime.equals(new byte[] {1, 2, 3}, new byte[] {1, 2}));
        assertFalse(ConstantTime.equals(new byte[] {1, 2}, new byte[] {1, 2, 0}));
        assertFalse(ConstantTime.equals(new byte[] {0}, new byte[0]));
        String constants = new String(classBytes(ConstantTime.class), StandardCharsets.ISO_8859_1);
        assertFalse(constants.contains("isEqual"));
        assertFalse(constants.contains("java/security/MessageDigest"));
    }

    // SEC-TOKEN-001 AC-5: the three classes are in utils.auth.internal, which is not a public API package
    @Test
    public void SEC_TOKEN_001_AC5_internalPackage() {
        for (Class<?> c : new Class<?>[] {Base64Url.class, HmacSigner.class, ConstantTime.class}) {
            assertEquals("com.snoworca.naruserve.utils.auth.internal", c.getPackage().getName());
        }
        assertFalse(ApiJarClassesAccess.publicPackages().contains("com.snoworca.naruserve.utils.auth.internal"));
        assertTrue(ApiJarClassesAccess.publicPackages().contains("com.snoworca.naruserve.utils.auth"));
    }
}
