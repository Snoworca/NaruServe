package com.snoworca.naruserve.servlet;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A raw-socket SSE client: sends one GET, keeps the socket open, decodes the chunked body as it arrives and notes
 * the {@link System#nanoTime} at which each read returned, so a test can compare arrival times with send times.
 */
// @req FR-SVL-005
final class SseProbe implements Closeable {

    private final Socket socket = new Socket();
    private final InputStream in;
    private final ByteArrayOutputStream raw = new ByteArrayOutputStream();

    /** Connects to 127.0.0.1:{@code port}, sends {@code GET path} and reads the status line and headers. */
    // @req FR-SVL-005
    static SseProbe open(int port, String path) throws IOException {
        SseProbe p = new SseProbe(port, path);
        try {
            p.headers();
        } catch (IOException | RuntimeException | Error e) {
            p.close();
            throw e;
        }
        return p;
    }

    private SseProbe(int port, String path) throws IOException {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(10000);
        socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
        in = socket.getInputStream();
    }

    /** The status of the response head. */
    int status() throws IOException {
        String h = headers();
        return Integer.parseInt(h.substring(h.indexOf(' ') + 1, h.indexOf(' ') + 4));
    }

    /** The status line and headers; reads until they arrived. */
    String headers() throws IOException {
        while (true) {
            String all = text();
            int end = all.indexOf("\r\n\r\n");
            if (end >= 0) {
                return all.substring(0, end + 4);
            }
            if (read() < 0) {
                throw new IOException("end of stream before the headers");
            }
        }
    }

    /** The payload of the complete chunks received so far (or the plain body when the response is not chunked). */
    String payload() {
        String all = text();
        int head = all.indexOf("\r\n\r\n");
        if (head < 0) {
            return "";
        }
        if (!all.substring(0, head).toLowerCase().contains("transfer-encoding: chunked")) {
            return all.substring(head + 4);
        }
        StringBuilder out = new StringBuilder();
        int at = head + 4;
        while (true) {
            int eol = all.indexOf("\r\n", at);
            if (eol < 0) {
                return out.toString();
            }
            String size = all.substring(at, eol);
            int semi = size.indexOf(';');
            int n = Integer.parseInt((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
            // Jetty sends a chunk's closing CRLF only in front of the next chunk, so a chunk counts once its data came.
            if (n == 0 || all.length() < eol + 2 + n) {
                return out.toString();
            }
            out.append(all, eol + 2, eol + 2 + n);
            at = eol + 2 + n + 2;
        }
    }

    /**
     * Reads until the payload contains {@code part} and returns the {@link System#nanoTime} of the read that
     * completed it; the 10 s read timeout fails the test.
     */
    // @req FR-SVL-005
    long awaitPayload(String part) throws IOException {
        return awaitCount(part, 1);
    }

    /** As {@link #awaitPayload}, until the payload holds {@code part} at least {@code count} times. */
    // @req FR-SVL-005
    long awaitCount(String part, int count) throws IOException {
        long at = System.nanoTime();
        while (count(payload(), part) < count) {
            int n;
            try {
                n = read();
            } catch (java.net.SocketTimeoutException e) {
                throw new IOException("no " + part + " in time; received " + text(), e);
            }
            if (n < 0) {
                throw new IOException("end of stream before " + part + "; received " + text());
            }
            at = System.nanoTime();
        }
        return at;
    }

    /** Non-overlapping occurrences of {@code part} in {@code text}. */
    static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    /** Reads until the server ends the response or closes; false when the read timeout passed first. */
    boolean awaitEnd(int timeoutMillis) throws IOException {
        socket.setSoTimeout(timeoutMillis);
        try {
            while (!text().endsWith("\r\n0\r\n\r\n")) {
                if (read() < 0) {
                    return true;
                }
            }
            return true;
        } catch (java.net.SocketTimeoutException e) {
            return false;
        }
    }

    private int read() throws IOException {
        byte[] b = new byte[16384];
        int n = in.read(b);
        if (n > 0) {
            synchronized (raw) {
                raw.write(b, 0, n);
            }
        }
        return n;
    }

    private String text() {
        synchronized (raw) {
            return new String(raw.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
