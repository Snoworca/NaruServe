package com.snoworca.naruserve.tinyhttpd;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Loopback WebSocket client that writes every frame by hand, so tests can send what no real client would: unmasked
 * frames, oversized or fragmented control frames, invalid UTF-8. Reads time out after 2 s.
 */
// @req FR-WS-005
final class RawWebSocketClient implements Closeable {

    static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    static final String ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
    static final int CONT = 0, TEXT = 1, BINARY = 2, CLOSE = 8, PING = 9, PONG = 10;

    /** One server frame. */
    static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }

        /** The close code of a close frame. */
        int closeCode() {
            if (opcode != CLOSE || payload.length < 2) {
                throw new AssertionError("not a close frame with a code: opcode " + opcode);
            }
            return (payload[0] & 0xff) << 8 | payload[1] & 0xff;
        }

        /** The close reason of a close frame. */
        String closeReason() {
            return new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
        }

        String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    /** The raw handshake answer, status line and headers. */
    final String handshake;

    private RawWebSocketClient(Socket socket, String handshake) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.handshake = handshake;
    }

    /** Opens {@code path} with the RFC 6455 sample key and version 13, plus {@code extraHeaders} lines. */
    static RawWebSocketClient open(int port, String path, String... extraHeaders) throws IOException {
        StringBuilder sb = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\nHost: 127.0.0.1\r\n")
                .append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ").append(KEY)
                .append("\r\nSec-WebSocket-Version: 13\r\n");
        for (String h : extraHeaders) {
            sb.append(h).append("\r\n");
        }
        return raw(port, sb.append("\r\n").toString());
    }

    /** Writes {@code request} as ISO-8859-1 and reads the answer head; no check of its status. */
    static RawWebSocketClient raw(int port, String request) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(2000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            return new RawWebSocketClient(s, readHead(s.getInputStream()));
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head.write(b);
            matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : b == '\r' ? 1 : 0;
        }
        return new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    int status() {
        return RawTargetSocketTest.Http.status(handshake);
    }

    String header(String name) {
        return RawTargetSocketTest.Http.header(handshake + "\r\n", name);
    }

    /** Writes one frame; masked frames use the fixed key 1 2 3 4. */
    void frame(boolean fin, int opcode, byte[] payload, boolean masked) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        writeHeader(f, fin, opcode, payload.length, masked);
        for (int i = 0; i < payload.length; i++) {
            f.write(masked ? payload[i] ^ (i % 4 + 1) : payload[i]);
        }
        out.write(f.toByteArray());
        out.flush();
    }

    /**
     * Writes only the header and mask of a masked frame declaring {@code length} payload bytes. For frames the server
     * refuses from the length alone, before it reads the mask: payload bytes it never reads would make its close a
     * TCP reset, which may drop the close frame before the client reads it. Small leftovers (the 4 mask bytes here,
     * or the payload of a short unmasked frame) arrive in the same loopback segment as the header and sit in
     * TinyHttpd's input buffer, so they never trigger the reset; do not replace this with a full large payload.
     */
    void frameHeader(boolean fin, int opcode, long length) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        writeHeader(f, fin, opcode, length, true);
        out.write(f.toByteArray());
        out.flush();
    }

    void text(String text) throws IOException {
        frame(true, TEXT, text.getBytes(StandardCharsets.UTF_8), true);
    }

    private static void writeHeader(ByteArrayOutputStream f, boolean fin, int opcode, long len, boolean masked) {
        f.write((fin ? 0x80 : 0) | opcode);
        int maskBit = masked ? 0x80 : 0;
        if (len < 126) {
            f.write(maskBit | (int) len);
        } else if (len <= 0xffff) {
            f.write(maskBit | 126);
            f.write((int) (len >> 8));
            f.write((int) len);
        } else {
            f.write(maskBit | 127);
            for (int i = 7; i >= 0; i--) {
                f.write((int) (len >> (8 * i)));
            }
        }
        if (masked) {
            f.write(1);
            f.write(2);
            f.write(3);
            f.write(4);
        }
    }

    /** Reads one server frame; throws EOFException at end of stream, SocketTimeoutException after 2 s. */
    Frame readFrame() throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            throw new EOFException("end of stream before a frame");
        }
        int b1 = readByte();
        long len = b1 & 0x7f;
        if (len == 126) {
            len = readByte() << 8 | readByte();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = len << 8 | readByte();
            }
        }
        byte[] payload = new byte[(int) len];
        for (int off = 0; off < payload.length; ) {
            int n = in.read(payload, off, payload.length - off);
            if (n < 0) {
                throw new EOFException("end of stream inside a frame");
            }
            off += n;
        }
        return new Frame((b0 & 0x80) != 0, b0 & 0x0f, payload);
    }

    /** Reads frames until one with {@code opcode}, skipping server pings. */
    Frame readFrame(int opcode) throws IOException {
        while (true) {
            Frame f = readFrame();
            if (f.opcode == opcode) {
                return f;
            }
            if (f.opcode != PING) {
                throw new AssertionError("expected opcode " + opcode + " but got " + f.opcode);
            }
        }
    }

    /** True when the server closed the TCP connection: a read returns end of stream (or a reset) within 2 s. */
    boolean endOfStream() throws IOException {
        try {
            return in.read() < 0;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException reset) {
            return true;
        }
    }

    void setReadTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("end of stream inside a frame");
        }
        return b;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
