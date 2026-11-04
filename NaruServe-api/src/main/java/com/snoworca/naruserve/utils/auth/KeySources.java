package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.auth.internal.SecretBox;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import com.snoworca.naruserve.utils.auth.spi.KeySource;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The shipped {@link KeySource}s. {@code Auth.builder(String)} is {@code Auth.builder(KeySources.of(secret))}.
 * A missing secret is refused by {@code build()} in PROD with instructions to generate one; in DEV it becomes a
 * temporary key. Express counterpart: the {@code secret} option of {@code express-jwt}.
 *
 * <pre>{@code
 * Auth.builder(KeySources.fromFile(new File(dataDir, "naru-auth.key")));          // made once, kept across restarts
 * Auth.builder(KeySources.rotating(JwtKeys.builder()
 *     .signingKey("k2", newKey).verificationKey("k1", oldKey).build()));           // rotation
 * }</pre>
 */
// @req SEC-AUTH-001
// @req SEC-TOKEN-004
public final class KeySources {

    /** The size of a key {@link #fromFile(File)} makes. */
    public static final int GENERATED_KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private KeySources() {
    }

    /** The UTF-8 bytes of {@code secret}; null means "not set". */
    // @req SEC-AUTH-001
    public static KeySource of(@Nullable String secret) {
        return new Fixed(secret == null ? null : secret.getBytes(StandardCharsets.UTF_8), "the secret");
    }

    /** A copy of {@code key}. */
    // @req SEC-AUTH-001
    public static KeySource fromBytes(byte[] key) {
        return new Fixed(Objects.requireNonNull(key, "key").clone(), "the key bytes");
    }

    /**
     * The UTF-8 bytes of the environment variable {@code name}, read now; unset or empty counts as a missing secret,
     * which {@code build()} names in its error. This explicit call is the app's choice to read the environment.
     */
    // @req SEC-AUTH-001
    public static KeySource fromEnv(String name) {
        String value = System.getenv(Objects.requireNonNull(name, "name"));
        return new Fixed(value == null || value.isEmpty() ? null : value.getBytes(StandardCharsets.UTF_8),
                "environment variable " + name);
    }

    /**
     * The raw bytes of {@code file}. A missing file is created once with {@link #GENERATED_KEY_BYTES} random bytes from
     * {@link SecureRandom}, readable and writable only by its owner; an existing file is read and never overwritten
     * (overwriting would log every user out). Creators racing on one missing path (two auth builds, two processes on
     * one data directory) are serialized by the sibling lock file {@code .<name>.lock}, which stays in place, so they
     * all use the one key that ends up on disk. The file is read when auth is built. Exclude it from Android auto
     * backup, or two installations end up sharing a signing key.
     */
    // @req SEC-TOKEN-004
    public static KeySource fromFile(File file) {
        return fromFile(file, null);
    }

    /**
     * {@link #fromFile(File)} whose key file holds the signing key as {@code enc(...)} under {@code key} (SEC-IAM-008);
     * null keeps the raw bytes, as {@link #fromFile(File)} does. A raw key file read with a key is rewritten once,
     * encrypted, through the same staging-and-rename path (the key itself does not change, so issued tokens keep
     * verifying); an encrypted file needs its key, and one that does not open (changed, another key) fails the read
     * naming the file, without making a new key.
     */
    // @req FR-IAM-015
    // @req SEC-IAM-008
    public static KeySource fromFile(File file, @Nullable AuthFileKey key) {
        return new FileKey(Objects.requireNonNull(file, "file"), key);
    }

    /**
     * The key set {@code keys}: tokens are signed with its signing key and kid, and tokens carrying any of its kids
     * verify. Rotate by moving the old key to a verification key and adding a new signing key; drop the old key once
     * the refresh lifetime has passed.
     */
    // @req SEC-TOKEN-004
    public static KeySource rotating(final JwtKeys keys) {
        Objects.requireNonNull(keys, "keys");
        return new KeySource() {
            @Override
            public byte[] signingKey() {
                return keys.getSigningKey();
            }

            @Override
            public String signingKid() {
                return keys.getSigningKid();
            }

            @Override
            public JwtKeys keys() {
                return keys;
            }

            @Override
            public String toString() {
                return "KeySource{rotating " + keys + "}";
            }
        };
    }

