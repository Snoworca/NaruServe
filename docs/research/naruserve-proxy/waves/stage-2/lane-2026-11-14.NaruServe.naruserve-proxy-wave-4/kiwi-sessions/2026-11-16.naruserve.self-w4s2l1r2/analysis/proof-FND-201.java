package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.common.AbortListeners;
import com.snoworca.naruserve.internal.DefaultResponse;
import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.internal.ResponseState;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.testing.RecordingExchange;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** FND-201 proof: an exchange-reported abort after upgrading.set(true) but before the 101 (onOpen) is dropped. */
public class ProofFnd201Test {

    private static ResponseState stateOf(Response res) {
        return ((DefaultResponse) res).state();
    }

    // FR-HTTP-035 AC-7 / FR-APP-019: the adapter reports the client disconnect while writing the 101 handshake,
    // before onOpen, and throws; the upgrade never happened, so the response must end ABORTED, each res.onAbort
    // listener must run once and the request line must be marked aborted.
    @Test
    public void preOnOpenExchangeReportedAbortRunsTheListenerAndEndsTheResponseAborted() {
        RecordingExchange rec = new RecordingExchange("GET", "/ws", "Connection", "Upgrade", "Upgrade", "websocket");
        AbortListeners aborts = new AbortListeners();
        rec.webSocketScript = (settings, endpoint) -> {
            aborts.abort(); // the adapter reports the disconnect it hit writing the handshake
            throw new IOException("peer gone before the 101");
        };
        HttpExchange ex = (HttpExchange) Proxy.newProxyInstance(HttpExchange.class.getClassLoader(),
                new Class<?>[] {HttpExchange.class}, (proxy, m, args) -> {
                    if (m.getName().equals("onAbort")) {
                        aborts.onAbort((Runnable) args[0]);
                        return null;
                    }
                    try {
                        return m.invoke(rec, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        RecordingLogger log = new RecordingLogger();
        DefaultRestApp app = new DefaultRestApp();
        app.logger(log);
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Response> captured = new AtomicReference<>();
        app.use((req, res, next) -> {
            captured.set(res);
            res.onAbort(runs::incrementAndGet);
            next.run();
        });
        app.ws("/ws", (req, ws) -> { });
        app.freeze();
        app.dispatcher().handle(ex);
        List<String> requestLines = new ArrayList<>();
        for (String line : log.info()) {
            if (line.startsWith("GET /ws ")) {
                requestLines.add(line);
            }
        }
        String diag = "state=" + stateOf(captured.get()) + " runs=" + runs.get() + " commits=" + rec.commits.get()
                + " status=" + rec.status + " lines=" + requestLines;
        assertEquals(diag, 1, runs.get());
        assertEquals(diag, ResponseState.ABORTED, stateOf(captured.get()));
        assertEquals(diag, 1, requestLines.size());
        assertTrue(diag, requestLines.get(0).contains("aborted"));
    }

    // Contrast: an adapter that refuses the commit after its own abort (as TinyHttpdExchange.checkNotAborted does)
    // masks the dropped notification, because the error-path commit failure aborts the response.
    @Test
    public void contrastAdapterRefusingCommitAfterAbortStillEndsAborted() {
        java.util.concurrent.atomic.AtomicBoolean gone = new java.util.concurrent.atomic.AtomicBoolean();
        RecordingExchange rec = new RecordingExchange("GET", "/ws", "Connection", "Upgrade", "Upgrade", "websocket");
        AbortListeners aborts = new AbortListeners();
        rec.webSocketScript = (settings, endpoint) -> {
            gone.set(true);
            aborts.abort();
            throw new IOException("peer gone before the 101");
        };
        HttpExchange ex = (HttpExchange) Proxy.newProxyInstance(HttpExchange.class.getClassLoader(),
                new Class<?>[] {HttpExchange.class}, (proxy, m, args) -> {
                    if (m.getName().equals("onAbort")) {
                        aborts.onAbort((Runnable) args[0]);
                        return null;
                    }
                    if (m.getName().equals("commit") && gone.get()) {
                        throw new IOException("exchange aborted");
                    }
                    try {
                        return m.invoke(rec, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        DefaultRestApp app = new DefaultRestApp();
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Response> captured = new AtomicReference<>();
        app.use((req, res, next) -> {
            captured.set(res);
            res.onAbort(runs::incrementAndGet);
            next.run();
        });
        app.ws("/ws", (req, ws) -> { });
        app.freeze();
        app.dispatcher().handle(ex);
        assertEquals(1, runs.get());
        assertEquals(ResponseState.ABORTED, stateOf(captured.get()));
    }
}
