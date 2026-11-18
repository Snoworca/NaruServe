package com.snoworca.naruserve.proxy;

import com.snoworca.naruserve.Response;
import java.util.ArrayList;
import java.util.List;

/**
 * The response header writes of a relayed response, held in order until the hand-off: the backend header copy, the
 * {@code Location} rewrite and the {@code .responseHeader} values are computed into this holder without touching the
 * client {@code Response}, so a deadline found fired at the last check before the hand-off gives a 504 that carries
 * none of them (FR-PROXY-017). {@link #applyTo} then replays the writes on {@code res} in the order they were made.
 */
// @req FR-PROXY-017
// @req FR-PROXY-011
// @req FR-PROXY-012
final class PendingResponseHeaders {

    /** One held write: {@code res.header} when {@code append} is false, {@code res.append} otherwise. */
    private static final class Write {
        final String name;
        final String value;
        final boolean append;

        Write(String name, String value, boolean append) {
            this.name = name;
            this.value = value;
            this.append = append;
        }
    }

    private final List<Write> writes = new ArrayList<>();

    /** Holds a write that replaces every earlier value of {@code name}, as {@code res.header} does. */
    void header(String name, String value) {
        writes.add(new Write(name, value, false));
    }

    /** Holds a write that adds a line of {@code name}, as {@code res.append} does. */
    void append(String name, String value) {
        writes.add(new Write(name, value, true));
    }

    /** Replays the held writes on {@code res} in the order they were made. */
    void applyTo(Response res) {
        for (Write w : writes) {
            if (w.append) {
                res.append(w.name, w.value);
            } else {
                res.header(w.name, w.value);
            }
        }
    }
}
