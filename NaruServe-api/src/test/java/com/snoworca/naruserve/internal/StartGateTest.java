package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AppStartException;
import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** The start gate of the handler listen() binds. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-3.sds.md. */
public class StartGateTest {

    /** Binds an in-memory adapter, then sends GET /x from a worker thread before bind returns. */
    static final class EarlyRequestAdapter implements HttpAdapter {
        final InMemoryHttpAdapter delegate = new InMemoryHttpAdapter();
        final boolean failBind;
        volatile Future<ClientResponse> early;
        volatile DefaultRestApp app;

        EarlyRequestAdapter(boolean failBind) {
            this.failBind = failBind;
        }

        @Override
        public void bind(String host, int port, ExchangeHandler handler) throws IOException {
            delegate.bind(host, port, handler);
            early = delegate.submit(ClientRequest.of("GET", "/x"));
            // Return only once the request is held on the start gate.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (app.lifecycle().heldOnStartGate() == 0) {
                assertTrue("request never reached the start gate", System.nanoTime() < deadline);
                Thread.yield();
            }
            if (failBind) {
                throw new IOException("bind failed");
            }
        }

        @Override
        public int boundPort() {
            return delegate.boundPort();
        }

        @Override
        public void stop(long timeout, TimeUnit unit) {
            delegate.stop(timeout, unit);
        }

        @Override
        public void release() {
            delegate.release();
        }
    }

    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    private RestApp app(EarlyRequestAdapter adapter) {
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).build();
        adapter.app = (DefaultRestApp) app;
        // Route middleware: the global pipeline lands with the FR-APP-003 SDS.
        app.get("/x", (req, res, next) -> {
            calls.add("mw " + app.state());
            next.run();
        }, (req, res) -> {
            calls.add("handler " + app.state());
            res.send("ok");
        });
        app.onError((error, req, res) -> calls.add("onError"));
        return app;
    }

    // SDS-AC-10; FR-APP-009 AC-5
    @Test
    public void sdsAc10_FR_APP_009_AC5_requestDuringStartingIsHeldUntilRunningThenAnsweredNormally()
            throws Exception {
        EarlyRequestAdapter adapter = new EarlyRequestAdapter(false);
        RestApp app = app(adapter);
        app.listen(0);
        ClientResponse r = adapter.early.get(5, TimeUnit.SECONDS);
        assertEquals(200, r.status());
        assertEquals("ok", new String(r.body(), StandardCharsets.UTF_8));
        assertEquals(java.util.Arrays.asList("mw RUNNING", "handler RUNNING"), calls);
        app.shutdown(1, TimeUnit.SECONDS);
    }

    // SDS-AC-11; FR-APP-009 AC-6
    @Test
    public void sdsAc11_FR_APP_009_AC6_bindFailureAnswersTheHeldRequest503WithConnectionCloseAndNothingRuns()
            throws Exception {
        EarlyRequestAdapter adapter = new EarlyRequestAdapter(true);
        RestApp app = app(adapter);
        try {
            app.listen(0);
            fail("bind threw IOException");
        } catch (AppStartException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
        assertEquals(AppState.FAILED, app.state());
        ClientResponse r = adapter.early.get(5, TimeUnit.SECONDS);
        assertEquals(503, r.status());
        assertEquals("close", r.header("Connection"));
        assertEquals("application/json; charset=UTF-8", r.header("Content-Type"));
        String body = new String(r.body(), StandardCharsets.UTF_8);
        assertTrue(body, body.matches(
                "\\{\"error\":\\{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"Service Unavailable\","
                        + "\"requestId\":\"[0-9a-f]{16}\"}}"));
        assertEquals(String.valueOf(r.body().length), r.header("Content-Length"));
        assertFalse(r.abnormallyClosed());
        assertEquals(new ArrayList<String>(), calls);
    }
}
