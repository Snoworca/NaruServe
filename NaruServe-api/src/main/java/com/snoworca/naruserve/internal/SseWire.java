package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.SseEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The event-stream text of one SSE event and the field check of names and ids. Not API. */
// @req FR-SSE-001
public final class SseWire {

    private static final Method WIRE = PackageMethods.lookup(SseEvent.class, "wire");

    private SseWire() {
    }

    /**
     * The {@code event}, {@code id}, {@code retry} and {@code data} lines in that order, then an empty line; each line
     * ends with LF. A null event or id and a retry of -1 write no line. The data is split at CRLF, CR and LF into one
     * {@code data: } line per line, empty lines included, and null data writes the single line {@code data: }.
     */
    // @req FR-SSE-001
    public static String encode(@Nullable String event, @Nullable String id, long retry, @Nullable String data) {
        StringBuilder sb = new StringBuilder();
        if (event != null) {
            sb.append("event: ").append(event).append('\n');
        }
        if (id != null) {
            sb.append("id: ").append(id).append('\n');
        }
        if (retry >= 0) {
            sb.append("retry: ").append(retry).append('\n');
        }
        String d = data == null ? "" : data;
        int start = 0;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c == '\r' || c == '\n') {
                sb.append("data: ").append(d, start, i).append('\n');
                if (c == '\r' && i + 1 < d.length() && d.charAt(i + 1) == '\n') {
                    i++; // CRLF is one line break
                }
                start = i + 1;
            }
        }
        sb.append("data: ").append(d, start, d.length()).append('\n');
        return sb.append('\n').toString();
    }

    /** {@link NullPointerException} for null, {@link IllegalArgumentException} for CR, LF or NUL in {@code value}. */
    // @req FR-SSE-001
    public static void checkField(String what, String value) {
        Objects.requireNonNull(value, what);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\u0000') {
                throw new IllegalArgumentException(what + " must not contain CR, LF or NUL");
            }
        }
    }

    /** The text of {@code e}, through its package-private {@code wire()}. */
    // @req FR-SSE-001
    public static String wire(SseEvent e) {
        try {
            return (String) WIRE.invoke(Objects.requireNonNull(e, "event"));
        } catch (IllegalAccessException | InvocationTargetException x) {
            throw new IllegalStateException("SseEvent.wire is unreachable", x);
        }
    }
}
