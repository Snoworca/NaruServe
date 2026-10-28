package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Deferred;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** A terminal adapter may reject body access while its already acquired input still needs cleanup. */
public class HttpInputCleanupContractTest {
    private static final class GuardedExchange extends LifecycleExchange {
        final AtomicInteger bodyAccesses = new AtomicInteger();
        GuardedExchange() {
            super("POST", "/input", "original".getBytes(StandardCharsets.UTF_8),
                    "Content-Type", "application/json", "Content-Length", "8");
        }
        @Override public InputStream requestBody() {
            bodyAccesses.incrementAndGet();
            if (transportAborted.get()) throw new IllegalStateException("container request already recycled");
            return input;
        }
    }

    // FR-APP-023 AC-3 AC-8; IR-HTTP-009 AC-2: close owned input without consulting a cancelled container.
    @Test public void cancellationClosesTheOriginalInputWithoutReacquiringTheAdapter() {
        DefaultRestApp app = new DefaultRestApp();
        app.jsonCodec(new HttpTransportRetentionContractTest.BodyCodec());
        Deferred pending = Deferred.pending();
        app.methodAsync("POST", "/input", (request, response, next) -> {
            assertEquals("original", request.body(String.class));
            return pending;
        });
        GuardedExchange exchange = new GuardedExchange();
        app.freeze(); app.dispatcher().handle(exchange);
        assertEquals(0, exchange.input.closes.get());
        exchange.abort();
        assertEquals("original input leaked after terminal cancellation", 1, exchange.input.closes.get());
        assertEquals("cleanup queried a recycled container", 1, exchange.bodyAccesses.get());
        pending.resolve(); exchange.abort();
        assertEquals(1, exchange.input.closes.get());
    }

    // FR-APP-023 AC-3 AC-8: normal completion closes the same source acquired by the body reader.
    @Test public void normalCompletionReusesTheAcquiredInputForCleanup() {
        DefaultRestApp app = new DefaultRestApp();
        app.jsonCodec(new HttpTransportRetentionContractTest.BodyCodec());
        app.post("/input", (request, response) -> response.send(request.body(String.class)));
        GuardedExchange exchange = new GuardedExchange();
        app.freeze(); app.dispatcher().handle(exchange);
        assertEquals("original", HttpExecutionContractTest.text(exchange.recording));
        assertEquals(1, exchange.input.closes.get());
        assertEquals("cleanup acquired a second adapter body handle", 1, exchange.bodyAccesses.get());
    }

    // FR-APP-023 AC-3 AC-8: retaining a source must not move acquisition before the application callback.
    @Test public void unreadNormalRequestStillReleasesItsInputAfterTheCallback() {
        GuardedExchange exchange = new GuardedExchange();
        DefaultRestApp app = new DefaultRestApp();
        app.post("/input", (request, response) -> {
            assertEquals(0, exchange.bodyAccesses.get());
            response.send("ack");
            assertEquals(0, exchange.input.closes.get());
        });
        app.freeze(); app.dispatcher().handle(exchange);
        assertEquals(1, exchange.bodyAccesses.get());
        assertEquals(1, exchange.input.closes.get());
    }
}
