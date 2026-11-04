package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Logger;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * Crash-safe writes of the auth store files over {@code java.io} only (API 21 has no {@code java.nio.file}).
 *
 * <p>Write: the content goes to {@code <name>.tmp}, which is synced with {@code FileDescriptor.sync()}; the existing
 * file is renamed to {@code <name>.bak}, the temp file is renamed to the main name, and the backup is deleted. Start:
 * only a backup means the process stopped between the two renames, so the backup is restored with a WARN; both files
 * with a readable main file means it stopped before the delete, so the main file is used and the backup deleted; an
 * unreadable main file fails start and nothing is touched, because rolling back silently could restore old passwords
 * or a lower token version. API 21 has no directory fsync, so a rename may be lost after power loss. One file has
 * one writer at a time: the writes into one directory (by the directory's canonical path) are serialized within the
 * process ({@link #directoryLockFor}), since two writers of one file would share the temp file; the key does not
 * depend on how the file name is spelled, so "users.json", "USERS.json" on a case-insensitive file system and the NFC
 * and NFD spellings of one name on a normalization-insensitive one (APFS, HFS+) never get two writers, whether the
 * file or its directory exists yet or not. The temp file is created owner-only inside an
 * owner-only staging directory and moved to its name before the content is written (best effort; a file system
 * without those permissions keeps its default), so the store file, which holds password hashes and token versions, is
 * never readable by other local users, not even while it is being written. A size limit given to {@link #load(File, long, TooLarge, Parser,
 * Logger)} is compared with the file length before anything is read.
 */
// @req REL-IAM-004
public final class AtomicFiles {

    /** Reads a whole file's content; throws when the content is corrupt. */
    @FunctionalInterface
    public interface Parser<T> {
        /** The value of {@code content}. */
        T parse(byte[] content) throws Exception;
    }

    /** The refusal reason of a file above a store's size limit, from the file's length in bytes. */
    @FunctionalInterface
    public interface TooLarge {
        /** The reason, for example "the users file has N bytes, above the limit of M bytes; ...". */
        String reason(long length);
    }

    /** The file operations, a seam for the write-order test. */
    static class Steps {
        FileOutputStream open(File f) throws IOException {
            return new FileOutputStream(f);
        }

        void sync(FileOutputStream out) throws IOException {
            out.getFD().sync();
        }

        boolean rename(File from, File to) {
            return from.renameTo(to);
        }

        boolean delete(File f) {
            return f.delete();
        }
    }

    private static final Steps STEPS = new Steps();
    private static final ConcurrentMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Object> DIRECTORY_LOCKS = new ConcurrentHashMap<>();
    /** The monitor of every directory that does not exist yet or cannot be resolved, see {@link #directoryLockFor}. */
    private static final Object MISSING_DIRECTORY_LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private AtomicFiles() {
    }

    /**
     * The process-wide monitor of the directory that holds {@code file}: every write holds it, and a store that checks
     * the file before writing holds it around the check and the write, so two store instances over one file never
     * interleave, however its name is spelled. The key is the canonical path of the parent directory, which the file
     * system resolves for an existing directory (".", "..", symbolic links, and the case and Unicode normalization a
     * case- or normalization-insensitive file system ignores), so it is the same for every spelling of the file and
     * does not change when the file is first created; the name is not part of it, so no spelling the file system
     * treats as one name (case, NFC/NFD, a Windows short name) can split the monitor. All files of one directory share
     * it; the auth stores write a few small files, so the wait is short. A directory that does not exist yet has no
     * resolved spelling, and one the system cannot resolve has none either: both get one fixed process-wide fallback
     * monitor, whatever their spelling. A write into a missing directory fails without writing, and a write whose
     * caller took the fallback monitor while the directory was missing fails when the directory exists by the time it
     * writes (the caller's check ran outside the directory's monitor; retrying takes that monitor), so a write never
     * runs beside one that holds the directory's own monitor.
     */
    // @req REL-IAM-004
    public static Object directoryLockFor(File file) {
        File dir = directoryOf(file);
        if (!dir.isDirectory()) {
            return MISSING_DIRECTORY_LOCK;
        }
        try {
            return monitor(DIRECTORY_LOCKS, dir.getCanonicalPath());
        } catch (IOException e) {
            return MISSING_DIRECTORY_LOCK;
        }
    }

    private static File directoryOf(File file) {
        File absolute = file.getAbsoluteFile();
        File parent = absolute.getParentFile();
        return parent == null ? absolute : parent;
    }

    /**
     * The process-wide monitor of {@code file}'s own path, keyed as {@link #lockKey} says. The writes and the
     * check-and-write of a store hold {@link #directoryLockFor} instead, whose key does not depend on how the name is
     * spelled; this per-path monitor is not held by them.
     */
    // @req REL-IAM-004
    public static Object lockFor(File file) {
        return monitor(LOCKS, lockKey(file));
    }

    private static Object monitor(ConcurrentMap<String, Object> locks, String key) {
        Object lock = locks.get(key);
        if (lock == null) {
            Object fresh = new Object();
            lock = locks.putIfAbsent(key, fresh);
            if (lock == null) {
                lock = fresh;
            }
        }
        return lock;
    }

    /**
     * The key of {@link #lockFor}: the canonical path, so "dir/users.json", "dir/./users.json" and a path through a
     * symbolic link share one monitor. A file that does not exist yet has no resolved spelling of its own, so its key is
     * the canonical parent directory plus its name, and the key is case-folded: on a case-insensitive file system
     * (macOS, Windows) "users.json" and "USERS.json" are one file with one temp file whether or not it exists yet, and
     * on a case-sensitive one the two files merely share a monitor. A path the system cannot resolve falls back to its
     * absolute spelling.
     */
    private static String lockKey(File file) {
        File absolute = file.getAbsoluteFile();
        String key;
        try {
            if (absolute.exists()) {
                key = absolute.getCanonicalPath();
            } else {
                File parent = absolute.getParentFile();
                key = parent == null ? absolute.getCanonicalPath()
                        : new File(parent.getCanonicalPath(), absolute.getName()).getPath();
            }
        } catch (IOException e) {
            key = absolute.getPath();
        }
        return key.toLowerCase(Locale.ROOT);
    }

    private static File sibling(File file, String suffix) {
        return new File(file.getParentFile(), file.getName() + suffix);
    }

    /** Replaces {@code file} with {@code content} by the write protocol of the class description. */
    // @req REL-IAM-004
    public static void write(File file, byte[] content) throws IOException {
        write(file, content, STEPS);
    }

    // @req REL-IAM-004
    static void write(File file, byte[] content, Steps steps) throws IOException {
        Object lock = directoryLockFor(file);
        if (lock == MISSING_DIRECTORY_LOCK) {
            if (!directoryOf(file).isDirectory()) {
                throw new IOException("cannot write " + file + ": its directory does not exist");
            }
            // The directory exists now (it may have been created since the lock was looked up): its own monitor,
            // unless the system cannot resolve it.
            lock = directoryLockFor(file);
        }
        if (lock != MISSING_DIRECTORY_LOCK && Thread.holdsLock(MISSING_DIRECTORY_LOCK)) {
            // The caller checked the file while the directory was missing, outside the directory's own monitor, so
            // another writer may have written it since; the next attempt checks again under the directory's monitor.
            throw new IOException("cannot write " + file + ": its directory was created while the write was being "
                    + "prepared; retry");
        }
        synchronized (lock) {
            writeLocked(file, content, steps);
        }
    }

    private static void writeLocked(File file, byte[] content, Steps steps) throws IOException {
        File tmp = sibling(file, ".tmp");
        File bak = sibling(file, ".bak");
        stageOwnerOnly(tmp);
        FileOutputStream out = steps.open(tmp);
        try {
            ownerOnly(tmp);
            out.write(content);
            out.flush();
            steps.sync(out);
        } finally {
            out.close();
        }
        boolean backedUp = false;
        if (file.exists()) {
            if (bak.exists() && !steps.delete(bak)) {
                throw new IOException("cannot delete the stale backup " + bak);
            }
            if (!steps.rename(file, bak)) {
                throw new IOException("cannot rename " + file + " to " + bak);
            }
            backedUp = true;
        }
        if (!steps.rename(tmp, file)) {
            if (backedUp && !file.exists()) {
                steps.rename(bak, file);
            }
            throw new IOException("cannot rename " + tmp + " to " + file);
        }
        // The new content is committed; a backup that cannot be deleted is removed by the next write or load.
        if (bak.exists()) {
            steps.delete(bak);
        }
    }

    /**
     * Puts an empty, owner-only file at {@code tmp} before anything is written: it is created inside a fresh directory
     * {@code .<name>.new-<hex>} that is restricted to the owner first, restricted itself, and only then renamed to
     * {@code tmp}, so the temp file is never visible under its name with the umask permissions and no other local user
     * can open it in between and keep reading what is written next (POSIX keeps an open descriptor readable after a
     * permission change). Best effort like {@link #ownerOnly}: where the staging directory or the rename is not
     * possible, the temp file is created in place and restricted before the content is written.
     */
    private static void stageOwnerOnly(File tmp) throws IOException {
        File dir = tmp.getAbsoluteFile().getParentFile();
        File staging = null;
        for (int attempt = 0; attempt < 4 && staging == null; attempt++) {
            File candidate = new File(dir, "." + tmp.getName() + ".new-" + Long.toHexString(RANDOM.nextLong()));
            if (candidate.mkdir()) {
                staging = candidate;
            }
        }
        if (staging == null) {
            return;
        }
        File empty = new File(staging, tmp.getName());
        try {
            ownerOnlyDirectory(staging);
            new FileOutputStream(empty).close();
            ownerOnly(empty);
            if (!empty.renameTo(tmp) && tmp.exists() && tmp.delete()) {
                empty.renameTo(tmp); // a leftover temp file where rename does not replace (Windows)
            }
        } finally {
            empty.delete();
            staging.delete();
        }
    }

    /** Restricts the directory {@code d} to its owner (read, write, search), best effort. */
    private static void ownerOnlyDirectory(File d) {
        d.setReadable(false, false);
        d.setReadable(true, true);
        d.setWritable(false, false);
        d.setWritable(true, true);
        d.setExecutable(false, false);
        d.setExecutable(true, true);
    }

    /**
     * Restricts {@code f} to its owner (read and write), best effort: a file system that refuses (FAT, some Windows
     * set-ups) keeps its default permissions and the write goes on.
     */
    private static void ownerOnly(File f) {
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
        f.setExecutable(false, false);
    }

    /**
     * The parsed content of {@code file} after start-up recovery, or null when neither the file nor its backup
     * exists. A corrupt or unreadable file fails with an {@link IOException} whose message starts with FAIL; a file
     * above the default store limit ({@link MiniJson#DEFAULT_FILE_BYTES}, 8 MB) fails before it is read, so no store
     * can read an unbounded file through this overload.
     */
    // @req REL-IAM-004
    public static <T> @Nullable T load(File file, Parser<T> parser, Logger logger) throws IOException {
        final long max = MiniJson.DEFAULT_FILE_BYTES;
        return load(file, max, length -> "the file has " + length + " bytes, above the limit of " + max + " bytes",
                parser, logger);
    }

    /**
     * {@link #load(File, Parser, Logger)} within {@code maxBytes}: a file (or backup) longer than that fails with the
     * FAIL message carrying {@code tooLarge}'s reason before any byte is read, so a huge or corrupt file cannot
     * exhaust the heap; the read buffer is sized from the file length.
     */
    // @req REL-IAM-004
    // @req SEC-TOKEN-002
    public static <T> @Nullable T load(File file, long maxBytes, TooLarge tooLarge, Parser<T> parser, Logger logger)
            throws IOException {
        File bak = sibling(file, ".bak");
        if (file.exists()) {
            T value = parse(file, maxBytes, tooLarge, parser, bak.exists()
                    ? "; a backup " + bak.getName() + " exists: inspect it and restore it by hand if it is intact"
                    : "");
            if (bak.exists() && !bak.delete()) {
                throw new IOException("cannot delete the leftover backup " + bak);
            }
            return value;
        }
        if (bak.exists()) {
            T value = parse(bak, maxBytes, tooLarge, parser, " and no " + file.getName()
                    + " exists; inspect the backup");
            if (!bak.renameTo(file)) {
                throw new IOException("cannot restore " + bak + " to " + file);
            }
            logger.warn("auth store: restored " + file.getName() + " from " + bak.getName()
                    + " (the last write stopped between its renames)");
            return value;
        }
        return null;
    }

    private static <T> T parse(File f, long maxBytes, TooLarge tooLarge, Parser<T> parser, String hint)
            throws IOException {
        if (f.isDirectory()) { // OPS-AUTH-022 AC-8
            throw new IOException("FAIL: auth store file " + f + " is a directory; give the store a file path inside "
                    + "it, such as " + new File(f, "users.json") + hint);
        }
        long length = f.length();
        if (length == 0) {
            throw new IOException("FAIL: auth store file " + f + " is empty (0 bytes); delete it so the store starts a "
                    + "new file, or restore it from a backup" + hint);
        }
        if (length > maxBytes) {
            throw new IOException("FAIL: auth store file " + f + " is unreadable (" + tooLarge.reason(length) + ")"
                    + hint);
        }
        byte[] content;
        try {
            content = read(f, length, maxBytes);
        } catch (IOException e) {
            throw new IOException("FAIL: cannot read auth store file " + f + hint, e);
        }
        try {
            return parser.parse(content);
        } catch (Exception e) {
            throw new IOException("FAIL: auth store file " + f + " is unreadable (" + e.getMessage() + ")" + hint, e);
        }
    }

    private static byte[] read(File f, long length, long maxBytes) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(32L, Math.min(length, 1L << 26)));
            byte[] buf = new byte[8192];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("the file grew above " + maxBytes + " bytes while it was read");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
