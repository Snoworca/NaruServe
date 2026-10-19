package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.MimeTypes;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The per-app MIME registry; register goes through the app's NEW-state guard. Lookup order: the mount's mapping, this
 * registry, the built-in table, then {@code application/octet-stream}.
 */
// @req IR-STATIC-002
// @req FR-STATIC-007
final class DefaultMimeTypes implements MimeTypes {

    /**
     * Exactly the built-in table of docs/api/static.md as extension, type pairs. A private array, not a static map:
     * FR-APP-002 AC-2 forbids static collection fields, so each instance and {@link #builtIn()} copy it.
     */
    private static final String[] BUILT_IN_ROWS = {"html", "text/html", "htm", "text/html", "css", "text/css",
            "js", "text/javascript", "mjs", "text/javascript", "json", "application/json", "map", "application/json",
            "webmanifest", "application/manifest+json", "wasm", "application/wasm", "svg", "image/svg+xml",
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "webp", "image/webp",
            "avif", "image/avif", "woff", "font/woff", "woff2", "font/woff2", "mp4", "video/mp4",
            "webm", "video/webm", "pdf", "application/pdf"};

    private final Map<String, String> builtIn = builtIn();

    /** A new unmodifiable copy of the built-in table, keyed by lower-case extension without the dot. */
    // @req FR-STATIC-007
    static Map<String, String> builtIn() {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < BUILT_IN_ROWS.length; i += 2) {
            m.put(BUILT_IN_ROWS[i], BUILT_IN_ROWS[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }

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

    /**
     * The Content-Type for {@code fileName}: the extension after its last '.', looked up in {@code mount}, this
     * registry and the built-in table; a bare {@code text/*} type gains {@code ; charset=UTF-8}.
     */
    // @req FR-STATIC-007
    String lookup(String fileName, Map<String, String> mount) {
        int dot = fileName.lastIndexOf('.');
        String type = null;
        if (dot >= 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            type = mount.get(ext);
            if (type == null) {
                type = registered.get(ext);
            }
            if (type == null) {
                type = builtIn.get(ext);
            }
        }
        if (type == null) {
            return "application/octet-stream";
        }
        boolean bareText = type.indexOf(';') < 0 && type.toLowerCase(Locale.ROOT).startsWith("text/");
        return bareText ? type + "; charset=UTF-8" : type;
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
