package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.*;

import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;

/** Direct SPI holds over real sockets; core Deferred integration remains a separate existing test. */
public class TinyRequestRetentionTest {
    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();
    private final ExecutorService tasks = Executors.newFixedThreadPool(4);
    private final List<CountDownLatch> releases = new ArrayList<>();
    private final AtomicReference<HttpExchange> exchange = new AtomicReference<>();
    private final List<Runnable> holds = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    private final CountDownLatch handled = new CountDownLatch(1);
    private final AtomicInteger notices = new AtomicInteger();

    @After public void cleanup() throws Exception {
        for (CountDownLatch latch : releases) latch.countDown();
        for (Runnable hold : holds) hold.run();
        HttpExchange ex = exchange.get();
        if (ex != null) ex.abort();
        Http.stopQuietly(adapter);
        tasks.shutdownNow();
        assertTrue("owned threads leaked", tasks.awaitTermination(12, TimeUnit.SECONDS));
    }

    private void start(int count, boolean finish) throws Exception {
        adapter.bind(Http.settings().maxSseStreams(1).build(), ex -> {
            if (ex.rawPath().equals("/next")) {
                try { send(ex, "next"); } catch (IOException problem) { throw new UncheckedIOException(problem); }
                return;
            }
            exchange.set(ex);
            ex.onAbort(() -> { unlocked(ex); notices.incrementAndGet(); });
            for (int i = 0; i < count; i++) holds.add(ex.retainRequest());
            try { if (finish) send(ex, "ack"); }
            catch (Throwable problem) { callbackFailure.set(problem); ex.abort(); }
            finally { handled.countDown(); }
        });
    }

    private static void send(HttpExchange ex, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ex.commit(200, Collections.singletonList(new AbstractMap.SimpleImmutableEntry<>(
                "Content-Length", Integer.toString(bytes.length))), bytes.length).write(bytes);
        ex.finish();
    }

