package com.snoworca.naruserve.proxy;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A JDK-only test backend over a raw {@link ServerSocket} on 127.0.0.1 (D-070): it accepts one connection at a time,
 * reads one request head and its body (a {@code Content-Length} or a chunked body), records them, hands the
 * connection to a {@link Responder} and closes it. Responders can write any bytes, including malformed or delayed
 * responses.
 */
final class RawBackend implements Closeable {

    /** Writes the response for the {@code index}-th request (0-based) of this backend. */
    interface Responder {
        void respond(int index, Received request, OutputStream out) throws IOException;
    }

    /** One request as the backend received it. */
    static final class Received {
        /** The request head up to and excluding the blank line, decoded as ISO-8859-1. */
        final String head;
        final byte[] body;

        Received(String head, byte[] body) {
            this.head = head;
            this.body = body;
        }

        /** The request line, for example {@code GET /x HTTP/1.1}. */
        String requestLine() {
            int end = head.indexOf("\r\n");
            return end < 0 ? head : head.substring(0, end);
        }

        /** The header lines after the request line, in received order. */
        List<String> headerLines() {
            List<String> lines = new ArrayList<>();
            String[] parts = head.split("\r\n", -1);
            for (int i = 1; i < parts.length; i++) {
                lines.add(parts[i]);
            }
            return lines;
        }

        /** Every value of {@code name} (case-insensitive), trimmed, in received order. */
        List<String> headerValues(String name) {
            List<String> values = new ArrayList<>();
            for (String line : headerLines()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                    values.add(line.substring(colon + 1).trim());
                }
            }
            return values;
        }
    }

    private final ServerSocket server;
    private final Responder responder;
    private final Thread acceptor;
    private final AtomicInteger count = new AtomicInteger();
    private final List<Received> received = Collections.synchronizedList(new ArrayList<Received>());

    private RawBackend(Responder responder) throws IOException {
        this.responder = responder;
        this.server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        this.acceptor = new Thread(this::acceptLoop, "raw-backend-" + server.getLocalPort());
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    /** A backend that runs {@code responder} for every request. */
    static RawBackend start(Responder responder) throws IOException {
        return new RawBackend(responder);
    }

    /** A backend that answers every request with the same raw bytes, given as ISO-8859-1 text. */
    static RawBackend answering(String rawResponse) throws IOException {
        byte[] bytes = rawResponse.getBytes(StandardCharsets.ISO_8859_1);
        return start((index, request, out) -> out.write(bytes));
    }

    /** {@code HTTP/1.1 <status> X} with {@code Content-Length} and {@code body} as ISO-8859-1 bytes. */
    static String response(int status, String body) {
        return "HTTP/1.1 " + status + " X\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
    }

    int port() {
        return server.getLocalPort();
    }

    /** {@code http://127.0.0.1:<port>}. */
    String url() {
        return "http://127.0.0.1:" + port();
    }

    /** How many request heads the backend has read so far. */
    int requestCount() {
        return count.get();
    }

    /** The requests received so far, in arrival order. */
    List<Received> requests() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    /** The only request received; fails when the backend saw another number of requests. */
    Received onlyRequest() {
        List<Received> all = requests();
        if (all.size() != 1) {
            throw new AssertionError("expected exactly one backend request, got " + all.size());
        }
        return all.get(0);
    }

    @Override
    public void close() throws IOException {
        server.close();
        try {
            acceptor.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(10_000);
                InputStream in = s.getInputStream();
                String head = readHead(in);
                Received r = new Received(head, readBody(in, new Received(head, new byte[0])));
                received.add(r);
                int index = count.getAndIncrement();
                OutputStream out = s.getOutputStream();
                responder.respond(index, r, out);
                out.flush();
            } catch (SocketException closed) {
                // the server socket was closed, or the proxy dropped the connection
            } catch (IOException | RuntimeException e) {
                // a broken exchange ends this connection only; the next one is served
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (head.length() < 4 || !head.substring(head.length() - 4).equals("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("EOF inside the request head: " + head);
            }
            head.append((char) b);
        }
        return head.substring(0, head.length() - 4);
    }

    private static byte[] readBody(InputStream in, Received head) throws IOException {
        for (String te : head.headerValues("Transfer-Encoding")) {
            if (te.toLowerCase(Locale.ROOT).contains("chunked")) {
                return readChunked(in);
            }
        }
        List<String> lengths = head.headerValues("Content-Length");
        if (lengths.isEmpty()) {
            return new byte[0];
        }
        int n = Integer.parseInt(lengths.get(0));
        byte[] body = new byte[n];
        for (int off = 0; off < n; ) {
            int k = in.read(body, off, n - off);
            if (k < 0) {
                throw new EOFException("EOF after " + off + " of " + n + " body bytes");
            }
            off += k;
        }
        return body;
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            int semi = sizeLine.indexOf(';');
            int size = Integer.parseInt((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim(), 16);
            if (size == 0) {
                while (!readLine(in).isEmpty()) {
                    // trailer lines are read and dropped
                }
                return body.toByteArray();
            }
            for (int i = 0; i < size; i++) {
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("EOF inside a chunk");
                }
                body.write(b);
            }
            readLine(in);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int b; (b = in.read()) != '\n'; ) {
            if (b < 0) {
                throw new EOFException("EOF inside a line");
            }
            if (b != '\r') {
                line.append((char) b);
            }
        }
        return line.toString();
    }
}
