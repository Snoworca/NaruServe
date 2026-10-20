package com.snoworca.naruserve;

import com.snoworca.naruserve.internal.DefaultResponse;
import com.snoworca.naruserve.internal.StandardErrors;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Unchecked error carrying an HTTP status in {@code 400..599}; throw it to answer with that status. */
// @req IR-APP-003
// @req IR-HTTP-008
public class HttpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    // ArrayList and SimpleImmutableEntry are Serializable; null until the first withHeader, and in streams written
    // before the field existed.
    private @Nullable ArrayList<Map.Entry<String, String>> headers;

    public HttpException(int status) {
        this(status, null, null);
    }

    public HttpException(int status, @Nullable String message) {
        this(status, message, null);
    }

    // @req IR-APP-003
    public HttpException(int status, @Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("HTTP error status " + status + " is outside 400..599");
        }
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** The standard error code of {@link #status()}, e.g. {@code BAD_REQUEST} for 400. */
    // @req IR-APP-003
    public String code() {
        return StandardErrors.code(status);
    }

    /**
     * Adds a header the standard error response carries, as Express {@code err.headers}; a same-name header is kept
     * beside the earlier one. Checked like {@code res.header}; {@code Content-Length} and {@code Transfer-Encoding}
     * are never written, the body writer sets them. Not thread-safe: build a new exception for each throw.
     */
    // @req IR-HTTP-008
    public HttpException withHeader(String name, String value) {
        DefaultResponse.checkHeader(name, value);
        ArrayList<Map.Entry<String, String>> h = headers;
        if (h == null) {
            h = new ArrayList<>();
            headers = h;
        }
        h.add(new SimpleImmutableEntry<>(name, value));
        return this;
    }

    /** The headers added by {@link #withHeader}, in order; unmodifiable, empty by default. */
    // @req IR-HTTP-008
    public List<Map.Entry<String, String>> headers() {
        ArrayList<Map.Entry<String, String>> h = headers;
        return h == null ? Collections.<Map.Entry<String, String>>emptyList() : Collections.unmodifiableList(h);
    }
}
