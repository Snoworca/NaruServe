package com.snoworca.naruserve.utils.auth.internal;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 over one key, safe to call from many threads: {@link Mac} is not thread-safe, so every call takes its
 * own instance and nothing mutable is shared.
 */
// @req SEC-TOKEN-001
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    /** A signer over a copy of {@code key}, which must not be empty. */
    // @req SEC-TOKEN-001
    public HmacSigner(byte[] key) {
        if (key.length == 0) {
            throw new IllegalArgumentException("HMAC key must not be empty");
        }
        this.key = new SecretKeySpec(key.clone(), ALGORITHM);
        mac(); // fail now, not on the first request, when the platform lacks the algorithm
    }

    /** The 32-byte HMAC-SHA256 of {@code data}. */
    // @req SEC-TOKEN-001
    public byte[] sign(byte[] data) {
        return mac().doFinal(data);
    }

    private Mac mac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** True when {@code signature} is the HMAC of {@code data}, compared in constant time. */
    // @req SEC-TOKEN-001
    public boolean verify(byte[] data, byte[] signature) {
        return ConstantTime.equals(sign(data), signature);
    }

    @Override
    public String toString() {
        return "HmacSigner{HmacSHA256}";
    }
}
