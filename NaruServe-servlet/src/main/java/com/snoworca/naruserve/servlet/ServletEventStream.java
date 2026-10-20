package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.spi.StreamDrain;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;

/**
 * The event stream over a Servlet container, the mirror of the TinyHttpd one: open streams are counted against the
 * adapter's {@link Pool}, which refuses with 503 before the exchange is started; the stream runs under an
 * {@link AsyncContext} with no timeout, and the core's drain runs on the pool's own daemon threads once the
 * container's request thread left the handler, never on a container thread.
 */
// @req FR-SVL-005
final class ServletEventStream {

    private ServletEventStream() {
    }

    /**
     * Under the exchange lock only the start checks, a slot and the start mark; then {@code startAsync()} with
     * timeout 0, the status and headers, one flush so the client sees them at once, {@code drain.attach} and the
     * registration of the drain that {@link ServletExchange#afterHandler} submits. A refused slot throws a 503
     * {@link HttpException} with the exchange unstarted.
     */
    // @req FR-SVL-005
    static void start(ServletExchange ex, int status, List<Map.Entry<String, String>> headers, StreamDrain drain)
            throws IOException {
        Pool pool = ex.pool();
        synchronized (ex) {
            ex.requireDrainable();
            ex.requireStartable();
            if (!ex.request().isAsyncSupported()) {
                // a filter or servlet registered without asyncSupported: refuse while the exchange is unstarted
                throw new IllegalStateException("SSE needs asyncSupported=true on every filter and servlet in the chain");
            }
            if (!pool.tryAcquire()) {
                throw new HttpException(503);
            }
            ex.markStarted();
        }
        boolean registered = false;
        try {
            AsyncContext ac = ex.request().startAsync();
            ac.setTimeout(0); // a container default (30 s on Jetty 9.4) would end a long stream
            ex.async(ac);
            pool.track(ex, ac);
            ex.head(status, headers);
            ex.response().flushBuffer();
            FlushingStream out = new FlushingStream(ex, ex.response().getOutputStream());
            drain.attach(out);
            ex.register(drain, out);
            registered = true;
        } finally {
            if (!registered) {
                pool.release();
                ex.complete(); // an AsyncContext already started would otherwise never end
                pool.streams.remove(ex);
            }
        }
    }

    /**
     * The adapter's SSE slots and the executor of their drains, built at bind from {@code maxSseStreams}: at most
     * that many daemon threads named {@code naruserve-servlet-sse-<n>}, which end when idle.
     */
    // @req FR-SVL-005
    static final class Pool {

        private final int max;
        private final AtomicInteger open = new AtomicInteger();
        private final ThreadPoolExecutor executor;
        /** The exchanges whose AsyncContext started and has not completed yet. */
        private final Set<ServletExchange> streams =
                Collections.newSetFromMap(new ConcurrentHashMap<ServletExchange, Boolean>());

        // @req FR-SVL-005
        Pool(int max) {
            this.max = max;
            AtomicInteger n = new AtomicInteger();
            // Never more than max threads: every task holds a slot; a task queued behind a thread still leaving
            // its finished drain waits for it instead of being refused.
            executor = new ThreadPoolExecutor(max, max, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
                    r -> {
                        Thread t = new Thread(r, "naruserve-servlet-sse-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
            executor.allowCoreThreadTimeOut(true);
        }

        /** Takes a slot when fewer than {@code max} are open. */
        // @req FR-SVL-005
        boolean tryAcquire() {
            while (true) {
                int n = open.get();
                if (n >= max) {
                    return false;
                }
                if (open.compareAndSet(n, n + 1)) {
                    return true;
                }
            }
        }

        /** Gives one slot back; each taken slot is released once. */
        // @req FR-SVL-005
        void release() {
            open.decrementAndGet();
        }

        /** Runs {@code task} on a pool thread; throws RejectedExecutionException once the pool was shut down. */
        // @req FR-SVL-005
        void submit(Runnable task) {
            executor.execute(task);
        }

        /** Lets running drains finish and their threads end; no new drain is accepted. */
        // @req FR-SVL-005
        void shutdown() {
            executor.shutdown();
        }

        /** Registers the started AsyncContext of {@code ex}; it leaves the set when the container completes it. */
        // @req FR-SVL-006
        void track(ServletExchange ex, AsyncContext ac) {
            streams.add(ex);
            ac.addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent e) {
                    streams.remove(ex);
                }

                @Override
                public void onTimeout(AsyncEvent e) {
                    ex.abort(); // the drain's next write fails, so it ends and gives its slot back
                    streams.remove(ex);
                }

                @Override
                public void onError(AsyncEvent e) {
                    ex.abort();
                    streams.remove(ex);
                }

                @Override
                public void onStartAsync(AsyncEvent e) {
                }
            });
        }

        /**
         * Aborts every open stream, which completes its {@code AsyncContext} once, each failure swallowed: the container closes the
         * output, which frees a drain blocked in a write on Jetty at once and elsewhere at the container's own
         * write timeout. No thread is interrupted here.
         */
        // @req FR-SVL-006
        void completeAll() {
            for (ServletExchange ex : streams) {
                try {
                    ex.abort(); // aborted before complete, so a running drain cannot write to the ended response
                } catch (RuntimeException e) {
                    // the container already ended this request
                }
                streams.remove(ex);
            }
        }

        /** The open stream count: slots taken and not yet given back. */
        // @req FR-SVL-006
        int open() {
            return open.get();
        }

        /** Stops the executor and interrupts its own drain threads, never a container thread. */
        // @req FR-SVL-006
        void shutdownNow() {
            executor.shutdownNow();
        }

        /** Waits up to {@code timeout} for the drain threads to end; false when some are still running. */
        // @req FR-SVL-006
        boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return executor.awaitTermination(timeout, unit);
        }
    }

    /**
     * The body stream handed to attach and drain: each write runs under the exchange lock, fails at once after an
     * abort and is flushed to the client at once, so the container buffers no event or heartbeat.
     */
    // @req FR-SVL-005
    static final class FlushingStream extends FilterOutputStream {

        private final ServletExchange ex;

        FlushingStream(ServletExchange ex, OutputStream out) {
            super(out);
            this.ex = ex;
        }

        @Override
        public void write(int b) throws IOException {
            synchronized (ex) {
                ex.checkNotAborted();
                out.write(b);
                out.flush();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            synchronized (ex) {
                ex.checkNotAborted();
                out.write(b, off, len);
                out.flush();
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (ex) {
                ex.checkNotAborted();
                out.flush();
            }
        }

        @Override
        public void close() {
            // AsyncContext.complete() ends the body.
        }
    }

    /**
     * Runs {@code drain}; an IOException or RuntimeException aborts the exchange. Either way the slot is released
     * once and the AsyncContext completed once.
     */
    // @req FR-SVL-005
    static Runnable drainTask(ServletExchange ex, StreamDrain drain, OutputStream out) {
        return () -> {
            try {
                drain.drain(out);
            } catch (IOException | RuntimeException e) {
                ex.abort();
            } finally {
                Pool pool = ex.pool();
                pool.release();
                ex.complete();
                pool.streams.remove(ex);
            }
        };
    }
}
