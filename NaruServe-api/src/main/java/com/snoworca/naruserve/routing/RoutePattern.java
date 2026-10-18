package com.snoworca.naruserve.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Parsed route pattern of literal and {@code :name} segments. A trailing {@code '/'} is kept as a final empty
 * literal segment; the root {@code /} is one empty literal segment.
 */
// @req FR-ROUTE-003
// @req FR-ROUTE-005
// @req FR-ROUTE-007
public final class RoutePattern {

    private final String source;
    /** Literal text, or the variable name for a variable segment. */
    private final String[] segments;
    private final boolean[] variable;

    private RoutePattern(String source, String[] segments, boolean[] variable) {
        this.source = source;
        this.segments = segments;
        this.variable = variable;
    }

    /** Throws {@link RoutingConfigException} naming the pattern when it is not valid route syntax. */
    // @req FR-ROUTE-003
    public static RoutePattern parse(String pattern) {
        if (!pattern.startsWith("/")) {
            throw error(pattern, "must start with '/'");
        }
        for (char c : new char[] {'?', '*', '+', '(', ')'}) {
            if (pattern.indexOf(c) >= 0) {
                throw error(pattern, "contains '" + c + "'; only literal and :name segments are supported");
            }
        }
        String[] parts = pattern.substring(1).split("/", -1);
        boolean[] variable = new boolean[parts.length];
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() && i < parts.length - 1) {
                throw error(pattern, "has an empty segment");
            }
            if (part.startsWith(":")) {
                String name = part.substring(1);
                if (name.isEmpty()) {
                    throw error(pattern, "has an empty variable name");
                }
                if (names.contains(name)) {
                    throw error(pattern, "repeats variable :" + name);
                }
                names.add(name);
                parts[i] = name;
                variable[i] = true;
            }
        }
        return new RoutePattern(pattern, parts, variable);
    }

    private static RoutingConfigException error(String pattern, String why) {
        return new RoutingConfigException("Invalid route pattern \"" + pattern + "\": " + why);
    }

    /** Segment count after the policy's trailing-slash rule; the root keeps its one segment. */
    private int effectiveLength(RouterPolicy policy) {
        int n = segments.length;
        boolean trailing = n > 1 && !variable[n - 1] && segments[n - 1].isEmpty();
        return policy.ignoreTrailingSlash() && trailing ? n - 1 : n;
    }

    /**
     * Unmodifiable parameters in pattern order when {@code decodedSegments} (from
     * {@link Router#decodeSegments}) match, else null. A variable never matches an empty segment.
     */
    // @req FR-ROUTE-005
    // @req FR-ROUTE-006
    public @Nullable Map<String, String> match(List<String> decodedSegments, RouterPolicy policy) {
        int n = effectiveLength(policy);
        if (decodedSegments.size() != n) {
            return null;
        }
        Map<String, String> params = null;
        for (int i = 0; i < n; i++) {
            String seg = decodedSegments.get(i);
            if (variable[i]) {
                if (seg.isEmpty()) {
                    return null;
                }
                if (params == null) {
                    params = new LinkedHashMap<>();
                }
                params.put(segments[i], seg);
            } else if (policy.caseInsensitive() ? !segments[i].equalsIgnoreCase(seg) : !segments[i].equals(seg)) {
                return null;
            }
        }
        return params == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(params);
    }

    /**
     * Positive when this pattern is more specific than {@code other}: at the leftmost segment where one is a
     * literal and the other a variable, the literal wins. Zero when no such segment exists.
     */
    // @req FR-ROUTE-007
    public int compareSpecificity(RoutePattern other) {
        int n = Math.min(segments.length, other.segments.length);
        for (int i = 0; i < n; i++) {
            if (variable[i] != other.variable[i]) {
                return variable[i] ? -1 : 1;
            }
        }
        return 0;
    }

    /** Equal keys mean conflicting patterns: variables as ':', case-folded and trailing slash dropped per policy. */
    // @req FR-ROUTE-008
    public String conflictKey(RouterPolicy policy) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0, n = effectiveLength(policy); i < n; i++) {
            sb.append('/');
            if (variable[i]) {
                sb.append(':');
            } else {
                sb.append(policy.caseInsensitive() ? fold(segments[i]) : segments[i]);
            }
        }
        return sb.toString();
    }

    /**
     * Per-char fold with exactly the equivalence of {@link String#equalsIgnoreCase} used by {@link #match}, so
     * literals that match the same paths get the same key; locale-independent, unlike toLowerCase(Locale.ROOT)
     * on whole strings (which maps U+0130 to two chars and leaves U+017F alone).
     */
    private static String fold(String s) {
        char[] cs = s.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            cs[i] = Character.toLowerCase(Character.toUpperCase(cs[i]));
        }
        return new String(cs);
    }

    /** The pattern as registered. */
    @Override
    public String toString() {
        return source;
    }
}
