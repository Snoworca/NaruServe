package com.snoworca.naruserve.utils.auth.internal;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The strict JSON reader and writer of auth: JWT headers and claims (the {@link Limits#claims()} profile) and the auth
 * files (the {@link Limits#file()} profile) are read only here, never through Gson or the app's JsonCodec. The limits
 * are checked while scanning, before a value is built, so a hostile deep or long input stops at the limit. Both
 * profiles refuse duplicate keys, {@code NaN}/{@code Infinity}, invalid escapes, unescaped control characters and any
 * non-whitespace after the value. Objects read as insertion-ordered {@code Map<String, Object>}, arrays as
 * {@code List<Object>}, integers that fit as {@link Long} (exact, unlike Gson's Double), other numbers as
 * {@link Double}, plus {@link String}, {@link Boolean} and null.
 */
// @req SEC-TOKEN-002
public final class MiniJson {

    /** The nesting, total-size and string-size limits of one profile; sizes are UTF-8 bytes (1 KB = 1,024). */
    public static final class Limits {
        final int maxDepth;
        final long maxTotalBytes;
        final int maxStringBytes;
        final String name;

        private Limits(String name, int maxDepth, long maxTotalBytes, int maxStringBytes) {
            this.name = name;
            this.maxDepth = maxDepth;
            this.maxTotalBytes = maxTotalBytes;
            this.maxStringBytes = maxStringBytes;
        }

        /** CLAIMS: depth 2, 8 KB in total, strings of 1 KB, for the JWTs auth issues. */
        // @req SEC-TOKEN-002
        public static Limits claims() {
            return new Limits("CLAIMS", 2, 8L * 1024, 1024);
        }

        /** FILE: depth 8, 8 MB in total, strings of 64 KB, for the auth files. */
        // @req SEC-TOKEN-002
        public static Limits file() {
            return file(DEFAULT_FILE_BYTES);
        }

        /** FILE with another total, as raised by the builder. */
        // @req SEC-TOKEN-002
        public static Limits file(long maxTotalBytes) {
            if (maxTotalBytes < 1) {
                throw new IllegalArgumentException("the auth file limit must be positive: " + maxTotalBytes);
            }
            return new Limits("FILE", 8, maxTotalBytes, 64 * 1024);
        }

        /** The total size limit in bytes. */
        public long maxTotalBytes() {
            return maxTotalBytes;
        }

        @Override
        public String toString() {
            return name + "{depth " + maxDepth + ", total " + maxTotalBytes + " bytes, string " + maxStringBytes
                    + " bytes}";
        }
    }

    /** The default total of the FILE profile: 8 MB. */
    public static final long DEFAULT_FILE_BYTES = 8L * 1024 * 1024;

    /** Input that is not strict JSON or breaks a limit; the message names the problem, never the input. */
    public static final class JsonFormatException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        JsonFormatException(String message) {
            super(message);
        }
    }

    private final String in;
    private final Limits limits;
    private int pos;

    private MiniJson(String in, Limits limits) {
        this.in = in;
        this.limits = limits;
    }

    /** The value of the UTF-8 document {@code utf8}; the total size is checked before decoding. */
    // @req SEC-TOKEN-002
    public static @Nullable Object parse(byte[] utf8, Limits limits) {
        if (utf8.length > limits.maxTotalBytes) {
            throw new JsonFormatException("the document has " + utf8.length + " bytes; the " + limits.name
                    + " limit is " + limits.maxTotalBytes);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException e) {
            throw new JsonFormatException("the document is not valid UTF-8");
        }
        return new MiniJson(text, limits).document();
    }

    /** The value of {@code text}; its UTF-8 size is checked before scanning. */
    // @req SEC-TOKEN-002
    public static @Nullable Object parse(String text, Limits limits) {
        long bytes = utf8Length(text, limits.maxTotalBytes);
        if (bytes > limits.maxTotalBytes) {
            throw new JsonFormatException("the document has more than " + limits.maxTotalBytes + " bytes; the "
                    + limits.name + " limit is " + limits.maxTotalBytes);
        }
        return new MiniJson(text, limits).document();
    }

    /** The UTF-8 length of {@code s}, counting stops just past {@code cap}. */
    private static long utf8Length(String s, long cap) {
        long n = 0;
        for (int i = 0; i < s.length() && n <= cap; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else {
                n += 3;
            }
        }
        return n;
    }

    private @Nullable Object document() {
        ws();
        Object v = value(0);
        ws();
        if (pos != in.length()) {
            throw error("unexpected character after the value");
        }
        return v;
    }

    /** The value of an ASCII hex digit, -1 for anything else (Character.digit also takes fullwidth digits). */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private JsonFormatException error(String what) {
        return new JsonFormatException("invalid JSON at offset " + pos + ": " + what);
    }

    private void ws() {
        while (pos < in.length()) {
            char c = in.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private @Nullable Object value(int depth) {
        if (pos >= in.length()) {
            throw error("unexpected end");
        }
        char c = in.charAt(pos);
        switch (c) {
            case '{':
                return object(depth + 1);
            case '[':
                return array(depth + 1);
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected character");
        }
    }

    private void depth(int depth) {
        if (depth > limits.maxDepth) {
            throw new JsonFormatException("the document nests deeper than " + limits.maxDepth + " levels (the "
                    + limits.name + " depth limit)");
        }
    }

    private Map<String, Object> object(int depth) {
        depth(depth);
        pos++;
        Map<String, Object> out = new LinkedHashMap<>();
        ws();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw error("expected a member name");
            }
            String key = string();
            if (out.containsKey(key)) {
                throw error("duplicate member name");
            }
            ws();
            if (peek() != ':') {
                throw error("expected ':'");
            }
            pos++;
            ws();
            out.put(key, value(depth));
            ws();
            char c = peek();
            pos++;
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                pos--;
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> array(int depth) {
        depth(depth);
        pos++;
        List<Object> out = new ArrayList<>();
        ws();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            ws();
            out.add(value(depth));
            ws();
            char c = peek();
            pos++;
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                pos--;
                throw error("expected ',' or ']'");
            }
        }
    }

    private char peek() {
        if (pos >= in.length()) {
            throw error("unexpected end");
        }
        return in.charAt(pos);
    }

    private void literal(String word) {
        if (!in.startsWith(word, pos)) {
            throw error("unexpected literal");
        }
        pos += word.length();
    }

    private String string() {
        pos++;
        StringBuilder b = new StringBuilder();
        long bytes = 0;
        while (true) {
            if (pos >= in.length()) {
                throw error("unterminated string");
            }
            char c = in.charAt(pos++);
            if (c == '"') {
                for (int i = 0; i < b.length(); i++) {
                    char ch = b.charAt(i);
                    if (Character.isHighSurrogate(ch) && i + 1 < b.length() && Character.isLowSurrogate(b.charAt(i + 1))) {
                        i++;
                    } else if (Character.isSurrogate(ch)) {
                        throw error("unpaired surrogate in a string");
                    }
                }
                return b.toString();
            }
            if (c < 0x20) {
                throw error("unescaped control character in a string");
            }
            if (c == '\\') {
                if (pos >= in.length()) {
                    throw error("unterminated escape");
                }
                char e = in.charAt(pos++);
                switch (e) {
                    case '"': c = '"'; break;
                    case '\\': c = '\\'; break;
                    case '/': c = '/'; break;
                    case 'b': c = '\b'; break;
                    case 'f': c = '\f'; break;
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case 'u':
                        if (pos + 4 > in.length()) {
                            throw error("short \\u escape");
                        }
                        int v = 0;
                        for (int i = 0; i < 4; i++) {
                            int d = hexDigit(in.charAt(pos + i)); // ASCII HEXDIG only (RFC 8259)
                            if (d < 0) {
                                throw error("invalid \\u escape");
                            }
                            v = (v << 4) | d;
                        }
                        pos += 4;
                        c = (char) v;
                        break;
                    default:
                        pos--;
                        throw error("invalid escape");
                }
            }
            bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isSurrogate(c) ? 2 : 3;
            if (bytes > limits.maxStringBytes) {
                throw new JsonFormatException("a string is longer than " + limits.maxStringBytes + " bytes (the "
                        + limits.name + " string limit)");
            }
            b.append(c);
        }
    }

    private Object number() {
        int start = pos;
        if (in.charAt(pos) == '-') {
            pos++;
        }
        if (pos >= in.length()) {
            throw error("bad number");
        }
        if (in.charAt(pos) == '0') {
            pos++;
        } else if (in.charAt(pos) >= '1' && in.charAt(pos) <= '9') {
            digits();
        } else {
            throw error("bad number");
        }
        boolean integral = true;
        if (pos < in.length() && in.charAt(pos) == '.') {
            integral = false;
            pos++;
            if (digits() == 0) {
                throw error("bad fraction");
            }
        }
        if (pos < in.length() && (in.charAt(pos) == 'e' || in.charAt(pos) == 'E')) {
            integral = false;
            pos++;
            if (pos < in.length() && (in.charAt(pos) == '+' || in.charAt(pos) == '-')) {
                pos++;
            }
            if (digits() == 0) {
                throw error("bad exponent");
            }
        }
        String text = in.substring(start, pos);
        int digitCount = text.length() - (text.charAt(0) == '-' ? 1 : 0);
        if (integral && digitCount <= 19) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException tooLarge) {
                // falls through to a double
            }
        }
        double d = Double.parseDouble(text);
        if (Double.isInfinite(d) || Double.isNaN(d)) {
            throw error("number out of range");
        }
        return d;
    }

    private int digits() {
        int n = 0;
        while (pos < in.length() && in.charAt(pos) >= '0' && in.charAt(pos) <= '9') {
            pos++;
            n++;
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------- writer

    /**
     * {@code value} as compact JSON: maps with String keys (in their iteration order), lists, strings, booleans, null
     * and finite numbers. Anything else is an {@link IllegalArgumentException}.
     */
    // @req SEC-TOKEN-002
    public static String write(@Nullable Object value) {
        return write(value, Limits.file(Long.MAX_VALUE));
    }

    /**
     * {@code value} as compact JSON within {@code limits}, so a file written here can always be read back with the
     * same limits: a deeper nesting, a longer string or a larger document is a {@link JsonFormatException} at write
     * time instead of a start that cannot read the file.
     */
    // @req SEC-TOKEN-002
    public static String write(@Nullable Object value, Limits limits) {
        StringBuilder b = new StringBuilder();
        write(b, value, 0, limits);
        String out = b.toString();
        if (utf8Length(out, limits.maxTotalBytes) > limits.maxTotalBytes) {
            throw new JsonFormatException("the written document is larger than " + limits.maxTotalBytes
                    + " bytes (the " + limits.name + " limit)");
        }
        return out;
    }

    private static void write(StringBuilder b, @Nullable Object v, int depth, Limits limits) {
        if ((v instanceof Map || v instanceof List) && depth + 1 > limits.maxDepth) {
            throw new JsonFormatException("the written value nests deeper than " + limits.maxDepth + " levels (the "
                    + limits.name + " depth limit)");
        }
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            checkString((String) v, limits);
            quote(b, (String) v);
        } else if (v instanceof Boolean) {
            b.append(v.toString());
        } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            b.append(((Number) v).longValue());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("JSON has no NaN or Infinity");
            }
            b.append(String.valueOf(d)); // keeps the fraction, so a double reads back as a double
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!(e.getKey() instanceof String)) {
                    throw new IllegalArgumentException("JSON member names are strings");
                }
                if (!first) {
                    b.append(',');
                }
                first = false;
                checkString((String) e.getKey(), limits);
                quote(b, (String) e.getKey());
                b.append(':');
                write(b, e.getValue(), depth + 1, limits);
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                write(b, o, depth + 1, limits);
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("cannot write a " + v.getClass().getName() + " as JSON");
        }
    }

    private static void checkString(String s, Limits limits) {
        if (utf8Length(s, limits.maxStringBytes) > limits.maxStringBytes) {
            throw new JsonFormatException("a written string is longer than " + limits.maxStringBytes + " bytes (the "
                    + limits.name + " string limit)");
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }
}
