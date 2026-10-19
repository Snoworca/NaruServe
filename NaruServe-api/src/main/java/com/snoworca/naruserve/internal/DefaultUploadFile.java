package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.UploadFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One parsed part, held in memory or in one spooled temp file. The filename is kept verbatim and never used in a
 * path. save, close and the end of the request release the content: later content methods throw
 * IllegalStateException while the metadata stays readable.
 */
// @req FR-MULTIPART-004
// @req FR-MULTIPART-005
// @req IR-MULTIPART-002
public final class DefaultUploadFile implements UploadFile {

    /** The largest part bytes() returns. */
    // @req IR-MULTIPART-002
    public static final long BYTES_LIMIT = 8388608L;

    private final String name;
    private final @Nullable String filename;
    private final @Nullable String contentType;
    private final long size;
    private final SpoolRegistry spool;
    // Guarded by this; memory and file are null once released.
    private byte @Nullable [] memory;
    private @Nullable File file;
    private boolean released;
    private boolean ended;
    private final List<InputStream> streams = new ArrayList<>();

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

    /** A new stream over the content; the end of the request closes it, so a later read throws IOException. */
    // @req FR-MULTIPART-005
    @Override
    public InputStream inputStream() throws IOException {
        synchronized (this) {
            checkContent();
            File f = file;
            byte[] m = memory;
            InputStream in = new ClosableStream(f != null ? new FileInputStream(f)
                    : new ByteArrayInputStream(m == null ? new byte[0] : m));
            streams.add(in);
            return in;
        }
    }

    private void checkContent() {
        if (released) {
            throw new IllegalStateException(ended ? "upload '" + name + "' used after its request ended"
                    : "upload '" + name + "' content was released by save or close");
        }
    }

    /** The request ended: closes every stream handed out, then releases the content; idempotent. */
    // @req FR-MULTIPART-005
    // @req FR-MULTIPART-006
    public void release() {
        synchronized (this) {
            ended = ended || !released;
        }
        close();
    }

    /**
     * Copies through a truncating FileOutputStream, never File.renameTo, then releases the content. The content
     * stream is opened first, so a released part throws before the destination is truncated.
     */
    // @req FR-MULTIPART-005
    @Override
    public void save(File destination) throws IOException {
        try (InputStream in = inputStream(); OutputStream out = new FileOutputStream(destination)) {
            copy(in, out);
        }
        close();
    }

    // @req FR-MULTIPART-005
    @Override
    public void transferTo(OutputStream target) throws IOException {
        try (InputStream in = inputStream()) {
            copy(in, target);
        }
        target.flush();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) >= 0) {
            out.write(b, 0, n);
        }
    }

    // @req FR-MULTIPART-005
    @Override
    public byte[] bytes() throws IOException {
        synchronized (this) {
            checkContent();
        }
        if (size > BYTES_LIMIT) {
            throw new IllegalStateException("upload '" + name + "' is " + size + " bytes, above the bytes() limit of "
                    + BYTES_LIMIT + " bytes; use inputStream(), transferTo or save");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) size);
        transferTo(out);
        return out.toByteArray();
    }

    /**
     * Closes every stream handed out, then releases the content and deletes the temp file (an open stream would block
     * the delete on Windows); the metadata stays readable. Idempotent.
     */
    // @req FR-MULTIPART-005
    @Override
    public void close() {
        File f;
        List<InputStream> open;
        synchronized (this) {
            released = true;
            f = file;
            file = null;
            memory = null;
            open = new ArrayList<>(streams);
            streams.clear();
        }
        for (InputStream in : open) {
            try {
                in.close();
            } catch (IOException ignored) {
                // the content is released next either way
            }
        }
        if (f != null) {
            spool.forget(f);
        }
    }

    /** A stream whose reads throw IOException once closed, also over an in-memory part. */
    private static final class ClosableStream extends FilterInputStream {

        private volatile boolean closed;

        ClosableStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            open();
            return super.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            open();
            return super.read(b, off, len);
        }

        @Override
        public long skip(long n) throws IOException {
            open();
            return super.skip(n);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private void open() throws IOException {
            if (closed) {
                throw new IOException("upload stream closed");
            }
        }
    }
}
