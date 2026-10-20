package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.testing.RecordingExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * The core SSE client over a {@link RecordingExchange}, whose eventStream never attaches a stream: the test runs the
 * stored drain on its own thread. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-10.sds.md.
 */
public class DefaultSseClientTest {

    private static SseRegistry registry(int sendQueueSize) {
        DefaultSseOptions o = new DefaultSseOptions();
        o.sendQueueSize(sendQueueSize);
        return new SseRegistry(o, null, new SafeLogger(new ConsoleLogger()));
    }

    /** A response on {@code ex} whose sse() runs on this thread, the handler thread. */
    private static DefaultSseClient open(RecordingExchange ex, int sendQueueSize) {
        DefaultResponse res = new DefaultResponse(ex, false);
        res.sseRegistry(registry(sendQueueSize));
        SseClient c = res.sse();
        assertSame(c, res.sseClient());
        return (DefaultSseClient) c;
    }

    /** Runs the stored drain on a new thread and waits for it; returns what it threw, or null. */
    private static Throwable drainOnAnotherThread(RecordingExchange ex, OutputStream out) throws InterruptedException {
        StreamDrain d = ex.drain();
        assertNotNull("eventStream stored no drain", d);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                d.drain(out);
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        t.start();
        t.join(5000);
        assertFalse("the drain did not return", t.isAlive());
        return thrown.get();
    }

