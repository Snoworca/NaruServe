package com.snoworca.naruserve.common;

import com.snoworca.naruserve.internal.DefaultMimeTypes;
import com.snoworca.naruserve.internal.DefaultResponse;
import java.util.Locale;
import java.util.Objects;

/** Shared value validation for response convenience methods. */
// @req FR-DX-004
public final class ResponseValues {
    private ResponseValues() { }

    /** Shared status range for response writes and optional transport observations. */
    // @req FR-HTTP-028; IR-HTTP-009 AC-8
    public static boolean isStatus(int status) { return status >= 100 && status <= 999; }

    public static void redirectStatus(int status) {
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
            throw new IllegalArgumentException("redirect status must be 301, 302, 303, 307 or 308");
        }
    }

    public static String location(String url) {
        Objects.requireNonNull(url, "url");
        if (url.trim().isEmpty()) {
            throw new IllegalArgumentException("Location must not be empty");
        }
        DefaultResponse.checkHeader("Location", url);
        return url;
    }

    public static String contentType(String value) {
        Objects.requireNonNull(value, "value");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("MIME type must not be empty");
        }
        DefaultResponse.checkHeader("Content-Type", value);
        if (value.indexOf('/') >= 0) {
            return value;
        }
        String extension = value.startsWith(".") ? value.substring(1) : value;
        String type = DefaultMimeTypes.builtIn().get(extension.toLowerCase(Locale.ROOT));
        return type == null ? "application/octet-stream" : type;
    }
}
