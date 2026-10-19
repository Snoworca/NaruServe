package com.snoworca.naruserve.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Static path checks on the mount-relative raw path (the request path after the prefix and its '/'). Each segment is
 * decoded exactly once; a segment that would still decode to a dot segment, '/' or NUL is refused rather than decoded
 * again.
 */
// @req FR-STATIC-005
final class StaticPath {

    private StaticPath() {
    }

    /**
     * 0 when the path may be looked up; 400 for a segment that decodes to {@code .} or {@code ..}, holds '/' or NUL,
     * still holds {@code %2e}, {@code %2f} or {@code %00}, or is malformed; then 404 for a decoded segment starting
     * with '.' or an empty segment other than the trailing one.
     */
    // @req FR-STATIC-005
    static int check(String rawRelative) {
        String[] raw = rawRelative.split("/", -1);
        String[] decoded = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            String d = PercentDecoder.decode(raw[i], false);
            if (d == null || d.equals(".") || d.equals("..") || d.indexOf('/') >= 0 || d.indexOf('\0') >= 0) {
                return 400;
            }
            String lower = d.toLowerCase(Locale.ROOT);
            if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%00")) {
                return 400;
            }
            decoded[i] = d;
        }
        for (int i = 0; i < decoded.length; i++) {
            boolean trailing = i == decoded.length - 1;
            if (decoded[i].startsWith(".") || decoded[i].isEmpty() && !trailing) {
                return 404;
            }
        }
        return 0;
    }

    /** The decoded segments of a path that passed {@link #check}, without the trailing empty one. */
    // @req FR-STATIC-005
    static List<String> segments(String rawRelative) {
        List<String> out = new ArrayList<>();
        for (String s : rawRelative.split("/", -1)) {
            if (!s.isEmpty()) {
                out.add(Objects.requireNonNull(PercentDecoder.decode(s, false)));
            }
        }
        return out;
    }
}
