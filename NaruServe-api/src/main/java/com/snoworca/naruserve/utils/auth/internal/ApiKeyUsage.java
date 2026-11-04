package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * The {@code lastUsedAt} bookkeeping of API keys (00 R47 (3)): at most one {@link ApiKeyStore#updateLastUsed} call
 * per key per minute of {@link TimeSource} time, submitted to a background executor so the request never waits for
 * it, best effort: a failing or rejected update is logged and only leaves {@code lastUsedAt} older. The store's default
 * {@code updateLastUsed} does nothing, so a store that does not track usage needs no code.
 */
// @req PERF-APIKEY-001
public final class ApiKeyUsage {

    /** The shortest time between two updates of one key: one minute. */
    public static final long INTERVAL_MILLIS = 60_000L;

    private final ApiKeyStore store;
    private final Executor executor;
    private final TimeSource time;
    private final Logger logger;
    private final ConcurrentHashMap<String, Long> lastWrite = new ConcurrentHashMap<>();

    /** Updates of {@code store} run on {@code executor}; time from {@code time}; failures go to {@code logger}. */
    // @req PERF-APIKEY-001
    public ApiKeyUsage(ApiKeyStore store, Executor executor, TimeSource time, Logger logger) {
        this.store = store;
        this.executor = executor;
        this.time = time;
        this.logger = logger;
    }

    /**
     * Records one successful authentication with {@code keyId}: schedules an update when the last one of this key is
     * a minute old or older, and returns at once either way. True when an update was scheduled.
     */
    // @req PERF-APIKEY-001
    public boolean used(final String keyId) {
        final long now = time.nowMillis();
        for (;;) {
            Long last = lastWrite.get(keyId);
            if (last != null && now - last < INTERVAL_MILLIS && now >= last) {
                return false;
            }
            boolean won = last == null ? lastWrite.putIfAbsent(keyId, now) == null
                    : lastWrite.replace(keyId, last, now);
            if (won) {
                break;
            }
        }
        try {
            executor.execute(() -> {
                try {
                    store.updateLastUsed(keyId, now);
                } catch (Throwable e) {
                    logger.warn("auth: updating lastUsedAt of an API key failed (best effort; the request was not "
                            + "affected): " + e.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException e) {
            logger.warn("auth: the lastUsedAt update of an API key was dropped; the background queue is full or "
                    + "stopped");
        }
        return true;
    }

    /** Forgets a deleted key. */
    public void forget(String keyId) {
        lastWrite.remove(keyId);
    }
}
