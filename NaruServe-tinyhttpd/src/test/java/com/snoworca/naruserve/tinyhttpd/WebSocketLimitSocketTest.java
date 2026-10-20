package com.snoworca.naruserve.tinyhttpd;

import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.NoLeftoverThreads;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/** The upgrade limit over TinyHttpd. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-7.sds.md. */
public class WebSocketLimitSocketTest {

    // I-015: no naruserve-* thread outlives the test
    @Rule
    public final NoLeftoverThreads noLeftoverThreads = new NoLeftoverThreads();

    private final RestApp app = Http.app(new TinyHttpdAdapter(), new RecordingLogger());

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
    }

    // SDS-AC-4; FR-WS-009 AC-1: with maxUpgradedConnections(2) two sessions open and a third upgrade gets 503 with
    // Retry-After and the standard error body, no 101 and no handler; no NaruServe counter keeps its reservation
    @Test(timeout = 30000)
    public void sdsAc4_FR_WS_009_AC1_thirdUpgradeBeyondTheLimitGets503RetryAfterAndNoHandler() throws Exception {
        AtomicInteger handlers = new AtomicInteger();
        app.server(o -> o.maxUpgradedConnections(2));
        app.ws("/ws", (req, ws) -> {
            handlers.incrementAndGet();
            ws.sendText("hi");
        });
        int port = app.listen(0).port();
        try (RawWebSocketClient a = RawWebSocketClient.open(port, "/ws");
                RawWebSocketClient b = RawWebSocketClient.open(port, "/ws")) {
            assertEquals(a.handshake, 101, a.status());
            assertEquals(b.handshake, 101, b.status());
            assertEquals("hi", a.readFrame(TEXT).text());
            assertEquals("hi", b.readFrame(TEXT).text());
            String third = Http.send(port, RawWebSocketClient.request("/ws", "Upgrade, close",
                    "Sec-WebSocket-Key: " + RawWebSocketClient.KEY, "Sec-WebSocket-Version: 13"));
            assertEquals(third, 503, Http.status(third));
            assertNotNull(third, Http.header(third, "Retry-After"));
            assertTrue(third, Http.body(third).matches("\\{\"error\":\\{\"code\":\"SERVICE_UNAVAILABLE\","
                    + "\"message\":\"[^\"]*\",\"requestId\":\"[0-9a-f]{16}\"}}"));
            assertEquals(2, handlers.get());
            assertEquals("the refused upgrade kept its reservation", 2, WebSocketShutdownSocketTest.registrySize(app));
        }
    }
}
