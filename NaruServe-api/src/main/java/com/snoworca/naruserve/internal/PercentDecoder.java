package com.snoworca.naruserve.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/** The one strict UTF-8 percent decoder of the core, shared by the path validator and the request. */
// @req FR-ROUTE-005
// @req FR-HTTP-009
// @req FR-HTTP-010
public final class PercentDecoder {

    private PercentDecoder() {
    }

    /**
     * One UTF-8 percent-decoding pass; '+' becomes a space when {@code plusAsSpace} (query form) and stays '+'
     * otherwise (path form). Null for malformed {@code %XX} or percent bytes that are not valid UTF-8.
     */
    // @req FR-ROUTE-005
    // @req FR-HTTP-009
    // @req FR-HTTP-010
    public static @Nullable String decode(String s, boolean plusAsSpace) {
        if (s.indexOf('%') < 0) {
            return plusAsSpace ? s.replace('+', ' ') : s;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%') {
                int hi = i + 2 < s.length() ? hex(s.charAt(i + 1)) : -1;
                int lo = hi < 0 ? -1 : hex(s.charAt(i + 2));
                if (lo < 0) {
                    return null;
                }
                bytes.write(hi * 16 + lo);
                i += 3;
            } else {
                int next = s.indexOf('%', i);
                int end = next < 0 ? s.length() : next;
                String run = s.substring(i, end);
                byte[] b = (plusAsSpace ? run.replace('+', ' ') : run).getBytes(StandardCharsets.UTF_8);
                bytes.write(b, 0, b.length);
                i = end;
            }
        }
        try {
            return strictUtf8(bytes.toByteArray());
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** {@code bytes} as UTF-8, malformed or unmappable input reported rather than replaced. */
    // @req FR-HTTP-010
    // @req FR-MULTIPART-002
    // @req FR-MULTIPART-003
    static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** UTF-8 bytes of {@code s} as upper-case {@code %XX}, except ASCII letters, digits and {@code safeExtra}. */
    // @req FR-STATIC-003
    // @req FR-STATIC-011
    static String percentEncode(String s, String safeExtra) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xFF;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || safeExtra.indexOf(c) >= 0) {
                b.append((char) c);
            } else {
                b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return b.toString();
    }

    /** ASCII hex digit value, or -1; Character.digit would also take non-ASCII digits. */
    private static int hex(char c) {
        return c < 0x80 ? Character.digit(c, 16) : -1;
    }
}
