package com.snoworca.naruserve.testing;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import org.jspecify.annotations.NullMarked;

/**
 * Test harness: one app built by {@code RestApp.builder()} over one {@link InMemoryHttpAdapter} and a
 * {@link StubJsonCodec}. Register through {@link #app()}, then {@link #start()} (or the first {@link #send}) calls
 * {@code listen(0)} once, which freezes the route table and binds the adapter.
 */
// @req FR-HTTP-001
// @req FR-APP-009
@NullMarked
public final class TestApp {

    private final DefaultRestApp app;
    private final InMemoryHttpAdapter adapter;
    // Guarded by this.
    private boolean started;

    private TestApp(InMemoryHttpAdapter adapter) {
        this.adapter = adapter;
        this.app = (DefaultRestApp) RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).build();
    }

    /** A new unfrozen app over a default in-memory adapter. */
    // @req FR-HTTP-001
    public static TestApp create() {
        return new TestApp(new InMemoryHttpAdapter());
    }

    /** A new unfrozen app over {@code adapter}, which must not be bound yet. */
    // @req FR-HTTP-001
    public static TestApp create(InMemoryHttpAdapter adapter) {
        return new TestApp(adapter);
    }

    // @req FR-HTTP-001
    public RestApp app() {
        return app;
    }

    /** Registers a WebSocket-only route through the internal hook; wave 3 never dispatches to it. */
    // @req FR-HTTP-001
    public TestApp webSocketRoute(String path) {
        app.webSocketRoute(path, (req, res) -> {
            throw new IllegalStateException("WebSocket hook routes are not dispatched in wave 3");
        });
        return this;
    }

    /** Calls {@code listen(0)} once; a freeze exception propagates as thrown, unwrapped. */
    // @req FR-HTTP-001
    // @req FR-APP-009
    public synchronized TestApp start() {
        if (!started) {
            app.listen(0);
            started = true;
        }
        return this;
    }

    /** Sends one request on the caller thread, starting first if needed; {@code headerPairs} is name, value, .... */
    // @req FR-HTTP-001
    public ClientResponse send(String method, String target, String... headerPairs) {
        if (headerPairs.length % 2 != 0) {
            throw new IllegalArgumentException("headerPairs needs a value for every name");
        }
        start();
        ClientRequest request = ClientRequest.of(method, target);
        for (int i = 0; i < headerPairs.length; i += 2) {
            request.header(headerPairs[i], headerPairs[i + 1]);
        }
        return adapter.send(request);
    }
}
