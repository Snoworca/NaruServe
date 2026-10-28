package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Next;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.common.CorsValues;
import com.snoworca.naruserve.common.HttpHeaderValues;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable HTTP CORS policy, registered as ordinary app middleware. */
// @req FR-DX-010
final class DefaultCors implements Middleware {
    private final Set<String> origins;
    private final Set<String> methods;
    private final Set<String> headers;
    private final boolean credentials;
    private final long maxAge;

    DefaultCors(DefaultCorsOptions options) {
        origins = new LinkedHashSet<>(options.origins);
        methods = new LinkedHashSet<>(options.methods);
        headers = new LinkedHashSet<>(options.headers);
        credentials = options.credentials;
        maxAge = options.maxAge;
    }

    @Override public void handle(Request req, Response res, Next next) throws Exception {
        // Upgrade Origin policy belongs to WebSocket settings, never HTTP CORS.
        for (String upgrade : req.headers("Upgrade")) {
            for (String token : upgrade.split(",")) {
                if ("websocket".equalsIgnoreCase(token.trim())) { next.run(); return; }
            }
        }
        DefaultResponse response = (DefaultResponse) res;
        response.corsVary("Origin");
        List<String> requestOrigins = req.headers("Origin");
        if (requestOrigins.isEmpty()) { next.run(); return; }
        boolean preflight = "OPTIONS".equals(req.methodToken()) && !req.headers("Access-Control-Request-Method").isEmpty();
        if (preflight) {
            response.corsVary("Access-Control-Request-Method");
            response.corsVary("Access-Control-Request-Headers");
        }
        String origin = requestOrigins.size() == 1 ? requestOrigins.get(0) : "";
        boolean allowed = CorsValues.isOrigin(origin) && (origins.contains("*") || origins.contains(origin));
        List<String> requestedHeaders = new ArrayList<>();
        if (preflight) {
            List<String> requestMethods = req.headers("Access-Control-Request-Method");
            allowed &= requestMethods.size() == 1 && methods.contains(requestMethods.get(0));
            for (String line : req.headers("Access-Control-Request-Headers")) {
                for (String value : line.split(",", -1)) {
                    String name = value.trim();
                    if (!DefaultResponse.isToken(name) || !headers.contains(name.toLowerCase(Locale.ROOT))) allowed = false;
                    requestedHeaders.add(name);
                }
            }
            if (!allowed) { res.status(403).end(); return; }
        }
        if (allowed) {
            response.corsHeader("Access-Control-Allow-Origin", origins.contains("*") ? "*" : origin);
            if (credentials) response.corsHeader("Access-Control-Allow-Credentials", "true");
            if (preflight) {
                response.corsHeader("Access-Control-Allow-Methods", HttpHeaderValues.commaSeparated(methods));
                if (!requestedHeaders.isEmpty()) response.corsHeader("Access-Control-Allow-Headers", HttpHeaderValues.commaSeparated(requestedHeaders));
                if (maxAge > 0) response.corsHeader("Access-Control-Max-Age", Long.toString(maxAge));
                res.status(204).end();
                return;
            }
        }
        next.run();
    }
}
