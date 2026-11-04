package com.snoworca.naruserve.utils.auth.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** The UserStore SPI shape and contract (IR-IAM-001). */
public class UserStoreContractTest {

    /** Only the five required methods: a SINGLE_INSTANCE store over a map. */
    static class MapStore implements UserStore {
        final Map<String, AuthUser> rows = new TreeMap<>();
        int nextId = 1;
        int updates;

        @Override
        public @Nullable AuthUser findByUsername(String username) {
            for (AuthUser u : rows.values()) {
                if (u.getUsername().equals(username)) {
                    return u;
                }
            }
            return null;
        }

        @Override
        public @Nullable AuthUser findById(String id) {
            return rows.get(id);
        }

        @Override
        public AuthUser create(AuthUser user) {
            AuthUser stored = user.toBuilder().id(String.valueOf(nextId++)).build();
            rows.put(stored.getId(), stored);
            return stored;
        }

        @Override
        public @Nullable AuthUser update(AuthUser user) {
            updates++;
            if (!rows.containsKey(user.getId())) {
                return null;
            }
            rows.put(user.getId(), user);
            return user;
        }

        @Override
        public boolean delete(String id) {
            return rows.remove(id) != null;
        }
    }

    private static final List<String> REQUIRED = Arrays.asList("findByUsername", "findById", "create", "update",
            "delete");
    private static final List<String> FIELD = Arrays.asList("updatePasswordHash", "changePassword", "setEnabled",
            "bumpTokenVersion", "setRoles");

