package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.group.GroupAdminStore;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * An app with auth installed over real access tokens (HS256, the auth key set), a JSON codec backed by the auth
 * {@link MiniJson} (the shipped app codec is Gson, which NaruServe-api does not depend on), a group store whose groups
 * confer roles and which counts the member and child traversals, a recording audit sink and a recording event
 * listener. Each test builds one; nothing here is a mock: the stores are the shipped in-memory ones.
 */
final class AdminFixture {

    static final long T0 = 1_761_700_900_000L;

    /** Decodes and encodes with MiniJson (objects, arrays, strings, numbers, booleans). */
    static final class MiniJsonCodec implements JsonCodec {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(Reader reader, Type type) throws IOException {
            StringBuilder b = new StringBuilder();
            char[] buf = new char[1024];
            for (int n; (n = reader.read(buf)) >= 0;) {
                b.append(buf, 0, n);
            }
            try {
                return (T) MiniJson.parse(b.toString(), MiniJson.Limits.file());
            } catch (IllegalArgumentException malformed) {
                throw new IOException("malformed JSON", malformed); // as GsonJsonCodec reports malformed input
            }
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) throws IOException {
            writer.write(MiniJson.write(value));
        }
    }

    /** A GroupAdminStore over InMemoryGroupStore whose groups confer roles, counting membersOf and childrenOf. */
    static final class RoleGroups implements GroupAdminStore {
        final InMemoryGroupStore d = new InMemoryGroupStore();
        final Map<String, Set<String>> roles = new HashMap<>();
        final AtomicInteger traversals = new AtomicInteger();
        final AtomicInteger addParentCalls = new AtomicInteger();

        RoleGroups confer(String group, String... r) {
            d.createGroup(group);
            roles.put(group, new LinkedHashSet<>(java.util.Arrays.asList(r)));
            return this;
        }

        @Override
        public Set<String> groupsOfUser(String userId) {
            return d.groupsOfUser(userId);
        }

        @Override
        public Set<String> parentsOf(Set<String> groupIds) {
            return d.parentsOf(groupIds);
        }

        @Override
        public Set<String> rolesOfGroups(Set<String> groupIds) {
            Set<String> out = new LinkedHashSet<>();
            for (String g : groupIds) {
                Set<String> r = roles.get(g);
                if (r != null) {
                    out.addAll(r);
                }
            }
            return out;
        }

        @Override
        public boolean createGroup(String groupId) {
            return d.createGroup(groupId);
        }

        @Override
        public boolean deleteGroup(String groupId) {
            return d.deleteGroup(groupId);
        }

        @Override
        public List<String> listGroups() {
            return d.listGroups();
        }

        @Override
        public boolean groupExists(String groupId) {
            return d.groupExists(groupId);
        }

        @Override
        public void addMember(String groupId, String userId) {
            d.addMember(groupId, userId);
        }

        @Override
        public void removeMember(String groupId, String userId) {
            d.removeMember(groupId, userId);
        }

        @Override
        public Set<String> membersOf(String groupId) {
            traversals.incrementAndGet();
            return d.membersOf(groupId);
        }

        @Override
        public void addParent(String childId, String parentId) {
            addParentCalls.incrementAndGet();
            d.addParent(childId, parentId);
        }

        @Override
        public void removeParent(String childId, String parentId) {
            d.removeParent(childId, parentId);
        }

        @Override
        public Set<String> childrenOf(String groupId) {
            traversals.incrementAndGet();
            return d.childrenOf(groupId);
        }
    }

    /** Records onUserUpdated (changes and origin) and onPasswordChanged. */
    static final class Events implements AuthEvents {
        final List<Set<UserChange>> updated = new CopyOnWriteArrayList<>();
        final List<UserOrigin> origins = new CopyOnWriteArrayList<>();
        final AtomicInteger passwordChanged = new AtomicInteger();

        @Override
        public void onUserUpdated(AuthUser before, AuthUser after, Set<UserChange> changes, AuthEventContext ctx) {
            updated.add(changes.isEmpty() ? EnumSet.noneOf(UserChange.class) : EnumSet.copyOf(changes));
            origins.add(ctx.getOrigin());
        }

        @Override
        public void onPasswordChanged(AuthUser user, AuthEventContext ctx) {
            passwordChanged.incrementAndGet();
        }
    }

    /** An API key store over a list; records every deletion. */
    static class ListKeys implements com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore {
        final List<com.snoworca.naruserve.utils.auth.apikey.StoredApiKey> rows = new CopyOnWriteArrayList<>();
        final AtomicInteger writes = new AtomicInteger();

        @Override
        public com.snoworca.naruserve.utils.auth.apikey.@Nullable StoredApiKey findByPrefix(String prefix) {
            for (com.snoworca.naruserve.utils.auth.apikey.StoredApiKey k : rows) {
                if (k.getPrefix().equals(prefix)) {
                    return k;
                }
            }
            return null;
        }

        @Override
        public com.snoworca.naruserve.utils.auth.apikey.@Nullable StoredApiKey findById(String keyId) {
            for (com.snoworca.naruserve.utils.auth.apikey.StoredApiKey k : rows) {
                if (k.getId().equals(keyId)) {
                    return k;
                }
            }
            return null;
        }

        @Override
        public List<com.snoworca.naruserve.utils.auth.apikey.StoredApiKey> listByOwner(String ownerUserId) {
            List<com.snoworca.naruserve.utils.auth.apikey.StoredApiKey> out = new ArrayList<>();
            for (com.snoworca.naruserve.utils.auth.apikey.StoredApiKey k : rows) {
                if (k.getOwnerUserId().equals(ownerUserId)) {
                    out.add(k);
                }
            }
            return out;
        }

