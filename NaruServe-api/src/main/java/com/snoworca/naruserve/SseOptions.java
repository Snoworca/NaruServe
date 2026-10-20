package com.snoworca.naruserve;

import java.util.concurrent.TimeUnit;

/**
 * SSE settings, configured through {@code app.sse(o -> o.heartbeatInterval(30, TimeUnit.SECONDS))}. Each setter returns
 * these options; a value outside its range throws {@link IllegalArgumentException} naming the setting and the range.
 * There is no unlimited value. The dispatcher uses a copy taken at {@code listen}.
 */
// @req IR-SSE-001
public interface SseOptions {

    /** Interval of the {@code : ping} heartbeat comment on an idle stream, {@code 1 s..1 h}; default 15 s. */
    SseOptions heartbeatInterval(long time, TimeUnit unit);

    /** Bytes queued for sending on one stream, {@code 1..16777216}; default 262144 (256 KiB). */
    SseOptions sendQueueSize(int bytes);
}
