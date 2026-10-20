package com.snoworca.naruserve;

import org.jspecify.annotations.Nullable;

/** The one app-wide context: services, attributes, owned resources and the app logger. */
// @req IR-APP-004
public interface ApplicationContext extends ServiceRegistry {

    /**
     * Stores {@code value} under {@code key}; a {@code null} value removes the key.
     * A literal {@code null} removes the value without a cast.
     */
    void attribute(String key, @Nullable Object value);

    /**
     * The value stored under {@code key} cast to {@code type}, or {@code null} when nothing is stored; throws
     * {@link ClassCastException} when the value is not a {@code type}.
     */
    <T> @Nullable T attributeAs(String key, Class<T> type);

    /** Removes an attribute; a missing key is harmless. */
    // @req FR-DX-001
    default void removeAttribute(String key) {
        attribute(key, (Object) null);
    }

    /** Closes {@code resource} at shutdown, in reverse order of ownership. */
    void own(AutoCloseable resource);

    /** The app logger. */
    Logger logger();
}
