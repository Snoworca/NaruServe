package com.snoworca.naruserve.internal;

import static com.snoworca.naruserve.internal.WebSocketSenderTest.eventually;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/** Sessions whose adapter fails after onOpen, and an idle watch refused in a race with terminate. */
public class WebSocketUpgradeFailureTest {

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final RecordingLogger log = new RecordingLogger();
    private final DefaultRestApp app = (DefaultRestApp) RestApp.builder().httpAdapter(adapter)
            .jsonCodec(new StubJsonCodec()).logger(log).build();

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
    }

    // FR-WS-006 AC-3 (close listeners exactly once), FR-APP-019 AC-9 (one close line, 1006 for an end without a
    // close frame), FR-WS-007 (the sender slot is released): HttpExchange.webSocket throwing after onOpen without an
    // onClose still ends the session once
    @Test(timeout = 20000)
    public void FR_WS_006_AC3_aThrowAfterOpenWithoutOnCloseStillEndsTheSessionOnce() throws Exception {
        BlockingQueue<DefaultWebSocketSession> handled = new LinkedBlockingQueue<>();
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<String> closedWith = new AtomicReference<>();
        app.ws("/ws", (req, ws) -> {
            ws.attribute("user", "kim");
            ws.onClose((code, reason) -> {
                closes.incrementAndGet();
                closedWith.set(code + " " + reason);
            });
            ws.sendText("hi");
            handled.add((DefaultWebSocketSession) ws);
        });
        app.listen(0);
        Future<ClientResponse> f = adapter.submit(ClientRequest.upgrade("/ws"));
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        DefaultWebSocketSession s = handled.poll(5, TimeUnit.SECONDS);
        assertTrue("the write never returned", eventually(() -> ch.sent().size() == 1 && s.sender().idle(s)));
        assertEquals("the session has a sender slot", 1, s.sender().slots());
        ch.readLoopFails(new IllegalStateException("connection lost"));
        f.get(5, TimeUnit.SECONDS);
        assertTrue("the exchange did not end", eventually(() -> app.webSocketRegistry().size() == 0));
        assertEquals(1, RecordingLogger.countMatching(log.lines, "ERROR websocket [0-9a-f]{16} failed connection lost"));
        assertEquals(1, closes.get());
        assertEquals("1006 ", closedWith.get());
        assertEquals(1, RecordingLogger.countMatching(log.lines, "INFO WS /ws 1006 \\d+ms rid=[0-9a-f]{16}"));
        assertEquals(0, s.attributeCount());
        assertEquals("sender slot left", 0, s.sender().slots());
        s.onClose(1000, ""); // a late adapter onClose changes nothing
        assertEquals(1, closes.get());
        assertEquals(1, RecordingLogger.countMatching(log.lines, "INFO WS /ws \\d+ \\d+ms rid=[0-9a-f]{16}"));
    }

    // FR-WS-010 AC-7 (an opening after the force close ends at once without user code), FR-WS-008: an idle watch
    // refused by a scheduler terminate already stopped never leaves onOpen; the session is aborted instead
    @Test(timeout = 20000)
    public void FR_WS_010_AC7_anIdleWatchRefusedAfterTerminateAbortsTheSessionInsteadOfThrowing() throws Exception {
        WebSocketUpgrade upgrade = new WebSocketUpgrade(new DefaultWebSocketOptions(), null, new SafeLogger(log));
        upgrade.idle().shutdownNow(1000); // the registry still reads OPEN, as for an opening that read it before terminate
        AtomicInteger handlers = new AtomicInteger();
        DefaultWebSocketSession s = WebSocketSenderTest.session(upgrade, "00000000000000aa",
                (req, ws) -> handlers.incrementAndGet());
        WebSocketSenderTest.RecordingChannel ch = new WebSocketSenderTest.RecordingChannel();
        s.onOpen(ch);
        assertEquals("abort", ch.calls.poll(5, TimeUnit.SECONDS));
        assertEquals(0, handlers.get());
    }
}
