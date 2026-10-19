package com.snoworca.naruserve.spi;

import java.io.IOException;
import java.io.OutputStream;

/** Core-owned event-stream loop; the adapter picks the thread that runs it. */
// @req IR-HTTP-004
@FunctionalInterface
public interface StreamDrain {

    /** Writes events and returns only when the core reports the stream ended. */
    // @req IR-HTTP-004
    void drain(OutputStream out) throws IOException;
}
