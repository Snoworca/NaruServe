package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.Cookie;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * One app definition run on Jetty through {@link NaruServeServlet} and on {@link InMemoryHttpAdapter}, plus a raw
 * socket client: requests are sent as written (undecoded targets) and the response is read until the server closes.
 */
final class SharedApp {

    /** The bytes {@code /file} serves: 100000 bytes of a repeating pattern. */
    static final byte[] FILE_BYTES = pattern(100000);

    private SharedApp() {
    }

    static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 31 + i / 251);
        }
        return b;
    }

    /** Decodes only to String (the raw JSON text); encodes null, numbers, booleans and strings without escapes. */
    static final JsonCodec CODEC = new JsonCodec() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(Reader reader, Type type) throws IOException {
            if (type != String.class) {
                throw new UnsupportedOperationException("SharedApp codec decodes only to String");
            }
            StringBuilder b = new StringBuilder();
            char[] buf = new char[256];
            for (int n; (n = reader.read(buf)) > 0; ) {
                b.append(buf, 0, n);
            }
            return (T) b.toString();
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) throws IOException {
            writer.write(value instanceof String ? "\"" + value + "\"" : String.valueOf(value));
        }
    };

    /** The routes both engines serve; {@code file} is the file behind {@code /file} and {@code /download}. */
    static void define(RestApp app, File file) {
        app.use((req, res, next) -> {
            req.attribute("trail", "m1");
            next.run();
        });
        app.use((req, res, next) -> {
            req.attribute("trail", req.attribute("trail", String.class) + ",m2");
            next.run();
        });
        app.get("/users/:id", (req, res) -> res.send("user " + req.param("id") + " q=" + req.query("q")));
        app.post("/json", (req, res) -> res.json("got " + req.body(String.class)));
        app.get("/cookie", (req, res) -> {
            Cookie c = req.cookie("s");
            res.addCookie(new Cookie("t", "set"));
            res.send("cookie " + (c == null ? "none" : c.getValue()));
        });
        app.get("/trail", (req, res) -> res.send(req.attribute("trail", String.class) + ",h"));
        app.get("/boom", (req, res) -> {
            throw new IllegalStateException("boom");
        });
        app.get("/teapot", (req, res) -> {
            throw new HttpException(418, "short and stout");
        });
        // Leaves every other error unanswered, so the core writes the standard error body.
        app.onError((error, req, res) -> {
            if (error instanceof HttpException && ((HttpException) error).status() == 418) {
                res.status(418).send("handled " + error.getMessage());
            }
        });
        app.get("/file", (req, res) -> res.file(file));
        app.get("/download", (req, res) -> res.download(file, "data.bin"));
    }

    /** A temporary file holding {@link #FILE_BYTES}, deleted on exit. */
    static File tempFile() throws IOException {
        File f = File.createTempFile("naruserve-shared", ".bin");
        f.deleteOnExit();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(FILE_BYTES);
        }
        return f;
    }

    /** An app over {@code adapter} with {@link #CODEC}, configured by {@code routes}, already listening. */
    static RestApp listen(com.snoworca.naruserve.spi.HttpAdapter adapter, Consumer<RestApp> routes) {
        return listen(adapter, null, routes);
    }

    /** As {@link #listen(com.snoworca.naruserve.spi.HttpAdapter, Consumer)}, logging to {@code log} when it is given. */
    static RestApp listen(com.snoworca.naruserve.spi.HttpAdapter adapter, @Nullable Logger log,
            Consumer<RestApp> routes) {
        RestApp.Builder builder = RestApp.builder().httpAdapter(adapter).jsonCodec(CODEC);
        if (log != null) {
            builder.logger(log);
        }
        RestApp app = builder.build();
        routes.accept(app);
        app.listen(0);
        return app;
    }

    /** Starts {@code routes} on a new ServletAdapter behind {@code container} at {@code pattern}. */
    static RestApp onJetty(ContainerRule container, String pattern, Consumer<RestApp> routes) throws Exception {
        return onJetty(container, new ServletAdapter(), pattern, null, routes);
    }

    /** Starts {@code routes} on {@code adapter} behind {@code container} at {@code pattern}, logging to {@code log}. */
    static RestApp onJetty(ContainerRule container, ServletAdapter adapter, String pattern, @Nullable Logger log,
            Consumer<RestApp> routes) throws Exception {
        RestApp app = listen(adapter, log, routes);
        container.servlet(new NaruServeServlet(adapter, InitParams.prefix(pattern)), pattern);
        return app;
    }

    /** One parsed response: status, headers in wire order and the de-chunked body. */
    static final class Reply {
        final int status;
        final List<Map.Entry<String, String>> headers;
        final byte[] body;

        Reply(int status, List<Map.Entry<String, String>> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        @Nullable String header(String name) {
            List<String> values = headers(name);
            return values.isEmpty() ? null : values.get(0);
        }

        List<String> headers(String name) {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, String> h : headers) {
                if (h.getKey().equalsIgnoreCase(name)) {
                    out.add(h.getValue());
                }
            }
            return out;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** True when the body is the standard error body with a code matching {@code codeRegex} and a 16 hex request id. */
        boolean isStandardError(String codeRegex) {
            return text().matches("\\{\"error\":\\{\"code\":\"" + codeRegex + "\",\"message\":\"[^\"]*\","
                    + "\"requestId\":\"[0-9a-f]{16}\"}}");
        }
    }

    /**
     * Sends {@code method target} with {@code headerPairs} (name, value, ...) and {@code body}, adding Host,
     * Connection: close and a Content-Length when a body is given.
     */
    static Reply http(int port, String method, String target, @Nullable byte[] body, String... headerPairs)
            throws IOException {
        StringBuilder head = new StringBuilder(method + " " + target + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Connection: close\r\n");
        for (int i = 0; i < headerPairs.length; i += 2) {
            head.append(headerPairs[i]).append(": ").append(headerPairs[i + 1]).append("\r\n");
        }
        if (body != null) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("\r\n");
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) {
            req.write(body);
        }
        return raw(port, req.toByteArray());
    }

    /** Writes {@code request} as given and parses everything the server sends until it closes. */
    static Reply raw(int port, byte[] request) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10000);
            s.getOutputStream().write(request);
            s.getOutputStream().flush();
            return parse(readAll(s.getInputStream()));
        }
    }

    /**
     * A GET on a persistent connection (no Connection: close), read until its framing (chunked or Content-Length)
     * says the body ended; the socket is closed afterwards.
     */
    static Reply keepAliveGet(int port, String target) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10000);
            s.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = s.getInputStream();
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (true) {
                int n = in.read(buf);
                if (n < 0) {
                    throw new IOException("connection closed before the framed body ended");
                }
                acc.write(buf, 0, n);
                byte[] all = acc.toByteArray();
                if (indexOf(all, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0) < 0) {
                    continue;
                }
                try {
                    Reply r = parse(all);
                    String length = r.header("Content-Length");
                    if (length == null || r.body.length >= Long.parseLong(length)) {
                        return r;
                    }
                } catch (IOException | IndexOutOfBoundsException truncated) {
                    // the chunked body is not complete yet
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static Reply parse(byte[] all) throws IOException {
        int end = indexOf(all, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0);
        if (end < 0) {
            throw new IOException("no complete response head in " + all.length + " bytes");
        }
        String[] lines = new String(all, 0, end, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            headers.add(new SimpleImmutableEntry<>(lines[i].substring(0, c), lines[i].substring(c + 1).trim()));
        }
        byte[] rest = new byte[all.length - end - 4];
        System.arraycopy(all, end + 4, rest, 0, rest.length);
        Reply r = new Reply(status, headers, rest);
        return "chunked".equalsIgnoreCase(r.header("Transfer-Encoding"))
                ? new Reply(status, headers, dechunk(rest)) : r;
    }

    private static byte[] dechunk(byte[] in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (true) {
            int eol = indexOf(in, new byte[] {'\r', '\n'}, pos);
            if (eol < 0) {
                throw new IOException("truncated chunked body");
            }
            String size = new String(in, pos, eol - pos, StandardCharsets.ISO_8859_1);
            int semi = size.indexOf(';');
            int n = Integer.parseInt((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
            if (n == 0) {
                return out.toByteArray();
            }
            out.write(in, eol + 2, n);
            pos = eol + 2 + n + 2;
        }
    }

    private static int indexOf(byte[] a, byte[] p, int from) {
        outer:
        for (int i = from; i <= a.length - p.length; i++) {
            for (int j = 0; j < p.length; j++) {
                if (a[i + j] != p[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Looks at {@code cond} every 5 ms until it holds or {@code timeout} has passed; true when it held at the last
     * look.
     */
    static boolean until(BooleanSupplier cond, long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.nanoTime() + unit.toNanos(timeout);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() >= end) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    /** The same request on the in-memory adapter, as a {@link Reply}. */
    static Reply inMemory(InMemoryHttpAdapter adapter, String method, String target, @Nullable byte[] body,
            String... headerPairs) {
        ClientRequest r = ClientRequest.of(method, target);
        r.header("Host", "localhost");
        for (int i = 0; i < headerPairs.length; i += 2) {
            r.header(headerPairs[i], headerPairs[i + 1]);
        }
        if (body != null) {
            r.header("Content-Length", Integer.toString(body.length));
            r.body(body);
        }
        ClientResponse c = adapter.send(r);
        return new Reply(c.status(), c.headers(), c.body());
    }
}
