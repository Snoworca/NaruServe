package com.snoworca.naruserve.internal;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The open SSE streams of one app and the options they read.
 * shortcut: no closed flag and no shutdown participants yet; FR-SSE-005 (wave 7 part 11) adds them.
 */
// @req IR-SSE-001
final class SseRegistry {

    private final DefaultSseOptions options;
    private final @Nullable AppLifecycle lifecycle;
    private final SafeLogger log;
    // Guarded by this.
    private final Set<DefaultSseClient> open = Collections.newSetFromMap(new IdentityHashMap<DefaultSseClient, Boolean>());

    SseRegistry(DefaultSseOptions options, @Nullable AppLifecycle lifecycle, SafeLogger log) {
        this.options = options;
        this.lifecycle = lifecycle;
        this.log = log;
    }

    synchronized boolean register(DefaultSseClient client) {
        return open.add(client);
    }

    synchronized void remove(DefaultSseClient client) {
        open.remove(client);
    }

    synchronized int size() {
        return open.size();
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
}
