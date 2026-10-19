package com.snoworca.naruserve.internal;

/** Standard error table; public because {@code HttpException.code()} reads it. */
// shortcut: only the code table; the FR-APP-006 SDS adds reason phrases and the JSON body.
// @req FR-APP-006
public final class StandardErrors {

    private StandardErrors() {
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
