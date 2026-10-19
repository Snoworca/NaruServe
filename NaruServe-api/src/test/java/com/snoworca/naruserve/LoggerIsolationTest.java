package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.Flushable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** A throwing app logger. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-8.sds.md. */
public class LoggerIsolationTest {

    /** Every method throws; flush and close throw too. Counts the calls. */
    static final class ThrowingLogger implements Logger, Flushable, AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();

        private RuntimeException fail() {
            calls.incrementAndGet();
            return new IllegalStateException("logger down");
        }

        @Override
        public void trace(String message) {
            throw fail();
        }

        @Override
        public void debug(String message) {
            throw fail();
        }

        @Override
        public void info(String message) {
            throw fail();
        }

        @Override
        public void warn(String message) {
            throw fail();
        }

        @Override
        public void error(String message) {
            throw fail();
        }

        @Override
        public void error(String message, Throwable error) {
            throw fail();
        }

        @Override
        public void flush() throws IOException {
            calls.incrementAndGet();
            throw new IOException("flush down");
        }

        @Override
        public void close() {
            throw fail();
        }
    }

    private final ThrowingLogger logger = new ThrowingLogger();

    // SDS-AC-1; FR-APP-014 AC-1, FR-APP-014 AC-3
    @Test
    public void sdsAc1_FR_APP_014_AC1_AC3_throwingLoggerChangesNoResponse() {
        TestApp t = TestApp.create();
        t.app().logger(logger).accessLog(o -> o.level(LogLevel.WARN));
        t.app().get("/ok", (req, res) -> res.send("fine"));
        t.app().get("/boom", (req, res) -> {
            throw new IllegalStateException("boom");
        });
        t.app().get("/unfinished", (req, res) -> res.status(202));
        ClientResponse ok = t.send("GET", "/ok");
        assertEquals(200, ok.status());
        assertEquals("fine", new String(ok.body(), StandardCharsets.UTF_8));
        ClientResponse boom = t.send("GET", "/boom");
        assertEquals(500, boom.status());
        String body = new String(boom.body(), StandardCharsets.UTF_8);
        assertTrue(body, body.matches("\\{\"error\":\\{\"code\":\"INTERNAL_ERROR\",\"message\":"
                + "\"Internal Server Error\",\"requestId\":\"[0-9a-f]{16}\"}}"));
        ClientResponse unfinished = t.send("GET", "/unfinished");
        assertEquals(202, unfinished.status());
        assertEquals(false, unfinished.abnormallyClosed());
        // access lines, the 500 error log and the unfinished-response warn all reached the logger and were dropped
        assertTrue(String.valueOf(logger.calls.get()), logger.calls.get() >= 5);
    }

    // SDS-AC-2; FR-APP-014 AC-2, FR-APP-014 AC-3
    @Test
    public void sdsAc2_FR_APP_014_AC2_AC3_listenAndShutdownCompleteWithAThrowingLogger() {
        TestApp t = TestApp.create();
        t.app().logger(logger);
        t.app().onStop(ctx -> {
            throw new IllegalStateException("onStop fails, so the lifecycle logs an error");
        });
        t.start();
        assertEquals(AppState.RUNNING, t.app().state());
        t.app().shutdown(1, TimeUnit.SECONDS);
        assertEquals(AppState.TERMINATED, t.app().state());
        assertTrue(String.valueOf(logger.calls.get()), logger.calls.get() >= 3); // error, flush, close
    }
}