    private Socket request(String payload) throws Exception {
        Socket socket = new Socket("127.0.0.1", adapter.boundPort());
        socket.setSoTimeout(5000);
        socket.getOutputStream().write(("POST /held HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n"
                + "Content-Type: text/plain\r\nContent-Length: " + payload.length() + "\r\n\r\n" + payload)
                .getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        assertTrue(handled.await(5, TimeUnit.SECONDS));
        until(() -> ((TinyHttpdExchange) exchange.get()).handlerReturned(), "initial callback not returned");
        assertNull(callbackFailure.get());
        return socket;
    }

    private static String fixed(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int tail = 0;
        while (tail != 0x0d0a0d0a) {
            int b = input.read();
            if (b < 0) throw new EOFException("missing complete response head");
            bytes.write(b); tail = (tail << 8) | b;
        }
        String head = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
        String length = Http.header(head, "Content-Length");
        assertNotNull(head, length);
        for (int left = Integer.parseInt(length); left > 0; left--) {
            int b = input.read();
            if (b < 0) throw new EOFException("short fixed response");
            bytes.write(b);
        }
        return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private int active() throws Exception {
        java.lang.reflect.Field field = TinyHttpdAdapter.class.getDeclaredField("exchanges");
        field.setAccessible(true);
        return ((Set<?>) field.get(adapter)).size();
    }

    private void unlocked(HttpExchange ex) {
        try {
            java.lang.reflect.Field field = TinyHttpdExchange.class.getDeclaredField("state");
            field.setAccessible(true);
            assertFalse("callback holds IO lock", Thread.holdsLock(ex));
            assertFalse("callback holds lifecycle lock", Thread.holdsLock(field.get(ex)));
        } catch (Throwable problem) { callbackFailure.compareAndSet(null, problem); }
    }

    private static void until(Callable<Boolean> check, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.call() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(message, check.call());
    }

    private static void pending(Socket socket) throws IOException {
        socket.setSoTimeout(120);
        try { int b = socket.getInputStream().read(); fail("engine returned retained request: next byte=" + b); }
        catch (SocketTimeoutException expected) { }
        finally { socket.setSoTimeout(5000); }
    }

    private void closed() throws Exception {
        HttpExchange ex = exchange.get();
        try { ex.commit(200, Collections.emptyList(), 0); fail("terminal request resurrected"); }
        catch (IOException | IllegalStateException expected) { }
    }

    // FR-APP-023 AC-3 AC-8: wire completion does not release input/engine ownership;
    // every hold has an independent idempotent release and the final one permits the next request.
    @Test(timeout=20000) public void completedResponseKeepsUnreadInputAndPipelineUntilLastHoldReleased() throws Exception {
        start(2, true);
        try (Socket socket = request("original-payload")) {
            assertEquals("ack", Http.body(fixed(socket.getInputStream())));
            assertEquals("retained input was retired after wire finish", 1, active());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            InputStream input = exchange.get().requestBody();
            for (int b; (b = input.read()) != -1;) body.write(b);
            assertEquals("original-payload", new String(body.toByteArray(), StandardCharsets.UTF_8));
            socket.getOutputStream().write("GET /next HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            holds.get(0).run(); holds.get(0).run();
            assertEquals(1, active()); pending(socket);
            holds.get(1).run(); holds.get(1).run();
            String next = fixed(socket.getInputStream());
            assertEquals(200, Http.status(next)); assertEquals("next", Http.body(next));
            until(() -> active() == 0, "last release did not retire request");
            assertEquals(0, notices.get()); assertNull(callbackFailure.get());
        }
    }

    // FR-APP-023 AC-8: a new hold acquired after response completion but before physical
    // retirement survives concurrent repeated releases of all earlier holds.
    @Test(timeout=20000) public void lateHoldSurvivesConcurrentRepeatedEarlierReleases() throws Exception {
        start(8, true);
        try (Socket socket = request("")) {
            assertEquals("ack", Http.body(fixed(socket.getInputStream())));
            Runnable late = exchange.get().retainRequest(); holds.add(late);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                Runnable hold = holds.get(i % 8);
                calls.add(tasks.submit(() -> { try { go.await(); hold.run(); hold.run(); }
                    catch (InterruptedException problem) { throw new RuntimeException(problem); } }));
            }
            go.countDown();
            for (Future<?> call : calls) call.get(5, TimeUnit.SECONDS);
            assertEquals("earlier release retired newer ownership", 1, active());
            late.run(); late.run();
            until(() -> active() == 0, "late hold was not released");
            assertEquals(0, notices.get()); assertNull(callbackFailure.get());
        }
    }

    // FR-APP-023 AC-8: release bookkeeping cannot wait on an unrelated output owner;
    // engine retirement still waits for that owner's buffer access to finish.
    @Test(timeout=20000) public void finalReleaseDoesNotAcquireIoMonitorButRetirementWaitsForIoOwner() throws Exception {
        start(1, true);
        try (Socket socket = request("")) {
            assertEquals("ack", Http.body(fixed(socket.getInputStream())));
            HttpExchange ex = exchange.get();
            synchronized (ex) {
                tasks.submit(holds.get(0)).get(2, TimeUnit.SECONDS);
                assertEquals("physical retirement bypassed IO owner", 1, active());
            }
            until(() -> active() == 0, "release did not wake engine worker");
            assertNull(callbackFailure.get()); assertEquals(0, notices.get());
        }
    }

    // FR-APP-023 AC-3 AC-8; IR-HTTP-009 AC-2/3: abnormal termination revokes held input
    // even after a completed wire response; late release/retain cannot resurrect it.
    @Test(timeout=20000) public void abortAfterWireFinishRevokesHoldsAndNotifiesOnceOutsideLocks() throws Exception {
        start(2, true);
        try (Socket socket = request("")) {
            assertEquals("ack", Http.body(fixed(socket.getInputStream())));
            HttpExchange ex = exchange.get();
            ex.abort(); ex.abort();
            assertEquals(1, notices.get());
            until(() -> active() == 0, "abort retained request worker");
            ex.onAbort(() -> { unlocked(ex); notices.incrementAndGet(); });
            assertEquals(2, notices.get());
            for (Runnable hold : holds) { hold.run(); hold.run(); }
            ex.retainRequest().run();
            assertEquals(0, active()); closed(); assertNull(callbackFailure.get());
        }
    }

    // FR-APP-023 AC-3/6/8: force-stop revokes holds within its existing deadline,
    // with once-only abnormal notification and harmless later releases.
    @Test(timeout=20000) public void forcedStopRevokesFinishedResponseHoldsWithinDeadline() throws Exception {
        start(2, true);
        try (Socket socket = request("")) {
            assertEquals("ack", Http.body(fixed(socket.getInputStream())));
            long begin = System.nanoTime();
            adapter.stop(100, TimeUnit.MILLISECONDS);
            assertTrue("stop exceeded deadline", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1500);
            until(() -> active() == 0, "stopped request still retained");
            assertEquals(1, notices.get()); assertNull(callbackFailure.get());
            for (Runnable hold : holds) { hold.run(); hold.run(); }
            exchange.get().retainRequest().run();
            assertEquals(0, active()); closed();
        }
    }

    // FR-APP-023 AC-1/8: releasing ownership is not an implicit finish or continuation.
    @Test(timeout=20000) public void releasingBeforeResponseCompletionLeavesOrdinaryRequestPending() throws Exception {
        start(1, false);
        try (Socket socket = request("")) {
            holds.get(0).run(); holds.get(0).run();
            assertEquals(1, active());
            pending(socket);
            send(exchange.get(), "explicit");
            String reply = fixed(socket.getInputStream());
            assertEquals(200, Http.status(reply)); assertEquals("explicit", Http.body(reply));
            until(() -> active() == 0, "explicit completion did not retire request");
            assertEquals(0, notices.get()); assertNull(callbackFailure.get());
        }
    }

    // FR-APP-023 AC-1/3/8: releasing input holds does not cancel a running SSE drain
    // or release its slot/response before the actual drain callback exits.
    @Test(timeout=20000) public void releasingRequestHoldDoesNotReleaseActiveSseDrainOwnership() throws Exception {
        start(1, false);
        CountDownLatch draining = new CountDownLatch(1), finish = new CountDownLatch(1);
        releases.add(finish);
        try (Socket socket = request("")) {
            HttpExchange ex = exchange.get();
            ex.eventStream(200, Collections.emptyList(), new StreamDrain() {
                public void drain(OutputStream out) throws IOException {
                    draining.countDown();
                    try { finish.await(); } catch (InterruptedException problem) { Thread.currentThread().interrupt(); }
                }
            });
            assertTrue(draining.await(5, TimeUnit.SECONDS));
            holds.get(0).run(); holds.get(0).run();
            assertEquals(1, ((TinyHttpdExchange) ex).slots().open());
            assertEquals(1, active());
            finish.countDown();
            until(() -> active() == 0, "finished drain retained exchange");
            assertEquals(0, ((TinyHttpdExchange) ex).slots().open());
            assertEquals(0, notices.get()); assertNull(callbackFailure.get());
        }
    }
}
