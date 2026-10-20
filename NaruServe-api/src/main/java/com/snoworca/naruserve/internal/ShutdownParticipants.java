package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Logger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Internal steps the shutdown sequence runs at three fixed points, per app: temp-file cleanup (AFTER_ON_STOP),
 * WebSocket and SSE closing (BEFORE_WAIT) and their force close (FORCE_CLOSE); nothing public registers them.
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

    /** A participant that receives the shutdown deadline, a System.nanoTime() value. */
    // @req FR-APP-020
    @FunctionalInterface
    interface DeadlineParticipant {
        void run(long deadlineNanos);
    }

    // Guarded by this.
    private final Map<Phase, List<DeadlineParticipant>> participants = new EnumMap<>(Phase.class);

    /** Appends {@code participant} to {@code phase}; participants of a phase run in call order. */
    // @req FR-APP-011
    // @req FR-APP-020
    void add(Phase phase, Runnable participant) {
        Objects.requireNonNull(participant, "participant");
        add(phase, d -> participant.run());
    }

    /** As above, for a participant that receives the deadline; both kinds share one list per phase. */
    // @req FR-APP-020
    synchronized void add(Phase phase, DeadlineParticipant participant) {
        List<DeadlineParticipant> list = participants.get(phase);
        if (list == null) {
            list = new ArrayList<>();
            participants.put(phase, list);
        }
        list.add(Objects.requireNonNull(participant, "participant"));
    }

    /**
     * Runs the participants of {@code phase} in order, each given {@code deadlineNanos}; one that throws is logged at
     * ERROR and the rest run. Returns the first {@link Error} thrown, for the caller to rethrow once the sequence
     * ended, or null.
     */
    // @req FR-APP-011
    // @req FR-APP-020
    @Nullable
    Error run(Phase phase, Logger logger, long deadlineNanos) {
        List<DeadlineParticipant> list;
        synchronized (this) {
            List<DeadlineParticipant> registered = participants.get(phase);
            list = registered == null ? new ArrayList<DeadlineParticipant>() : new ArrayList<>(registered);
        }
        Error first = null;
        for (DeadlineParticipant p : list) {
            try {
                p.run(deadlineNanos);
            } catch (Throwable e) {
                logger.error("shutdown participant failed (" + phase + ")", e);
                first = DefaultRestApp.firstError(first, e);
            }
        }
        return first;
    }
}
