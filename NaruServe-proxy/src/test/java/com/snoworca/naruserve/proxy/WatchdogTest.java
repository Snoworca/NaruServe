package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.ApplicationContext;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * The proxy watchdog scheduler (REL-PROXY-002): one JVM-wide scheduler, created by the first proxied request and
 * owned by no app, that enforces the write and total deadlines (the total one also bounding the TLS handshake) on at most one daemon thread without
 * context class loader, which ends once idle; cancelled reservations leave its queue, and a firing deadline closes the
 * plain backend socket, never the {@code SSLSocket}. The proxy tests share one test JVM, so the laziness check runs in
 * a fresh JVM ({@link LazyProbe}) and the thread checks count the live threads whose name marks them as a proxy
 * watchdog.
 */
public class WatchdogTest {

    /** How long a test waits for a request or an event that must happen. */
    private static final long WAIT_SECONDS = 20;

    private final List<RestApp> apps = new ArrayList<>();
    private final List<Closeable> closeables = new ArrayList<>();
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());

    @After
    public void tearDown() throws IOException {
        TlsConnector.endpointIdentification = true;
        for (RestApp a : apps) {
            a.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    // ---- AC-1 ----

    // REL-PROXY-002 AC-1: loading Proxy, calling Proxy.to(url) with options and starting an app that mounts it create
    // no scheduler and no watchdog thread until the first proxied request, in a fresh JVM, as the package-private
    // scheduler accessor and the thread listing show; the first request then creates the scheduler
    @Test(timeout = 60000)
    public void rel_PROXY_002_AC1_nothingIsCreatedBeforeTheFirstProxiedRequest() throws Exception {
        String out = runLazyProbe();

        assertTrue(out, out.contains("before: scheduler=none threads=[]"));
        assertTrue(out, out.contains("after: scheduler=created status=200"));
    }

    // ---- AC-2 ----

    // REL-PROXY-002 AC-2: proxies of two different apps in one JVM use the same scheduler instance, and while 20
    // proxied requests, http and https, are in flight at most one watchdog thread exists
    @Test(timeout = 60000)
    public void rel_PROXY_002_AC2_twoAppsShareOneSchedulerAndAtMostOneWatchdogThread() throws Exception {
        SilentBackend backend = silentBackend(true);
        Proxy http1 = Proxy.to(backend.url());
        Proxy https1 = Proxy.to("https://127.0.0.1:" + backend.port());
        Proxy http2 = Proxy.to(backend.url());
        Proxy https2 = Proxy.to("https://127.0.0.1:" + backend.port());
        InMemoryHttpAdapter a1 = app(http1, https1);
        InMemoryHttpAdapter a2 = app(http2, https2);

        assertSame(ProxyExchange.Scheduler.INSTANCE, http1.watchdog());
        assertSame(http1.watchdog(), http2.watchdog());
        assertSame(http1.watchdog(), https2.watchdog());

        List<Future<ClientResponse>> inFlight = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            inFlight.add(a1.submit(ClientRequest.of("GET", "/http/x")));
            inFlight.add(a1.submit(ClientRequest.of("GET", "/https/x")));
            inFlight.add(a2.submit(ClientRequest.of("GET", "/http/x")));
            inFlight.add(a2.submit(ClientRequest.of("GET", "/https/x")));
        }
        backend.awaitConnections(20);
        ScheduledThreadPoolExecutor executor = ProxyExchange.Scheduler.INSTANCE.executorIfCreated();
        assertNotNull("the scheduler exists once requests are in flight", executor);

        // Sample the threads for a while: every in-flight request, https included, holds its total-timeout reservation,
        // which also bounds the TLS handshake.
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        int most = 0;
        List<String> seen = new ArrayList<>();
        while (System.nanoTime() < until) {
            List<Thread> threads = watchdogThreads();
            if (threads.size() > most) {
                most = threads.size();
                seen = names(threads);
            }
            Thread.sleep(20);
        }
        for (Future<ClientResponse> f : inFlight) {
            assertFalse("request still in flight while sampling", f.isDone());
        }
        assertTrue("watchdog threads while 20 requests were in flight: " + seen, most <= 1);
        assertSame(executor, ProxyExchange.Scheduler.INSTANCE.executorIfCreated());

        backend.close();
        for (Future<ClientResponse> f : inFlight) {
            f.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    // ---- AC-3 ----

    // REL-PROXY-002 AC-3: every watchdog thread, the one that bounds an https request's handshake included, is a
    // daemon thread whose context class loader is null
    @Test(timeout = 60000)
    public void rel_PROXY_002_AC3_theWatchdogThreadIsADaemonWithoutContextClassLoader() throws Exception {
        SilentBackend backend = silentBackend(true);
        Proxy http = Proxy.to(backend.url()).totalTimeout(1, TimeUnit.SECONDS);
        Proxy https = Proxy.to("https://127.0.0.1:" + backend.port()).totalTimeout(1, TimeUnit.SECONDS);
        InMemoryHttpAdapter a = app(http, https);

        Future<ClientResponse> viaHttps = a.submit(ClientRequest.of("GET", "/https/x"));
        Future<ClientResponse> viaHttp = a.submit(ClientRequest.of("GET", "/http/x"));
        backend.awaitConnections(2);
        Thread.sleep(100);
        List<Thread> threads = watchdogThreads();

        assertFalse("a watchdog thread runs while requests are in flight", threads.isEmpty());
        for (Thread t : threads) {
            assertTrue(t.getName() + " is a daemon thread", t.isDaemon());
            assertNull(t.getName() + " has no context class loader", t.getContextClassLoader());
        }
        assertEquals(504, viaHttps.get(WAIT_SECONDS, TimeUnit.SECONDS).status());
        assertEquals(504, viaHttp.get(WAIT_SECONDS, TimeUnit.SECONDS).status());
    }

    // ---- AC-4 ----

    // REL-PROXY-002 AC-4: after every proxied request, an https one included, has ended and no new one arrives, no
    // watchdog thread remains within 30 seconds, and a request sent after that with .totalTimeout(1, SECONDS) to a
    // backend that never answers still gives 504 TOTAL_TIMEOUT after about 1 second
    @Test(timeout = 90000)
    public void rel_PROXY_002_AC4_theIdleWatchdogThreadEndsAndANewRequestStillTimesOut() throws Exception {
        SilentBackend backend = silentBackend(true);
        Proxy http = Proxy.to(backend.url()).totalTimeout(1, TimeUnit.SECONDS);
        Proxy https = Proxy.to("https://127.0.0.1:" + backend.port()).totalTimeout(1, TimeUnit.SECONDS);
        InMemoryHttpAdapter a = app(http, https);

        assertEquals(504, a.submit(ClientRequest.of("GET", "/https/x")).get(WAIT_SECONDS, TimeUnit.SECONDS).status());
        assertEquals(504, a.submit(ClientRequest.of("GET", "/http/x")).get(WAIT_SECONDS, TimeUnit.SECONDS).status());
        long ended = System.nanoTime();

        List<Thread> left = watchdogThreads();
        while (!left.isEmpty() && System.nanoTime() - ended < TimeUnit.SECONDS.toNanos(30)) {
            Thread.sleep(100);
            left = watchdogThreads();
        }
        assertTrue("watchdog threads 30 seconds after the last request: " + names(left), left.isEmpty());

        errors.clear();
        long t0 = System.nanoTime();
        ClientResponse r = a.submit(ClientRequest.of("GET", "/http/x")).get(WAIT_SECONDS, TimeUnit.SECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertEquals(504, r.status());
        assertOnlyKind(ProxyException.Kind.TOTAL_TIMEOUT);
        assertAbout(1000, millis);
    }

    // ---- AC-5 ----

    // REL-PROXY-002 AC-5: in a fresh JVM, the first proxied request after the app has started creates the scheduler,
    // calls neither ApplicationContext.own nor an attribute setter, reaches onError with nothing, so no
    // IllegalStateException, and its response is relayed
    @Test(timeout = 60000)
    public void rel_PROXY_002_AC5_theFirstRequestAfterStartPreparesTheWatchdogWithoutAppResources()
            throws Exception {
        String out = runLazyProbe();

        assertTrue(out, out.contains("before: scheduler=none"));
        assertTrue(out, out.contains("after: scheduler=created status=200 body=ok"));
        assertTrue(out, out.contains("context mutations=[] errors=[]"));
    }

    // REL-PROXY-002 AC-5: a proxied request after start calls neither ApplicationContext.own nor an attribute
    // setter, throws no IllegalStateException and its response is relayed
    @Test(timeout = 30000)
    public void rel_PROXY_002_AC5_aRequestAfterStartTouchesNoAppResource() throws Exception {
        RawBackend backend = RawBackend.answering(RawBackend.response(200, "relayed"));
        closeables.add(backend);
        List<String> mutations = Collections.synchronizedList(new ArrayList<String>());
        InMemoryHttpAdapter a = app(recordingContext(Proxy.to(backend.url()), mutations),
                Proxy.to("https://127.0.0.1:9"));

        ClientResponse r = a.submit(ClientRequest.of("GET", "/http/x")).get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(200, r.status());
        assertEquals("relayed", r.text());
        assertTrue("errors: " + errors, errors.isEmpty());
        assertTrue("app context mutations: " + mutations, mutations.isEmpty());
        assertNotNull(ProxyExchange.Scheduler.INSTANCE.executorIfCreated());
    }

    // ---- AC-6 ----

    // REL-PROXY-002 AC-6: after 1000 proxied requests completed under the default 5-minute total timeout the scheduler
    // queue holds no reservation, while a request in flight holds one
    @Test(timeout = 120000)
    public void rel_PROXY_002_AC6_completedRequestsLeaveNoReservationInTheQueue() throws Exception {
        RawBackend backend = RawBackend.answering(RawBackend.response(200, "ok"));
        closeables.add(backend);
        SilentBackend silent = silentBackend(true);
        Proxy p = Proxy.to(backend.url());
        assertEquals(TimeUnit.MINUTES.toNanos(5), p.totalTimeoutNanos());
        InMemoryHttpAdapter a = app(p, Proxy.to(silent.url()));

        for (int i = 0; i < 1000; i++) {
            ClientResponse r = a.submit(ClientRequest.of("GET", "/http/" + i)).get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("request " + i, 200, r.status());
        }
        assertEquals(1000, backend.requestCount());
        awaitQueued(0);

        // The accessor sees a reservation of a request in flight, which leaves once that request ends.
        Future<ClientResponse> held = a.submit(ClientRequest.of("GET", "/https/x"));
        silent.awaitConnections(1);
        assertTrue("queued while a request is in flight", ProxyExchange.Scheduler.INSTANCE.queuedReservations() >= 1);
        silent.close();
        held.get(WAIT_SECONDS, TimeUnit.SECONDS);
        awaitQueued(0);
    }

    // ---- AC-7 ----

    // REL-PROXY-002 AC-7: the total timeout firing on an https exchange closes the plain socket and never calls close()
    // on the SSLSocket
    @Test(timeout = 30000)
    public void rel_PROXY_002_AC7_theTotalTimeoutClosesThePlainSocketOnly() throws Exception {
        RecordingSocket plain = new RecordingSocket();
        RecordingSslSocket ssl = new RecordingSslSocket(plain);
        ProxyExchange exchange = new ProxyExchange(null);
        exchange.plainSocket(plain);
        exchange.attach(ssl);

        exchange.watch(ProxyExchange.Scheduler.INSTANCE, TimeUnit.MILLISECONDS.toNanos(200));

        assertTrue("the plain socket was closed", plain.closed.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertSame(ProxyException.Kind.TOTAL_TIMEOUT, exchange.firedKind());
        Thread.sleep(100);
        assertEquals("close() calls on the SSLSocket", 0, ssl.closes.get());
        exchange.release();
    }

    // REL-PROXY-002 AC-7: the write timeout firing on an https exchange whose write is blocked closes the plain socket
    // and never calls close() on the SSLSocket
    @Test(timeout = 30000)
    public void rel_PROXY_002_AC7_theWriteTimeoutClosesThePlainSocketOnly() throws Exception {
        final RecordingSocket plain = new RecordingSocket();
        RecordingSslSocket ssl = new RecordingSslSocket(plain);
        ProxyExchange exchange = new ProxyExchange(null);
        exchange.plainSocket(plain);
        exchange.attach(ssl);
        OutputStream blocked = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                try {
                    plain.closed.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new SocketException("Socket closed");
            }
        };
        OutputStream out = exchange.backendOutput(blocked, TimeUnit.MILLISECONDS.toNanos(200));

        try {
            out.write(new byte[] {1, 2, 3});
            out.flush();
            fail("the blocked write failed");
        } catch (IOException expected) {
            // the plain socket was closed under it
        }

        assertSame(ProxyException.Kind.WRITE_TIMEOUT, exchange.firedKind());
        assertTrue("close() calls on the plain socket", plain.closes.get() >= 1);
        assertEquals("close() calls on the SSLSocket", 0, ssl.closes.get());
        exchange.release();
    }

    // REL-PROXY-002 AC-7: the total deadline firing during an https handshake closes the plain socket, never the
    // SSLSocket, and the handshake fails with TOTAL_TIMEOUT
    @Test(timeout = 30000)
    public void rel_PROXY_002_AC7_theHandshakeDeadlineClosesThePlainSocketOnly() throws Exception {
        TlsConnector.endpointIdentification = false;
        final RecordingSocket plain = new RecordingSocket();
        final AtomicReference<RecordingSslSocket> made = new AtomicReference<>();
        SSLSocketFactory factory = new StubSslSocketFactory() {
            @Override
            public Socket createSocket(Socket s, String host, int port, boolean autoClose) {
                RecordingSslSocket ssl = new RecordingSslSocket(plain);
                made.set(ssl);
                return ssl;
            }
        };

        // The handshake runs under the request's own total-timeout reservation, the one deadline of the exchange.
        ProxyExchange exchange = new ProxyExchange(null);
        exchange.plainSocket(plain);
        exchange.watch(ProxyExchange.Scheduler.INSTANCE, TimeUnit.MILLISECONDS.toNanos(200));
        try {
            TlsConnector.layer(plain, "localhost", 443, 10_000, exchange, factory);
            fail("the handshake failed");
        } catch (ProxyException e) {
            assertSame(ProxyException.Kind.TOTAL_TIMEOUT, e.kind());
        }

        assertTrue("close() calls on the plain socket", plain.closes.get() >= 1);
        assertEquals("close() calls on the SSLSocket", 0, made.get().closes.get());
        exchange.release();
    }

    // ---- AC-8 ----

    // REL-PROXY-002 AC-8: stopping one app neither shuts down nor waits for the scheduler, and a proxy of a second app
    // still running in the same JVM still gets 504 TOTAL_TIMEOUT from .totalTimeout(1, SECONDS)
    @Test(timeout = 60000)
    public void rel_PROXY_002_AC8_stoppingOneAppLeavesTheSchedulerToTheOthers() throws Exception {
        SilentBackend backend = silentBackend(true);
        RawBackend answering = RawBackend.answering(RawBackend.response(200, "ok"));
        closeables.add(answering);
        InMemoryHttpAdapter first = app(Proxy.to(answering.url()), Proxy.to("https://127.0.0.1:9"));
        RestApp firstApp = apps.get(apps.size() - 1);
        InMemoryHttpAdapter second = app(Proxy.to(backend.url()).totalTimeout(1, TimeUnit.SECONDS),
                Proxy.to("https://127.0.0.1:9"));
        assertEquals(200, first.submit(ClientRequest.of("GET", "/http/x")).get(WAIT_SECONDS, TimeUnit.SECONDS)
                .status());
        ScheduledThreadPoolExecutor executor = ProxyExchange.Scheduler.INSTANCE.executorIfCreated();
        assertNotNull(executor);

        long s0 = System.nanoTime();
        firstApp.shutdown(2, TimeUnit.SECONDS);
        long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - s0);
        apps.remove(firstApp);

        assertSame(executor, ProxyExchange.Scheduler.INSTANCE.executorIfCreated());
        assertFalse("the scheduler was shut down", executor.isShutdown());
        assertTrue("stopping the app took " + stopMillis + " ms", stopMillis < 2000);
        errors.clear();
        long t0 = System.nanoTime();
        ClientResponse r = second.submit(ClientRequest.of("GET", "/http/x")).get(WAIT_SECONDS, TimeUnit.SECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertEquals(504, r.status());
        assertOnlyKind(ProxyException.Kind.TOTAL_TIMEOUT);
        assertAbout(1000, millis);
    }

    // ---- AC-9 ----

    // REL-PROXY-002 AC-9: while one proxied request is blocked writing a body to a backend that never reads it, the
    // total timeout of another request still fires about on time
    @Test(timeout = 60000)
    public void rel_PROXY_002_AC9_aBlockedWriterDoesNotDelayAnotherTotalTimeout() throws Exception {
        SilentBackend neverReads = silentBackend(false);
        SilentBackend silent = silentBackend(true);
        // The blocked request's own write deadline fires inside the window too, so the watchdog thread handles it
        // while the other request's total deadline is due.
        InMemoryHttpAdapter a = app(Proxy.to(neverReads.url()).bodyLimit(64 << 20).writeTimeout(1, TimeUnit.SECONDS),
                Proxy.to(silent.url()).totalTimeout(1, TimeUnit.SECONDS));
        byte[] body = new byte[32 << 20];

        Future<ClientResponse> blocked = a.submit(ClientRequest.of("POST", "/http/x")
                .header("Content-Length", Integer.toString(body.length)).body(body));
        neverReads.awaitConnections(1);
        // Let the body fill the socket buffers, so the first request waits inside a write.
        Thread.sleep(500);
        assertFalse("the blocked request is still in flight", blocked.isDone());

        long t0 = System.nanoTime();
        ClientResponse r = a.submit(ClientRequest.of("GET", "/https/x")).get(WAIT_SECONDS, TimeUnit.SECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertEquals(504, r.status());
        assertAbout(1000, millis);
        assertEquals(504, blocked.get(WAIT_SECONDS, TimeUnit.SECONDS).status());
        List<ProxyException.Kind> kinds = new ArrayList<>();
        synchronized (errors) {
            for (Throwable t : errors) {
                kinds.add(((ProxyException) t).kind());
            }
        }
        assertEquals("kinds: " + kinds, 2, kinds.size());
        assertTrue("kinds: " + kinds, kinds.contains(ProxyException.Kind.TOTAL_TIMEOUT));
        assertTrue("kinds: " + kinds, kinds.contains(ProxyException.Kind.WRITE_TIMEOUT));
    }

    // REL-PROXY-002 AC-9: an https exchange whose SSLSocket.close() would block behind a stuck writer does not hold
    // up the watchdog thread when its total deadline fires, because only its plain socket is closed, so another
    // exchange's later total deadline still fires about on time
    @Test(timeout = 30000)
    public void rel_PROXY_002_AC9_aFiringDeadlineNeverWaitsOnABlockedSslSocket() throws Exception {
        RecordingSocket plainA = new RecordingSocket();
        RecordingSslSocket stuck = new RecordingSslSocket(plainA);
        stuck.closeBlocks = new CountDownLatch(1);
        ProxyExchange a = new ProxyExchange(null);
        a.plainSocket(plainA);
        a.attach(stuck);
        RecordingSocket plainB = new RecordingSocket();
        ProxyExchange b = new ProxyExchange(null);
        b.plainSocket(plainB);
        b.attach(new RecordingSslSocket(plainB));
        try {
            long t0 = System.nanoTime();
            a.watch(ProxyExchange.Scheduler.INSTANCE, TimeUnit.MILLISECONDS.toNanos(100));
            b.watch(ProxyExchange.Scheduler.INSTANCE, TimeUnit.MILLISECONDS.toNanos(400));

            assertTrue("the second exchange's deadline fired", plainB.closed.await(5, TimeUnit.SECONDS));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            assertTrue("fired after " + millis + " ms", millis >= 300 && millis < 2000);
            assertSame(ProxyException.Kind.TOTAL_TIMEOUT, a.firedKind());
            assertSame(ProxyException.Kind.TOTAL_TIMEOUT, b.firedKind());
            assertTrue("the first exchange's plain socket was closed", plainA.closes.get() >= 1);
            assertEquals("close() calls on the stuck SSLSocket", 0, stuck.closes.get());
        } finally {
            stuck.closeBlocks.countDown();
            b.release();
        }
    }

    // ---- the fresh JVM of AC-1 ----

    /**
     * Runs in its own JVM (REL-PROXY-002 AC-1): loads {@code Proxy}, configures and mounts proxies on a started app,
     * prints the scheduler and the watchdog threads, sends the first proxied request and prints them again.
     */
    public static final class LazyProbe {
        private LazyProbe() {
        }

        public static void main(String[] args) throws Exception {
            int status = 1;
            RawBackend backend = RawBackend.answering(RawBackend.response(200, "ok"));
            InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
            RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                    .logger(new RecordingLogger()).build();
            List<String> mutations = Collections.synchronizedList(new ArrayList<String>());
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
            try {
                Class.forName("com.snoworca.naruserve.proxy.Proxy");
                app.use("/http", recordingContext(Proxy.to(backend.url()).totalTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(5, TimeUnit.SECONDS).retries(1), mutations));
                app.use("/https", Proxy.to("https://127.0.0.1:" + backend.port())
                        .totalTimeout(30, TimeUnit.SECONDS));
                app.onError((error, req, res) -> errors.add(error));
                app.listen(0);
                Thread.sleep(200);
                System.out.println("before: scheduler="
                        + (ProxyExchange.Scheduler.INSTANCE.executorIfCreated() == null ? "none" : "created")
                        + " threads=" + names(watchdogThreads()));
                ClientResponse r = adapter.submit(ClientRequest.of("GET", "/http/x")).get(WAIT_SECONDS,
                        TimeUnit.SECONDS);
                System.out.println("after: scheduler="
                        + (ProxyExchange.Scheduler.INSTANCE.executorIfCreated() == null ? "none" : "created")
                        + " status=" + r.status() + " body=" + r.text() + " threads=" + names(watchdogThreads()));
                System.out.println("context mutations=" + mutations + " errors=" + errors);
                status = 0;
            } catch (Throwable t) {
                t.printStackTrace(System.out);
            } finally {
                app.shutdown(2, TimeUnit.SECONDS);
                backend.close();
            }
            System.out.flush();
            System.exit(status);
        }
    }

    // ---- helpers ----

    /** A started app with {@code http} mounted at {@code /http} and {@code https} at {@code /https}. */
    private InMemoryHttpAdapter app(Middleware http, Middleware https) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        app.use("/http", http);
        app.use("/https", https);
        app.onError((error, req, res) -> errors.add(error));
        app.listen(0);
        apps.add(app);
        return adapter;
    }

    /** Runs {@link LazyProbe} in a fresh JVM on this test JVM's class path and returns its output; it must exit 0. */
    private static String runLazyProbe() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(LazyProbe.class.getName());
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        assertTrue("the probe JVM ended in time:\n" + out, p.waitFor(WAIT_SECONDS * 2, TimeUnit.SECONDS));
        assertEquals("probe output:\n" + out, 0, p.exitValue());
        return out;
    }

    /**
     * {@code proxy} behind a request wrapper whose {@code application()} records every call that would add an app
     * resource or attribute ({@code own}, {@code attribute(key, value)}, {@code removeAttribute}) into
     * {@code mutations} before passing it on.
     */
    static Middleware recordingContext(final Proxy proxy, final List<String> mutations) {
        return (req, res, next) -> {
            final ApplicationContext context = req.application();
            final ApplicationContext recording = (ApplicationContext) java.lang.reflect.Proxy.newProxyInstance(
                    ApplicationContext.class.getClassLoader(), new Class<?>[] {ApplicationContext.class},
                    (self, method, args) -> {
                        String name = method.getName();
                        if (name.equals("own") || name.equals("removeAttribute")
                                || (name.equals("attribute") && args != null && args.length == 2)) {
                            mutations.add(name + Arrays.asList(args));
                        }
                        try {
                            return method.invoke(context, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            Request wrapped = (Request) java.lang.reflect.Proxy.newProxyInstance(Request.class.getClassLoader(),
                    new Class<?>[] {Request.class}, (self, method, args) -> {
                        if (method.getName().equals("application") && (args == null || args.length == 0)) {
                            return recording;
                        }
                        try {
                            return method.invoke(req, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            proxy.handle(wrapped, res, next);
        };
    }

    private SilentBackend silentBackend(boolean drain) throws IOException {
        SilentBackend b = new SilentBackend(drain);
        closeables.add(b);
        return b;
    }

    /** The live threads named as a proxy watchdog. */
    static List<Thread> watchdogThreads() {
        List<Thread> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("naruserve-proxy") && t.getName().contains("watchdog")) {
                out.add(t);
            }
        }
        return out;
    }

    static List<String> names(List<Thread> threads) {
        List<String> out = new ArrayList<>();
        for (Thread t : threads) {
            out.add(t.getName());
        }
        return out;
    }

    private static void awaitQueued(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int queued = ProxyExchange.Scheduler.INSTANCE.queuedReservations();
        while (queued != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
            queued = ProxyExchange.Scheduler.INSTANCE.queuedReservations();
        }
        assertEquals("reservations in the scheduler queue", expected, queued);
    }

    private void assertOnlyKind(ProxyException.Kind kind) {
        synchronized (errors) {
            assertEquals("errors that reached onError: " + errors, 1, errors.size());
            assertTrue("a ProxyException: " + errors.get(0), errors.get(0) instanceof ProxyException);
            assertSame(kind, ((ProxyException) errors.get(0)).kind());
        }
    }

    /** {@code millis} is about {@code expected}: not early, and well before 6 seconds. */
    private static void assertAbout(long expected, long millis) {
        assertTrue("after " + millis + " ms, expected about " + expected, millis >= expected - 100);
        assertTrue("after " + millis + " ms, expected about " + expected, millis < 6000);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) != -1; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * A JDK-only backend on 127.0.0.1 that accepts every connection and never writes: it drains what the proxy sends
     * when {@code drain} is set (a silent HTTP backend or TLS handshake), and reads nothing otherwise (a backend that
     * never reads the request body). {@link #close} closes every connection.
     */
    private static final class SilentBackend implements Closeable {
        private final ServerSocket server;
        private final boolean drain;
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
        private final AtomicInteger connections = new AtomicInteger();

        SilentBackend(boolean drain) throws IOException {
            this.drain = drain;
            server = new ServerSocket(0, 100, InetAddress.getByName("127.0.0.1"));
            Thread acceptor = new Thread(this::acceptLoop, "silent-backend-" + server.getLocalPort());
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        String url() {
            return "http://127.0.0.1:" + port();
        }

        void awaitConnections(int n) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (connections.get() < n) {
                if (System.nanoTime() > deadline) {
                    fail("connections: " + connections.get() + " of " + n);
                }
                Thread.sleep(5);
            }
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                final Socket s;
                try {
                    s = server.accept();
                } catch (IOException e) {
                    return;
                }
                sockets.add(s);
                connections.incrementAndGet();
                if (drain) {
                    Thread t = new Thread(() -> {
                        try {
                            InputStream in = s.getInputStream();
                            byte[] buf = new byte[8192];
                            while (in.read(buf) >= 0) {
                                // dropped
                            }
                        } catch (IOException e) {
                            // the connection is over
                        }
                    }, "silent-backend-conn");
                    t.setDaemon(true);
                    t.start();
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            synchronized (sockets) {
                for (Socket s : sockets) {
                    s.close();
                }
            }
        }
    }

    /** An unconnected plain socket that records its {@code close()} calls. */
    private static final class RecordingSocket extends Socket {
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public synchronized void close() throws IOException {
            closes.incrementAndGet();
            closed.countDown();
            super.close();
        }
    }

    /** An {@code SSLSocket} over {@code plain} that records its {@code close()} calls; its handshake waits on plain. */
    private static final class RecordingSslSocket extends SSLSocket {
        final AtomicInteger closes = new AtomicInteger();
        /** When set, {@code close()} waits on it, as a close_notify stuck behind a blocked writer would. */
        volatile @Nullable CountDownLatch closeBlocks;
        private final RecordingSocket plain;

        RecordingSslSocket(RecordingSocket plain) {
            this.plain = plain;
        }

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
            CountDownLatch blocks = closeBlocks;
            if (blocks != null) {
                try {
                    blocks.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void startHandshake() throws IOException {
            try {
                plain.closed.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new SocketException("Socket closed");
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return new String[0];
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return new String[0];
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
        }

        @Override
        public String[] getSupportedProtocols() {
            return new String[0];
        }

        @Override
        public String[] getEnabledProtocols() {
            return new String[0];
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
        }

        @Override
        public SSLSession getSession() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
        }

        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
        }

        @Override
        public void setUseClientMode(boolean mode) {
        }

        @Override
        public boolean getUseClientMode() {
            return true;
        }

        @Override
        public void setNeedClientAuth(boolean need) {
        }

        @Override
        public boolean getNeedClientAuth() {
            return false;
        }

        @Override
        public void setWantClientAuth(boolean want) {
        }

        @Override
        public boolean getWantClientAuth() {
            return false;
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
        }

        @Override
        public boolean getEnableSessionCreation() {
            return true;
        }
    }

    /** An {@code SSLSocketFactory} whose self-connecting methods are never called. */
    private abstract static class StubSslSocketFactory extends SSLSocketFactory {
        @Override
        public String[] getDefaultCipherSuites() {
            return new String[0];
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return new String[0];
        }

        @Override
        public Socket createSocket(String host, int port) {
            throw new UnsupportedOperationException(Arrays.asList(host, port).toString());
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
            throw new UnsupportedOperationException(host);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new UnsupportedOperationException(String.valueOf(host));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
            throw new UnsupportedOperationException(String.valueOf(address));
        }
    }
}
