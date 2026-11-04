package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.auth.AuthRejection;
import com.snoworca.naruserve.utils.auth.AuthUnavailableException;
import com.snoworca.naruserve.utils.auth.user.AuthUser;

/**
 * The one place listener calls go through, so their exception mapping is uniform: a before-event passes
 * {@link AuthRejection} through and turns any other exception into a logged {@link AuthUnavailableException} (503);
 * an after-event only logs. Listeners receive {@link #copy(AuthUser) copies} without the password hash.
 */
// @req FR-AUTH-007
public final class EventCalls {

    /** One listener invocation. */
    @FunctionalInterface
    public interface Call {
        /** Calls the listener. */
        void run() throws Exception;
    }

    private EventCalls() {
    }

    /** Runs a before-event; it may refuse with {@link AuthRejection}. */
    // @req FR-AUTH-007
    public static void before(Logger logger, String event, Call call) {
        try {
            call.run();
        } catch (AuthRejection rejection) {
            throw rejection;
        } catch (Exception e) {
            logger.error("auth event " + event + " failed; answering 503. To refuse the action instead, throw new "
                    + "AuthRejection(status, code, message) from the listener", e); // OPS-AUTH-024 AC-1
            throw new AuthUnavailableException("auth event " + event + " failed", e);
        }
    }

    /** Runs an after-event; a failure is logged and the response stays as it is. */
    // @req FR-AUTH-007
    public static void after(Logger logger, String event, Call call) {
        try {
            call.run();
        } catch (Exception e) {
            logger.error("auth event " + event + " failed after the write; the response is unchanged", e);
        }
    }

    /** {@code user} without its password hash, the only form a listener sees. */
    // @req FR-AUTH-007
    public static AuthUser copy(AuthUser user) {
        return user.getPasswordHash() == null ? user : user.toBuilder().passwordHash(null).build();
    }
}
