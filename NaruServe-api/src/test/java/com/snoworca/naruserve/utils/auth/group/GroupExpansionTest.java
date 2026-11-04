package com.snoworca.naruserve.utils.auth.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.AuthUnavailableException;
import com.snoworca.naruserve.utils.auth.internal.EffectiveGrants;
import com.snoworca.naruserve.utils.auth.internal.GroupExpander;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** GroupStore, GroupLimits and the default expansion (DR-IAM-002, FR-IAM-007). */
public class GroupExpansionTest {

    private static Set<String> set(String... s) {
        return new LinkedHashSet<>(Arrays.asList(s));
    }

    /** A store of direct relations that counts its calls. */
    static class Edges implements GroupStore {
        final Map<String, Set<String>> members = new HashMap<>();
        final Map<String, Set<String>> parents = new HashMap<>();
        int groupsOfUserCalls;
        int parentsOfCalls;

        Edges member(String user, String... groups) {
            members.put(user, set(groups));
            return this;
        }

        Edges parent(String child, String... ps) {
            Set<String> s = parents.get(child);
            if (s == null) {
                parents.put(child, s = new LinkedHashSet<>());
            }
            s.addAll(Arrays.asList(ps));
            return this;
        }

        @Override
        public Set<String> groupsOfUser(String userId) {
            groupsOfUserCalls++;
            Set<String> s = members.get(userId);
            return s == null ? Collections.<String>emptySet() : s;
        }

        @Override
        public Set<String> parentsOf(Set<String> groupIds) {
            parentsOfCalls++;
            Set<String> out = new HashSet<>();
            for (String g : groupIds) {
                Set<String> p = parents.get(g);
                if (p != null) {
                    out.addAll(p);
                }
            }
            return out;
        }
    }

    // DR-IAM-002 AC-1 (Java half; the Kotlin object is in AuthFoundationKotlinTest): only the two direct methods
    @Test
    public void DR_IAM_002_AC1_twoDirectMethodsThrowExceptionAndAreUnannotated() throws Exception {
        GroupStore minimal = new GroupStore() {
            @Override
            public Set<String> groupsOfUser(String userId) {
                return Collections.singleton("eng");
            }

            @Override
            public Set<String> parentsOf(Set<String> groupIds) {
                return Collections.emptySet();
            }
        };
        assertEquals(set("eng"), minimal.effectiveGroupsOfUser("u"));
        assertEquals(Collections.emptySet(), minimal.rolesOfGroups(set("eng")));
        for (Method m : GroupStore.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            assertEquals(m.getName(), Collections.<Class<?>>singletonList(Exception.class),
                    Arrays.asList(m.getExceptionTypes()));
            assertNoNullness(m.getName() + " return", m.getAnnotatedReturnType());
            for (AnnotatedType p : m.getAnnotatedParameterTypes()) {
                assertNoNullness(m.getName() + " parameter", p);
            }
            assertFalse(m.getName() + " single-group parentsOf",
                    m.getName().equals("parentsOf") && m.getParameterTypes()[0] == String.class);
        }
        try {
            GroupStore.class.getMethod("parentsOf", String.class);
            fail("a single-group parentsOf exists");
        } catch (NoSuchMethodException expected) {
            // set-valued only, so no N+1 queries
        }
        int abstractCount = 0;
        for (Method m : GroupStore.class.getMethods()) {
            abstractCount += m.isDefault() ? 0 : 1;
        }
        assertEquals(2, abstractCount);
    }

    private static void assertNoNullness(String what, AnnotatedType t) {
        for (Annotation a : t.getAnnotations()) {
            assertFalse(what + " carries " + a, a.annotationType().getName().startsWith("org.jspecify"));
        }
    }

