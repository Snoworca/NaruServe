package com.snoworca.naruserve.utils.auth.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The AtomicFiles write and start-up recovery protocol (REL-IAM-004). */
public class AtomicFilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The parser of a fake store: "ok:" + payload, anything else is corrupt. */
    private static final AtomicFiles.Parser<String> PARSER = content -> {
        String s = new String(content, StandardCharsets.UTF_8);
        if (!s.startsWith("ok:")) {
            throw new IllegalArgumentException("corrupt");
        }
        return s.substring(3);
    };

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void put(File f, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes(content));
        }
    }

    private static List<String> names(File dir) {
        String[] names = dir.list();
        List<String> out = new ArrayList<>(Arrays.asList(names == null ? new String[0] : names));
        java.util.Collections.sort(out);
        return out;
    }

    /** Steps that record what happens, in order, around the real file operations. */
    private static final class Recording extends AtomicFiles.Steps {
        final List<String> events = new ArrayList<>();
        File expectedTemp;
        long expectedLength = -1;

        @Override
        void sync(FileOutputStream out) throws IOException {
            // the synced stream is the temp file's, and every byte was written before the sync
            assertTrue(expectedTemp.isFile());
            assertEquals(expectedLength, expectedTemp.length());
            assertEquals(expectedLength, out.getChannel().size());
            events.add("sync");
            super.sync(out);
        }

        @Override
        boolean rename(File from, File to) {
            events.add("rename " + from.getName() + " -> " + to.getName());
            return super.rename(from, to);
        }

        @Override
        boolean delete(File f) {
            events.add("delete " + f.getName());
            return super.delete(f);
        }
    }

    // REL-IAM-004 AC-1: a write leaves only the main file, and the temp file was synced before the first rename
    @Test
    public void REL_IAM_004_AC1_writeSyncsBeforeRenameAndLeavesOnlyTheMainFile() throws IOException {
        File dir = tmp.newFolder("store");
        File main = new File(dir, "users.json");
        Recording first = new Recording();
        first.expectedTemp = new File(dir, "users.json.tmp");
        first.expectedLength = bytes("ok:v1").length;
        AtomicFiles.write(main, bytes("ok:v1"), first);
        assertEquals(Arrays.asList("users.json"), names(dir));
        assertArrayEquals(bytes("ok:v1"), read(main));
        assertEquals("sync", first.events.get(0));
        assertEquals("rename users.json.tmp -> users.json", first.events.get(1));

        Recording second = new Recording();
        second.expectedTemp = new File(dir, "users.json.tmp");
        second.expectedLength = bytes("ok:v2-longer").length;
        AtomicFiles.write(main, bytes("ok:v2-longer"), second);
        assertEquals(Arrays.asList("sync", "rename users.json -> users.json.bak", "rename users.json.tmp -> users.json",
                "delete users.json.bak"), second.events);
        assertEquals(Arrays.asList("users.json"), names(dir));
        assertArrayEquals(bytes("ok:v2-longer"), read(main));

        AtomicFiles.write(main, bytes("ok:v3"));
        assertEquals(Arrays.asList("users.json"), names(dir));
        assertEquals("v3", AtomicFiles.load(main, PARSER, new RecordingLogger()));
    }

    // REL-IAM-004 AC-2: only the .bak exists, so it is loaded, renamed to the main name, with one WARN
    @Test
    public void REL_IAM_004_AC2_onlyBakIsRestoredWithOneWarn() throws IOException {
        File dir = tmp.newFolder("store");
        File main = new File(dir, "users.json");
        put(new File(dir, "users.json.bak"), "ok:from-bak");
        RecordingLogger log = new RecordingLogger();
        assertEquals("from-bak", AtomicFiles.load(main, PARSER, log));
        assertEquals(Arrays.asList("users.json"), names(dir));
        assertArrayEquals(bytes("ok:from-bak"), read(main));
        assertEquals(1, log.count("WARN"));
        assertEquals(1, log.lines.size());
        assertTrue(log.lines.get(0), log.lines.get(0).contains("users.json.bak"));
    }

    // REL-IAM-004 AC-3: a readable main file wins over a leftover .bak, which is deleted
    @Test
    public void REL_IAM_004_AC3_readableMainWinsAndBakIsDeleted() throws IOException {
        File dir = tmp.newFolder("store");
        File main = new File(dir, "users.json");
        put(main, "ok:main");
        put(new File(dir, "users.json.bak"), "ok:older");
        RecordingLogger log = new RecordingLogger();
        assertEquals("main", AtomicFiles.load(main, PARSER, log));
        assertEquals(Arrays.asList("users.json"), names(dir));
        assertEquals(0, log.count("WARN"));
    }

    // REL-IAM-004 AC-4: an unreadable main file with a .bak fails start and modifies neither file
    @Test
    public void REL_IAM_004_AC4_corruptMainFailsWithoutTouchingEitherFile() throws IOException {
        File dir = tmp.newFolder("store");
        File main = new File(dir, "users.json");
        File bak = new File(dir, "users.json.bak");
        put(main, "{garbage");
        put(bak, "ok:older");
        long mainModified = main.lastModified();
        try {
            AtomicFiles.load(main, PARSER, new RecordingLogger());
            fail("a corrupt main file was accepted");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("FAIL"));
            assertTrue(e.getMessage(), e.getMessage().contains("users.json.bak"));
            assertTrue(e.getMessage(), e.getMessage().contains("inspect"));
        }
        assertArrayEquals(bytes("{garbage"), read(main));
        assertArrayEquals(bytes("ok:older"), read(bak));
        assertEquals(mainModified, main.lastModified());
        assertEquals(Arrays.asList("users.json", "users.json.bak"), names(dir));
    }

    // REL-IAM-004 component: a corrupt main without a .bak fails too; an unreadable .bak alone is not restored
    @Test
    public void corruptFilesWithoutABackupFail() throws IOException {
        File dir = tmp.newFolder("store");
        File main = new File(dir, "users.json");
        put(main, "bad");
        try {
            AtomicFiles.load(main, PARSER, new RecordingLogger());
            fail("accepted");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("FAIL"));
        }
        assertArrayEquals(bytes("bad"), read(main));
        assertTrue(main.delete());
        File bak = new File(dir, "users.json.bak");
        put(bak, "bad");
        try {
            AtomicFiles.load(main, PARSER, new RecordingLogger());
            fail("accepted");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("users.json.bak"));
        }
        assertFalse(main.exists());
        assertArrayEquals(bytes("bad"), read(bak));
        assertTrue(bak.delete());
        assertNull(AtomicFiles.load(main, PARSER, new RecordingLogger()));
    }

    // REL-IAM-004 component: a backup that cannot be deleted after the commit does not fail the committed write
    @Test
    public void undeletableBackupAfterCommitIsNotAFailure() throws IOException {
        File dir = tmp.newFolder("store");
        File main = new File(dir, "users.json");
        AtomicFiles.write(main, bytes("ok:v1"));
        AtomicFiles.Steps stubborn = new AtomicFiles.Steps() {
            @Override
            boolean delete(File f) {
                return false;
            }
        };
        AtomicFiles.write(main, bytes("ok:v2"), stubborn);
        assertArrayEquals(bytes("ok:v2"), read(main));
        assertEquals(Arrays.asList("users.json", "users.json.bak"), names(dir));
        // the next start cleans the leftover backup and keeps the committed content
        assertEquals("v2", AtomicFiles.load(main, PARSER, new RecordingLogger()));
        assertEquals(Arrays.asList("users.json"), names(dir));
    }

    // REL-IAM-004: a store file (password hashes, token versions) is readable and writable by its owner only, like the
    // key file, whatever the process umask (POSIX file systems; skipped where there are no POSIX permissions)
    @Test
    public void storeFilesAreOwnerOnly() throws IOException {
        File dir = tmp.newFolder("store");
        File main = new File(dir, "users.json");
        org.junit.Assume.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()
                .contains("posix"));
        AtomicFiles.write(main, bytes("ok:v1"));
        AtomicFiles.write(main, bytes("ok:v2"));
        java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                java.nio.file.Files.getPosixFilePermissions(main.toPath());
        assertEquals(java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), perms);
        assertEquals("v2", AtomicFiles.load(main, PARSER, new RecordingLogger()));
    }

    // REL-IAM-004 (R2-M1-F03): the per-path write monitor is keyed on the canonical path, so two spellings of one file
    // ("dir/users.json" and "dir/./users.json", or a path through a symbolic link) share one monitor
    @Test
    public void lockForKeysOnTheCanonicalPath() throws IOException {
        File dir = tmp.newFolder("store");
        File plain = new File(dir, "users.json");
        File dotted = new File(new File(dir, "."), "users.json");
        File upAndBack = new File(new File(new File(dir, "sub"), ".."), "users.json");
        assertTrue(new File(dir, "sub").mkdir());
        org.junit.Assert.assertSame(AtomicFiles.lockFor(plain), AtomicFiles.lockFor(dotted));
        org.junit.Assert.assertSame(AtomicFiles.lockFor(plain), AtomicFiles.lockFor(upAndBack));
        org.junit.Assert.assertNotSame(AtomicFiles.lockFor(plain), AtomicFiles.lockFor(new File(dir, "groups.txt")));
        File link = new File(tmp.getRoot(), "store-link");
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), dir.toPath());
        } catch (UnsupportedOperationException | IOException | SecurityException noLinks) {
            return; // a file system without symbolic links: the spellings above are the check
        }
        org.junit.Assert.assertSame(AtomicFiles.lockFor(plain), AtomicFiles.lockFor(new File(link, "users.json")));
    }

    // REL-IAM-004 (N3-02): the monitor of a file that does not exist yet is the same for spellings that differ only in
    // case, since on a case-insensitive file system (macOS, Windows) they name one file and share one temp file; the
    // key stays the same once the file exists, so a writer that looked before and one that looks after the first
    // create share it too
    @Test
    public void lockForFoldsTheCaseOfAFileThatDoesNotExistYet() throws IOException {
        File dir = tmp.newFolder("store");
        File lower = new File(dir, "users.json");
        File upper = new File(dir, "USERS.json");
        assertFalse(lower.exists());
        Object before = AtomicFiles.lockFor(lower);
        org.junit.Assert.assertSame(before, AtomicFiles.lockFor(upper));
        org.junit.Assert.assertSame(before, AtomicFiles.lockFor(new File(new File(dir, "."), "Users.JSON")));
        AtomicFiles.write(upper, bytes("ok:v1"));
        org.junit.Assert.assertSame(before, AtomicFiles.lockFor(lower));
        org.junit.Assert.assertSame(before, AtomicFiles.lockFor(upper));
        org.junit.Assert.assertNotSame(before, AtomicFiles.lockFor(new File(dir, "users.json.bak")));
    }

    // REL-IAM-004 (N3-02): on a case-insensitive file system two FileUserStores over "users.json" and "USERS.json" of
    // a fresh directory that create a user at the same moment never lose a create that reported success and never
    // leave an unreadable file: the second writer is refused as a foreign change
    @Test
    public void racingFirstCreatesThroughCaseSpellingsLoseNothing() throws Exception {
        File probeDir = tmp.newFolder("probe");
        assertTrue(new File(probeDir, "probe").createNewFile());
        org.junit.Assume.assumeTrue("a case-sensitive file system: the two spellings are two files",
                new File(probeDir, "PROBE").exists());
        assertRacingFirstCreatesLoseNothing("users.json", "USERS.json", 60);
    }

    // REL-IAM-004 (N4-01): on a file system that does not tell Unicode normalization forms apart (APFS, HFS+) the NFC
    // and the NFD spelling of "café.json" are one file; two FileUserStores over them that create a user in a fresh
    // directory at the same moment never lose a create that reported success and never leave an unreadable file
    @Test
    public void racingFirstCreatesThroughUnicodeNormalizationSpellingsLoseNothing() throws Exception {
        String nfc = "caf\u00e9.json";
        String nfd = "cafe\u0301.json";
        File probeDir = tmp.newFolder("probe-nf");
        assertTrue(new File(probeDir, nfc).createNewFile());
        org.junit.Assume.assumeTrue("a file system that tells NFC and NFD apart: the two spellings are two files",
                new File(probeDir, nfd).exists());
        assertRacingFirstCreatesLoseNothing(nfc, nfd, 100);
    }

    // REL-IAM-004 (N4-01): the write monitor is the canonical directory's, so it does not depend on how the file name
    // is spelled ("./", "..", a symbolic link, case, NFC/NFD) and does not change across the first create; another
    // directory has its own
    @Test
    public void directoryLockForIsIndependentOfTheNameSpelling() throws IOException {
        File dir = tmp.newFolder("store");
        assertTrue(new File(dir, "sub").mkdir());
        File nfc = new File(dir, "caf\u00e9.json");
        assertFalse(nfc.exists());
        Object before = AtomicFiles.directoryLockFor(nfc);
        org.junit.Assert.assertSame(before, AtomicFiles.directoryLockFor(new File(dir, "cafe\u0301.json")));
        org.junit.Assert.assertSame(before, AtomicFiles.directoryLockFor(new File(dir, "CAF\u00c9.json")));
        org.junit.Assert.assertSame(before, AtomicFiles.directoryLockFor(new File(new File(dir, "."), "users.json")));
        org.junit.Assert.assertSame(before,
                AtomicFiles.directoryLockFor(new File(new File(new File(dir, "sub"), ".."), "users.json")));
        AtomicFiles.write(new File(dir, "cafe\u0301.json"), bytes("ok:v1"));
        org.junit.Assert.assertSame(before, AtomicFiles.directoryLockFor(nfc));
        org.junit.Assert.assertNotSame(before, AtomicFiles.directoryLockFor(new File(new File(dir, "sub"), "users.json")));
        File link = new File(tmp.getRoot(), "store-link-dir");
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), dir.toPath());
        } catch (UnsupportedOperationException | IOException | SecurityException noLinks) {
            return; // a file system without symbolic links: the spellings above are the check
        }
        org.junit.Assert.assertSame(before, AtomicFiles.directoryLockFor(new File(link, "users.json")));
    }

    // REL-IAM-004 (N5-01): a directory that does not exist yet has no resolved spelling, so every spelling of it shares
    // one monitor, and a write into it fails before it takes any lock
    @Test
    public void directoryLockForSharesOneMonitorForEverySpellingOfAMissingDirectory() throws IOException {
        File root = tmp.newFolder("root");
        Object missing = AtomicFiles.directoryLockFor(new File(new File(root, "Missing"), "u.json"));
        org.junit.Assert.assertSame(missing, AtomicFiles.directoryLockFor(new File(new File(root, "MISSING"), "u.json")));
        org.junit.Assert.assertSame(missing, AtomicFiles.directoryLockFor(new File(new File(root, "other"), "v.json")));
        org.junit.Assert.assertNotSame(missing, AtomicFiles.directoryLockFor(new File(root, "u.json")));
        try {
            AtomicFiles.write(new File(new File(root, "Missing"), "u.json"), bytes("ok:v1"));
            fail("a write into a missing directory must fail");
        } catch (IOException expected) {
            assertFalse(new File(root, "Missing").exists());
        }
    }

    // REL-IAM-004 (N5-01): on a case-insensitive file system two FileUserStores over "store/users.json" and
    // "STORE/users.json" of a directory that does not exist yet, which retry a create while the directory is being
    // created, never lose a create that reported success and never leave an unreadable file: a write whose lock was
    // taken while the directory was missing never runs beside one that took the directory's own lock
    @Test
    public void racingCreatesIntoADirectoryCreatedMidRetryLoseNothing() throws Exception {
        File probeDir = tmp.newFolder("probe-dir");
        assertTrue(new File(probeDir, "probe").createNewFile());
        org.junit.Assume.assumeTrue("a case-sensitive file system: the two spellings are two directories",
                new File(probeDir, "PROBE").exists());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            int lost = 0;
            int unreadable = 0;
            for (int round = 0; round < 300; round++) {
                final File root = tmp.newFolder("missing-dir-" + round);
                final File dir = new File(root, "store");
                final com.snoworca.naruserve.utils.auth.user.UserStore a =
                        new com.snoworca.naruserve.utils.auth.user.FileUserStore(new File(dir, "users.json"));
                final com.snoworca.naruserve.utils.auth.user.UserStore b =
                        new com.snoworca.naruserve.utils.auth.user.FileUserStore(
                                new File(new File(root, "STORE"), "users.json"));
                final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(3);
                List<java.util.concurrent.Future<String>> results = new ArrayList<>();
                for (final Object[] job : new Object[][] {{a, "alice"}, {b, "bob"}}) {
                    results.add(pool.submit(() -> {
                        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                        while (System.nanoTime() < deadline) {
                            try {
                                ((com.snoworca.naruserve.utils.auth.user.UserStore) job[0]).create(
                                        com.snoworca.naruserve.utils.auth.user.AuthUser.builder()
                                                .username((String) job[1]).passwordHash("pbkdf2-sha256$1000$x$y")
                                                .build());
                                return (String) job[1];
                            } catch (IOException e) {
                                if (String.valueOf(e.getMessage()).contains("changed by another writer")) {
                                    return null; // refused as a foreign change: nothing reported success
                                }
                                Thread.yield(); // the directory is not there yet: retry
                            }
                        }
                        return null;
                    }));
                }
                java.util.concurrent.Future<Boolean> made = pool.submit(() -> {
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    for (int i = 0; i < 2000; i++) {
                        Thread.yield();
                    }
                    return dir.mkdir();
                });
                assertTrue(made.get(30, java.util.concurrent.TimeUnit.SECONDS));
                List<String> created = new ArrayList<>();
                for (java.util.concurrent.Future<String> f : results) {
                    String name = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (name != null) {
                        created.add(name);
                    }
                }
                com.snoworca.naruserve.utils.auth.user.FileUserStore reopened;
                try {
                    reopened = new com.snoworca.naruserve.utils.auth.user.FileUserStore(new File(dir, "users.json"));
                } catch (IOException e) {
                    unreadable++;
                    continue;
                }
                for (String name : created) {
                    if (reopened.findByUsername(name) == null) {
                        lost++;
                    }
                }
            }
            assertEquals("unreadable files", 0, unreadable);
            assertEquals("creates that reported success but are not on disk", 0, lost);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    /**
     * Races two FileUserStores over the spellings {@code nameA} and {@code nameB} of one file in a fresh directory,
     * {@code rounds} times, each creating a user at one barrier, and reopens the file through {@code nameA}: no create
     * that reported success may be missing, and no round may leave an unreadable file.
     */
    private void assertRacingFirstCreatesLoseNothing(String nameA, String nameB, int rounds) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            int lost = 0;
            int unreadable = 0;
            for (int round = 0; round < rounds; round++) {
                File dir = tmp.newFolder("race-" + rounds + "-" + nameB.hashCode() + "-" + round);
                final com.snoworca.naruserve.utils.auth.user.UserStore a =
                        new com.snoworca.naruserve.utils.auth.user.FileUserStore(new File(dir, nameA));
                final com.snoworca.naruserve.utils.auth.user.UserStore b =
                        new com.snoworca.naruserve.utils.auth.user.FileUserStore(new File(dir, nameB));
                final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
                List<java.util.concurrent.Future<String>> results = new ArrayList<>();
                for (final Object[] job : new Object[][] {{a, "alice"}, {b, "bob"}}) {
                    results.add(pool.submit(() -> {
                        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        try {
                            ((com.snoworca.naruserve.utils.auth.user.UserStore) job[0]).create(
                                    com.snoworca.naruserve.utils.auth.user.AuthUser.builder()
                                            .username((String) job[1]).passwordHash("pbkdf2-sha256$1000$x$y").build());
                            return (String) job[1];
                        } catch (IOException refused) {
                            return null;
                        }
                    }));
                }
                List<String> created = new ArrayList<>();
                for (java.util.concurrent.Future<String> f : results) {
                    String name = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (name != null) {
                        created.add(name);
                    }
                }
                com.snoworca.naruserve.utils.auth.user.FileUserStore reopened;
                try {
                    reopened = new com.snoworca.naruserve.utils.auth.user.FileUserStore(new File(dir, nameA));
                } catch (IOException e) {
                    unreadable++;
                    continue;
                }
                for (String name : created) {
                    if (reopened.findByUsername(name) == null) {
                        lost++;
                    }
                }
            }
            assertEquals("unreadable files", 0, unreadable);
            assertEquals("creates that reported success but are not on disk", 0, lost);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    // REL-IAM-004 (R2-M1-F05): the temp file is never visible under its name with the umask permissions: it already is
    // owner-only (POSIX) when the write stream is opened on it, so no other local user can open it in a window between
    // its creation and the permission change and keep reading the content written next
    @Test
    public void tempFileIsOwnerOnlyBeforeItIsOpenedForWriting() throws IOException {
        org.junit.Assume.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()
                .contains("posix"));
        File dir = tmp.newFolder("store");
        File main = new File(dir, "users.json");
        final java.util.EnumSet<java.nio.file.attribute.PosixFilePermission> ownerOnly = java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        final List<String> seen = new ArrayList<>();
        AtomicFiles.Steps watching = new AtomicFiles.Steps() {
            @Override
            FileOutputStream open(File f) throws IOException {
                FileOutputStream out = super.open(f);
                seen.add(f.getName() + " " + java.util.EnumSet.copyOf(java.nio.file.Files.getPosixFilePermissions(
                        f.toPath())));
                return out;
            }
        };
        for (int i = 0; i < 3; i++) {
            AtomicFiles.write(main, bytes("ok:v" + i), watching);
        }
        assertEquals(3, seen.size());
        for (String s : seen) {
            assertEquals(s, "users.json.tmp " + ownerOnly, s);
        }
        assertEquals(Arrays.asList("users.json"), names(dir));
        assertEquals(ownerOnly, java.nio.file.Files.getPosixFilePermissions(main.toPath()));
        assertEquals("v2", AtomicFiles.load(main, PARSER, new RecordingLogger()));
    }

    // REL-IAM-004 (R2-M1-F04): the load overload without a limit is bounded too (8 MB, MiniJson.DEFAULT_FILE_BYTES),
    // so no store can read an unbounded file into the heap through it
    @Test(timeout = 60000)
    public void loadWithoutALimitIsBoundedByTheDefault() throws IOException {
        File dir = tmp.newFolder("store");
        File main = new File(dir, "users.json");
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(main, "rw")) {
            r.write(bytes("ok:"));
            r.setLength(MiniJson.DEFAULT_FILE_BYTES + 1);
        }
        try {
            AtomicFiles.load(main, PARSER, new RecordingLogger());
            fail("a file above 8 MB was read");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("FAIL"));
            assertTrue(e.getMessage(), e.getMessage().contains("above the limit of " + MiniJson.DEFAULT_FILE_BYTES));
        }
    }
}
