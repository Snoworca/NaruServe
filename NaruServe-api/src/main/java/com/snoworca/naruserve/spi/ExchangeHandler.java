package com.snoworca.naruserve.spi;

/** Core entry the adapter calls once per exchange, on the adapter's worker thread. */
// @req IR-HTTP-003
@FunctionalInterface
public interface ExchangeHandler {

    // @req IR-HTTP-003
    void handle(HttpExchange exchange);
}