    // DR-IAM-002 AC-4: without rolesOfGroups a user's roles are its own; with it, members get the group's roles
    @Test
    public void DR_IAM_002_AC4_rolesOfGroupsConfersRoles() throws Exception {
        RolePolicy policy = RolePolicy.builder().role("editor", "posts:write").build();
        AuthUser alice = AuthUser.builder().id("alice").username("alice").roles("viewer").build();
        Edges plain = new Edges().member("alice", "backend").parent("backend", "eng");
        EffectiveGrants without = new EffectiveGrants(policy, plain, GroupLimits.defaults(), new RecordingLogger());
        assertEquals(set("viewer"), without.rolesOf(alice));
        assertEquals(Collections.emptySet(), without.scopesOf(alice));

        final Set<Set<String>> asked = new HashSet<>();
        Edges withRoles = new Edges() {
            @Override
            public Set<String> rolesOfGroups(Set<String> groupIds) {
                asked.add(new HashSet<>(groupIds));
                return groupIds.contains("eng") ? set("editor") : Collections.<String>emptySet();
            }
        }.member("alice", "backend").parent("backend", "eng").member("bob", "eng").member("carol", "sales");
        EffectiveGrants with = new EffectiveGrants(policy, withRoles, GroupLimits.defaults(), new RecordingLogger());
        assertEquals(set("viewer", "editor"), with.rolesOf(alice)); // via the child group backend
        assertEquals(set("posts:write"), with.scopesOf(alice));
        AuthUser bob = AuthUser.builder().id("bob").username("bob").build();
        assertEquals(set("editor"), with.rolesOf(bob)); // direct member of eng
        AuthUser carol = AuthUser.builder().id("carol").username("carol").build();
        assertEquals(Collections.emptySet(), with.rolesOf(carol));
        assertTrue(asked.contains(set("backend", "eng")));
        AuthUser noId = AuthUser.builder().username("dave").roles("viewer").build();
        assertEquals(set("viewer"), with.rolesOf(noId));
    }

    // FR-IAM-007 AC-1: level by level with one parentsOf call per level
    @Test
    public void FR_IAM_007_AC1_chainUsesOneParentsOfCallPerLevel() throws Exception {
        Edges e = new Edges().member("alice", "backend").parent("backend", "eng").parent("eng", "company");
        assertEquals(set("backend", "eng", "company"), e.effectiveGroupsOfUser("alice"));
        assertEquals(1, e.groupsOfUserCalls);
        assertEquals(3, e.parentsOfCalls);
    }

