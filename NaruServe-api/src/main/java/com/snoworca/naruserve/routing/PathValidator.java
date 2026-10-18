package com.snoworca.naruserve.routing;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Raw request path check run before the router lookup. It answers accept or reject only; it never returns a
 * decoded path. Text from the first {@code '?'} is ignored.
 */
// @req FR-ROUTE-004
public final class PathValidator {

    private PathValidator() {
    }

    /**
     * False for a raw char {@code <= 0x20}, {@code 0x7F}, {@code '#'} or above {@code 0x7F}; malformed
     * {@code %XX}; percent bytes that are not UTF-8; an encoded {@code '/'} or NUL; a {@code .} or {@code ..}
     * segment in raw or encoded form.
     */
    // @req FR-ROUTE-004
    public static boolean isValid(String rawPath) {
        String path = stripQuery(rawPath);
        // Only origin-form paths reach the router; "*", authority and absolute forms are the adapter's job.
        if (!path.startsWith("/")) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == '#') {
                return false;
            }
        }
        for (String raw : path.split("/", -1)) {
            String seg = decode(raw);
            if (seg == null || seg.indexOf('/') >= 0 || seg.indexOf('\0') >= 0 || seg.equals(".") || seg.equals("..")) {
                return false;
            }
        }
        return true;
    }

    static String stripQuery(String rawPath) {
        int q = rawPath.indexOf('?');
        return q < 0 ? rawPath : rawPath.substring(0, q);
    }

    /** One UTF-8 percent-decoding pass; '+' stays '+'. Null for malformed {@code %XX} or invalid UTF-8. */
    // @req FR-ROUTE-005
    static @Nullable String decode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(segment.length());
        int i = 0;
        while (i < segment.length()) {
            char c = segment.charAt(i);
            if (c == '%') {
                int hi = i + 2 < segment.length() ? hex(segment.charAt(i + 1)) : -1;
                int lo = hi < 0 ? -1 : hex(segment.charAt(i + 2));
                if (lo < 0) {
                    return null;
                }
                bytes.write(hi * 16 + lo);
                i += 3;
            } else {
                int next = segment.indexOf('%', i);
                int end = next < 0 ? segment.length() : next;
                byte[] run = segment.substring(i, end).getBytes(StandardCharsets.UTF_8);
                bytes.write(run, 0, run.length);
                i = end;
            }
        }
        return utf8(bytes.toByteArray());
    }

    /** ASCII hex digit value, or -1; Character.digit would also take non-ASCII digits. */
    private static int hex(char c) {
        return c < 0x80 ? Character.digit(c, 16) : -1;
    }

    private static @Nullable String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
