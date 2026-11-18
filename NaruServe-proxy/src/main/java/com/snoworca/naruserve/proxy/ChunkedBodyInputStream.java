package com.snoworca.naruserve.proxy;

import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;

/**
 * A chunked backend response body decoded to its data (SEC-PROXY-004): chunk extensions are ignored and the trailer
 * section after the last chunk is read and dropped, so it never reaches the client. A chunk-size line longer than
 * {@link #MAX_CHUNK_SIZE_LINE} bytes with its extensions, a chunk size that is not hexadecimal, chunk data not
 * followed by CRLF, a trailer section past {@link #MAX_TRAILER_BYTES} and the end of the stream before the last chunk
 * are malformed: {@code read} throws an {@link IOException} whose cause is a {@link ProxyException} of kind
 * {@code INVALID_RESPONSE}, and keeps throwing it. A read failure of the backend stream itself, a timeout for example,
 * passes through as it is. Closing this stream closes the backend stream.
 */
// @req SEC-PROXY-004
final class ChunkedBodyInputStream extends InputStream {

    /** The longest chunk-size line, chunk extensions included and its terminator excluded. */
    static final int MAX_CHUNK_SIZE_LINE = 4 * 1024;
    /** The most bytes the trailer section may take, line terminators included and its closing blank line excluded. */
    static final int MAX_TRAILER_BYTES = 64 * 1024;
    /** At most 15 significant hex digits, so a chunk size always fits a non-negative long. */
    private static final int MAX_SIZE_DIGITS = 15;

    private final InputStream in;
    private final ResponseParser.LineReader lines;
    /** Data bytes left in the current chunk. */
    private long remaining;
    private boolean started;
    private boolean done;
    /** The failure of a malformed body, thrown again by every later read. */
    private @Nullable IOException failure;

    // @req SEC-PROXY-004
    ChunkedBodyInputStream(InputStream in) {
        this.in = in;
        this.lines = new ResponseParser.LineReader(in);
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    // @req SEC-PROXY-004
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (failure != null) {
            throw failure;
        }
        if (len == 0) {
            return 0;
        }
        try {
            if (remaining == 0 && !nextChunk()) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) {
                throw new ResponseParser.Malformed("the backend closed inside a chunk");
            }
            remaining -= n;
            return n;
        } catch (ResponseParser.Malformed e) {
            failure = new IOException(e.getMessage(), new ProxyException(ProxyException.Kind.INVALID_RESPONSE, e));
            throw failure;
        }
    }

    /** Reads the next chunk-size line; false after the last chunk, whose trailer section is read and dropped. */
    // @req SEC-PROXY-004
    private boolean nextChunk() throws IOException {
        if (done) {
            return false;
        }
        if (started && !lines.read(0).isEmpty()) {
            throw new ResponseParser.Malformed("chunk data not followed by CRLF");
        }
        started = true;
        long size = chunkSize(lines.read(MAX_CHUNK_SIZE_LINE));
        if (size > 0) {
            remaining = size;
            return true;
        }
        int left = MAX_TRAILER_BYTES;
        while (!lines.read(left).isEmpty()) {
            left -= lines.consumed;
            if (left < 0) {
                throw new ResponseParser.Malformed("trailer section larger than " + MAX_TRAILER_BYTES + " bytes");
            }
        }
        done = true;
        return false;
    }

    /**
     * The size of a chunk-size line: one or more hex digits at the start of the line, then nothing or optional
     * spaces and tabs and a chunk extension after {@code ;}; the line holds no control character but HTAB.
     */
    // @req SEC-PROXY-004
    private static long chunkSize(String line) throws ResponseParser.Malformed {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7F) {
                throw new ResponseParser.Malformed("control character in a chunk-size line");
            }
        }
        int end = 0;
        long size = 0;
        int significant = 0;
        for (int d; end < line.length() && (d = hexValue(line.charAt(end))) >= 0; end++) {
            if (significant > 0 || d > 0) {
                significant++; // leading zeros do not count toward the digit limit
            }
            if (significant > MAX_SIZE_DIGITS) {
                throw new ResponseParser.Malformed("chunk size too large");
            }
            size = size * 16 + d;
        }
        if (end == 0) {
            throw new ResponseParser.Malformed("chunk size that is not hexadecimal");
        }
        String rest = HeaderRules.trimOws(line.substring(end));
        if (!rest.isEmpty() && rest.charAt(0) != ';') {
            throw new ResponseParser.Malformed("chunk size that is not hexadecimal");
        }
        return size;
    }

    /** The value of the ASCII hex digit {@code c}, or -1. */
    private static int hexValue(char c) {
        return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10
                : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
