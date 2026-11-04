package com.snoworca.naruserve.utils.auth.rbac;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.utils.auth.internal.ScopeMatcher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** The scope grammar and covering rule (FR-IAM-005). */
public class ScopesTest {

    private static String repeat(char c, int n) {
        return new String(new char[n]).replace('\0', c);
    }

    // FR-IAM-005 AC-1: valid and invalid grants; an invalid one is an IllegalArgumentException at registration
    @Test
    public void FR_IAM_005_AC1_grantGrammar() {
        for (String ok : new String[] {"posts:read", "posts:*", "*", "auth-users:read", "auth-groups:*",
                "a-1:b-2", repeat('a', 32) + ":" + repeat('b', 32)}) {
            assertTrue(ok, Scopes.isValidGrant(ok));
            RolePolicy.builder().role("r", ok).build();
        }
        for (String bad : new String[] {"Posts:Read", "posts", "posts:read:own", "po*", "*:read", "posts::read",
                repeat('a', 33) + ":read", "posts read", "", ":read", "posts:", "posts_x:read", "posts.x:read",
                "**", "posts:**", "posts:re*"}) {
            assertFalse(bad, Scopes.isValidGrant(bad));
            try {
                RolePolicy.builder().role("r", bad);
                fail("registered " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(bad.isEmpty() ? "scope" : bad));
            }
        }
    }

    // FR-IAM-005 AC-2: the covering table
    @Test
    public void FR_IAM_005_AC2_coveringTable() {
        assertTrue(Scopes.covers("posts:*", "posts:write"));
        assertTrue(Scopes.covers("posts:*", "posts:*"));
        assertFalse(Scopes.covers("posts:read", "posts:write"));
        assertTrue(Scopes.covers("*", "anything:here"));
        assertTrue(Scopes.covers("*", "posts:*"));
        assertTrue(Scopes.covers("*", "*"));
        assertFalse(Scopes.covers("posts:read", "posts:*"));
        assertFalse(Scopes.covers("admin:*", "posts:write"));
        assertTrue(Scopes.covers("posts:read", "posts:read"));
        assertFalse(Scopes.covers("posts:*", "*"));
        // no action implication: write does not imply read
        assertFalse(Scopes.covers("posts:write", "posts:read"));
    }

    // FR-IAM-005 AC-4: reserved auth-* resources; the three admin resources with read, write or * are accepted
    @Test
    public void FR_IAM_005_AC4_reservedAuthResources() {
        try {
            RolePolicy.builder().role("x", "auth-reports:read").build();
            fail("reserved resource accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("auth-reports:read"));
        }
        assertFalse(Scopes.isValidGrant("auth-:read"));
        assertFalse(Scopes.isValidGrant("auth-x:*"));
        for (String resource : new String[] {"auth-users", "auth-groups", "auth-apikeys"}) {
            for (String action : new String[] {"read", "write", "*"}) {
                String s = resource + ":" + action;
                assertTrue(s, Scopes.isValidGrant(s));
                RolePolicy.builder().role("x", s).build();
            }
        }
        // an app may name a resource that merely contains "auth"
        assertTrue(Scopes.isValidGrant("oauth-clients:read"));
        assertTrue(Scopes.isValidGrant("author:write"));
    }

    /** A grant set that counts its lookups. */
    private static final class CountingSet extends HashSet<String> {
        int lookups;

        CountingSet(String... grants) {
            super(Arrays.asList(grants));
        }

        @Override
        public boolean contains(Object o) {
            lookups++;
            return super.contains(o);
        }
    }

    private static byte[] classBytes(Class<?> c) throws IOException {
        try (InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    // FR-IAM-005 AC-5: at most 3 hash lookups per required scope and no regular expression in the matcher
    @Test
    public void FR_IAM_005_AC5_atMostThreeLookupsAndNoRegex() throws IOException {
        String[][] cases = {
            {"posts:read", "posts:read"}, {"posts:*", "posts:write"}, {"*", "posts:write"}, {"other:read", "posts:read"},
        };
        for (String[] c : cases) {
            CountingSet grants = new CountingSet(c[0]);
            boolean allowed = ScopeMatcher.allows(grants, c[1]);
            assertEquals(Arrays.toString(c), !c[0].equals("other:read"), allowed);
            assertTrue(Arrays.toString(c) + " lookups=" + grants.lookups, grants.lookups <= 3);
        }
        CountingSet empty = new CountingSet();
        assertFalse(ScopeMatcher.allows(empty, "posts:read"));
        assertEquals(3, empty.lookups);
        String constants = new String(classBytes(ScopeMatcher.class), StandardCharsets.ISO_8859_1);
        assertFalse(constants.contains("java/util/regex"));
        for (String regexUse : new String[] {"matches", "replaceAll", "replaceFirst", "split", "Pattern"}) {
            assertFalse(regexUse, constants.contains(regexUse));
        }
    }

    // FR-IAM-005 AC-6: covers is usable without an Auth instance, and required scopes reject wildcards
    @Test
    public void FR_IAM_005_AC6_publicCoversCheck() {
        Set<String> grants = new HashSet<>(Arrays.asList("posts:*", "users:read"));
        int allowed = 0;
        for (String g : grants) {
            if (Scopes.covers(g, "posts:delete")) {
                allowed++;
            }
        }
        assertEquals(1, allowed);
        assertTrue(Scopes.isValidRequired("posts:read"));
        assertFalse(Scopes.isValidRequired("posts:*"));
        assertFalse(Scopes.isValidRequired("*"));
        assertFalse(Scopes.isValidRequired("auth-reports:read"));
        try {
            Scopes.covers("Posts:read", "posts:read");
            fail("an invalid grant was compared");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Posts:read"));
        }
    }
}
