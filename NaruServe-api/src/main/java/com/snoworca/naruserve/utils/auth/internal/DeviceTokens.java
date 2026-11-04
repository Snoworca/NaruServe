package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.jwt.Jwt;
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;
import com.snoworca.naruserve.utils.auth.jwt.JwtException;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import com.snoworca.naruserve.utils.auth.spi.DeviceToken;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The signed device tokens of trusted-device login (00 R34 (2)): an HS256 token with header {@code kid} and
 * {@code typ} {@code dt+jwt}, signed with the device key HMAC(secret, "naru-device") of that kid, holding {@code sub}
 * (the user id), {@code ver}, {@code fp} = base64url(HMAC(fingerprint key, normalized login name)) under the
 * fingerprint key HMAC(secret, "naru-device-fp") of the same kid, a random {@code jti}, {@code iat} and an
 * {@code exp} 90 days later (checked without leeway). The login name itself is never in the token. A token is read back only when its
 * signature, expiry, type and fingerprint all match; the user id and version are compared with the stored user by
 * the login (step 3 of the check order).
 */
// @req SEC-FLOW-013
public final class DeviceTokens {

    /** The header {@code typ} of a device token. */
    public static final String TYPE = "dt+jwt";

    /** The device-token lifetime: 90 days. */
    public static final long LIFETIME_MILLIS = 90L * 24 * 3_600_000L;

    /** An issued device token: the string a client keeps and what a trust store sees of it. */
    public static final class Issued {
        private final String token;
        private final DeviceToken content;

        Issued(String token, DeviceToken content) {
            this.token = token;
            this.content = content;
        }

        /** The token string. */
        public String token() {
            return token;
        }

        /** Its verified content. */
        public DeviceToken content() {
            return content;
        }
    }

    private final JwtKeys raw;
    private final Jwt jwt;
    private final TimeSource time;

    /** The device tokens of the raw key set {@code keys} on {@code time}. */
    // @req SEC-FLOW-013
    public DeviceTokens(JwtKeys keys, TimeSource time) {
        this.raw = keys;
        this.jwt = Jwt.builder(TokenKeys.purpose(keys, TokenKeys.DEVICE)).timeSource(time).leeway(0).build();
        this.time = time;
    }

    /** A new device token for the user {@code userId} at {@code ver} who logged in as {@code normalizedName}. */
    // @req SEC-FLOW-013
    public Issued issue(String userId, long ver, String normalizedName) {
        long now = time.nowMillis();
        String jti = AccessTokens.random();
        String kid = raw.getSigningKid();
        String fp = fingerprint(kid, normalizedName);
        if (fp == null) {
            throw new IllegalStateException("the key set has no signing key");
        }
        long exp = now + LIFETIME_MILLIS;
        String token = jwt.sign(JwtClaims.builder().type(TYPE).subject(userId).issuedAt(now).expiresAt(exp)
                .tokenId(jti).claim("ver", ver).claim("fp", fp).build());
        // the signed exp is rounded down to the second; report the same instant
        return new Issued(token, new DeviceToken(jti, userId, ver, (exp / 1000L) * 1000L));
    }

    /**
     * The content of {@code token} when its signature (by the kid in its header), expiry, type and fingerprint for
     * {@code normalizedName} all match; null otherwise (step 1 of the check order: no store is read).
     */
    // @req SEC-FLOW-013
    // @req SEC-FLOW-012
    /** OPS-AUTH-026 AC-3: whether {@code token} verifies as a device token of this key set. */
    public boolean isDeviceToken(String token) {
        try {
            jwt.verify(token, TYPE);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    public @Nullable DeviceToken read(String token, String normalizedName) {
        JwtClaims c;
        try {
            c = jwt.verify(token, TYPE);
        } catch (JwtException e) {
            return null;
        }
        String kid = kidOf(token);
        String sub = c.getSubject();
        Long ver = c.getLong("ver");
        String fp = c.getString("fp");
        String jti = c.getTokenId();
        long exp = c.getExpiresAt();
        if (kid == null || sub == null || ver == null || fp == null || jti == null) {
            return null;
        }
        String expected = fingerprint(kid, normalizedName);
        if (expected == null || !ConstantTime.equals(expected.getBytes(StandardCharsets.US_ASCII),
                fp.getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        return new DeviceToken(jti, sub, ver, exp);
    }

    private @Nullable String fingerprint(String kid, String normalizedName) {
        byte[] key = TokenKeys.keyFor(raw, kid, TokenKeys.DEVICE_FINGERPRINT);
        if (key == null) {
            return null;
        }
        return Base64Url.encode(new HmacSigner(key).sign(normalizedName.getBytes(StandardCharsets.UTF_8)));
    }

    /** The {@code kid} of a token's header (already verified by the signature check). */
    private static @Nullable String kidOf(String token) {
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        try {
            Object header = MiniJson.parse(new String(Base64Url.decode(token.substring(0, dot)),
                    StandardCharsets.UTF_8), MiniJson.Limits.claims());
            Object kid = header instanceof Map ? ((Map<?, ?>) header).get("kid") : null;
            return kid instanceof String ? (String) kid : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "DeviceTokens{" + TYPE + "}";
    }
}
