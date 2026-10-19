package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
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
            dispatcher.handle(ex);
            return;
        }
        unavailable(ex, start);
    }

    /** 503, Connection: close and the standard body; no pipeline runs. */
    private void unavailable(HttpExchange ex, long start) {
        String rid = RequestIds.next();
        byte[] body = StandardErrors.json(503, StandardErrors.reason(503), rid);
        boolean head = "HEAD".equals(ex.methodToken());
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(new SimpleImmutableEntry<>("Content-Type", "application/json; charset=UTF-8"));
        headers.add(new SimpleImmutableEntry<>("Content-Length", String.valueOf(body.length)));
        headers.add(new SimpleImmutableEntry<>("Connection", "close"));
        boolean aborted = false;
        long written = 0;
        try {
            OutputStream out = ex.commit(503, headers, head ? 0 : body.length);
            if (!head) {
                out.write(body);
                written = body.length;
            }
            ex.finish();
        } catch (IOException | RuntimeException e) {
            aborted = true;
            ex.abort();
        }
        accessLog.write(ex.methodToken(), ex.rawPath(), 503, aborted, start, 0, written, rid);
    }
}
