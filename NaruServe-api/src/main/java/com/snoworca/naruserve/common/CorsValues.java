package com.snoworca.naruserve.common;

import java.net.URI;

/** Shared serialized-origin checks for both CORS configuration and requests. */
// @req FR-DX-010
public final class CorsValues {
    private CorsValues() { }

    public static boolean isOrigin(String value) {
        if ("null".equals(value)) return true;
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getPort() <= 65535 && !value.endsWith(":");
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
