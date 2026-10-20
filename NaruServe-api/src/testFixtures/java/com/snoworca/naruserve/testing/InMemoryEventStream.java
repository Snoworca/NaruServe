package com.snoworca.naruserve.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The client view of one in-memory event stream, from {@link InMemoryHttpAdapter#nextEventStream}. Every write of the
 * exchange's body stream is recorded with its thread; a test can hold the next write, fail the nth write or make
 * every later write fail as a disconnected client would.
 */
// @req FR-SSE-002
// @req FR-SSE-004
@NullMarked
public final class InMemoryEventStream {

    private final OutputStream sink;
    private final OutputStream stream = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            record(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            sink.flush();
        }
    };

    // Guarded by this.
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private final Set<Thread> writers = new LinkedHashSet<>();
    // {body length in bytes after the write, System.nanoTime() of the write}
    private final List<long[]> writeTimes = new ArrayList<>();
    private @Nullable CountDownLatch hold;
    private int writes;
    private int failAt;
    private @Nullable Exception failure;
    private boolean disconnected;
    private @Nullable OutputStream attached;
    private @Nullable OutputStream drained;

    /** Records what goes to {@code sink}, the exchange-synchronized body stream. */
    // @req FR-SSE-002
    InMemoryEventStream(OutputStream sink) {
        this.sink = sink;
    }

    /** The stream the adapter passes to attach and drain. */
    OutputStream stream() {
        return stream;
    }

    synchronized void attachedTo(OutputStream out) {
        attached = out;
    }

    synchronized void drainedTo(OutputStream out) {
        drained = out;
    }

    private void record(byte[] b, int off, int len) throws IOException {
        CountDownLatch h;
        int n;
        synchronized (this) {
            writers.add(Thread.currentThread());
            n = ++writes;
            h = hold;
            hold = null;
        }
        if (h != null) {
            try {
                h.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("held write interrupted");
            }
        }
        Exception fail;
        synchronized (this) {
            fail = disconnected ? new IOException("client disconnected") : n == failAt ? failure : null;
        }
        if (fail instanceof IOException) {
            throw (IOException) fail;
        }
        if (fail instanceof RuntimeException) {
            throw (RuntimeException) fail;
        }
        if (fail != null) {
            throw new IOException(fail);
        }
        sink.write(b, off, len);
        synchronized (this) {
            written.write(b, off, len);
            writeTimes.add(new long[] {written.size(), System.nanoTime()});
            notifyAll();
        }
    }

    /** The body written so far, decoded as UTF-8. */
    // @req FR-SSE-002
    public synchronized String text() {
        return new String(written.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Waits until the body contains {@code part}.
     *
     * @throws AssertionError when it does not within {@code millis}
     */
    // @req FR-SSE-002
    public synchronized void awaitText(String part, long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        try {
            while (!text().contains(part)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("no " + part + " within " + millis + " ms in: " + text());
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for " + part, e);
        }
    }

    /**
     * The {@link System#nanoTime()} of the write after which the body first contained {@code part}.
     *
     * @throws AssertionError when the body does not contain it
     */
    // @req FR-SSE-002
    public synchronized long writtenAt(String part) {
        String text = text();
        int i = text.indexOf(part);
        if (i < 0) {
            throw new AssertionError("no " + part + " in: " + text);
        }
        int end = text.substring(0, i + part.length()).getBytes(StandardCharsets.UTF_8).length;
        for (long[] w : writeTimes) {
            if (w[0] >= end) {
                return w[1];
            }
        }
        throw new AssertionError("unreachable: " + part + " found but no write reached it");
    }

    /** The threads that wrote to the stream, in first-write order. */
    // @req FR-SSE-002
    public synchronized Set<Thread> writerThreads() {
        return new LinkedHashSet<>(writers);
    }

    /** The next write waits until the returned latch is counted down. */
    // @req FR-SSE-002
    public synchronized CountDownLatch hold() {
        CountDownLatch h = new CountDownLatch(1);
        hold = h;
        return h;
    }

    /** The {@code nth} write from the start of the stream throws {@code e} (an IOException or RuntimeException). */
    // @req FR-SSE-004
    public synchronized void failWrite(int nth, Exception e) {
        failAt = nth;
        failure = e;
    }

    /** Every later write throws IOException, as when the client closed its socket. */
    // @req FR-SSE-004
    public synchronized void disconnect() {
        disconnected = true;
    }

    /** The stream the adapter passed to {@code StreamDrain.attach}, or null before that. */
    // @req FR-SSE-002
    public synchronized @Nullable OutputStream attached() {
        return attached;
    }

    /** The stream the adapter passed to {@code StreamDrain.drain}, or null before that. */
    // @req FR-SSE-002
    public synchronized @Nullable OutputStream drained() {
        return drained;
    }
}
