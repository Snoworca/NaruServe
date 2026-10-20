package com.snoworca.naruserve.common;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Validates JSON media types and token/quoted-string parameters before a body reader consumes input. */
// @req FR-JSON-001
public final class MediaTypes {
    private MediaTypes() { }

    /** True for application/json or application/subtype+json with absent or UTF-8 charset parameters. */
    public static boolean isJsonUtf8(@Nullable String value) {
        if (value == null) return false;
        int semi = value.indexOf(';');
        int typeEnd = semi < 0 ? value.length() : semi;
        String media = owsTrim(value.substring(0, typeEnd));
        int slash = media.indexOf('/');
        if (slash < 0 || !HttpHeaderValues.isToken(media.substring(0, slash))
                || !HttpHeaderValues.isToken(media.substring(slash + 1))) return false;
        String type = media.substring(0, slash);
        String subtype = media.substring(slash + 1).toLowerCase(Locale.ROOT);
        if (!type.equalsIgnoreCase("application") || !(subtype.equals("json")
                || subtype.length() > "+json".length() && subtype.endsWith("+json"))) return false;
        int offset = typeEnd;
        while (offset < value.length()) {
            if (value.charAt(offset++) != ';') return false;
            offset = skipOws(value, offset);
            // HTTP parameter lists permit empty elements, including a trailing semicolon.
            if (offset == value.length() || value.charAt(offset) == ';') continue;
            int start = offset;
            while (offset < value.length() && value.charAt(offset) != '=' && value.charAt(offset) != ';') offset++;
            String name = owsTrim(value.substring(start, offset));
            if (!HttpHeaderValues.isToken(name) || offset == value.length() || value.charAt(offset++) != '=') return false;
            offset = skipOws(value, offset);
            String parameter;
            if (offset < value.length() && value.charAt(offset) == '"') {
                StringBuilder decoded = new StringBuilder();
                offset++;
                boolean closed = false;
                while (offset < value.length()) {
                    char c = value.charAt(offset++);
                    if (c == '"') { closed = true; break; }
                    if (c == '\\') {
                        if (offset == value.length()) return false;
                        c = value.charAt(offset++);
                    }
                    if (!quotedCharacter(c)) return false;
                    decoded.append(c);
                }
                if (!closed) return false;
                parameter = decoded.toString();
                offset = skipOws(value, offset);
                if (offset < value.length() && value.charAt(offset) != ';') return false;
            } else {
                start = offset;
                while (offset < value.length() && value.charAt(offset) != ';') offset++;
                parameter = owsTrim(value.substring(start, offset));
                if (!HttpHeaderValues.isToken(parameter)) return false;
            }
            if (name.equalsIgnoreCase("charset") && !parameter.equalsIgnoreCase("utf-8")) return false;
        }
        return true;
    }

    private static boolean quotedCharacter(char c) {
        return c == '\t' || c >= 0x20 && c != 0x7F && c <= 0xFF;
    }

    private static int skipOws(String value, int offset) {
        while (offset < value.length() && (value.charAt(offset) == ' ' || value.charAt(offset) == '\t')) offset++;
        return offset;
    }

    private static String owsTrim(String value) {
        int start = skipOws(value, 0);
        int end = value.length();
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) end--;
        return value.substring(start, end);
    }
}