    private static String text(ByteArrayOutputStream out) {
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // SDS-AC-6; FR-SSE-002 AC-8
    @Test(timeout = 20000)
    public void sdsAc6_FR_SSE_002_AC8_withoutAnAttachedStreamHandlerSendsAreQueuedAndDrainedInCallOrder()
            throws Exception {
        RecordingExchange ex = new RecordingExchange("GET", "/events");
        DefaultSseClient c = open(ex, 262144);
        assertTrue(c.send("a"));
        assertTrue(c.send("b"));
        assertTrue(c.send("tick", "c"));
        assertEquals("data: a\n\ndata: b\n\nevent: tick\ndata: c\n\n".length(), c.queuedBytes());
        c.close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertNull(drainOnAnotherThread(ex, out));
        assertEquals("data: a\n\ndata: b\n\nevent: tick\ndata: c\n\n", text(out));
        assertEquals(1, ex.finishes.get());
        assertEquals(0, ex.aborts.get());
    }

    // SDS-AC-4, SDS-AC-5; FR-SSE-002 AC-5, AC-6
    @Test(timeout = 20000)
    public void sdsAc5_FR_SSE_002_AC6_aHandlerSendBeforeTheDrainWritesTheQueueThenItsOwnEventToTheAttachedStream()
            throws Exception {
        RecordingExchange ex = new RecordingExchange("GET", "/events");
        DefaultSseClient c = open(ex, 262144);
        ByteArrayOutputStream attached = new ByteArrayOutputStream();
        c.attach(attached); // what an adapter's eventStream does before it returns; the drain never starts here
        Thread other = new Thread(() -> {
            c.send("A");
            c.send("B");
        });
        other.start();
        other.join(5000);
        assertEquals(0, attached.size());
        assertTrue(c.send("C")); // this thread opened the stream, so it is the handler thread
        assertEquals("data: A\n\ndata: B\n\ndata: C\n\n", text(attached));
        assertEquals(0, c.queuedBytes());
        assertTrue(c.isOpen());
    }

    // SDS-AC-6; FR-SSE-002 AC-8
    @Test
    public void sdsAc6_FR_SSE_002_AC8_withoutAnAttachedStreamHandlerSendsAreBoundByTheQueueLimit() {
        RecordingExchange ex = new RecordingExchange("GET", "/events");
        DefaultSseClient c = open(ex, 20); // "data: a\n\n" is 9 bytes: two fit, the third overflows
        assertTrue(c.send("a"));
        assertTrue(c.send("b"));
        assertFalse(c.send("c"));
        assertFalse(c.isOpen());
        assertEquals("overflow", c.cause());
    }

    // SDS-AC-7; FR-SSE-002 AC-7
    @Test(timeout = 20000)
    public void sdsAc7_FR_SSE_002_AC7_afterCloseSendIsFalseAndTheDrainLeavesAnEmptyQueue() throws Exception {
        RecordingExchange ex = new RecordingExchange("GET", "/events");
        DefaultSseClient c = open(ex, 262144);
        assertTrue(c.send("queued"));
        c.close();
        assertFalse(c.send("after close"));
        assertEquals("data: queued\n\n".length(), c.queuedBytes());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertNull(drainOnAnotherThread(ex, out));
        assertEquals("data: queued\n\n", text(out));
        assertFalse(c.send("after the drain"));
        assertEquals(0, c.queuedBytes());
        assertEquals("app", c.cause());
    }

    // SDS-AC-7; FR-SSE-002 AC-7
    @Test(timeout = 20000)
    public void sdsAc7_FR_SSE_002_AC7_afterADisconnectSendIsFalseAndTheQueueIsEmpty() throws Exception {
        RecordingExchange ex = new RecordingExchange("GET", "/events");
        DefaultSseClient c = open(ex, 262144);
        assertTrue(c.send("1"));
        assertTrue(c.send("2"));
        OutputStream gone = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("peer gone");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("peer gone");
            }
        };
        assertNull("the drain threw to the adapter", drainOnAnotherThread(ex, gone));
        assertFalse(c.send("3"));
        assertEquals(0, c.queuedBytes());
        assertEquals("client", c.cause());
        assertEquals(1, ex.aborts.get());
    }

    // SDS-AC-7; FR-SSE-002 AC-7
    @Test
    public void sdsAc7_FR_SSE_002_AC7_afterAnOverflowSendIsFalseAndTheQueueIsEmpty() {
        RecordingExchange ex = new RecordingExchange("GET", "/events");
        DefaultSseClient c = open(ex, 10);
        assertTrue(c.send("1"));
        assertFalse(c.send("2"));
        assertFalse(c.send("3"));
        assertEquals(0, c.queuedBytes());
    }

    // SDS-AC-9; FR-SSE-004 AC-5
    @Test(timeout = 20000)
    public void sdsAc9_FR_SSE_004_AC5_aWriteFailureAfterCloseKeepsCauseAppAndARuntimeFailureIsNotRethrown()
            throws Exception {
        RecordingExchange ex = new RecordingExchange("GET", "/events");
        DefaultSseClient c = open(ex, 262144);
        AtomicInteger closes = new AtomicInteger();
        c.onClose(closes::incrementAndGet);
        assertTrue(c.send("1"));
        c.close();
        OutputStream gone = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("peer gone");
            }
        };
        assertNull(drainOnAnotherThread(ex, gone));
        assertEquals("app", c.cause());
        assertEquals(1, closes.get());

        RecordingExchange ex2 = new RecordingExchange("GET", "/events");
        DefaultSseClient c2 = open(ex2, 262144);
        assertTrue(c2.send("1"));
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) {
                throw new IllegalStateException("broken");
            }
        };
        assertNull("the drain threw to the adapter", drainOnAnotherThread(ex2, broken));
        assertEquals("error", c2.cause());
        assertEquals(1, ex2.aborts.get());
    }

    // SDS-AC-11; FR-SSE-004 AC-6
    @Test
    public void sdsAc11_FR_SSE_004_AC6_headCommitsBodylessWithoutAnEventStreamAndKeepsSseClientNull() {
        RecordingExchange ex = new RecordingExchange("HEAD", "/events");
        DefaultResponse res = new DefaultResponse(ex, true);
        SseRegistry reg = registry(262144);
        res.sseRegistry(reg);
        SseClient c = res.sse();
        assertNull(res.sseClient());
        assertNull("HEAD reached eventStream", ex.drain());
        assertEquals(1, ex.commits.get());
        assertEquals(0, reg.size());
        assertFalse(c.isOpen());
        assertFalse(c.send("x"));
        AtomicInteger closes = new AtomicInteger();
        c.onClose(closes::incrementAndGet);
        assertEquals(1, closes.get());
        assertEquals(ResponseState.COMPLETED, res.state());
    }

    // SDS-AC-13; FR-SSE-004 AC-5
    @Test(timeout = 20000)
    public void sdsAc13_FR_SSE_004_AC5_anAbortBeforeTheDrainRunsTheListenersAndALaterDrainReturnsAtOnce()
            throws Exception {
        RecordingExchange ex = new RecordingExchange("GET", "/events");
        DefaultResponse res = new DefaultResponse(ex, false);
        SseRegistry reg = registry(262144);
        res.sseRegistry(reg);
        DefaultSseClient c = (DefaultSseClient) res.sse();
        AtomicInteger closes = new AtomicInteger();
        c.onClose(closes::incrementAndGet);
        assertTrue(c.send("queued"));
        assertEquals(1, reg.size());
        res.abort(new IllegalStateException("handler failed after sse()"));
        assertEquals(1, closes.get());
        assertEquals("error", c.cause());
        assertEquals(0, reg.size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertNull(drainOnAnotherThread(ex, out));
        assertEquals(0, out.size());
        assertEquals(1, closes.get());
        assertEquals(0, c.queuedBytes());
    }
}
