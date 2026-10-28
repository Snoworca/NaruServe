package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpExchange;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The exchange handler {@code listen()} binds: the start gate and the lifecycle gate in front of the ungated
 * {@link Dispatcher}. The state is read once at entry; a request held in STARTING re-reads it when released.
 */
// @req FR-APP-009
// @req FR-APP-013
final class GatedExchangeHandler implements ExchangeHandler {

    private final AppLifecycle lifecycle;
    private final Dispatcher dispatcher;
    private final AccessLog accessLog;

    // @req FR-APP-009
    GatedExchangeHandler(AppLifecycle lifecycle, Dispatcher dispatcher, AccessLog accessLog) {
        this.lifecycle = lifecycle;
        this.dispatcher = dispatcher;
        this.accessLog = accessLog;
    }

    /**
     * The first check of every exchange, before the dispatcher's pre-request checks: RUNNING dispatches; STARTING
     * waits on the start gate unless a stop is pending; FAILED, STOPPING, TERMINATED or a pending stop get 503.
     */
    // @req FR-APP-009
    // @req FR-APP-012
    // @req FR-APP-013
    @Override
    public void handle(HttpExchange ex) {
        long start = System.nanoTime();
        AppState s = lifecycle.state();
        if (s == AppState.STARTING && !lifecycle.pendingStop()) {
            lifecycle.awaitStartGate();
            s = lifecycle.state();
        }
        if (s == AppState.RUNNING) {
            lifecycle.withRequest(()->dispatcher.handle(ex));
            return;
        }
        unavailable(ex, start);
    }

    /** 503, Connection: close and the standard body; no pipeline runs. */
    private void unavailable(HttpExchange ex, long start) {
        List<Map.Entry<String, String>> headers = new ArrayList<>(3);
        headers.add(new SimpleImmutableEntry<>("Connection", "close"));
        Dispatcher.answerDirect(ex, 503, headers, RequestIds.next(), start, accessLog);
    }
}