    /** The key file of {@link #fromFile(File)}. */
    static final class FileKey implements KeySource {
        /** Serializes creators in this JVM: a {@link FileLock} is held per JVM, not per thread. */
        private static final Object CREATE_LOCK = new Object();
        /** The largest raw key file read. */
        private static final int MAX_KEY_FILE_BYTES = 4096;
        /** The largest enc(...) key file read: the encrypted form of the largest raw key, with room to spare. */
        private static final int MAX_SEALED_FILE_BYTES = 8192;

        private final File file;
        private final @Nullable AuthFileKey fileKey;
        private final @Nullable SecretBox box;
        private volatile Moves moves = Moves.REAL;

        /**
         * The renames and deletes of the key replacement. A package-private seam: the tests simulate a file system
         * whose rename does not replace an existing file and whose delete can fail (Windows) on POSIX.
         */
        interface Moves {
            boolean rename(File from, File to);

            boolean delete(File f);

            Moves REAL = new Moves() {
                @Override
                public boolean rename(File from, File to) {
                    return from.renameTo(to);
                }

                @Override
                public boolean delete(File f) {
                    return f.delete();
                }
            };
        }

        FileKey(File file, @Nullable AuthFileKey fileKey) {
            this.file = file;
            this.fileKey = fileKey;
            this.box = fileKey == null ? null : SecretBox.of(fileKey);
        }

        /** Where the key comes from, for messages; the path, never the key. */
        String origin() {
            return "key file " + file;
        }

        File file() {
            return file;
        }

        /** For the tests: the moves the key replacement uses from now on. */
        FileKey moves(Moves next) {
            this.moves = Objects.requireNonNull(next, "next");
            return this;
        }

        /** The AuthFileKey the key file is encrypted under; null: raw bytes. */
        @Nullable AuthFileKey fileKey() {
            return fileKey;
        }

        @Override
        public byte[] signingKey() throws IOException {
            return signingKey(new ConsoleLogger());
        }

        /** {@link #signingKey()} logging its warnings (never key material) to {@code log}; auth passes its logger. */
        // @req SEC-IAM-008
        byte[] signingKey(Logger log) throws IOException {
            if (!file.exists()) {
                createOnce();
            }
            byte[] content = read();
            String sealed = sealedText(content);
            if (sealed != null) {
                byte[] key = open(sealed);
                File dir = file.getAbsoluteFile().getParentFile();
                if (dir != null && backup(dir).exists()) {
                    removeLeftoverBackup(dir, log);
                }
                return key;
            }
            if (content.length > MAX_KEY_FILE_BYTES) {
                throw new IOException("the key file " + file + " is larger than " + MAX_KEY_FILE_BYTES + " bytes");
            }
            if (box != null) {
                migrateOnce(content, log);
            }
            return content;
        }

        /**
         * R2-M1-F06: a {@code .<name>.bak} beside a key file that opens as enc(...) is the raw key a migration could
         * not delete (Windows); it is deleted under the creation locks, while the key file is still there (a missing
         * key file means a replacement stopped midway, and createOnce restores the backup instead).
         */
        private void removeLeftoverBackup(File dir, Logger log) throws IOException {
            synchronized (CREATE_LOCK) {
                RandomAccessFile lockFile = new RandomAccessFile(new File(dir, "." + file.getName() + ".lock"), "rw");
                try {
                    FileLock lock = lockFile.getChannel().lock();
                    try {
                        File bak = backup(dir);
                        if (file.exists() && bak.exists() && !moves.delete(bak)) {
                            log.warn("auth: cannot delete the raw key backup " + bak + " left by the encryption of "
                                    + file + "; delete it by hand");
                        }
                    } finally {
                        lock.release();
                    }
                } finally {
                    lockFile.close();
                }
            }
        }

        /** The enc(...) text of {@code content} (surrounding ASCII whitespace ignored); null for raw key bytes. */
        private static @Nullable String sealedText(byte[] content) {
            int from = 0;
            int to = content.length;
            while (from < to && content[from] <= ' ' && content[from] >= 0) {
                from++;
            }
            while (to > from && content[to - 1] <= ' ' && content[to - 1] >= 0) {
                to--;
            }
            if (to - from < 5 || content[from] != 'e' || content[from + 1] != 'n' || content[from + 2] != 'c'
                    || content[from + 3] != '(') {
                return null;
            }
            return new String(content, from, to - from, StandardCharsets.ISO_8859_1);
        }

