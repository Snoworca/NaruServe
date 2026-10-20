package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

/**
 * {@link HttpExchange} over one container request and response. Request values come from the plain Servlet getters
 * (never {@code getServletPath}, {@code getPathInfo} or {@code getParameter*}); the response is written with
 * {@code setStatus}, {@code addHeader} and {@code setHeader("Content-Length")}, never {@code sendError} or
 * {@code setContentLength}, so the core owns the error body and the length.
 */
// @req FR-SVL-002
// @req FR-SVL-003
final class ServletExchange implements HttpExchange {

    /** The largest chunk {@link #sendFile} reads and writes at once. */
    static final int CHUNK = 65536;

    private final HttpServletRequest req;
    private final HttpServletResponse res;
    private final String path;
    // Guarded by this.
    private boolean started;
    // Lock-free so abort() never waits for a writer.
    private volatile boolean aborted;

    // @req FR-SVL-002
    ServletExchange(HttpServletRequest req, HttpServletResponse res, String path) {
        this.req = req;
        this.res = res;
        this.path = path;
    }

    // @req FR-SVL-002
    @Override
    public String methodToken() {
        return req.getMethod();
    }

    /** The remainder {@link ServletPath} computed from the raw request URI. */
    // @req FR-SVL-002
    @Override
    public String rawPath() {
        return path;
    }

    // @req FR-SVL-002
    @Override
    public @Nullable String rawQuery() {
        return req.getQueryString();
    }

    // @req FR-SVL-002
    @Override
    public String httpVersion() {
        return req.getProtocol();
    }

    /** Every value of every name, in per-name order; the Servlet API gives no order across names. */
    // @req FR-SVL-002
    @Override
    public List<Map.Entry<String, String>> headers() {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            Enumeration<String> values = req.getHeaders(name);
            while (values != null && values.hasMoreElements()) {
                out.add(new SimpleImmutableEntry<>(name, values.nextElement()));
            }
        }
        return Collections.unmodifiableList(out);
    }

    // @req FR-SVL-002
    @Override
    public InputStream requestBody() {
        try {
            return req.getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException("request body unavailable", e);
        }
    }

    // @req FR-SVL-002
    @Override
    public String remoteAddress() {
        return req.getRemoteAddr();
    }

    // @req FR-SVL-002
    @Override
    public boolean isSecure() {
        return req.isSecure();
    }

    /** {@code contentLength} needs no action: the core's Content-Length header is passed as given. */
    // @req FR-SVL-003
    @Override
    public synchronized OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength)
            throws IOException {
        requireStartable();
        started = true;
        res.setStatus(status);
        for (Map.Entry<String, String> h : headers) {
            if (h.getKey().equalsIgnoreCase("Content-Length")) {
                res.setHeader(h.getKey(), h.getValue());
            } else {
                res.addHeader(h.getKey(), h.getValue());
            }
        }
        return new FilterOutputStream(res.getOutputStream()) {
            @Override
            public void write(int b) throws IOException {
                check();
                out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                check();
                out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                check();
                out.flush();
            }

            @Override
            public void close() {
                // The container ends the body when service() returns.
            }
        };
    }

    // @req FR-SVL-003
    @Override
    public void finish() throws IOException {
        check();
        res.flushBuffer();
    }

    /** Marks the exchange aborted; the container owns the socket, so nothing is called on it. */
    // @req FR-SVL-003
    @Override
    public void abort() {
        aborted = true;
    }

    /** Commits, then copies {@code length} bytes from {@code offset} through java.io in chunks of {@link #CHUNK}. */
    // @req FR-SVL-003
    @Override
    public void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset, long length)
            throws IOException {
        // The start rule first, then the range and the open, so a bad range or a missing file leaves the response
        // unstarted and the core can still answer with an error.
        requireStartable();
        long size = file.length();
        if (offset < 0 || length < 0 || offset > size - length) {
            throw new IllegalArgumentException(
                    "range offset " + offset + " length " + length + " outside file of " + size + " bytes");
        }
        OutputStream out;
        try (InputStream in = new FileInputStream(file)) {
            for (long skipped = 0; skipped < offset; ) {
                long n = in.skip(offset - skipped);
                if (n <= 0) {
                    throw new EOFException("file ended before offset " + offset + ": " + file);
                }
                skipped += n;
            }
            out = commit(status, headers, length);
            byte[] buf = new byte[(int) Math.min(CHUNK, Math.max(1, length))];
            for (long left = length; left > 0; ) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    throw new EOFException("file ended " + left + " bytes before the requested range: " + file);
                }
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.flush();
    }

    // @req FR-SVL-003
    @Override
    public void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) {
        throw new UnsupportedOperationException("upgrade is not supported by the Servlet adapter");
    }

    // @req FR-SVL-003
    @Override
    public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain) {
        throw new UnsupportedOperationException("eventStream is not supported by the Servlet adapter yet");
    }

    /** A second start of one exchange throws IllegalStateException; an aborted one throws IOException. */
    // @req FR-SVL-003
    synchronized void requireStartable() throws IOException {
        check();
        if (started) {
            throw new IllegalStateException("the response was already started");
        }
    }

    private void check() throws IOException {
        if (aborted) {
            throw new IOException("exchange aborted");
        }
    }
}
