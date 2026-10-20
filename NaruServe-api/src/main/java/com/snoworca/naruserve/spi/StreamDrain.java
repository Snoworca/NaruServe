package com.snoworca.naruserve.spi;

import java.io.IOException;
import java.io.OutputStream;

/** Core-owned event-stream loop; the adapter picks the thread that runs it. */
// @req IR-HTTP-007
@FunctionalInterface
public interface StreamDrain {

    /** Writes events and returns only when the core reports the stream ended. */
    // @req IR-HTTP-007
    void drain(OutputStream out) throws IOException;

    /**
     * Hands over the body stream before the drain starts, so the core can send from the handler thread. Every
     * adapter's {@link HttpExchange#eventStream} calls it before returning, with the same stream it later passes to
     * {@link #drain}. Does nothing by default.
     */
    // @req IR-HTTP-007
    default void attach(OutputStream out) {
    }

    /**
     * Reports that the adapter cannot start this drain, so the core can release its stream and close listeners.
     * Does nothing by default and leaves this interface usable as a lambda.
     */
    // @req IR-HTTP-007
    default void abort() {
    }
}
