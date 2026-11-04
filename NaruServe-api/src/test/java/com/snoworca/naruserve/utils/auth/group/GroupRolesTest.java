package com.snoworca.naruserve.utils.auth.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The built-in group stores give roles to groups (FR-IAM-016). */
public class GroupRolesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The role methods both built-in stores share, so one test body covers both. */
    private interface Roles {
        GroupAdminStore store();

        void grant(String group, String role) throws Exception;

        void revoke(String group, String role) throws Exception;

        Set<String> of(String group);
    }

    private static Roles of(InMemoryGroupStore s) {
        return new Roles() {
            public GroupAdminStore store() {
                return s;
            }

            public void grant(String group, String role) {
                s.grantRole(group, role);
            }

            public void revoke(String group, String role) {
                s.revokeRole(group, role);
            }

            public Set<String> of(String group) {
                return s.rolesOf(group);
            }
        };
    }

    private static Roles of(FileGroupStore s) {
        return new Roles() {
            public GroupAdminStore store() {
                return s;
            }

            public void grant(String group, String role) throws IOException {
                s.grantRole(group, role);
            }

            public void revoke(String group, String role) throws IOException {
                s.revokeRole(group, role);
            }

            public Set<String> of(String group) {
                return s.rolesOf(group);
            }
        };
    }

    private List<Roles> stores() throws IOException {
        return Arrays.asList(of(new InMemoryGroupStore()), of(new FileGroupStore(new File(tmp.newFolder(), "g.txt"))));
    }

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    // FR-IAM-016 AC-1: grant, rolesOf, rolesOfGroups as a union, idempotent grant, revoke, empty for a group without roles
    @Test
    public void FR_IAM_016_AC1_grantRevokeAndUnion() throws Exception {
        for (Roles r : stores()) {
            GroupAdminStore s = r.store();
            s.createGroup("editors");
            s.createGroup("other");
            r.grant("editors", "editor");
            r.grant("editors", "editor");
            assertEquals(set("editor"), r.of("editors"));
            assertEquals(set("editor"), s.rolesOfGroups(set("editors", "other")));
            assertEquals(Collections.emptySet(), r.of("other"));
            r.grant("other", "auditor");
            assertEquals(set("editor", "auditor"), s.rolesOfGroups(set("editors", "other")));
            r.revoke("editors", "editor");
            assertEquals(Collections.emptySet(), r.of("editors"));
            assertEquals(set("auditor"), s.rolesOfGroups(set("editors", "other")));
        }
    }

    // FR-IAM-016 AC-2: an unknown group or an empty or control-character role is refused and changes nothing;
    // deleteGroup removes the group's roles
    @Test
    public void FR_IAM_016_AC2_refusalsAndDelete() throws Exception {
        for (Roles r : stores()) {
            GroupAdminStore s = r.store();
            s.createGroup("eng");
            for (String[] bad : new String[][] {{"nope", "editor"}, {"eng", ""}, {"eng", "a\tb"}, {"eng", "a\rb"},
                    {"eng", "a\nb"}}) {
                try {
                    r.grant(bad[0], bad[1]);
                    fail("granted " + Arrays.toString(bad));
                } catch (IllegalArgumentException expected) {
                    // refused
                }
            }
            assertEquals(Collections.emptySet(), r.of("eng"));
            r.grant("eng", "editor");
            assertTrue(s.deleteGroup("eng"));
            s.createGroup("eng");
            assertEquals(Collections.emptySet(), r.of("eng"));
        }
    }

    // FR-IAM-016 AC-4: the file keeps the roles across a restart; a write over the size limit leaves memory unchanged
    @Test
    public void FR_IAM_016_AC4_fileKeepsRolesAndAFailedWriteChangesNothing() throws Exception {
        File f = new File(tmp.newFolder(), "groups.txt");
        FileGroupStore first = new FileGroupStore(f);
        first.createGroup("eng");
        first.grantRole("eng", "editor");
        assertTrue(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
                .contains("editor"));
        FileGroupStore again = new FileGroupStore(f);
        assertEquals(set("editor"), again.rolesOf("eng"));
        long size = f.length();
        FileGroupStore tight = new FileGroupStore(f, GroupLimits.defaults(), size + 4, new RecordingLogger());
        try {
            tight.grantRole("eng", "a-role-name-longer-than-four-bytes");
            fail("a grant above the size limit was accepted");
        } catch (IOException expected) {
            // refused
        }
        assertEquals(set("editor"), tight.rolesOf("eng"));
        assertFalse(new FileGroupStore(f).rolesOf("eng").contains("a-role-name-longer-than-four-bytes"));
    }
}
