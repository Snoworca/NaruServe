package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.Request;
import org.jspecify.annotations.Nullable;

/** The context of one auth event: its origin, the request if any, the acting user if any, and the time. */
// @req FR-AUTH-007
public final class AuthEventContext {

    private final @Nullable UserOrigin origin;
    private final @Nullable Request request;
    private final @Nullable String actorId;
    private final long timestamp;

    /**
     * A context; {@code origin} is set for user-change events only, {@code request} is null for app calls outside a
     * request, {@code actorId} is the admin or app user acting, and {@code timestamp} is epoch milliseconds.
     */
    // @req FR-AUTH-007
    public AuthEventContext(@Nullable UserOrigin origin, @Nullable Request request, @Nullable String actorId,
            long timestamp) {
        this.origin = origin;
        this.request = request;
        this.actorId = actorId;
        this.timestamp = timestamp;
    }

    /** Where a user change came from; null for events that are not user changes, such as login. */
    public @Nullable UserOrigin getOrigin() {
        return origin;
    }

    /** The request that caused the event, or null for an app call outside a request. */
    public @Nullable Request getRequest() {
        return request;
    }

    /** The id of the admin or app user acting, or null. */
    public @Nullable String getActorId() {
        return actorId;
    }

    /** When the event happened, in epoch milliseconds. */
    public long getTimestamp() {
        return timestamp;
    }
}
