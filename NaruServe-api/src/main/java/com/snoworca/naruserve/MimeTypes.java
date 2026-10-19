package com.snoworca.naruserve;

/**
 * The per-app MIME registry returned by {@link RestApp#mimeTypes()}, consulted after a mount's
 * {@link StaticOptions#mime} and before the built-in table. Registering after {@code listen()} throws
 * {@link IllegalStateException}.
 */
// @req IR-STATIC-002
public interface MimeTypes {

    /**
     * Maps the extension {@code ext} (with or without the leading dot, which is stripped) to {@code type} for this
     * app only; returns this registry.
     */
    MimeTypes register(String ext, String type);
}
