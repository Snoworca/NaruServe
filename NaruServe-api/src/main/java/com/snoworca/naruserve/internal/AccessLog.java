package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.LogLevel;
import java.util.Set;

/**
 * One access-log line per request: {@code <method> <path> <status>[ aborted][ <n>ms][ in=<i> out=<o>][ rid=<id>]};
 * a WebSocket session adds a close line, and an SSE stream's one line comes at its end with its start and cause.
 * It takes no header, query or body, so none can be logged; method and path are escaped to keep one line.
 */
// @req FR-APP-019
// @req SEC-APP-001
final class AccessLog {

    private final Set<String> exclude;
    private final SafeLogger logger;
    private final boolean enabled;
    private final LogLevel level;
    private final boolean duration;
    private final boolean bytes;
    private final boolean requestId;

    /** A disabled log, for dispatchers built without an app. */
    // @req FR-APP-019
    AccessLog() {
        this(disabled(), new SafeLogger(new com.snoworca.naruserve.ConsoleLogger()));
    }

    /** Built at listen (in freeze) from the app's options and its SafeLogger; the flags and excludes are copied here. */
    // @req FR-APP-019
    // @req IR-APP-007
    AccessLog(DefaultAccessLogOptions options, SafeLogger logger) {
        this.exclude = options.excludeSnapshot();
        this.logger = logger;
        this.enabled = options.isEnabled();
        this.level = options.logLevel();
        this.duration = options.durationOn();
        this.bytes = options.bytesOn();
        this.requestId = options.requestIdOn();
    }

    private static DefaultAccessLogOptions disabled() {
        DefaultAccessLogOptions o = new DefaultAccessLogOptions();
        o.enabled(false);
        return o;
    }

    /** No-op when disabled or excluded; otherwise one line at the configured level. Never throws. */
    // @req FR-APP-019
    // @req FR-APP-014
    // @req SEC-APP-001
    void write(String methodToken, String rawPath, int status, boolean aborted, long startNanos, long in, long out,
            String rid) {
        try {
            if (!enabled || exclude.contains(rawPath)) {
                return;
            }
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log(format(methodToken, rawPath, status, aborted, ms, in, out, rid));
        } catch (RuntimeException ignored) {
            // a logger failure is never a request failure
        }
    }

    /** The WebSocket close line {@code WS <path> <code>[ <n>ms][ rid=<id>]}, filtered as {@link #write}. */
    // @req FR-APP-019
    void writeClose(String rawPath, int code, long openNanos, String rid) {
        try {
            if (!enabled || exclude.contains(rawPath)) {
                return;
            }
            StringBuilder sb = new StringBuilder(48).append("WS ").append(escape(rawPath)).append(' ').append(code);
            if (duration) {
                sb.append(' ').append((System.nanoTime() - openNanos) / 1_000_000).append("ms");
            }
            if (requestId) {
                sb.append(" rid=").append(rid);
            }
            log(sb.toString());
        } catch (RuntimeException ignored) {
            // a logger failure is never a request failure
        }
    }

    /** The SSE stream-end line: the request line, never aborted, then {@code  sse start=<ms> cause=<cause>}. */
    // @req FR-APP-019
    void writeSse(String methodToken, String rawPath, int status, long startNanos, long in, long out, String rid,
            long startMillis, String cause) {
        try {
            if (!enabled || exclude.contains(rawPath)) {
                return;
            }
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log(format(methodToken, rawPath, status, false, ms, in, out, rid) + " sse start=" + startMillis + " cause="
                    + cause);
        } catch (RuntimeException ignored) {
            // a logger failure is never a request failure
        }
    }

    // @req FR-APP-019
    private void log(String line) {
        switch (level) {
            case TRACE:
                logger.trace(line);
                break;
            case DEBUG:
                logger.debug(line);
                break;
            case WARN:
                logger.warn(line);
                break;
            case ERROR:
                logger.error(line);
                break;
            default:
                logger.info(line);
        }
    }

    // @req FR-APP-019
    // @req SEC-APP-001
    String format(String methodToken, String rawPath, int status, boolean aborted, long durationMs, long in,
            long out, String rid) {
        StringBuilder sb = new StringBuilder(64).append(escape(methodToken)).append(' ').append(escape(rawPath))
                .append(' ').append(status);
        if (aborted) {
            sb.append(" aborted");
        }
        if (duration) {
            sb.append(' ').append(durationMs).append("ms");
        }
        if (bytes) {
            sb.append(" in=").append(in).append(" out=").append(out);
        }
        if (requestId) {
            sb.append(" rid=").append(rid);
        }
        return sb.toString();
    }

    /** {@code \\}, CR, LF, TAB as two-char escapes; other C0, DEL and C1 controls as {@code \\u} plus 4 hex. */
    // @req SEC-APP-001
    static String escape(String value) {
        StringBuilder sb = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String esc;
            if (c == '\\') {
                esc = "\\\\";
            } else if (c == '\r') {
                esc = "\\r";
            } else if (c == '\n') {
                esc = "\\n";
            } else if (c == '\t') {
                esc = "\\t";
            } else if (c < 0x20 || c >= 0x7F && c <= 0x9F) {
                String hex = Integer.toHexString(c);
                esc = "\\u" + "0000".substring(hex.length()) + hex;
            } else {
                if (sb != null) {
                    sb.append(c);
                }
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(value.length() + 8).append(value, 0, i);
            }
            sb.append(esc);
        }
        return sb == null ? value : sb.toString();
    }
}
