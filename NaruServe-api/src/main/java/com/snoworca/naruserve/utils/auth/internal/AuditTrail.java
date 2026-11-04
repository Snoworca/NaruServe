package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.audit.AuditSink;
import org.jspecify.annotations.Nullable;

/**
 * Emits auth's audit events to the builder's {@link AuditSink}: each event is stamped with the clock and, during a
 * request, with {@code req.requestId()} and {@code req.remoteAddress()}; an API-key actor adds its key id and a
 * service-account actor is marked. A sink that throws is logged and never changes the response. Only ids go into an
 * event, never a name, password, token, cookie value, key secret or {@code Authorization} value.
 */
// @req FR-AUTH-009
public final class AuditTrail {

    /** The request attribute holding the request's {@code AuthPrincipal}. */
    public static final String PRINCIPAL_ATTRIBUTE = "naru.auth.principal";

    private final AuditSink sink;
    private final TimeSource time;
    private final Logger logger;

    /** A trail over {@code sink}. */
    // @req FR-AUTH-009
    public AuditTrail(AuditSink sink, TimeSource time, Logger logger) {
        this.sink = sink;
        this.time = time;
        this.logger = logger;
    }

    /**
     * A builder of an event of {@code type} for {@code action}, stamped with the time and the request's id and client
     * address when {@code req} is not null, and with the actor.
     */
    // @req FR-AUTH-009
    // @req SEC-APIKEY-007
    public AuditEvent.Builder event(String type, String action, @Nullable Request req, @Nullable String actorId,
            @Nullable String actorApiKeyId, boolean actorServiceAccount) {
        AuditEvent.Builder b = AuditEvent.builder(type, action).timestampMillis(time.nowMillis()).actorId(actorId);
        if (req != null) {
            b.requestId(req.requestId());
            try {
                b.sourceIp(req.remoteAddress());
            } catch (UnsupportedOperationException none) {
                // a request view without an address
            }
        }
        String keyId = actorApiKeyId;
        if (keyId == null && req != null && actorId != null) {
            // an actor that authenticated with an API key: the key id of the request's principal (SEC-APIKEY-007)
            com.snoworca.naruserve.utils.auth.AuthPrincipal p = req.attributeAs(PRINCIPAL_ATTRIBUTE,
                    com.snoworca.naruserve.utils.auth.AuthPrincipal.class);
            if (p != null && actorId.equals(p.getId())) {
                keyId = p.getApiKeyId();
            }
        }
        if (keyId != null) {
            b.detail("apiKeyId", keyId);
        }
        if (actorServiceAccount) {
            b.detail("serviceAccount", "true");
        }
        return b;
    }

    /** Hands {@code event} to the sink; a failure is logged and swallowed. */
    // @req FR-AUTH-009
    public void emit(AuditEvent event) {
        try {
            sink.record(event);
        } catch (Exception e) {
            logger.error("auth: the audit sink failed on a " + event.getType() + " event; the response is unchanged",
                    e);
        }
    }
}
