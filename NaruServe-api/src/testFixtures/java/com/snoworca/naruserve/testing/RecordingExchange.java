package com.snoworca.naruserve.testing;

import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Hand-written exchange that counts the calls the core makes (commit, finish, abort, sendFile) and keeps the
 * committed status, headers and body, so a test can tell the response's own abort from the in-memory adapter's
 * fallback abort of an unfinished exchange.
 */
@NullMarked
public final class RecordingExchange implements HttpExchange {
    public final String method;
    public final String path;
    public final List<Map.Entry<String, String>> requestHeaders = new ArrayList<>();
    // Atomic and volatile: the core may call these from the handler thread and an abort-caller thread at once.
    public final AtomicInteger commits = new AtomicInteger();
    public final AtomicInteger finishes = new AtomicInteger();
    public final AtomicInteger aborts = new AtomicInteger();
    public final AtomicInteger sendFiles = new AtomicInteger();
    public volatile int status;
    /** The length passed to commit; -2 before any commit. */
    public volatile long contentLength = -2;
    public long failWriteAfter = Long.MAX_VALUE;
    public Runnable onWrite = () -> { };
    public Runnable onCommit = () -> { };
    public volatile List<Map.Entry<String, String>> headers = Collections.emptyList();
    public final ByteArrayOutputStream body = new ByteArrayOutputStream();
    /** Thrown by {@link #eventStream} when set (an IOException or a RuntimeException). */
    public volatile @Nullable Exception eventStreamFailure;
    private volatile @Nullable StreamDrain drain;

    /** {@code headerPairs} are request header name, value, name, value, ... */
    public RecordingExchange(String method, String path, String... headerPairs) {
        this.method = method;
        this.path = path;
        for (int i = 0; i < headerPairs.length; i += 2) {
            requestHeaders.add(new SimpleImmutableEntry<>(headerPairs[i], headerPairs[i + 1]));
        }
    }

    /** Values of committed header {@code name}, case-insensitive. */
    public List<String> committed(String name) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> h : headers) {
            if (h.getKey().equalsIgnoreCase(name)) {
                values.add(h.getValue());
            }
        }
        return values;
    }

    @Override
    public String methodToken() {
        return method;
    }

    @Override
    public String rawPath() {
        return path;
    }

    @Override
    public @Nullable String rawQuery() {
        return null;
    }

    @Override
    public String httpVersion() {
        return "HTTP/1.1";
    }

    @Override
    public List<Map.Entry<String, String>> headers() {
        return requestHeaders;
    }

    @Override
    public InputStream requestBody() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public String remoteAddress() {
        return "127.0.0.1";
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength) {
        commits.incrementAndGet();
        this.status = status;
        this.contentLength = contentLength;
        this.headers = headers;
        onCommit.run();
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                onWrite.run();
                if (body.size() >= failWriteAfter) {
                    throw new IOException("peer gone");
                }
                body.write(b, off, len);
            }
        };
    }

    @Override
    public void finish() {
        finishes.incrementAndGet();
    }

    @Override
    public void abort() {
        aborts.incrementAndGet();
    }

    @Override
    public void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset, long length) {
        sendFiles.incrementAndGet();
    }

    @Override
    public void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain)
            throws IOException {
        Exception e = eventStreamFailure;
        if (e instanceof IOException) {
            throw (IOException) e;
        }
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        commits.incrementAndGet();
        this.status = status;
        this.contentLength = -1;
        this.headers = headers;
        this.drain = drain; // never attached: the core falls back to its bounded queue
    }

    /** The drain stored by {@link #eventStream}, run by the test on its own thread; null before that. */
    // @req FR-SSE-002
    public @Nullable StreamDrain drain() {
        return drain;
    }
}
