package com.snoworca.naruserve.proxy;

import com.snoworca.naruserve.common.HttpHeaderValues;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The backend response head parser of the proxy's HTTP/1.1 client (SEC-PROXY-004). The status line and header lines
 * are read byte by byte as ISO-8859-1, one char per byte; interim 1xx heads other than 101 are skipped, and the body
 * framing of the final head follows RFC 9112 §6.3, with HEAD, 204 and 304 bodyless before any framing header is
 * looked at (FR-PROXY-014). Every malformed head is a {@link ProxyException} of kind
 * {@link ProxyException.Kind#INVALID_RESPONSE}.
 */
// @req SEC-PROXY-004
final class ResponseParser {

    /**
     * The most bytes the status lines and header lines may take, line terminators included and the blank line that
     * ends each head excluded. Interim heads count toward the same bound as the final head, so a backend cannot keep
     * the proxy reading interim heads without end.
     */
    static final int MAX_HEAD_BYTES = 64 * 1024;

    private ResponseParser() { }

    /** A parse failure: the backend sent something that is not a valid HTTP/1.1 response. */
    static final class Malformed extends IOException {
        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message);
        }
    }

    /** One final response head: its status, its header lines and its framing header values. */
    static final class Head {
        final int status;
        /** Whether the status line is {@code HTTP/1.0}; such a response cannot carry Transfer-Encoding. */
        final boolean http10;
        /** The header lines in received order, duplicates kept: the name as received, the value without OWS. */
        final List<Map.Entry<String, String>> headers;
        /** The values of every {@code Content-Length} line, in received order. */
        final List<String> contentLengths;
        /** The values of every {@code Transfer-Encoding} line, in received order. */
        final List<String> transferEncodings;

        Head(int status, boolean http10, List<Map.Entry<String, String>> headers, List<String> contentLengths,
                List<String> transferEncodings) {
            this.status = status;
            this.http10 = http10;
            this.headers = Collections.unmodifiableList(headers);
            this.contentLengths = Collections.unmodifiableList(contentLengths);
            this.transferEncodings = Collections.unmodifiableList(transferEncodings);
        }
    }

    /** How the body of a final response is read and the length handed to {@code res.relay}. */
    static final class Framing {
        /** Whether the response has no body to read (a response to HEAD, 204 or 304). */
        final boolean bodyless;
        /** Whether the body is in the chunked coding. */
        final boolean chunked;
        /** The length for {@code res.relay}: a valid Content-Length, or -1 for an unknown length. */
        final long relayLength;

        private Framing(boolean bodyless, boolean chunked, long relayLength) {
            this.bodyless = bodyless;
            this.chunked = chunked;
            this.relayLength = relayLength;
        }
    }

    /**
     * Reads interim heads and returns the first final head. A malformed head, a 101, a head past
     * {@link #MAX_HEAD_BYTES} and a connection closed or reset before the head is complete throw a
     * {@link ProxyException} of kind {@code INVALID_RESPONSE}; a read timeout passes through as the
     * {@link SocketTimeoutException} itself.
     */
    // @req SEC-PROXY-004
    static Head read(InputStream in) throws IOException {
        try {
            LineReader lines = new LineReader(in);
            int remaining = MAX_HEAD_BYTES;
            while (true) {
                StatusLine statusLine = statusLine(lines.read(remaining));
                remaining = charge(remaining, lines);
                int status = statusLine.status;
                if (status == 101) {
                    // The proxy relays no protocol switch: a WebSocket upgrade never reaches the backend
                    // (FR-PROXY-009).
                    throw new Malformed("101 Switching Protocols");
                }
                List<Map.Entry<String, String>> headers = new ArrayList<>();
                for (String line; !(line = lines.read(remaining)).isEmpty(); ) {
                    remaining = charge(remaining, lines);
                    headers.add(headerLine(line));
                }
                if (status >= 200) {
                    return new Head(status, statusLine.http10, headers,
                            FramingLists.fieldValues(headers, FramingLists.CONTENT_LENGTH),
                            FramingLists.fieldValues(headers, FramingLists.TRANSFER_ENCODING));
                }
            }
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            // Malformed bytes, the end of the stream and a reset all leave the head incomplete (SEC-PROXY-004 AC-8).
            throw new ProxyException(ProxyException.Kind.INVALID_RESPONSE, e);
        }
    }

    /**
     * The framing of {@code head}'s body in the RFC 9112 §6.3 order: a response to a HEAD request, a 204 and a 304
     * are bodyless whatever their framing headers say, the length for {@code res.relay} being -1 for a 204 and the
     * agreed Content-Length or -1 otherwise (FR-PROXY-014); then {@code Transfer-Encoding}, which an HTTP/1.0 response
     * must not have at all (RFC 9112 §6.1) and which must otherwise be a single {@code chunked} without
     * {@code Content-Length}; then {@code Content-Length}, which must be one agreed length; then a body read until
     * the backend closes. The two lists are read as on the request side ({@link FramingLists}, SEC-PROXY-003).
     *
     * @throws ProxyException of kind {@code INVALID_RESPONSE} for framing headers a body-capable response cannot have
     */
    // @req SEC-PROXY-004
    static Framing framing(Head head, boolean headRequest) {
        if (headRequest || head.status == 204 || head.status == 304) {
            long length = head.status == 204 ? -1 : FramingLists.contentLength(head.contentLengths);
            return new Framing(true, false, length);
        }
        if (!head.transferEncodings.isEmpty()) {
            // RFC 9112 §6.1: an HTTP/1.0 message with Transfer-Encoding has faulty framing, Content-Length or not.
            // @req SEC-PROXY-004
            if (head.http10) {
                throw invalid("Transfer-Encoding on an HTTP/1.0 response");
            }
            if (!head.contentLengths.isEmpty()) {
                throw invalid("Content-Length with Transfer-Encoding");
            }
            if (FramingLists.transferEncoding(head.transferEncodings) != FramingLists.TransferCoding.CHUNKED) {
                throw invalid("a Transfer-Encoding other than a single chunked");
            }
            return new Framing(false, true, -1);
        }
        if (!head.contentLengths.isEmpty()) {
            long length = FramingLists.contentLength(head.contentLengths);
            if (length == FramingLists.INVALID_LENGTH) {
                throw invalid("a Content-Length that is not one agreed length");
            }
            return new Framing(false, false, length);
        }
        return new Framing(false, false, -1);
    }

    private static ProxyException invalid(String detail) {
        return new ProxyException(ProxyException.Kind.INVALID_RESPONSE, new Malformed(detail));
    }

    /** {@code remaining} less the bytes of the line just read; a head past the bound is malformed. */
    private static int charge(int remaining, LineReader lines) throws Malformed {
        int left = remaining - lines.consumed();
        if (left < 0) {
            throw new Malformed("the status line and headers exceed " + MAX_HEAD_BYTES + " bytes");
        }
        return left;
    }

    /** A parsed status line: its status code and whether its version is {@code HTTP/1.0}. */
    private static final class StatusLine {
        final int status;
        final boolean http10;

        StatusLine(int status, boolean http10) {
            this.status = status;
            this.http10 = http10;
        }
    }

    /**
     * The status line {@code line}: {@code HTTP/1.<digit>}, a space, exactly three digits within
     * {@code 100..599}, and then nothing or a space and a reason phrase of HTAB, SP, VCHAR and obs-text.
     */
    // @req SEC-PROXY-004
    private static StatusLine statusLine(String line) throws Malformed {
        if (line.length() < 12 || !line.startsWith("HTTP/1.") || !isDigit(line.charAt(7)) || line.charAt(8) != ' '
                || !isDigit(line.charAt(9)) || !isDigit(line.charAt(10)) || !isDigit(line.charAt(11))
                || (line.length() > 12 && line.charAt(12) != ' ')) {
            throw new Malformed("malformed status line");
        }
        int status = (line.charAt(9) - '0') * 100 + (line.charAt(10) - '0') * 10 + (line.charAt(11) - '0');
        if (status < 100 || status > 599) {
            throw new Malformed("status outside 100..599");
        }
        // Every char is one byte (LineReader), so isFieldValue rejects exactly the controls other than HTAB.
        if (line.length() > 13 && !HttpHeaderValues.isFieldValue(line.substring(13))) {
            throw new Malformed("control character in the reason phrase");
        }
        // The minor version is the digit after "HTTP/1.", checked above.
        return new StatusLine(status, line.charAt(7) == '0');
    }

    /**
     * One header line as (name, value): an obs-fold line (starting with SP or HTAB), a name that is not a token and
     * a value that is not a field value are malformed.
     */
    // @req SEC-PROXY-004
    private static Map.Entry<String, String> headerLine(String line) throws Malformed {
        char first = line.charAt(0);
        if (first == ' ' || first == '\t') {
            throw new Malformed("obs-fold line");
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            throw new Malformed("header line without a name");
        }
        String name = line.substring(0, colon);
        String value = HeaderRules.trimOws(line.substring(colon + 1));
        if (!HttpHeaderValues.isToken(name) || !HttpHeaderValues.isFieldValue(value)) {
            throw new Malformed("malformed header line");
        }
        return new SimpleImmutableEntry<>(name, value);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Reads lines ending in LF, a CR before the LF dropped (RFC 9112 §2.2), each byte one ISO-8859-1 char. The end of
     * the stream inside a line and a line longer than the caller's bound are {@link Malformed}.
     */
    // @req SEC-PROXY-004
    static final class LineReader {
        private final InputStream in;
        private final StringBuilder line = new StringBuilder();
        /** The bytes the last line read took, its terminator included. */
        private int consumed;

        LineReader(InputStream in) {
            this.in = in;
        }

        /** The next line without its terminator; it may hold at most {@code maxContent} bytes before the terminator. */
        // @req SEC-PROXY-004
        String read(int maxContent) throws IOException {
            line.setLength(0);
            int terminator = 1;
            for (int b; (b = in.read()) != '\n'; ) {
                if (b < 0) {
                    throw new Malformed("the backend closed inside a line");
                }
                // A CR may be the first byte of the terminator, so the bound allows it one byte of slack.
                if (line.length() > maxContent) {
                    throw new Malformed("line longer than " + maxContent + " bytes");
                }
                line.append((char) b);
            }
            int end = line.length();
            if (end > 0 && line.charAt(end - 1) == '\r') {
                end--;
                terminator = 2;
            }
            if (end > maxContent) {
                throw new Malformed("line longer than " + maxContent + " bytes");
            }
            consumed = end + terminator;
            return line.substring(0, end);
        }

        /** The bytes the last line read took, its terminator included. */
        int consumed() {
            return consumed;
        }
    }
}
