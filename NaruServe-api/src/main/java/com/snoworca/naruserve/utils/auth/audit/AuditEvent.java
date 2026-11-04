package com.snoworca.naruserve.utils.auth.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One security action auth took, as an {@link AuditSink} receives it: an immutable value with a fixed schema. It
 * holds ids, never a username, e-mail, password, token, cookie value, API key secret or {@code Authorization} value.
 * {@link #getType()} is one of the type constants here (more may be added); {@link #getDetails()} carries extra
 * facts such as {@code apiKeyId} (the key of an API-key actor) and {@code serviceAccount} ({@code "true"} for a
 * service-account actor). Express has no counterpart; apps log these themselves.
 */
// @req FR-AUTH-009
public final class AuditEvent {

    /** A one-time setup or recovery token was used. */
    public static final String RECOVERY_USED = "recovery_used";
    /** An admin write was refused because the actor may not grant or touch what it asked for. */
    public static final String GRANT_DENIED = "grant_denied";
    /** An actor tried to change its own roles or groups, or to disable or delete itself. */
    public static final String SELF_CHANGE_DENIED = "self_change_denied";
    /** The user store did not apply a security write after its retries (503 security_write_conflict). */
    public static final String SECURITY_WRITE_CONFLICT = "security_write_conflict";
    /**
     * An admin user update (A4) failed after its password step was applied; the details name what was applied
     * ({@code applied}) and the event marks the partial write.
     */
    public static final String USER_UPDATE_FAILED = "user_update_failed";
    /** An API key was issued. */
    public static final String API_KEY_ISSUED = "api_key_issued";
    /** An API key was revoked. */
    public static final String API_KEY_REVOKED = "api_key_revoked";

    /** How the action ended. */
    public enum Outcome {
        /** The action was carried out. */
        SUCCESS,
        /** The action was refused by a rule. */
        DENIED,
        /** The action failed (a store did not apply it). */
        FAILURE
    }

    private final long timestampMillis;
    private final String type;
    private final Outcome outcome;
    private final @Nullable String actorId;
    private final @Nullable String targetId;
    private final String action;
    private final @Nullable String sourceIp;
    private final @Nullable String requestId;
    private final @Nullable String reason;
    private final Map<String, String> details;

    private AuditEvent(Builder b) {
        this.timestampMillis = b.timestampMillis;
        this.type = b.type;
        this.outcome = b.outcome;
        this.actorId = b.actorId;
        this.targetId = b.targetId;
        this.action = b.action;
        this.sourceIp = b.sourceIp;
        this.requestId = b.requestId;
        this.reason = b.reason;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(b.details));
    }

    /** A builder of an event of {@code type} for {@code action} (for example {@code "setRoles"}). */
    // @req FR-AUTH-009
    public static Builder builder(String type, String action) {
        return new Builder(Objects.requireNonNull(type, "type"), Objects.requireNonNull(action, "action"));
    }

    /** When it happened, in epoch milliseconds. */
    public long getTimestampMillis() {
        return timestampMillis;
    }

    /** What happened: one of the type constants. */
    public String getType() {
        return type;
    }

    /** How it ended. */
    public Outcome getOutcome() {
        return outcome;
    }

    /** The user id that acted, or null (an anonymous request or a start-up action). */
    public @Nullable String getActorId() {
        return actorId;
    }

    /** The id the action was aimed at (a user or key id), or null. */
    public @Nullable String getTargetId() {
        return targetId;
    }

    /** The operation, for example {@code setRoles} or {@code issue}. */
    public String getAction() {
        return action;
    }

    /** The client address ({@code req.remoteAddress()}), or null outside a request. */
    public @Nullable String getSourceIp() {
        return sourceIp;
    }

    /** The {@code req.requestId()} of the request that raised it, or null outside a request. */
    public @Nullable String getRequestId() {
        return requestId;
    }

    /** Why it was refused or failed, or null. */
    public @Nullable String getReason() {
        return reason;
    }

    /** Extra facts, unmodifiable. */
    public Map<String, String> getDetails() {
        return details;
    }

    @Override
    public String toString() {
        return AuditSinks.line(this);
    }

    /** Collects the fields of an {@link AuditEvent}. */
    public static final class Builder {
        private long timestampMillis;
        private final String type;
        private Outcome outcome = Outcome.SUCCESS;
        private @Nullable String actorId;
        private @Nullable String targetId;
        private final String action;
        private @Nullable String sourceIp;
        private @Nullable String requestId;
        private @Nullable String reason;
        private final Map<String, String> details = new LinkedHashMap<>();

        private Builder(String type, String action) {
            this.type = type;
            this.action = action;
        }

        /** When it happened. */
        public Builder timestampMillis(long millis) {
            this.timestampMillis = millis;
            return this;
        }

        /** How it ended; {@link Outcome#SUCCESS} when not called. */
        public Builder outcome(Outcome outcome) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            return this;
        }

        /** The acting user id. */
        public Builder actorId(@Nullable String actorId) {
            this.actorId = actorId;
            return this;
        }

        /** The target id. */
        public Builder targetId(@Nullable String targetId) {
            this.targetId = targetId;
            return this;
        }

        /** The client address. */
        public Builder sourceIp(@Nullable String sourceIp) {
            this.sourceIp = sourceIp;
            return this;
        }

        /** The request id. */
        public Builder requestId(@Nullable String requestId) {
            this.requestId = requestId;
            return this;
        }

        /** Why it was refused or failed. */
        public Builder reason(@Nullable String reason) {
            this.reason = reason;
            return this;
        }

        /** One extra fact. */
        public Builder detail(String name, String value) {
            details.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** The event. */
        public AuditEvent build() {
            return new AuditEvent(this);
        }
    }
}
