package com.snoworca.naruserve.proxy;

import java.util.List;

/**
 * Reads the {@code Content-Length} and {@code Transfer-Encoding} field lines of one message as comma-joined lists
 * (design D-054, D-073): elements are split on commas and trimmed of optional whitespace. The request framing checks
 * (SEC-PROXY-003) and the backend response parser ({@link ResponseParser#framing}, SEC-PROXY-004) share this reading.
 * What an invalid list answers is the caller's choice: 400 or 501 for a request, {@code INVALID_RESPONSE} for a
 * response.
 */
// @req SEC-PROXY-003
// @req SEC-PROXY-004
final class FramingLists {

    /** What {@link #contentLength} returns for a list that is not one agreed length. */
    static final long INVALID_LENGTH = -1;

    /** How a {@code Transfer-Encoding} list frames the body. */
    enum TransferCoding {
        /** {@code chunked} is the only coding and appears once: the body is chunked. */
        CHUNKED,
        /** The last coding is not {@code chunked}, or {@code chunked} appears more than once. */
        INVALID,
        /** One {@code chunked} last, preceded by another coding this proxy does not decode. */
        UNSUPPORTED
    }

    private FramingLists() { }

    /**
     * The length every element of the {@code Content-Length} field values agrees on, or {@link #INVALID_LENGTH} when
     * there is no element, an element is not ASCII digits that fit a {@code long}, or two elements differ in value.
     */
    // @req SEC-PROXY-003
    static long contentLength(List<String> fieldValues) {
        long agreed = INVALID_LENGTH;
        for (String value : fieldValues) {
            for (String element : value.split(",", -1)) {
                long n = digits(HeaderRules.trimOws(element));
                if (n < 0 || (agreed >= 0 && n != agreed)) {
                    return INVALID_LENGTH;
                }
                agreed = n;
            }
        }
        return agreed;
    }

    /**
     * The framing of the {@code Transfer-Encoding} field values. Empty elements are ignored and coding names compare
     * case-insensitively; a coding with parameters ({@code chunked;x=1}) and an empty overall value count as a coding
     * other than {@code chunked}.
     */
    // @req SEC-PROXY-003
    static TransferCoding transferEncoding(List<String> fieldValues) {
        int codings = 0;
        int chunked = 0;
        boolean lastChunked = false;
        for (String value : fieldValues) {
            for (String element : value.split(",", -1)) {
                String coding = HeaderRules.trimOws(element);
                if (coding.isEmpty()) {
                    continue;
                }
                codings++;
                lastChunked = coding.equalsIgnoreCase("chunked");
                if (lastChunked) {
                    chunked++;
                }
            }
        }
        if (!lastChunked || chunked > 1) {
            // An empty overall value has no coding at all, so its last coding is not chunked either.
            return TransferCoding.INVALID;
        }
        return codings > 1 ? TransferCoding.UNSUPPORTED : TransferCoding.CHUNKED;
    }

    /** {@code s} as a non-negative decimal of ASCII digits, or -1 when it is empty, holds another char or overflows. */
    // @req SEC-PROXY-003
    private static long digits(String s) {
        if (s.isEmpty()) {
            return -1;
        }
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            int d = c - '0';
            if (n > (Long.MAX_VALUE - d) / 10) {
                return -1;
            }
            n = n * 10 + d;
        }
        return n;
    }
}
