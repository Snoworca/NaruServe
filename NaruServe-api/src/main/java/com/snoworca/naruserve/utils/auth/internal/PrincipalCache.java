package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.TimeSource;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * The node-local cache of what a user may do (00 R36): the user row's name, token version, enabled flag and
 * attributes plus the effective roles and scopes, per user id, for {@code userCacheTtl} (5 seconds by default), so a
 * busy user costs one store read per TTL. A change auth makes itself invalidates the entry at once (a role or group
 * change clears the whole cache, since group roles reach many users); a change written straight to the store is seen
 * after at most the TTL. Authorization decisions of admin writes (00 R31) never read it. Cross-node invalidation is v2.
 */
// @req SEC-AUTH-007
public final class PrincipalCache {

    /** The default lifetime of an entry: 5 seconds. */
    public static final long DEFAULT_TTL_MILLIS = 5_000L;

    /** Entries above this count make a put drop the expired ones first. */
    private static final int SWEEP_ABOVE = 10_000;

    /** What a user may do, as read from the stores. */
    public static final class Snapshot {
        final String username;
        final long tokenVersion;
        final boolean enabled;
        final Set<String> roles;
        final Set<String> scopes;
        final Map<String, String> attributes;
        final long loadedAt;

        /** The values read for one user. */
        public Snapshot(String username, long tokenVersion, boolean enabled, Set<String> roles, Set<String> scopes,
                Map<String, String> attributes, long loadedAt) {
            this.username = username;
            this.tokenVersion = tokenVersion;
            this.enabled = enabled;
            this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(roles));
            this.scopes = Collections.unmodifiableSet(new LinkedHashSet<>(scopes));
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
            this.loadedAt = loadedAt;
        }

        /** The normalized username. */
        public String getUsername() {
            return username;
        }

        /** The token version of the row. */
        public long getTokenVersion() {
            return tokenVersion;
        }

        /** The enabled flag of the row. */
        public boolean isEnabled() {
            return enabled;
        }

        /** The effective roles. */
        public Set<String> getRoles() {
            return roles;
        }

        /** The scopes of the effective roles. */
        public Set<String> getScopes() {
            return scopes;
        }

        /** The attributes of the row. */
        public Map<String, String> getAttributes() {
            return attributes;
        }
    }

    /** Reads one user from the stores; null when there is no such user. */
    @FunctionalInterface
    public interface Loader {
        /** The snapshot of {@code userId} read now, or null. */
        @Nullable Snapshot load(String userId) throws Exception;
    }

    private final Loader loader;
    private final TimeSource time;
    private final long ttlMillis;
    private final ConcurrentHashMap<String, Snapshot> entries = new ConcurrentHashMap<>();
    /** Raised by every invalidation; a load that saw another value is not cached (it may predate the change). */
    private final AtomicLong epoch = new AtomicLong();

    /** A cache over {@code loader} whose entries live {@code ttlMillis} on {@code time}; 0 caches nothing. */
    // @req SEC-AUTH-007
    public PrincipalCache(Loader loader, TimeSource time, long ttlMillis) {
        if (ttlMillis < 0) {
            throw new IllegalArgumentException("userCacheTtl must not be negative: " + ttlMillis);
        }
        this.loader = loader;
        this.time = time;
        this.ttlMillis = ttlMillis;
    }

    /** The entry lifetime in milliseconds. */
    public long ttlMillis() {
        return ttlMillis;
    }

    /** The snapshot of {@code userId}: the cached one while it is younger than the TTL, else one read now. */
    // @req SEC-AUTH-007
    public @Nullable Snapshot get(String userId) throws Exception {
        long now = time.nowMillis();
        Snapshot s = entries.get(userId);
        if (s != null && now - s.loadedAt < ttlMillis && now >= s.loadedAt) {
            return s;
        }
        long seen = epoch.get();
        Snapshot fresh = loader.load(userId);
        if (fresh == null) {
            entries.remove(userId);
            return null;
        }
        if (ttlMillis > 0) {
            if (entries.size() >= SWEEP_ABOVE) {
                sweep(now);
            }
            entries.put(userId, fresh);
            if (epoch.get() != seen) {
                entries.remove(userId, fresh); // an invalidation ran during the load: never keep a stale row
            }
        }
        return fresh;
    }

    /**
     * The snapshot of {@code userId} read now from the stores, whatever the cached entry's age, which then replaces the
     * entry (unless an invalidation ran during the load). Unlike {@link #invalidate} it does not disturb concurrent
     * loads of other users; the WebSocket recheck uses it.
     */
    // @req SEC-APIKEY-011
    public @Nullable Snapshot refresh(String userId) throws Exception {
        long seen = epoch.get();
        Snapshot fresh = loader.load(userId);
        if (fresh == null) {
            entries.remove(userId);
            return null;
        }
        if (ttlMillis > 0) {
            entries.put(userId, fresh);
            if (epoch.get() != seen) {
                entries.remove(userId, fresh); // an invalidation ran during the load: never keep a stale row
            }
        }
        return fresh;
    }

    /** Forgets {@code userId}; its next request reads the stores. */
    // @req SEC-AUTH-007
    public void invalidate(String userId) {
        epoch.incrementAndGet();
        entries.remove(userId);
    }

    /** Forgets every user: after a role or group change through auth, which may reach any user. */
    // @req SEC-AUTH-007
    public void clear() {
        epoch.incrementAndGet();
        entries.clear();
    }

    private void sweep(long now) {
        for (Iterator<Snapshot> it = entries.values().iterator(); it.hasNext();) {
            Snapshot s = it.next();
            if (now - s.loadedAt >= ttlMillis || now < s.loadedAt) {
                it.remove();
            }
        }
    }

    @Override
    public String toString() {
        return "PrincipalCache{ttl=" + ttlMillis + "ms, entries=" + entries.size() + "}";
    }
}
