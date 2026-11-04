package com.snoworca.naruserve.utils.auth.audit;

/**
 * Receives the audit events auth emits (the {@code AuditEvent} types: security write conflicts now; recovery use,
 * refused grants and self-changes, API key issue and revoke as their endpoints land), set with {@code Auth.builder(...).audit(sink)}. Called on the request thread; a sink
 * that throws never changes the response, auth logs the failure. Kotlin: {@code AuditSink { e -> log(e.type) }}.
 * After the first public release this interface gains default methods only.
 */
// @req FR-AUTH-009
@FunctionalInterface
public interface AuditSink {

    /** Handles one event; the event is immutable and may be kept. */
    void record(AuditEvent event) throws Exception;
}
