package com.snoworca.naruserve.internal;

import java.nio.charset.StandardCharsets;

/** Standard error table; public because {@code HttpException.code()} reads it. */
// shortcut: no statusOf/messageOf yet; the FR-APP-006 SDS adds them and the full reason table.
// @req FR-APP-006
public final class StandardErrors {

    private StandardErrors() {
    }

    /** The reason phrase of {@code status}, {@code Error} when unlisted. */
    // @req FR-APP-006
    static String reason(int status) {
        switch (status) {
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 422: return "Unprocessable Entity";
            case 426: return "Upgrade Required";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
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
