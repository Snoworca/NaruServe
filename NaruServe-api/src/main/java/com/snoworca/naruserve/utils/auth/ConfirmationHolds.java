package com.snoworca.naruserve.utils.auth;

import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * One current-password confirmation per user id at a time, shared by every path that counts toward the per-user
 * confirmation limit (00 R34 (7), SEC-FLOW-015): {@code POST /auth/password}, {@code POST /auth/me/apikeys},
 * {@code POST /auth/admin/apikeys} and {@code auth.users().checkPassword}. Without it parallel guesses would all pass
 * the limit check before any failure is counted. A hold is a token of its own request, so a late or repeated release
 * never frees another request's hold.
 */
// @req SEC-FLOW-015
// @req FR-APIKEY-003
final class ConfirmationHolds {

    private final ConcurrentHashMap<String, Object> holds = new ConcurrentHashMap<>();

    /** A new hold of {@code userId}, or null when another confirmation of that user holds it. */
    @Nullable Object tryHold(String userId) {
        Object hold = new Object();
        return holds.putIfAbsent(userId, hold) == null ? hold : null;
    }

    /** A hold of {@code userId}, waiting at most {@code waitMillis}; null when it stayed taken. */
    @Nullable Object hold(String userId, long waitMillis) {
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        for (;;) {
            Object h = tryHold(userId);
            if (h != null || System.nanoTime() >= deadline) {
                return h;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** Releases {@code hold} of {@code userId}; a hold already released, or another one, is left alone. */
    void release(String userId, @Nullable Object hold) {
        if (hold != null) {
            holds.remove(userId, hold);
        }
    }

    /** The users whose confirmation is running; for tests. */
    int size() {
        return holds.size();
    }

    /** 429 {@code too_many_attempts} for a confirmation that finds another of the same user running. */
    static AuthRejection busy() {
        return new AuthRejection(AuthErrorCode.TOO_MANY_ATTEMPTS.getStatus(), AuthErrorCode.TOO_MANY_ATTEMPTS.getCode(),
                "another password confirmation of this user is running; try again", 1L);
    }
}
