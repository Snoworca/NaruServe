package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.internal.ShutdownSequenceTest.RecordingAdapter;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** The lifecycle gate during STOPPING. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-7.sds.md. */
public class StoppingGateTest {

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<ClientResponse> answered = Collections.synchronizedList(new ArrayList<>());
    private final com.snoworca.naruserve.testing.RecordingLogger log = new com.snoworca.naruserve.testing.RecordingLogger();

    private DefaultRestApp app(RecordingAdapter adapter) {
        DefaultRestApp app = (DefaultRestApp) RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                .logger(log).build();
        app.use((req, res, next) -> {
            calls.add("middleware " + req.path());
            next.run();
        });
        app.get("/x", (req, res) -> {
            calls.add("handler");
            res.send("ok");
        });
        app.onError((e, req, res) -> calls.add("onError"));
        return app;
    }

    private static void assert503(ClientResponse r) {
        assertEquals(503, r.status());
        assertEquals("close", r.header("Connection"));
        String body = new String(r.body(), StandardCharsets.UTF_8);
        assertTrue(body, body.matches(
                "\\{\"error\":\\{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"Service Unavailable\","
                        + "\"requestId\":\"[0-9a-f]{16}\"}}"));
    }

    // SDS-AC-21; FR-APP-013 AC-1
    // SDS-AC-22; FR-APP-013 AC-2
    @Test
    public void sdsAc21_sdsAc22_FR_APP_013_AC1_AC2_requestFromABeforeWaitParticipantGets503AndNothingRuns() {
        RecordingAdapter adapter = new RecordingAdapter(events);
        DefaultRestApp app = app(adapter);
        app.shutdownParticipants().add(ShutdownParticipants.Phase.BEFORE_WAIT,
                () -> answered.add(adapter.delegate.send(ClientRequest.of("GET", "/x"))));
        app.listen(0);
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(1, answered.size());
        assert503(answered.get(0));
        assertEquals(new ArrayList<String>(), calls);
        // FR-APP-019 AC-3 (SDS-AC-5 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md): the gate's 503
        // during the before-wait phase writes exactly one access line, as every other dispatched answer does.
        List<String> lines = log.info();
        assertEquals(lines.toString(), 1, lines.size());
        assertTrue(lines.get(0), lines.get(0).matches("GET /x 503 \\d+ms in=0 out=\\d+ rid=[0-9a-f]{16}"));
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-23; FR-APP-013 AC-3
    @Test
    public void sdsAc23_FR_APP_013_AC3_requestThatPassedTheGateBeforeStoppingCompletesWithItsHandler()
            throws Exception {
        RecordingAdapter adapter = new RecordingAdapter(events);
        DefaultRestApp app = (DefaultRestApp) RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                .build();
        CountDownLatch inHandler = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        app.get("/slow", (req, res) -> {
            inHandler.countDown();
            release.await(5, TimeUnit.SECONDS);
            res.send("slow ok");
        });
        app.listen(0);
        Future<ClientResponse> r = adapter.delegate.submit(ClientRequest.of("GET", "/slow"));
        assertTrue(inHandler.await(5, TimeUnit.SECONDS));
        // Release the handler only once adapter.stop is waiting, so the test proves stop waits for it.
        Thread releaser = new Thread(() -> {
            try {
                while (!events.contains("adapter.stop")) {
                    Thread.sleep(5);
                }
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        releaser.start();
        app.shutdown(5, TimeUnit.SECONDS);
        releaser.join(5000);
        ClientResponse resp = r.get(5, TimeUnit.SECONDS);
        assertEquals(200, resp.status());
        assertEquals("slow ok", new String(resp.body(), StandardCharsets.UTF_8));
    }

    // SDS-AC-24; FR-APP-013 AC-4
    @Test
    public void sdsAc24_FR_APP_013_AC4_gateRunsBeforePreRequestChecksDuringStopping() {
        RecordingAdapter adapter = new RecordingAdapter(events);
        DefaultRestApp app = app(adapter);
        app.shutdownParticipants().add(ShutdownParticipants.Phase.BEFORE_WAIT, () -> {
            answered.add(adapter.delegate.send(ClientRequest.of("OPTIONS", "*")));
            answered.add(adapter.delegate.send(ClientRequest.of("FROB", "/x")));
            answered.add(adapter.delegate.send(ClientRequest.of("GET", "x")));
        });
        app.listen(0);
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(3, answered.size());
        for (ClientResponse r : answered) {
            assert503(r);
        }
        assertEquals(new ArrayList<String>(), calls);
    }

    // SDS-AC-24; FR-APP-013 AC-4
    @Test
    public void sdsAc24_FR_APP_013_AC4_preCheckRequestsAreHeldOnTheStartGateInStarting() throws Exception {
        List<Future<ClientResponse>> held = new ArrayList<>();
        DefaultRestApp[] ref = new DefaultRestApp[1];
        RecordingAdapter adapter = new RecordingAdapter(events) {
            @Override
            public void bind(String host, int port, ExchangeHandler handler) throws IOException {
                super.bind(host, port, handler);
                for (ClientRequest r : Arrays.asList(ClientRequest.of("OPTIONS", "*"),
                        ClientRequest.of("FROB", "/x"), ClientRequest.of("GET", "x"))) {
                    held.add(delegate.submit(r));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (ref[0].lifecycle().heldOnStartGate() < 3) {
                    assertTrue("requests never held", System.nanoTime() < deadline);
                    Thread.yield();
                }
            }
        };
        DefaultRestApp app = app(adapter);
        ref[0] = app;
        app.listen(0);
        for (Future<ClientResponse> f : held) {
            ClientResponse r = f.get(5, TimeUnit.SECONDS);
            assertNotEquals(503, r.status());
        }
        assertFalse(calls.contains("handler"));
        app.shutdown(1, TimeUnit.SECONDS);
    }
}