        /** The key of an enc(...) key file; fails closed naming the file, never the value or the key. */
        private byte[] open(String sealed) throws IOException {
            SecretBox b = box;
            if (b == null) {
                throw new IOException("the key file " + file + " holds an encrypted value (enc(...)); an AuthFileKey "
                        + "is needed: KeySources.fromFile(file, AuthFileKey), for example AuthFileKey.fromEnv() reading "
                        + AuthFileKey.ENV_VARIABLE);
            }
            try {
                return b.open(SecretBox.SIGNING_KEY, file.getName(), sealed);
            } catch (IllegalArgumentException e) {
                throw new IOException("the signing key in the key file " + file + " " + e.getMessage()
                        + "; the file is left as it is");
            }
        }

        /** The encrypted file content of {@code key}. */
        private byte[] seal(SecretBox b, byte[] key) {
            return b.seal(SecretBox.SIGNING_KEY, file.getName(), key).getBytes(StandardCharsets.ISO_8859_1);
        }

        /**
         * FR-IAM-015 AC-5: replaces the raw key file by its enc(...) form under the creation locks, unless another
         * reader already did; the key bytes stay the same.
         */
        private void migrateOnce(byte[] raw, Logger log) throws IOException {
            SecretBox b = box;
            File dir = file.getAbsoluteFile().getParentFile();
            if (b == null || dir == null) {
                return;
            }
            synchronized (CREATE_LOCK) {
                RandomAccessFile lockFile = new RandomAccessFile(new File(dir, "." + file.getName() + ".lock"), "rw");
                try {
                    FileLock lock = lockFile.getChannel().lock();
                    try {
                        if (Arrays.equals(raw, read())) {
                            publish(dir, seal(b, raw), true, log);
                        }
                    } finally {
                        lock.release();
                    }
                } finally {
                    lockFile.close();
                }
            }
        }

        /**
         * Creates the key unless another creator did: a JVM-wide monitor plus an exclusive lock on the sibling
         * {@code .<name>.lock} file span the re-check and the publish, because {@link File#renameTo} replaces an
         * existing target on POSIX. Every caller then reads the one file that is on disk.
         */
        private void createOnce() throws IOException {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir == null || !dir.isDirectory()) {
                throw new IOException("the directory of " + file + " does not exist");
            }
            synchronized (CREATE_LOCK) {
                RandomAccessFile lockFile = new RandomAccessFile(new File(dir, "." + file.getName() + ".lock"), "rw");
                try {
                    FileLock lock = lockFile.getChannel().lock();
                    try {
                        if (!file.exists()) {
                            File bak = backup(dir);
                            if (bak.exists()) {
                                // a replacement stopped after moving the old key aside: put it back, never a new key
                                if (!bak.renameTo(file)) {
                                    throw new IOException("cannot restore the key file " + file + " from " + bak);
                                }
                            } else {
                                create(dir);
                            }
                        }
                    } finally {
                        lock.release();
                    }
                } finally {
                    lockFile.close();
                }
            }
        }

        /**
         * Writes the new key inside a fresh owner-only directory, so no other user can open the file while its
         * permissions are still being set, then moves it to its name; the caller holds the creation lock.
         */
        private void create(File dir) throws IOException {
            byte[] key = new byte[GENERATED_KEY_BYTES];
            RANDOM.nextBytes(key);
            try {
                SecretBox b = box;
                publish(dir, b == null ? key : seal(b, key), false, new ConsoleLogger());
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        }

        /**
         * Writes {@code content} inside a fresh owner-only directory, so no other user can open the file while its
         * permissions are still being set, then moves it to its name: a new file only when {@code replace} is false,
         * over the existing raw file (POSIX rename) when it is true.
         */
        private void publish(File dir, byte[] content, boolean replace, Logger log) throws IOException {
            byte[] name = new byte[8];
            RANDOM.nextBytes(name);
            StringBuilder hex = new StringBuilder();
            for (byte b : name) {
                hex.append(Integer.toHexString((b & 0xff) | 0x100).substring(1));
            }
            File staging = new File(dir, "." + file.getName() + ".new-" + hex);
            if (!staging.mkdir()) {
                throw new IOException("cannot create " + staging);
            }
            File tmp = new File(staging, "key");
            try {
                ownerOnly(staging, true);
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    ownerOnly(tmp, false);
                    out.write(content);
                    out.getFD().sync();
                }
                if (replace) {
                    replaceWith(tmp, backup(dir), log);
                } else if (!file.exists() && !tmp.renameTo(file) && !file.exists()) {
                    throw new IOException("cannot move the new key to " + file);
                }
            } finally {
                tmp.delete();
                staging.delete();
            }
        }

