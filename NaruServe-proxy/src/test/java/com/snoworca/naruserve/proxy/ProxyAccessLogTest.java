package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Test;

/**
 * Proxied requests in the app's access log (FR-PROXY-021): the proxy writes nothing to the log itself, so a proxied
 * request gets the same line as any other request, and {@code in=} counts the body bytes it read through
 * {@code req.bodyStream(...)}. Every case runs over real TinyHttpd sockets with the default access log options.
 */
public class ProxyAccessLogTest {

    private static final Pattern IN = Pattern.compile(" in=(\\d+) ");

    private final List<Closeable> closeables = new ArrayList<>();
    private final List<RestApp> started = new ArrayList<>();
    private final RecordingLogger log = new RecordingLogger();

    @After
    public void tearDown() throws IOException {
        for (RestApp a : started) {
            a.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    /** A backend that answers 200 with body {@code ok} and records what it received. */
    private RawBackend okBackend() throws IOException {
        RawBackend b = RawBackend.answering(RawBackend.response(200, "ok"));
        closeables.add(b);
        return b;
    }

    /** A TinyHttpd app logging to {@link #log}, not yet listening. */
    private RestApp tinyApp() {
        RestApp g = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec()).logger(log)
                .build();
        started.add(g);
        return g;
    }

    /** A port nothing listens on: bound once, then closed. */
    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /**
     * Writes {@code head} (the request line and header lines without the blank line; {@code Host} and
     * {@code Connection: close} are added) and {@code body} to the TinyHttpd port and returns everything the server
     * sent until it closed, as ISO-8859-1 text.
     */
    private static String exchange(int port, String head, byte[] body) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(20000);
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            out.write((head + "\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            return readToEnd(s.getInputStream());
        }
    }

    /** Every byte until the server closes, as ISO-8859-1 text. */
    private static String readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        try {
            for (int n; (n = in.read(b)) >= 0; ) {
                buf.write(b, 0, n);
            }
        } catch (SocketException reset) {
            // a reset after the close counts as the end
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /** {@code body} in the chunked coding with chunks of the given sizes, cycled, and the last chunk. */
    private static byte[] chunkedCoding(byte[] body, int... sizes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        for (int off = 0; off < body.length; i++) {
            int n = Math.min(sizes[i % sizes.length], body.length - off);
            byte[] size = (Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
            out.write(size, 0, size.length);
            out.write(body, off, n);
            out.write('\r');
            out.write('\n');
            off += n;
        }
        byte[] last = "0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        out.write(last, 0, last.length);
        return out.toByteArray();
    }

    /** {@code n} bytes of {@code 'a'..'z'} repeating. */
    private static byte[] body(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ('a' + i % 26);
        }
        return b;
    }

    /** Waits for exactly one access log line of {@code method path} and returns it; fails on none or several. */
    private String onlyLine(String method, String path) throws InterruptedException {
        String regex = Pattern.quote(method + " " + path + " ") + ".*";
        RecordingLogger.awaitMatching(log::info, regex, 1);
        Thread.sleep(50);
        List<String> found = RecordingLogger.matching(log.info(), regex);
        assertEquals(found.toString(), 1, found.size());
        return found.get(0);
    }

    /** {@code line} with its path replaced by {@code <path>}, its duration by {@code <n>ms}, its rid by {@code <rid>}. */
    private static String masked(String line, String path) {
        String rest = line.replaceFirst(Pattern.quote(" " + path + " "), " <path> ");
        return rest.replaceAll(" \\d+ms", " <n>ms").replaceAll(" rid=\\S+", " rid=<rid>");
    }

    private static long in(String line) {
        Matcher m = IN.matcher(line);
        assertTrue(line, m.find());
        return Long.parseLong(m.group(1));
    }

    // ---- AC-1 ----

    // FR-PROXY-021 AC-1: a proxied GET answered 200 by the backend logs one line in the same format, with the same
    // fields, as a GET answered 200 by a plain route of the same app
    @Test(timeout = 30000)
    public void fr_PROXY_021_AC1_proxiedGetLogsTheSameLineAsAPlainRoute() throws Exception {
        RawBackend backend = okBackend();
        RestApp app = tinyApp();
        app.get("/plain", (req, res) -> res.send("ok"));
        app.use("/api", Proxy.to(backend.url()));
        int port = app.listen(0).port();

        String proxied = exchange(port, "GET /api/x HTTP/1.1", new byte[0]);
        String plain = exchange(port, "GET /plain HTTP/1.1", new byte[0]);

        assertTrue(proxied, proxied.startsWith("HTTP/1.1 200 "));
        assertTrue(plain, plain.startsWith("HTTP/1.1 200 "));
        String proxiedLine = onlyLine("GET", "/api/x");
        String plainLine = onlyLine("GET", "/plain");
        assertTrue(proxiedLine, proxiedLine.matches("GET /api/x 200 \\d+ms in=0 out=2 rid=\\S+"));
        assertEquals(masked(plainLine, "/plain"), masked(proxiedLine, "/api/x"));
        assertEquals("access log lines: " + log.info(), 2, log.info().size());
    }

    // ---- AC-2 ----

    // FR-PROXY-021 AC-2: a proxied POST whose 1000-byte Content-Length body is forwarded to the backend logs in=1000
    @Test(timeout = 30000)
    public void fr_PROXY_021_AC2_proxiedPostOf1000BytesLogsIn1000() throws Exception {
        RawBackend backend = okBackend();
        RestApp app = tinyApp();
        app.use("/api", Proxy.to(backend.url()));
        int port = app.listen(0).port();
        byte[] body = body(1000);

        String response = exchange(port, "POST /api/x HTTP/1.1\r\nContent-Type: application/octet-stream"
                + "\r\nContent-Length: 1000", body);

        assertTrue(response, response.startsWith("HTTP/1.1 200 "));
        assertTrue(Arrays.equals(body, backend.onlyRequest().body));
        String line = onlyLine("POST", "/api/x");
        assertTrue(line, line.matches("POST /api/x 200 \\d+ms in=1000 out=2 rid=\\S+"));
        assertEquals(line, 1000, in(line));
    }

    // FR-PROXY-021 AC-2: a proxied POST whose 5000-byte chunked body is forwarded to the backend logs in=5000, the
    // decoded body bytes and not the chunked coding on the wire
    @Test(timeout = 30000)
    public void fr_PROXY_021_AC2_proxiedChunkedPostOf5000BytesLogsIn5000() throws Exception {
        RawBackend backend = okBackend();
        RestApp app = tinyApp();
        app.use("/api", Proxy.to(backend.url()));
        int port = app.listen(0).port();
        byte[] body = body(5000);

        String response = exchange(port, "POST /api/x HTTP/1.1\r\nContent-Type: application/octet-stream"
                + "\r\nTransfer-Encoding: chunked", chunkedCoding(body, 700, 1, 2048));

        assertTrue(response, response.startsWith("HTTP/1.1 200 "));
        assertTrue(Arrays.equals(body, backend.onlyRequest().body));
        String line = onlyLine("POST", "/api/x");
        assertTrue(line, line.matches("POST /api/x 200 \\d+ms in=5000 out=2 rid=\\S+"));
        assertEquals(line, 5000, in(line));
    }

    // ---- AC-3 ----

    // FR-PROXY-021 AC-3: a proxied request ending in 502 because the backend is down (nothing listens on its port)
    // logs status 502 in the same format as a plain route that fails with 502
    @Test(timeout = 30000)
    public void fr_PROXY_021_AC3_backendDown502LogsLikeOtherFailedRequests() throws Exception {
        RestApp app = tinyApp();
        app.get("/fail", (req, res) -> {
            throw new HttpException(502);
        });
        app.use("/api", Proxy.to("http://127.0.0.1:" + closedPort()));
        int port = app.listen(0).port();

        String proxied = exchange(port, "GET /api/x HTTP/1.1", new byte[0]);
        String plain = exchange(port, "GET /fail HTTP/1.1", new byte[0]);

        assertTrue(proxied, proxied.startsWith("HTTP/1.1 502 "));
        assertTrue(plain, plain.startsWith("HTTP/1.1 502 "));
        String proxiedLine = onlyLine("GET", "/api/x");
        String plainLine = onlyLine("GET", "/fail");
        assertTrue(proxiedLine, proxiedLine.matches("GET /api/x 502 \\d+ms in=0 out=\\d+ rid=\\S+"));
        // The error page carries each request's own stack trace, so only the out= value is masked.
        assertEquals(masked(plainLine, "/fail").replaceFirst(" out=\\d+", " out=<o>"),
                masked(proxiedLine, "/api/x").replaceFirst(" out=\\d+", " out=<o>"));
    }
}
