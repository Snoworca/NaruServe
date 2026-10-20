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
import org.jspecify.annotations.Nullable;

/**
 * The core {@link SseClient} of one stream and the {@link StreamDrain} the adapter runs for it. Events are encoded on
 * the calling thread and queued whole; the drain writes them in call order.
 * shortcut: no queue limit, heartbeat or handler-thread direct write yet; FR-SSE-002 (wave 7 part 10) adds them.
 */
// @req IR-SSE-001
// @req FR-SSE-001
final class DefaultSseClient implements SseClient, StreamDrain {

    private final @Nullable DefaultResponse res;
    private final @Nullable SseRegistry registry;
    private final String rid;
    private final SafeLogger log;
    // Guarded by this.
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private final List<CloseListener> listeners = new ArrayList<>();
    private boolean ended;
    private @Nullable String cause;
    private boolean listenersRan;

    DefaultSseClient(DefaultResponse res, SseRegistry registry, Thread handlerThread, String rid) {
        this(res, registry, rid, registry.log());
    }

    private DefaultSseClient(@Nullable DefaultResponse res, @Nullable SseRegistry registry, String rid,
            SafeLogger log) {
        this.res = res;
        this.registry = registry;
        this.rid = rid;
        this.log = log;
    }

    /** The client of a HEAD request: ended, its listeners already run, in no registry. */
    // @req IR-SSE-001
    static DefaultSseClient ended(String rid, SafeLogger log) {
        DefaultSseClient c = new DefaultSseClient(null, null, rid, log);
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

    private boolean offer(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        synchronized (this) {
            if (ended) {
                return false;
            }
            queue.add(b);
            notifyAll();
            return true;
        }
    }

    @Override
    public void close() {
        endBy("app");
    }

    @Override
    public synchronized boolean isOpen() {
        return !ended;
    }

    /** Adds {@code listener}, or runs it at once on the caller when the listeners already ran. */
    @Override
    public void onClose(CloseListener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (this) {
            if (!listenersRan) {
                listeners.add(listener);
                return;
            }
        }
        run(listener);
    }

    /** Ends the stream; the first cause wins. True when this call ended it. */
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

    /** Writes the queued events until the stream ended and the queue is empty; never throws to the adapter. */
    @Override
    public void drain(OutputStream out) {
        DefaultResponse r = Objects.requireNonNull(res, "drain of an ended client");
        try {
            while (true) {
                byte[] next;
                synchronized (this) {
                    while (queue.isEmpty() && !ended) {
                        wait();
                    }
                    next = queue.poll();
                }
                if (next == null) {
                    break;
                }
                out.write(next);
                out.flush();
            }
            r.sseEnded(null);
        } catch (IOException e) {
            endBy("client");
            r.sseEnded(e);
        } catch (RuntimeException e) {
            endBy("error");
            r.sseEnded(e);
        } catch (InterruptedException e) {
            endBy("shutdown");
            r.sseEnded(e);
            Thread.currentThread().interrupt();
        } catch (Error e) {
            endBy("error");
            r.sseEnded(e);
            throw e;
        } finally {
            synchronized (this) {
                queue.clear();
            }
            if (registry != null) {
                registry.remove(this);
            }
            runListeners();
        }
    }

    /** Runs the listeners once per client; a throwing listener is logged and the rest still run. */
    void runListeners() {
        List<CloseListener> run;
        synchronized (this) {
            if (listenersRan) {
                return;
            }
            listenersRan = true;
            run = new ArrayList<>(listeners);
            listeners.clear();
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
