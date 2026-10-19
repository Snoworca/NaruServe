package com.snoworca.naruserve.internal;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;

/**
 * One parsed part, held in memory or in one spooled temp file. The filename is kept verbatim and never used in a
 * path. shortcut: only the parser's needs; the UploadFile content methods and request-end rules come with the
 * UploadFile and spooling fragments of wave 6.
 */
// @req FR-MULTIPART-004
// @req FR-MULTIPART-005
public final class DefaultUploadFile {

    private final String name;
    private final @Nullable String filename;
    private final @Nullable String contentType;
    private final long size;
    private final byte @Nullable [] memory;
    private final @Nullable File file;
    private final SpoolRegistry spool;

    // @req FR-MULTIPART-004
    DefaultUploadFile(String name, @Nullable String filename, @Nullable String contentType, long size,
            byte @Nullable [] memory, @Nullable File file, SpoolRegistry spool) {
        this.name = name;
        this.filename = filename;
        this.contentType = contentType;
        this.size = size;
        this.memory = memory;
        this.file = file;
        this.spool = spool;
    }

    public String name() {
        return name;
    }

    public @Nullable String filename() {
        return filename;
    }

    public @Nullable String contentType() {
        return contentType;
    }

    public long size() {
        return size;
    }

    // @req FR-MULTIPART-005
    public InputStream inputStream() throws IOException {
        File f = file;
        if (f != null) {
            return new FileInputStream(f);
        }
        byte[] m = memory;
        return new ByteArrayInputStream(m == null ? new byte[0] : m);
    }

    /** Deletes the temp file, if any; idempotent. */
    // @req FR-MULTIPART-005
    public void release() {
        File f = file;
        if (f != null) {
            spool.forget(f);
        }
    }
}
