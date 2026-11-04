package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.AuthErrorCode;
import com.snoworca.naruserve.utils.auth.AuthRejection;
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;
import com.snoworca.naruserve.utils.auth.jwt.JwtException;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import com.snoworca.naruserve.utils.auth.spi.TokenCodec;
import com.snoworca.naruserve.Logger;
import org.jspecify.annotations.Nullable;
import java.security.SecureRandom;

/**
 * The access tokens of one Auth, through the configured token codec (the standalone HS256 {@code Jwt} by default): header {@code typ} {@code at+jwt}, claims
 * {@code sub}, {@code ver}, {@code fam}, {@code iat}, {@code exp} and {@code jti} (00 R2, R8). As the
 * {@link TokenVerifier} behind the transport reading, a token that does not verify is 401 {@code invalid_token}, and
 * the reason is never shown. Signed with the access-purpose keys of {@link TokenKeys} (00 R8), so a refresh or
 * device token never verifies here. {@code TokenService} issues them together with the refresh tokens.
 */
// @req SEC-TOKEN-003
public final class AccessTokens implements TokenVerifier {

    /** The header {@code typ} of an access token. */
    public static final String TYPE = "at+jwt";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TokenCodec codec;
    private final TimeSource time;
    private final @Nullable Logger logger;

    /** Access tokens signed and verified with {@code keys} on {@code time} (the default HS256 codec). */
    // @req SEC-TOKEN-003
    // @req SEC-TOKEN-005
    public AccessTokens(JwtKeys keys, TimeSource time) {
        this(new Hs256TokenCodec(keys, time), time, null);
    }

    /**
     * Access tokens encoded and decoded by {@code codec} on {@code time}; a decode failure is logged at debug level on
     * {@code logger} (when given) and answered 401 {@code invalid_token} without its reason.
     */
    // @req IR-TOKEN-002
    public AccessTokens(TokenCodec codec, TimeSource time, @Nullable Logger logger) {
        this.codec = codec;
        this.time = time;
        this.logger = logger;
    }

    /** A new access token of {@code userId} at token version {@code ver}, valid for {@code ttlMillis}. */
    // @req SEC-TOKEN-003
    public String issue(String userId, long ver, String familyId, long ttlMillis) {
        long now = time.nowMillis();
        JwtClaims claims = JwtClaims.builder().type(TYPE).subject(userId).issuedAt(now).expiresAt(now + ttlMillis)
                .tokenId(random()).claim("ver", ver).claim("fam", familyId).build();
        try {
            return codec.encode(claims);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("the token codec failed to encode an access token", e);
        }
    }

    /** A random 128-bit base64url id for {@code jti} and {@code fam}. */
    public static String random() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64Url.encode(b);
    }

    // @req SEC-TOKEN-003
    @Override
    public Credential verify(String token) {
        JwtClaims c = claims(token);
        String sub = c.getSubject();
        Long ver = c.getLong("ver");
        if (sub == null || ver == null) {
            throw invalid();
        }
        return new Credential(sub, ver, c.getString("fam"), c.getExpiresAt());
    }

    /** The claims of a valid access token; {@code AuthRejection(401, "invalid_token")} otherwise. */
    // @req SEC-TOKEN-005
    public JwtClaims claims(String token) {
        JwtClaims c;
        if (token.trim().isEmpty()) { // OPS-AUTH-031 AC-2
            throw new AuthRejection(AuthErrorCode.INVALID_TOKEN.getStatus(), AuthErrorCode.INVALID_TOKEN.getCode(),
                    "the Bearer value is empty; send Authorization: Bearer <the accessToken of the login answer>");
        }
        if (token.startsWith("nk_")) { // OPS-AUTH-021 AC-8: an API key sent as a Bearer token
            throw new AuthRejection(AuthErrorCode.INVALID_TOKEN.getStatus(), AuthErrorCode.INVALID_TOKEN.getCode(),
                    "the token is an API key, not an access token; send API keys as Authorization: ApiKey <key> (or "
                            + "the scheme set with .apiKeyScheme(...))");
        }
        try {
            c = codec.decode(token, TYPE);
        } catch (JwtException e) {
            DeviceTokens devices = deviceTokens;
            if (e.getReason() != JwtException.Reason.EXPIRED && devices != null && devices.isDeviceToken(token)) {
                // OPS-AUTH-026 AC-3
                throw new AuthRejection(AuthErrorCode.INVALID_TOKEN.getStatus(), AuthErrorCode.INVALID_TOKEN.getCode(),
                        "the token is not valid as an access token: this is a device token, which goes only to the "
                                + "login, in the Naru-Device header; send the accessToken of the login answer");
            }
            if (e.getReason() != JwtException.Reason.EXPIRED && isRefreshToken(token)) { // OPS-AUTH-024 AC-4
                throw new AuthRejection(AuthErrorCode.INVALID_TOKEN.getStatus(), AuthErrorCode.INVALID_TOKEN.getCode(),
                        "the token is not valid as an access token: this is a refresh token, which goes only to POST "
                                + "/auth/refresh by default; send the accessToken of the login or refresh answer");
            }
            throw invalid(e.getReason());
        } catch (Exception e) {
            Logger l = logger;
            if (l != null) {
                l.debug("auth: the token codec refused an access token: " + e.getClass().getName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            }
            throw invalid();
        }
        if (!TYPE.equals(c.getType())) {
            throw invalid(); // a codec that ignored the expected type
        }
        return c;
    }

    private static AuthRejection invalid() {
        return invalid(null);
    }

    private volatile @Nullable DeviceTokens deviceTokens;

    /** The device tokens auth issues, so a device token sent as an access token is named. */
    public void deviceTokens(DeviceTokens d) {
        this.deviceTokens = d;
    }

    private boolean isRefreshToken(String token) {
        return decodes(token, RefreshTokens.TYPE);
    }

    private boolean decodes(String token, String type) {
        try {
            codec.decode(token, type);
            return true;
        } catch (Exception other) {
            return false;
        }
    }

    /** OPS-AUTH-021 AC-8: an expired token says so; any other refusal does not claim expiry. */
    private static AuthRejection invalid(JwtException.@Nullable Reason reason) {
        String why;
        if (reason == JwtException.Reason.EXPIRED) {
            why = "the token has expired; renew it with the refresh endpoint (POST /auth/refresh by default) or log in "
                    + "again";
        } else if (reason == JwtException.Reason.WRONG_TYPE) {
            why = "the token is not valid as an access token (a refresh token goes only to POST /auth/refresh by "
                    + "default); send the accessToken of the login answer";
        } else {
            why = "the token is not valid (malformed, changed, or signed with another key); send the accessToken of "
                    + "the login or refresh answer exactly as received";
        }
        return new AuthRejection(AuthErrorCode.INVALID_TOKEN.getStatus(), AuthErrorCode.INVALID_TOKEN.getCode(), why);
    }

    @Override
    public String toString() {
        return "AccessTokens{" + TYPE + "}";
    }
}
