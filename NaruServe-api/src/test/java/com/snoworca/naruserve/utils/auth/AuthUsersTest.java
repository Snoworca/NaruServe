package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.AuthUsers;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserPage;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** The AuthUsers facade (IR-IAM-003). */
public class AuthUsersTest {

    /** Records every event with its origin. */
    static final class Recorder implements AuthEvents {
        final List<String> seen = new ArrayList<>();
        @Nullable AuthRejection refuse;

        private void check() {
            AuthRejection r = refuse;
            if (r != null) {
                throw r;
            }
        }

        @Override
        public void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) {
            seen.add("beforeUserCreate " + ctx.getOrigin() + " hash=" + candidate.getPasswordHash());
            check();
        }

        @Override
        public void onUserCreated(AuthUser user, AuthEventContext ctx) {
            seen.add("onUserCreated " + ctx.getOrigin() + " hash=" + user.getPasswordHash());
        }

        @Override
        public void beforeUserUpdate(AuthUser before, AuthUser after, AuthEventContext ctx) {
            seen.add("beforeUserUpdate " + ctx.getOrigin());
            check();
        }

        @Override
        public void onUserUpdated(AuthUser before, AuthUser after, Set<UserChange> changes, AuthEventContext ctx) {
            seen.add("onUserUpdated " + ctx.getOrigin() + " " + changes);
        }

        @Override
        public void beforeUserDelete(AuthUser user, AuthEventContext ctx) {
            seen.add("beforeUserDelete " + ctx.getOrigin());
            check();
        }

