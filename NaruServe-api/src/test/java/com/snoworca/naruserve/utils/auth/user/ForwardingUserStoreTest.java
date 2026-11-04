package com.snoworca.naruserve.utils.auth.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** ForwardingUserStore, the decorator base class (IR-IAM-002). */
public class ForwardingUserStoreTest {

    /** A delegate that records calls and may declare atomic field writes. */
    static final class Recording extends UserStoreContractTest.MapStore {
        final List<String> calls = new ArrayList<>();
        final boolean atomic;

        Recording(boolean atomic) {
            this.atomic = atomic;
        }

        @Override
        public boolean setEnabled(String id, boolean enabled) throws Exception {
            calls.add("setEnabled");
            return atomic ? rows.put(id, rows.get(id).toBuilder().enabled(enabled).build()) != null
                    : super.setEnabled(id, enabled);
        }

        @Override
        public UserPage list(int offset, int limit, @Nullable String search) {
            List<AuthUser> all = new ArrayList<>(rows.values());
            return new UserPage(all.subList(offset, Math.min(all.size(), offset + limit)), all.size(), offset, limit);
        }

        @Override
        public boolean supportsAtomicFieldWrites() {
            return atomic;
        }
    }

    /** A caching decorator: a write through it invalidates its entry. */
    static final class Caching extends ForwardingUserStore {
        final Map<String, AuthUser> cache = new HashMap<>();
        int ownUpdates;

        Caching(UserStore delegate) {
            super(delegate);
        }

        @Override
        public @Nullable AuthUser findById(String id) throws Exception {
            AuthUser u = cache.get(id);
            if (u == null) {
                u = super.findById(id);
                if (u != null) {
                    cache.put(id, u);
                }
            }
            return u;
        }

        @Override
        public @Nullable AuthUser update(AuthUser user) throws Exception {
            ownUpdates++;
            cache.remove(user.getId());
            return super.update(user);
        }
    }

    // IR-IAM-002 AC-1: a subclass overriding nothing returns the delegate's results, list(0, 2, null) included
    @Test
    public void IR_IAM_002_AC1_forwardsEverything() throws Exception {
        Recording d = new Recording(false);
        for (String n : Arrays.asList("a", "b", "c")) {
            d.create(AuthUser.builder().username(n).build());
        }
        ForwardingUserStore f = new ForwardingUserStore(d) { };
        assertSame(d.findByUsername("b"), f.findByUsername("b"));
        assertSame(d.findById("1"), f.findById("1"));
        AuthUser created = f.create(AuthUser.builder().username("d").build());
        assertEquals("4", created.getId());
        assertSame(created, d.findById("4"));
        AuthUser renamed = created.toBuilder().username("dd").build();
        assertSame(renamed, f.update(renamed));
        assertTrue(f.delete("4"));
        assertNull(d.findById("4"));
        assertFalse(f.supportsAtomicFieldWrites());
        UserPage page = f.list(0, 2, null);
        assertEquals(d.list(0, 2, null).getItems(), page.getItems());
        assertEquals(3, page.getTotal());
        assertTrue(new ForwardingUserStore(new Recording(true)) { }.supportsAtomicFieldWrites());
    }

    // IR-IAM-002 AC-2: an atomic delegate's setEnabled is called once and the decorator's update is not
    @Test
    public void IR_IAM_002_AC2_atomicDelegateGetsTheFieldMethod() throws Exception {
        Recording d = new Recording(true);
        String id = d.create(AuthUser.builder().username("a").build()).getId();
        Caching c = new Caching(d);
        assertTrue(c.setEnabled(id, false));
        assertEquals(Collections.singletonList("setEnabled"), d.calls);
        assertEquals(0, c.ownUpdates);
        assertEquals(0, d.updates);
    }

    // IR-IAM-002 AC-3: a non-atomic delegate: the default runs through the decorator's findById and update, so its
    // cache entry is invalidated in the same call
    @Test
    public void IR_IAM_002_AC3_nonAtomicDelegateRunsTheDefaultThroughTheDecorator() throws Exception {
        Recording d = new Recording(false);
        String id = d.create(AuthUser.builder().username("a").build()).getId();
        Caching c = new Caching(d);
        assertTrue(c.findById(id).isEnabled());
        assertTrue(c.cache.containsKey(id));
        assertTrue(c.setEnabled(id, false));
        assertTrue(d.calls.isEmpty());
        assertEquals(1, c.ownUpdates);
        assertFalse(c.findById(id).isEnabled());
    }

    // IR-IAM-002 AC-4: ForwardingUserStore overrides every default method of UserStore
    @Test
    public void IR_IAM_002_AC4_everyDefaultIsOverridden() throws Exception {
        int defaults = 0;
        for (Method m : UserStore.class.getDeclaredMethods()) {
            if (!m.isDefault()) {
                continue;
            }
            defaults++;
            Method own = ForwardingUserStore.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
            assertTrue(m.getName(), Modifier.isPublic(own.getModifiers()));
        }
        assertEquals(7, defaults);
        // and the check fails for an interface with a default the class lacks
        assertFalse(declaresAll(Extended.class, ForwardingUserStore.class));
        assertTrue(declaresAll(UserStore.class, ForwardingUserStore.class));
    }

    interface Extended extends UserStore {
        default void exportUser(String id) {
        }
    }

    private static boolean declaresAll(Class<?> iface, Class<?> impl) {
        for (Method m : iface.getDeclaredMethods()) {
            if (m.isDefault()) {
                try {
                    impl.getDeclaredMethod(m.getName(), m.getParameterTypes());
                } catch (NoSuchMethodException e) {
                    return false;
                }
            }
        }
        return iface == UserStore.class || declaresAll(UserStore.class, impl);
    }

    // IR-IAM-002 AC-5: the Javadoc states the forwarding rule and that decorators extend the class
    @Test
    public void IR_IAM_002_AC5_javadocStatesTheRule() throws Exception {
        String src = new String(Files.readAllBytes(new File(
                "src/main/java/com/snoworca/naruserve/utils/auth/user/ForwardingUserStore.java").toPath()),
                StandardCharsets.UTF_8).replaceAll("\\s*\\n\\s*\\*\\s*", " ");
        assertTrue(src.contains("decorators must extend this class"));
        assertTrue(src.contains("they are forwarded only when {@code delegate().supportsAtomicFieldWrites()} is true"));
        assertTrue(src.contains("otherwise the {@code UserStore} default runs on this decorator"));
        assertTrue(Modifier.isAbstract(ForwardingUserStore.class.getModifiers()));
        // the rule the Javadoc states holds in the code: each of the five field methods is gated on the delegate
        assertTrue(src.contains("one rule for the five field methods"));
        Set<String> unused = new java.util.TreeSet<>();
        for (String field : Arrays.asList("updatePasswordHash", "changePassword", "setEnabled", "bumpTokenVersion",
                "setRoles")) {
            int at = src.indexOf("public boolean " + field + "(");
            int end = at < 0 ? -1 : src.indexOf("@Override", at);
            String body = at < 0 ? "" : src.substring(at, end < 0 ? src.length() : end);
            if (!body.contains("delegate.supportsAtomicFieldWrites() ? delegate." + field + "(")
                    || !body.contains("UserStore.super." + field + "(")) {
                unused.add(field);
            }
        }
        assertTrue("field methods not gated on supportsAtomicFieldWrites: " + unused, unused.isEmpty());
    }
}
