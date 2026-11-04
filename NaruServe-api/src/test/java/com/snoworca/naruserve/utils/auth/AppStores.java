package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.group.GroupAdminStore;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import com.snoworca.naruserve.utils.auth.spi.DeviceToken;
import com.snoworca.naruserve.utils.auth.spi.DeviceTrust;
import com.snoworca.naruserve.utils.auth.spi.ForwardingLoginThrottle;
import com.snoworca.naruserve.utils.auth.spi.RefreshRecord;
import com.snoworca.naruserve.utils.auth.spi.RefreshTokenStore;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.UserPage;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * App-provided stores for tests, written the way an application connects auth to its own database (CON-IAM-003): one
 * {@code AppStores} object is the shared database, and every {@code users()}, {@code refreshTokens()},
 * {@code loginThrottle()}, {@code deviceTrust()}, {@code apiKeys()} and {@code groups()} call hands out a new store
 * object over it, as each node of a cluster would make its own. None of the stores is a built-in in-memory or file
 * store, so {@code deployment(MULTI_INSTANCE)} accepts them, and each declares the atomicity it keeps:
 * {@link Users#supportsAtomicFieldWrites()} (every field method is one conditional write under the database lock, and
 * {@code update} writes only the username and attributes), {@link Keys#supportsAtomicLimitInsert()} and the throttle's
 * {@code usesLimitArguments()}. No JDBC driver is involved; the backing state stands in for the application's tables.
 */
public final class AppStores {

    private final Map<String, AuthUser> userRows = new LinkedHashMap<>();
    private final InMemoryRefreshTokenStore refreshRows = new InMemoryRefreshTokenStore();
    private final InMemoryLoginThrottle throttleRows;
    private final InMemoryDeviceTrust deviceRows;
    private final InMemoryApiKeyStore keyRows = new InMemoryApiKeyStore();
    private final InMemoryGroupStore groupRows = new InMemoryGroupStore();

    /** A shared database whose counters run on the wall clock. */
    public AppStores() {
        this(System::currentTimeMillis);
    }

    /** A shared database whose throttle and device-trust windows run on {@code time}. */
    public AppStores(TimeSource time) {
        throttleRows = InMemoryLoginThrottle.builder().timeSource(time).build();
        deviceRows = new InMemoryDeviceTrust(time);
    }

    /** A new user store over the shared users table. */
    public Users users() {
        return new Users(this);
    }

    /** A new refresh token store over the shared table. */
    public RefreshTokenStore refreshTokens() {
        return new Refresh(refreshRows);
    }

    /** A new login throttle over the shared counters; it honours the limit arguments. */
    public Throttle loginThrottle() {
        return new Throttle(throttleRows);
    }

    /** A new device trust store over the shared table. */
    public DeviceTrust deviceTrust() {
        return new Devices(deviceRows);
    }

    /** A new API key store over the shared table; {@code insertIfUnderLimit} is one atomic write. */
    public Keys apiKeys() {
        return new Keys(keyRows);
    }

    /** A new group store over the shared tables. */
    public GroupAdminStore groups() {
        return new Groups(groupRows);
    }

    /**
     * A user store over the shared users table: ids are random UUIDs (never reused), a duplicate username is a
     * {@link DuplicateUserException}, every field method is one conditional write and {@code update} writes the
     * username and attributes only.
     */
    public static final class Users implements UserStore {
        private final AppStores db;

        Users(AppStores db) {
            this.db = db;
        }

        private @Nullable AuthUser byName(String username) {
            for (AuthUser u : db.userRows.values()) {
                if (u.getUsername().equals(username)) {
                    return u;
                }
            }
            return null;
        }

        @Override
        public @Nullable AuthUser findByUsername(String username) {
            synchronized (db) {
                return byName(username);
            }
        }

        @Override
        public @Nullable AuthUser findById(String id) {
            synchronized (db) {
                return db.userRows.get(id);
            }
        }

        @Override
        public AuthUser create(AuthUser user) {
            if (user.getId() != null) {
                throw new IllegalArgumentException("a new user has no id yet: " + user.getId());
            }
            synchronized (db) {
                if (byName(user.getUsername()) != null) {
                    throw new DuplicateUserException("user " + user.getUsername() + " already exists");
                }
                AuthUser stored = user.toBuilder().id(UUID.randomUUID().toString()).build();
                db.userRows.put(stored.getId(), stored);
                return stored;
            }
        }

        @Override
        public @Nullable AuthUser update(AuthUser user) {
            String id = user.getId();
            if (id == null) {
                return null;
            }
            synchronized (db) {
                AuthUser old = db.userRows.get(id);
                if (old == null) {
                    return null;
                }
                AuthUser other = byName(user.getUsername());
                if (other != null && !id.equals(other.getId())) {
                    throw new DuplicateUserException("user " + user.getUsername() + " already exists");
                }
                AuthUser row = old.toBuilder().username(user.getUsername()).attributes(user.getAttributes()).build();
                db.userRows.put(id, row);
                return row;
            }
        }

        @Override
        public boolean delete(String id) {
            synchronized (db) {
                return db.userRows.remove(id) != null;
            }
        }

        @Override
        public boolean updatePasswordHash(String id, @Nullable String expectedOldHash, String newHash) {
            Objects.requireNonNull(newHash, "newHash");
            synchronized (db) {
                AuthUser u = db.userRows.get(id);
                if (u == null || !Objects.equals(u.getPasswordHash(), expectedOldHash)) {
                    return false;
                }
                db.userRows.put(id, u.toBuilder().passwordHash(newHash).build());
                return true;
            }
        }

        @Override
        public boolean changePassword(String id, @Nullable String expectedOldHash, long expectedVer, String newHash) {
            Objects.requireNonNull(newHash, "newHash");
            synchronized (db) {
                AuthUser u = db.userRows.get(id);
                if (u == null || u.getTokenVersion() != expectedVer
                        || !Objects.equals(u.getPasswordHash(), expectedOldHash)) {
                    return false;
                }
                db.userRows.put(id, u.toBuilder().passwordHash(newHash).tokenVersion(u.getTokenVersion() + 1).build());
                return true;
            }
        }

        @Override
        public boolean setEnabled(String id, boolean enabled) {
            synchronized (db) {
                AuthUser u = db.userRows.get(id);
                if (u == null) {
                    return false;
                }
                db.userRows.put(id, u.toBuilder().enabled(enabled).build());
                return true;
            }
        }

        @Override
        public boolean bumpTokenVersion(String id) {
            synchronized (db) {
                AuthUser u = db.userRows.get(id);
                if (u == null) {
                    return false;
                }
                db.userRows.put(id, u.toBuilder().tokenVersion(u.getTokenVersion() + 1).build());
                return true;
            }
        }

        @Override
        public boolean setRoles(String id, Set<String> roles) {
            synchronized (db) {
                AuthUser u = db.userRows.get(id);
                if (u == null) {
                    return false;
                }
                db.userRows.put(id, u.toBuilder().roles(roles).build());
                return true;
            }
        }

        @Override
        public UserPage list(int offset, int limit, @Nullable String search) {
            if (offset < 0 || limit < 0) {
                throw new IllegalArgumentException("offset and limit must not be negative: " + offset + ", " + limit);
            }
            List<AuthUser> all = new ArrayList<>();
            synchronized (db) {
                String s = search == null ? null : search.toLowerCase(Locale.ROOT);
                for (AuthUser u : db.userRows.values()) {
                    if (s == null || u.getUsername().toLowerCase(Locale.ROOT).contains(s)) {
                        all.add(u);
                    }
                }
            }
            Collections.sort(all, Comparator.comparing(AuthUser::getUsername));
            int from = Math.min(offset, all.size());
            int to = Math.min(all.size(), from + limit);
            return new UserPage(new ArrayList<>(all.subList(from, to)), all.size(), offset, limit);
        }

        /** True: every field method above is one conditional write and {@code update} skips the security fields. */
        @Override
        public boolean supportsAtomicFieldWrites() {
            return true;
        }

        @Override
        public String toString() {
            return "AppStores.Users";
        }
    }

    /** A refresh token store over the shared table. */
    static final class Refresh implements RefreshTokenStore {
        private final RefreshTokenStore rows;

        Refresh(RefreshTokenStore rows) {
            this.rows = rows;
        }

        @Override
        public void save(RefreshRecord record) throws Exception {
            rows.save(record);
        }

        @Override
        public @Nullable RefreshRecord find(String tokenId) throws Exception {
            return rows.find(tokenId);
        }

        @Override
        public boolean markUsed(String tokenId, long nowMillis) throws Exception {
            return rows.markUsed(tokenId, nowMillis);
        }

        @Override
        public int revokeFamily(String familyId) throws Exception {
            return rows.revokeFamily(familyId);
        }

        @Override
        public int purgeExpired(long nowMillis) throws Exception {
            return rows.purgeExpired(nowMillis);
        }
    }

    /** A login throttle over the shared counters; {@code usesLimitArguments()} is true, as the counters honour them. */
    public static final class Throttle extends ForwardingLoginThrottle {
        Throttle(InMemoryLoginThrottle rows) {
            super(rows);
        }
    }

    /** A device trust store over the shared table. */
    static final class Devices implements DeviceTrust {
        private final DeviceTrust rows;

        Devices(DeviceTrust rows) {
            this.rows = rows;
        }

        @Override
        public void issued(DeviceToken token) throws Exception {
            rows.issued(token);
        }

        @Override
        public boolean isInvalidated(DeviceToken token) throws Exception {
            return rows.isInvalidated(token);
        }

        @Override
        public boolean recordFailure(DeviceToken token, int max, long windowMillis) throws Exception {
            return rows.recordFailure(token, max, windowMillis);
        }

        @Override
        public int purgeExpired(long nowMillis) throws Exception {
            return rows.purgeExpired(nowMillis);
        }
    }

    /** An API key store over the shared table; {@code insertIfUnderLimit} counts and inserts under one lock. */
    public static final class Keys implements ApiKeyStore {
        private final ApiKeyStore rows;

        Keys(ApiKeyStore rows) {
            this.rows = rows;
        }

        @Override
        public @Nullable StoredApiKey findByPrefix(String prefix) throws Exception {
            return rows.findByPrefix(prefix);
        }

        @Override
        public @Nullable StoredApiKey findById(String keyId) throws Exception {
            return rows.findById(keyId);
        }

        @Override
        public List<StoredApiKey> listByOwner(String ownerUserId) throws Exception {
            return rows.listByOwner(ownerUserId);
        }

        @Override
        public boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) throws Exception {
            return rows.insertIfUnderLimit(key, maxLiveKeys, nowMillis);
        }

        @Override
        public boolean deleteByIdAndOwner(String keyId, String ownerUserId) throws Exception {
            return rows.deleteByIdAndOwner(keyId, ownerUserId);
        }

        @Override
        public boolean deleteById(String keyId) throws Exception {
            return rows.deleteById(keyId);
        }

        @Override
        public int deleteAllByOwner(String ownerUserId) throws Exception {
            return rows.deleteAllByOwner(ownerUserId);
        }

        @Override
        public int deleteStaleByOwner(String ownerUserId, long currentOwnerVer) throws Exception {
            return rows.deleteStaleByOwner(ownerUserId, currentOwnerVer);
        }

        @Override
        public int deleteFutureByOwner(String ownerUserId, long currentOwnerVer) throws Exception {
            return rows.deleteFutureByOwner(ownerUserId, currentOwnerVer);
        }

        @Override
        public int deleteExpired(long nowMillis) throws Exception {
            return rows.deleteExpired(nowMillis);
        }

        @Override
        public void updateLastUsed(String keyId, long lastUsedAtMillis) throws Exception {
            rows.updateLastUsed(keyId, lastUsedAtMillis);
        }

        /** True: the count and the insert run under the table's one lock. */
        @Override
        public boolean supportsAtomicLimitInsert() {
            return true;
        }

        @Override
        public int countKeysWithAdminScopes() throws Exception {
            return rows.countKeysWithAdminScopes();
        }
    }

    /** A group store over the shared tables. */
    static final class Groups implements GroupAdminStore {
        private final GroupAdminStore rows;

        Groups(GroupAdminStore rows) {
            this.rows = rows;
        }

        @Override
        public Set<String> groupsOfUser(String userId) throws Exception {
            return rows.groupsOfUser(userId);
        }

        @Override
        public Set<String> parentsOf(Set<String> groupIds) throws Exception {
            return rows.parentsOf(groupIds);
        }

        @Override
        public boolean createGroup(String groupId) throws Exception {
            return rows.createGroup(groupId);
        }

        @Override
        public boolean deleteGroup(String groupId) throws Exception {
            return rows.deleteGroup(groupId);
        }

        @Override
        public List<String> listGroups() throws Exception {
            return rows.listGroups();
        }

        @Override
        public boolean groupExists(String groupId) throws Exception {
            return rows.groupExists(groupId);
        }

        @Override
        public void addMember(String groupId, String userId) throws Exception {
            rows.addMember(groupId, userId);
        }

        @Override
        public void removeMember(String groupId, String userId) throws Exception {
            rows.removeMember(groupId, userId);
        }

        @Override
        public Set<String> membersOf(String groupId) throws Exception {
            return rows.membersOf(groupId);
        }

        @Override
        public void addParent(String childId, String parentId) throws Exception {
            rows.addParent(childId, parentId);
        }

        @Override
        public void removeParent(String childId, String parentId) throws Exception {
            rows.removeParent(childId, parentId);
        }

        @Override
        public Set<String> childrenOf(String groupId) throws Exception {
            return rows.childrenOf(groupId);
        }

        @Override
        public void removeUser(String userId) throws Exception {
            rows.removeUser(userId);
        }
    }
}
