package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.tinyhttpd.http.Response;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * File transfer, event stream and protocol upgrade over TinyHttpd, kept apart from the exchange bridge so later
 * waves fill these bodies without editing the adapter core.
 */
// @req FR-ENGINE-001
final class TinyHttpdFeatures {

    private TinyHttpdFeatures() {
    }

    // @req FR-ENGINE-001
    static void sendFile(Response res, int status, List<Map.Entry<String, String>> headers, File file, long offset,
            long length) {
        throw new UnsupportedOperationException("file transfer on the TinyHttpd adapter arrives in wave 6");
    }

    // @req FR-ENGINE-001
    static void eventStream(Response res, int status, List<Map.Entry<String, String>> headers, StreamDrain drain) {
        throw new UnsupportedOperationException("event streams on the TinyHttpd adapter arrive in wave 7");
    }

    // @req FR-ENGINE-001
    static void upgrade(Response res, List<Map.Entry<String, String>> headers, UpgradeHandler handler) {
        throw new UnsupportedOperationException("protocol upgrades on the TinyHttpd adapter arrive in wave 7");
    }
}
