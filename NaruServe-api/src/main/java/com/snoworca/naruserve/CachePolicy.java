package com.snoworca.naruserve;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * An immutable {@code Cache-Control} value for {@link Response#cache}, {@link StaticOptions#cache} and
 * {@link RestApp#defaultCache}: {@code CachePolicy.publicFor(365, TimeUnit.DAYS).immutable()} renders
 * {@code public, max-age=31536000, immutable}. Equality, hash code and {@link #toString()} follow
 * {@link #getHeaderValue()}.
 */
// @req IR-STATIC-002
public final class CachePolicy {

    private static final String IMMUTABLE = ", immutable";

    private final String headerValue;

    private CachePolicy(String headerValue) {
        this.headerValue = headerValue;
    }

    /** {@code no-store}. */
    // @req IR-STATIC-002
    public static CachePolicy noStore() {
        return new CachePolicy("no-store");
    }

    /** {@code no-cache}. */
    // @req IR-STATIC-002
    public static CachePolicy noCache() {
        return new CachePolicy("no-cache");
    }

    /** {@code private, max-age=<seconds>}; a negative {@code amount} throws {@link IllegalArgumentException}. */
    // @req IR-STATIC-002
    public static CachePolicy privateFor(long amount, TimeUnit unit) {
        return new CachePolicy("private, max-age=" + seconds(amount, unit));
    }

    /** {@code public, max-age=<seconds>}; a negative {@code amount} throws {@link IllegalArgumentException}. */
    // @req IR-STATIC-002
    public static CachePolicy publicFor(long amount, TimeUnit unit) {
        return new CachePolicy("public, max-age=" + seconds(amount, unit));
    }

    // @req IR-STATIC-002
    private static long seconds(long amount, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        if (amount < 0) {
            throw new IllegalArgumentException("cache max-age must not be negative: " + amount);
        }
        return unit.toSeconds(amount);
    }

    /** A new policy with {@code immutable} appended; this policy keeps its value. Appended at most once. */
    // @req IR-STATIC-002
    public CachePolicy immutable() {
        return new CachePolicy(headerValue.endsWith(IMMUTABLE) ? headerValue : headerValue + IMMUTABLE);
    }

    /** The {@code Cache-Control} header value; Kotlin reads it as {@code policy.headerValue}. */
    public String getHeaderValue() {
        return headerValue;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof CachePolicy && ((CachePolicy) o).headerValue.equals(headerValue);
    }

    @Override
    public int hashCode() {
        return headerValue.hashCode();
    }

    @Override
    public String toString() {
        return headerValue;
    }
}
