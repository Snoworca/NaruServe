package com.snoworca.naruserve.common;

import java.util.Objects;

/** Synchronous fail-soft notifications for resource cleanup and termination callbacks. */
// @req IR-HTTP-009 AC-3
public final class SafeCallbacks {
    private SafeCallbacks() {
    }

    /** Run a nonnull callback now, isolating its failure so remaining cleanup can proceed. */
    // @req IR-HTTP-009 AC-3
    public static void run(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        try {
            callback.run();
        } catch (Throwable ignored) {
            // Resource notification failures must not interrupt the caller's cleanup.
        }
    }
}
