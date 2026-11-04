package com.snoworca.naruserve.utils.auth.password;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import com.snoworca.naruserve.utils.auth.internal.ConstantTime;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The shipped {@link PasswordHasher}s for moving existing accounts to PBKDF2: {@link #chain} reads several stored
 * formats through one hasher, {@link #bcrypt()}, {@link #digest} and {@link #saltedDigest} read the legacy formats.
 *
 * <pre>{@code
 * Auth auth = Auth.builder(secret)
 *     .passwordHasher(PasswordHashers.chain(new Pbkdf2PasswordHasher(600_000),
 *         PasswordHashers.bcrypt(),
 *         PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX)))
 *     .build();
 * }</pre>
 */
// @req SEC-TOKEN-008
// @req FR-TOKEN-003
public final class PasswordHashers {

    /** How many distinct unknown prefixes a chain logs; more are not remembered (and not logged). */
    private static final int MAX_LOGGED_PREFIXES = 64;

    private PasswordHashers() {
    }

    /** A bcrypt verifier with the default limits (stored cost at most 14). */
    // @req SEC-TOKEN-008
    public static BcryptPasswordHasher bcrypt() {
        return BcryptPasswordHasher.builder().build();
    }

    /**
     * One hasher over several formats, for {@code .passwordHasher(...)}: a stored string goes to the first member
     * (primary first, then {@code legacy} in order) whose {@code accepts} is true. {@code hash} always uses
     * {@code primary}; {@code needsRehash} is true when the accepting member is not {@code primary} (or
     * {@code primary.needsRehash} says so), so the next successful login moves the account to {@code primary}. A
     * stored string no member accepts fails, and its first characters (at most 4, never the whole string) are logged
     * as one ERROR per distinct prefix to a {@link ConsoleLogger}; {@link #chain(Logger, PasswordHasher, PasswordHasher...)}
     * picks the logger.
     */
    // @req FR-TOKEN-003
    public static PasswordHasher chain(PasswordHasher primary, PasswordHasher... legacy) {
        return new Chain(new ConsoleLogger(), primary, legacy);
    }

    /** {@link #chain(PasswordHasher, PasswordHasher...)} logging unknown prefixes to {@code logger}. */
    // @req FR-TOKEN-003
    public static PasswordHasher chain(Logger logger, PasswordHasher primary, PasswordHasher... legacy) {
        return new Chain(Objects.requireNonNull(logger, "logger"), primary, legacy);
    }

    /**
     * A verifier of unsalted legacy digests stored as {@code <prefix>$<digest>}, for example
     * {@code legacy-md5$5f4dcc3b5aa765d61d8327deb882cf99} from {@code digest("legacy-md5", "MD5", DigestEncoding.HEX)}.
     * {@code algorithm} is a {@link MessageDigest} name ({@code "MD5"}, {@code "SHA-1"}, {@code "SHA-256"}); the
     * password is digested as UTF-8 and compared in constant time. A store whose column has no prefix adds it when it
     * reads the row. {@code hash} throws {@link UnsupportedOperationException} (no new weak hashes);
     * {@code needsRehash} is always true. {@code prefix} may end with one {@code $}, which is not doubled.
     */
    // @req FR-TOKEN-003
    public static PasswordHasher digest(String prefix, String algorithm, DigestEncoding encoding) {
        return new Digest(prefix, algorithm, encoding, null);
    }

    /**
     * A verifier of salted legacy digests stored as {@code <prefix>$<salt>$<digest>}, for example
     * {@code legacy-sha256$Xy7q$9f86d0...}: the digest of the salt text and the password, both UTF-8, in
     * {@code order}. The salt is the text between the prefix and the last {@code $}, used as written (a store with a
     * separate salt column joins the two when it reads the row). Otherwise as {@link #digest}.
     */
    // @req FR-TOKEN-003
    public static PasswordHasher saltedDigest(String prefix, String algorithm, DigestEncoding encoding,
            SaltOrder order) {
        return new Digest(prefix, algorithm, encoding, Objects.requireNonNull(order, "order"));
    }

    /** The chain of {@link #chain}. */
    // @req FR-TOKEN-003
    private static final class Chain implements PasswordHasher {
        private final Logger logger;
        private final PasswordHasher primary;
        private final List<PasswordHasher> members;
        private final ConcurrentHashMap<String, Boolean> logged = new ConcurrentHashMap<>();

        Chain(Logger logger, PasswordHasher primary, PasswordHasher[] legacy) {
            this.logger = logger;
            this.primary = Objects.requireNonNull(primary, "primary");
            List<PasswordHasher> all = new ArrayList<>();
            all.add(primary);
            for (PasswordHasher h : Objects.requireNonNull(legacy, "legacy")) {
                all.add(Objects.requireNonNull(h, "a legacy hasher is null"));
            }
            this.members = Collections.unmodifiableList(all);
        }

        private @Nullable PasswordHasher member(String stored) {
            for (PasswordHasher h : members) {
                if (h.accepts(stored)) {
                    return h;
                }
            }
            return null;
        }

        /** True when a member accepts {@code stored}; otherwise false, and the unknown prefix is logged once. */
        @Override
        public boolean accepts(String stored) {
            if (member(stored) != null) {
                return true;
            }
            unknown(stored);
            return false;
        }

        @Override
        public String hash(char[] password) {
            return primary.hash(password);
        }

        @Override
        public boolean verify(char[] password, String stored) {
            PasswordHasher h = member(stored);
            if (h == null) {
                unknown(stored);
                return false;
            }
            return h.verify(password, stored);
        }

        @Override
        public boolean needsRehash(String stored) {
            PasswordHasher h = member(stored);
            return h != null && (h != primary || primary.needsRehash(stored));
        }

        private void unknown(String stored) {
            // at most 4 characters and never the whole string
            String shown = stored.substring(0, Math.min(4, Math.max(0, stored.length() - 1)));
            if (logged.size() >= MAX_LOGGED_PREFIXES || logged.putIfAbsent(shown, Boolean.TRUE) != null) {
                return;
            }
            logger.error("auth: no password hasher of the chain accepts a stored hash starting with \"" + shown
                    + "\"; that login fails (add a hasher for the format to PasswordHashers.chain(...); "
                    + "logged once per prefix)");
        }

        @Override
        public String toString() {
            return "PasswordHashers.chain" + members;
        }
    }

    /** The verifier of {@link #digest} ({@code order} null) and {@link #saltedDigest}. */
    // @req FR-TOKEN-003
    private static final class Digest implements PasswordHasher {
        private final String prefix;
        private final String algorithm;
        private final DigestEncoding encoding;
        private final @Nullable SaltOrder order;
        private final int length;

        Digest(String prefix, String algorithm, DigestEncoding encoding, @Nullable SaltOrder order) {
            Objects.requireNonNull(prefix, "prefix");
            String name = prefix.endsWith("$") ? prefix.substring(0, prefix.length() - 1) : prefix;
            if (name.isEmpty() || name.indexOf('$') >= 0) {
                throw new IllegalArgumentException("a digest prefix is a non-empty name without '$', such as "
                        + "\"legacy-md5\": " + prefix);
            }
            this.prefix = name + "$";
            this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
            this.encoding = Objects.requireNonNull(encoding, "encoding");
            this.order = order;
            this.length = newDigest().getDigestLength();
        }

        private MessageDigest newDigest() {
            try {
                return MessageDigest.getInstance(algorithm);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalArgumentException("no MessageDigest " + algorithm, e);
            }
        }

        @Override
        public boolean accepts(String stored) {
            return stored.startsWith(prefix);
        }

        /** Always throws {@link UnsupportedOperationException}: a legacy digest only verifies. */
        @Override
        public String hash(char[] password) {
            throw new UnsupportedOperationException(prefix + " hashes are verified only; new hashes come from the "
                    + "chain's primary hasher");
        }

        @Override
        public boolean verify(char[] password, String stored) {
            if (!accepts(stored) || password.length == 0) {
                return false;
            }
            String rest = stored.substring(prefix.length());
            String salt = null;
            if (order != null) {
                int cut = rest.lastIndexOf('$');
                if (cut < 0) {
                    return false;
                }
                salt = rest.substring(0, cut);
                rest = rest.substring(cut + 1);
            } else if (rest.indexOf('$') >= 0) {
                return false;
            }
            byte[] expected = decode(rest);
            if (expected == null || length > 0 && expected.length != length) {
                return false;
            }
            byte[] pw = utf8(password);
            try {
                MessageDigest md = newDigest();
                byte[] saltBytes = salt == null ? null : salt.getBytes(StandardCharsets.UTF_8);
                if (saltBytes != null && order == SaltOrder.SALT_THEN_PASSWORD) {
                    md.update(saltBytes);
                }
                md.update(pw);
                if (saltBytes != null && order == SaltOrder.PASSWORD_THEN_SALT) {
                    md.update(saltBytes);
                }
                return ConstantTime.equals(md.digest(), expected);
            } finally {
                Arrays.fill(pw, (byte) 0);
            }
        }

        /** Always true: a legacy digest account moves to the primary hasher on its next successful login. */
        @Override
        public boolean needsRehash(String stored) {
            return true;
        }

        private byte @Nullable [] decode(String text) {
            if (encoding == DigestEncoding.HEX) {
                if (text.length() % 2 != 0) {
                    return null;
                }
                byte[] out = new byte[text.length() / 2];
                for (int i = 0; i < out.length; i++) {
                    int hi = hex(text.charAt(2 * i));
                    int lo = hex(text.charAt(2 * i + 1));
                    if (hi < 0 || lo < 0) {
                        return null;
                    }
                    out[i] = (byte) (hi << 4 | lo);
                }
                return out;
            }
            String t = text;
            while (t.endsWith("=")) {
                t = t.substring(0, t.length() - 1);
            }
            if (t.indexOf('-') >= 0 || t.indexOf('_') >= 0) {
                return null;
            }
            try {
                return Base64Url.decode(t.replace('+', '-').replace('/', '_'));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static int hex(char c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            return c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
        }

        private static byte[] utf8(char[] password) {
            ByteBuffer buf = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
            byte[] out = new byte[buf.remaining()];
            buf.get(out);
            if (buf.hasArray()) {
                Arrays.fill(buf.array(), (byte) 0);
            }
            return out;
        }

        @Override
        public String toString() {
            return (order == null ? "digest(" : "saltedDigest(") + prefix + ", " + algorithm + ", " + encoding
                    + (order == null ? "" : ", " + order) + ")";
        }
    }
}
