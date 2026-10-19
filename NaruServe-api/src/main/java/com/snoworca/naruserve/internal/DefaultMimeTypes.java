package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.MimeTypes;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The per-app MIME registry; register goes through the app's NEW-state guard. Minimal wave 6 part 5 version: the
 * built-in table and the full lookup order arrive with FR-STATIC-007.
 */
// @req IR-STATIC-002
final class DefaultMimeTypes implements MimeTypes {

    private final AppLifecycle lifecycle;
    private final Map<String, String> registered = new ConcurrentHashMap<>();

    DefaultMimeTypes(AppLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    // @req IR-STATIC-002
    @Override
    public MimeTypes register(String ext, String type) {
        lifecycle.requireConfigurable("mimeTypes().register");
        registered.put(extension(ext), type(type));
        return this;
    }

    /** The app-registered type for a lower-case extension without the dot, or null. */
    @Nullable String registered(String ext) {
        return registered.get(ext.toLowerCase(Locale.ROOT));
    }

    /** {@code ext} without one leading dot, lower-cased; empty throws {@link IllegalArgumentException}. */
    // @req IR-STATIC-002
    static String extension(String ext) {
        Objects.requireNonNull(ext, "ext");
        String e = ext.startsWith(".") ? ext.substring(1) : ext;
        if (e.isEmpty()) {
            throw new IllegalArgumentException("MIME extension must not be empty: " + ext);
        }
        return e.toLowerCase(Locale.ROOT);
    }

    /** {@code type} checked non-empty. */
    // @req IR-STATIC-002
    static String type(String type) {
        Objects.requireNonNull(type, "type");
        if (type.isEmpty()) {
            throw new IllegalArgumentException("MIME type must not be empty");
        }
        return type;
    }
}
