package com.snoworca.naruserve.testing;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import org.jspecify.annotations.NullMarked;

/**
 * Test harness: a {@link DefaultRestApp} served by an {@link InMemoryHttpAdapter}. Register through {@link #app()},
 * then {@link #start()} (or the first {@link #send}) freezes the route table once and binds the adapter.
 */
// @req FR-HTTP-001
@NullMarked
public final class TestApp {

    private final DefaultRestApp app = new DefaultRestApp();
    private final InMemoryHttpAdapter adapter;
    // Guarded by this.
    private boolean started;

    private TestApp(InMemoryHttpAdapter adapter) {
        this.adapter = adapter;
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

    /** Freezes once and binds port 0; a freeze exception propagates as thrown, unwrapped. */
    // @req FR-HTTP-001
    public synchronized TestApp start() {
        if (!started) {
            app.freeze();
            adapter.bind("127.0.0.1", 0, app.dispatcher());
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
