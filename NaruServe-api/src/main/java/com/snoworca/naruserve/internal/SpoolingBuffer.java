package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.jspecify.annotations.Nullable;

/** A file part's content: memory up to {@code threshold} bytes, then one temp file. Text parts never spool. */
// @req FR-MULTIPART-004
final class SpoolingBuffer extends OutputStream {

    private final int threshold;
    private final File dir;
    private final SpoolRegistry spool;
    private @Nullable ByteArrayOutputStream memory = new ByteArrayOutputStream();
    private @Nullable File file;
    private @Nullable OutputStream fileOut;
    private long size;

    // @req FR-MULTIPART-004
    SpoolingBuffer(int threshold, File dir, SpoolRegistry spool) {
        this.threshold = threshold;
        this.dir = dir;
        this.spool = spool;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    /** Byte threshold+1 creates the temp file, flushes the buffered bytes to it and drops the buffer. */
    // @req FR-MULTIPART-004
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        try {
            ByteArrayOutputStream m = memory;
            if (m != null && size + len > threshold) {
                File f = spool.createTemp(dir);
                file = f;
                OutputStream out = new FileOutputStream(f);
                fileOut = out;
                m.writeTo(out);
                memory = null;
            }
            ByteArrayOutputStream mem = memory;
            if (mem != null) {
                mem.write(b, off, len);
            } else {
                OutputStream out = fileOut;
                if (out != null) {
                    out.write(b, off, len);
                }
            }
        } catch (IOException e) {
            throw spoolFailed(e);
        }
        size += len;
    }

    /** The spool, not the request body, failed: a missing or unwritable tempDirectory, a full disk. */
    private static HttpException spoolFailed(IOException e) {
        return new HttpException(500, "upload spool failed", e);
    }

    // @req FR-MULTIPART-004
    DefaultUploadFile toUpload(String name, @Nullable String filename, @Nullable String contentType)
            throws IOException {
        OutputStream out = fileOut;
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                throw spoolFailed(e);
            }
        }
        ByteArrayOutputStream m = memory;
        return new DefaultUploadFile(name, filename, contentType, size, m == null ? null : m.toByteArray(), file,
                spool);
    }

    /** Deletes a half-written temp file. */
    // @req FR-MULTIPART-004
    void discard() {
        OutputStream out = fileOut;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // the file is deleted next either way
            }
        }
        File f = file;
        if (f != null) {
            spool.forget(f);
        }
    }
}
