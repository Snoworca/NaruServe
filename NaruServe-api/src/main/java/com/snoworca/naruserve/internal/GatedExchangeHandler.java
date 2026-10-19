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

    // @req FR-APP-009
    @Override
    public void handle(HttpExchange ex) {
        long start = System.nanoTime();
        AppState s = lifecycle.state();
        if (s == AppState.STARTING) {
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
        try {
            OutputStream out = ex.commit(503, headers, head ? 0 : body.length);
            if (!head) {
                out.write(body);
            }
            ex.finish();
        } catch (IOException | RuntimeException e) {
            aborted = true;
            ex.abort();
        }
        accessLog.write(ex.methodToken(), ex.rawPath(), 503, aborted, start, 0, head ? 0 : body.length, rid);
    }
}
