package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AppStartException;
import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.internal.ShutdownSequenceTest.Closer;
import com.snoworca.naruserve.internal.ShutdownSequenceTest.RecordingAdapter;
import com.snoworca.naruserve.internal.ShutdownSequenceTest.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** listen() start failures. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-7.sds.md. */
public class StartFailureTest {

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final RecordingAdapter adapter = new RecordingAdapter(events);
    private final RecordingLogger logger = new RecordingLogger(events);

    private DefaultRestApp app() {
        DefaultRestApp app = (DefaultRestApp) RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                .logger(logger).build();
        logger.app = app;
        app.onStop(ctx -> events.add("onStop"));
        return app;
    }

    private static Throwable listenFails(RestApp app) {
        try {
            app.listen(0);
        } catch (RuntimeException e) {
            return e;
        }
        fail("listen should have failed");
        return null;
    }

    // SDS-AC-1; FR-APP-010 AC-1
    @Test
    public void sdsAc1_FR_APP_010_AC1_uncheckedOnStartFailureIsRethrownAsIsAndBindNeverRuns() {
        DefaultRestApp app = app();
        IllegalArgumentException cause = new IllegalArgumentException("bad config");
        app.onStart(ctx -> {
            throw cause;
        });
        assertSame(cause, listenFails(app));
        assertFalse(events.toString(), events.contains("bind"));
    }

    // SDS-AC-2; FR-APP-010 AC-2
    @Test
    public void sdsAc2_FR_APP_010_AC2_checkedOnStartFailureIsWrappedInAppStartException() {
        DefaultRestApp app = app();
        IOException cause = new IOException("disk");
        app.onStart(ctx -> {
            throw cause;
        });
        Throwable thrown = listenFails(app);
        assertEquals(AppStartException.class, thrown.getClass());
        assertSame(cause, thrown.getCause());
    }

    // SDS-AC-3; FR-APP-010 AC-3
    @Test
    public void sdsAc3_FR_APP_010_AC3_bindFailureStopsAndReleasesTheAdapterBeforeThrowing() {
        DefaultRestApp app = app();
        IOException cause = new IOException("address in use");
        adapter.bindFailure = cause;
        Throwable thrown = listenFails(app);
        assertEquals(AppStartException.class, thrown.getClass());
        assertSame(cause, thrown.getCause());
        assertEquals(Arrays.asList("bind", "adapter.stop", "adapter.release", "logger.flush FAILED",
                "logger.close FAILED"), events);
    }

    // SDS-AC-4; FR-APP-010 AC-4
    @Test
    public void sdsAc4_FR_APP_010_AC4_resourcesCloseInReverseSeeingFailedThenTheStateIsTerminated() {
        DefaultRestApp app = app();
        app.onStart(ctx -> {
            ctx.own(new Closer(events, "first", app));
            ctx.own(new Closer(events, "second", app));
            throw new IllegalStateException("start broke");
        });
        listenFails(app);
        assertEquals(Arrays.asList("close second FAILED", "close first FAILED", "logger.flush FAILED",
                "logger.close FAILED"), events);
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-5; FR-APP-010 AC-5
    @Test
    public void sdsAc5_FR_APP_010_AC5_noOnStopRunsForAnOnStartOrBindFailure() {
        DefaultRestApp a = app();
        a.onStart(ctx -> {
            throw new IOException("x");
        });
        listenFails(a);
        adapter.bindFailure = new IOException("y");
        listenFails(app());
        assertFalse(events.toString(), events.contains("onStop"));
    }

    // SDS-AC-6; FR-APP-010 AC-6
    @Test
    public void sdsAc6_FR_APP_010_AC6_ownedAppLoggerIsClosedOnceAsTheLastStepBeforeTerminated() {
        DefaultRestApp app = app();
        app.onStart(ctx -> {
            ctx.own(new Closer(events, "first", app));
            ctx.own(logger);
            ctx.own(new Closer(events, "last", app));
            throw new IllegalStateException("start broke");
        });
        listenFails(app);
        assertEquals(1, logger.closes.get());
        assertEquals(Arrays.asList("close last FAILED", "close first FAILED", "logger.flush FAILED",
                "logger.close FAILED"), events);
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-11 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-4.sds.md; FR-ENGINE-004 AC-2
    @Test
    public void sdsAc11_FR_ENGINE_004_AC2_tooFewConnectionsFailTheFreezeAfterOnStartAndBeforeBind() {
        DefaultRestApp app = app();
        app.onStart(ctx -> events.add("onStart"));
        app.server(o -> o.maxWorkers(4).maxSseStreams(2).maxConnections(5));
        Throwable e = listenFails(app);
        assertEquals(IllegalStateException.class, e.getClass());
        for (String part : new String[] {"maxConnections (5)", "maxWorkers (4)", "maxSseStreams (2)"}) {
            org.junit.Assert.assertTrue(e.getMessage(), e.getMessage().contains(part));
        }
        assertEquals(Arrays.asList("onStart", "logger.flush FAILED", "logger.close FAILED"), events);
        assertEquals(AppState.TERMINATED, app.state());

        events.clear();
        DefaultRestApp ok = app();
        ok.server(o -> o.maxWorkers(4).maxSseStreams(2).maxConnections(6));
        ok.listen(0);
        assertEquals(AppState.RUNNING, ok.state());
        org.junit.Assert.assertTrue(events.toString(), events.contains("bind"));
        ok.shutdown(1, java.util.concurrent.TimeUnit.SECONDS);
    }
}
