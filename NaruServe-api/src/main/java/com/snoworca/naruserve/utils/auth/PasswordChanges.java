package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.utils.auth.internal.EventCalls;
import com.snoworca.naruserve.utils.auth.user.AuthUser;

/**
 * The user events of the self-service password change (00 R37 9th–10th editions), which {@code POST /auth/password}
 * ({@code AccountEndpoints.changePassword}) runs itself: beforeUserUpdate before the write ({@link #beforeUpdate}),
 * then onUserUpdated ({@code PASSWORD}) and onPasswordChanged ({@link #afterUpdate}), all with origin SELF.
 */
// @req REL-IAM-002
// @req SEC-IAM-003
// @req FR-TOKEN-004
final class PasswordChanges {

    private PasswordChanges() {
    }

    /**
     * The before-event of a self-service password change of {@code POST /auth/password}: beforeUserUpdate (origin
     * SELF, the hash-free row as before and after; it may refuse).
     */
    // @req FR-FLOW-006
    // @req FR-AUTH-008
    static void beforeUpdate(Auth auth, final AuthUser before, final AuthEventContext ctx) {
        for (final AuthEvents l : auth.eventListeners()) {
            EventCalls.before(auth.log(), "beforeUserUpdate", () -> l.beforeUserUpdate(before, before, ctx));
        }
    }

    /**
     * The after-events of a self-service password change that applied: onUserUpdated with
     * {@code PASSWORD}, then onPasswordChanged, each with the hash-free row read after the write.
     */
    // @req FR-FLOW-006
    // @req FR-AUTH-008
    static void afterUpdate(Auth auth, final AuthUser before, AuthUser now, final AuthEventContext ctx) {
        final AuthUser out = EventCalls.copy(now);
        final java.util.Set<UserChange> changes = java.util.Collections.unmodifiableSet(
                java.util.EnumSet.of(UserChange.PASSWORD));
        for (final AuthEvents l : auth.eventListeners()) {
            EventCalls.after(auth.log(), "onUserUpdated", () -> l.onUserUpdated(before, out, changes, ctx));
        }
        for (final AuthEvents l : auth.eventListeners()) {
            EventCalls.after(auth.log(), "onPasswordChanged", () -> l.onPasswordChanged(out, ctx));
        }
    }
}
