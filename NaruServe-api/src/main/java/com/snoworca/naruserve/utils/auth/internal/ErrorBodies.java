package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.utils.auth.AuthRejection;
import org.jspecify.annotations.Nullable;

/**
 * The auth JSON error body {@code {"error":{"code","message","requestId"}}}, the Core shape, written by auth itself
 * because Core's error table has no 401, 403 or 429. A null message becomes the status's reason phrase; a retry delay
 * becomes {@code Retry-After}. No stack trace or exception text other than the given message is written.
 */
// @req IR-AUTH-003
public final class ErrorBodies {

    private ErrorBodies() {
    }

    /** Answers {@code rejection} with its status, code, message and retry delay. */
    // @req IR-AUTH-003
    public static void write(Request req, Response res, AuthRejection rejection) {
        write(req, res, rejection.getStatus(), rejection.getCode(), rejection.getMessage(),
                rejection.getRetryAfterSeconds());
    }

    /** Answers {@code status} with the auth error body. */
    // @req IR-AUTH-003
    public static void write(Request req, Response res, int status, String code, @Nullable String message,
            @Nullable Long retryAfterSeconds) {
        String text = message == null || message.isEmpty() ? reason(status) : message;
        StringBuilder b = new StringBuilder(96).append("{\"error\":{\"code\":");
        quote(b, code).append(",\"message\":");
        quote(b, text).append(",\"requestId\":");
        quote(b, requestId(req)).append("}}");
        res.status(status);
        if (retryAfterSeconds != null) {
            res.header("Retry-After", String.valueOf(retryAfterSeconds));
        }
        res.type("application/json; charset=utf-8").send(b.toString());
    }

    private static String requestId(Request req) {
        try {
            return req.requestId();
        } catch (UnsupportedOperationException noId) {
            return "";
        }
    }

    /** The reason phrase of the statuses auth answers; "Error" for any other. */
    // @req IR-AUTH-003
    public static String reason(int status) {
        switch (status) {
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 415: return "Unsupported Media Type";
            case 422: return "Unprocessable Content";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return status >= 400 && status < 500 ? "Client Error" : "Error";
        }
    }

    private static StringBuilder quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"');
    }
}
