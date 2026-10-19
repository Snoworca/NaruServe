package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.spi.ErrorRenderer;
import java.nio.charset.StandardCharsets;

/** Standard error table and body, built by the core without the app's codec; public for {@code HttpException.code()}. */
// @req FR-APP-006
public final class StandardErrors {

    /** The Content-Type of every standard error body. */
    static final String JSON_TYPE = "application/json; charset=UTF-8";

    private StandardErrors() {
    }

    /** {@link HttpException#status()}, otherwise 500. */
    // @req FR-APP-006
    static int statusOf(Throwable error) {
        return error instanceof HttpException ? ((HttpException) error).status() : 500;
    }

    /** The app-supplied message of an {@link HttpException}, otherwise the reason phrase; a 500 always the reason. */
    // @req FR-APP-006
    static String messageOf(Throwable error, int status) {
        String message = error instanceof HttpException ? error.getMessage() : null;
        return status == 500 || message == null ? reason(status) : message;
    }

    /** The reason phrase of {@code status}, {@code Error} when unlisted. */
    // @req FR-APP-006
    // @req FR-ENGINE-005
    static String reason(int status) {
        switch (status) {
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 421: return "Misdirected Request";
            case 422: return "Unprocessable Entity";
            case 426: return "Upgrade Required";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    /** The renderer adapters get: the standard body of a status with its reason and a fresh request id. */
    // @req IR-ENGINE-002
    // @req FR-ENGINE-005
    static ErrorRenderer renderer() {
        return status -> new String(json(status, reason(status), RequestIds.next()), StandardCharsets.UTF_8);
    }

    /** UTF-8 {@code {"error":{"code":...,"message":...,"requestId":...}}}, built without the app's codec. */
    // @req FR-APP-006
    static byte[] json(int status, String message, String requestId) {
        return ("{\"error\":{\"code\":\"" + escape(code(status)) + "\",\"message\":\"" + escape(message)
                + "\",\"requestId\":\"" + escape(requestId) + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    /** RFC 8259 string escape: quote, backslash and U+0000..U+001F. */
    // @req FR-APP-006
    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    /** The standard code of {@code status}, {@code HTTP_<status>} when unlisted. */
    // @req FR-APP-006
    public static String code(int status) {
        switch (status) {
            case 400: return "BAD_REQUEST";
            case 404: return "NOT_FOUND";
            case 405: return "METHOD_NOT_ALLOWED";
            case 413: return "PAYLOAD_TOO_LARGE";
            case 415: return "UNSUPPORTED_MEDIA_TYPE";
            case 422: return "UNPROCESSABLE_ENTITY";
            case 426: return "UPGRADE_REQUIRED";
            case 500: return "INTERNAL_ERROR";
            case 501: return "NOT_IMPLEMENTED";
            case 503: return "SERVICE_UNAVAILABLE";
            default: return "HTTP_" + status;
        }
    }
}
