package com.snoworca.naruserve.spi;

/**
 * Core entry the adapter calls once per exchange, on the adapter's worker thread. The exchange may still be open
 * when {@code handle} returns (a stream copy can go on on another thread): the adapter keeps it open until the core
 * ends it with {@link HttpExchange#finish} or {@link HttpExchange#abort}, and may force-close it only at its stop
 * deadline.
 */
// @req IR-HTTP-003
@FunctionalInterface
public interface ExchangeHandler {

    // @req IR-HTTP-003
    void handle(HttpExchange exchange);
}
