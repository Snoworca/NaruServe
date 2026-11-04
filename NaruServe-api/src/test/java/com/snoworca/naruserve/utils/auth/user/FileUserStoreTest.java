package com.snoworca.naruserve.utils.auth.user;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The FileUserStore built-in store (FR-IAM-002) and its file limits (SEC-TOKEN-002 AC-6). */
public class FileUserStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static AuthUser row(String name) {
        return AuthUser.builder().username(name).passwordHash("pbkdf2-sha256$1000$c2FsdA$aGFzaA").tokenVersion(3)
                .enabled(false).roles("editor", "reader").attribute("email", "a\"lice@example.com")
                .attribute("tab", "x\ty\n").build();
    }

    // FR-IAM-002 AC-2: a row written by one instance reads back equal, hash included, from a new instance
    @Test
    public void FR_IAM_002_AC2_rowsSurviveANewInstance() throws Exception {
        File f = new File(tmp.getRoot(), "users.json");
        FileUserStore s = new FileUserStore(f);
        AuthUser created = s.create(row("alice"));
        AuthUser service = s.create(AuthUser.builder().username("ci-bot").serviceAccount(true).build());
        FileUserStore reopened = new FileUserStore(f);
        AuthUser read = reopened.findById(created.getId());
        assertEquals(created, read);
        assertNotNull(read.getPasswordHash());
        assertEquals(3, read.getTokenVersion());
        assertFalse(read.isEnabled());
        assertEquals(created.getRoles(), read.getRoles());
        assertEquals(created.getAttributes(), read.getAttributes());
        assertEquals(service, reopened.findById(service.getId()));
        assertTrue(reopened.findById(service.getId()).isServiceAccount());
        assertNull(reopened.findById(service.getId()).getPasswordHash());
        assertEquals(created, reopened.findByUsername("alice"));
        try {
            reopened.create(row("alice"));
            fail("a duplicate name was stored");
        } catch (DuplicateUserException expected) {
            // one lock over the name index
        }
        AuthUser renamed = reopened.update(read.toBuilder().username("alicia").build());
        assertEquals("alicia", renamed.getUsername());
        assertNull(new FileUserStore(f).findByUsername("alice"));
        assertEquals(renamed, new FileUserStore(f).findByUsername("alicia"));
        assertEquals(2, new FileUserStore(f).list(0, 10, null).getTotal());
    }

    // FR-IAM-002 AC-3: the file carries a format version; an unknown version fails with a message naming it and the
    // file is not overwritten
    @Test
    public void FR_IAM_002_AC3_unknownVersionFailsWithoutOverwriting() throws Exception {
        File f = new File(tmp.getRoot(), "users.json");
        new FileUserStore(f).create(row("alice"));
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(text, text.contains("\"version\":1"));
        String future = text.replace("\"version\":1", "\"version\":7");
        Files.write(f.toPath(), future.getBytes(StandardCharsets.UTF_8));
        try {
            new FileUserStore(f);
            fail("a version 7 file was opened");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("FAIL"));
            assertTrue(e.getMessage(), e.getMessage().contains("version 7"));
        }
        assertEquals(future, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    // FR-IAM-002 AC-5: ids are never reused, over 1,000 create/delete cycles, for both built-in stores and across a
    // reopen of the file store
    @Test
    public void FR_IAM_002_AC5_idsAreNeverReused() throws Exception {
        File f = new File(tmp.getRoot(), "users.json");
        UserStore[] stores = {new InMemoryUserStore(), new FileUserStore(f)};
        for (UserStore s : stores) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                AuthUser u = s.create(AuthUser.builder().username("u").passwordHash("h").build());
                assertTrue(s + " reused " + u.getId(), seen.add(u.getId()));
                assertTrue(s.delete(u.getId()));
            }
            if (s instanceof FileUserStore) {
                AuthUser after = new FileUserStore(f).create(AuthUser.builder().username("u").passwordHash("h")
                        .build());
                assertFalse("the reopened store reused " + after.getId(), seen.contains(after.getId()));
            }
        }
    }

    /** A file whose counter is lower than a stored id never hands that id out again. */
    @Test
    public void lowCounterNeverOverwritesARow() throws Exception {
        File f = new File(tmp.getRoot(), "users.json");
        FileUserStore s = new FileUserStore(f);
        AuthUser a = s.create(row("alice"));
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Files.write(f.toPath(), text.replaceFirst("\"nextId\":\\d+", "\"nextId\":1").getBytes(StandardCharsets.UTF_8));
        FileUserStore edited = new FileUserStore(f);
        AuthUser b = edited.create(row("bob"));
        assertFalse(a.getId().equals(b.getId()));
        assertEquals(a, edited.findById(a.getId()));
    }

    // FR-IAM-002 AC-6: writes go through AtomicFiles: a write stopped after the temp file was synced leaves either
    // the old or the new content readable on restart
    @Test
    public void FR_IAM_002_AC6_interruptedWritesLeaveOldOrNew() throws Exception {
        File f = new File(tmp.getRoot(), "users.json");
        FileUserStore s = new FileUserStore(f);
        AuthUser alice = s.create(row("alice"));
        byte[] old = Files.readAllBytes(f.toPath());
        s.create(row("bob"));
        byte[] fresh = Files.readAllBytes(f.toPath());
        File bak = new File(tmp.getRoot(), "users.json.bak");
        File temp = new File(tmp.getRoot(), "users.json.tmp");
        assertFalse(bak.exists() || temp.exists());

        // a write through the store stopped after its temp file was synced (the old main file cannot be moved to
        // the backup): the store keeps the old state, the main file keeps the old content, the temp file holds the new
        File blocker = new File(tmp.getRoot(), "users.json.bak");
        assertTrue(blocker.mkdir());
        assertTrue(new File(blocker, "keep").createNewFile());
        try {
            s.create(row("carol"));
            fail("the interrupted write reported success");
        } catch (IOException expected) {
            // the rename step failed after the temp file was written and synced
        }
        assertNull(s.findByUsername("carol"));
        assertArrayEquals(fresh, Files.readAllBytes(f.toPath()));
        assertTrue(new String(Files.readAllBytes(new File(tmp.getRoot(), "users.json.tmp").toPath()),
                StandardCharsets.UTF_8).contains("carol"));
        assertTrue(new File(blocker, "keep").delete() && blocker.delete());
        assertNotNull(new FileUserStore(f).findByUsername("bob"));
        assertNull(new FileUserStore(f).findByUsername("carol"));

        // stopped between the two renames: only the backup (old) and the synced temp file (new) exist
        Files.write(bak.toPath(), old);
        Files.write(temp.toPath(), fresh);
        assertTrue(f.delete());
        RecordingLogger log = new RecordingLogger();
        FileUserStore restored = new FileUserStore(f, FileUserStore.DEFAULT_MAX_FILE_BYTES, log);
        assertEquals(alice, restored.findById(alice.getId()));
        assertNull(restored.findByUsername("bob"));
        assertEquals(1, RecordingLogger.countMatching(log.lines, "WARN .*restored users.json.*"));

        // stopped before the backup was deleted: the main file (new) wins
        Files.write(f.toPath(), fresh);
        Files.write(bak.toPath(), old);
        FileUserStore kept = new FileUserStore(f);
        assertNotNull(kept.findByUsername("bob"));
        assertFalse(bak.exists());
        assertArrayEquals(fresh, Files.readAllBytes(f.toPath()));
    }

    // SEC-TOKEN-002 AC-6: a users file larger than the FILE total (9 MB under the default 8 MB) is not loaded: start
    // stops with the FAIL of REL-IAM-004 naming the limit, the constructor that raises it and the advice to move to an
    // app-provided UserStore backed by the application's database, and nothing is overwritten
    @Test
    public void SEC_TOKEN_002_AC6_oversizedFileIsNotLoaded() throws Exception {
        File f = new File(tmp.getRoot(), "users.json");
        new FileUserStore(f).create(row("alice"));
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            byte[] spaces = new byte[1024 * 1024];
            java.util.Arrays.fill(spaces, (byte) ' ');
            for (int i = 0; i < 9; i++) {
                out.write(spaces);
            }
        }
        long size = f.length();
        assertTrue(size > 9L * 1024 * 1024);
        try {
            new FileUserStore(f);
            fail("a 9 MB users file was loaded");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("FAIL"));
            assertTrue(e.getMessage(), e.getMessage().contains("8388608"));
            assertTrue(e.getMessage(), e.getMessage().contains("new FileUserStore(file, maxFileBytes, logger)"));
            assertTrue(e.getMessage(), e.getMessage().contains(
                    "app-provided UserStore implementation backed by the application's database"));
            assertFalse(e.getMessage(), e.getMessage().contains("JDBC"));
        }
        assertEquals(size, f.length());
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            assertEquals('{', r.read());
        }
        // a raised limit loads it
        assertNotNull(new FileUserStore(f, 16L * 1024 * 1024, new RecordingLogger()).findByUsername("alice"));
    }

    // SEC-TOKEN-002 AC-6: the size limit is applied before the file is read, so a users file above the limit fails
    // start with the FAIL message of the length check instead of being read (a read would stop at the limit with
    // "cannot read" instead); the file is limit + 1 bytes, not a 3 GB sparse file, so the test also runs where the
    // file system has no sparse files and little free space (R2-T2-F06)
    @Test(timeout = 60000)
    public void SEC_TOKEN_002_AC6_hugeFileFailsBeforeItIsRead() throws Exception {
        File f = new File(tmp.getRoot(), "users.json");
        long size = FileUserStore.DEFAULT_MAX_FILE_BYTES + 1;
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            r.setLength(size);
        }
        try {
            new FileUserStore(f);
            fail("a users file above the default limit was loaded");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("FAIL"));
            assertTrue(e.getMessage(), e.getMessage().contains("8388608"));
            assertTrue(e.getMessage(), e.getMessage().contains("new FileUserStore(file, maxFileBytes, logger)"));
            assertTrue(e.getMessage(), e.getMessage().contains("has " + size + " bytes"));
        }
        assertEquals(size, f.length());
    }

    // FR-IAM-002: one file has one writer; a second instance over the same file that writes after the first changed
    // it is refused instead of rolling the file back (ver and password hash), and the file keeps the first's change
    @Test
    public void aSecondInstanceCannotRollTheFileBack() throws Exception {
        File f = new File(tmp.getRoot(), "users.json");
        FileUserStore a = new FileUserStore(f);
        AuthUser alice = a.create(row("alice"));
        RecordingLogger log = new RecordingLogger();
        FileUserStore b = new FileUserStore(f, FileUserStore.DEFAULT_MAX_FILE_BYTES, log);
        assertTrue(a.bumpTokenVersion(alice.getId()));
        assertTrue(a.updatePasswordHash(alice.getId(), alice.getPasswordHash(), "pbkdf2-sha256$1000$bmV3$bmV3"));
        byte[] afterA = Files.readAllBytes(f.toPath());
        try {
            b.create(row("bob"));
            fail("the second instance overwrote the first's change");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("another writer"));
        }
        try {
            b.update(b.findById(alice.getId()).toBuilder().attribute("x", "1").build());
            fail("the second instance overwrote the first's change");
        } catch (IOException expected) {
            // still refused
        }
        assertEquals(1, log.count("ERROR"));
        assertArrayEquals(afterA, Files.readAllBytes(f.toPath()));
        FileUserStore reopened = new FileUserStore(f);
        assertEquals(4, reopened.findById(alice.getId()).getTokenVersion());
        assertEquals("pbkdf2-sha256$1000$bmV3$bmV3", reopened.findById(alice.getId()).getPasswordHash());
        assertNull(reopened.findByUsername("bob"));
        assertNull(b.findByUsername("bob"));
        // the first instance keeps writing: its own writes are not foreign
        assertNotNull(a.create(row("carol")));
        assertNotNull(new FileUserStore(f).findByUsername("carol"));
    }

    // FR-IAM-002: list(search) ignores case on both sides, for a username stored with upper case through the public
    // store API
    @Test
    public void listSearchIgnoresTheCaseOfStoredNames() throws Exception {
        UserStore[] stores = {new InMemoryUserStore(), new FileUserStore(new File(tmp.getRoot(), "users.json"))};
        for (UserStore s : stores) {
            AuthUser alice = s.create(row("Alice"));
            s.create(row("bob"));
            for (String search : new String[] {"ali", "ALI", "Alice"}) {
                UserPage p = s.list(0, 10, search);
                assertEquals(s + " " + search, 1, p.getTotal());
                assertEquals(alice.getId(), p.getItems().get(0).getId());
            }
        }
    }

    // FR-IAM-002 / REL-IAM-004 (R2-M1-F03): two FileUserStores over one file spelled differently ("dir/users.json" and
    // "dir/./users.json") share the per-path write lock, so when both create a user at the same moment one create is
    // refused and the other is on disk: a create that returned normally is never lost and the file stays readable
    @Test(timeout = 120000)
    public void twoSpellingsOfOneFileShareTheWriteLock() throws Exception {
        for (int trial = 0; trial < 150; trial++) {
            File dir = tmp.newFolder("t" + trial);
            final File plain = new File(dir, "users.json");
            final File dotted = new File(new File(dir, "."), "users.json");
            final FileUserStore a = new FileUserStore(plain);
            final FileUserStore b = new FileUserStore(dotted);
            final java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
            final java.util.concurrent.ConcurrentHashMap<String, Object> created =
                    new java.util.concurrent.ConcurrentHashMap<>();
            Thread[] threads = new Thread[2];
            final FileUserStore[] stores = {a, b};
            for (int k = 0; k < 2; k++) {
                final int n = k;
                threads[k] = new Thread(() -> {
                    try {
                        start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        AuthUser u = stores[n].create(row("user" + n));
                        created.put("user" + n, u);
                    } catch (IOException refused) {
                        created.put("refused" + n, refused);
                    } catch (Exception e) {
                        created.put("error" + n, e);
                    }
                });
                threads[k].start();
            }
            for (Thread t : threads) {
                t.join(30_000);
                assertFalse(t.isAlive());
            }
            assertFalse(created.toString(), created.containsKey("error0") || created.containsKey("error1"));
            FileUserStore reopened;
            try {
                reopened = new FileUserStore(plain);
            } catch (IOException unreadable) {
                throw new AssertionError("trial " + trial + ": the file is unreadable after " + created.keySet(),
                        unreadable);
            }
            for (int k = 0; k < 2; k++) {
                if (created.containsKey("user" + k)) {
                    assertNotNull("trial " + trial + ": user" + k + " was created but is not on disk "
                            + created.keySet(), reopened.findByUsername("user" + k));
                }
            }
            assertTrue("trial " + trial + ": " + created.keySet(),
                    created.containsKey("user0") || created.containsKey("user1"));
        }
    }
}
