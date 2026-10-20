package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.ServerOptions;
import com.snoworca.naruserve.spi.ServerSettings;
import com.snoworca.naruserve.testing.NoLeftoverThreads;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.tinyhttpd.ServerConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/** Connection limits and the worker pool. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-5.sds.md. */
public class ConnectionLimitsTest {

    // I-015: no naruserve-* thread outlives the test
    @Rule
    public final NoLeftoverThreads noLeftoverThreads = new NoLeftoverThreads();

    private final RecordingLogger log = new RecordingLogger();
    private final List<Socket> sockets = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private RestApp app;

    @After
    public void stop() throws IOException {
        release.countDown();
        for (Socket s : sockets) {
            s.close();
        }
        if (app != null) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
    }

    /** Opens a connection and sends GET /block, which holds its worker until {@link #release}. */
    private Socket blocked(int port) throws IOException {
        Socket s = new Socket();
        sockets.add(s);
        s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        s.setSoTimeout(10000);
        s.getOutputStream().write(Http.get("/block").getBytes(StandardCharsets.ISO_8859_1));
        s.getOutputStream().flush();
        return s;
    }

    /** Starts an app with {@code options}, holds two connections in /block and returns the third's response. */
    private String thirdWhileTwoAreHeld(Consumer<ServerOptions> options, AtomicInteger onError) throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        app = Http.app(new TinyHttpdAdapter(), log);
        app.server(options::accept);
        app.get("/block", (req, res) -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            res.send("held");
        });
        app.onError((err, req, res) -> {
            onError.incrementAndGet();
            res.status(500).send("error");
        });
        int port = app.listen(0).port();
        Socket first = blocked(port);
        Socket second = blocked(port);
        assertTrue("both held requests reach their handler", entered.await(5, TimeUnit.SECONDS));

        String third = Http.send(port, Http.get("/block"));

        release.countDown();
        assertEquals(200, Http.status(Http.readAll(first.getInputStream())));
        assertEquals(200, Http.status(Http.readAll(second.getInputStream())));
        return third;
    }

    private int accessLines(String status) {
        int n = 0;
        for (String line : log.info()) {
            if (line.contains(" " + status + " ")) {
                n++;
            }
        }
        return n;
    }

    // SDS-AC-11; FR-ENGINE-004 AC-1; FR-ENGINE-011 AC-4: maxEventStreams and maxUpgradedConnections unchanged by wave 7
    @Test
    public void sdsAc11_FR_ENGINE_004_AC1_defaultConnectionLimitsReachTheBuilder() throws Exception {
        ServerConfig c = TinyHttpdAdapter.configure(Http.settings().build(), (q, r) -> { }).build().getConfig();
        assertEquals(192, c.getMaxWorkers());
        assertEquals(128, c.getMaxEventStreams());
        assertEquals(512, c.getMaxConnections());
        assertEquals(128, c.getMaxUpgradedConnections());
        assertEquals(256, c.maxConnectionsPerAddress);
        assertEquals(320, c.getMaxConnections() - c.getMaxWorkers());
    }

    // SDS-AC-8 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-11.sds.md; FR-SSE-003 AC-5: the limit mapping
    // still sets maxEventStreams to maxSseStreams, and the SSE slots every exchange of a bound adapter shares take
    // exactly maxSseStreams streams
    @Test(timeout = 20000)
    public void sdsAc8_FR_SSE_003_AC5_maxEventStreamsStillMapsAndTheSseSlotsHoldMaxSseStreams() throws Exception {
        ServerSettings s = Http.settings().maxSseStreams(7).build();
        assertEquals(7, TinyHttpdAdapter.configure(s, (q, r) -> { }).build().getConfig().getMaxEventStreams());
        TinyHttpdAdapter a = new TinyHttpdAdapter();
        List<TinyHttpdEventStream.Slots> seen = new ArrayList<>();
        AtomicInteger taken = new AtomicInteger();
        try {
            a.bind(s, ex -> {
                TinyHttpdEventStream.Slots slots = ((TinyHttpdExchange) ex).slots();
                synchronized (seen) {
                    seen.add(slots);
                }
                if (seen.size() == 1) {
                    int n = 0;
                    while (slots.tryAcquire()) {
                        n++;
                    }
                    taken.set(n);
                    for (int i = 0; i < n; i++) {
                        slots.release();
                    }
                }
                try {
                    ex.commit(200, Collections.<Map.Entry<String, String>>emptyList(), 0).flush();
                    ex.finish();
                } catch (IOException e) {
                    ex.abort();
                }
            });
            assertEquals(200, Http.status(Http.send(a.boundPort(), Http.get("/a"))));
            assertEquals(200, Http.status(Http.send(a.boundPort(), Http.get("/b"))));
        } finally {
            Http.stopQuietly(a);
        }
        assertEquals(7, taken.get());
        assertEquals(2, seen.size());
        assertTrue("one Slots per bind", seen.get(0) == seen.get(1));
        assertEquals(0, seen.get(0).open());
    }

    // SDS-AC-12; FR-ENGINE-004 AC-3
    @Test(timeout = 30000)
    public void sdsAc12_FR_ENGINE_004_AC3_fullWorkersAndZeroQueueAnswerThePlain503() throws Exception {
        AtomicInteger onError = new AtomicInteger();
        String third = thirdWhileTwoAreHeld(o -> o.maxWorkers(1).maxSseStreams(1).maxConnections(2), onError);
        assertEquals(third, 503, Http.status(third));
        assertTrue(third, third.contains("text/plain"));
        assertEquals(0, onError.get());
        assertEquals(0, accessLines("503"));
        assertEquals(2, accessLines("200"));
    }

    // SDS-AC-12; FR-ENGINE-004 AC-4
    @Test(timeout = 30000)
    public void sdsAc12_FR_ENGINE_004_AC4_aThirdConnectionFromOneAddressIsRefused() throws Exception {
        AtomicInteger onError = new AtomicInteger();
        String third = thirdWhileTwoAreHeld(o -> o.maxConnectionsPerAddress(2), onError);
        assertEquals(third, 503, Http.status(third));
        assertEquals(0, onError.get());
        assertEquals(0, accessLines("503"));
    }
}
