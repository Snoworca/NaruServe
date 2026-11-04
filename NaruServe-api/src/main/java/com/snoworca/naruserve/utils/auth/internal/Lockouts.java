package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.TimeSource;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The in-memory state of the optional hard lockout (FR-FLOW-009), per user id: consecutive failed password logins
 * and, once they reach the policy count, the time the lock ends. A success or an admin release forgets the user; an
 * ended lock starts the count again. Capped like the login throttle so distinct ids cannot exhaust memory: at the cap
 * the entry touched longest ago is dropped. Thread-safe.
 */
// @req FR-FLOW-009
public final class Lockouts {

    private static final int MAX_ENTRIES = 100_000;

    private static final class State {
        int failures;
        long lockedUntil;
    }

    private final int failures;
    private final long durationMillis;
    private final TimeSource time;
    private final LinkedHashMap<String, State> states = new LinkedHashMap<>(16, 0.75f, true);

    /** A lock after {@code failures} failures lasting {@code durationMillis}, on {@code time}. */
    // @req FR-FLOW-009
    /** Hears each new lock. */
    public interface LockListener {
        void locked(String userId, long untilMillis);
    }

    private volatile LockListener listener;

    public void onLock(LockListener l) {
        this.listener = l;
    }

    public Lockouts(int failures, long durationMillis, TimeSource time) {
        this.failures = failures;
        this.durationMillis = durationMillis;
        this.time = time;
    }

    /** True while {@code userId} is locked. */
    // @req FR-FLOW-009
    public synchronized boolean isLocked(String userId) {
        State s = states.get(userId);
        if (s == null || s.lockedUntil == 0) {
            return false;
        }
        if (time.nowMillis() < s.lockedUntil) {
            return true;
        }
        states.remove(userId);
        return false;
    }

    /** Counts a failed password login of {@code userId}; true when this failure locked the account. */
    // @req FR-FLOW-009
    public synchronized boolean recordFailure(String userId) {
        if (isLocked(userId)) {
            return false;
        }
        State s = states.get(userId);
        if (s == null) {
            if (states.size() >= MAX_ENTRIES) {
                Iterator<Map.Entry<String, State>> it = states.entrySet().iterator();
                it.next();
                it.remove();
            }
            s = new State();
            states.put(userId, s);
        }
        s.failures++;
        if (s.failures >= failures) {
            s.lockedUntil = time.nowMillis() + durationMillis;
            LockListener l = listener;
            if (l != null) {
                l.locked(userId, s.lockedUntil); // OPS-AUTH-030 AC-5
            }
            return true;
        }
        return false;
    }

    /** Forgets {@code userId}: after a successful login or an admin release. True when there was a lock. */
    // @req FR-FLOW-009
    public synchronized boolean release(String userId) {
        State s = states.remove(userId);
        return s != null && s.lockedUntil != 0 && time.nowMillis() < s.lockedUntil;
    }
}
