package com.snoworca.naruserve.utils.auth.jwt;

import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import com.snoworca.naruserve.utils.auth.internal.ConstantTime;
import com.snoworca.naruserve.utils.auth.internal.HmacSigner;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * HS256 JSON Web Tokens (JWS compact form) signed and verified with a {@link JwtKeys} set, usable without an
 * {@code Auth}. The algorithm is fixed by the key set: the header {@code alg} must be exactly {@code HS256} and is
 * never used to choose one, so {@code none}, {@code HS512} and case variants are refused. The header {@code kid}
 * selects the key (a token without a kid is accepted only when the set holds one key); {@code jku}, {@code jwk} and
 * {@code x5u} are ignored and never fetched; {@code crit} is refused. The signature is compared in constant time
 * before any claim is read, and every malformed or invalid token raises {@link JwtException}. Thread-safe. Express
 * counterpart: {@code jsonwebtoken}'s {@code jwt.sign(payload, key)} and {@code jwt.verify(token, key)} with
 * {@code algorithms: ['HS256']}.
 *
 * <pre>{@code
 * Jwt jwt = Jwt.builder(JwtKeys.of("k1", key)).issuer("hub").build();
 * String t = jwt.sign(JwtClaims.builder().subject("device-7").type("device").expiresAt(now + 3_600_000L).build());
 * JwtClaims c = jwt.verify(t, "device"); // JwtException when it does not verify
 * }</pre>
 */
// @req SEC-TOKEN-003
public final class Jwt {

    /** The default clock skew allowed on {@code exp}, {@code nbf} and {@code iat}: 60 seconds. */
    public static final long DEFAULT_LEEWAY_MILLIS = 60_000L;

    /** The largest leeway the builder accepts: 5 minutes. */
    public static final long MAX_LEEWAY_MILLIS = 300_000L;

    /** The default maximum token size: 8 KiB. */
    public static final int DEFAULT_MAX_TOKEN_BYTES = 8 * 1024;

    private static final String ALG = "HS256";
    /** The largest NumericDate whose milliseconds plus the leeway still fit in a long. */
    private static final long MAX_SECONDS = (Long.MAX_VALUE - MAX_LEEWAY_MILLIS) / 1000L;

    private final JwtKeys keys;
    private final Map<String, HmacSigner> signers;
    private final @Nullable String issuer;
    private final @Nullable String audience;
    private final long leeway;
    private final int maxTokenBytes;
    private final TimeSource time;

    private Jwt(Builder b) {
        this.keys = b.keys;
        Map<String, HmacSigner> s = new LinkedHashMap<>();
        for (String kid : b.keys.getKids()) {
            byte[] k = b.keys.verificationKey(kid);
            s.put(kid, new HmacSigner(Objects.requireNonNull(k, kid)));
        }
        this.signers = Collections.unmodifiableMap(s);
        this.issuer = b.issuer;
        this.audience = b.audience;
        this.leeway = b.leeway;
        this.maxTokenBytes = b.maxTokenBytes;
        this.time = b.time;
    }

    /** A builder over {@code keys}: new tokens are signed with its signing key, tokens verify with any of its keys. */
    // @req SEC-TOKEN-003
    public static Builder builder(JwtKeys keys) {
        return new Builder(Objects.requireNonNull(keys, "keys"));
    }

