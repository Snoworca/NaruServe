package com.snoworca.naruserve.proxy;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The backend response body as handed to {@code res.relay}: closing it closes the backend body and then releases the
 * request's {@link ProxyExchange}. The first of this close and the {@code res.onAbort} notification releases the
 * exchange, so the concurrency slot is held until the relayed body ends or the response is aborted.
 */
// @req FR-PROXY-019
// @req REL-PROXY-001
final class RelayedBodyStream extends FilterInputStream {

    private final ProxyExchange exchange;

    // @req FR-PROXY-019
    RelayedBodyStream(InputStream body, ProxyExchange exchange) {
        super(body);
        this.exchange = exchange;
    }

    // @req FR-PROXY-019
    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            exchange.release();
        }
    }
}
