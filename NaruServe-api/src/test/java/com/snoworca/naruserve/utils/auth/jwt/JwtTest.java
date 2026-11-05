package com.snoworca.naruserve.utils.auth.jwt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import com.snoworca.naruserve.utils.auth.internal.HmacSigner;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** The standalone HS256 Jwt (SEC-TOKEN-003). */
public class JwtTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OTHER = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII);
    /** A second-aligned instant: 2025-10-29T01:21:40Z. */
    private static final long NOW = 1_761_700_900_000L;

    private final AtomicLong clock = new AtomicLong(NOW);
    private final JwtKeys keys = JwtKeys.of("k1", KEY);
    private final Jwt jwt = Jwt.builder(keys).timeSource(clock::get).build();

    private static String b64(String json) {
        return Base64Url.encode(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A token over raw header and payload JSON, signed with {@code key}. */
    private static String token(String header, String payload, byte[] key) {
        String input = b64(header) + "." + b64(payload);
        return input + "." + Base64Url.encode(new HmacSigner(key).sign(input.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String token(String header, String payload) {
        return token(header, payload, KEY);
    }

    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"k1\"}";
    private static final String PAYLOAD = "{\"sub\":\"u1\",\"exp\":" + (NOW / 1000 + 600) + "}";

    private JwtException.Reason refused(String token) {
        return refused(jwt, token, null);
    }

    private static JwtException.Reason refused(Jwt j, String token, String type) {
        try {
            j.verify(token, type);
        } catch (JwtException e) {
            return e.getReason();
        } catch (RuntimeException e) {
            throw new AssertionError("an unchecked " + e.getClass().getName() + " escaped verify", e);
        }
        fail("the token verified: " + token);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> header(String token) {
        String part = token.substring(0, token.indexOf('.'));
        return (Map<String, Object>) MiniJson.parse(Base64Url.decode(part), MiniJson.Limits.claims());
    }

    // SEC-TOKEN-003 AC-1: the header is exactly alg/typ/kid with HS256 and the signing kid; verify returns the
    // same subject, expiry and custom claims
    @Test
    public void SEC_TOKEN_003_AC1_signThenVerifyRoundTrips() throws Exception {
        JwtKeys rotated = JwtKeys.builder().signingKey("k2", OTHER).verificationKey("k1", KEY).build();
        Jwt j = Jwt.builder(rotated).timeSource(clock::get).build();
        JwtClaims in = JwtClaims.builder().subject("u1").expiresAt(NOW + 600_000L).claim("room", "lab")
                .claim("level", 3L).claim("admin", true).claim("tags", Arrays.asList("a", "b")).build();
        String t = j.sign(in);
        Map<String, Object> h = header(t);
        assertEquals(Arrays.asList("alg", "typ", "kid"), Arrays.asList(h.keySet().toArray()));
        assertEquals("HS256", h.get("alg"));
        assertEquals("k2", h.get("kid"));
        assertEquals("JWT", h.get("typ"));
        JwtClaims out = j.verify(t);
        assertEquals("u1", out.getSubject());
        assertEquals(NOW + 600_000L, out.getExpiresAt());
        assertEquals("lab", out.getString("room"));
        assertEquals(Long.valueOf(3), out.getLong("level"));
        assertEquals(Boolean.TRUE, out.getBoolean("admin"));
        assertEquals(Arrays.asList("a", "b"), out.getStringList("tags"));
        assertEquals(Long.valueOf(NOW), out.getIssuedAt());
        // a time is rounded down to the second, as a JWT stores whole seconds
        assertEquals(NOW + 1000L, j.verify(j.sign(JwtClaims.builder().expiresAt(NOW + 1999L).build()))
                .getExpiresAt());
        // a token signed with the old key still verifies by its kid
        assertEquals("u1", j.verify(token(HEADER, PAYLOAD)).getSubject());
    }

    // SEC-TOKEN-003 AC-2: alg none with an empty signature, HS512 and hs256 are refused (06 AUTH-T01, T02)
    @Test
    public void SEC_TOKEN_003_AC2_algorithmIsFixedByTheKeySet() {
        String valid = token(HEADER, PAYLOAD);
        assertEquals("u1", subjectOf(valid));
        String none = b64("{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\"k1\"}") + "." + b64(PAYLOAD) + ".";
        assertEquals(JwtException.Reason.MALFORMED, refused(none));
        String noneSigned = token("{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\"k1\"}", PAYLOAD);
        assertEquals(JwtException.Reason.BAD_ALGORITHM, refused(noneSigned));
        assertEquals(JwtException.Reason.BAD_ALGORITHM,
                refused(token("{\"alg\":\"HS512\",\"typ\":\"JWT\",\"kid\":\"k1\"}", PAYLOAD)));
        assertEquals(JwtException.Reason.BAD_ALGORITHM,
                refused(token("{\"alg\":\"hs256\",\"typ\":\"JWT\",\"kid\":\"k1\"}", PAYLOAD)));
        // alg given twice is a duplicate member, refused by the strict reader (06 AUTH-T06)
        assertEquals(JwtException.Reason.MALFORMED,
                refused(token("{\"alg\":\"HS256\",\"alg\":\"none\",\"kid\":\"k1\"}", PAYLOAD)));
    }

    private String subjectOf(String t) {
        try {
            return jwt.verify(t).getSubject();
        } catch (JwtException e) {
            throw new AssertionError(e.getReason() + ": " + e.getMessage());
        }
    }

    // SEC-TOKEN-003 AC-3: one or three dots, padding, '+' or '/' and a non-canonical last signature character are
    // refused (06 AUTH-T03, T04)
    @Test
    public void SEC_TOKEN_003_AC3_strictCompactForm() {
        String valid = token(HEADER, PAYLOAD);
        String[] p = valid.split("\\.");
        assertEquals(JwtException.Reason.MALFORMED, refused(p[0] + "." + p[1]));
        assertEquals(JwtException.Reason.MALFORMED, refused(valid + ".x"));
        assertEquals(JwtException.Reason.MALFORMED, refused(valid + "="));
        assertEquals(JwtException.Reason.MALFORMED, refused(p[0] + "=." + p[1] + "." + p[2]));
        assertEquals(JwtException.Reason.MALFORMED, refused(p[0] + "." + p[1] + "." + p[2].replace('-', '+')
                .replace('_', '/') + (p[2].contains("-") || p[2].contains("_") ? "" : "+")));
        assertEquals(JwtException.Reason.MALFORMED, refused(p[0] + "." + p[1].substring(0, 4) + "/"
                + p[1].substring(5) + "." + p[2]));
        // 32 signature bytes are 43 characters: the last one carries 2 unused low bits, which must be zero
        String sig = p[2];
        assertEquals(43, sig.length());
        char last = sig.charAt(42);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char sameBytes = alphabet.charAt(alphabet.indexOf(last) | 1);
        assertTrue(sameBytes != last);
        assertEquals(JwtException.Reason.MALFORMED, refused(p[0] + "." + p[1] + "." + sig.substring(0, 42)
                + sameBytes));
    }

    // SEC-TOKEN-003 AC-3: '=', '+' and '/' are refused in each of the three parts of a correctly signed token
    @Test
    public void SEC_TOKEN_003_AC3_forbiddenCharactersInEveryPart() {
        String[] p = token(HEADER, PAYLOAD).split("\\.");
        for (int part = 0; part < 3; part++) {
            for (char c : new char[] {'=', '+', '/'}) {
                String[] q = p.clone();
                q[part] = q[part].substring(0, 4) + c + q[part].substring(5);
                assertEquals("part " + part + " with " + c, JwtException.Reason.MALFORMED,
                        refused(q[0] + "." + q[1] + "." + q[2]));
            }
        }
    }

    private static String brackets(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    // SEC-TOKEN-002 AC-4: a correctly signed JWT whose header or payload holds a 100-level nested array is refused by
    // the CLAIMS limit; the content past the limit is not JSON, so the refusal shows it was never parsed (06 AUTH-T07)
    @Test
    public void SEC_TOKEN_002_AC4_deepArrayInAJwtHeaderOrPayloadIsRefused() {
        String deep = brackets('[', 100) + brackets(']', 100);
        String hostile = brackets('[', 100) + "this is not json";
        for (String nested : new String[] {deep, hostile}) {
            assertEquals(JwtException.Reason.MALFORMED, refused(token(
                    "{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"k1\",\"x\":" + nested + "}", PAYLOAD)));
            assertEquals(JwtException.Reason.MALFORMED, refused(token(HEADER,
                    "{\"sub\":\"u1\",\"exp\":" + (NOW / 1000 + 600) + ",\"x\":" + nested + "}")));
        }
    }

    // SEC-TOKEN-003 AC-4: exp missing, a string, a fraction or 1e300 is refused with JwtException only (06 AUTH-T05)
    @Test
    public void SEC_TOKEN_003_AC4_expMustBeAnIntegerNumericDate() {
        for (String payload : new String[] {"{\"sub\":\"u1\"}", "{\"sub\":\"u1\",\"exp\":\"1761700900\"}",
            "{\"sub\":\"u1\",\"exp\":1761700900.5}", "{\"sub\":\"u1\",\"exp\":1e300}",
            "{\"sub\":\"u1\",\"exp\":-5}", "{\"sub\":\"u1\",\"exp\":99999999999999999999}",
            "{\"sub\":7,\"exp\":1761701500}", "[1,2]"}) {
            assertEquals(payload, JwtException.Reason.MALFORMED, refused(token(HEADER, payload)));
        }
    }

    // SEC-TOKEN-003 AC-5: crit is refused; jku is ignored and verification opens no connection (06 AUTH-T10)
    @Test
    public void SEC_TOKEN_003_AC5_critRefusedAndJkuNeverFetched() throws Exception {
        assertEquals(JwtException.Reason.BAD_ALGORITHM, refused(token(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"k1\",\"crit\":[\"exp\"]}", PAYLOAD)));
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setSoTimeout(300);
            String url = "http://127.0.0.1:" + listener.getLocalPort() + "/jwks.json";
            String t = token("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"k1\",\"jku\":\"" + url + "\",\"x5u\":\""
                    + url + "\",\"jwk\":{\"kty\":\"oct\",\"k\":\"AA\"}}", PAYLOAD);
            assertEquals("u1", jwt.verify(t).getSubject());
            String forged = token("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"k1\",\"jku\":\"" + url + "\"}",
                    PAYLOAD, OTHER);
            assertEquals(JwtException.Reason.BAD_SIGNATURE, refused(forged));
            try {
                listener.accept().close();
                fail("verification connected to the jku URL");
            } catch (SocketTimeoutException expected) {
                // no connection was made
            }
        }
    }

    // SEC-TOKEN-003 AC-6: an unknown kid is refused, and a token over the size limit is refused before its
    // signature is computed
    @Test
    public void SEC_TOKEN_003_AC6_unknownKidAndTooLarge() throws Exception {
        assertEquals(JwtException.Reason.UNKNOWN_KEY,
                refused(token("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"k9\"}", PAYLOAD)));
        assertEquals(8 * 1024, jwt.getMaxTokenBytes());
        StringBuilder big = new StringBuilder("{\"sub\":\"u1\",\"exp\":" + (NOW / 1000 + 600) + ",\"pad\":\"");
        while (big.length() < 6200) {
            big.append('x');
        }
        // a token above 8 KiB whose signature is wrong: the size check answers first, never BAD_SIGNATURE
        String large = token(HEADER, big.append("\"}").toString(), OTHER);
        assertTrue(large.length() > 8 * 1024);
        assertEquals(JwtException.Reason.TOO_LARGE, refused(large));
        // a key set with several keys refuses a token without a kid; a single-key set accepts it
        JwtKeys two = JwtKeys.builder().signingKey("k1", KEY).verificationKey("k0", OTHER).build();
        String noKid = token("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", PAYLOAD);
        assertEquals(JwtException.Reason.UNKNOWN_KEY, refused(Jwt.builder(two).timeSource(clock::get).build(),
                noKid, null));
        assertEquals("u1", jwt.verify(noKid).getSubject());
        assertEquals(JwtException.Reason.MALFORMED, refused(token("{\"alg\":\"HS256\",\"kid\":null}", PAYLOAD)));
    }

    // SEC-TOKEN-003 AC-7: exp + leeway - 1 ms passes, exp + leeway + 1 ms is refused; default 60 s, above 5 min refused
    @Test
    public void SEC_TOKEN_003_AC7_leewayBoundary() throws Exception {
        assertEquals(60_000L, jwt.getLeeway());
        long exp = NOW + 600_000L;
        String t = jwt.sign(JwtClaims.builder().subject("u1").expiresAt(exp).build());
        clock.set(exp + 60_000L - 1);
        assertEquals("u1", jwt.verify(t).getSubject());
        clock.set(exp + 60_000L + 1);
        assertEquals(JwtException.Reason.EXPIRED, refused(t));
        try {
            Jwt.builder(keys).leeway(300_001L);
            fail("a leeway above 5 minutes was accepted");
        } catch (IllegalArgumentException expected) {
            // refused by the builder
        }
        Jwt.builder(keys).leeway(300_000L).build();
        // nbf in the future beyond the leeway is not yet valid
        clock.set(NOW);
        String later = jwt.sign(JwtClaims.builder().notBefore(NOW + 61_000L).expiresAt(NOW + 600_000L).build());
        assertEquals(JwtException.Reason.NOT_YET_VALID, refused(later));
        clock.set(NOW + 1000L);
        jwt.verify(later);
    }

    // SEC-TOKEN-003 AC-8: the reasons distinguish malformed, bad algorithm, unknown key, bad signature, expired, not
    // yet valid, wrong type and too large
    @Test
    public void SEC_TOKEN_003_AC8_reasonsAreDistinguished() throws Exception {
        Set<JwtException.Reason> seen = EnumSet.noneOf(JwtException.Reason.class);
        seen.add(refused("a.b"));
        seen.add(refused(token("{\"alg\":\"HS384\",\"kid\":\"k1\"}", PAYLOAD)));
        seen.add(refused(token("{\"alg\":\"HS256\",\"kid\":\"nope\"}", PAYLOAD)));
        seen.add(refused(token(HEADER, PAYLOAD, OTHER)));
        seen.add(refused(token(HEADER, "{\"exp\":" + (NOW / 1000 - 3600) + "}")));
        seen.add(refused(token(HEADER, "{\"iat\":" + (NOW / 1000 + 3600) + ",\"exp\":" + (NOW / 1000 + 7200) + "}")));
        seen.add(refused(jwt, token(HEADER, PAYLOAD), "device"));
        char[] pad = new char[9000];
        Arrays.fill(pad, 'a');
        seen.add(refused(new String(pad)));
        Jwt scoped = Jwt.builder(keys).issuer("hub").audience("api").timeSource(clock::get).build();
        seen.add(refused(scoped, token(HEADER, "{\"iss\":\"other\",\"aud\":\"api\",\"exp\":" + (NOW / 1000 + 60)
                + "}"), null));
        seen.add(refused(scoped, token(HEADER, "{\"iss\":\"hub\",\"aud\":[\"web\"],\"exp\":" + (NOW / 1000 + 60)
                + "}"), null));
        assertEquals(EnumSet.allOf(JwtException.Reason.class), seen);
        JwtException e = new JwtException(JwtException.Reason.EXPIRED, "the token has expired");
        assertFalse(e.getMessage().contains("eyJ"));
        // the type check passes for the matching typ, and issuer/audience are written by sign
        JwtClaims c = scoped.verify(scoped.sign(JwtClaims.builder().type("device").expiresAt(NOW + 60_000L).build()),
                "device");
        assertEquals("device", c.getType());
        assertEquals("hub", c.getIssuer());
        assertEquals(Arrays.asList("api"), c.getAudience());
        assertNull(c.getSubject());
    }

    /** Builder misuse is refused at once. */
    @Test
    public void builderRefusesMisuse() {
        try {
            JwtClaims.builder().build();
            fail("claims without an expiry");
        } catch (IllegalStateException expected) {
            // exp is required
        }
        try {
            JwtClaims.builder().claim("exp", 5L);
            fail("a registered claim through claim()");
        } catch (IllegalArgumentException expected) {
            // registered claims have their own setters
        }
        Map<String, Object> big = new LinkedHashMap<>();
        StringBuilder s = new StringBuilder();
        while (s.length() < 1000) {
            s.append("abcdefghij");
        }
        JwtClaims.Builder b = JwtClaims.builder().expiresAt(NOW + 1000L);
        for (int i = 0; i < 9; i++) {
            b.claim("c" + i, s.toString());
        }
        big.put("unused", Boolean.TRUE);
        try {
            jwt.sign(b.build());
            fail("a token above the size limit was signed");
        } catch (IllegalArgumentException expected) {
            // too large to sign
        }
        assertFalse(jwt.toString().contains(new String(KEY, StandardCharsets.US_ASCII)));
    }
}