    /**
     * The token of {@code claims}, signed with the signing key; the header is exactly {@code alg} ({@code HS256}),
     * {@code typ} (the claims' type, {@code JWT} when it has none) and {@code kid} (the signing kid). {@code iat}
     * is the current time unless the claims set it; the configured issuer and audience fill an unset {@code iss} and
     * {@code aud}. {@link IllegalArgumentException} when the token would exceed the maximum token size.
     */
    // @req SEC-TOKEN-003
    public String sign(JwtClaims claims) {
        Objects.requireNonNull(claims, "claims");
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", ALG);
        header.put("typ", claims.getType() != null ? claims.getType() : "JWT");
        header.put("kid", keys.getSigningKid());
        Map<String, Object> payload = new LinkedHashMap<>();
        if (claims.getSubject() != null) {
            payload.put("sub", claims.getSubject());
        }
        String iss = claims.getIssuer() != null ? claims.getIssuer() : issuer;
        if (iss != null) {
            payload.put("iss", iss);
        }
        List<String> aud = claims.getAudience();
        if (aud.isEmpty() && audience != null) {
            aud = Collections.singletonList(audience);
        }
        if (aud.size() == 1) {
            payload.put("aud", aud.get(0));
        } else if (!aud.isEmpty()) {
            payload.put("aud", aud);
        }
        Long iat = claims.getIssuedAt();
        payload.put("iat", seconds(iat != null ? iat : time.nowMillis()));
        Long nbf = claims.getNotBefore();
        if (nbf != null) {
            payload.put("nbf", seconds(nbf));
        }
        payload.put("exp", seconds(claims.getExpiresAt()));
        if (claims.getTokenId() != null) {
            payload.put("jti", claims.getTokenId());
        }
        payload.putAll(claims.custom());
        String input = part(header) + "." + part(payload);
        HmacSigner signer = signers.get(keys.getSigningKid());
        String token = input + "." + Base64Url.encode(signer.sign(input.getBytes(StandardCharsets.US_ASCII)));
        if (token.length() > maxTokenBytes) {
            throw new IllegalArgumentException("the token has " + token.length() + " bytes, above the maximum token "
                    + "size of " + maxTokenBytes);
        }
        return token;
    }

    private static String part(Map<String, Object> json) {
        return Base64Url.encode(MiniJson.write(json, MiniJson.Limits.claims()).getBytes(StandardCharsets.UTF_8));
    }

    /** Whole seconds of {@code millis}, rounded down. */
    private static long seconds(long millis) {
        long s = millis / 1000L;
        return millis < 0 && s * 1000L != millis ? s - 1 : s;
    }

    /** {@link #verify(String, String)} without a type check. */
    // @req SEC-TOKEN-003
    public JwtClaims verify(String token) throws JwtException {
        return verify(token, null);
    }

    /**
     * The claims of {@code token} after it passed, in this order: size, three canonical base64url parts, a strict
     * JSON header with {@code alg} exactly {@code HS256} and no {@code crit}, the key of its {@code kid}, the
     * signature (constant time), then the claims: {@code exp} required and an integer, {@code nbf}/{@code iat} not
     * further in the future than the leeway, the header {@code typ} equal to {@code expectedType} when that is not
     * null, and the configured issuer and audience. Any failure is a {@link JwtException}.
     */
    // @req SEC-TOKEN-003
    public JwtClaims verify(String token, @Nullable String expectedType) throws JwtException {
        Objects.requireNonNull(token, "token");
        if (token.length() > maxTokenBytes) {
            throw fail(JwtException.Reason.TOO_LARGE, "the token is larger than " + maxTokenBytes + " bytes");
        }
        int d1 = token.indexOf('.');
        int d2 = d1 < 0 ? -1 : token.indexOf('.', d1 + 1);
        if (d1 <= 0 || d2 <= d1 + 1 || d2 == token.length() - 1 || token.indexOf('.', d2 + 1) >= 0) {
            throw fail(JwtException.Reason.MALFORMED, "a token is three non-empty parts joined by two dots");
        }
        String headerPart = token.substring(0, d1);
        String payloadPart = token.substring(d1 + 1, d2);
        byte[] headerBytes = decode(headerPart);
        byte[] payloadBytes = decode(payloadPart);
        byte[] signature = decode(token.substring(d2 + 1));
        Map<String, Object> header = object(headerBytes, "header");
        if (!ALG.equals(header.get("alg"))) {
            throw fail(JwtException.Reason.BAD_ALGORITHM, "the header alg is not HS256");
        }
        if (header.containsKey("crit")) {
            throw fail(JwtException.Reason.BAD_ALGORITHM, "the header carries crit, which is not supported");
        }
        Object kidValue = header.get("kid");
        String kid;
        if (kidValue == null && header.containsKey("kid")) {
            throw fail(JwtException.Reason.MALFORMED, "the header kid is null");
        }
        if (kidValue == null) {
            if (signers.size() != 1) {
                throw fail(JwtException.Reason.UNKNOWN_KEY, "the token has no kid and the key set has several keys");
            }
            kid = signers.keySet().iterator().next();
        } else if (kidValue instanceof String) {
            kid = (String) kidValue;
        } else {
            throw fail(JwtException.Reason.MALFORMED, "the header kid is not a string");
        }
        HmacSigner signer = signers.get(kid);
        if (signer == null) {
            throw fail(JwtException.Reason.UNKNOWN_KEY, "the token's kid is not in the key set");
        }
        Object typValue = header.get("typ");
        if (typValue != null && !(typValue instanceof String)) {
            throw fail(JwtException.Reason.MALFORMED, "the header typ is not a string");
        }
        byte[] expected = signer.sign(token.substring(0, d2).getBytes(StandardCharsets.US_ASCII));
        if (!ConstantTime.equals(expected, signature)) {
            throw fail(JwtException.Reason.BAD_SIGNATURE, "the signature does not match");
        }
        return claims(object(payloadBytes, "payload"), (String) typValue, expectedType);
    }

