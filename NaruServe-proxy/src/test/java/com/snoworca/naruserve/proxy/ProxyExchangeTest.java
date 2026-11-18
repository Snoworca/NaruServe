package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * The lifetime of one proxied request (REL-PROXY-001) over the in-memory adapter: the relayed stream's {@code close()}
 * and the {@code res.onAbort} notification end the exchange exactly once, the paths that end inside the middleware
 * return the slot and cancel the total-timeout watchdog's reservation before {@code onError} runs, the refusals that
 * come before the slot take neither, and the proxy does its backend I/O on the thread that called
 * {@code Middleware.handle}. The watchdog is the package-private seam a recording implementation is injected through.
 */
public class ProxyExchangeTest {

    /** How long a test waits for something that must happen. */
    private static final long WAIT_SECONDS = 10;

    private final List<Closeable> closeables = new ArrayList<>();
    private final List<RestApp> started = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();

    @After
    public void tearDown() throws IOException {
        BackendClient.resolver = BackendClient.SYSTEM_RESOLVER;
        for (CountDownLatch gate : gates) {
            gate.countDown();
        }
        for (RestApp a : started) {
            a.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    // ---- fixtures ----

    /** A total-timeout watchdog that records every reservation and every cancel of it. */
    private static final class RecordingWatchdog implements ProxyExchange.Watchdog {
        final List<RecordingReservation> reservations =
                Collections.synchronizedList(new ArrayList<RecordingReservation>());

        @Override
        public ProxyExchange.Reservation reserve(ProxyExchange exchange, long totalTimeoutNanos) {
            RecordingReservation r = new RecordingReservation(totalTimeoutNanos);
            reservations.add(r);
            return r;
        }

        int reservationCount() {
            return reservations.size();
        }

        RecordingReservation only() {
            synchronized (reservations) {
                assertEquals("watchdog reservations", 1, reservations.size());
                return reservations.get(0);
            }
        }

        RecordingReservation last() {
            synchronized (reservations) {
                assertFalse("no watchdog reservation", reservations.isEmpty());
                return reservations.get(reservations.size() - 1);
            }
        }
    }

    private static final class RecordingReservation implements ProxyExchange.Reservation {
        final long totalTimeoutNanos;
        final AtomicInteger cancels = new AtomicInteger();

        RecordingReservation(long totalTimeoutNanos) {
            this.totalTimeoutNanos = totalTimeoutNanos;
        }

        @Override
        public void cancel() {
            cancels.incrementAndGet();
        }
    }

    /** What a capturing response took instead of passing on: the onAbort listener and the relayed stream. */
    private static final class Captured {
        final AtomicReference<Runnable> abortListener = new AtomicReference<>();
        final AtomicReference<InputStream> relayed = new AtomicReference<>();
    }

    /**
     * {@code res} with {@code onAbort} and {@code relay} kept in {@code captured} instead of passed on, so the test
     * drives the relayed stream and the abort notification itself; every other call goes to {@code res}.
     */
    private static Response capturing(Response res, Captured captured) {
        return (Response) java.lang.reflect.Proxy.newProxyInstance(Response.class.getClassLoader(),
                new Class<?>[] {Response.class}, (self, method, args) -> {
                    if (method.getName().equals("onAbort")) {
                        captured.abortListener.set((Runnable) args[0]);
                        return null;
                    }
                    if (method.getName().equals("relay")) {
                        captured.relayed.set((InputStream) args[0]);
                        return null;
                    }
                    try {
                        return method.invoke(res, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private Mounted app() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        return new Mounted(adapter, TestApp.create(adapter));
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

        Mounted start() {
            t.start();
            started.add(app);
            return this;
        }
    }

    private HoldingBackend backend(HoldingBackend.Mode mode) throws IOException {
        HoldingBackend b = new HoldingBackend(mode, 0);
        closeables.add(b);
        return b;
    }

    private CountDownLatch gate() {
        CountDownLatch g = new CountDownLatch(1);
        gates.add(g);
        return g;
    }

    private static void await(String what, CountDownLatch latch) throws InterruptedException {
        assertTrue(what, latch.await(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    /**
     * Waits until {@code connections} reaches {@code expected}: a proxy connect returns once the kernel accepted it, a
     * moment before the backend's accept loop counts it.
     */
    private static void awaitConnections(String what, AtomicInteger connections, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (connections.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(what, expected, connections.get());
    }

    private static ClientResponse answered(String what, Future<ClientResponse> f) throws Exception {
        try {
            return f.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            throw new AssertionError(what + " was not answered within " + WAIT_SECONDS + "s");
        }
    }

    // ---- AC-3 ----

    /** The four ways the relayed stream's close and the abort notification may end the exchange. */
    private enum EndOrder {
        CLOSE_TWICE, CLOSE_AFTER_ABORT, ABORT_AFTER_CLOSE, ABORT_ALONE
    }

    // REL-PROXY-001 AC-3: driving the relayed stream, close() twice returns the slot once, cancels the watchdog
    // reservation once and closes the backend socket
    @Test(timeout = 60000)
    public void rel_PROXY_001_AC3_closingTheRelayedStreamTwiceEndsTheExchangeOnce() throws Exception {
        driveRelayedEnd(EndOrder.CLOSE_TWICE);
    }

    // REL-PROXY-001 AC-3: close() after the onAbort notification returns the slot once, cancels the watchdog
    // reservation once and closes the backend socket
    @Test(timeout = 60000)
    public void rel_PROXY_001_AC3_closeAfterTheAbortNotificationEndsTheExchangeOnce() throws Exception {
        driveRelayedEnd(EndOrder.CLOSE_AFTER_ABORT);
    }

    // REL-PROXY-001 AC-3: the onAbort notification after close() returns the slot once, cancels the watchdog
    // reservation once and closes the backend socket
    @Test(timeout = 60000)
    public void rel_PROXY_001_AC3_theAbortNotificationAfterCloseEndsTheExchangeOnce() throws Exception {
        driveRelayedEnd(EndOrder.ABORT_AFTER_CLOSE);
    }

    /**
     * Forwards one request on a {@code .maxConcurrent(1)} mount to a backend that sends a head and the first body
     * bytes and then holds the connection, keeps the relayed stream and the onAbort listener the proxy hands to the
     * response, and drives them in {@code order}.
     */
    private void driveRelayedEnd(EndOrder order) throws Exception {
        HoldingBackend backend = backend(HoldingBackend.Mode.HEAD_THEN_HOLD);
        RecordingWatchdog watchdog = new RecordingWatchdog();
        Proxy p = Proxy.to(backend.url()).maxConcurrent(1).withWatchdog(watchdog);
        Captured captured = new Captured();
        Mounted m = app();
        m.app.use("/api", (req, res, next) -> {
            p.handle(req, capturing(res, captured), next);
            res.send("handed over");
        });
        m.start();

        ClientResponse r = m.adapter.send(ClientRequest.of("GET", "/api/x"));

        assertEquals("handed over", r.text());
        InputStream relayed = captured.relayed.get();
        Runnable abortListener = captured.abortListener.get();
        assertNotNull("the proxy handed a body to res.relay", relayed);
        assertNotNull("the proxy registered an onAbort listener before res.relay", abortListener);
        RecordingReservation reservation = watchdog.only();
        assertEquals("the reservation is for the total timeout", p.totalTimeoutNanos(), reservation.totalTimeoutNanos);
        assertEquals("the relayed request holds its slot", 1, p.concurrencyCounter().get());
        assertEquals("the relayed request keeps its reservation", 0, reservation.cancels.get());
        assertFalse("the backend connection is still open", backend.closedByProxy.await(100, TimeUnit.MILLISECONDS));

        switch (order) {
            case CLOSE_TWICE:
                relayed.close();
                relayed.close();
                break;
            case CLOSE_AFTER_ABORT:
                abortListener.run();
                // REL-PROXY-001 AC-3: the abort notification, before any close() of the relayed stream, has already
                // ended the exchange: the slot is back, the reservation cancelled and the backend socket closed
                assertAbortAloneEndedTheExchange(order, p, reservation, backend);
                relayed.close();
                break;
            case ABORT_ALONE:
                abortListener.run();
                assertAbortAloneEndedTheExchange(order, p, reservation, backend);
                break;
            default:
                relayed.close();
                abortListener.run();
                break;
        }

        assertEquals(order + ": counter, 0 and not -1", 0, p.concurrencyCounter().get());
        assertEquals(order + ": reservation cancels", 1, reservation.cancels.get());
        await(order + ": the backend observed its connection closed", backend.closedByProxy);
        assertEquals(order + ": watchdog reservations", 1, watchdog.reservationCount());
    }

    /**
     * What the onAbort notification alone must have done when it is the first end of the exchange, before anything
     * closed the relayed stream: the stream does not close the socket here, so a closed backend connection is the
     * proxy's own release (REL-PROXY-001 AC-3).
     */
    private static void assertAbortAloneEndedTheExchange(EndOrder order, Proxy p, RecordingReservation reservation,
            HoldingBackend backend) throws InterruptedException {
        assertEquals(order + ": counter right after the abort notification", 0, p.concurrencyCounter().get());
        assertEquals(order + ": reservation cancels right after the abort notification", 1,
                reservation.cancels.get());
        await(order + ": the abort notification alone closed the backend connection", backend.closedByProxy);
    }

    // REL-PROXY-001 AC-3: the onAbort notification with no close() of the relayed stream at all returns the slot once,
    // cancels the watchdog reservation once and closes the backend socket by itself
    @Test(timeout = 60000)
    public void rel_PROXY_001_AC3_theAbortNotificationAloneEndsTheExchange() throws Exception {
        driveRelayedEnd(EndOrder.ABORT_ALONE);
    }

    // REL-PROXY-001 AC-3: when res.abort runs while the relay copy is blocked reading a backend that sends nothing more,
    // the slot is back at once and the backend observes its connection closed within 3 s; the core keeps the input
    // of a running copy open, so only the proxy's abort listener can close the backend socket here
    @Test(timeout = 60000)
    public void rel_PROXY_001_AC3_anAbortDuringABlockedRelayCopyClosesTheBackend() throws Exception {
        HoldingBackend backend = backend(HoldingBackend.Mode.HEAD_THEN_HOLD);
        RecordingWatchdog watchdog = new RecordingWatchdog();
        Proxy p = Proxy.to(backend.url()).maxConcurrent(1).withWatchdog(watchdog);
        AtomicReference<Response> resRef = new AtomicReference<>();
        Mounted m = app();
        m.app.use("/api", (req, res, next) -> {
            resRef.set(res);
            p.handle(req, res, next);
        });
        m.start();

        Future<ClientResponse> f = m.adapter.submit(ClientRequest.of("GET", "/api/x"));

        awaitBlockedRelayCopy();
        assertEquals("the relay holds its slot", 1, p.concurrencyCounter().get());
        RecordingReservation reservation = watchdog.only();
        assertEquals("the relay keeps its reservation", 0, reservation.cancels.get());
        assertFalse("the backend connection is still open", backend.closedByProxy.await(100, TimeUnit.MILLISECONDS));
        Response res = resRef.get();
        assertNotNull("the request reached the proxy", res);

        res.abort(null);

        assertEquals("counter right after res.abort", 0, p.concurrencyCounter().get());
        assertEquals("reservation cancels right after res.abort", 1, reservation.cancels.get());
        assertTrue("the backend observed its connection closed within 3 s of res.abort",
                backend.closedByProxy.await(3, TimeUnit.SECONDS));
        f.cancel(true);
        assertEquals("counter after the client gave up", 0, p.concurrencyCounter().get());
        assertEquals("reservation cancels after the client gave up", 1, reservation.cancels.get());
    }

    /**
     * Waits until some thread runs the core's relay copy and is inside a socket read below it: the copy holds the
     * input of the response, so an abort from now on leaves the input to the copy.
     */
    private static void awaitBlockedRelayCopy() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            for (StackTraceElement[] stack : Thread.getAllStackTraces().values()) {
                boolean inCopy = false;
                boolean inSocketRead = false;
                for (StackTraceElement e : stack) {
                    if (e.getClassName().endsWith(".DefaultResponse") && e.getMethodName().equals("runTransfer")) {
                        inCopy = true;
                    }
                    if (e.getClassName().contains("Socket") && e.getMethodName().toLowerCase(Locale.ROOT)
                            .contains("read")) {
                        inSocketRead = true;
                    }
                }
                if (inCopy && inSocketRead) {
                    return;
                }
            }
            Thread.sleep(5);
        }
        fail("no relay copy blocked in a backend read within " + WAIT_SECONDS + "s");
    }

    // ---- AC-4 ----

    // REL-PROXY-001 AC-4: when onError runs for a ProxyException (a failed TLS handshake), the counter of the
    // .maxConcurrent(1) mount is already 0 and the request's watchdog reservation is cancelled
    @Test(timeout = 60000)
    public void rel_PROXY_001_AC4_aProxyExceptionReachesOnErrorAfterTheRelease() throws Exception {
        HoldingBackend backend = backend(HoldingBackend.Mode.CLOSE_AT_ONCE);
        RecordingWatchdog watchdog = new RecordingWatchdog();
        Proxy p = Proxy.to("https://127.0.0.1:" + backend.port()).maxConcurrent(1).withWatchdog(watchdog);

        AtSeen seen = sendRecordingAtError(p, watchdog, ClientRequest.of("GET", "/api/x"));

        assertTrue("a ProxyException reached onError: " + seen.error, seen.error instanceof ProxyException);
        assertEquals("counter when onError ran", 0, seen.counter);
        assertEquals("watchdog reservations", 1, watchdog.reservationCount());
        assertEquals("reservation cancels when onError ran", 1, seen.cancels);
        assertEquals("reservation cancels after the request", 1, watchdog.only().cancels.get());
    }

    // REL-PROXY-001 AC-4: when onError runs for the 413 of a chunked body that passed .bodyLimit while it was being
    // forwarded, the counter of the .maxConcurrent(1) mount is already 0 and the watchdog reservation is cancelled
    @Test(timeout = 60000)
    public void rel_PROXY_001_AC4_a413WhileForwardingReachesOnErrorAfterTheRelease() throws Exception {
        HoldingBackend backend = backend(HoldingBackend.Mode.DRAIN);
        RecordingWatchdog watchdog = new RecordingWatchdog();
        Proxy p = Proxy.to(backend.url()).bodyLimit(1024).maxConcurrent(1).withWatchdog(watchdog);

        AtSeen seen = sendRecordingAtError(p, watchdog, ClientRequest.of("POST", "/api/x")
                .header("Transfer-Encoding", "chunked").body(new byte[4096]));

        assertTrue("an HttpException reached onError: " + seen.error, seen.error instanceof HttpException);
        assertEquals("status", 413, ((HttpException) seen.error).status());
        awaitConnections("the proxy connected before the body passed the limit", backend.connections, 1);
        assertEquals("counter when onError ran", 0, seen.counter);
        assertEquals("watchdog reservations", 1, watchdog.reservationCount());
        assertEquals("reservation cancels when onError ran", 1, seen.cancels);
    }

    /** The error onError received with the counter and the cancels of the last reservation at that moment. */
    private static final class AtSeen {
        volatile Throwable error;
        volatile int counter = -100;
        volatile int cancels = -100;
    }

    private AtSeen sendRecordingAtError(Proxy p, RecordingWatchdog watchdog, ClientRequest request) throws Exception {
        AtSeen seen = new AtSeen();
        Mounted m = app();
        m.app.use("/api", p);
        m.app.onError((error, req, res) -> {
            seen.error = error;
            seen.counter = p.concurrencyCounter().get();
            seen.cancels = watchdog.reservationCount() == 0 ? -1 : watchdog.last().cancels.get();
            res.sendStatus(500);
        });
        m.start();
        m.adapter.send(request);
        assertNotNull("onError ran", seen.error);
        return seen;
    }

    // ---- AC-5 ----

    private static final String READ_EARLY = "X-Read-Early";

    // REL-PROXY-001 AC-5: after 20 requests refused with 400, 405, 501, 413 before reading and 500 for a consumed body
    // on a .maxConcurrent(1) mount, the counter is 0 and the watchdog received no reservation for them
    @Test(timeout = 60000)
    public void rel_PROXY_001_AC5_refusalsBeforeTheSlotTakeNeitherTheSlotNorTheWatchdog() throws Exception {
        HoldingBackend backend = backend(HoldingBackend.Mode.ANSWER_OK);
        RecordingWatchdog watchdog = new RecordingWatchdog();
        Proxy p = Proxy.to(backend.url()).bodyLimit(1024).maxConcurrent(1).withWatchdog(watchdog);
        Mounted m = app();
        m.app.use("/api", (req, res, next) -> {
            if (req.header(READ_EARLY) != null) {
                assertEquals('h', req.bodyStream().read());
            }
            next.run();
        });
        m.app.use("/api", p);
        m.app.onError((error, req, res) ->
                res.sendStatus(error instanceof HttpException ? ((HttpException) error).status() : 500));
        m.start();
        byte[] hello = "hello".getBytes(StandardCharsets.ISO_8859_1);
        byte[] over = new byte[2048];

        int sent = 0;
        for (int i = 0; i < 4; i++) {
            assertRefused(m, p, 400, ClientRequest.of("GET", "/api/%2e%2e/x"));
            assertRefused(m, p, 405, ClientRequest.of("TRACE", "/api/x"));
            assertRefused(m, p, 501, ClientRequest.of("POST", "/api/x")
                    .header("Transfer-Encoding", "gzip, chunked").body(hello));
            assertRefused(m, p, 413, ClientRequest.of("POST", "/api/x")
                    .header("Content-Length", "2048").body(over));
            assertRefused(m, p, 500, ClientRequest.of("POST", "/api/x")
                    .header("Content-Length", "5").header(READ_EARLY, "1").body(hello));
            sent += 5;
        }

        assertEquals("refused requests", 20, sent);
        assertEquals("counter", 0, p.concurrencyCounter().get());
        assertEquals("watchdog reservations", 0, watchdog.reservationCount());
        assertEquals("backend connections", 0, backend.connections.get());
    }

    // REL-PROXY-001: the watchdog installed through the package-private seam survives the option methods called after
    // it, so a forwarded request reserves the installed watchdog for the total timeout set last
    @Test(timeout = 60000)
    public void rel_PROXY_001_aWatchdogSurvivesLaterOptionMethods() throws Exception {
        HoldingBackend backend = backend(HoldingBackend.Mode.ANSWER_OK);
        RecordingWatchdog watchdog = new RecordingWatchdog();
        Proxy p = Proxy.to(backend.url()).withWatchdog(watchdog).retries(0).maxConcurrent(1)
                .totalTimeout(7, TimeUnit.SECONDS);
        Mounted m = app();
        m.app.use("/api", p);
        m.start();

        ClientResponse r = m.adapter.send(ClientRequest.of("GET", "/api/x"));

        assertEquals("status", 200, r.status());
        assertEquals("watchdog reservations", 1, watchdog.reservationCount());
        assertEquals("the reservation is for the total timeout set after the watchdog",
                TimeUnit.SECONDS.toNanos(7), watchdog.only().totalTimeoutNanos);
    }

    private static void assertRefused(Mounted m, Proxy p, int status, ClientRequest request) {
        ClientResponse r = m.adapter.send(request);
        assertEquals(request + " status", status, r.status());
        assertEquals(request + " counter", 0, p.concurrencyCounter().get());
    }

    // ---- AC-6 ----

    // REL-PROXY-001 AC-6: the proxy connects, writes the request and reads the response header block on the thread
    // that called Middleware.handle, and handle returns only after the response was handed to res.relay
    @Test(timeout = 60000)
    public void rel_PROXY_001_AC6_theBackendExchangeRunsOnTheHandleThreadUntilTheRelay() throws Exception {
        CountDownLatch readGate = gate();
        CountDownLatch headGate = gate();
        HoldingBackend backend = new HoldingBackend(HoldingBackend.Mode.GATED_OK, 4096);
        closeables.add(backend);
        backend.readGate = readGate;
        backend.headGate = headGate;
        AtomicReference<Thread> resolvedOn = new AtomicReference<>();
        BackendClient.resolver = host -> {
            resolvedOn.set(Thread.currentThread());
            return InetAddress.getByName(host);
        };
        // A body limit above the app's 1 MiB default, so the large body below is forwarded.
        Proxy p = Proxy.to(backend.url()).bodyLimit(32 * 1024 * 1024);
        AtomicReference<Thread> handleThread = new AtomicReference<>();
        List<String> events = Collections.synchronizedList(new ArrayList<String>());
        Mounted m = app();
        m.app.use("/api", (req, res, next) -> {
            handleThread.set(Thread.currentThread());
            p.handle(req, recording(res, events), next);
            events.add("returned@" + Thread.currentThread().getName());
        });
        m.start();
        // A body far larger than the socket buffers, so the write blocks while the backend does not read.
        byte[] body = new byte[16 * 1024 * 1024];

        Future<ClientResponse> f = m.adapter.submit(ClientRequest.of("POST", "/api/x")
                .header("Content-Length", Integer.toString(body.length)).body(body));

        Thread t = awaitHandleThread(handleThread);
        awaitFrame("the request write", t, RequestBodyCopier.class.getName(), null);
        assertSame("the backend connect ran on the handle thread", t, resolvedOn.get());
        assertTrue("nothing reached the response while writing: " + events, events.isEmpty());
        readGate.countDown();
        await("the backend read the whole request", backend.requestRead);
        awaitFrame("the response head read", t, Proxy.class.getName() + "$ResponseHead", "read");
        assertTrue("nothing reached the response while reading the head: " + events, events.isEmpty());
        headGate.countDown();

        ClientResponse r = answered("the forwarded request", f);
        assertEquals(200, r.status());
        assertEquals("ok", r.text());
        String on = "@" + t.getName();
        assertEquals("the order of the response calls and the return of handle",
                java.util.Arrays.asList("status" + on, "relay" + on, "returned" + on), events);
    }

    // REL-PROXY-001 AC-6: when the backend response cannot be read, the exception leaves handle on the thread that
    // called it, without a relay
    @Test(timeout = 60000)
    public void rel_PROXY_001_AC6_anExceptionLeavesHandleOnItsThreadWithoutARelay() throws Exception {
        HoldingBackend backend = backend(HoldingBackend.Mode.ANSWER_GARBAGE);
        Proxy p = Proxy.to(backend.url());
        AtomicReference<Thread> handleThread = new AtomicReference<>();
        List<String> events = Collections.synchronizedList(new ArrayList<String>());
        Mounted m = app();
        m.app.use("/api", (req, res, next) -> {
            handleThread.set(Thread.currentThread());
            try {
                p.handle(req, recording(res, events), next);
                events.add("returned@" + Thread.currentThread().getName());
            } catch (HttpException e) {
                events.add("threw " + e.status() + "@" + Thread.currentThread().getName());
                throw e;
            }
        });
        m.app.onError((error, req, res) ->
                res.sendStatus(error instanceof HttpException ? ((HttpException) error).status() : 500));
        m.start();

        ClientResponse r = m.adapter.send(ClientRequest.of("GET", "/api/x"));

        assertEquals(502, r.status());
        assertEquals(java.util.Arrays.asList("threw 502@" + handleThread.get().getName()), events);
    }

    /** {@code res} recording each {@code status} and {@code relay} call with its thread, then passing it on. */
    private static Response recording(Response res, List<String> events) {
        return (Response) java.lang.reflect.Proxy.newProxyInstance(Response.class.getClassLoader(),
                new Class<?>[] {Response.class}, (self, method, args) -> {
                    String name = method.getName();
                    if (name.equals("status") || name.equals("relay")) {
                        events.add(name + "@" + Thread.currentThread().getName());
                    }
                    try {
                        Object out = method.invoke(res, args);
                        return out == res ? self : out;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static Thread awaitHandleThread(AtomicReference<Thread> handleThread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (handleThread.get() == null) {
            if (System.nanoTime() > deadline) {
                fail("the request did not reach the proxy");
            }
            Thread.sleep(5);
        }
        return handleThread.get();
    }

    /**
     * Waits until {@code t} is inside a frame of {@code className} (and {@code methodName} when not null) called from
     * {@link Proxy#handle}: the thread recording of where the backend I/O runs.
     */
    private static void awaitFrame(String what, Thread t, String className, String methodName)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        StackTraceElement[] last = new StackTraceElement[0];
        while (System.nanoTime() < deadline) {
            last = t.getStackTrace();
            boolean inFrame = false;
            boolean inHandle = false;
            for (StackTraceElement e : last) {
                if (e.getClassName().equals(className) && (methodName == null || e.getMethodName().equals(methodName))) {
                    inFrame = true;
                }
                if (e.getClassName().equals(Proxy.class.getName()) && e.getMethodName().equals("handle")) {
                    inHandle = true;
                }
            }
            if (inFrame && inHandle) {
                return;
            }
            Thread.sleep(5);
        }
        StringBuilder trace = new StringBuilder();
        for (StackTraceElement e : last) {
            trace.append("\n  at ").append(e);
        }
        fail(what + " did not run on the handle thread " + t.getName() + trace);
    }

    /**
     * A JDK-only backend over a raw {@link ServerSocket} on 127.0.0.1 (D-070) that serves each connection on its own
     * thread, in one of a few fixed behaviours, and records when the proxy closed a held connection.
     */
    private static final class HoldingBackend implements Closeable {
        enum Mode {
            /** Sends a head with Content-Length 10 and two body bytes, then holds until the proxy closes. */
            HEAD_THEN_HOLD,
            /** Closes every connection at once without reading. */
            CLOSE_AT_ONCE,
            /** Reads and discards everything until the proxy closes the connection. */
            DRAIN,
            /** Reads the request head and answers 200 {@code ok}. */
            ANSWER_OK,
            /** Reads the request head and answers bytes that are not HTTP. */
            ANSWER_GARBAGE,
            /** Waits for readGate, reads the head and body, waits for headGate and answers 200 {@code ok}. */
            GATED_OK
        }

        private final Mode mode;
        private final ServerSocket server;
        private final Thread acceptor;
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
        final AtomicInteger connections = new AtomicInteger();
        final CountDownLatch closedByProxy = new CountDownLatch(1);
        final CountDownLatch requestRead = new CountDownLatch(1);
        volatile CountDownLatch readGate = new CountDownLatch(0);
        volatile CountDownLatch headGate = new CountDownLatch(0);

        HoldingBackend(Mode mode, int receiveBuffer) throws IOException {
            this.mode = mode;
            this.server = new ServerSocket();
            if (receiveBuffer > 0) {
                server.setReceiveBufferSize(receiveBuffer);
            }
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 50);
            this.acceptor = new Thread(this::acceptLoop, "holding-backend-" + server.getLocalPort());
            this.acceptor.setDaemon(true);
            this.acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        String url() {
            return "http://127.0.0.1:" + port();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                Socket s;
                try {
                    s = server.accept();
                } catch (IOException e) {
                    return;
                }
                connections.incrementAndGet();
                sockets.add(s);
                Thread t = new Thread(() -> serve(s), "holding-backend-conn");
                t.setDaemon(true);
                t.start();
            }
        }

        private void serve(Socket s) {
            try {
                s.setSoTimeout(30_000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                switch (mode) {
                    case CLOSE_AT_ONCE:
                        return;
                    case DRAIN:
                        drainUntilClosed(in);
                        return;
                    case HEAD_THEN_HOLD:
                        readHead(in);
                        out.write("HTTP/1.1 200 X\r\nContent-Length: 10\r\n\r\nab".getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        drainUntilClosed(in);
                        return;
                    case ANSWER_GARBAGE:
                        readHead(in);
                        out.write("NOT HTTP\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        return;
                    case GATED_OK: {
                        readGate.await(30, TimeUnit.SECONDS);
                        String head = readHead(in);
                        readFully(in, contentLength(head));
                        requestRead.countDown();
                        headGate.await(30, TimeUnit.SECONDS);
                        answerOk(out);
                        return;
                    }
                    default:
                        readHead(in);
                        answerOk(out);
                }
            } catch (IOException | InterruptedException e) {
                // the connection is over either way
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closed either way
                }
            }
        }

        private void drainUntilClosed(InputStream in) {
            byte[] buf = new byte[8192];
            try {
                while (in.read(buf) >= 0) {
                    // discard
                }
            } catch (IOException e) {
                // a reset is a close as well
            }
            closedByProxy.countDown();
        }

        private static void answerOk(OutputStream out) throws IOException {
            out.write("HTTP/1.1 200 X\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        private static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            while (matched < 4) {
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("the connection closed inside the request head");
                }
                head.write(b);
                matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            return new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        private static long contentLength(String head) {
            for (String line : head.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("content-length")) {
                    return Long.parseLong(line.substring(colon + 1).trim());
                }
            }
            return 0;
        }

        private static void readFully(InputStream in, long n) throws IOException {
            byte[] buf = new byte[65536];
            long left = n;
            while (left > 0) {
                int k = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (k < 0) {
                    throw new EOFException("the request body ended " + left + " bytes early");
                }
                left -= k;
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            synchronized (sockets) {
                for (Socket s : sockets) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // closed either way
                    }
                }
            }
            try {
                acceptor.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
