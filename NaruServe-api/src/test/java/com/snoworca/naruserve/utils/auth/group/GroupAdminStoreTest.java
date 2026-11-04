package com.snoworca.naruserve.utils.auth.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.AuthRejection;
import com.snoworca.naruserve.utils.auth.internal.ErrorBodies;
import com.snoworca.naruserve.utils.auth.internal.GroupEdges;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** GroupAdminStore, the edge check, InMemoryGroupStore and FileGroupStore (IR-IAM-004). */
public class GroupAdminStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private List<GroupAdminStore> stores() throws Exception {
        return Arrays.<GroupAdminStore>asList(new InMemoryGroupStore(), new FileGroupStore(new File(tmp.newFolder(), "groups.txt")));
    }

    /** backend ⊂ eng ⊂ company; alice in backend. */
    private static void fixture(GroupAdminStore s) throws Exception {
        for (String g : Arrays.asList("company", "eng", "backend")) {
            assertTrue(s.createGroup(g));
        }
        s.addParent("eng", "company");
        s.addParent("backend", "eng");
        s.addMember("backend", "alice");
        s.addMember("eng", "bob");
    }

    // IR-IAM-004 AC-1: the GroupAdminStore method set, removeUser a default
    @Test
    public void IR_IAM_004_AC1_methodSet() throws Exception {
        TreeSet<String> names = new TreeSet<>();
        for (Method m : GroupAdminStore.class.getDeclaredMethods()) {
            names.add(m.getName());
        }
        assertEquals(new TreeSet<>(Arrays.asList("createGroup", "deleteGroup", "listGroups", "groupExists",
                "addMember", "removeMember", "membersOf", "addParent", "removeParent", "childrenOf", "removeUser")),
                names);
        assertTrue(GroupAdminStore.class.getDeclaredMethod("removeUser", String.class).isDefault());
        assertTrue(GroupStore.class.isAssignableFrom(GroupAdminStore.class));
    }

    // IR-IAM-004 AC-2: deleteGroup removes the group's memberships and every edge it is child or parent of
    @Test
    public void IR_IAM_004_AC2_deleteRemovesMembershipsAndEdges() throws Exception {
        for (GroupAdminStore s : stores()) {
            fixture(s);
            assertTrue(s.effectiveGroupsOfUser("alice").contains("eng"));
            assertTrue(s.deleteGroup("eng"));
            assertFalse(s.deleteGroup("eng"));
            assertFalse(s.effectiveGroupsOfUser("alice").contains("eng"));
            assertFalse(s.effectiveGroupsOfUser("bob").contains("eng"));
            assertEquals(Collections.singleton("backend"), s.effectiveGroupsOfUser("alice"));
            assertTrue(s.childrenOf("company").isEmpty());
            assertTrue(s.parentsOf(Collections.singleton("backend")).isEmpty());
            assertEquals(Arrays.asList("backend", "company"), s.listGroups());
            assertFalse(s.groupExists("eng"));
        }
    }

    // IR-IAM-004 AC-3: addMember and removeMember twice equal once, without errors
    @Test
    public void IR_IAM_004_AC3_idempotentMembership() throws Exception {
        for (GroupAdminStore s : stores()) {
            s.createGroup("eng");
            s.addMember("eng", "u1");
            s.addMember("eng", "u1");
            assertEquals(Collections.singleton("u1"), s.membersOf("eng"));
            assertEquals(Collections.singleton("eng"), s.groupsOfUser("u1"));
            s.removeMember("eng", "u1");
            s.removeMember("eng", "u1");
            assertTrue(s.membersOf("eng").isEmpty());
            assertTrue(s.groupsOfUser("u1").isEmpty());
            s.addParent("eng", createAndReturn(s, "corp"));
            s.addParent("eng", "corp");
            assertEquals(Collections.singleton("corp"), s.parentsOf(Collections.singleton("eng")));
        }
    }

    private static String createAndReturn(GroupAdminStore s, String g) throws Exception {
        s.createGroup(g);
        return g;
    }

    /** An admin store that records whether addParent was reached. */
    static final class Recording extends DelegatingAdminStore {
        final AtomicInteger addParents = new AtomicInteger();

        Recording(GroupAdminStore d) {
            super(d);
        }

        @Override
        public void addParent(String childId, String parentId) throws Exception {
            addParents.incrementAndGet();
            super.addParent(childId, parentId);
        }
    }

    private static int status(final GroupAdminStore store, final String child, final String parent) {
        TestApp t = TestApp.create();
        t.app().post("/edge", (req, res) -> {
            GroupEdges.addParent(store, child, parent, GroupLimits.defaults());
            res.send("added");
        });
        t.app().onError((err, req, res) -> ErrorBodies.write(req, res, (AuthRejection) err));
        ClientResponse r = t.send("POST", "/edge");
        if (r.status() == 409) {
            assertTrue(r.text(), r.text().contains("\"code\":\"group_cycle\""));
        }
        return r.status();
    }

    // IR-IAM-004 AC-4: a cycle and a self edge are refused with 409 and addParent is not called
    @Test
    public void IR_IAM_004_AC4_cycleAndSelfEdgeAre409() throws Exception {
        Recording s = new Recording(new InMemoryGroupStore());
        fixture(s);
        s.addParents.set(0);
        assertEquals(409, status(s, "eng", "backend"));
        assertEquals(409, status(s, "company", "backend"));
        assertEquals(409, status(s, "eng", "eng"));
        assertEquals(0, s.addParents.get());
        s.createGroup("ops");
        assertEquals(200, status(s, "ops", "company"));
        assertEquals(1, s.addParents.get());
    }

    // IR-IAM-004 AC-5: an edge whose chain would exceed depth 8 is refused with 409 and no addParent call
    @Test
    public void IR_IAM_004_AC5_depthLimitIs409() throws Exception {
        Recording s = new Recording(new InMemoryGroupStore());
        // chain g0 ⊂ g1 ⊂ ... ⊂ g8: 8 edges, the limit
        for (int i = 0; i <= 9; i++) {
            s.createGroup("g" + i);
        }
        for (int i = 0; i < 8; i++) {
            s.addParent("g" + i, "g" + (i + 1));
        }
        s.addParents.set(0);
        assertEquals(409, status(s, "g8", "g9"));   // above: 0, below g8: 8 -> 9 edges
        s.createGroup("h0");
        s.createGroup("h1");
        s.addParent("h0", "h1");
        s.addParents.set(0);
        assertEquals(409, status(s, "h1", "g1"));   // above g1: 7, below h1: 1 -> 9 edges
        assertEquals(0, s.addParents.get());
        assertEquals(200, status(s, "h1", "g2"));   // above g2: 6, below h1: 1 -> 8 edges
        assertEquals(1, s.addParents.get());
    }

    // IR-IAM-004 AC-6: the in-memory store checks and inserts under one lock; the file store survives a reopen
    @Test
    public void IR_IAM_004_AC6_oneLockAndDurableFile() throws Exception {
        for (int round = 0; round < 20; round++) {
            final InMemoryGroupStore s = new InMemoryGroupStore();
            s.createGroup("a");
            s.createGroup("b");
            final CountDownLatch go = new CountDownLatch(1);
            final List<Throwable> refused = Collections.synchronizedList(new ArrayList<Throwable>());
            Thread x = new Thread(() -> edge(s, go, "a", "b", refused));
            Thread y = new Thread(() -> edge(s, go, "b", "a", refused));
            x.start();
            y.start();
            go.countDown();
            x.join();
            y.join();
            assertEquals(1, refused.size());
            assertTrue(refused.get(0) instanceof AuthRejection);
            assertEquals(409, ((AuthRejection) refused.get(0)).getStatus());
        }
        // the race forced: x has passed the check for a -> b and waits before its insert; y's b -> a must wait for the
        // same lock (BLOCKED), then sees x's edge and is refused
        final InMemoryGroupStore s = new InMemoryGroupStore();
        s.createGroup("a");
        s.createGroup("b");
        final CountDownLatch checked = new CountDownLatch(1);
        final CountDownLatch insert = new CountDownLatch(1);
        final AtomicInteger hooked = new AtomicInteger();
        s.afterEdgeCheck(() -> {
            if (hooked.getAndIncrement() == 0) {
                checked.countDown();
                try {
                    insert.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        final CountDownLatch now = new CountDownLatch(0);
        final List<Throwable> refused = Collections.synchronizedList(new ArrayList<Throwable>());
        Thread x = new Thread(() -> edge(s, now, "a", "b", refused));
        x.start();
        assertTrue(checked.await(10, java.util.concurrent.TimeUnit.SECONDS));
        Thread y = new Thread(() -> edge(s, now, "b", "a", refused));
        y.start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (y.getState() != Thread.State.BLOCKED && y.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals("b -> a waits for the lock a -> b holds over its check and insert", Thread.State.BLOCKED,
                    y.getState());
        } finally {
            insert.countDown();
            x.join(10_000);
            y.join(10_000);
        }
        assertEquals(1, refused.size());
        assertEquals(409, ((AuthRejection) refused.get(0)).getStatus());
        assertEquals(Collections.singleton("a"), s.childrenOf("b"));
        assertTrue(s.childrenOf("a").isEmpty());
        File f = new File(tmp.newFolder(), "groups.txt");
        FileGroupStore first = new FileGroupStore(f);
        fixture(first);
        first.addMember("company", "user\twith\nodd\\chars");
        FileGroupStore again = new FileGroupStore(f);
        for (String g : first.listGroups()) {
            assertEquals(g, first.membersOf(g), again.membersOf(g));
            assertEquals(g, first.childrenOf(g), again.childrenOf(g));
            assertEquals(g, first.parentsOf(Collections.singleton(g)), again.parentsOf(Collections.singleton(g)));
        }
        assertEquals(first.listGroups(), again.listGroups());
        assertEquals(first.effectiveGroupsOfUser("alice"), again.effectiveGroupsOfUser("alice"));
        assertTrue(again.groupsOfUser("user\twith\nodd\\chars").contains("company"));
    }

    private static void edge(InMemoryGroupStore s, CountDownLatch go, String child, String parent,
            List<Throwable> refused) {
        try {
            go.await();
            s.addParent(child, parent);
        } catch (AuthRejection e) {
            refused.add(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Forwards every GroupAdminStore method; a test base. */
    static class DelegatingAdminStore implements GroupAdminStore {
        private final GroupAdminStore d;

        DelegatingAdminStore(GroupAdminStore d) {
            this.d = d;
        }

        @Override
        public Set<String> groupsOfUser(String userId) throws Exception {
            return d.groupsOfUser(userId);
        }

        @Override
        public Set<String> parentsOf(Set<String> groupIds) throws Exception {
            return d.parentsOf(groupIds);
        }

        @Override
        public boolean createGroup(String groupId) throws Exception {
            return d.createGroup(groupId);
        }

        @Override
        public boolean deleteGroup(String groupId) throws Exception {
            return d.deleteGroup(groupId);
        }

        @Override
        public List<String> listGroups() throws Exception {
            return d.listGroups();
        }

        @Override
        public boolean groupExists(String groupId) throws Exception {
            return d.groupExists(groupId);
        }

        @Override
        public void addMember(String groupId, String userId) throws Exception {
            d.addMember(groupId, userId);
        }

        @Override
        public void removeMember(String groupId, String userId) throws Exception {
            d.removeMember(groupId, userId);
        }

        @Override
        public Set<String> membersOf(String groupId) throws Exception {
            return d.membersOf(groupId);
        }

        @Override
        public void addParent(String childId, String parentId) throws Exception {
            d.addParent(childId, parentId);
        }

        @Override
        public void removeParent(String childId, String parentId) throws Exception {
            d.removeParent(childId, parentId);
        }

        @Override
        public Set<String> childrenOf(String groupId) throws Exception {
            return d.childrenOf(groupId);
        }
    }

    // removeUser default walks the user's groups
    @Test
    public void removeUserDefault() throws Exception {
        DelegatingAdminStore s = new DelegatingAdminStore(new InMemoryGroupStore());
        fixture(s);
        s.addMember("company", "alice");
        s.removeUser("alice");
        assertTrue(s.groupsOfUser("alice").isEmpty());
        try {
            s.addMember("nope", "x");
            fail("a member of a missing group was accepted");
        } catch (IllegalArgumentException expected) {
            // the group must exist
        }
    }

    // IR-IAM-004 (R2-M1-F04; REL-IAM-004 AC-4 is AtomicFilesTest): the groups file is read within a size limit (8 MB by default) that is
    // compared with the file length before anything is read, so a huge or corrupt (sparse 900 MB) groups file fails
    // construction with a FAIL ... above the limit message instead of exhausting the heap, and is left as it is
    @Test(timeout = 60000)
    public void hugeGroupsFileFailsBeforeItIsRead() throws Exception {
        File f = new File(tmp.newFolder(), "groups.txt");
        long size = 900L * 1024 * 1024;
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "rw")) {
            r.write((FileGroupStore.HEADER + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            r.setLength(size);
        }
        try {
            new FileGroupStore(f, GroupLimits.defaults(), new com.snoworca.naruserve.testing.RecordingLogger());
            fail("a 900 MB groups file was opened");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("FAIL"));
            assertTrue(e.getMessage(), e.getMessage().contains("above the limit of " + 8L * 1024 * 1024 + " bytes"));
        }
        assertEquals(size, f.length());
    }

    // IR-IAM-004 / REL-IAM-004 (R2-M1-F04): the size limit of new FileGroupStore(file, limits, maxFileBytes, logger)
    // bounds the read and the writes: a change that would grow the file above it is refused with an IOException and
    // memory and disk keep the previous state, so the file can always be read back with the same limit
    @Test
    public void fileGroupStoreSizeLimitBoundsReadsAndWrites() throws Exception {
        assertEquals(8L * 1024 * 1024, FileGroupStore.DEFAULT_MAX_FILE_BYTES);
        File f = new File(tmp.newFolder(), "groups.txt");
        com.snoworca.naruserve.testing.RecordingLogger log = new com.snoworca.naruserve.testing.RecordingLogger();
        FileGroupStore s = new FileGroupStore(f, GroupLimits.defaults(), 64, log);
        assertTrue(s.createGroup("eng"));
        long before = f.length();
        try {
            s.addMember("eng", "a-user-whose-membership-does-not-fit-in-64-bytes-of-file");
            fail("a change above the limit was written");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("above the limit of 64 bytes"));
        }
        assertTrue(s.membersOf("eng").isEmpty());
        assertEquals(before, f.length());
        assertEquals(Collections.singletonList("eng"), new FileGroupStore(f, GroupLimits.defaults(), 64, log)
                .listGroups());
        try {
            new FileGroupStore(f, GroupLimits.defaults(), 8, log);
            fail("a file above the limit was opened");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("FAIL"));
            assertTrue(e.getMessage(), e.getMessage().contains("above the limit of 8 bytes"));
        }
        try {
            new FileGroupStore(f, GroupLimits.defaults(), 0, log);
            fail("a zero limit was accepted");
        } catch (IllegalArgumentException expected) {
            // at least one byte
        }
    }
}
