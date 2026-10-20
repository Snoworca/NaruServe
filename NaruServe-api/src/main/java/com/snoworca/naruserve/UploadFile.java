package com.snoworca.naruserve;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.jspecify.annotations.Nullable;

/**
 * One part of a {@code multipart/form-data} body, from {@link Request#file}, {@link Request#files} or
 * {@link Request#parts}; like a multer file in Express. Only {@code java.io} types appear here, so it works on
 * Android 5.0. A text field is an UploadFile too, with a {@code null} {@link #filename()}.
 */
// @req IR-MULTIPART-002
public interface UploadFile extends AutoCloseable {

    /** The {@code name} parameter of the part's {@code Content-Disposition}. */
    String name();

    /** The client's {@code filename} parameter, unchanged; {@code null} for a part without one (a text field). */
    @Nullable String filename();

    /** The part's {@code Content-Type} header, or {@code null} when the part has none. */
    @Nullable String contentType();

    /** The content length in bytes. */
    long size();

    /** A new stream over the content; {@link IllegalStateException} once the request has ended. */
    InputStream inputStream() throws IOException;

    /**
     * Writes the content to {@code destination}, replacing it, and releases the temporary copy;
     * {@link IllegalStateException}, with {@code destination} untouched, once the request has ended.
     */
    void save(File destination) throws IOException;

    /**
     * Copies the content into {@code target} and flushes it; {@code target} is not closed.
     * {@link IllegalStateException} once the request has ended.
     */
    void transferTo(OutputStream target) throws IOException;

    /**
     * The whole content in memory; {@link IllegalStateException} for a part over 8 MiB (8388608 bytes) or once the
     * request has ended.
     */
    byte[] bytes() throws IOException;

    /** Releases the content, deleting any temporary file; the metadata stays readable. */
    @Override
    void close();
}