        @Override
        public void onUserDeleted(AuthUser user, AuthEventContext ctx) {
            seen.add("onUserDeleted " + ctx.getOrigin());
        }
    }

    // IR-IAM-003 AC-1: exactly the listed methods and no update(AuthUser)
    @Test
    public void IR_IAM_003_AC1_surface() {
        TreeSet<String> names = new TreeSet<>();
        int creates = 0;
        for (Method m : AuthUsers.class.getDeclaredMethods()) {
            names.add(m.getName());
            creates += m.getName().equals("create") ? 1 : 0;
            if (m.getName().equals("update")) {
                assertFalse(Arrays.asList(m.getParameterTypes()).contains(AuthUser.class));
            }
        }
        assertEquals(new TreeSet<>(Arrays.asList("findById", "findByUsername", "create", "update", "delete", "rename",
                "checkPassword")), names);
        assertEquals(2, creates);
        assertEquals(8, AuthUsers.class.getDeclaredMethods().length);
    }

    /** Encodes through getters, as a non-Gson codec would. */
    static final class GetterCodec implements JsonCodec {
        @Override
        public <T> T decode(Reader reader, Type type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) throws java.io.IOException {
            AuthUser u = (AuthUser) value;
            writer.write("{\"username\":\"" + u.getUsername() + "\",\"passwordHash\":"
                    + (u.getPasswordHash() == null ? "null" : "\"" + u.getPasswordHash() + "\"") + "}");
        }
    }

    // IR-IAM-003 AC-2 (getter-reading codec; the default Gson half is AuthUsersGsonTest in NaruServe-gson): every
    // returned user has a null hash, so res.json(...) never carries the stored hash
    @Test
    public void IR_IAM_003_AC2_returnsCarryNoHash() {
        InMemoryUserStore store = new InMemoryUserStore();
        Auth auth = AuthTestSupport.builder().users(store).permit("/u").build();
        AuthUsers users = auth.users();
        AuthUser created = users.create("Alice", "pw-pw-pw-1".toCharArray(), "editor");
        String stored = store.findById(created.getId()).getPasswordHash();
        assertNotNull(stored);
        assertNull(created.getPasswordHash());
        assertNull(users.findById(created.getId()).getPasswordHash());
        assertNull(users.findByUsername("ALICE").getPasswordHash());
        assertNull(users.update(created.getId(), u -> u.toBuilder().attribute("nick", "al").build()).getPasswordHash());
        assertNull(users.rename(created.getId(), "alicia").getPasswordHash());
        assertEquals(stored, store.findById(created.getId()).getPasswordHash());
        TestApp t = TestApp.create();
        t.app().jsonCodec(new GetterCodec());
        auth.install(t.app());
        t.app().get("/u", (req, res) -> res.json(users.findById(created.getId())));
        String body = t.send("GET", "/u").text();
        assertTrue(body, body.contains("\"passwordHash\":null"));
        assertFalse(body, body.contains(stored));
    }

    // IR-IAM-003 AC-3: create hashes, normalizes, emits the APP events and returns the stored user with an id
    @Test
    public void IR_IAM_003_AC3_createHashesNormalizesAndEmits() {
        InMemoryUserStore store = new InMemoryUserStore();
        Recorder events = new Recorder();
        Auth auth = AuthTestSupport.builder().users(store).events(events).build();
        AuthUser alice = auth.users().create("Alice", "correct horse".toCharArray(), "editor");
        assertNotNull(alice.getId());
        assertEquals("alice", alice.getUsername());
        assertEquals(Collections.singleton("editor"), alice.getRoles());
        String hash = store.findByUsername("alice").getPasswordHash();
        assertTrue(hash, hash.startsWith("pbkdf2-sha256$"));
        assertFalse(hash.contains("correct horse"));
        assertEquals(Arrays.asList("beforeUserCreate APP hash=null", "onUserCreated APP hash=null"), events.seen);
        assertTrue(auth.users().checkPassword(alice.getId(), "correct horse".toCharArray()));
        assertFalse(auth.users().checkPassword(alice.getId(), "wrong".toCharArray()));
        assertFalse(auth.users().checkPassword("404", "correct horse".toCharArray()));
        // a service account: null password, flag set by init, null stored hash
        AuthUser bot = auth.users().create("ci-bot", null, u -> u.toBuilder().serviceAccount(true).build());
        assertTrue(bot.isServiceAccount());
        assertNull(store.findById(bot.getId()).getPasswordHash());
    }

    /** Every method throws SQLException. */
    static final class BrokenStore implements UserStore {
        @Override
        public @Nullable AuthUser findByUsername(String username) throws SQLException {
            throw new SQLException("down");
        }

        @Override
        public @Nullable AuthUser findById(String id) throws SQLException {
            throw new SQLException("down");
        }

        @Override
        public AuthUser create(AuthUser user) throws SQLException {
            throw new SQLException("down");
        }

        @Override
        public @Nullable AuthUser update(AuthUser user) throws SQLException {
            throw new SQLException("down");
        }

        @Override
        public boolean delete(String id) throws SQLException {
            throw new SQLException("down");
        }

        @Override
        public UserPage list(int offset, int limit, @Nullable String search) throws SQLException {
            throw new SQLException("down");
        }
    }

    private interface Call {
        void run();
    }

    private static Class<?> thrown(Call c) {
        try {
            c.run();
            return null;
        } catch (RuntimeException e) {
            return e.getClass();
        }
    }

    // IR-IAM-003 AC-4: store SQLExceptions become AuthUnavailableException; only the four unchecked types escape
    @Test
    public void IR_IAM_003_AC4_exceptionTypes() {
        AuthUsers users = AuthTestSupport.builder().users(new BrokenStore()).build().users();
        char[] pw = "pw-pw-pw-pw".toCharArray();
        for (Call c : Arrays.<Call>asList(() -> users.findById("1"), () -> users.findByUsername("a"),
                () -> users.create("a", pw), () -> users.update("1", u -> u), () -> users.delete("1"),
                () -> users.rename("1", "b"), () -> users.checkPassword("1", pw))) {
            assertEquals(AuthUnavailableException.class, thrown(c));
        }
        AuthUsers ok = AuthTestSupport.builder().build().users();
        assertTrue(IllegalArgumentException.class.isAssignableFrom(thrown(() -> ok.create("  ", pw))));
        assertTrue(IllegalArgumentException.class.isAssignableFrom(thrown(() -> ok.create("x", null, (com.snoworca.naruserve.utils.auth.user.UserMutator) null))));
        ok.create("dup", pw);
        assertEquals(DuplicateUserException.class, thrown(() -> ok.create("DUP", pw)));
        String id = ok.findByUsername("dup").getId();
        assertTrue(IllegalArgumentException.class.isAssignableFrom(thrown(() -> ok.update(id,
                u -> u.toBuilder().passwordHash("x").build()))));
        assertTrue(IllegalArgumentException.class.isAssignableFrom(thrown(() -> ok.update(id,
                u -> u.toBuilder().roles("admin").build()))));
        for (Method m : AuthUsers.class.getDeclaredMethods()) {
            assertEquals(m.getName(), 0, m.getExceptionTypes().length);
        }
    }

    // IR-IAM-003 AC-4 (503 through auth.errors()): each of the eight AuthUsers methods, the init overload of create
    // included, over a store throwing SQLException answers 503 in an app route with auth.errors() as its error handler
    @Test
    public void IR_IAM_003_AC4_everyMethodIs503ThroughAuthErrors() {
        Auth auth = AuthTestSupport.builder().users(new BrokenStore()).build(); // not installed: its doctor refuses the store
        AuthUsers users = auth.users();
        List<Call> calls = Arrays.<Call>asList(() -> users.findById("1"), () -> users.findByUsername("a"),
                () -> users.create("a", "pw-pw-pw-pw".toCharArray()),
                () -> users.create("a", "pw-pw-pw-pw".toCharArray(), u -> u),
                () -> users.update("1", u -> u), () -> users.delete("1"), () -> users.rename("1", "b"),
                () -> users.checkPassword("1", "pw-pw-pw-pw".toCharArray()));
        TestApp t = TestApp.create();
        for (int i = 0; i < calls.size(); i++) {
            final Call call = calls.get(i);
            t.app().get("/broken/" + i, (req, res) -> {
                call.run();
                res.send("not reached");
            });
        }
        t.app().onError(auth.errors());
        for (int i = 0; i < calls.size(); i++) {
            assertEquals(AuthUnavailableException.class, thrown(calls.get(i)));
            com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse r = t.send("GET", "/broken/" + i);
            assertEquals(i + " " + r.text(), 503, r.status());
            assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(r));
        }
    }

    // IR-IAM-003 AC-5: a refusing before-listener stops create, update, rename and delete and nothing is written
    @Test
    public void IR_IAM_003_AC5_refusingListenerWritesNothing() {
        InMemoryUserStore store = new InMemoryUserStore();
        Recorder events = new Recorder();
        Auth auth = AuthTestSupport.builder().users(store).events(events).build();
        AuthUser alice = auth.users().create("alice", "pw-pw-pw-pw".toCharArray());
        events.refuse = new AuthRejection(422, "name_policy", "no");
        for (Call c : Arrays.<Call>asList(() -> auth.users().create("bob", "pw-pw-pw-pw".toCharArray()),
                () -> auth.users().update(alice.getId(), u -> u.toBuilder().attribute("a", "b").build()),
                () -> auth.users().rename(alice.getId(), "alicia"), () -> auth.users().delete(alice.getId()))) {
            try {
                c.run();
                fail("not refused");
            } catch (AuthRejection r) {
                assertEquals("name_policy", r.getCode());
            }
        }
        assertNull(store.findByUsername("bob"));
        AuthUser now = store.findById(alice.getId());
        assertEquals("alice", now.getUsername());
        assertTrue(now.getAttributes().isEmpty());
    }

    // IR-IAM-003 AC-6: delete removes group memberships before the row and fires the APP delete events;
    // userStore() is the configured store and its Javadoc warns about stripped copies
    @Test
    public void IR_IAM_003_AC6_deleteOrderAndUserStore() throws Exception {
        final List<String> order = new ArrayList<>();
        InMemoryUserStore rows = new InMemoryUserStore();
        UserStore store = new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(rows) {
            @Override
            public boolean delete(String id) throws Exception {
                order.add("user row");
                return super.delete(id);
            }
        };
        InMemoryGroupStore groups = new InMemoryGroupStore();
        Recorder events = new Recorder();
        Auth auth = AuthTestSupport.builder().users(store).groups(new com.snoworca.naruserve.utils.auth.group
                .GroupAdminStoreAccess.Recording(groups, order)).events(events).build();
        AuthUser alice = auth.users().create("alice", "pw-pw-pw-pw".toCharArray());
        groups.createGroup("eng");
        groups.addMember("eng", alice.getId());
        events.seen.clear();
        assertTrue(auth.users().delete(alice.getId()));
        assertEquals(Arrays.asList("removeUser", "user row"), order);
        assertTrue(groups.groupsOfUser(alice.getId()).isEmpty());
        assertEquals(Arrays.asList("beforeUserDelete APP", "onUserDeleted APP"), events.seen);
        assertFalse(auth.users().delete(alice.getId()));
        assertSame(store, auth.userStore());
        String src = new String(Files.readAllBytes(new File(
                "src/main/java/com/snoworca/naruserve/utils/auth/Auth.java").toPath()), StandardCharsets.UTF_8);
        int at = src.indexOf("public UserStore userStore()");
        String doc = src.substring(src.lastIndexOf("/**", at), at).replaceAll("\\s*\\n\\s*\\*\\s*", " ");
        assertTrue(doc, doc.contains("Never pass a user returned by {@link #users()} to its {@code update}"));
        assertTrue(doc, doc.contains("the store would save a null hash"));
    }
}
