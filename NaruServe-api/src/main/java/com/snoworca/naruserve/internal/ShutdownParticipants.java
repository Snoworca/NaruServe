package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Logger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Internal steps the shutdown sequence runs at three fixed points, per app. Later waves add temp-file cleanup
 * (AFTER_ON_STOP) and WebSocket/SSE closing (BEFORE_WAIT) here; nothing public registers them.
 */
// @req FR-APP-011
final class ShutdownParticipants {

    /** Where in the shutdown sequence a participant runs. */
    // @req FR-APP-011
    enum Phase {
        /** After STOPPING began, before the adapter stop waits. */
        BEFORE_WAIT,
        /** After the adapter stop returned (deadline reached or nothing in flight). */
        FORCE_CLOSE,
        /** After the onStop callbacks, before the owned resources close. */
        AFTER_ON_STOP
    }

    // Guarded by this.
    private final Map<Phase, List<Runnable>> participants = new EnumMap<>(Phase.class);

    /** Appends {@code participant} to {@code phase}; participants of a phase run in call order. */
    // @req FR-APP-011
    synchronized void add(Phase phase, Runnable participant) {
        List<Runnable> list = participants.get(phase);
        if (list == null) {
            list = new ArrayList<>();
            participants.put(phase, list);
        }
        list.add(participant);
    }

    /**
     * Runs the participants of {@code phase} in order; one that throws is logged at ERROR and the rest run. Returns
     * the first {@link Error} thrown, for the caller to rethrow once the sequence ended, or null.
     */
    // @req FR-APP-011
    @Nullable
    Error run(Phase phase, Logger logger) {
        List<Runnable> list;
        synchronized (this) {
            List<Runnable> registered = participants.get(phase);
            list = registered == null ? new ArrayList<Runnable>() : new ArrayList<>(registered);
        }
        Error first = null;
        for (Runnable p : list) {
            try {
                p.run();
            } catch (Throwable e) {
                logger.error("shutdown participant failed (" + phase + ")", e);
                first = DefaultRestApp.firstError(first, e);
            }
        }
        return first;
    }
}
