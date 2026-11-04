package com.snoworca.naruserve.utils.auth.jwt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The claims of one token: what {@link Jwt#sign(JwtClaims)} writes and what {@link Jwt#verify(String, String)}
 * returns. Times are epoch milliseconds, like every auth timestamp; a token stores whole seconds (RFC 7519
 * NumericDate), so a time is rounded down to the second when it is signed. {@link #getType()} is the header
 * {@code typ}. Custom claims are strings, numbers, booleans and string lists. {@link #toString()} shows claim names,
 * not custom values. Express counterpart: the payload object of {@code jwt.sign(payload, key)}.
 *
 * <pre>{@code
 * JwtClaims c = JwtClaims.builder().subject("device-7").type("device").expiresAt(now + 3_600_000L)
 *     .claim("room", "lab").build();
 * }</pre>
 */
// @req SEC-TOKEN-003
public final class JwtClaims {

    /** The claim names with their own builder setter; {@link Builder#claim} refuses them. */
    static final Set<String> REGISTERED = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "sub", "iss", "aud", "exp", "nbf", "iat", "jti")));

    private final @Nullable String subject;
    private final @Nullable String type;
    private final @Nullable String issuer;
    private final List<String> audience;
    private final @Nullable Long issuedAt;
    private final @Nullable Long notBefore;
    private final long expiresAt;
    private final @Nullable String tokenId;
    private final Map<String, Object> custom;

    private JwtClaims(Builder b, long expiresAt) {
        this.subject = b.subject;
        this.type = b.type;
        this.issuer = b.issuer;
        this.audience = Collections.unmodifiableList(new ArrayList<>(b.audience));
        this.issuedAt = b.issuedAt;
        this.notBefore = b.notBefore;
        this.expiresAt = expiresAt;
        this.tokenId = b.tokenId;
        this.custom = Collections.unmodifiableMap(new LinkedHashMap<>(b.custom));
    }

    /** A builder; {@link Builder#build()} needs {@link Builder#expiresAt(long)}. */
    // @req SEC-TOKEN-003
    public static Builder builder() {
        return new Builder();
    }

    /** {@code sub}, or null. */
    public @Nullable String getSubject() {
        return subject;
    }

    /** The header {@code typ}, or null ({@code JWT} is written then). */
    public @Nullable String getType() {
        return type;
    }

    /** {@code iss}, or null. */
    public @Nullable String getIssuer() {
        return issuer;
    }

    /** {@code aud} as a list; empty without one. */
    public List<String> getAudience() {
        return audience;
    }

    /** {@code iat} in epoch milliseconds, or null. */
    public @Nullable Long getIssuedAt() {
        return issuedAt;
    }

    /** {@code nbf} in epoch milliseconds, or null. */
    public @Nullable Long getNotBefore() {
        return notBefore;
    }

    /** {@code exp} in epoch milliseconds; every token has one. */
    public long getExpiresAt() {
        return expiresAt;
    }

    /** {@code jti}, or null. */
    public @Nullable String getTokenId() {
        return tokenId;
    }

    /** The custom claim names, in token order. */
    public Set<String> getClaimNames() {
        return custom.keySet();
    }

    /** The custom string claim {@code name}, or null when it is absent or not a string. */
    public @Nullable String getString(String name) {
        Object v = custom.get(name);
        return v instanceof String ? (String) v : null;
    }

    /** The custom integer claim {@code name}, or null when it is absent or not an integer. */
    public @Nullable Long getLong(String name) {
        Object v = custom.get(name);
        return v instanceof Long ? (Long) v : null;
    }

    /** The custom boolean claim {@code name}, or null when it is absent or not a boolean. */
    public @Nullable Boolean getBoolean(String name) {
        Object v = custom.get(name);
        return v instanceof Boolean ? (Boolean) v : null;
    }

    /** The custom string-list claim {@code name}, or null when it is absent or not a list of strings. */
    public @Nullable List<String> getStringList(String name) {
        Object v = custom.get(name);
        if (!(v instanceof List)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) v) {
            if (!(o instanceof String)) {
                return null;
            }
            out.add((String) o);
        }
        return Collections.unmodifiableList(out);
    }

    /** The custom claims as read or set; values are String, Long, Boolean or a list of String. */
    Map<String, Object> custom() {
        return custom;
    }

    @Override
    public String toString() {
        return "JwtClaims{sub=" + subject + ", typ=" + type + ", exp=" + expiresAt + ", claims=" + custom.keySet()
                + "}";
    }

    /** Collects the claims of a {@link JwtClaims}. */
    public static final class Builder {
        private @Nullable String subject;
        private @Nullable String type;
        private @Nullable String issuer;
        private final List<String> audience = new ArrayList<>();
        private @Nullable Long issuedAt;
        private @Nullable Long notBefore;
        private @Nullable Long expiresAt;
        private @Nullable String tokenId;
        private final Map<String, Object> custom = new LinkedHashMap<>();

        private Builder() {
        }

        /** {@code sub}: whom the token is about. */
        public Builder subject(@Nullable String subject) {
            this.subject = subject;
            return this;
        }

        /** The header {@code typ}; {@link Jwt#verify(String, String)} compares it with the expected type. */
        public Builder type(@Nullable String type) {
            this.type = type;
            return this;
        }

        /** {@code iss}; when null, {@link Jwt.Builder#issuer(String)} applies. */
        public Builder issuer(@Nullable String issuer) {
            this.issuer = issuer;
            return this;
        }

        /** {@code aud}; when empty, {@link Jwt.Builder#audience(String)} applies. */
        public Builder audience(String... audience) {
            this.audience.clear();
            for (String a : audience) {
                this.audience.add(Objects.requireNonNull(a, "audience"));
            }
            return this;
        }

        /** {@code iat} in epoch milliseconds; when not set, signing writes the current time. */
        public Builder issuedAt(long millis) {
            this.issuedAt = millis;
            return this;
        }

        /** {@code nbf} in epoch milliseconds. */
        public Builder notBefore(long millis) {
            this.notBefore = millis;
            return this;
        }

        /** {@code exp} in epoch milliseconds; required. */
        public Builder expiresAt(long millis) {
            this.expiresAt = millis;
            return this;
        }

        /** {@code jti}. */
        public Builder tokenId(@Nullable String tokenId) {
            this.tokenId = tokenId;
            return this;
        }

        /** A custom string claim. */
        public Builder claim(String name, String value) {
            return put(name, Objects.requireNonNull(value, "value"));
        }

        /** A custom integer claim. */
        public Builder claim(String name, long value) {
            return put(name, value);
        }

        /** A custom boolean claim. */
        public Builder claim(String name, boolean value) {
            return put(name, value);
        }

        /** A custom string-list claim. */
        public Builder claim(String name, List<String> value) {
            List<String> copy = new ArrayList<>();
            for (String s : value) {
                copy.add(Objects.requireNonNull(s, "value"));
            }
            return put(name, Collections.unmodifiableList(copy));
        }

        private Builder put(String name, Object value) {
            Objects.requireNonNull(name, "name");
            if (REGISTERED.contains(name)) {
                throw new IllegalArgumentException("claim \"" + name + "\" has its own builder method");
            }
            custom.put(name, value);
            return this;
        }

        /** Every custom claim as read from a verified token (parse side, no reserved-name check). */
        Builder raw(String name, Object value) {
            custom.put(name, value);
            return this;
        }

        /** The claims; {@link IllegalStateException} without {@link #expiresAt(long)}. */
        // @req SEC-TOKEN-003
        public JwtClaims build() {
            Long exp = expiresAt;
            if (exp == null) {
                throw new IllegalStateException("a token needs an expiry: call expiresAt(millis)");
            }
            return new JwtClaims(this, exp);
        }
    }
}