        @Override
        public boolean insertIfUnderLimit(com.snoworca.naruserve.utils.auth.apikey.StoredApiKey key, int max,
                long now) {
            writes.incrementAndGet();
            return rows.add(key);
        }

        @Override
        public boolean deleteByIdAndOwner(String keyId, String ownerUserId) {
            com.snoworca.naruserve.utils.auth.apikey.StoredApiKey k = findById(keyId);
            return k != null && k.getOwnerUserId().equals(ownerUserId) && deleteById(keyId);
        }

        @Override
        public boolean deleteById(String keyId) {
            com.snoworca.naruserve.utils.auth.apikey.StoredApiKey k = findById(keyId);
            if (k == null) {
                return false;
            }
            writes.incrementAndGet();
            return rows.remove(k);
        }

        @Override
        public int deleteAllByOwner(String ownerUserId) {
            List<com.snoworca.naruserve.utils.auth.apikey.StoredApiKey> mine = listByOwner(ownerUserId);
            rows.removeAll(mine);
            return mine.size();
        }

        @Override
        public int deleteStaleByOwner(String ownerUserId, long currentOwnerVer) {
            return 0;
        }

        @Override
        public int deleteExpired(long nowMillis) {
            return 0;
        }
    }

    final AtomicLong clock = new AtomicLong(T0);
    final UserStore users;
    final InMemoryUserStore base;
    final RoleGroups groups = new RoleGroups();
    final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
    final Events events = new Events();
    final RecordingLogger logger = new RecordingLogger();
    InMemoryHttpAdapter adapter;
    RestApp app;
    Auth auth;

    AdminFixture() {
        this(null);
    }

    /** A fixture over {@code store} (a decorator of {@link #base}), or {@link #base} itself when null. */
    AdminFixture(@Nullable StoreWrapper store) {
        this.base = new InMemoryUserStore();
        this.users = store == null ? base : store.wrap(base);
    }

    /** Wraps the base store, for failure injection. */
    interface StoreWrapper {
        UserStore wrap(InMemoryUserStore base);
    }

    static RolePolicy.Builder policy() {
        return RolePolicy.builder()
                .role("usersreader", "auth-users:read")
                .role("userswriter", "auth-users:write")
                .role("usersall", "auth-users:*")
                .role("groupsreader", "auth-groups:read")
                .role("groupswriter", "auth-groups:write")
                .role("keysreader", "auth-apikeys:read")
                .role("keyswriter", "auth-apikeys:write")
                .role("reader", "posts:read")
                .role("editor", "posts:write")
                .role("poster", "posts:*")
                .role("docs", "docs:read");
    }

    Auth.Builder builder() {
        return Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(logger).users(users).groups(groups)
                .roles(policy().build()).timeSource(clock::get).audit(audit::add).events(events);
    }

    AdminFixture start() {
        return start(b -> b);
    }

    AdminFixture start(UnaryOperator<Auth.Builder> config) {
        return start(config, x -> { });
    }

    /** Runs on the app before auth.install, for path-free middleware a test places in front of auth. */
    java.util.function.Consumer<RestApp> preInstall = a -> { };

    /** Builds auth with {@code config}, installs it, runs {@code routes} (before listen), then starts. */
    AdminFixture start(UnaryOperator<Auth.Builder> config, java.util.function.Consumer<AdminFixture> routes) {
        adapter = new InMemoryHttpAdapter("203.0.113.9", true);
        app = RestApp.builder().httpAdapter(adapter).jsonCodec(new MiniJsonCodec()).logger(logger).build();
        preInstall.accept(app);
        auth = config.apply(builder()).build();
        auth.install(app);
        routes.accept(this);
        app.onError(auth.errors());
        app.listen(0);
        return this;
    }

    /**
     * Shuts the started app down (5 s), which stops auth through the app's onStop: its cleanup scheduler, hash slots
     * and background pools end with it. A no-op before {@link #start}; a second call finds the app terminated.
     */
    void stop() {
        RestApp a = app;
        if (a != null) {
            a.shutdown(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** Stops every fixture of {@code started}, then clears it; the first failure is thrown after all were tried. */
    static void stopAll(List<AdminFixture> started) {
        RuntimeException first = null;
        for (AdminFixture f : started) {
            try {
                f.stop();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        started.clear();
        if (first != null) {
            throw first;
        }
    }

    /** A stored user with a real password hash and {@code roles}; its id. */
    String user(String name, String... roles) throws Exception {
        String hash = auth != null ? auth.passwords().hash(("pw-" + name).toCharArray()) : "x";
        return base.create(AuthUser.builder().username(name).passwordHash(hash).roles(roles).build()).getId();
    }

    String token(String userId) throws Exception {
        AuthUser u = base.findById(userId);
        return auth.accessToken(userId, u == null ? 0 : u.getTokenVersion(), 900_000L);
    }

    ClientResponse call(String method, String path, @Nullable String actorId, @Nullable String json)
            throws Exception {
        ClientRequest r = json == null ? ClientRequest.of(method, path) : ClientRequest.json(method, path, json);
        if (actorId != null) {
            r.header("Authorization", "Bearer " + token(actorId));
        }
        return adapter.send(r);
    }

    AuthUser stored(String id) throws Exception {
        AuthUser u = base.findById(id);
        if (u == null) {
            throw new AssertionError("no user " + id);
        }
        return u;
    }

    List<String> auditTypes() {
        List<String> out = new ArrayList<>();
        for (AuditEvent e : audit) {
            out.add(e.getType());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(ClientResponse r) {
        Object o = MiniJson.parse(r.text(), MiniJson.Limits.file());
        return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
    }

    static Set<String> set(String... values) {
        return new LinkedHashSet<>(java.util.Arrays.asList(values));
    }
}
