package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import org.junit.After;
import org.junit.Test;

/**
 * The proxy concurrency limit (FR-PROXY-019): {@code .maxConcurrent(n)} gives its result a counter shared by every
 * proxy later derived from it, a request that finds the counter at its limit gets 503 {@code CONCURRENCY_LIMIT} at once
 * without a backend connection, and a proxy without a limit counts nothing. The backend holds every request until the
 * test releases it, so "in flight" is decided by latches, not by sleeps.
 */
public class ProxyConcurrencyTest {

    /** How long a test waits for something that must happen. */
    private static final long WAIT_SECONDS = 10;

    private final List<Closeable> closeables = new ArrayList<>();
    private final List<RestApp> started = new ArrayList<>();
    private final List<HeldBackend> backends = new ArrayList<>();
    /** The errors that reached onError, in order. */
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());

    @After
    public void tearDown() throws IOException {
        for (HeldBackend b : backends) {
            b.releaseAll();
        }
        for (RestApp a : started) {
            a.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    // ---- fixtures ----

    /** A backend that holds every request until {@link HeldBackend#releaseAll}, then answers 200 {@code ok}. */
    private HeldBackend heldBackend() throws IOException {
        return backend(false);
    }

    /**
     * A backend whose responses are held in the body: it sends the head and the first body byte at once and the rest
     * after {@link HeldBackend#releaseAll}.
     */
    private HeldBackend bodyHeldBackend() throws IOException {
        return backend(true);
    }

    private HeldBackend backend(boolean holdInBody) throws IOException {
        HeldBackend b = new HeldBackend(holdInBody);
        backends.add(b);
        closeables.add(b);
        return b;
    }

    /** A started in-memory app whose onError records the error and answers its status. */
    private Mounted app() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        return new Mounted(adapter, t);
    }

    private final class Mounted {
        final InMemoryHttpAdapter adapter;
        final TestApp t;
        final RestApp app;

        Mounted(InMemoryHttpAdapter adapter, TestApp t) {
            this.adapter = adapter;
            this.t = t;
            this.app = t.app();
        }

        Mounted use(String path, Proxy proxy) {
            app.use(path, proxy);
            return this;
        }

        Mounted start() {
            app.onError((error, req, res) -> {
                errors.add(error);
                res.sendStatus(error instanceof HttpException ? ((HttpException) error).status() : 500);
            });
            t.start();
            started.add(app);
            return this;
        }

        Future<ClientResponse> submit(String target) {
            return adapter.submit(ClientRequest.of("GET", target));
        }
    }

    /** The response of {@code f} within {@link #WAIT_SECONDS}; a request still pending then fails the test. */
    private static ClientResponse answered(String what, Future<ClientResponse> f) throws Exception {
        try {
            return f.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new AssertionError(what + " was not answered within " + WAIT_SECONDS + "s: it is waiting on the "
                    + "backend instead of being refused at once");
        } catch (ExecutionException e) {
            throw new AssertionError(what + " failed: " + e.getCause(), e.getCause());
        }
    }

    /** Asserts that {@code r} is the 503 of one refused request and that onError saw CONCURRENCY_LIMIT for it. */
    private void assertRefused(String what, ClientResponse r) {
        assertEquals(what + " status", 503, r.status());
        assertEquals(what + " errors that reached onError: " + errors, 1, errors.size());
        Throwable e = errors.get(0);
        assertTrue(what + " error is a ProxyException: " + e, e instanceof ProxyException);
        assertEquals(what + " kind", ProxyException.Kind.CONCURRENCY_LIMIT, ((ProxyException) e).kind());
    }

    /** Releases {@code backend} and asserts every pending request ends 200 {@code ok}. */
    private static void releaseAndAssertOk(HeldBackend backend, List<Future<ClientResponse>> pending) throws Exception {
        backend.releaseAll();
        for (int i = 0; i < pending.size(); i++) {
            ClientResponse r = answered("pending request " + i, pending.get(i));
            assertEquals("pending request " + i + " status", 200, r.status());
            assertEquals("pending request " + i + " body", "ok", r.text());
        }
    }

    // ---- AC-1 ----

    // FR-PROXY-019 AC-1: on a .maxConcurrent(2) mount with a delayed backend, of three simultaneous requests the third
    // gets 503 at once without a backend connection, and onError receives ProxyException CONCURRENCY_LIMIT
    @Test(timeout = 60000)
    public void fr_PROXY_019_AC1_theThirdOfThreeDelayedRequestsIsRefusedAtOnce() throws Exception {
        HeldBackend backend = heldBackend();
        Mounted m = app().use("/api", Proxy.to(backend.url()).maxConcurrent(2)).start();

        List<Future<ClientResponse>> pending = new ArrayList<>();
        pending.add(m.submit("/api/1"));
        pending.add(m.submit("/api/2"));
        backend.awaitArrivals(2);

        ClientResponse third = answered("the third request", m.submit("/api/3"));

        assertRefused("the third request", third);
        assertEquals("backend connections", 2, backend.connections());
        releaseAndAssertOk(backend, pending);
        assertEquals("backend connections once every request ended", 2, backend.connections());
    }

    // ---- AC-2 ----

    // FR-PROXY-019 AC-2: a third request that arrives while the first two are still relaying their bodies gets 503,
    // and once both bodies have been fully relayed the next request reaches the backend
    @Test(timeout = 60000)
    public void fr_PROXY_019_AC2_slotsAreHeldUntilTheRelayedBodiesEnd() throws Exception {
        HeldBackend backend = bodyHeldBackend();
        Proxy proxy = Proxy.to(backend.url()).maxConcurrent(2);
        Mounted m = app().use("/api", proxy).start();

        List<Future<ClientResponse>> pending = new ArrayList<>();
        pending.add(m.submit("/api/1"));
        pending.add(m.submit("/api/2"));
        backend.awaitArrivals(2);
        backend.awaitBodiesStarted(2);

        ClientResponse third = answered("the request during the relays", m.submit("/api/3"));
        assertRefused("the request during the relays", third);
        assertEquals("backend connections during the relays", 2, backend.connections());

        releaseAndAssertOk(backend, pending);
        assertEquals("backend connections once the relays ended", 2, backend.connections());
        awaitCounterZero(proxy);
        errors.clear();
        Future<ClientResponse> next = m.submit("/api/4");
        backend.awaitArrivals(3);
        ClientResponse fourth = answered("the request after the relays", next);
        assertEquals("the request after the relays", 200, fourth.status());
        assertEquals("ok", fourth.text());
        assertTrue("no error after the relays: " + errors, errors.isEmpty());
    }

    /**
     * Waits until the counter of {@code p} is back at zero: the core reports a relayed response done to the client a
     * moment before it closes the relayed stream, which is where the slot is returned.
     */
    private static void awaitCounterZero(Proxy p) throws InterruptedException {
        AtomicInteger counter = p.concurrencyCounter();
        assertNotNull("a limited proxy has a counter", counter);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (counter.get() != 0) {
            if (System.nanoTime() > deadline) {
                fail("the slots were not returned after the relayed bodies ended: counter=" + counter.get());
            }
            Thread.sleep(5);
        }
    }

    // ---- AC-3 ----

    // FR-PROXY-019 AC-3: p = Proxy.to(url).maxConcurrent(2) on /a and p.retries(0) on /b share one counter, so of three
    // simultaneous delayed requests split across /a and /b the third is refused at once
    @Test(timeout = 60000)
    public void fr_PROXY_019_AC3_aDerivedProxySharesTheCounter() throws Exception {
        HeldBackend backend = heldBackend();
        Proxy p = Proxy.to(backend.url()).maxConcurrent(2);
        Mounted m = app().use("/a", p).use("/b", p.retries(0)).start();

        List<Future<ClientResponse>> pending = new ArrayList<>();
        pending.add(m.submit("/a/1"));
        pending.add(m.submit("/b/2"));
        backend.awaitArrivals(2);

        ClientResponse third = answered("the third request", m.submit("/a/3"));

        assertRefused("the third request", third);
        assertEquals("backend connections", 2, backend.connections());
        releaseAndAssertOk(backend, pending);
        assertEquals("backend connections once every request ended", 2, backend.connections());
    }

    // ---- AC-4 ----

    // FR-PROXY-019 AC-4: two objects created separately as Proxy.to(url).maxConcurrent(2) on /a and /b do not share a
    // counter: two simultaneous delayed requests on each path all four reach the backend
    @Test(timeout = 60000)
    public void fr_PROXY_019_AC4_separatelyCreatedProxiesDoNotShareACounter() throws Exception {
        HeldBackend backend = heldBackend();
        Mounted m = app().use("/a", Proxy.to(backend.url()).maxConcurrent(2))
                .use("/b", Proxy.to(backend.url()).maxConcurrent(2)).start();

        List<Future<ClientResponse>> pending = submitTwoEach(m);

        backend.awaitArrivals(4);
        assertTrue("no request was refused: " + errors, errors.isEmpty());
        releaseAndAssertOk(backend, pending);
    }

    private static List<Future<ClientResponse>> submitTwoEach(Mounted m) {
        List<Future<ClientResponse>> pending = new ArrayList<>();
        pending.add(m.submit("/a/1"));
        pending.add(m.submit("/a/2"));
        pending.add(m.submit("/b/1"));
        pending.add(m.submit("/b/2"));
        return pending;
    }

    // ---- AC-5 ----

    // FR-PROXY-019 AC-5: p = Proxy.to(url).maxConcurrent(2) on /a and p.maxConcurrent(2) on /b: calling maxConcurrent
    // again creates a new counter, so two simultaneous delayed requests on each path all four reach the backend
    @Test(timeout = 60000)
    public void fr_PROXY_019_AC5_maxConcurrentOnADerivedProxyCreatesANewCounter() throws Exception {
        HeldBackend backend = heldBackend();
        Proxy p = Proxy.to(backend.url()).maxConcurrent(2);
        Mounted m = app().use("/a", p).use("/b", p.maxConcurrent(2)).start();

        List<Future<ClientResponse>> pending = submitTwoEach(m);

        backend.awaitArrivals(4);
        assertTrue("no request was refused: " + errors, errors.isEmpty());
        releaseAndAssertOk(backend, pending);
    }

    // ---- AC-6 ----

    // FR-PROXY-019 AC-6: with Proxy.to(url).maxConcurrent(2) on /a and an unlimited Proxy.to(url) on /b, while five
    // delayed requests through /b are in flight, two requests through /a both reach the backend
    @Test(timeout = 60000)
    public void fr_PROXY_019_AC6_anUnlimitedMountDoesNotCountAgainstALimitedOne() throws Exception {
        HeldBackend backend = heldBackend();
        Mounted m = app().use("/a", Proxy.to(backend.url()).maxConcurrent(2)).use("/b", Proxy.to(backend.url()))
                .start();

        List<Future<ClientResponse>> pending = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pending.add(m.submit("/b/" + i));
        }
        backend.awaitArrivals(5);
        pending.add(m.submit("/a/1"));
        pending.add(m.submit("/a/2"));

        backend.awaitArrivals(7);
        assertTrue("no request was refused: " + errors, errors.isEmpty());
        releaseAndAssertOk(backend, pending);
    }

    // ---- AC-7 ----

    // FR-PROXY-019 AC-7: Proxy.to(url) reports no limit through its package-private accessors, and twenty simultaneous
    // requests through it to a delayed backend all reach the backend
    @Test(timeout = 60000)
    public void fr_PROXY_019_AC7_theDefaultHasNoLimit() throws Exception {
        HeldBackend backend = heldBackend();
        Proxy p = Proxy.to(backend.url());
        assertEquals("maxConcurrent()", Proxy.NO_CONCURRENCY_LIMIT, p.maxConcurrent());
        assertNull("concurrencyCounter()", p.concurrencyCounter());
        Mounted m = app().use("/api", p).start();

        List<Future<ClientResponse>> pending = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            pending.add(m.submit("/api/" + i));
        }

        backend.awaitArrivals(20);
        assertTrue("no request was refused: " + errors, errors.isEmpty());
        releaseAndAssertOk(backend, pending);
    }

    // ---- AC-8 ----

    // FR-PROXY-019 AC-8: one object created with .maxConcurrent(1) mounted in two apps: while a delayed request through
    // the first app is in flight, a request through the second app gets 503 with kind CONCURRENCY_LIMIT
    @Test(timeout = 60000)
    public void fr_PROXY_019_AC8_theCounterIsSharedAcrossApps() throws Exception {
        HeldBackend backend = heldBackend();
        Proxy p = Proxy.to(backend.url()).maxConcurrent(1);
        Mounted first = app().use("/api", p).start();
        Mounted second = app().use("/api", p).start();

        List<Future<ClientResponse>> pending = new ArrayList<>();
        pending.add(first.submit("/api/1"));
        backend.awaitArrivals(1);

        ClientResponse other = answered("the request through the second app", second.submit("/api/2"));

        assertRefused("the request through the second app", other);
        assertEquals("backend connections", 1, backend.connections());
        releaseAndAssertOk(backend, pending);
        assertEquals("backend connections once every request ended", 1, backend.connections());
    }

    // ---- slot return on the paths that end before the relay ----

    // FR-PROXY-019 AC-9: a request whose backend connect fails returns its slot before its 502 leaves, so on a
    // .maxConcurrent(1) mount the next request is 502 again, not 503, and the counter is back at zero each time
    @Test(timeout = 30000)
    public void fr_PROXY_019_AC9_aFailedConnectReturnsTheSlot() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = probe.getLocalPort();
        }
        Proxy p = Proxy.to("http://127.0.0.1:" + closedPort).maxConcurrent(1);
        Mounted m = app().use("/api", p).start();

        for (int i = 0; i < 3; i++) {
            ClientResponse r = answered("request " + i, m.submit("/api/" + i));
            assertEquals("request " + i + " status", 502, r.status());
            assertEquals("counter after request " + i, 0, p.concurrencyCounter().get());
        }
    }

    // FR-PROXY-019 AC-9: a backend answer the proxy cannot read is 502 and returns the slot, so the next request on the
    // .maxConcurrent(1) mount reaches the backend again
    @Test(timeout = 30000)
    public void fr_PROXY_019_AC9_anUnreadableResponseReturnsTheSlot() throws Exception {
        RawBackend backend = RawBackend.answering("NOT HTTP\r\n\r\n");
        closeables.add(backend);
        Proxy p = Proxy.to(backend.url()).maxConcurrent(1);
        Mounted m = app().use("/api", p).start();

        ClientResponse first = answered("the first request", m.submit("/api/1"));
        ClientResponse second = answered("the second request", m.submit("/api/2"));

        assertEquals("the first request", 502, first.status());
        assertEquals("the second request", 502, second.status());
        assertEquals("backend requests", 2, backend.requestCount());
        assertEquals("counter", 0, p.concurrencyCounter().get());
    }

    // FR-PROXY-019 AC-9: when res.relay refuses the body, closing it as a response does when it refuses a stream, the
    // slot is returned exactly once: the refused stream's close and the forward path both end the exchange, and the
    // counter is 0, not -1, so a .maxConcurrent(1) mount still admits one request at a time afterwards
    @Test(timeout = 30000)
    public void fr_PROXY_019_AC9_aRefusedRelayReturnsTheSlotOnce() throws Exception {
        RawBackend backend = RawBackend.answering(RawBackend.response(200, "ok"));
        closeables.add(backend);
        Proxy p = Proxy.to(backend.url()).maxConcurrent(1);
        List<Throwable> proxyFailures = Collections.synchronizedList(new ArrayList<Throwable>());
        Mounted m = app();
        m.app.use("/refuse", (req, res, next) -> {
            try {
                p.handle(req, refusingRelay(res), next);
            } catch (Exception | Error e) {
                proxyFailures.add(e);
            }
            res.status(200).send("after " + p.concurrencyCounter().get());
        });
        m.use("/api", p).start();

        ClientResponse refused = answered("the request whose relay is refused", m.submit("/refuse/x"));

        assertEquals("backend requests", 1, backend.requestCount());
        assertEquals("the refused relay failed the proxy call: " + proxyFailures, 1, proxyFailures.size());
        assertEquals("counter seen right after the refused relay", "after 0", refused.text());
        assertEquals("counter after the refused relay", 0, p.concurrencyCounter().get());
        ClientResponse next = answered("the next request", m.submit("/api/y"));
        assertEquals("the next request", 200, next.status());
        assertEquals("counter after the next request", 0, waitForZero(p.concurrencyCounter()));
    }

    /** {@code res} with a {@code relay} that closes the stream and throws, as a response refusing the body does. */
    private static Response refusingRelay(Response res) {
        return (Response) java.lang.reflect.Proxy.newProxyInstance(Response.class.getClassLoader(),
                new Class<?>[] {Response.class}, (self, method, args) -> {
                    if (method.getName().equals("relay")) {
                        ((InputStream) args[0]).close();
                        throw new IllegalStateException("relay refused");
                    }
                    try {
                        return method.invoke(res, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** The value of {@code counter} once it is 0, or its value after {@link #WAIT_SECONDS}. */
    private static int waitForZero(AtomicInteger counter) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (counter.get() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return counter.get();
    }

    // ---- AC-9 ----

    // FR-PROXY-019 AC-9: the package-private counter accessor returns the same AtomicInteger for p and p.retries(0)
    // and a different one for p.maxConcurrent(2)
    @Test
    public void fr_PROXY_019_AC9_derivedProxiesShareTheCounterInstance() {
        Proxy p = Proxy.to("http://127.0.0.1:8081").maxConcurrent(2);
        AtomicInteger counter = p.concurrencyCounter();

        assertNotNull("a limited proxy has a counter", counter);
        assertSame("p.retries(0)", counter, p.retries(0).concurrencyCounter());
        assertSame("p.stripPrefix(true).bodyLimit(10)", counter,
                p.stripPrefix(true).bodyLimit(10).concurrencyCounter());
        assertNotSame("p.maxConcurrent(2)", counter, p.maxConcurrent(2).concurrencyCounter());
        assertEquals("p.maxConcurrent(2).maxConcurrent()", 2, p.maxConcurrent(2).maxConcurrent());
        assertNotSame("a second Proxy.to(url).maxConcurrent(2)", counter,
                Proxy.to("http://127.0.0.1:8081").maxConcurrent(2).concurrencyCounter());
    }

    // FR-PROXY-019 AC-9: the slot is taken with a compare-and-set that refuses at the limit and returned with a
    // decrement, without a lock: no synchronized method and no lock field, and under contention exactly the limit wins
    @Test(timeout = 30000)
    public void fr_PROXY_019_AC9_theSlotIsTakenAndReturnedWithoutALock() throws Exception {
        for (Method method : ConcurrencyLimiter.class.getDeclaredMethods()) {
            assertFalse(method + " is synchronized", Modifier.isSynchronized(method.getModifiers()));
        }
        for (Field field : ConcurrencyLimiter.class.getDeclaredFields()) {
            assertFalse(field + " is a lock", Lock.class.isAssignableFrom(field.getType())
                    || Semaphore.class.isAssignableFrom(field.getType()));
        }

        ConcurrencyLimiter limiter = new ConcurrencyLimiter(3);
        assertEquals("limit", 3, limiter.limit());
        assertEquals("a new counter", 0, limiter.counter().get());
        int threads = 16;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger wins = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    if (limiter.tryAcquire()) {
                        wins.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        go.countDown();
        assertTrue("every contender finished without waiting", done.await(WAIT_SECONDS, TimeUnit.SECONDS));

        assertEquals("slots won under contention", 3, wins.get());
        assertEquals("counter at the limit", 3, limiter.counter().get());
        assertFalse("a request at the limit is refused", limiter.tryAcquire());
        assertEquals("a refusal leaves the counter", 3, limiter.counter().get());
        limiter.release();
        assertEquals("a release returns one slot", 2, limiter.counter().get());
        assertTrue("the returned slot can be taken again", limiter.tryAcquire());
    }

    /**
     * A JDK-only backend over a raw {@link ServerSocket} on 127.0.0.1 (D-070) that serves every connection on its own
     * thread, so many requests can be in flight at once. Each request is read, counted and then held until
     * {@link #releaseAll}; a body-held backend sends the head and the first body byte before it waits.
     */
    private static final class HeldBackend implements Closeable {
        private static final String BODY = "ok";

        private final boolean holdInBody;
        private final ServerSocket server;
        private final Thread acceptor;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger connections = new AtomicInteger();
        private final Semaphore arrivals = new Semaphore(0);
        private final Semaphore bodiesStarted = new Semaphore(0);
        private final AtomicInteger arrived = new AtomicInteger();
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());

        HeldBackend(boolean holdInBody) throws IOException {
            this.holdInBody = holdInBody;
            this.server = new ServerSocket(0, 100, InetAddress.getByName("127.0.0.1"));
            this.acceptor = new Thread(this::acceptLoop, "held-backend-" + server.getLocalPort());
            this.acceptor.setDaemon(true);
            this.acceptor.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        /** Waits until {@code total} requests in all have been read. */
        void awaitArrivals(int total) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (arrived.get() < total) {
                long left = deadline - System.nanoTime();
                if (left <= 0 || !arrivals.tryAcquire(left, TimeUnit.NANOSECONDS)) {
                    fail("expected " + total + " requests at the backend, got " + arrived.get());
                }
            }
        }

        /** Waits until {@code n} held responses have sent their head and first body byte. */
        void awaitBodiesStarted(int n) throws InterruptedException {
            assertTrue(n + " bodies started", bodiesStarted.tryAcquire(n, WAIT_SECONDS, TimeUnit.SECONDS));
        }

        void releaseAll() {
            release.countDown();
        }

        @Override
        public void close() throws IOException {
            release.countDown();
            server.close();
            synchronized (sockets) {
                for (Socket s : sockets) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // closing either way
                    }
                }
            }
            try {
                acceptor.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                Socket s;
                try {
                    s = server.accept();
                } catch (IOException closed) {
                    return;
                }
                connections.incrementAndGet();
                sockets.add(s);
                Thread t = new Thread(() -> serve(s), "held-backend-conn");
                t.setDaemon(true);
                t.start();
            }
        }

        private void serve(Socket s) {
            try (Socket socket = s) {
                socket.setSoTimeout(30_000);
                readHead(socket.getInputStream());
                arrived.incrementAndGet();
                arrivals.release();
                OutputStream out = socket.getOutputStream();
                byte[] head = ("HTTP/1.1 200 OK\r\nContent-Length: " + BODY.length() + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1);
                byte[] body = BODY.getBytes(StandardCharsets.ISO_8859_1);
                if (holdInBody) {
                    out.write(head);
                    out.write(body, 0, 1);
                    out.flush();
                    bodiesStarted.release();
                    release.await(30, TimeUnit.SECONDS);
                    out.write(body, 1, body.length - 1);
                } else {
                    release.await(30, TimeUnit.SECONDS);
                    out.write(head);
                    out.write(body);
                }
                out.flush();
                // Keep the connection until the proxy closes it, so the relay ends by the length, not by EOF.
                InputStream in = socket.getInputStream();
                while (in.read() >= 0) {
                    // drain until the proxy closes
                }
            } catch (IOException | InterruptedException e) {
                // the proxy dropped the connection or the test ended
            }
        }

        private static void readHead(InputStream in) throws IOException {
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            while (matched < end.length) {
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("EOF inside the request head");
                }
                matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            }
        }
    }
}