    private JwtClaims claims(Map<String, Object> p, @Nullable String typ, @Nullable String expectedType)
            throws JwtException {
        Long exp = time(p, "exp");
        if (exp == null) {
            throw fail(JwtException.Reason.MALFORMED, "the token has no exp");
        }
        Long nbf = time(p, "nbf");
        Long iat = time(p, "iat");
        String sub = string(p, "sub");
        String iss = string(p, "iss");
        String jti = string(p, "jti");
        List<String> aud = audience(p.get("aud"));
        long now = time.nowMillis();
        if (now >= exp * 1000L + leeway) {
            throw fail(JwtException.Reason.EXPIRED, "the token has expired");
        }
        if ((nbf != null && nbf * 1000L > now + leeway) || (iat != null && iat * 1000L > now + leeway)) {
            throw fail(JwtException.Reason.NOT_YET_VALID, "the token is not valid yet");
        }
        if (expectedType != null && !expectedType.equals(typ)) {
            throw fail(JwtException.Reason.WRONG_TYPE, "the token type is not " + expectedType);
        }
        if (issuer != null && !issuer.equals(iss)) {
            throw fail(JwtException.Reason.WRONG_ISSUER, "the token issuer is not the configured issuer");
        }
        if (audience != null && !aud.contains(audience)) {
            throw fail(JwtException.Reason.WRONG_AUDIENCE, "the token audience lacks the configured audience");
        }
        JwtClaims.Builder b = JwtClaims.builder().subject(sub).type(typ).issuer(iss).tokenId(jti)
                .expiresAt(exp * 1000L).audience(aud.toArray(new String[0]));
        if (nbf != null) {
            b.notBefore(nbf * 1000L);
        }
        if (iat != null) {
            b.issuedAt(iat * 1000L);
        }
        for (Map.Entry<String, Object> e : p.entrySet()) {
            if (!JwtClaims.REGISTERED.contains(e.getKey()) && e.getValue() != null) {
                b.raw(e.getKey(), e.getValue());
            }
        }
        return b.build();
    }

