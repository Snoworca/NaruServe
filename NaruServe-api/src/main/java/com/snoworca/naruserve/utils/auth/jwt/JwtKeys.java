package com.snoworca.naruserve.utils.auth.jwt;

import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A key set addressed by {@code kid}: exactly one signing key, which new tokens are signed with and whose kid goes in
 * their header, and zero or more verification keys, which tokens signed earlier still verify with. Rotating a key is
 * "add the new key as the signing key, keep the old one as a verification key until every token it signed has
 * expired, then drop it". {@link #toString()} never shows a key. Express counterpart: the array of keys a JWKS
 * endpoint publishes, kept in memory.
 *
 * <pre>{@code
 * JwtKeys keys = JwtKeys.builder()
 *     .signingKey("k2", newKey)
 *     .verificationKey("k1", oldKey)
 *     .build();
 * }</pre>
 */
// @req SEC-TOKEN-004
public final class JwtKeys {

    private final String signingKid;
    private final Map<String, byte[]> keys;

    private JwtKeys(String signingKid, Map<String, byte[]> keys) {
        this.signingKid = signingKid;
        this.keys = keys;
    }

    /** A set of one signing key under {@code kid}. */
    // @req SEC-TOKEN-004
    public static JwtKeys of(String kid, byte[] key) {
        return builder().signingKey(kid, key).build();
    }

    /**
     * A set of one signing key whose kid is derived from the key itself (the first 8 base64url characters of its
     * SHA-256), so the same key gets the same kid on every node and after every restart.
     */
    // @req SEC-TOKEN-004
    public static JwtKeys of(byte[] key) {
        return of(derivedKid(key), key);
    }

    /** A builder; {@link Builder#build()} needs exactly one signing key. */
    // @req SEC-TOKEN-004
    public static Builder builder() {
        return new Builder();
    }

    private static String derivedKid(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return Base64Url.encode(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The kid of the signing key. */
    public String getSigningKid() {
        return signingKid;
    }

    /** A copy of the signing key. */
    public byte[] getSigningKey() {
        return keys.get(signingKid).clone();
    }

    /** A copy of the key a token with header {@code kid} verifies with (the signing key included), or null. */
    // @req SEC-TOKEN-004
    public byte @Nullable [] verificationKey(String kid) {
        byte[] k = keys.get(kid);
        return k == null ? null : k.clone();
    }

    /** Every kid of the set, the signing kid first. */
    public Set<String> getKids() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(keys.keySet()));
    }

    @Override
    public String toString() {
        return "JwtKeys{signing=" + signingKid + ", kids=" + keys.keySet() + "}";
    }

    /** Collects the keys of a {@link JwtKeys}. */
    public static final class Builder {
        private @Nullable String signingKid;
        private int signingKeys;
        private final Map<String, byte[]> keys = new LinkedHashMap<>();

        private Builder() {
        }

        /** The key new tokens are signed with; a set takes exactly one. */
        // @req SEC-TOKEN-004
        public Builder signingKey(String kid, byte[] key) {
            put(kid, key);
            signingKeys++;
            if (signingKid == null) {
                signingKid = kid;
            }
            return this;
        }

        /** A key that tokens signed earlier still verify with. */
        // @req SEC-TOKEN-004
        public Builder verificationKey(String kid, byte[] key) {
            put(kid, key);
            return this;
        }

        private void put(String kid, byte[] key) {
            Objects.requireNonNull(kid, "kid");
            Objects.requireNonNull(key, "key");
            if (kid.isEmpty() || kid.length() > 64) {
                throw new IllegalArgumentException("a kid has 1 to 64 characters");
            }
            for (int i = 0; i < kid.length(); i++) {
                char c = kid.charAt(i);
                if (c < 0x21 || c > 0x7e || c == '"' || c == '\\') {
                    throw new IllegalArgumentException("a kid is printable ASCII without quotes or backslashes");
                }
            }
            if (key.length == 0) {
                throw new IllegalArgumentException("key " + kid + " is empty");
            }
            if (keys.containsKey(kid)) {
                throw new IllegalArgumentException("kid " + kid + " is used twice");
            }
            keys.put(kid, key.clone());
        }

        /** The set; {@link IllegalStateException} unless exactly one signing key was given. */
        // @req SEC-TOKEN-004
        public JwtKeys build() {
            String kid = signingKid;
            if (kid == null || signingKeys != 1) {
                throw new IllegalStateException("a key set needs exactly one signing key; it has " + signingKeys);
            }
            Map<String, byte[]> ordered = new LinkedHashMap<>();
            ordered.put(kid, keys.get(kid));
            for (Map.Entry<String, byte[]> e : keys.entrySet()) {
                if (!e.getKey().equals(kid)) {
                    ordered.put(e.getKey(), e.getValue());
                }
            }
            return new JwtKeys(kid, ordered);
        }
    }
}
