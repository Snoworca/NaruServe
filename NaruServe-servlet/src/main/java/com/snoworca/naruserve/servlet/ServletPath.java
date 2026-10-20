package com.snoworca.naruserve.servlet;

import org.jspecify.annotations.Nullable;

/** The request path the core sees: pure string code over the raw, still percent-encoded request URI. */
// @req FR-SVL-002
final class ServletPath {

    private ServletPath() {
    }

    /**
     * {@code requestUri} minus {@code contextPath} minus {@code prefix}, or null when the URI does not start with
     * both at a segment boundary (the rest must be empty or start with {@code /}); an empty rest is {@code /}.
     * Nothing is decoded or normalised, so an encoded segment cannot change which handler runs.
     */
    // @req FR-SVL-002
    static @Nullable String remainder(String requestUri, String contextPath, String prefix) {
        String base = contextPath + prefix;
        if (!requestUri.startsWith(base)) {
            return null;
        }
        String rest = requestUri.substring(base.length());
        if (rest.isEmpty()) {
            return "/";
        }
        return rest.charAt(0) == '/' ? rest : null;
    }
}
