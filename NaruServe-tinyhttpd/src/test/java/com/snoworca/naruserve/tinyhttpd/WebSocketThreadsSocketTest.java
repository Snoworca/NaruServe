package com.snoworca.naruserve.tinyhttpd;

import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.testing.NoLeftoverThreads;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/** Sender and idle threads per app. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-6.sds.md. */
public class WebSocketThreadsSocketTest {
    // I-015: no naruserve-* or tinyhttpd-* thread outlives a test that started a server
    @Rule
    public final NoLeftoverThreads noLeftoverThreads = new NoLeftoverThreads();


    private final RestApp first = Http.app(new TinyHttpdAdapter(), new RecordingLogger());
    private final RestApp second = Http.app(new TinyHttpdAdapter(), new RecordingLogger());

    @After
    public void stop() {
        first.shutdown(2, TimeUnit.SECONDS);
        second.shutdown(2, TimeUnit.SECONDS);
    }

    private static int listen(RestApp app) {
        app.ws("/ws", (req, ws) -> ws.sendText("hi"));
        return app.listen(0).port();
    }

    // SDS-AC-3; FR-WS-007 AC-2: two bound apps in one JVM own sender threads named after their own port
    @Test(timeout = 20000)
    public void sdsAc3_FR_WS_007_AC2_twoBoundAppsOwnDistinctSenderThreadNames() throws Exception {
        Set<Thread> before = new HashSet<>(Thread.getAllStackTraces().keySet());
        int p1 = listen(first);
        int p2 = listen(second);
        assertNotEquals(p1, p2);
        try (RawWebSocketClient a = RawWebSocketClient.open(p1, "/ws");
                RawWebSocketClient b = RawWebSocketClient.open(p2, "/ws")) {
            assertEquals("hi", a.readFrame(TEXT).text());
            assertEquals("hi", b.readFrame(TEXT).text());
            // only threads made since the apps were built, so a leftover thread of a reused port cannot count
            List<String> senders = new ArrayList<>();
            List<String> idle = new ArrayList<>();
            for (Thread t : Http.startedSince(before)) {
                if (t.getName().contains("-ws-sender-")) {
                    assertTrue(t.getName(), t.isDaemon());
                    senders.add(t.getName().replaceAll("-[0-9]+$", ""));
                } else if (t.getName().endsWith("-ws-idle")) {
                    idle.add(t.getName());
                }
            }
            Collections.sort(senders);
            Collections.sort(idle);
            String s1 = "naruserve-" + p1 + "-ws-sender";
            String s2 = "naruserve-" + p2 + "-ws-sender";
            assertEquals(p1 < p2 ? Arrays.asList(s1, s2) : Arrays.asList(s2, s1), senders);
            List<String> idles = Arrays.asList("naruserve-" + p1 + "-ws-idle", "naruserve-" + p2 + "-ws-idle");
            Collections.sort(idles);
            assertEquals(idles, idle);
        }
    }
}
