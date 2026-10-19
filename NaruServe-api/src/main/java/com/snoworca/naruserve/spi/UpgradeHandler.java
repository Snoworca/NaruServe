package com.snoworca.naruserve.spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Protocol-upgrade callback, called after the 101 response with the exchange's raw streams. */
// @req IR-HTTP-004
@FunctionalInterface
public interface UpgradeHandler {

    // @req IR-HTTP-004
    void upgraded(InputStream in, OutputStream out) throws IOException;
}
