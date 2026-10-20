package com.snoworca.naruserve.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Resources closed once each, by identity, in reverse order of their first {@link #add}. */
// @req FR-APP-008
final class OwnedResources {

    // Guarded by this.
    private final List<AutoCloseable> order = new ArrayList<>();
    private final Set<AutoCloseable> seen = Collections.newSetFromMap(new IdentityHashMap<AutoCloseable, Boolean>());

    /** Tracks {@code resource}; an object already present by identity keeps its first position. */
    // @req FR-APP-008
    synchronized void add(AutoCloseable resource) {
        if (seen.add(resource)) {
            order.add(resource);
        }
    }

    /**
     * Closes every tracked resource in reverse order, never the object identical to {@code skip}, continuing after
     * a failing close. Returns the failures in close order; the list is emptied, so a second call closes nothing.
     * An {@link Error} from a close does not stop the others: after the last close the first Error is rethrown with
     * the Exception failures attached as suppressed.
     */
    // @req FR-APP-008
    List<Exception> closeAll(@Nullable Object skip) {
        List<AutoCloseable> toClose;
        synchronized (this) {
            toClose = new ArrayList<>(order);
            order.clear();
        }
        List<Exception> failures = new ArrayList<>();
        Error firstError = null;
        for (int i = toClose.size() - 1; i >= 0; i--) {
            AutoCloseable r = toClose.get(i);
            if (r == skip) {
                continue;
            }
            try {
                r.close();
            } catch (Exception e) {
                failures.add(e);
            } catch (Error e) {
                firstError = firstError == null ? e : firstError;
            }
        }
        if (firstError != null) {
            for (Exception failure : failures) {
                firstError.addSuppressed(failure);
            }
            throw firstError;
        }
        return failures;
    }
}
