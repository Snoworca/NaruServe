package com.snoworca.naruserve.utils.auth.rbac;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.group.GroupLimits;
import com.snoworca.naruserve.utils.auth.internal.EffectiveGrants;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** RolePolicy, the built-in admin role and RoleStore rows (FR-IAM-006). */
public class RolePolicyTest {

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    // FR-IAM-006 component: app roles merge into the built-in policy whose admin holds *
    @Test
    public void appRolesMergeIntoTheBuiltInPolicy() {
        RolePolicy p = RolePolicy.builder().role("editor", "posts:read", "posts:write").build();
        assertEquals(set("*"), p.scopesOf("admin"));
        assertEquals(set("posts:read", "posts:write"), p.scopesOf("editor"));
        assertEquals(Collections.emptySet(), p.scopesOf("nobody"));
        assertEquals(set("admin", "editor"), p.roles());
        assertEquals(set("*", "posts:read", "posts:write"), p.grantedScopes());
        assertEquals(set("*"), RolePolicy.builtin().scopesOf("admin"));
        // role(...) twice for one name is a union
        RolePolicy twice = RolePolicy.builder().role("e", "a:read").role("e", "b:read").build();
        assertEquals(set("a:read", "b:read"), twice.scopesOf("e"));
        try {
            p.scopesOf("admin").add("x:y");
            fail("policy scopes are modifiable");
        } catch (UnsupportedOperationException expected) {
            // immutable policy
        }
    }

    // FR-IAM-006 AC-2: redefining admin logs one WARN and admin still holds *
    @Test
    public void FR_IAM_006_AC2_redefiningAdminIsAUnionWithOneWarn() {
        RecordingLogger log = new RecordingLogger();
        RolePolicy p = RolePolicy.builder().logger(log).role("admin", "posts:read").build();
        assertEquals(1, log.count("WARN"));
        assertTrue(log.lines.get(0), log.lines.get(0).contains("admin"));
        assertEquals(set("*", "posts:read"), p.scopesOf("admin"));
        for (String required : new String[] {"posts:write", "auth-users:write", "anything:here"}) {
            boolean covered = false;
            for (String g : p.scopesOf("admin")) {
                covered |= Scopes.covers(g, required);
            }
            assertTrue(required, covered);
        }
        RecordingLogger quiet = new RecordingLogger();
        RolePolicy.builder().logger(quiet).role("editor", "posts:read").build();
        RolePolicy.builder().logger(quiet).replaceBuiltinRoles().role("admin", "posts:read").build();
        assertEquals(0, quiet.lines.size());
    }

    // FR-IAM-006 component: replaceBuiltinRoles drops the built-in admin
    @Test
    public void replaceBuiltinRolesDropsAdmin() {
        RolePolicy p = RolePolicy.builder().replaceBuiltinRoles().role("editor", "posts:read").build();
        assertEquals(Collections.emptySet(), p.scopesOf("admin"));
        assertEquals(set("editor"), p.roles());
    }

    // FR-IAM-006 AC-4: an invalid or reserved scope throws before any Auth exists
    @Test
    public void FR_IAM_006_AC4_invalidOrReservedScopeThrows() {
        for (String bad : new String[] {"Posts:read", "posts", "auth-reports:read", "*:read"}) {
            try {
                RolePolicy.builder().role("x", bad).build();
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(bad));
            }
        }
        try {
            RolePolicy.builder().role("", "posts:read");
            fail("accepted an empty role name");
        } catch (IllegalArgumentException expected) {
            // role names are non-empty
        }
    }

    // FR-IAM-006 AC-5: a RoleStore lambda's reserved row is filtered with exactly one WARN for that role
    @Test
    public void FR_IAM_006_AC5_reservedRoleStoreRowsAreFilteredWithOneWarn() throws Exception {
        RecordingLogger log = new RecordingLogger();
        RoleStore db = role -> role.equals("x") ? set("posts:read", "auth-reports:read") : set("posts:write");
        EffectiveGrants grants = new EffectiveGrants(db, null, GroupLimits.defaults(), log);
        for (int request = 0; request < 5; request++) {
            assertEquals(set("posts:read"), grants.scopesOfRoles(set("x")));
        }
        assertEquals(1, log.count("WARN"));
        assertTrue(log.lines.get(0), log.lines.get(0).contains("x") && log.lines.get(0).contains("auth-reports:read"));
        assertEquals(set("posts:write"), grants.scopesOfRoles(set("y")));
        assertEquals(set("posts:read", "posts:write"), grants.scopesOfRoles(set("x", "y")));
        assertEquals(1, log.count("WARN"));
        // a RolePolicy is already valid and logs nothing through the same path
        RecordingLogger quiet = new RecordingLogger();
        EffectiveGrants policy = new EffectiveGrants(RolePolicy.builtin(), null, GroupLimits.defaults(), quiet);
        assertEquals(set("*"), policy.scopesOfRoles(set("admin")));
        assertEquals(0, quiet.lines.size());
    }

    // FR-IAM-006 component: a store answering a null set has failed (503), never an empty grant
    @Test
    public void nullRoleStoreAnswerIsAFailure() {
        RoleStore broken = role -> null;
        EffectiveGrants grants = new EffectiveGrants(broken, null, GroupLimits.defaults(), new RecordingLogger());
        try {
            grants.scopesOfRoles(set("x"));
            fail("a null answer became a grant");
        } catch (Exception e) {
            assertTrue(String.valueOf(e), e instanceof com.snoworca.naruserve.utils.auth.AuthUnavailableException);
        }
    }

    // FR-IAM-006 component: a throwing RoleStore propagates; the guard answers it 503 (that AC waits for the guard)
    @Test
    public void throwingRoleStorePropagates() {
        final IllegalStateException down = new IllegalStateException("db down");
        RoleStore db = role -> {
            throw down;
        };
        EffectiveGrants grants = new EffectiveGrants(db, null, GroupLimits.defaults(), new RecordingLogger());
        Exception propagated = null;
        try {
            grants.scopesOfRoles(set("x"));
            fail("store error swallowed");
        } catch (Exception e) {
            assertEquals(down, e);
            propagated = e;
        }
        // the propagated exception keeps the store's message (not wrapped or replaced)
        assertFalse(propagated.getMessage().isEmpty());
        assertEquals("db down", propagated.getMessage());
    }
}
