package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.CorsOptions;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.common.CorsValues;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Mutable configuration copied into the registered CORS middleware after complete validation. */
// @req FR-DX-010
final class DefaultCorsOptions implements CorsOptions {
    final Set<String> origins = new LinkedHashSet<>();
    final Set<String> methods = new LinkedHashSet<>(Arrays.asList("GET", "HEAD", "POST"));
    final Set<String> headers = new LinkedHashSet<>();
    boolean credentials;
    long maxAge;

    @Override public CorsOptions allowedOrigins(String... values) {
        Objects.requireNonNull(values, "origins");
        Set<String> checked = new LinkedHashSet<>();
        for (String value : values) {
            Objects.requireNonNull(value, "origin");
            if (!"*".equals(value) && !CorsValues.isOrigin(value)) {
                throw new IllegalArgumentException("allowedOrigins requires serialized HTTP(S) origins, null or *");
            }
            checked.add(value);
        }
        origins.clear(); origins.addAll(checked);
        return this;
    }

    @Override public CorsOptions allowedMethods(Method... values) {
        Objects.requireNonNull(values, "methods");
        Set<String> checked = new LinkedHashSet<>();
        for (Method value : values) checked.add(Objects.requireNonNull(value, "method").name());
        methods.clear(); methods.addAll(checked);
        return this;
    }

    @Override public CorsOptions allowedHeaders(String... values) {
        Objects.requireNonNull(values, "headers");
        Set<String> checked = new LinkedHashSet<>();
        for (String value : values) {
            Objects.requireNonNull(value, "header");
            DefaultResponse.checkHeader(value, "");
            checked.add(value.toLowerCase(Locale.ROOT));
        }
        headers.clear(); headers.addAll(checked);
        return this;
    }

    @Override public CorsOptions credentials(boolean allowed) { credentials = allowed; return this; }

    @Override public CorsOptions maxAge(long seconds) {
        if (seconds < 0) throw new IllegalArgumentException("maxAge must be nonnegative seconds");
        maxAge = seconds;
        return this;
    }

    DefaultCors middleware() {
        if (credentials && origins.contains("*")) {
            throw new IllegalArgumentException("credentials cannot be combined with wildcard origin");
        }
        return new DefaultCors(this);
    }
}