        /** The sibling {@code .<name>.bak} the old key moves to while a replacement cannot rename over it. */
        private File backup(File dir) {
            return new File(dir, "." + file.getName() + ".bak");
        }

        /**
         * Moves {@code tmp} over the key file: a POSIX rename replaces it in one step; where rename does not replace
         * (Windows), the old file moves aside to {@code old} ({@code .<name>.bak}) first and is put back when the
         * second rename fails; a crash in between leaves the backup, which the next read restores (createOnce). A
         * backup that cannot be deleted after the second rename (a locked file) is one WARN, not a failure: the key
         * file is already enc(...), and the next read deletes the backup (removeLeftoverBackup).
         */
        private void replaceWith(File tmp, File old, Logger log) throws IOException {
            Moves m = moves;
            if (m.rename(tmp, file)) {
                return;
            }
            if (!m.rename(file, old)) {
                throw new IOException("cannot replace the key file " + file + " with its encrypted form; the raw file "
                        + "is left as it is");
            }
            if (!m.rename(tmp, file)) {
                if (!m.rename(old, file)) {
                    throw new IOException("cannot replace the key file " + file + " and cannot put the raw file back "
                            + "from " + old + "; move it back by hand");
                }
                throw new IOException("cannot replace the key file " + file + " with its encrypted form; the raw file "
                        + "is left as it is");
            }
            if (!m.delete(old)) {
                // R2-M1-F06: the key file is already enc(...); a locked raw backup must not fail the build. The next
                // read deletes it (removeLeftoverBackup); the message names the files, never the key.
                log.warn("auth: the key file " + file + " is now encrypted, but the replaced raw key " + old
                        + " cannot be deleted yet; the next read deletes it (or delete it by hand)");
            }
        }

        /** Readable and writable (and for a directory searchable) by the owner only; fails when the system refuses. */
        private static void ownerOnly(File f, boolean directory) throws IOException {
            boolean ok = f.setReadable(false, false) & f.setWritable(false, false) & f.setExecutable(false, false)
                    & f.setReadable(true, true) & f.setWritable(true, true);
            if (directory) {
                ok &= f.setExecutable(true, true);
            }
            if (!ok) {
                throw new IOException("cannot restrict " + f + " to its owner");
            }
        }

        private byte[] read() throws IOException {
            long size = file.length();
            if (size == 0) {
                throw new IOException("the key file " + file + " is empty; delete it to make a new key, or restore it");
            }
            if (size > MAX_SEALED_FILE_BYTES) {
                throw new IOException("the key file " + file + " is larger than " + MAX_SEALED_FILE_BYTES + " bytes");
            }
            byte[] out = new byte[(int) size];
            try (InputStream in = new FileInputStream(file)) {
                int off = 0;
                while (off < out.length) {
                    int n = in.read(out, off, out.length - off);
                    if (n < 0) {
                        throw new IOException("the key file " + file + " shrank while it was read");
                    }
                    off += n;
                }
            }
            return out;
        }

        @Override
        public String toString() {
            return "KeySource{file " + file + "}";
        }
    }

    /** A key fixed at creation; null bytes mean the secret is missing. */
    static final class Fixed implements KeySource {
        private final byte @Nullable [] key;
        private final String origin;

        Fixed(byte @Nullable [] key, String origin) {
            this.key = key;
            this.origin = origin;
        }

        byte @Nullable [] read() {
            return key == null ? null : key.clone();
        }

        /** Where the secret comes from, for messages; never the value. */
        String origin() {
            return origin;
        }

        @Override
        public byte[] signingKey() {
            byte[] k = read();
            if (k == null) {
                throw new IllegalStateException(origin + " is not set");
            }
            return k;
        }

        @Override
        public String toString() {
            return "KeySource{" + origin + "}";
        }
    }
}
