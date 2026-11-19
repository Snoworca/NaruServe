package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Test;

/**
 * End-to-end integration of the requester's API-gateway use case (docs/naruserve-proxy-request.md §1.1, §1.2, §7)
 * over real processes' worth of sockets: a NaruServe gateway app on TinyHttpd on a free port mounts {@link Proxy}
 * exactly as §7 does ({@code /api/orders/jobs} with the shared defaults registered before {@code /api/orders} with
 * {@code retries(2)}, a fixed {@code X-Gateway-Source}, a total timeout longer than the read timeout, and an
 * {@code onError} that answers JSON by {@link ProxyException#kind()}). Backends are a JDK
 * {@code com.sun.net.httpserver.HttpServer} (what the requester used on 0.11.0), a raw {@link ServerSocket} where
 * exact bytes or silence are needed, and an {@code HttpsServer} with a private CA. The client is a raw socket or
 * {@link HttpURLConnection}; nothing goes through an in-memory adapter.
 */
public class GatewayEndToEndTest {

    private static final long MIB = 1024L * 1024L;

    private final List<RestApp> apps = new CopyOnWriteArrayList<>();
    private final List<Closeable> closeables = new CopyOnWriteArrayList<>();
    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    private final List<ExecutorService> executors = new CopyOnWriteArrayList<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();

