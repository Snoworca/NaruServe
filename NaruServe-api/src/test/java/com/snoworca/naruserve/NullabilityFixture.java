package com.snoworca.naruserve;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Java methods whose JSpecify annotations Kotlin must read as {@code String?} and {@code String}. */
public final class NullabilityFixture {

    private NullabilityFixture() {
    }

    // @req CON-BUILD-011
    public static @Nullable String annotatedNullable() {
        return null;
    }

    // @req CON-BUILD-011
    public static @NonNull String annotatedNonNull() {
        return "ok";
    }
}
