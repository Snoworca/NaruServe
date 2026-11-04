package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.auth.AuthFileKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * The {@code enc(...)} form of the secret values the auth file stores write (SEC-IAM-008): {@code enc(<payload>)}
 * where payload is unpadded base64url of a format version byte (1), a random 96-bit IV from {@link SecureRandom}, the
 * AES-256-GCM ciphertext and its 128-bit tag. The AES key is derived per purpose from the master key as
 * HMAC-SHA256(master, {@code "naru-auth-file/v1/" + purpose}); the additional authenticated data binds the value to
 * its purpose and its record (the user id, or the key file name), so a value copied to another record or purpose
 * does not open. Only {@code javax.crypto}, {@code java.security} and {@link Base64Url} are used (Java 8 and Android
 * API 21). Messages never carry the value, the master key or a derived key.
 */
// @req SEC-IAM-008
public final class SecretBox {

    /** The purpose of the {@code passwordHash} of a users-file record; its record is the user id. */
    public static final String USERS_PASSWORD_HASH = "users.passwordHash";
    /** The purpose of the signing key in a {@code KeySources.fromFile} key file; its record is the file name. */
    public static final String SIGNING_KEY = "signing-key";
    /** The payload format version this release writes and reads. */
    public static final int VERSION = 1;

    static final String LABEL_PREFIX = "naru-auth-file/v1/";
    static final int IV_BYTES = 12;
    static final int TAG_BITS = 128;
    private static final String PREFIX = "enc(";
    private static final String SUFFIX = ")";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** How {@link #of(AuthFileKey)} reaches the box an {@link AuthFileKey} holds. */
    @FunctionalInterface
    public interface Access {
        /** The box of {@code key}. */
        SecretBox boxOf(AuthFileKey key);
    }

    private static volatile @Nullable Access access;

    private final byte[] master;
    /** The derived key of each purpose, made once. */
    private final ConcurrentMap<String, byte[]> subkeys = new ConcurrentHashMap<>();

    /** A box over a copy of {@code master}, at least 32 bytes. */
    // @req SEC-IAM-008
    public SecretBox(byte[] master) {
        if (master.length < AuthFileKey.MIN_BYTES) {
            throw new IllegalArgumentException("a master key has at least " + AuthFileKey.MIN_BYTES + " bytes");
        }
        this.master = master.clone();
    }

    /**
     * Installs the access of {@link AuthFileKey}: a class nested in it (compared by identity, so a minified app that
     * renames classes still passes); anything else, or a second install, is refused.
     */
    public static synchronized void install(Access a) {
        if (a.getClass().getDeclaringClass() != AuthFileKey.class || access != null) {
            throw new IllegalArgumentException("only AuthFileKey installs the box access");
        }
        access = a;
    }

    /** The box {@code key} holds. */
    // @req SEC-IAM-008
    public static SecretBox of(AuthFileKey key) {
        Objects.requireNonNull(key, "key");
        Access a = access; // an AuthFileKey instance exists, so its class initialized and installed the access
        if (a == null) {
            throw new IllegalStateException("the AuthFileKey box access is not installed");
        }
        return a.boxOf(key);
    }

    /** True when {@code value} has the {@code enc(...)} shape (it may still fail to open). */
    public static boolean isSealed(@Nullable String value) {
        return value != null && value.startsWith(PREFIX) && value.endsWith(SUFFIX);
    }

    /** The AES-256 key of {@code purpose}: HMAC-SHA256 of the label under the master key; a copy. */
    // @req SEC-IAM-008
    byte[] subkey(String purpose) {
        byte[] k = subkeys.get(purpose);
        if (k == null) {
            k = derive(purpose);
            byte[] raced = subkeys.putIfAbsent(purpose, k);
            if (raced != null) {
                k = raced;
            }
        }
        return k.clone();
    }

    private byte[] derive(String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return mac.doFinal((LABEL_PREFIX + purpose).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static byte[] aad(String purpose, String record) {
        return (LABEL_PREFIX + purpose + '\u0000' + record).getBytes(StandardCharsets.UTF_8);
    }

    /** {@code enc(...)} of {@code plain} for {@code purpose} and {@code record}, under a fresh random IV. */
    // @req SEC-IAM-008
    public String seal(String purpose, String record, byte[] plain) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        byte[] key = subkey(purpose);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(purpose, record));
            byte[] sealed = c.doFinal(plain);
            byte[] payload = new byte[1 + IV_BYTES + sealed.length];
            payload[0] = (byte) VERSION;
            System.arraycopy(iv, 0, payload, 1, IV_BYTES);
            System.arraycopy(sealed, 0, payload, 1 + IV_BYTES, sealed.length);
            return PREFIX + Base64Url.encode(payload) + SUFFIX;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM/NoPadding is not available", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * The plain bytes of an {@code enc(...)} value written for {@code purpose} and {@code record}; an
     * {@link IllegalArgumentException} naming what is wrong (not an enc value, not base64url, too short, an unknown
     * format version, or a value that does not authenticate: tampered, another master key, or another record) when
     * it does not open. The message never holds the value.
     */
    // @req SEC-IAM-008
    public byte[] open(String purpose, String record, String value) {
        if (!isSealed(value)) {
            throw new IllegalArgumentException("is not an enc(...) value");
        }
        byte[] payload;
        try {
            payload = Base64Url.decode(value.substring(PREFIX.length(), value.length() - SUFFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("is an enc(...) value that is not valid base64url");
        }
        if (payload.length < 1 + IV_BYTES + TAG_BITS / 8) {
            throw new IllegalArgumentException("is an enc(...) value too short to hold a version, an IV and a tag");
        }
        int version = payload[0] & 0xff;
        if (version != VERSION) {
            throw new IllegalArgumentException("is an enc(...) value of unknown format version " + version
                    + "; this release reads version " + VERSION);
        }
        byte[] key = subkey(purpose);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, payload, 1, IV_BYTES));
            c.updateAAD(aad(purpose, record));
            return c.doFinal(payload, 1 + IV_BYTES, payload.length - 1 - IV_BYTES);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("is an enc(...) value that does not authenticate (it was changed, "
                    + "written under another AuthFileKey, or copied from another record)");
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("is an enc(...) value that does not open ("
                    + e.getClass().getSimpleName() + ")");
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "SecretBox{hidden}";
    }
}
