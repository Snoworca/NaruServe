package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Deferred;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Access logging must observe an adapter-owned rejection without restarting its response. */
public class AdapterResponseMetricsContractTest {
    private static final byte[] ERROR = "{\"status\":500}".getBytes(StandardCharsets.UTF_8);
    private static final class MetricsExchange extends LifecycleExchange {
        int reportedStatus;
        long reportedBytes = -1;
        boolean throwStatus, throwBytes;
        MetricsExchange() { super("GET", "/synthetic"); }
        // Deliberately valid methods even before the additive SPI exists: dispatcher behavior is the RED.
        public int responseStatus() {
            if (throwStatus) throw new AssertionError("status observer failed");
            return reportedStatus;
        }
        public long responseBytesWritten() {
            if (throwBytes) throw new IllegalStateException("byte observer failed");
            return reportedBytes;
        }
        void adapterFailure() throws Exception {
            recording.commit(500, Collections.emptyList(), ERROR.length).write(ERROR);
            recording.finish();
            abort();
        }
    }
    private static DefaultRestApp app(RecordingLogger log, Deferred pending) {
        DefaultRestApp app = new DefaultRestApp();
        app.logger(log).accessLog(o -> o.includeDuration(false).includeRequestId(false));
        app.getAsync("/synthetic", (request, response, next) -> { response.status(207); return pending; });
        app.freeze();
        return app;
    }
    private static void completed(RecordingLogger log, MetricsExchange exchange, int status, long bytes) {
        assertEquals(Collections.singletonList("INFO GET /synthetic " + status + " aborted in=0 out=" + bytes), log.lines);
        assertEquals(1, exchange.input.closes.get());
        assertEquals(1, exchange.recording.finishes.get());
        assertEquals(1, exchange.recording.aborts.get());
    }

    // IR-HTTP-009 AC-8; FR-APP-019 AC-3 AC-5: legacy implementations remain source-compatible.
    @Test public void optionalStatisticsAreDefaultUnknownValuesForExistingExchanges() throws Exception {
        Method status = HttpExchange.class.getMethod("responseStatus");
        Method bytes = HttpExchange.class.getMethod("responseBytesWritten");
        assertTrue(status.isDefault()); assertEquals(int.class, status.getReturnType());
        assertTrue(bytes.isDefault()); assertEquals(long.class, bytes.getReturnType());
        LifecycleExchange legacy = new LifecycleExchange("GET", "/legacy");
        assertEquals(0, status.invoke(legacy)); assertEquals(-1L, bytes.invoke(legacy));
    }

    // IR-HTTP-009 AC-8; FR-APP-019 AC-3 AC-5: report actual adapter status/body, once after termination.
    @Test public void adapterOwnedErrorReportsItsWireStatusAndWrittenBodyBytesOnce() throws Exception {
        RecordingLogger log = new RecordingLogger(); Deferred pending = Deferred.pending();
        MetricsExchange exchange = new MetricsExchange();
        exchange.reportedStatus = 500; exchange.reportedBytes = ERROR.length;
        app(log, pending).dispatcher().handle(exchange);
        assertTrue(log.lines.isEmpty());
        exchange.adapterFailure();
        completed(log, exchange, 500, ERROR.length);
        assertEquals(500, exchange.recording.status);
        assertArrayEquals(ERROR, exchange.recording.body.toByteArray());
        pending.resolve(); exchange.abort(); completed(log, exchange, 500, ERROR.length);
    }

    // IR-HTTP-009 AC-8: unknown and invalid observations cannot replace the core's own metrics.
    @Test public void unknownOrInvalidOptionalStatisticsPreserveCoreValues() throws Exception {
        for (int reported : new int[]{0, 99, 1000}) {
            RecordingLogger log = new RecordingLogger(); Deferred pending = Deferred.pending();
            MetricsExchange exchange = new MetricsExchange();
            exchange.reportedStatus = reported; exchange.reportedBytes = -2;
            app(log, pending).dispatcher().handle(exchange); exchange.adapterFailure();
            completed(log, exchange, 207, 0);
        }
    }

    // IR-HTTP-009 AC-8: failing observations are independent, fail-soft and cannot skip input cleanup.
    @Test public void failingOptionalObserversStillLogOnceAndReleaseTheInput() throws Exception {
        for (int failure = 1; failure <= 3; failure++) {
            RecordingLogger log = new RecordingLogger(); Deferred pending = Deferred.pending();
            MetricsExchange exchange = new MetricsExchange();
            exchange.reportedStatus = 500; exchange.reportedBytes = ERROR.length;
            exchange.throwStatus = (failure & 1) != 0; exchange.throwBytes = (failure & 2) != 0;
            app(log, pending).dispatcher().handle(exchange); exchange.adapterFailure();
            completed(log, exchange, exchange.throwStatus ? 207 : 500, exchange.throwBytes ? 0 : ERROR.length);
            pending.resolve(); exchange.abort();
            assertEquals(1, log.lines.size()); assertEquals(1, exchange.input.closes.get());
        }
    }
}
