package com.snoworca.naruserve;

import org.jspecify.annotations.Nullable;

/** Typed service map: one instance per type key. Every argument is non-null. */
// @req IR-APP-004
public interface ServiceRegistry {

    /** Registers {@code instance} under {@code type}; an {@link AutoCloseable} instance is closed at shutdown. */
    <T> void register(Class<T> type, T instance);

    /** Registers {@code instance} under {@code type}; the framework never closes it. */
    <T> void registerExternal(Class<T> type, T instance);

    /** The instance under {@code type}; throws {@link ServiceNotFoundException} when none is registered. */
    <T> T get(Class<T> type);

    /** The instance under {@code type}, or {@code null} when none is registered. */
    <T> @Nullable T getOrNull(Class<T> type);

    /** Whether an instance is registered under {@code type}. */
    boolean contains(Class<?> type);
}
