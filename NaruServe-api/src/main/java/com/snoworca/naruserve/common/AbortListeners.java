package com.snoworca.naruserve.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One exchange's detected-abort notifications. Each registration fires once; registering after
 * abort invokes the listener immediately. Successful response completion is a separate lifecycle
 * event and must not call {@link #abort()}.
 *
 * <p>Listeners run outside the state monitor. A listener failure is isolated so the remaining
 * listeners and the adapter's transport/resource cleanup can proceed.
 */
// @req IR-HTTP-009 AC-2 AC-3 AC-6
public final class AbortListeners {
    private final Object state = new Object();
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean aborted;

    public AbortListeners() {
    }

    /** Register one nonnull listener, or notify it now when an abort was already detected. */
    // @req IR-HTTP-009 AC-1 AC-2
    public void onAbort(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (state) {
            if (!aborted) {
                listeners.add(listener);
                return;
            }
        }
        notifyListener(listener);
    }

    /** Record a detected abnormal termination and notify the registrations once. Idempotent. */
    // @req IR-HTTP-009 AC-2 AC-3
    public void abort() {
        List<Runnable> pending;
        synchronized (state) {
            if (aborted) {
                return;
            }
            aborted = true;
            pending = new ArrayList<>(listeners);
            listeners.clear();
        }
        for (Runnable listener : pending) {
            notifyListener(listener);
        }
    }

    /** Whether an abnormal termination has been recorded; normal finish leaves this false. */
    public boolean isAborted() {
        synchronized (state) {
            return aborted;
        }
    }

    private static void notifyListener(Runnable listener) {
        SafeCallbacks.run(listener);
    }
}
