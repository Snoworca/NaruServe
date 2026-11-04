package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The purpose keys of one key set (00 R8, R34): every kid's secret is turned into one key per purpose with
 * HMAC-SHA256(secret, label), so an access token, a refresh token and a device token are each signed with their own
 * key and a token of one purpose never verifies as another, even when the header {@code typ} is forged. Keys are
 * derived for every kid of the set, the verification-only ones included, so tokens signed before a rotation still
 * verify under their old kid.
 */
// @req SEC-TOKEN-005
public final class TokenKeys {

    /** The derivation label of access-token keys. */
    public static final String ACCESS = "naru-access";
    /** The derivation label of refresh-token keys. */
    public static final String REFRESH = "naru-refresh";
    /** The derivation label of device-token keys (00 R34). */
    public static final String DEVICE = "naru-device";
    /** The derivation label of the device-name fingerprint key (00 R34). */
    public static final String DEVICE_FINGERPRINT = "naru-device-fp";
    /** The derivation label of the CSRF-token key (00 R29 (e), 05 §7 item 1). */
    public static final String CSRF = "naru-csrf";

    private TokenKeys() {
    }

    /** HMAC-SHA256({@code secret}, {@code label}). */
    // @req SEC-TOKEN-005
    public static byte[] derive(byte[] secret, String label) {
        return new HmacSigner(secret).sign(label.getBytes(StandardCharsets.US_ASCII));
    }

    /** The key set of {@code purpose}: the same kids and signing kid, each key derived from that kid's secret. */
    // @req SEC-TOKEN-005
    public static JwtKeys purpose(JwtKeys raw, String purpose) {
        JwtKeys.Builder b = JwtKeys.builder();
        for (String kid : raw.getKids()) {
            byte[] secret = raw.verificationKey(kid);
            if (secret == null) {
                continue;
            }
            byte[] key = derive(secret, purpose);
            Arrays.fill(secret, (byte) 0);
            if (kid.equals(raw.getSigningKid())) {
                b.signingKey(kid, key);
            } else {
                b.verificationKey(kid, key);
            }
        }
        return b.build();
    }

    /**
     * The key of {@code purpose} for {@code kid} (the kid named in a token header), or null when the set has no such
     * kid; for the device-name fingerprint and the CSRF tokens, which are HMACs rather than JWTs signed through
     * {@link #purpose}.
     */
    // @req SEC-TOKEN-005
    public static byte @Nullable [] keyFor(JwtKeys raw, String kid, String purpose) {
        byte[] secret = raw.verificationKey(kid);
        if (secret == null) {
            return null;
        }
        try {
            return derive(secret, purpose);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }
}
