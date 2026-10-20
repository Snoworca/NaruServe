package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.SseEvent;
import com.snoworca.naruserve.spi.StreamDrain;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * The core {@link SseClient} of one stream and the {@link StreamDrain} the adapter runs for it. Events are encoded on
 * the calling thread and queued whole, at most {@code sendQueueSize} bytes; the drain writes them in call order and
 * a {@code : ping} comment after each heartbeat interval without a write. Until the drain starts, a send on the
 * handler thread writes the queue and then its own event straight to the stream the adapter attached. State is
 * guarded by this; socket writes by {@link #writeLock}, which is never held while waiting on this.
 */
// @req IR-SSE-001
// @req FR-SSE-001
// @req FR-SSE-002
// @req FR-SSE-004
final class DefaultSseClient implements SseClient, StreamDrain {

    private static final byte[] PING = ": ping\n\n".getBytes(StandardCharsets.US_ASCII);

    private final @Nullable DefaultResponse res;
    private final @Nullable SseRegistry registry;
    private final @Nullable Thread handlerThread;
    private final String rid;
    private final SafeLogger log;
    private final int limit;
    private final long heartbeatNanos;
    private final long startMillis = System.currentTimeMillis();
    private final Object writeLock = new Object();
    private final AtomicBoolean listenersRan = new AtomicBoolean();
    // Guarded by this.
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private final List<CloseListener> listeners = new ArrayList<>();
    private int queuedBytes;
    private boolean ended;
    private @Nullable String cause;
    private boolean draining;
    private @Nullable OutputStream attached;
    private long lastWriteNanos = System.nanoTime();
    private @Nullable Throwable directFailure;

    /** A stream of {@code res}; the queue limit and heartbeat come from {@code registry.options()}. */
    // @req FR-SSE-002
    DefaultSseClient(DefaultResponse res, SseRegistry registry, Thread handlerThread, String rid) {
        this(res, registry, handlerThread, rid, registry.log(), registry.options().sendQueueSize(),
                registry.options().heartbeatMillis());
    }

    private DefaultSseClient(@Nullable DefaultResponse res, @Nullable SseRegistry registry,
            @Nullable Thread handlerThread, String rid, SafeLogger log, int limit, long heartbeatMillis) {
        this.res = res;
        this.registry = registry;
        this.handlerThread = handlerThread;
        this.rid = rid;
        this.log = log;
        this.limit = limit;
        this.heartbeatNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatMillis);
    }

    /** The client of a HEAD request: ended, its listeners already run, in no registry. */
    // @req IR-SSE-001
    // @req FR-SSE-004
    static DefaultSseClient ended(String rid, SafeLogger log) {
        DefaultSseClient c = new DefaultSseClient(null, null, null, rid, log, 1, 1000L);
        c.endBy("head");
        c.runListeners();
        return c;
    }

    // @req FR-SSE-001
    @Override
    public boolean send(String event, String data) {
        SseWire.checkField("event", event);
        return offer(SseWire.encode(event, null, -1, Objects.requireNonNull(data, "data")));
    }

    // @req FR-SSE-001
    @Override
    public boolean send(String data) {
        return offer(SseWire.encode(null, null, -1, Objects.requireNonNull(data, "data")));
    }

    // @req FR-SSE-001
    @Override
    public boolean send(SseEvent event) {
        return offer(SseWire.wire(event));
    }

    /** Encoded before the lock; written direct on the handler thread before the drain, else queued or refused. */
    // @req FR-SSE-002
    private boolean offer(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        OutputStream direct;
        synchronized (this) {
            if (ended) {
                return false;
            }
            // shortcut: an adapter that pools the handler thread and starts the drain later lets another request on
            // that thread write here directly until then; both adapters start the drain at once or on that thread.
            direct = Thread.currentThread() == handlerThread && !draining ? attached : null;
            if (direct == null) {
                return enqueue(b);
            }
        }
        return writeDirect(direct, b);
    }

    /** Under this: adds {@code b}, or on overflow drops it, clears the queue and ends the stream. */
    // @req FR-SSE-002
    private boolean enqueue(byte[] b) {
        if ((long) queuedBytes + b.length > limit) {
            queue.clear();
            queuedBytes = 0;
            endBy("overflow");
            return false;
        }
        queue.add(b);
        queuedBytes += b.length;
        notifyAll();
        return true;
    }

    /**
     * The handler-thread write before the drain: under writeLock, the queued events and then {@code b}. A drain that
     * started meanwhile set draining before taking writeLock, so the event joins the queue instead.
     */
    // @req FR-SSE-002
    private boolean writeDirect(OutputStream out, byte[] b) {
        synchronized (writeLock) {
            List<byte[]> batch;
            synchronized (this) {
                if (ended) {
                    return false;
                }
                if (draining) {
                    return enqueue(b);
                }
                batch = new ArrayList<>(queue);
                queue.clear();
                queuedBytes = 0;
            }
            batch.add(b);
            try {
                for (byte[] e : batch) {
                    out.write(e);
                    written(e.length);
                }
                out.flush();
                return true;
            } catch (IOException | RuntimeException e) {
                synchronized (this) {
                    if (endBy(e instanceof IOException ? "client" : "error")) {
                        directFailure = e; // the drain aborts the response with it instead of finishing
                    }
                }
                return false;
            }
        }
    }

    private void written(int n) {
        synchronized (this) {
            lastWriteNanos = System.nanoTime();
        }
        if (res != null) {
            res.addWritten(n);
        }
    }

    /** Stores the adapter's body stream for handler-thread writes before the drain. */
    // @req FR-SSE-002
    @Override
    public synchronized void attach(OutputStream out) {
        attached = Objects.requireNonNull(out, "out");
    }

    // @req FR-SSE-004
    @Override
    public void close() {
        endBy("app");
    }

    @Override
    public synchronized boolean isOpen() {
        return !ended;
    }

    /** Adds {@code listener}, or runs it at once on the caller when the listeners already ran. */
    // @req FR-SSE-004
    @Override
    public void onClose(CloseListener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (this) {
            if (!listenersRan.get()) {
                listeners.add(listener);
                return;
            }
        }
        run(listener);
    }

    /** Ends the stream; the first cause wins and nothing is cleared. True when this call ended it. */
    // @req FR-SSE-002
    // @req FR-SSE-004
    synchronized boolean endBy(String why) {
        if (ended) {
            return false;
        }
        ended = true;
        cause = why;
        notifyAll();
        return true;
    }

    synchronized @Nullable String cause() {
        return cause;
    }

    synchronized int queuedBytes() {
        return queuedBytes;
    }

    long startMillis() {
        return startMillis;
    }

    /** Whether a drain call took over the stream; until then an abort runs the listeners itself. */
    synchronized boolean drainStarted() {
        return draining;
    }

    /**
     * Writes the queue in order and a ping after each idle heartbeat interval until the stream ended and the queue is
     * empty, then ends the response, leaves the registry and runs the listeners. Never throws to the adapter except an
     * Error. A thread outside a request holds the request mark until the listeners ran.
     */
    // @req FR-SSE-002
    // @req FR-SSE-004
    @Override
    public void drain(OutputStream out) {
        synchronized (this) {
            if (draining) {
                return; // a second drain call; the first one owns the stream
            }
            if (listenersRan.get() || res == null) {
                queue.clear();
                queuedBytes = 0;
                return; // the stream already ended without a drain
            }
            draining = true;
        }
        DefaultResponse r = res;
        AppLifecycle lc = registry != null ? registry.lifecycle() : null;
        if (lc != null && lc.onRequestThread()) {
            lc = null; // already marked: whoever marked it also unmarks it
        }
        if (lc != null) {
            lc.enterRequest();
        }
        Throwable failure = null;
        try {
            loop(out);
        } catch (IOException e) {
            endBy("client");
            failure = e;
        } catch (RuntimeException e) {
            endBy("error");
            failure = e;
        } catch (InterruptedException e) {
            endBy("shutdown");
            failure = e;
            Thread.currentThread().interrupt();
        } catch (Error e) {
            endBy("error");
            failure = e;
            throw e;
        } finally {
            synchronized (this) {
                queue.clear();
                queuedBytes = 0;
                if (failure == null) {
                    failure = directFailure;
                }
            }
            try {
                r.sseEnded(failure);
            } catch (RuntimeException e) {
                log.error("sse " + rid + " end failed", e); // never to the adapter
            } finally {
                try {
                    runListeners();
                } finally {
                    if (lc != null) {
                        lc.exitRequest();
                    }
                }
            }
        }
    }

    private void loop(OutputStream out) throws IOException, InterruptedException {
        while (true) {
            byte[] next;
            synchronized (this) {
                next = queue.poll();
                while (next == null && !ended) {
                    long left = lastWriteNanos + heartbeatNanos - System.nanoTime();
                    if (left <= 0) {
                        next = PING;
                        break;
                    }
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                    next = queue.poll();
                }
                if (next == null) {
                    return; // ended and the queue is empty
                }
                if (next != PING) {
                    queuedBytes -= next.length;
                }
            }
            synchronized (writeLock) {
                out.write(next);
                out.flush();
            }
            written(next.length);
        }
    }

    /**
     * Once per client: leaves the registry, then runs the listeners in order; a throwing listener is logged at ERROR
     * with the request id and the rest still run.
     */
    // @req FR-SSE-004
    void runListeners() {
        List<CloseListener> run;
        synchronized (this) {
            if (!listenersRan.compareAndSet(false, true)) {
                return;
            }
            run = new ArrayList<>(listeners);
            listeners.clear();
        }
        if (registry != null) {
            registry.remove(this);
        }
        for (CloseListener l : run) {
            run(l);
        }
    }

    private void run(CloseListener l) {
        try {
            l.onClose();
        } catch (Exception e) {
            log.error("sse " + rid + " onClose listener failed", e);
        }
    }
}
