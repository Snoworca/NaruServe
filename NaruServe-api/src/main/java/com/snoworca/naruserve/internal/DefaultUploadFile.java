package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.UploadFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.jspecify.annotations.Nullable;

/**
 * One parsed part, held in memory or in one spooled temp file. The filename is kept verbatim and never used in a
 * path. shortcut: plain content methods only; the bytes() limit, release-after-save and request-end rules come with
 * the spooling fragment of wave 6.
 */
// @req FR-MULTIPART-004
// @req FR-MULTIPART-005
// @req IR-MULTIPART-002
public final class DefaultUploadFile implements UploadFile {

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

    @Override
    public String name() {
        return name;
    }

    @Override
    public @Nullable String filename() {
        return filename;
    }

    @Override
    public @Nullable String contentType() {
        return contentType;
    }

    @Override
    public long size() {
        return size;
    }

    // @req FR-MULTIPART-005
    @Override
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

    // @req IR-MULTIPART-002
    @Override
    public void save(File destination) throws IOException {
        try (OutputStream out = new FileOutputStream(destination)) {
            transferTo(out);
        }
    }

    // @req IR-MULTIPART-002
    @Override
    public void transferTo(OutputStream target) throws IOException {
        try (InputStream in = inputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) >= 0) {
                target.write(b, 0, n);
            }
        }
        target.flush();
    }

    // @req IR-MULTIPART-002
    @Override
    public byte[] bytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transferTo(out);
        return out.toByteArray();
    }

    // @req IR-MULTIPART-002
    @Override
    public void close() {
        release();
    }
}
