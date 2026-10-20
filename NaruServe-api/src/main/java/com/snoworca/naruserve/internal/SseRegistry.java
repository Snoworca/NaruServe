package com.snoworca.naruserve.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The open SSE streams of one app, the options they read and the closed flag the shutdown sets. Its two shutdown
 * participants are {@link #closeAll} before the adapter stop waits and {@link #terminate} after it returned.
 */
// @req IR-SSE-001
// @req FR-SSE-005
final class SseRegistry {

    /** CLOSED once {@link #closeAll} ran; there is no close wait, so no third state. */
    // @req FR-SSE-005
    enum State {
        OPEN,
        CLOSED
    }

    private final DefaultSseOptions options;
    private final @Nullable AppLifecycle lifecycle;
    private final SafeLogger log;
    // Guarded by this.
    private final Set<DefaultSseClient> open = Collections.newSetFromMap(new IdentityHashMap<DefaultSseClient, Boolean>());
    private State state = State.OPEN;

    SseRegistry(DefaultSseOptions options, @Nullable AppLifecycle lifecycle, SafeLogger log) {
        this.options = options;
        this.lifecycle = lifecycle;
        this.log = log;
    }

    /** Adds {@code client}; false once the registry is closed, so a stream opening then is never left out. */
    // @req FR-SSE-005
    synchronized boolean register(DefaultSseClient client) {
        if (state != State.OPEN) {
            return false;
        }
        return open.add(client);
    }

    synchronized void remove(DefaultSseClient client) {
        open.remove(client);
    }

    synchronized int size() {
        return open.size();
    }

    synchronized State state() {
        return state;
    }

    DefaultSseOptions options() {
        return options;
    }

    @Nullable AppLifecycle lifecycle() {
        return lifecycle;
    }

    SafeLogger log() {
        return log;
    }

    /**
     * The before-wait participant: sets the flag and copies the open streams in one block, then queues the closing
     * notice on each and ends it. Returns without waiting for any drain; the adapter stop waits for the workers, so
     * {@code deadlineNanos} is not used.
     */
    // @req FR-SSE-005
    void closeAll(long deadlineNanos) {
        List<DefaultSseClient> snapshot;
        synchronized (this) {
            state = State.CLOSED;
            snapshot = new ArrayList<>(open);
        }
        for (DefaultSseClient c : snapshot) {
            c.closing();
        }
    }

    /**
     * The force-close participant, after the adapter stop: empties the registry, then ends and empties every stream
     * left. Listeners stay with the drain or the abort, so a drain starting later still ends its response.
     */
    // @req FR-SSE-005
    void terminate() {
        List<DefaultSseClient> left;
        synchronized (this) {
            state = State.CLOSED;
            left = new ArrayList<>(open);
            open.clear();
        }
        for (DefaultSseClient c : left) {
            c.endBy("shutdown");
            c.clearQueue();
        }
    }
}
