package com.snoworca.naruserve.utils.auth.audit;

import com.snoworca.naruserve.Logger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Ready-made {@link AuditSink}s. */
// @req FR-AUTH-009
public final class AuditSinks {

    private AuditSinks() {
    }

    /**
     * Writes one INFO line per event to {@code logger}: {@code audit type=... outcome=... action=... actor=...
     * target=... ip=... rid=... reason=...} followed by the details; events hold no secret or PII.
     */
    // @req FR-AUTH-009
    public static AuditSink logger(final Logger logger) {
        Objects.requireNonNull(logger, "logger");
        return e -> logger.info(line(e));
    }

    /** A sink handing every event to each of {@code sinks} in order; one failing sink does not stop the others. */
    // @req FR-AUTH-009
    public static AuditSink all(AuditSink... sinks) {
        final List<AuditSink> list = new ArrayList<>(Arrays.asList(sinks));
        for (AuditSink s : list) {
            Objects.requireNonNull(s, "sink");
        }
        return e -> {
            Exception first = null;
            for (AuditSink s : list) {
                try {
                    s.record(e);
                } catch (Exception failed) {
                    if (first == null) {
                        first = failed;
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        };
    }

    /** A sink that drops every event (the default when no sink is set). */
    // @req FR-AUTH-009
    public static AuditSink none() {
        return e -> { };
    }

    static String line(AuditEvent e) {
        StringBuilder b = new StringBuilder("audit type=").append(clean(e.getType())).append(" outcome=")
                .append(e.getOutcome()).append(" action=").append(clean(e.getAction()));
        append(b, "actor", e.getActorId());
        append(b, "target", e.getTargetId());
        append(b, "ip", e.getSourceIp());
        append(b, "rid", e.getRequestId());
        append(b, "reason", e.getReason());
        for (Map.Entry<String, String> d : e.getDetails().entrySet()) {
            append(b, d.getKey(), d.getValue());
        }
        return b.append(" at=").append(e.getTimestampMillis()).toString();
    }

    private static void append(StringBuilder b, String name, @Nullable String value) {
        if (value != null) {
            b.append(' ').append(clean(name)).append('=').append(clean(value));
        }
    }

    /** {@code s} with control characters escaped, so a value from a request cannot start a forged log line. */
    private static String clean(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                if (b == null) {
                    b = new StringBuilder(s.substring(0, i));
                }
                b.append(String.format("\\u%04x", (int) c));
            } else if (b != null) {
                b.append(c);
            }
        }
        return b == null ? s : b.toString();
    }
}
