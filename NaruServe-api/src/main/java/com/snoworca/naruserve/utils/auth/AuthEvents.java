package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.util.Set;

/**
 * Auth event listener. Every method is a default no-op, so a listener overrides only what it needs
 * ({@code new AuthEvents() { ... }} in Java, {@code object : AuthEvents { ... }} in Kotlin).
 *
 * <p>The four {@code before*} events run synchronously before the store write and may refuse it by throwing
 * {@link AuthRejection}, which is answered with its status and code. Any other exception a listener throws, an
 * {@code HttpException} included, is logged and becomes an {@link AuthUnavailableException} (503). The {@code on*}
 * events run after a successful write and before the response; their failures are only logged. Every
 * {@link AuthUser} passed to a callback is a copy whose password hash is null.
 *
 * <p>After the first public release this interface gains default methods only.
 */
// @req FR-AUTH-007
// @req CON-AUTH-006
public interface AuthEvents {

    /** Before a login with a correct password; throw {@link AuthRejection} to refuse it. */
    default void beforeLogin(AuthUser user, AuthEventContext ctx) throws Exception {
    }

    /** After a successful login. */
    default void onLogin(AuthUser user, AuthEventContext ctx) throws Exception {
    }

    /** Before register, an admin create or {@code auth.users().create}; the candidate has no id yet. */
    default void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) throws Exception {
    }

    /** After a user was created. */
    default void onUserCreated(AuthUser user, AuthEventContext ctx) throws Exception {
    }

    /** Before a rename, an attribute change, an admin update or a password change, with both values. */
    default void beforeUserUpdate(AuthUser before, AuthUser after, AuthEventContext ctx) throws Exception {
    }

    /** After a user was updated; {@code changes} names what changed. */
    default void onUserUpdated(AuthUser before, AuthUser after, Set<UserChange> changes, AuthEventContext ctx)
            throws Exception {
    }

    /** After a password was changed or reset. */
    default void onPasswordChanged(AuthUser user, AuthEventContext ctx) throws Exception {
    }

    /** Before an admin delete or {@code auth.users().delete}. */
    default void beforeUserDelete(AuthUser user, AuthEventContext ctx) throws Exception {
    }

    /** After a user was deleted. */
    default void onUserDeleted(AuthUser user, AuthEventContext ctx) throws Exception {
    }

    /** After a refresh token was rotated. */
    default void onTokenRefreshed(AuthUser user, AuthEventContext ctx) throws Exception {
    }

    /**
     * After a refresh token was presented again outside the reuse grace window: its family {@code familyId} of user
     * {@code userId} has been revoked (RFC 9700 4.14 reuse detection). The client got the ordinary invalid-refresh
     * answer, so only this event tells that detection fired.
     */
    // @req SEC-TOKEN-006
    default void onRefreshTokenReused(String userId, String familyId, AuthEventContext ctx) throws Exception {
    }

    /** After a logout; {@code allDevices} is true for {@code ?all=true}. */
    default void onLogout(String userId, boolean allDevices, AuthEventContext ctx) throws Exception {
    }
}