    @After
    public void tearDown() throws Exception {
        for (RestApp app : apps) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        for (HttpServer s : servers) {
            s.stop(0);
        }
        for (ExecutorService e : executors) {
            e.shutdownNow();
        }
        for (Closeable c : closeables) {
            try {
                c.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    // =====================================================================================================
    // The gateway of §7
    // =====================================================================================================

    /**
     * Starts the §7 gateway towards {@code ordersUrl} on a free port and returns the port. {@code extra} runs first
     * and may configure the app (trust settings) and add further mounts; it receives the shared {@code orders}.
     */
    private int gateway(String ordersUrl, BiConsumer<RestApp, Proxy> extra) {
        RestApp app = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new MapJsonCodec())
                .logger(new RecordingLogger()).build();
        apps.add(app);
        // The public host names clients use for the gateway (TinyHttpd answers 421 to other names).
        app.server(o -> o.allowedHosts("gw.example", "g"));

        // The settings the mounts share (§7), with timeouts of a few seconds for the test.
        Proxy orders = Proxy.to(ordersUrl)
                .requestHeader("X-Gateway-Source", "gateway")
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .totalTimeout(20, TimeUnit.SECONDS);
        extra.accept(app, orders);

        // Paths that must not be retried keep the defaults and are registered before the general mount.
        app.use("/api/orders/jobs", orders);
        app.use("/api/orders", orders.retries(2));
        app.get("/health", (req, res) -> res.send("ok"));

        app.onError((error, req, res) -> {
            errors.add(error);
            if (error instanceof ProxyException) {
                ProxyException e = (ProxyException) error;
                Map<String, String> body = new LinkedHashMap<>();
                body.put("error", "upstream");
                body.put("kind", e.kind().name());
                res.status(e.status()).json(body);
            } else if (error instanceof HttpException) {
                res.status(((HttpException) error).status()).json(Collections.singletonMap("error", "request"));
            } else {
                res.status(500).json(Collections.singletonMap("error", "internal"));
            }
        });
        return app.listen(0).port();
    }

    private int gateway(String ordersUrl) {
        return gateway(ordersUrl, (app, orders) -> { });
    }

    // =====================================================================================================
    // 1. JSON body byte-identical (§1.2 row 1)
    // =====================================================================================================

    // FR-PROXY-006 AC-1, FR-PROXY-007 AC-4: {"limit" : 5} reaches the backend byte for byte, not {"limit":5.0}
    @Test(timeout = 60000)
    public void fr_PROXY_006_AC1_jsonBodyArrivesByteIdentical() throws Exception {
        Recorder rec = new Recorder();
        HttpServer backend = httpServer(rec.handler(200, "application/json", "{\"ok\":true}"));
        int port = gateway(url(backend));
        byte[] json = "{\"limit\" : 5}".getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/orders/query").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(json.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(json);
        }
        assertEquals(200, c.getResponseCode());
        assertEquals("{\"ok\":true}", new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
        assertEquals("application/json", c.getHeaderField("Content-Type"));

        Recorded r = rec.only();
        assertEquals("POST", r.method);
        assertEquals("/api/orders/query", r.rawTarget);
        assertArrayEquals(json, r.body);
        assertEquals("{\"limit\" : 5}", new String(r.body, StandardCharsets.UTF_8));
        assertEquals(Collections.singletonList("application/json"), r.headers.get("content-type"));
        assertEquals(Collections.singletonList(Integer.toString(json.length)), r.headers.get("content-length"));
    }

    // =====================================================================================================
    // 2. urlencoded form body byte-identical (§1.2 row 2)
    // =====================================================================================================

    // FR-PROXY-007 AC-7, FR-PROXY-006 AC-1: an urlencoded form body arrives byte for byte, not empty
    @Test(timeout = 60000)
    public void fr_PROXY_007_AC7_urlencodedFormBodyArrivesByteIdentical() throws Exception {
        Recorder rec = new Recorder();
        HttpServer backend = httpServer(rec.handler(200, "text/plain", "form ok"));
        int port = gateway(url(backend));
        String form = "a=1&name=%ED%95%9C%EA%B8%80&q=x+y&a=2&empty=&flag";
        byte[] bytes = form.getBytes(StandardCharsets.US_ASCII);

        Reply reply = call(port, "POST /api/orders/search?page=2 HTTP/1.1\r\nHost: gw.example\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n", bytes);

        assertEquals(200, reply.status);
        assertEquals("form ok", reply.text());
        Recorded r = rec.only();
        assertEquals("/api/orders/search?page=2", r.rawTarget);
        assertArrayEquals(bytes, r.body);
        assertEquals(Collections.singletonList("application/x-www-form-urlencoded"), r.headers.get("content-type"));
    }

    // =====================================================================================================
    // 3. Arbitrary headers incl. X-Custom and duplicates, in order (§1.2 row 3)
    // =====================================================================================================

    // FR-PROXY-003 AC-1, AC-4: X-Custom and duplicate lines reach the backend in received order with their values
    @Test(timeout = 60000)
    public void fr_PROXY_003_AC1_arbitraryAndDuplicateHeadersArriveInOrder() throws Exception {
        RawBackend backend = closing(RawBackend.answering(RawBackend.response(200, "hdr ok")));
        int port = gateway(backend.url());

        Reply reply = call(port, "GET /api/orders/x HTTP/1.1\r\nHost: gw.example\r\nX-Custom: custom-1\r\n"
                + "X-Dup: first\r\nAccept: application/json\r\nX-Dup: second\r\nCookie: a=1\r\n"
                + "X-Tenant-Id: t-42\r\nCookie: b=2\r\nX-Dup: third\r\nConnection: close\r\n\r\n", null);

        assertEquals(200, reply.status);
        assertEquals("hdr ok", reply.text());
        RawBackend.Received r = backend.onlyRequest();
        assertEquals(Collections.singletonList("custom-1"), r.headerValues("X-Custom"));
        assertEquals(Arrays.asList("first", "second", "third"), r.headerValues("X-Dup"));
        assertEquals(Collections.singletonList("t-42"), r.headerValues("X-Tenant-Id"));
        assertEquals(Collections.singletonList("a=1; b=2"), r.headerValues("Cookie"));
        // Relative order of the client lines as received: X-Custom, X-Dup, Accept, X-Dup, Cookie, X-Tenant-Id, X-Dup.
        List<String> names = new ArrayList<>();
        for (String line : r.headerLines()) {
            String name = line.substring(0, line.indexOf(':'));
            if (Arrays.asList("X-Custom", "X-Dup", "Accept", "Cookie", "X-Tenant-Id").contains(name)) {
                names.add(name);
            }
        }
        assertEquals(Arrays.asList("X-Custom", "X-Dup", "Accept", "X-Dup", "Cookie", "X-Tenant-Id", "X-Dup"), names);
    }

    // =====================================================================================================
    // 4. Two backend Set-Cookie lines both reach the client (§1.2 row 4)
    // =====================================================================================================

    // FR-PROXY-011 AC-2: two Set-Cookie lines from the backend reach the client in order, with the body
    @Test(timeout = 60000)
    public void fr_PROXY_011_AC2_twoSetCookieLinesReachTheClient() throws Exception {
        HttpServer backend = httpServer(ex -> {
            drain(ex);
            ex.getResponseHeaders().add("Set-Cookie", "session=abc; Path=/; HttpOnly");
            ex.getResponseHeaders().add("Set-Cookie", "theme=dark; Path=/");
            send(ex, 200, "text/plain", "cookies");
        });
        int port = gateway(url(backend));

        Reply reply = call(port, "GET /api/orders/login HTTP/1.1\r\nHost: gw.example\r\nConnection: close\r\n\r\n", null);

        assertEquals(200, reply.status);
        assertEquals(Arrays.asList("session=abc; Path=/; HttpOnly", "theme=dark; Path=/"), reply.values("Set-Cookie"));
        assertEquals("cookies", reply.text());
    }

    // =====================================================================================================
    // 5. Status, headers and body relayed; no Upgrade: h2c; full path with the mount prefix and the query unchanged
    // =====================================================================================================

    // FR-PROXY-011 AC-1, FR-PROXY-009 AC-6, FR-PROXY-006 AC-1, AC-4: status, headers and body are relayed, the backend
    // sees no Upgrade/HTTP2-Settings and the full raw path with the mount prefix and the query as encoded
    @Test(timeout = 60000)
    public void fr_PROXY_011_AC1_statusHeadersBodyRelayedWithFullPathAndNoH2c() throws Exception {
        Recorder rec = new Recorder();
        HttpServer backend = httpServer(ex -> {
            rec.record(ex);
            String path = ex.getRequestURI().getRawPath();
            ex.getResponseHeaders().add("X-Backend", "orders-1");
            ex.getResponseHeaders().add("Cache-Control", "max-age=7");
            if (path.endsWith("/missing")) {
                send(ex, 404, "application/json", "{\"error\":\"not found\"}");
            } else {
                send(ex, 201, "text/plain; charset=utf-8", "created");
            }
        });
        int port = gateway(url(backend));

        Reply created = call(port, "GET /api/orders/items/7?x=1&y=%ED%95%9C&z=+&w HTTP/1.1\r\nHost: gw.example\r\n"
                + "Connection: Upgrade, HTTP2-Settings\r\nUpgrade: h2c\r\nHTTP2-Settings: AAMAAABkAARAAAAAAAIAAAAA\r\n\r\n",
                null);
        Reply missing = call(port, "GET /api/orders/missing HTTP/1.1\r\nHost: gw.example\r\nConnection: close\r\n\r\n",
                null);

        assertEquals(201, created.status);
        assertEquals("created", created.text());
        assertEquals("orders-1", created.first("X-Backend"));
        assertEquals("max-age=7", created.first("Cache-Control"));
        assertEquals("text/plain; charset=utf-8", created.first("Content-Type"));
        assertEquals("7", created.first("Content-Length"));
        assertEquals(404, missing.status);
        assertEquals("{\"error\":\"not found\"}", missing.text());

        List<Recorded> all = rec.all();
        assertEquals(2, all.size());
        Recorded r = all.get(0);
        assertEquals("GET", r.method);
        assertEquals("/api/orders/items/7?x=1&y=%ED%95%9C&z=+&w", r.rawTarget);
        assertNull("Upgrade reached the backend: " + r.headers, r.headers.get("upgrade"));
        assertNull("HTTP2-Settings reached the backend: " + r.headers, r.headers.get("http2-settings"));
        assertEquals(Collections.singletonList("close"), r.headers.get("connection"));
        assertEquals("/api/orders/missing", all.get(1).rawTarget);
    }

    // =====================================================================================================
    // 6. Backend down → 502 JSON CONNECT_FAILED; silent backend → 504 READ_TIMEOUT; no stuck worker
    // =====================================================================================================

    // FR-PROXY-015 AC-1, AC-3: with the backend down the client gets the onError JSON 502 naming CONNECT_FAILED, and
    // the body names neither the target host nor the target port
    @Test(timeout = 60000)
    public void fr_PROXY_015_AC3_backendDownGives502JsonConnectFailedWithoutTheTarget() throws Exception {
        int down = closedPort();
        int port = gateway("http://127.0.0.1:" + down);

        Reply retried = call(port, "GET /api/orders/x HTTP/1.1\r\nHost: gw.example\r\nConnection: close\r\n\r\n", null);
        Reply single = call(port, "POST /api/orders/jobs/run HTTP/1.1\r\nHost: gw.example\r\nContent-Length: 2\r\n"
                + "Content-Type: application/json\r\nConnection: close\r\n\r\n", "{}".getBytes(StandardCharsets.US_ASCII));

        for (Reply reply : Arrays.asList(retried, single)) {
            assertEquals(502, reply.status);
            assertEquals("{\"error\":\"upstream\",\"kind\":\"CONNECT_FAILED\"}", reply.text());
            assertTrue(reply.first("Content-Type"), reply.first("Content-Type").startsWith("application/json"));
            assertFalse(reply.text().contains("127.0.0.1"));
            assertFalse(reply.text().contains(Integer.toString(down)));
        }
        assertEquals(2, errors.size());
        for (Throwable t : errors) {
            assertTrue(t instanceof ProxyException);
            assertEquals(ProxyException.Kind.CONNECT_FAILED, ((ProxyException) t).kind());
            assertNull(t.getMessage());
            assertTrue(String.valueOf(t.getCause()), t.getCause() instanceof java.net.ConnectException);
        }
    }

    // FR-PROXY-017 AC-4, FR-PROXY-018 AC-4, REL-PROXY-001 AC-6: a backend that reads the request and never answers gives
    // 504 READ_TIMEOUT within the 2-second read timeout (not retried on the retries(2) mount), and the gateway serves
    // another route and another proxied request right after, so no worker thread stays stuck
    @Test(timeout = 60000)
    public void fr_PROXY_017_AC4_silentBackendGives504ReadTimeoutAndTheGatewayKeepsServing() throws Exception {
        SilentBackend silent = closing(new SilentBackend());
        int port = gateway("http://127.0.0.1:" + silent.port());

        long t0 = System.nanoTime();
        Reply reply = call(port, "GET /api/orders/report HTTP/1.1\r\nHost: gw.example\r\nConnection: close\r\n\r\n", null);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertEquals(504, reply.status);
        assertEquals("{\"error\":\"upstream\",\"kind\":\"READ_TIMEOUT\"}", reply.text());
        assertTrue("answered before the read timeout: " + elapsedMs + " ms", elapsedMs >= 1800);
        assertTrue("answered long after the 2 s read timeout: " + elapsedMs + " ms", elapsedMs < 3900);
        assertEquals("READ_TIMEOUT is not retried", 1, silent.accepted());

        long t1 = System.nanoTime();
        Reply health = call(port, "GET /health HTTP/1.1\r\nHost: gw.example\r\nConnection: close\r\n\r\n", null);
        assertEquals(200, health.status);
        assertEquals("ok", health.text());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1) < 1000);

        // Several silent requests at once each end at their own read timeout and free their worker.
        ExecutorService pool = pool();
        List<Future<Reply>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> call(port,
                    "GET /api/orders/jobs/slow HTTP/1.1\r\nHost: gw.example\r\nConnection: close\r\n\r\n", null)));
        }
        for (Future<Reply> f : futures) {
            assertEquals(504, f.get(10, TimeUnit.SECONDS).status);
        }
        assertEquals(200, call(port, "GET /health HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n", null).status);
    }

    // =====================================================================================================
    // 7. Transient connect failure retried on /api/orders, /api/orders/jobs not retried
    // =====================================================================================================

    // FR-PROXY-018 AC-5, AC-11: on /api/orders (retries(2)) a backend that comes up between attempts serves the request
    // after the first refused connection, while /api/orders/jobs (no retries) answers 502 CONNECT_FAILED at once
    @Test(timeout = 60000)
    public void fr_PROXY_018_AC5_transientConnectFailureRetriedOnOrdersNotOnJobs() throws Exception {
        int backendPort = closedPort();
        int port = gateway("http://127.0.0.1:" + backendPort);

        // /api/orders/jobs: one attempt only.
        long t0 = System.nanoTime();
        Reply jobs = call(port, "GET /api/orders/jobs/1 HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n", null);
        long jobsMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals(502, jobs.status);
        assertEquals("{\"error\":\"upstream\",\"kind\":\"CONNECT_FAILED\"}", jobs.text());

        // /api/orders with the backend still down: three attempts with two 200 ms retry waits.
        long t1 = System.nanoTime();
        Reply ordersDown = call(port, "GET /api/orders/1 HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n", null);
        long ordersMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1);
        assertEquals(502, ordersDown.status);
        assertTrue("retries(2) waited twice 200 ms: " + ordersMs + " ms", ordersMs >= 380);
        assertTrue("jobs " + jobsMs + " ms must not wait like orders " + ordersMs + " ms", jobsMs < 300);

        // The backend comes up 120 ms after the request is sent: the first attempt is refused, a retry succeeds.
        Recorder rec = new Recorder();
        CountDownLatch up = new CountDownLatch(1);
        Thread starter = new Thread(() -> {
            try {
                Thread.sleep(120);
                httpServerOn(backendPort, rec.handler(200, "text/plain", "back up"));
                up.countDown();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        long t2 = System.nanoTime();
        starter.start();
        Reply orders = call(port, "POST /api/orders/1/confirm HTTP/1.1\r\nHost: g\r\nContent-Length: 0\r\n"
                + "Connection: close\r\n\r\n", null);
        long retriedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t2);
        starter.join(5000);
        assertTrue(up.await(1, TimeUnit.SECONDS));
        assertEquals(200, orders.status);
        assertEquals("back up", orders.text());
        assertEquals(1, rec.all().size());
        assertEquals("POST", rec.only().method);
        assertTrue("the first attempt was refused and a retry waited: " + retriedMs + " ms", retriedMs >= 150);

        // Now the backend is up: jobs reaches it with no retry needed.
        assertEquals(200, call(port, "GET /api/orders/jobs/2 HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n",
                null).status);
    }

    // FR-PROXY-018 AC-2, AC-11: a backend that always answers 503 sees a bodyless GET three times on /api/orders and once
    // on /api/orders/jobs, and the client receives the backend's last 503 unchanged
    @Test(timeout = 60000)
    public void fr_PROXY_018_AC11_backend503RetriedOnOrdersOnlyOnceOnJobs() throws Exception {
        AtomicInteger orders = new AtomicInteger();
        AtomicInteger jobs = new AtomicInteger();
        HttpServer backend = httpServer(ex -> {
            drain(ex);
            String path = ex.getRequestURI().getRawPath();
            int n = (path.startsWith("/api/orders/jobs") ? jobs : orders).incrementAndGet();
            ex.getResponseHeaders().add("Retry-After", "1");
            send(ex, 503, "text/plain", "busy " + n);
        });
        int port = gateway(url(backend));

        Reply o = call(port, "GET /api/orders/list HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n", null);
        Reply j = call(port, "GET /api/orders/jobs/list HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n", null);

        assertEquals(503, o.status);
        assertEquals("busy 3", o.text());
        assertEquals("1", o.first("Retry-After"));
        assertEquals(3, orders.get());
        assertEquals(503, j.status);
        assertEquals("busy 1", j.text());
        assertEquals(1, jobs.get());
        assertTrue("a relayed backend 503 is not an error: " + errors, errors.isEmpty());
    }

    // =====================================================================================================
    // 8. X-Gateway-Source; X-Forwarded-* with trust off and on; client forwarding headers dropped
    // =====================================================================================================

    private static final String FORGED = "GET /api/orders/me HTTP/1.1\r\nHost: gw.example\r\n"
            + "X-Forwarded-For: 1.2.3.4\r\nX-Forwarded-Proto: https\r\nX-Forwarded-Host: evil.example\r\n"
            + "Forwarded: for=6.6.6.6;proto=https\r\nX-Real-IP: 7.7.7.7\r\nX-Forwarded-Port: 443\r\n"
            + "X-Forwarded-Prefix: /evil\r\nX-Gateway-Source: forged\r\nConnection: close\r\n\r\n";

    // SEC-PROXY-002 AC-1, AC-7, FR-PROXY-005 AC-1: with no trust setting the backend receives X-Forwarded-For with the
    // socket address only, X-Forwarded-Proto http, X-Forwarded-Host from Host, none of the client's other forwarding
    // headers, Host rewritten to the target, and exactly one X-Gateway-Source: gateway
    @Test(timeout = 60000)
    public void sec_PROXY_002_AC1_trustOffReplacesForwardingHeadersAndAddsGatewaySource() throws Exception {
        RawBackend backend = closing(RawBackend.answering(RawBackend.response(200, "me")));
        int port = gateway(backend.url());

        assertEquals(200, call(port, FORGED, null).status);

        RawBackend.Received r = backend.onlyRequest();
        assertEquals(Collections.singletonList("127.0.0.1:" + backend.port()), r.headerValues("Host"));
        assertEquals(Collections.singletonList("127.0.0.1"), r.headerValues("X-Forwarded-For"));
        assertEquals(Collections.singletonList("http"), r.headerValues("X-Forwarded-Proto"));
        assertEquals(Collections.singletonList("gw.example"), r.headerValues("X-Forwarded-Host"));
        assertNoClientForwarding(r);
        assertEquals(Collections.singletonList("gateway"), r.headerValues("X-Gateway-Source"));
    }

    // SEC-PROXY-002 AC-4, AC-5, AC-6, AC-7: with the app trusting the client's address (trustProxyCidrs and
    // trustProxyHops(1)) the backend receives X-Forwarded-For "1.2.3.4, 127.0.0.1", X-Forwarded-Proto https from
    // isSecure(), X-Forwarded-Host still from Host, and the other forwarding headers still dropped
    @Test(timeout = 60000)
    public void sec_PROXY_002_AC4_trustOnAppendsForwardedForAndKeepsProto() throws Exception {
        RawBackend backend = closing(RawBackend.answering(RawBackend.response(200, "me")));
        int cidrPort = gateway(backend.url(), (app, orders) -> app.trustProxyCidrs("127.0.0.1/32"));
        int hopsPort = gateway(backend.url(), (app, orders) -> app.trustProxyHops(1));

        assertEquals(200, call(cidrPort, FORGED, null).status);
        assertEquals(200, call(hopsPort, FORGED, null).status);

        List<RawBackend.Received> all = backend.requests();
        assertEquals(2, all.size());
        for (RawBackend.Received r : all) {
            assertEquals(Collections.singletonList("1.2.3.4, 127.0.0.1"), r.headerValues("X-Forwarded-For"));
            assertEquals(Collections.singletonList("https"), r.headerValues("X-Forwarded-Proto"));
            assertEquals(Collections.singletonList("gw.example"), r.headerValues("X-Forwarded-Host"));
            assertNoClientForwarding(r);
            assertEquals(Collections.singletonList("gateway"), r.headerValues("X-Gateway-Source"));
        }
    }

    private static void assertNoClientForwarding(RawBackend.Received r) {
        for (String name : Arrays.asList("Forwarded", "X-Real-IP", "X-Forwarded-Port", "X-Forwarded-Prefix")) {
            assertEquals(name + " reached the backend", Collections.<String>emptyList(), r.headerValues(name));
        }
        assertFalse(r.head.contains("evil"));
        assertFalse(r.head.contains("forged"));
    }

    // =====================================================================================================
    // 9. Large request and response bodies stream through
    // =====================================================================================================

    // FR-PROXY-007 AC-7, AC-4, FR-PROXY-008: a 64 MiB Content-Length request body and a 64 MiB chunked request body
    // reach the backend with the same length and SHA-256 through a mount with bodyLimit(96 MiB)
    @Test(timeout = 120000)
    public void fr_PROXY_007_AC7_largeRequestBodiesStreamThrough() throws Exception {
        HttpServer backend = httpServer(ex -> {
            MessageDigest md = sha256();
            long n = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = ex.getRequestBody()) {
                for (int r; (r = in.read(buf)) != -1; ) {
                    md.update(buf, 0, r);
                    n += r;
                }
            }
            send(ex, 200, "text/plain", n + " " + hex(md.digest()));
        });
        int port = gateway(url(backend), (app, orders) -> app.use("/api/bulk", orders.bodyLimit(96 * MIB)));
        long size = 64 * MIB;
        String expected = size + " " + patternSha256(size);

        Reply fixed = callStreaming(port, "PUT /api/bulk/upload HTTP/1.1\r\nHost: g\r\nContent-Type: "
                + "application/octet-stream\r\nContent-Length: " + size + "\r\nConnection: close\r\n\r\n", size, false);
        assertEquals(200, fixed.status);
        assertEquals(expected, fixed.text());

        Reply chunked = callStreaming(port, "POST /api/bulk/upload HTTP/1.1\r\nHost: g\r\nTransfer-Encoding: chunked"
                + "\r\nConnection: close\r\n\r\n", size, true);
        assertEquals(200, chunked.status);
        assertEquals(expected, chunked.text());
    }

    // FR-PROXY-014 AC-1, AC-2, FR-PROXY-007 AC-7: a 64 MiB backend response with Content-Length reaches the client with
    // that Content-Length and identical bytes, and a 64 MiB chunked backend response reaches it with identical bytes
    @Test(timeout = 120000)
    public void fr_PROXY_014_AC1_largeResponsesStreamThrough() throws Exception {
        long size = 64 * MIB;
        HttpServer backend = httpServer(ex -> {
            drain(ex);
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            boolean chunked = ex.getRequestURI().getRawPath().endsWith("/chunked");
            ex.sendResponseHeaders(200, chunked ? 0 : size);
            try (OutputStream out = ex.getResponseBody()) {
                writePattern(out, size);
            }
        });
        int port = gateway(url(backend));
        String expected = patternSha256(size);

        Reply fixed = callDigest(port, "GET /api/orders/export HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n");
        assertEquals(200, fixed.status);
        assertEquals(Long.toString(size), fixed.first("Content-Length"));
        assertEquals("application/octet-stream", fixed.first("Content-Type"));
        assertEquals(size, fixed.bodyLength);
        assertEquals(expected, fixed.sha256);

        Reply chunked = callDigest(port, "GET /api/orders/export/chunked HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n");
        assertEquals(200, chunked.status);
        assertEquals(size, chunked.bodyLength);
        assertEquals(expected, chunked.sha256);
    }

    // =====================================================================================================
    // 10. https backend with a private CA; wrong-host certificate refused
    // =====================================================================================================

    // SEC-PROXY-005 AC-1, AC-2: through .sslSocketFactory with the private CA the https backend is reached; the same
    // backend without it, and a backend whose certificate names another host even with it, give 502 CONNECT_FAILED
    @Test(timeout = 120000)
    public void sec_PROXY_005_AC2_privateCaBackendReachedAndWrongHostRefused() throws Exception {
        Tls.init();
        AtomicInteger good = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        HttpsServer goodServer = httpsServer(Tls.goodServer, ex -> {
            good.incrementAndGet();
            drain(ex);
            send(ex, 200, "text/plain", "secure " + ex.getRequestURI().getRawPath());
        });
        HttpsServer otherServer = httpsServer(Tls.otherHostServer, ex -> {
            other.incrementAndGet();
            drain(ex);
            send(ex, 200, "text/plain", "must not be reached");
        });
        String goodUrl = "https://127.0.0.1:" + goodServer.getAddress().getPort();
        String otherUrl = "https://127.0.0.1:" + otherServer.getAddress().getPort();
        int port = gateway("http://127.0.0.1:" + closedPort(), (app, orders) -> {
            app.use("/secure", Proxy.to(goodUrl).sslSocketFactory(Tls.trustingClient.getSocketFactory()));
            app.use("/untrusted", Proxy.to(goodUrl));
            app.use("/wrong-host", Proxy.to(otherUrl).sslSocketFactory(Tls.trustingClient.getSocketFactory()));
        });

        Reply ok = call(port, "GET /secure/orders/1?x=1 HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n", null);
        assertEquals(200, ok.status);
        assertEquals("secure /secure/orders/1", ok.text());
        assertEquals(1, good.get());

        Reply untrusted = call(port, "GET /untrusted/1 HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n", null);
        assertEquals(502, untrusted.status);
        assertEquals("{\"error\":\"upstream\",\"kind\":\"CONNECT_FAILED\"}", untrusted.text());
        assertEquals("no request bytes reach a backend that fails verification", 1, good.get());

        Reply wrongHost = call(port, "GET /wrong-host/1 HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n", null);
        assertEquals(502, wrongHost.status);
        assertEquals("{\"error\":\"upstream\",\"kind\":\"CONNECT_FAILED\"}", wrongHost.text());
        assertEquals(0, other.get());
    }

    // =====================================================================================================
    // 11. maxConcurrent(1): immediate 503, slot returns; a client that disconnects mid-download frees the slot
    // =====================================================================================================

    // FR-PROXY-019 AC-1, AC-2, REL-PROXY-001 AC-1: on a maxConcurrent(1) mount a second request during a delayed first
    // one gets 503 CONCURRENCY_LIMIT at once without reaching the backend, the slot returns after the first ends, and
    // a client that disconnects mid-download frees the slot and the backend sees its connection closed
    @Test(timeout = 120000)
    public void fr_PROXY_019_AC1_maxConcurrentOneRejectsAtOnceAndTheSlotReturns() throws Exception {
        CountDownLatch slowArrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch streamClosed = new CountDownLatch(1);
        AtomicInteger quick = new AtomicInteger();
        HttpServer backend = httpServer(ex -> {
            drain(ex);
            String path = ex.getRequestURI().getRawPath();
            if (path.endsWith("/slow")) {
                slowArrived.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                send(ex, 200, "text/plain", "slow done");
            } else if (path.endsWith("/stream")) {
                ex.sendResponseHeaders(200, 0);
                byte[] block = new byte[64 * 1024];
                try (OutputStream out = ex.getResponseBody()) {
                    for (long sent = 0; sent < 2048 * MIB; sent += block.length) {
                        out.write(block);
                    }
                } catch (IOException closed) {
                    streamClosed.countDown();
                }
            } else {
                quick.incrementAndGet();
                send(ex, 200, "text/plain", "quick");
            }
        });
        int port = gateway(url(backend), (app, orders) ->
                app.use("/api/limited", orders.maxConcurrent(1).readTimeout(10, TimeUnit.SECONDS)));
        ExecutorService pool = pool();

        Future<Reply> first = pool.submit(() ->
                call(port, "GET /api/limited/slow HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n", null));
        assertTrue(slowArrived.await(10, TimeUnit.SECONDS));

        long t0 = System.nanoTime();
        Reply rejected = call(port, "GET /api/limited/quick HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n", null);
        long rejectedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals(503, rejected.status);
        assertEquals("{\"error\":\"upstream\",\"kind\":\"CONCURRENCY_LIMIT\"}", rejected.text());
        assertTrue("503 at once, not after waiting: " + rejectedMs + " ms", rejectedMs < 1000);
        assertEquals(0, quick.get());

        release.countDown();
        Reply firstReply = first.get(10, TimeUnit.SECONDS);
        assertEquals(200, firstReply.status);
        assertEquals("slow done", firstReply.text());
        assertEquals("quick", awaitOk(port, "/api/limited/quick").text());
        assertEquals(1, quick.get());

        // A client that reads part of a streamed download and disconnects frees the slot.
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(10000);
            s.getOutputStream().write("GET /api/limited/stream HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = s.getInputStream();
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            while (got < 1 * MIB) {
                int n = in.read(buf);
                assertTrue("stream ended early", n > 0);
                got += n;
            }
            s.setSoLinger(true, 0);
        }
        assertTrue("the backend saw its connection closed after the client left",
                streamClosed.await(15, TimeUnit.SECONDS));
        assertEquals("quick", awaitOk(port, "/api/limited/quick").text());
        assertEquals(2, quick.get());
    }

    /** Sends GET {@code path} until it is answered 200 (the slot returns as the previous relay closes), up to 10 s. */
    private Reply awaitOk(int port, String path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Reply last = null;
        while (System.nanoTime() < deadline) {
            last = call(port, "GET " + path + " HTTP/1.1\r\nHost: g\r\nConnection: close\r\n\r\n", null);
            if (last.status == 200) {
                return last;
            }
            assertEquals(last.text(), 503, last.status);
            Thread.sleep(20);
        }
        throw new AssertionError("the slot never returned; last status " + (last == null ? "-" : last.status));
    }

    // =====================================================================================================
    // 12. Location rewrite under the mount; stripPrefix(true) on a separate mount
    // =====================================================================================================

    // FR-PROXY-013 AC-1, AC-3, FR-PROXY-011 AC-1, FR-PROXY-006 AC-2: a same-origin absolute Location under the mount is
    // rewritten to an origin-less path and the 302 is relayed, not followed; on a separate stripPrefix(true) mount the
    // backend sees the path without the prefix and its Location comes back with the prefix
    @Test(timeout = 60000)
    public void fr_PROXY_013_AC1_locationRewriteAndStripPrefix() throws Exception {
        Recorder rec = new Recorder();
        int[] self = new int[1];
        HttpServer backend = httpServer(ex -> {
            rec.record(ex);
            String path = ex.getRequestURI().getRawPath();
            String origin = "http://127.0.0.1:" + self[0];
            if (path.equals("/api/orders/old")) {
                ex.getResponseHeaders().add("Location", origin + "/api/orders/new?from=old");
                send(ex, 302, "text/plain", "moved");
            } else if (path.equals("/items/1")) {
                ex.getResponseHeaders().add("Location", origin + "/items/2");
                send(ex, 301, "text/plain", "moved");
            } else if (path.equals("/api/orders/away")) {
                ex.getResponseHeaders().add("Location", "http://other.example/x");
                send(ex, 307, "text/plain", "away");
            } else {
                send(ex, 200, "text/plain", "at " + ex.getRequestURI().getRawPath());
            }
        });
        self[0] = backend.getAddress().getPort();
        int port = gateway(url(backend), (app, orders) -> app.use("/shop", Proxy.to(url(backend)).stripPrefix(true)));

        Reply moved = call(port, "GET /api/orders/old HTTP/1.1\r\nHost: gw.example\r\nConnection: close\r\n\r\n", null);
        assertEquals(302, moved.status);
        assertEquals("/api/orders/new?from=old", moved.first("Location"));
        Reply away = call(port, "GET /api/orders/away HTTP/1.1\r\nHost: gw.example\r\nConnection: close\r\n\r\n", null);
        assertEquals(307, away.status);
        assertEquals("http://other.example/x", away.first("Location"));

        Reply stripped = call(port, "GET /shop/items/1?q=2 HTTP/1.1\r\nHost: gw.example\r\nConnection: close\r\n\r\n",
                null);
        assertEquals(301, stripped.status);
        assertEquals("/shop/items/2", stripped.first("Location"));

        List<Recorded> all = rec.all();
        assertEquals("redirects are relayed, never followed", 3, all.size());
        assertEquals("/api/orders/old", all.get(0).rawTarget);
        assertEquals("/api/orders/away", all.get(1).rawTarget);
        assertEquals("/items/1?q=2", all.get(2).rawTarget);
    }

    // =====================================================================================================
    // Backends
    // =====================================================================================================

    /** One request as the JDK HttpServer received it. */
    static final class Recorded {
        final String method;
        final String rawTarget;
        /** Lower-case names to their values in received order. */
        final Map<String, List<String>> headers;
        final byte[] body;

        Recorded(String method, String rawTarget, Map<String, List<String>> headers, byte[] body) {
            this.method = method;
            this.rawTarget = rawTarget;
            this.headers = headers;
            this.body = body;
        }
    }

    /** Records every request a JDK HttpServer handler sees. */
    static final class Recorder {
        private final List<Recorded> list = new CopyOnWriteArrayList<>();

        void record(HttpExchange ex) throws IOException {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : ex.getRequestHeaders().entrySet()) {
                headers.put(e.getKey().toLowerCase(Locale.ROOT), new ArrayList<>(e.getValue()));
            }
            String query = ex.getRequestURI().getRawQuery();
            String target = ex.getRequestURI().getRawPath() + (query == null ? "" : "?" + query);
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = readAll(in);
            }
            list.add(new Recorded(ex.getRequestMethod(), target, headers, body));
        }

        HttpHandler handler(int status, String type, String body) {
            return ex -> {
                record(ex);
                send(ex, status, type, body);
            };
        }

        List<Recorded> all() {
            return new ArrayList<>(list);
        }

        Recorded only() {
            assertEquals("backend requests", 1, list.size());
            return list.get(0);
        }
    }

    private HttpServer httpServer(HttpHandler handler) throws IOException {
        return httpServerOn(0, handler);
    }

    private HttpServer httpServerOn(int port, HttpHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 64);
        return startServer(s, handler);
    }

    private HttpsServer httpsServer(SSLContext context, HttpHandler handler) throws IOException {
        HttpsServer s = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64);
        s.setHttpsConfigurator(new HttpsConfigurator(context));
        startServer(s, handler);
        return s;
    }

    private HttpServer startServer(HttpServer s, HttpHandler handler) {
        ExecutorService executor = Executors.newCachedThreadPool();
        executors.add(executor);
        s.createContext("/", ex -> {
            try {
                handler.handle(ex);
            } catch (IOException | RuntimeException e) {
                // the proxy closed the connection; the exchange ends here
            } finally {
                ex.close();
            }
        });
        s.setExecutor(executor);
        s.start();
        servers.add(s);
        return s;
    }

    private static String url(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (type != null) {
            ex.getResponseHeaders().add("Content-Type", type);
        }
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void drain(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            readAll(in);
        }
    }

    /** Accepts connections, reads nothing back and never answers. */
    static final class SilentBackend implements Closeable {
        private final ServerSocket server;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final Thread acceptor;

        SilentBackend() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        sockets.add(server.accept());
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "silent-backend");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        int accepted() {
            return sockets.size();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket s : sockets) {
                s.close();
            }
        }
    }

    private <T extends Closeable> T closing(T c) {
        closeables.add(c);
        return c;
    }

    private ExecutorService pool() {
        ExecutorService p = Executors.newCachedThreadPool();
        executors.add(p);
        return p;
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    // =====================================================================================================
    // Raw socket client
    // =====================================================================================================

    /** The client's view of one response. */
    static final class Reply {
        int status;
        final List<String[]> headers = new ArrayList<>();
        byte[] body = new byte[0];
        long bodyLength;
        @Nullable String sha256;

        @Nullable String first(String name) {
            List<String> v = values(name);
            return v.isEmpty() ? null : v.get(0);
        }

        List<String> values(String name) {
            List<String> out = new ArrayList<>();
            for (String[] h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    out.add(h[1]);
                }
            }
            return out;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** Sends {@code head} (with its blank line) and {@code body}, and reads one final response, keeping its body. */
    static Reply call(int port, String head, byte @Nullable [] body) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(30000);
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                out.write(body);
            }
            out.flush();
            return read(new BufferedInputStream(s.getInputStream()), head.startsWith("HEAD "), true);
        }
    }

    /** GETs and reads the body into a length and a SHA-256 only. */
    static Reply callDigest(int port, String head) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(30000);
            s.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1));
            return read(new BufferedInputStream(s.getInputStream()), false, false);
        }
    }

    /** Sends {@code head} and then {@code size} pattern bytes, plain or chunked, and reads the response. */
    static Reply callStreaming(int port, String head, long size, boolean chunked) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(60000);
            OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream(), 256 * 1024);
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            byte[] buf = new byte[256 * 1024];
            for (long off = 0; off < size; ) {
                int n = (int) Math.min(buf.length, size - off);
                fillPattern(buf, off, n);
                if (chunked) {
                    out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                }
                out.write(buf, 0, n);
                if (chunked) {
                    out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                }
                off += n;
            }
            if (chunked) {
                out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            }
            out.flush();
            return read(new BufferedInputStream(s.getInputStream()), false, true);
        }
    }

    private static Reply read(InputStream in, boolean head, boolean keep) throws IOException {
        Reply reply;
        do {
            reply = new Reply();
            String status = line(in);
            if (status == null) {
                throw new IOException("connection closed before a status line");
            }
            reply.status = Integer.parseInt(status.split(" ")[1]);
            for (String l; (l = line(in)) != null && !l.isEmpty(); ) {
                int colon = l.indexOf(':');
                reply.headers.add(new String[] {l.substring(0, colon), l.substring(colon + 1).trim()});
            }
        } while (reply.status >= 100 && reply.status < 200);

        MessageDigest md = sha256();
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        boolean noBody = head || reply.status == 204 || reply.status == 304;
        String te = reply.first("Transfer-Encoding");
        String cl = reply.first("Content-Length");
        if (noBody) {
            // nothing
        } else if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            while (true) {
                String sizeLine = line(in);
                if (sizeLine == null) {
                    throw new IOException("truncated chunked body after " + total + " bytes");
                }
                int semi = sizeLine.indexOf(';');
                long size = Long.parseLong((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim(), 16);
                if (size == 0) {
                    while (true) {
                        String trailer = line(in);
                        if (trailer == null || trailer.isEmpty()) {
                            break;
                        }
                    }
                    break;
                }
                total += copy(in, size, buf, md, keep ? kept : null);
                line(in);
            }
        } else if (cl != null) {
            long size = Long.parseLong(cl);
            total = copy(in, size, buf, md, keep ? kept : null);
            if (total != size) {
                throw new IOException("body ended after " + total + " of " + size + " bytes");
            }
        } else {
            total = copy(in, Long.MAX_VALUE, buf, md, keep ? kept : null);
        }
        reply.bodyLength = total;
        reply.sha256 = hex(md.digest());
        reply.body = kept.toByteArray();
        return reply;
    }

    private static long copy(InputStream in, long limit, byte[] buf, MessageDigest md,
            @Nullable ByteArrayOutputStream kept) throws IOException {
        long total = 0;
        while (total < limit) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, limit - total));
            if (n < 0) {
                break;
            }
            md.update(buf, 0, n);
            if (kept != null) {
                kept.write(buf, 0, n);
            }
            total += n;
        }
        return total;
    }

    private static @Nullable String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // =====================================================================================================
    // Bytes, digests and JSON
    // =====================================================================================================

    static void fillPattern(byte[] buf, long offset, int n) {
        for (int i = 0; i < n; i++) {
            long x = offset + i;
            buf[i] = (byte) ((x * 2654435761L) >>> 13 ^ (x >>> 20));
        }
    }

    static void writePattern(OutputStream out, long size) throws IOException {
        byte[] buf = new byte[256 * 1024];
        for (long off = 0; off < size; ) {
            int n = (int) Math.min(buf.length, size - off);
            fillPattern(buf, off, n);
            out.write(buf, 0, n);
            off += n;
        }
    }

    static String patternSha256(long size) {
        MessageDigest md = sha256();
        byte[] buf = new byte[256 * 1024];
        for (long off = 0; off < size; ) {
            int n = (int) Math.min(buf.length, size - off);
            fillPattern(buf, off, n);
            md.update(buf, 0, n);
            off += n;
        }
        return hex(md.digest());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Encodes maps of strings (the onError bodies) and the JSON literals; decoding is not needed. */
    static final class MapJsonCodec implements JsonCodec {
        @Override
        public <T> T decode(Reader reader, Type type) {
            throw new UnsupportedOperationException("the gateway test decodes no JSON");
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) throws IOException {
            if (value == null || value instanceof Number || value instanceof Boolean) {
                writer.write(String.valueOf(value));
            } else if (value instanceof Map) {
                writer.write('{');
                boolean firstEntry = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    if (!firstEntry) {
                        writer.write(',');
                    }
                    firstEntry = false;
                    string(String.valueOf(e.getKey()), writer);
                    writer.write(':');
                    encode(e.getValue(), writer);
                }
                writer.write('}');
            } else {
                string(String.valueOf(value), writer);
            }
        }

        private static void string(String s, Writer w) throws IOException {
            w.write('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') {
                    w.write('\\');
                    w.write(c);
                } else if (c < 0x20) {
                    w.write(String.format("\\u%04x", (int) c));
                } else {
                    w.write(c);
                }
            }
            w.write('"');
        }
    }

    // =====================================================================================================
    // Private CA (made once with the JDK's keytool, as ProxyTlsTest does)
    // =====================================================================================================

    static final class Tls {
        private static final char[] PASSWORD = "changeit".toCharArray();
        static @Nullable File dir;
        static SSLContext trustingClient;
        static SSLContext goodServer;
        static SSLContext otherHostServer;

        static synchronized void init() throws Exception {
            if (dir != null) {
                return;
            }
            File d = java.nio.file.Files.createTempDirectory("naruserve-gateway-e2e-tls").toFile();
            dir = d;
            keytool(d, "-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname",
                    "CN=Gateway E2E Test CA", "-ext", "bc:c", "-validity", "3", "-keystore", "ca.p12");
            keytool(d, "-exportcert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-file", "ca.pem");
            server(d, "good", "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1");
            server(d, "other", "CN=other.example", "SAN=dns:other.example");
            keytool(d, "-importcert", "-noprompt", "-alias", "ca", "-file", "ca.pem", "-keystore", "trust.p12");

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(load(d, "trust.p12"));
            trustingClient = SSLContext.getInstance("TLS");
            trustingClient.init(null, tmf.getTrustManagers(), null);
            goodServer = serverContext(d, "good.p12");
            otherHostServer = serverContext(d, "other.p12");
        }

        private static void server(File d, String name, String dname, String san) throws Exception {
            keytool(d, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", dname,
                    "-validity", "3", "-keystore", name + ".p12");
            keytool(d, "-certreq", "-alias", "server", "-keystore", name + ".p12", "-file", name + ".csr");
            keytool(d, "-gencert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-infile", name + ".csr",
                    "-outfile", name + ".pem", "-ext", san, "-validity", "3");
            keytool(d, "-importcert", "-noprompt", "-alias", "ca", "-file", "ca.pem", "-keystore", name + ".p12");
            keytool(d, "-importcert", "-noprompt", "-alias", "server", "-file", name + ".pem", "-keystore",
                    name + ".p12");
        }

        private static void keytool(File d, String... args) throws Exception {
            List<String> command = new ArrayList<>();
            command.add(new File(System.getProperty("java.home"), "bin/keytool").getPath());
            command.addAll(Arrays.asList(args));
            command.addAll(Arrays.asList("-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit"));
            Process p = new ProcessBuilder(command).directory(d).redirectErrorStream(true).start();
            String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                fail("keytool " + Arrays.asList(args) + " failed:\n" + out);
            }
        }

        private static KeyStore load(File d, String name) throws Exception {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(new File(d, name))) {
                ks.load(in, PASSWORD);
            }
            return ks;
        }

        private static SSLContext serverContext(File d, String keystore) throws Exception {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(load(d, keystore), PASSWORD);
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(kmf.getKeyManagers(), null, null);
            return c;
        }
    }

    @AfterClass
    public static void deleteCertificates() {
        File d = Tls.dir;
        if (d == null) {
            return;
        }
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                assertTrue(f.delete());
            }
        }
        assertTrue(d.delete());
    }
}
