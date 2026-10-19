package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Next;
import com.snoworca.naruserve.RouteHandler;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Next} handed to one middleware invocation. {@code run()} and {@code error(e)} together are its single
 * continuation; both run synchronously on the calling thread. Exceptions from downstream steps propagate out of
 * {@code run()} unchanged (a checked one too, although {@link Next#run} declares none).
 */
// @req FR-APP-004
final class ChainNext implements Next {

    /** State shared by every ChainNext of one request. */
    static final class Chain {
        final List<Middleware> steps;
        final RouteHandler terminal;
        final DefaultRequest req;
        final DefaultResponse res;
        final ErrorPath errors;
        final String requestId;
        final Thread owner = Thread.currentThread();
        volatile boolean ended;
        volatile boolean errorRouted;
        // The throwable next.error(e) handed to the error path; a rethrow of it is expected, anything else is logged.
        volatile @Nullable Throwable routedError;

        Chain(List<Middleware> steps, RouteHandler terminal, DefaultRequest req, DefaultResponse res,
                ErrorPath errors, String requestId) {
            this.steps = steps;
            this.terminal = terminal;
            this.req = req;
            this.res = res;
            this.errors = errors;
            this.requestId = requestId;
        }
    }

    private final Chain chain;
    private final int index;
    private final AtomicBoolean used = new AtomicBoolean();

    /** The continuation that runs {@code chain.steps[index]}, or the terminal when index is past the steps. */
    ChainNext(Chain chain, int index) {
        this.chain = chain;
        this.index = index;
    }

    // @req FR-APP-004
    // @req FR-APP-003
    @Override
    public void run() {
        consume("next.run()");
        ResponseState s = chain.res.state();
        if (s == ResponseState.COMPLETED || s == ResponseState.ABORTED) {
            throw new IllegalStateException("next.run() after the response is " + s.name());
        }
        try {
            if (index < chain.steps.size()) {
                chain.steps.get(index).handle(chain.req, chain.res, new ChainNext(chain, index + 1));
            } else {
                chain.terminal.handle(chain.req, chain.res);
            }
        } catch (Exception e) {
            throw ChainNext.<RuntimeException>sneaky(e);
        }
    }

    // @req FR-APP-004
    @Override
    public void error(Throwable error) {
        Objects.requireNonNull(error, "error");
        consume("next.error(e)");
        chain.routedError = error;
        chain.errorRouted = true;
        chain.errors.handle(error, chain.req, chain.res, chain.requestId);
    }

    /** Marks the request ended; every continuation of it, used or not, then throws. */
    // @req FR-APP-004
    void end() {
        chain.ended = true;
    }

    private void consume(String call) {
        if (chain.ended) {
            throw new IllegalStateException(call + " after the request ended");
        }
        if (Thread.currentThread() != chain.owner) {
            throw new IllegalStateException(call + " off the request thread; there is no async resume");
        }
        if (!used.compareAndSet(false, true)) {
            throw new IllegalStateException(call + ": this middleware already continued the chain");
        }
    }

    // Rethrows a checked exception unchanged through Next.run(), which declares none.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E sneaky(Throwable t) throws E {
        throw (E) t;
    }
}