    private static Method only(String name) {
        Method found = null;
        for (Method m : UserStore.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                assertNull("one " + name, found);
                found = m;
            }
        }
        assertNotNull(name, found);
        return found;
    }

    private static void noNonNull(AnnotatedType t, String where) {
        assertFalse(where, t.isAnnotationPresent(NonNull.class));
    }

    // IR-IAM-001 AC-1: the five required methods throw Exception; references are @Nullable or @NullMarked-non-null
    @Test
    public void IR_IAM_001_AC1_fiveRequiredMethods() throws Exception {
        for (String name : REQUIRED) {
            Method m = only(name);
            assertTrue(name, Modifier.isAbstract(m.getModifiers()));
            assertEquals(name, Collections.singletonList(Exception.class), Arrays.asList(m.getExceptionTypes()));
            noNonNull(m.getAnnotatedReturnType(), name);
            for (AnnotatedType p : m.getAnnotatedParameterTypes()) {
                noNonNull(p, name);
            }
        }
        assertTrue(only("findByUsername").getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        assertTrue(only("findById").getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        assertFalse(only("create").getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        assertTrue(UserStore.class.getPackage().isAnnotationPresent(org.jspecify.annotations.NullMarked.class));
        MapStore s = new MapStore();
        AuthUser a = s.create(AuthUser.builder().username("alice").build());
        assertEquals("1", a.getId());
        assertEquals(a, s.findByUsername("alice"));
    }

    // IR-IAM-001 AC-2: the default field methods and the listing default; supportsAtomicFieldWrites() is false
    @Test
    public void IR_IAM_001_AC2_defaultMethods() throws Exception {
        for (String name : FIELD) {
            assertTrue(name, only(name).isDefault());
        }
        Method list = only("list");
        assertTrue(list.isDefault());
        assertEquals(Arrays.asList(int.class, int.class, String.class), Arrays.asList(list.getParameterTypes()));
        assertTrue(list.getAnnotatedParameterTypes()[2].isAnnotationPresent(Nullable.class));
        assertEquals(UserPage.class, list.getReturnType());
        assertEquals(Collections.singletonList(Exception.class), Arrays.asList(list.getExceptionTypes()));
        for (String getter : Arrays.asList("getItems", "getTotal", "getOffset", "getLimit")) {
            assertNotNull(UserPage.class.getMethod(getter));
        }
        MapStore s = new MapStore();
        assertFalse(s.supportsAtomicFieldWrites());
        AuthUser a = s.create(AuthUser.builder().username("alice").passwordHash("h1").build());
        String id = a.getId();
        assertTrue(s.setEnabled(id, false));
        assertFalse(s.findById(id).isEnabled());
        assertTrue(s.bumpTokenVersion(id));
        assertEquals(1, s.findById(id).getTokenVersion());
        assertTrue(s.setRoles(id, new java.util.LinkedHashSet<>(Arrays.asList("editor"))));
        assertEquals(Collections.singleton("editor"), s.findById(id).getRoles());
        assertFalse(s.updatePasswordHash(id, "wrong", "h2"));
        assertTrue(s.updatePasswordHash(id, "h1", "h2"));
        assertEquals(1, s.findById(id).getTokenVersion());
        assertFalse(s.changePassword(id, "h2", 0, "h3"));
        assertTrue(s.changePassword(id, "h2", 1, "h3"));
        assertEquals(2, s.findById(id).getTokenVersion());
        assertEquals("h3", s.findById(id).getPasswordHash());
        assertFalse(s.setEnabled("missing", true));
        try {
            s.list(0, 10, null);
            fail("the default list is not implemented");
        } catch (UnsupportedOperationException expected) {
            // the shipped stores override it
        }
    }

    // IR-IAM-001 component (security write confirmation): a store whose update silently drops the write makes the
    // default field method throw AuthUnavailableException with security_write_conflict
    @Test
    public void unconfirmedWriteIsASecurityWriteConflict() throws Exception {
        MapStore s = new MapStore() {
            @Override
            public @Nullable AuthUser update(AuthUser user) {
                return user; // claims success, writes nothing
            }
        };
        AuthUser a = s.create(AuthUser.builder().username("alice").passwordHash("h1").build());
        try {
            s.changePassword(a.getId(), "h1", 0, "h2");
            fail("an unapplied password change succeeded");
        } catch (com.snoworca.naruserve.utils.auth.AuthUnavailableException e) {
            assertEquals("security_write_conflict", e.getCode());
        }
    }

    private static String javadoc(String source, String marker) {
        int end = source.indexOf(marker);
        int start = source.lastIndexOf("/**", end);
        assertTrue(marker, start >= 0 && end > start);
        return source.substring(start, end).replaceAll("\\s*\\n\\s*\\*\\s*", " ").replaceAll("<[^>]+>", "");
    }

    // IR-IAM-001 AC-3: the Javadoc states the id, ver, duplicate and consistency invariants
    @Test
    public void IR_IAM_001_AC3_invariantsInTheJavadoc() throws Exception {
        String src = new String(Files.readAllBytes(new File(
                "src/main/java/com/snoworca/naruserve/utils/auth/user/UserStore.java").toPath()), StandardCharsets.UTF_8);
        String doc = javadoc(src, "public interface UserStore");
        for (String phrase : Arrays.asList("Ids are Strings and a deleted id is never reused",
                "ver only increases; a store never lowers or restores it",
                "create and update throw DuplicateUserException on a unique-key violation",
                "ver should be read-after-write consistent; this is recommended under MULTI_INSTANCE")) {
            assertTrue(phrase, doc.contains(phrase));
        }
    }

    // IR-IAM-001 AC-4: findByUsername gets the normalized name; exactly the twelve methods, no alternate lookups
    @Test
    public void IR_IAM_001_AC4_exactlyTwelveMethods() {
        TreeSet<String> names = new TreeSet<>();
        for (Method m : UserStore.class.getDeclaredMethods()) {
            if (!m.isSynthetic()) {
                names.add(m.getName());
            }
        }
        List<String> expected = new ArrayList<>(REQUIRED);
        expected.addAll(FIELD);
        expected.add("supportsAtomicFieldWrites");
        expected.add("list");
        assertEquals(new TreeSet<>(expected), names);
        assertEquals(12, UserStore.class.getDeclaredMethods().length);
        for (String n : names) {
            String lower = n.toLowerCase(java.util.Locale.ROOT);
            assertFalse(n, lower.contains("email") || lower.contains("phone") || lower.contains("loginkey"));
        }
    }

    // IR-IAM-001 component (listing): InMemoryUserStore pages its users (the file store and admin A1 come later)
    @Test
    public void inMemoryStoreListsPages() throws Exception {
        InMemoryUserStore s = new InMemoryUserStore();
        for (String n : Arrays.asList("ann", "bob", "cid")) {
            s.create(AuthUser.builder().username(n).build());
        }
        UserPage first = s.list(0, 2, null);
        assertEquals(2, first.getItems().size());
        assertEquals(3, first.getTotal());
        assertEquals(0, first.getOffset());
        assertEquals(2, first.getLimit());
        UserPage rest = s.list(2, 2, null);
        assertEquals(1, rest.getItems().size());
        assertEquals("cid", rest.getItems().get(0).getUsername());
        assertEquals(1, s.list(0, 10, "BO").getTotal());
        try {
            s.create(AuthUser.builder().username("ann").build());
            fail("duplicate accepted");
        } catch (DuplicateUserException expected) {
            // unique usernames
        }
    }
}
