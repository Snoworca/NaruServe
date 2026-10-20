package com.snoworca.naruserve;

import com.snoworca.naruserve.internal.SseWire;
import org.jspecify.annotations.Nullable;

/**
 * One SSE event, built as {@code SseEvent.event("progress").id("100").data("...").retry(3000)} and sent with
 * {@link SseClient#send(SseEvent)}. The fields are written in the order {@code event}, {@code id}, {@code retry},
 * {@code data} whatever the setter order; an unset {@code id} or {@code retry} writes no line. A name or id with CR, LF
 * or NUL throws {@link IllegalArgumentException}.
 */
// @req IR-SSE-001
// @req FR-SSE-001
public final class SseEvent {

    private final String name;
    private @Nullable String id;
    private long retry = -1;
    private @Nullable String data;

    private SseEvent(String name) {
        this.name = name;
    }

    /** A new event whose {@code event:} line names {@code name}. */
    // @req IR-SSE-001
    public static SseEvent event(String name) {
        SseWire.checkField("event", name);
        return new SseEvent(name);
    }

    /** Sets the {@code id:} line, which a reconnecting {@code EventSource} sends back as {@code Last-Event-ID}. */
    // @req IR-SSE-001
    public SseEvent id(String id) {
        SseWire.checkField("id", id);
        this.id = id;
        return this;
    }

    /** Sets the data, split at CR, LF and CRLF into {@code data:} lines; {@code null} writes one empty data line. */
    // @req IR-SSE-001
    public SseEvent data(@Nullable String data) {
        this.data = data;
        return this;
    }

    /** Sets the {@code retry:} line, the client's reconnect delay in milliseconds; a negative value throws. */
    // @req IR-SSE-001
    public SseEvent retry(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("retry must not be negative: " + millis);
        }
        this.retry = millis;
        return this;
    }

    /** The event-stream text; package-private, so SseWire reaches it by reflection. */
    // @req FR-SSE-001
    String wire() {
        return SseWire.encode(name, id, retry, data);
    }
}
