package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Cookie;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/** The backend status and header copy of {@link Proxy} (FR-PROXY-011). */
public class ProxyResponseHeadersTest {

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final RestApp app = t.app();
    private final List<RawBackend> backends = new ArrayList<>();
    private final List<RestApp> gateways = new ArrayList<>();
    private boolean started;

    @After
    public void tearDown() throws IOException {
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        for (RestApp g : gateways) {
            g.shutdown(2, TimeUnit.SECONDS);
        }
        for (RawBackend b : backends) {
            b.close();
        }
    }

    private RawBackend backend(String rawResponse) throws IOException {
        RawBackend b = RawBackend.answering(rawResponse);
        backends.add(b);
        return b;
    }

    private ClientResponse send(String method, String target) {
        started = true;
        return t.send(method, target);
    }

    /** Every value of {@code name} (case-insensitive) the in-memory client received, in order. */
    private static List<String> values(ClientResponse r, String name) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> h : r.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                out.add(h.getValue());
            }
        }
        return out;
    }

    private static List<Map.Entry<String, String>> lines(String... nameValuePairs) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            out.add(new SimpleImmutableEntry<>(nameValuePairs[i], nameValuePairs[i + 1]));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A Response that records header, append and relay calls; it delegates to a real response when given one.

    /** One recorded call: the method name and its arguments as text. */
    static final class Call {
        final String method;
        final String name;
        final String value;

        Call(String method, String name, String value) {
            this.method = method;
            this.name = name;
            this.value = value;
        }

        @Override
        public String toString() {
            return method + "(" + name + ", " + value + ")";
        }
    }

    static final class RecordingResponse implements Response {
        final List<Call> calls = new ArrayList<>();
        private final @Nullable Response delegate;

        RecordingResponse(@Nullable Response delegate) {
            this.delegate = delegate;
        }

        /** The header and append calls, in call order, as {@code method(name, value)}. */
        List<String> headerCalls() {
            List<String> out = new ArrayList<>();
            for (Call c : calls) {
                if (c.method.equals("header") || c.method.equals("append")) {
                    out.add(c.toString());
                }
            }
            return out;
        }

        /** The names (lower case) passed to header or append. */
        Set<String> headerNames() {
            Set<String> out = new HashSet<>();
            for (Call c : calls) {
                if (c.method.equals("header") || c.method.equals("append")) {
                    out.add(c.name.toLowerCase(Locale.ROOT));
                }
            }
            return out;
        }

        List<Call> relays() {
            List<Call> out = new ArrayList<>();
            for (Call c : calls) {
                if (c.method.equals("relay")) {
                    out.add(c);
                }
            }
            return out;
        }

        @Override
        public Response status(int code) {
            calls.add(new Call("status", String.valueOf(code), ""));
            if (delegate != null) {
                delegate.status(code);
            }
            return this;
        }

        @Override
        public Response header(String name, String value) {
            calls.add(new Call("header", name, value));
            if (delegate != null) {
                delegate.header(name, value);
            }
            return this;
        }

        @Override
        public Response append(String name, String value) {
            calls.add(new Call("append", name, value));
            if (delegate != null) {
                delegate.append(name, value);
            }
            return this;
        }

        @Override
        public Response addCookie(Cookie cookie) {
            calls.add(new Call("addCookie", cookie.getName(), cookie.getValue()));
            if (delegate != null) {
                delegate.addCookie(cookie);
            }
            return this;
        }

        @Override
        public void onAbort(Runnable listener) {
            if (delegate != null) {
                delegate.onAbort(listener);
            }
        }

        @Override
        public void relay(InputStream in, long contentLength) {
            calls.add(new Call("relay", "", String.valueOf(contentLength)));
            if (delegate != null) {
                delegate.relay(in, contentLength);
            } else {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // released either way
                }
            }
        }

        @Override
        public void send(@Nullable Object body) {
            calls.add(new Call("send", "", String.valueOf(body)));
            if (delegate != null) {
                delegate.send(body);
            }
        }

        @Override
        public void html(String body) {
            if (delegate != null) {
                delegate.html(body);
            }
        }

        @Override
        public void stream(InputStream in, String contentType) {
            if (delegate != null) {
                delegate.stream(in, contentType);
            }
        }

        @Override
        public void end() {
            if (delegate != null) {
                delegate.end();
            }
        }

        @Override
        public boolean isCommitted() {
            return delegate != null && delegate.isCommitted();
        }

        @Override
        public void abort(@Nullable Throwable error) {
            if (delegate != null) {
                delegate.abort(error);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // TinyHttpd gateway and raw socket client: the adapter's own status line and framing are observed (D-059, D-060).

    /** A gateway app over a real TinyHttpd socket on port 0. */
    private RestApp gateway() {
        RestApp g = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        gateways.add(g);
        return g;
    }

    /** A raw HTTP/1.1 exchange: the response head (up to the blank line) and the decoded body bytes. */
    static final class RawExchange {
        final String head;
        /** Every byte after the head, before any decoding. */
        final byte[] rest;
        final byte[] body;

        RawExchange(String head, byte[] rest, byte[] body) {
            this.head = head;
            this.rest = rest;
            this.body = body;
        }

        String statusLine() {
            return head.substring(0, head.indexOf("\r\n"));
        }

        /** The header lines after the status line. */
        List<String> headerLines() {
            List<String> out = new ArrayList<>(Arrays.asList(head.split("\r\n")));
            out.remove(0);
            return out;
        }

        boolean hasHeader(String name) {
            for (String line : headerLines()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }

        String all() {
            return head + new String(rest, StandardCharsets.ISO_8859_1);
        }
    }

    /** Sends {@code GET target} with Connection: close to 127.0.0.1:{@code port} and reads until the server closes. */
    private static RawExchange get(int port, String target) throws IOException {
        byte[] raw;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] b = new byte[8192];
            try {
                for (int n; (n = in.read(b)) >= 0; ) {
                    buf.write(b, 0, n);
                }
            } catch (SocketException reset) {
                // a reset after the close counts as the end
            }
            raw = buf.toByteArray();
        }
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int end = text.indexOf("\r\n\r\n");
        if (end < 0) {
            throw new EOFException("no complete response head: " + text);
        }
        String head = text.substring(0, end + 2);
        byte[] rest = Arrays.copyOfRange(raw, end + 4, raw.length);
        RawExchange partial = new RawExchange(head, rest, rest);
        boolean chunked = false;
        for (String line : partial.headerLines()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Transfer-Encoding")
                    && line.substring(colon + 1).toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            }
        }
        return new RawExchange(head, rest, chunked ? dechunk(rest) : rest);
    }

    /** The data of a chunked body; trailer lines after the last chunk are skipped. */
    private static byte[] dechunk(byte[] raw) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int pos = 0;
        while (true) {
            int eol = indexOfCrlf(raw, pos);
            String sizeLine = new String(raw, pos, eol - pos, StandardCharsets.ISO_8859_1);
            int semi = sizeLine.indexOf(';');
            int size = Integer.parseInt((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim(), 16);
            pos = eol + 2;
            if (size == 0) {
                return body.toByteArray();
            }
            body.write(raw, pos, size);
            pos += size + 2;
        }
    }

    private static int indexOfCrlf(byte[] raw, int from) throws EOFException {
        for (int i = from; i + 1 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n') {
                return i;
            }
        }
        throw new EOFException("no CRLF after " + from);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-1

    // FR-PROXY-011 AC-1: backend statuses 200, 400, 404 and 500 each reach the client unchanged
    @Test
    public void fr_PROXY_011_AC1_backendStatusesReachTheClientUnchanged() throws IOException {
        for (int status : new int[] {200, 400, 404, 500}) {
            RawBackend backend = backend(RawBackend.response(status, "s" + status));
            app.use("/s" + status, Proxy.to(backend.url()));
        }
        for (int status : new int[] {200, 400, 404, 500}) {
            ClientResponse r = send("GET", "/s" + status + "/x");
            assertEquals("status " + status, status, r.status());
            assertEquals("s" + status, r.text());
        }
    }

    // FR-PROXY-011 AC-1: a backend 302 with Location: /next reaches the client as that 302, and the backend sees one request
    @Test
    public void fr_PROXY_011_AC1_backend302IsRelayedWithoutFollowingTheRedirect() throws IOException {
        RawBackend backend = backend("HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Length: 0\r\n\r\n");
        app.use("/p", Proxy.to(backend.url()));

        ClientResponse r = send("GET", "/p/x");

        assertEquals(302, r.status());
        assertEquals("the proxy never follows a redirect", 1, backend.requestCount());
        assertEquals("GET /p/x HTTP/1.1", backend.onlyRequest().requestLine());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-2

    // FR-PROXY-011 AC-2: two Set-Cookie lines in order, the same Content-Type, Content-Length and body bytes
    @Test
    public void fr_PROXY_011_AC2_setCookieContentTypeContentLengthAndBodyArriveIntact() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\nSet-Cookie: a=1; Path=/\r\nContent-Type: text/plain; charset=utf-8"
                + "\r\nSet-Cookie: b=2\r\nContent-Length: 5\r\n\r\nhello");
        app.use("/p", Proxy.to(backend.url()));

        ClientResponse r = send("GET", "/p/x");

        assertEquals(200, r.status());
        assertEquals(Arrays.asList("a=1; Path=/", "b=2"), values(r, "Set-Cookie"));
        assertEquals(Collections.singletonList("text/plain; charset=utf-8"), values(r, "Content-Type"));
        assertEquals(Collections.singletonList("5"), values(r, "Content-Length"));
        assertEquals(5, r.contentLength());
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), r.body());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-3

    /** The hop-by-hop backend lines of AC-3, a name listed in Connection, and two end-to-end lines. */
    private static List<Map.Entry<String, String>> hopByHopLines() {
        return lines("Connection", "close, X-Hop", "Keep-Alive", "timeout=99", "Proxy-Connection", "keep-alive",
                "TE", "trailers", "Transfer-Encoding", "chunked", "Upgrade", "websocket",
                "Proxy-Authenticate", "Basic realm=\"b\"", "Proxy-Authorization", "Basic YTpi", "Trailer", "X-Sum",
                "X-Hop", "secret", "X-Keep", "1", "Content-Type", "text/plain");
    }

    // FR-PROXY-011 AC-3: neither header nor append is called for a hop-by-hop line or a name listed in Connection
    @Test
    public void fr_PROXY_011_AC3_copierSkipsHopByHopLinesAndNamesListedInConnection() {
        RecordingResponse rec = new RecordingResponse(null);

        ResponseHeaderCopier.copy(hopByHopLines(), rec);

        assertEquals(Arrays.asList("header(X-Keep, 1)", "header(Content-Type, text/plain)"), rec.headerCalls());
    }

    // FR-PROXY-011 AC-3: Connection tokens are split on commas, trimmed of OWS and compared case-insensitively
    @Test
    public void fr_PROXY_011_AC3_connectionTokensAreTrimmedAndCaseInsensitiveAcrossLines() {
        RecordingResponse rec = new RecordingResponse(null);

        ResponseHeaderCopier.copy(lines("connection", " x-one ,\tX-TWO,, ", "Connection", "x-three",
                "X-One", "1", "x-two", "2", "X-Three", "3", "X-Four", "4"), rec);

        assertEquals(Collections.singletonList("header(X-Four, 4)"), rec.headerCalls());
    }

    // FR-PROXY-011 AC-3: the Proxy itself calls neither header nor append for the hop-by-hop backend lines
    @Test
    public void fr_PROXY_011_AC3_proxyCallsNoHeaderMethodForHopByHopBackendLines() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\nConnection: close, X-Hop\r\nKeep-Alive: timeout=99"
                + "\r\nProxy-Connection: keep-alive\r\nTE: trailers\r\nUpgrade: websocket"
                + "\r\nProxy-Authenticate: Basic realm=\"b\"\r\nProxy-Authorization: Basic YTpi\r\nTrailer: X-Sum"
                + "\r\nX-Hop: secret\r\nX-Keep: 1\r\nContent-Length: 2\r\n\r\nok");
        RecordingResponse[] holder = new RecordingResponse[1];
        Proxy proxy = Proxy.to(backend.url());
        app.use("/p", (req, res, next) -> {
            holder[0] = new RecordingResponse(res);
            proxy.handle(req, holder[0], next);
        });

        ClientResponse r = send("GET", "/p/x");

        assertEquals(200, r.status());
        assertEquals("ok", r.text());
        assertEquals(Collections.singleton("x-keep"), holder[0].headerNames());
    }

    // FR-PROXY-011 AC-3: over a socket the client receives none of the backend's hop-by-hop lines nor the X-Hop line
    @Test(timeout = 30000)
    public void fr_PROXY_011_AC3_overASocketTheClientReceivesNoBackendHopByHopLine() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\nConnection: close, X-Hop\r\nKeep-Alive: timeout=99"
                + "\r\nProxy-Connection: keep-alive\r\nTE: trailers\r\nUpgrade: websocket"
                + "\r\nProxy-Authenticate: Basic realm=\"b\"\r\nProxy-Authorization: Basic YTpi"
                + "\r\nX-Hop: secret\r\nX-Keep: 1\r\nContent-Length: 2\r\n\r\nok");
        RestApp g = gateway();
        g.use("/p", Proxy.to(backend.url()));
        int port = g.listen(0).port();

        RawExchange r = get(port, "/p/x");

        assertTrue(r.statusLine(), r.statusLine().startsWith("HTTP/1.1 200 "));
        assertEquals("ok", new String(r.body, StandardCharsets.ISO_8859_1));
        assertTrue(r.head, r.hasHeader("X-Keep"));
        // TinyHttpd drops a Keep-Alive line by itself, so the recording-Response tests above are what pin Keep-Alive.
        assertFalse(r.head, r.head.contains("timeout=99"));
        for (String name : new String[] {"Proxy-Connection", "TE", "Upgrade", "Proxy-Authenticate",
                "Proxy-Authorization", "X-Hop"}) {
            assertFalse(name + " in " + r.head, r.hasHeader(name));
        }
    }

    private static final String CHUNKED_WITH_TRAILER = "HTTP/1.1 200 OK\r\nContent-Type: text/plain"
            + "\r\nTransfer-Encoding: chunked\r\nTrailer: X-Sum\r\n\r\n"
            + "5;ext=1\r\nhello\r\n6\r\n world\r\n0\r\nX-Sum: abc123\r\n\r\n";

    // FR-PROXY-011 AC-3: over a socket a chunked backend body with a Trailer header and trailer fields arrives without them
    @Test(timeout = 30000)
    public void fr_PROXY_011_AC3_overASocketChunkedBodyArrivesWithoutTrailerHeaderOrTrailerFields() throws IOException {
        RawBackend backend = backend(CHUNKED_WITH_TRAILER);
        RestApp g = gateway();
        g.use("/p", Proxy.to(backend.url()));
        int port = g.listen(0).port();

        RawExchange r = get(port, "/p/x");

        assertTrue(r.statusLine(), r.statusLine().startsWith("HTTP/1.1 200 "));
        assertEquals("hello world", new String(r.body, StandardCharsets.ISO_8859_1));
        assertFalse("no Trailer header: " + r.head, r.hasHeader("Trailer"));
        assertFalse("no trailer field anywhere: " + r.all(), r.all().contains("X-Sum"));
        assertFalse("no trailer value anywhere: " + r.all(), r.all().contains("abc123"));
    }

    // FR-PROXY-011 AC-3: a chunked backend body is decoded and relayed with an unknown length (in-memory view)
    @Test
    public void fr_PROXY_011_AC3_chunkedBackendBodyIsDecodedWithoutTrailerFields() throws IOException {
        RawBackend backend = backend(CHUNKED_WITH_TRAILER);
        RecordingResponse[] holder = new RecordingResponse[1];
        Proxy proxy = Proxy.to(backend.url());
        app.use("/p", (req, res, next) -> {
            holder[0] = new RecordingResponse(res);
            proxy.handle(req, holder[0], next);
        });

        ClientResponse r = send("GET", "/p/x");

        assertEquals(200, r.status());
        assertEquals("hello world", r.text());
        assertTrue(values(r, "Trailer").isEmpty());
        assertTrue(values(r, "X-Sum").isEmpty());
        assertTrue(values(r, "Transfer-Encoding").isEmpty());
        assertEquals(1, holder[0].relays().size());
        assertEquals("-1", holder[0].relays().get(0).value);
    }

    // FR-PROXY-011 AC-3: a chunk size written with leading zeros is still the chunk size (RFC 9112 chunk-size = 1*HEXDIG)
    @Test
    public void fr_PROXY_011_AC3_chunkSizeWithLeadingZerosIsDecoded() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "00000000000000000005\r\nhello\r\n0000\r\n\r\n");
        app.use("/p", Proxy.to(backend.url()));

        ClientResponse r = send("GET", "/p/x");

        assertEquals(200, r.status());
        assertFalse("the body arrives whole", r.abnormallyClosed());
        assertEquals("hello", r.text());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-4

    // FR-PROXY-011 AC-4: a backend response without Content-Type reaches the client without Content-Type
    @Test
    public void fr_PROXY_011_AC4_noBackendContentTypeMeansNoClientContentType() throws IOException {
        RawBackend backend = backend(RawBackend.response(200, "raw"));
        app.use("/p", Proxy.to(backend.url()));

        ClientResponse r = send("GET", "/p/x");

        assertEquals(200, r.status());
        assertEquals("raw", r.text());
        assertNull(r.header("Content-Type"));
    }

    // FR-PROXY-011 AC-4: over a socket the gateway adds no Content-Type the backend did not send
    @Test(timeout = 30000)
    public void fr_PROXY_011_AC4_overASocketNoBackendContentTypeMeansNoClientContentType() throws IOException {
        RawBackend backend = backend(RawBackend.response(200, "raw"));
        RestApp g = gateway();
        g.use("/p", Proxy.to(backend.url()));
        int port = g.listen(0).port();

        RawExchange r = get(port, "/p/x");

        assertEquals("raw", new String(r.body, StandardCharsets.ISO_8859_1));
        assertFalse(r.head, r.hasHeader("Content-Type"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-5

    // FR-PROXY-011 AC-5: Content-Length never goes to header or append; it reaches relay as the length argument only
    @Test
    public void fr_PROXY_011_AC5_contentLengthReachesRelayAsItsLengthArgumentOnly() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello");
        RecordingResponse[] holder = new RecordingResponse[1];
        Proxy proxy = Proxy.to(backend.url());
        app.use("/p", (req, res, next) -> {
            holder[0] = new RecordingResponse(res);
            proxy.handle(req, holder[0], next);
        });

        ClientResponse r = send("GET", "/p/x");

        assertEquals("hello", r.text());
        assertFalse(holder[0].headerCalls().toString(), holder[0].headerNames().contains("content-length"));
        assertEquals(1, holder[0].relays().size());
        assertEquals("5", holder[0].relays().get(0).value);
        assertTrue("headers are copied before relay", holder[0].calls.get(holder[0].calls.size() - 1).method
                .equals("relay"));
    }

    // FR-PROXY-011 AC-5: the copier passes no Content-Length line to header or append
    @Test
    public void fr_PROXY_011_AC5_copierNeverPassesContentLength() {
        RecordingResponse rec = new RecordingResponse(null);

        ResponseHeaderCopier.copy(lines("Content-Length", "5", "content-length", "5", "X-A", "1"), rec);

        assertEquals(Collections.singletonList("header(X-A, 1)"), rec.headerCalls());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-6, AC-7, AC-8

    // FR-PROXY-011 AC-6: the backend's Access-Control-Allow-Origin replaces the app's, and the app's X-App stays
    @Test
    public void fr_PROXY_011_AC6_backendValueReplacesTheAppValueAndOtherAppHeadersStay() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: https://a.example"
                + "\r\nContent-Length: 2\r\n\r\nok");
        app.use((req, res, next) -> {
            res.header("Access-Control-Allow-Origin", "*");
            res.header("X-App", "1");
            next.run();
        });
        app.use("/p", Proxy.to(backend.url()));

        ClientResponse r = send("GET", "/p/x");

        assertEquals(Collections.singletonList("https://a.example"), values(r, "Access-Control-Allow-Origin"));
        assertEquals(Collections.singletonList("1"), values(r, "X-App"));
    }

    // FR-PROXY-011 AC-7: an app cookie and two backend Set-Cookie lines give three Set-Cookie lines
    @Test
    public void fr_PROXY_011_AC7_appCookieAndTwoBackendSetCookieLinesGiveThree() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2"
                + "\r\nContent-Length: 2\r\n\r\nok");
        app.use((req, res, next) -> {
            res.addCookie(new Cookie("app", "0"));
            next.run();
        });
        app.use("/p", Proxy.to(backend.url()));

        ClientResponse r = send("GET", "/p/x");

        List<String> cookies = values(r, "Set-Cookie");
        assertEquals(cookies.toString(), 3, cookies.size());
        assertTrue(cookies.toString(), cookies.get(0).startsWith("app=0"));
        assertEquals(Arrays.asList("a=1", "b=2"), cookies.subList(1, 3));
    }

    // FR-PROXY-011 AC-8: an app Vary: Origin and a backend Vary: Accept-Encoding give a Vary holding both tokens
    @Test
    public void fr_PROXY_011_AC8_appVaryAndBackendVaryAreBothKept() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\nVary: Accept-Encoding\r\nContent-Length: 2\r\n\r\nok");
        app.use((req, res, next) -> {
            res.header("Vary", "Origin");
            next.run();
        });
        app.use("/p", Proxy.to(backend.url()));

        ClientResponse r = send("GET", "/p/x");

        Set<String> tokens = new HashSet<>();
        for (String v : values(r, "Vary")) {
            for (String token : v.split(",")) {
                tokens.add(token.trim());
            }
        }
        assertEquals(new HashSet<>(Arrays.asList("Origin", "Accept-Encoding")), tokens);
    }

    // FR-PROXY-011 AC-8: every Vary and Set-Cookie line is appended, while the first line of another name replaces
    @Test
    public void fr_PROXY_011_AC8_copierAppendsEveryVaryAndSetCookieLine() {
        RecordingResponse rec = new RecordingResponse(null);

        ResponseHeaderCopier.copy(lines("Vary", "Accept-Encoding", "Set-Cookie", "a=1", "X-A", "1", "Vary", "Cookie",
                "X-A", "2"), rec);

        assertEquals(Arrays.asList("append(Vary, Accept-Encoding)", "append(Set-Cookie, a=1)", "header(X-A, 1)",
                "append(Vary, Cookie)", "append(X-A, 2)"), rec.headerCalls());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-9

    // FR-PROXY-011 AC-9: HTTP/1.1 404 Nope reaches the client as 404 with the adapter's own reason phrase
    @Test(timeout = 30000)
    public void fr_PROXY_011_AC9_backendReasonPhraseIsReplacedByTheAdapterPhrase() throws IOException {
        RawBackend backend = backend("HTTP/1.1 404 Nope\r\nContent-Length: 2\r\n\r\nno");
        RestApp g = gateway();
        g.get("/direct", (req, res) -> res.status(404).send("no"));
        g.use("/p", Proxy.to(backend.url()));
        int port = g.listen(0).port();

        RawExchange proxied = get(port, "/p/x");
        RawExchange direct = get(port, "/direct");

        assertTrue(direct.statusLine(), direct.statusLine().startsWith("HTTP/1.1 404 "));
        assertEquals("the adapter's own 404 status line", direct.statusLine(), proxied.statusLine());
        assertFalse(proxied.statusLine(), proxied.statusLine().contains("Nope"));
        assertEquals("no", new String(proxied.body, StandardCharsets.ISO_8859_1));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-10

    // FR-PROXY-011 AC-10: a lower-case backend access-control-allow-origin replaces the app's Access-Control-Allow-Origin
    @Test
    public void fr_PROXY_011_AC10_lowerCaseBackendNameReplacesTheAppHeader() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\naccess-control-allow-origin: https://a.example"
                + "\r\nContent-Length: 2\r\n\r\nok");
        app.use((req, res, next) -> {
            res.header("Access-Control-Allow-Origin", "*");
            next.run();
        });
        app.use("/p", Proxy.to(backend.url()));

        ClientResponse r = send("GET", "/p/x");

        assertEquals(Collections.singletonList("https://a.example"), values(r, "Access-Control-Allow-Origin"));
    }

    // FR-PROXY-011 AC-10: backend set-cookie and Set-Cookie lines are both appended next to an app cookie
    @Test
    public void fr_PROXY_011_AC10_setCookieInAnyCaseIsAppendedNextToTheAppCookie() throws IOException {
        RawBackend backend = backend("HTTP/1.1 200 OK\r\nset-cookie: a=1\r\nSet-Cookie: b=2"
                + "\r\nContent-Length: 2\r\n\r\nok");
        app.use((req, res, next) -> {
            res.addCookie(new Cookie("app", "0"));
            next.run();
        });
        app.use("/p", Proxy.to(backend.url()));

        ClientResponse r = send("GET", "/p/x");

        List<String> cookies = values(r, "Set-Cookie");
        assertEquals(cookies.toString(), 3, cookies.size());
        assertTrue(cookies.toString(), cookies.get(0).startsWith("app=0"));
        assertTrue(cookies.toString(), cookies.contains("a=1"));
        assertTrue(cookies.toString(), cookies.contains("b=2"));
    }

    // FR-PROXY-011 AC-10: lines that differ only in letter case are one name: the first replaces, the later append
    @Test
    public void fr_PROXY_011_AC10_copierTreatsNamesCaseInsensitively() {
        RecordingResponse rec = new RecordingResponse(null);

        ResponseHeaderCopier.copy(lines("x-a", "1", "X-A", "2", "SET-COOKIE", "c=3"), rec);

        assertEquals(Arrays.asList("header(x-a, 1)", "append(X-A, 2)", "append(SET-COOKIE, c=3)"),
                rec.headerCalls());
    }
}