    private static byte[] decode(String part) throws JwtException {
        try {
            return Base64Url.decode(part);
        } catch (IllegalArgumentException e) {
            throw fail(JwtException.Reason.MALFORMED, "a token part is not canonical base64url");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(byte[] json, String what) throws JwtException {
        Object v;
        try {
            v = MiniJson.parse(json, MiniJson.Limits.claims());
        } catch (RuntimeException e) {
            throw fail(JwtException.Reason.MALFORMED, "the token " + what + " is not strict JSON");
        }
        if (!(v instanceof Map)) {
            throw fail(JwtException.Reason.MALFORMED, "the token " + what + " is not a JSON object");
        }
        return (Map<String, Object>) v;
    }

    /** A NumericDate claim: absent, or a non-negative integer small enough to hold in milliseconds. */
    private static @Nullable Long time(Map<String, Object> p, String name) throws JwtException {
        Object v = p.get(name);
        if (v == null && !p.containsKey(name)) {
            return null;
        }
        if (!(v instanceof Long) || (Long) v < 0 || (Long) v > MAX_SECONDS) {
            throw fail(JwtException.Reason.MALFORMED, "the " + name + " claim is not an integer NumericDate");
        }
        return (Long) v;
    }

    private static @Nullable String string(Map<String, Object> p, String name) throws JwtException {
        Object v = p.get(name);
        if (v == null && !p.containsKey(name)) {
            return null;
        }
        if (!(v instanceof String)) {
            throw fail(JwtException.Reason.MALFORMED, "the " + name + " claim is not a string");
        }
        return (String) v;
    }

    private static List<String> audience(@Nullable Object v) throws JwtException {
        List<String> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        if (v instanceof String) {
            out.add((String) v);
            return out;
        }
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (!(o instanceof String)) {
                    throw fail(JwtException.Reason.MALFORMED, "the aud claim is not a string list");
                }
                out.add((String) o);
            }
            return out;
        }
        throw fail(JwtException.Reason.MALFORMED, "the aud claim is not a string or a string list");
    }

    private static JwtException fail(JwtException.Reason reason, String message) {
        return new JwtException(reason, message);
    }

    /** The key set this signs and verifies with. */
    public JwtKeys getKeys() {
        return keys;
    }

    /** The configured leeway in milliseconds. */
    public long getLeeway() {
        return leeway;
    }

    /** The configured maximum token size in bytes. */
    public int getMaxTokenBytes() {
        return maxTokenBytes;
    }

    @Override
    public String toString() {
        return "Jwt{HS256, keys=" + keys + ", leeway=" + leeway + "ms}";
    }

    /** Configures a {@link Jwt}. */
    public static final class Builder {
        private final JwtKeys keys;
        private @Nullable String issuer;
        private @Nullable String audience;
        private long leeway = DEFAULT_LEEWAY_MILLIS;
        private int maxTokenBytes = DEFAULT_MAX_TOKEN_BYTES;
        private TimeSource time = TimeSource.system();

        private Builder(JwtKeys keys) {
            this.keys = keys;
        }

        /** The {@code iss} written into tokens without one and required on verification; none by default. */
        public Builder issuer(@Nullable String issuer) {
            this.issuer = issuer;
            return this;
        }

        /** The {@code aud} written into tokens without one and required on verification; none by default. */
        public Builder audience(@Nullable String audience) {
            this.audience = audience;
            return this;
        }

        /**
         * The clock skew allowed on {@code exp}, {@code nbf} and {@code iat}, in milliseconds: 60 seconds by default;
         * {@link IllegalArgumentException} below 0 or above 5 minutes.
         */
        // @req SEC-TOKEN-003
        public Builder leeway(long millis) {
            if (millis < 0 || millis > MAX_LEEWAY_MILLIS) {
                throw new IllegalArgumentException("a leeway lies between 0 and " + MAX_LEEWAY_MILLIS + " ms: "
                        + millis);
            }
            this.leeway = millis;
            return this;
        }

        /** The largest token accepted or signed, in bytes: 8 KiB by default; at least 64. */
        // @req SEC-TOKEN-003
        public Builder maxTokenBytes(int bytes) {
            if (bytes < 64) {
                throw new IllegalArgumentException("the maximum token size is at least 64 bytes: " + bytes);
            }
            this.maxTokenBytes = bytes;
            return this;
        }

        /** The clock of {@code iat} and of the time checks; {@link TimeSource#system()} by default. */
        public Builder timeSource(TimeSource time) {
            this.time = Objects.requireNonNull(time, "time");
            return this;
        }

        /** The {@link Jwt}. */
        public Jwt build() {
            return new Jwt(this);
        }
    }
}
