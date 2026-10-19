package com.snoworca.naruserve;

import java.io.IOException;
import java.io.InputStream;

/**
 * An engine-free source of static resources, addressed by the mount-relative path. The built-in {@code java.io.File}
 * resolver is the only v1 implementation and no public method accepts another one. Each method throws
 * {@link IOException} when it cannot read the path, {@link java.io.FileNotFoundException} for a missing or refused
 * path.
 */
// @req IR-STATIC-001
public interface ResourceResolver {

    /** A new stream over the resource; the caller closes it. */
    InputStream open(String path) throws IOException;

    /** The resource length in bytes. */
    long length(String path) throws IOException;

    /** The resource entity tag, quoted. */
    String etag(String path) throws IOException;

    /** The last-modified time in epoch milliseconds. */
    long modified(String path) throws IOException;
}
