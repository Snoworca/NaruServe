package com.snoworca.naruserve.common;

import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RoutePattern;
import com.snoworca.naruserve.routing.RouterPolicy;
import java.util.Objects;

/** Shared joining and validation for scoped route paths. */
// @req FR-DX-009
public final class RoutePaths {
    private RoutePaths() { }

    /** Joins slash-delimited paths, preserving their scope and normalizing boundary slashes. */
    public static String join(String prefix, String path) {
        validate(prefix);
        validate(path);
        String left = trimBoundarySlashes(prefix);
        String right = trimBoundarySlashes(path);
        String joined = "/" + left + (left.isEmpty() || right.isEmpty() ? "" : "/") + right;
        RoutePattern.parse(joined);
        return joined;
    }

    private static void validate(String path) {
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/") || path.indexOf('?') >= 0 || path.indexOf('#') >= 0 || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("group paths must start with '/' and contain only path segments");
        }
        for (String segment : Router.decodeSegments(path, RouterPolicy.strict())) {
            if (segment.equals(".") || segment.equals("..") || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("group paths must not contain traversal or encoded separators");
            }
        }
    }

    private static String trimBoundarySlashes(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') start++;
        while (end > start && path.charAt(end - 1) == '/') end--;
        return path.substring(start, end);
    }
}