    // FR-IAM-007 AC-2: a diamond counts the shared parent once; a stored cycle terminates
    @Test
    public void FR_IAM_007_AC2_diamondAndCycleTerminate() throws Exception {
        Edges diamond = new Edges().member("u", "oncall").parent("oncall", "eng", "sales").parent("eng", "company")
                .parent("sales", "company");
        Set<String> groups = diamond.effectiveGroupsOfUser("u");
        assertEquals(new HashSet<>(Arrays.asList("oncall", "eng", "sales", "company")), groups);
        assertEquals(4, groups.size());
        Edges cycle = new Edges().member("u", "a").parent("a", "b").parent("b", "a");
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), cycle.effectiveGroupsOfUser("u"));
        assertTrue(cycle.parentsOfCalls <= 3);
    }

    private static Edges chain(int levelsAbove) {
        Edges e = new Edges().member("u", "g0");
        for (int i = 0; i < levelsAbove; i++) {
            e.parent("g" + i, "g" + (i + 1));
        }
        return e;
    }

    // FR-IAM-007 component: depth 8 above the direct group passes, 9 fails instead of truncating
    @Test
    public void depthLimitFailsInsteadOfTruncating() throws Exception {
        assertEquals(9, chain(8).effectiveGroupsOfUser("u").size());
        try {
            chain(9).effectiveGroupsOfUser("u");
            fail("expanded past depth 8");
        } catch (AuthUnavailableException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("depth 8"));
        }
        assertEquals(10, GroupExpander.expand(chain(9), "u", GroupLimits.of(9, 256)).size());
    }

    private static Edges wide(int groups) {
        Edges e = new Edges();
        String[] direct = new String[groups];
        for (int i = 0; i < groups; i++) {
            direct[i] = "g" + i;
        }
        return e.member("u", direct);
    }

    // FR-IAM-007 component: 256 groups pass, 257 fail, counting groups reached through parents too
    @Test
    public void sizeLimitFailsInsteadOfTruncating() throws Exception {
        assertEquals(256, wide(256).effectiveGroupsOfUser("u").size());
        try {
            wide(257).effectiveGroupsOfUser("u");
            fail("expanded past 256 groups");
        } catch (AuthUnavailableException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("256"));
        }
        Edges viaParents = wide(200);
        for (int i = 0; i < 57; i++) {
            viaParents.parent("g" + i, "p" + i);
        }
        try {
            viaParents.effectiveGroupsOfUser("u");
            fail("expanded past 256 groups through parents");
        } catch (AuthUnavailableException expected) {
            // 200 direct + 57 parents = 257
        }
    }

    // FR-IAM-007 AC-6: an overridden effectiveGroupsOfUser still has its size checked against GroupLimits by auth
    @Test
    public void FR_IAM_007_AC6_overriddenExpansionIsSizeChecked() throws Exception {
        final Set<String> big = new LinkedHashSet<>();
        for (int i = 0; i < 300; i++) {
            big.add("cte" + i);
        }
        Edges cte = new Edges() {
            @Override
            public Set<String> effectiveGroupsOfUser(String userId) {
                return big;
            }
        };
        EffectiveGrants grants = new EffectiveGrants(RolePolicy.builtin(), cte, GroupLimits.defaults(),
                new RecordingLogger());
        try {
            grants.groupsOf("u");
            fail("an oversized overridden result was used");
        } catch (AuthUnavailableException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("256"));
        }
        assertEquals(0, cte.parentsOfCalls);
        EffectiveGrants roomy = new EffectiveGrants(RolePolicy.builtin(), cte, GroupLimits.of(8, 300),
                new RecordingLogger());
        assertEquals(300, roomy.groupsOf("u").size());
        // the default expansion takes the configured limits, not only the defaults
        EffectiveGrants deep = new EffectiveGrants(RolePolicy.builtin(), chain(9), GroupLimits.of(9, 256),
                new RecordingLogger());
        assertEquals(10, deep.groupsOf("u").size());
    }

    // FR-IAM-007 component: GroupLimits defaults and validation
    @Test
    public void groupLimitsDefaultsAndValidation() {
        assertEquals(8, GroupLimits.defaults().getMaxDepth());
        assertEquals(256, GroupLimits.defaults().getMaxSize());
        assertEquals(3, GroupLimits.of(3, 10).getMaxDepth());
        assertEquals(10, GroupLimits.of(3, 10).getMaxSize());
        for (int[] bad : new int[][] {{-1, 10}, {3, 0}}) {
            try {
                GroupLimits.of(bad[0], bad[1]);
                fail("accepted " + Arrays.toString(bad));
            } catch (IllegalArgumentException expected) {
                // limits are positive (depth may be 0: direct groups only)
            }
        }
    }

    // DR-IAM-002 component: the group id rule used by the write paths
    @Test
    public void groupIdRule() {
        for (String ok : new String[] {"eng", "team.backend", "on-call_1", "a", repeat(64)}) {
            assertTrue(ok, com.snoworca.naruserve.utils.auth.internal.GroupIds.isValid(ok));
        }
        for (String bad : new String[] {"Eng", "", repeat(65), "a b", "a/b", "é"}) {
            assertFalse(bad, com.snoworca.naruserve.utils.auth.internal.GroupIds.isValid(bad));
        }
    }

    private static String repeat(int n) {
        return new String(new char[n]).replace('\0', 'a');
    }
}
