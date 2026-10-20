package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/** The 426 answer of a WebSocket-only path over a TinyHttpd loopback socket. */
public class WebSocketOnlySocketTest {

    private final RestApp app = Http.app(new TinyHttpdAdapter(), new RecordingLogger());

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
    }

    // FR-WS-004 AC-2: TinyHttpd replaces Connection on a non-101 answer, so only 426 and Upgrade are asserted here.
    @Test(timeout = 20000)
    public void FR_WS_004_AC2_plainGetToAWebSocketOnlyPathGets426WithUpgradeOverTinyHttpd() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        app.ws("/live", (req, ws) -> handled.incrementAndGet());
        int port = app.listen(0).port();
        String r = Http.send(port, Http.get("/live"));
        assertEquals(r, 426, Http.status(r));
        assertEquals(r, "websocket", Http.header(r, "Upgrade"));
        assertTrue(r, Http.body(r).contains("\"code\":\"UPGRADE_REQUIRED\""));
        assertEquals(0, handled.get());
    }
}
