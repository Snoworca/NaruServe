package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.spi.DeviceToken;
import com.snoworca.naruserve.utils.auth.spi.DeviceTrust;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The in-memory {@link DeviceTrust} of one node: per device token a fixed-window failure count and, once the limit is
 * reached, a permanent invalidation (until the token expires and is forgotten). Expired entries are dropped on the
 * next failure record (at most once a minute). The number of tokens is capped like the in-memory login throttle
 * (100,000 on a JVM, 10,000 on Android): at the cap the expired entries go first, then the token whose window started
 * longest ago among those not invalidated, and only when every entry is an invalidation, the one expiring first; the
 * first drop logs one WARN.
 */
// @req SEC-FLOW-013
final class InMemoryDeviceTrust implements DeviceTrust {

    private static final class Entry {
        int failures;
        long windowStart;
        boolean invalidated;
        /** Set under this entry's monitor when it leaves the map, so a failure that looked it up first retries. */
        boolean removed;
        final long expiresAt;

        Entry(long expiresAt, long windowStart) {
            this.expiresAt = expiresAt;
            this.windowStart = windowStart;
        }
    }

    private final TimeSource time;
    private final int maxEntries;
    private final Logger logger;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile long lastPurge = Long.MIN_VALUE;
    private volatile boolean capWarned;
    /** Test seam: runs once a cap eviction has chosen its victim, before it drops it; null in production. */
    volatile @Nullable Runnable beforeDrop;

    InMemoryDeviceTrust(TimeSource time) {
        this(time, InMemoryLoginThrottle.defaultMaxKeys(System.getProperty("java.vm.name"),
                System.getProperty("java.vendor")), new ConsoleLogger());
    }

    /** A trust store holding at most {@code maxEntries} tokens; the cap WARN goes to {@code logger}. */
    // @req REL-FLOW-001
    InMemoryDeviceTrust(TimeSource time, int maxEntries, Logger logger) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1: " + maxEntries);
        }
        this.time = time;
        this.maxEntries = maxEntries;
        this.logger = logger;
    }

    // @req SEC-FLOW-013
    @Override
    public boolean isInvalidated(DeviceToken token) {
        Entry e = entries.get(token.getTokenId());
        if (e == null) {
            return false;
        }
        synchronized (e) {
            return e.invalidated;
        }
    }

    // @req SEC-FLOW-013
    @Override
    public boolean recordFailure(DeviceToken token, int max, long windowMillis) {
        long now = time.nowMillis();
        purge(now);
        for (;;) {
            Entry e = entries.get(token.getTokenId());
            if (e == null) {
                if (entries.size() >= maxEntries) {
                    makeRoom(now);
                }
                Entry fresh = new Entry(token.getExpiresAt(), now); // not the oldest window for a concurrent makeRoom
                Entry known = entries.putIfAbsent(token.getTokenId(), fresh);
                e = known != null ? known : fresh;
            }
            synchronized (e) {
                if (e.removed) {
                    continue; // dropped between the lookup and the lock: count on the entry the map holds now
                }
                return count(e, now, max, windowMillis);
            }
        }
    }

    /** Counts one failure on {@code e}, whose monitor the caller holds; true once it is invalidated. */
    private static boolean count(Entry e, long now, int max, long windowMillis) {
        if (e.failures == 0 || now - e.windowStart >= windowMillis) {
            e.failures = 1;
            e.windowStart = now;
        } else {
            e.failures++;
        }
        if (e.failures >= max) {
            e.invalidated = true;
        }
        return e.invalidated;
    }

    // @req SEC-FLOW-013
    @Override
    public int purgeExpired(long nowMillis) {
        return dropExpired(nowMillis);
    }

    /**
     * One token's state as {@code {expiresAt, failures, windowStart, invalidated (1 or 0)}} by token id; for the file
     * of {@code FileDeviceTrust}.
     */
    // @req REL-FLOW-001
    java.util.LinkedHashMap<String, long[]> snapshot() {
        java.util.LinkedHashMap<String, long[]> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Entry> e : entries.entrySet()) {
            Entry x = e.getValue();
            synchronized (x) {
                out.put(e.getKey(), new long[] {x.expiresAt, x.failures, x.windowStart, x.invalidated ? 1 : 0});
            }
        }
        return out;
    }

    /** Adds the states of {@link #snapshot()}; for a file restored at start. */
    // @req REL-FLOW-001
    void restore(java.util.Map<String, long[]> state) {
        for (java.util.Map.Entry<String, long[]> e : state.entrySet()) {
            long[] v = e.getValue();
            Entry x = new Entry(v[0], v[2]);
            x.failures = (int) v[1];
            x.invalidated = v[3] != 0;
            if (entries.size() >= maxEntries) {
                // a file written under a larger cap (a JVM file read on Android): the drop order is among the entries
                // read so far, not the whole file, which is good enough for this last-resort path
                makeRoom(time.nowMillis());
            }
            entries.put(e.getKey(), x);
        }
    }

    /**
     * At the cap: drops the expired entries, else the entry not invalidated whose window started longest ago (else the
     * invalidation that expires first); the first drop logs one WARN.
     */
    // @req REL-FLOW-001
    private synchronized void makeRoom(long now) {
        if (entries.size() < maxEntries) {
            return;
        }
        String victim = null;
        long victimAt = Long.MAX_VALUE;
        String invalidVictim = null;
        long invalidAt = Long.MAX_VALUE;
        for (Iterator<java.util.Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext();) {
            java.util.Map.Entry<String, Entry> me = it.next();
            Entry x = me.getValue();
            synchronized (x) {
                if (x.expiresAt <= now) {
                    x.removed = true;
                    it.remove();
                    continue;
                }
                if (!x.invalidated && x.windowStart < victimAt) {
                    victim = me.getKey();
                    victimAt = x.windowStart;
                } else if (x.invalidated && x.expiresAt < invalidAt) {
                    invalidVictim = me.getKey();
                    invalidAt = x.expiresAt;
                }
            }
        }
        if (entries.size() < maxEntries) {
            return;
        }
        String drop = victim != null ? victim : invalidVictim;
        boolean retryChoice = false;
        Runnable hook = beforeDrop;
        if (hook != null) {
            hook.run();
        }
        Entry dropped = drop == null ? null : entries.get(drop);
        if (dropped != null) {
            synchronized (dropped) {
                if (victim != null && dropped.invalidated) {
                    // a concurrent failure invalidated the chosen token after the scan: keep it and choose again
                    retryChoice = true;
                } else {
                    dropped.removed = true;
                    entries.remove(drop, dropped);
                }
            }
        }
        if (retryChoice) {
            makeRoom(now);
            return;
        }
        if (!capWarned) {
            capWarned = true;
            logger.warn("auth: the device trust reached its cap of " + maxEntries + " tokens; the oldest tokens are "
                    + "dropped from now on (logged once)");
        }
    }

    /** Drops expired entries, at most once a minute. */
    private void purge(long now) {
        long last = lastPurge;
        if (last != Long.MIN_VALUE && now - last < 60_000L) {
            return;
        }
        lastPurge = now;
        dropExpired(now);
    }

    /** Drops the entries with {@code expiresAt <= now}; the count dropped. */
    private int dropExpired(long now) {
        int removed = 0;
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext();) {
            Entry x = it.next();
            synchronized (x) {
                if (x.expiresAt <= now) {
                    x.removed = true;
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    @Override
    public String toString() {
        return "InMemoryDeviceTrust{" + entries.size() + " tokens}";
    }
}
