package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.CachePolicy;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code options.cache} pattern matching against the mount-relative path, which begins with '/'. A pattern without a
 * leading '/' matches as {@code "**}{@code /" + pattern}; {@code *} stays within one segment, {@code **}{@code /}
 * spans zero or more segments and a trailing {@code **} anything; the whole path must match.
 */
// @req FR-STATIC-008
final class CachePattern {

    private CachePattern() {
    }

    /** The policy of the first registered pattern matching {@code relative}, or null. */
    // @req FR-STATIC-008
    static @Nullable CachePolicy select(List<Map.Entry<String, CachePolicy>> patterns, String relative) {
        for (Map.Entry<String, CachePolicy> e : patterns) {
            // shortcut: compiled per request; precompile per mount in StaticMount.freeze if it shows in profiles
            if (compile(e.getKey()).matcher(relative).matches()) {
                return e.getValue();
            }
        }
        return null;
    }

    // @req FR-STATIC-008
    static Pattern compile(String pattern) {
        String p = pattern.startsWith("/") ? pattern : pattern.startsWith("**/") ? "/" + pattern : "/**/" + pattern;
        StringBuilder re = new StringBuilder();
        int i = 0;
        while (i < p.length()) {
            if (p.startsWith("**/", i)) {
                re.append("(?:[^/]*/)*");
                i += 3;
            } else if (p.startsWith("**", i)) {
                re.append(".*");
                i += 2;
            } else if (p.charAt(i) == '*') {
                re.append("[^/]*");
                i++;
            } else {
                int j = i;
                while (j < p.length() && p.charAt(j) != '*') {
                    j++;
                }
                re.append(Pattern.quote(p.substring(i, j)));
                i = j;
            }
        }
        return Pattern.compile(re.toString());
    }
}
