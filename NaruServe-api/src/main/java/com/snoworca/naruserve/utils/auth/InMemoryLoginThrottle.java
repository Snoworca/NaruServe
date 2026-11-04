package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.spi.LoginThrottle;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The in-memory {@link LoginThrottle}: failures per key in a sliding window, lost on restart. The number of keys is
 * capped (100,000 on a JVM, 10,000 on Android) so a flood of distinct keys cannot exhaust memory: at the cap the key
 * that failed longest ago is dropped, and the first drop logs one WARN. It honours the limit arguments
 * ({@link #usesLimitArguments()} is true); its own limits for the one-argument methods are 5 failures per 15 minutes
 * unless the builder says otherwise. Thread-safe.
 */
// @req IR-FLOW-003
// @req REL-FLOW-001
public final class InMemoryLoginThrottle implements LoginThrottle {

    /** The key cap on a JVM. */
    public static final int JVM_MAX_KEYS = 100_000;

    /** The key cap on Android. */
    public static final int ANDROID_MAX_KEYS = 10_000;

    private final int maxKeys;
    private final int ownMax;
    private final long ownWindowMillis;
    private final TimeSource time;
    private final Logger logger;
    /** Ordered by last failure: recordFailure moves its key to the end, so the first key failed longest ago. */
    private final LinkedHashMap<String, ArrayDeque<Long>> failures = new LinkedHashMap<>();
    private boolean capWarned;

    private InMemoryLoginThrottle(Builder b) {
        this.maxKeys = b.maxKeys > 0 ? b.maxKeys : defaultMaxKeys(System.getProperty("java.vm.name"),
                System.getProperty("java.vendor"));
        this.ownMax = b.ownMax;
        this.ownWindowMillis = b.ownWindowMillis;
        this.time = b.time;
        this.logger = b.logger != null ? b.logger : new ConsoleLogger();
    }

    /** The defaults: the runtime's key cap, 5 failures per 15 minutes, the system clock, the console logger. */
    // @req IR-FLOW-003
    public InMemoryLoginThrottle() {
        this(new Builder());
    }

    /** A builder for the key cap, the own limits, the clock and the logger. */
    public static Builder builder() {
        return new Builder();
    }

    /** {@link #ANDROID_MAX_KEYS} when the runtime is Android (Dalvik/ART), else {@link #JVM_MAX_KEYS}. */
    // @req REL-FLOW-001
    static int defaultMaxKeys(@Nullable String vmName, @Nullable String vendor) {
        boolean android = (vmName != null && vmName.toLowerCase(java.util.Locale.ROOT).contains("dalvik"))
                || (vendor != null && vendor.toLowerCase(java.util.Locale.ROOT).contains("android"));
        return android ? ANDROID_MAX_KEYS : JVM_MAX_KEYS;
    }

    /** The key cap. */
    public int getMaxKeys() {
        return maxKeys;
    }

    @Override
    public long retryAfter(String key) {
        return retryAfter(key, ownMax, ownWindowMillis);
    }

    @Override
    public long recordFailure(String key) {
        return recordFailure(key, ownMax, ownWindowMillis);
    }

    @Override
    public synchronized void reset(String key) {
        failures.remove(Objects.requireNonNull(key, "key"));
    }

    // @req IR-FLOW-003
    @Override
    public synchronized long retryAfter(String key, int max, long windowMillis) {
        checkLimits(max, windowMillis);
        ArrayDeque<Long> q = failures.get(Objects.requireNonNull(key, "key"));
        if (q == null) {
            return 0L;
        }
        long now = time.nowMillis();
        trim(q, now, windowMillis);
        if (q.isEmpty()) {
            failures.remove(key);
            return 0L;
        }
        return blockedFor(q, now, max, windowMillis);
    }

    // @req IR-FLOW-003
    // @req REL-FLOW-001
    @Override
    public synchronized long recordFailure(String key, int max, long windowMillis) {
        Objects.requireNonNull(key, "key");
        checkLimits(max, windowMillis);
        long now = time.nowMillis();
        ArrayDeque<Long> q = failures.remove(key);
        if (q == null) {
            if (failures.size() >= maxKeys) {
                evictOldest();
            }
            q = new ArrayDeque<>();
        }
        failures.put(key, q); // to the end: the most recent failure
        trim(q, now, windowMillis);
        q.addLast(now);
        while (q.size() > max) {
            q.removeFirst();
        }
        return blockedFor(q, now, max, windowMillis);
    }

    /**
     * A copy of every key's failure times, oldest key first (the order eviction follows); for the file snapshot of
     * {@code FileLoginThrottle}.
     */
    // @req REL-FLOW-001
    synchronized LinkedHashMap<String, long[]> snapshot() {
        LinkedHashMap<String, long[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, ArrayDeque<Long>> e : failures.entrySet()) {
            long[] times = new long[e.getValue().size()];
            int i = 0;
            for (Long t : e.getValue()) {
                times[i++] = t;
            }
            out.put(e.getKey(), times);
        }
        return out;
    }

    /** Replaces the counters with {@code state} (oldest key first), within the key cap; for a file restored at start. */
    // @req REL-FLOW-001
    synchronized void restore(Map<String, long[]> state) {
        failures.clear();
        for (Map.Entry<String, long[]> e : state.entrySet()) {
            if (failures.size() >= maxKeys) {
                evictOldest();
            }
            ArrayDeque<Long> q = new ArrayDeque<>();
            for (long t : e.getValue()) {
                q.addLast(t);
            }
            failures.put(e.getKey(), q);
        }
    }

    /**
     * Forgets every key whose newest failure is older than {@code cutoffMillis}; for auth's default throttle (never one
     * the app passed in) the 10-minute cleanup calls it with now minus the longer of 24 hours and the longest window
     * auth counts with, so the counters (and the file snapshot of {@code FileLoginThrottle}) keep no old entry. Returns how many
     * keys were removed.
     */
    // @req REL-FLOW-001
    synchronized int purgeOlderThan(long cutoffMillis) {
        int removed = 0;
        for (Iterator<Map.Entry<String, ArrayDeque<Long>>> it = failures.entrySet().iterator(); it.hasNext();) {
            ArrayDeque<Long> q = it.next().getValue();
            if (q.isEmpty() || q.peekLast() < cutoffMillis) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Always true: the limit arguments are honoured. */
    @Override
    public boolean usesLimitArguments() {
        return true;
    }

    private static void checkLimits(int max, long windowMillis) {
        if (max < 1 || windowMillis < 1) {
            throw new IllegalArgumentException("a throttle limit needs max >= 1 and window >= 1 ms");
        }
    }

    private void evictOldest() {
        Iterator<String> it = failures.keySet().iterator();
        if (it.hasNext()) {
            it.next();
            it.remove();
        }
        if (!capWarned) {
            capWarned = true;
            logger.warn("auth: the in-memory login throttle reached its cap of " + maxKeys + " keys; the oldest keys "
                    + "are dropped from now on (logged once)");
        }
    }

    private static void trim(ArrayDeque<Long> q, long now, long windowMillis) {
        while (!q.isEmpty() && now - q.peekFirst() >= windowMillis) {
            q.removeFirst();
        }
    }

    private static long blockedFor(ArrayDeque<Long> q, long now, int max, long windowMillis) {
        if (q.size() < max) {
            return 0L;
        }
        // blocked until the oldest of the last max failures leaves the window
        Long[] all = q.toArray(new Long[0]);
        long oldest = all[all.length - max];
        return Math.max(0L, oldest + windowMillis - now);
    }

    @Override
    public String toString() {
        return "InMemoryLoginThrottle{maxKeys=" + maxKeys + ", own=" + ownMax + "/" + ownWindowMillis + " ms}";
    }

    /** Configures an {@link InMemoryLoginThrottle}. */
    public static final class Builder {
        private int maxKeys;
        private int ownMax = 5;
        private long ownWindowMillis = 15 * 60_000L;
        private TimeSource time = TimeSource.system();
        private @Nullable Logger logger;

        private Builder() {
        }

        /** The key cap; the runtime's default ({@link #JVM_MAX_KEYS} or {@link #ANDROID_MAX_KEYS}) when not called. */
        // @req REL-FLOW-001
        public Builder maxKeys(int maxKeys) {
            if (maxKeys < 1) {
                throw new IllegalArgumentException("maxKeys must be at least 1: " + maxKeys);
            }
            this.maxKeys = maxKeys;
            return this;
        }

        /** The limits of the one-argument methods; 5 failures per 15 minutes when not called. */
        public Builder limits(int max, long windowMillis) {
            if (max < 1 || windowMillis < 1) {
                throw new IllegalArgumentException("a throttle limit needs max >= 1 and window >= 1 ms");
            }
            this.ownMax = max;
            this.ownWindowMillis = windowMillis;
            return this;
        }

        /** The clock; {@link TimeSource#system()} when not called. */
        public Builder timeSource(TimeSource time) {
            this.time = Objects.requireNonNull(time, "time");
            return this;
        }

        /** Where the cap WARN goes; the console logger when not called. */
        public Builder logger(Logger logger) {
            this.logger = Objects.requireNonNull(logger, "logger");
            return this;
        }

        /** The throttle. */
        public InMemoryLoginThrottle build() {
            return new InMemoryLoginThrottle(this);
        }
    }
}
