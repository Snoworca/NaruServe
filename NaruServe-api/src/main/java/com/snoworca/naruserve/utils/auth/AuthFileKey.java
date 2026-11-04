package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import com.snoworca.naruserve.utils.auth.internal.SecretBox;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The master key of the auth file stores (SEC-IAM-008): when one is given to {@code new FileUserStore(file, key)} and
 * {@code KeySources.fromFile(file, key)}, the secret values they write (each users-file {@code passwordHash} and the
 * signing key) are stored as {@code enc(...)} with AES-256-GCM under a key derived per purpose. Make one with 32
 * random bytes, for example {@code openssl rand -base64 32}, and keep it outside the data directory. The key never
 * appears in {@link #toString()}, a message, a log line or a file, and this class is not {@code Serializable}. Key
 * rotation is not supported in 0.11.0; the payload's version byte reserves room for it. Express counterpart: the
 * {@code secret} of an encrypted session store, read from {@code process.env}.
 *
 * <pre>{@code
 * AuthFileKey key = AuthFileKey.fromEnv();      // NARU_AUTH_FILE_KEY, or null when unset
 * Auth auth = Auth.builder(KeySources.fromFile(new File(dataDir, "naru-auth.key"), key))
 *     .users(new FileUserStore(new File(dataDir, "users.json"), key))
 *     .build();
 * }</pre>
 */
// @req SEC-IAM-008
// @req FR-IAM-015
public final class AuthFileKey {

    /** The environment variable {@link #fromEnv()} reads. */
    public static final String ENV_VARIABLE = "NARU_AUTH_FILE_KEY";

    /** The fewest key bytes accepted. */
    public static final int MIN_BYTES = 32;

    private static final String RULE = "an AuthFileKey is at least 32 bytes, given as standard or URL-safe base64 "
            + "with or without padding (for example the output of openssl rand -base64 32)";

    static {
        SecretBox.install(new BoxAccess());
    }

    private final SecretBox box;

    private AuthFileKey(byte[] key) {
        this.box = new SecretBox(key);
    }

    /** A key over a copy of {@code key}; {@link IllegalArgumentException} for fewer than 32 bytes. */
    // @req SEC-IAM-008
    public static AuthFileKey of(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length < MIN_BYTES) {
            throw new IllegalArgumentException(RULE + "; these key bytes are fewer");
        }
        return new AuthFileKey(key);
    }

    /**
     * The key whose bytes {@code base64} encodes (standard or URL-safe alphabet, padding optional, surrounding
     * whitespace ignored); {@link IllegalArgumentException} stating the rule, without the input, when it does not
     * decode to at least 32 bytes.
     */
    // @req SEC-IAM-008
    public static AuthFileKey of(String base64) {
        Objects.requireNonNull(base64, "base64");
        byte[] bytes = decode(base64.trim());
        if (bytes == null || bytes.length < MIN_BYTES) { // OPS-AUTH-031 AC-3: the size, never the value
            int n = bytes == null ? -1 : bytes.length;
            if (bytes != null) {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
            throw new IllegalArgumentException(RULE + (n < 0 ? "; the value is not base64" : "; it decodes to " + n
                    + " bytes"));
        }
        try {
            return new AuthFileKey(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * The key in the environment variable {@link #ENV_VARIABLE}, read now; null when it is unset or empty, and an
     * {@link IllegalArgumentException} naming the variable (never its value) when it does not hold a valid key. This
     * explicit call is the app's choice to read the environment.
     */
    // @req FR-IAM-015
    public static @Nullable AuthFileKey fromEnv() {
        return fromEnvValue(System.getenv(ENV_VARIABLE));
    }

    /** {@link #fromEnv()} over a given value; package-private for the tests. */
    static @Nullable AuthFileKey fromEnvValue(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return of(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the environment variable " + ENV_VARIABLE + " does not hold a valid "
                    + "AuthFileKey: " + RULE);
        }
    }

    /** The bytes of standard or URL-safe base64 with optional padding; null when it is not such a text. */
    private static byte @Nullable [] decode(String text) {
        String t = text;
        int pad = 0;
        while (t.endsWith("=") && pad < 2) {
            t = t.substring(0, t.length() - 1);
            pad++;
        }
        if (pad > 0 && (t.length() + pad) % 4 != 0) {
            return null;
        }
        if (t.isEmpty()) {
            return null;
        }
        StringBuilder url = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            url.append(c == '+' ? '-' : c == '/' ? '_' : c);
        }
        try {
            return Base64Url.decode(url.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A fixed text: the key is never shown. */
    @Override
    public String toString() {
        return "AuthFileKey{hidden}";
    }

    /** The bridge {@link SecretBox#of(AuthFileKey)} uses; the key bytes stay inside the box. */
    private static final class BoxAccess implements SecretBox.Access {
        @Override
        public SecretBox boxOf(AuthFileKey key) {
            return key.box;
        }
    }
}
