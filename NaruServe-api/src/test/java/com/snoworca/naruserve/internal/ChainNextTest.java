package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.testing.RecordingExchange;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.ErrorHandler;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Next;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** The once-only next contract. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md. */
public class ChainNextTest {

    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    private Middleware mw(String name) {
        return (req, res, next) -> {
            calls.add(name);
            next.run();
        };
    }

    private RouteHandler h(String name) {
        return (req, res) -> {
            calls.add(name);
            res.send(name);
        };
    }

    /** Runs {@code r}, recording "ISE" or "NPE" when it throws that exception. */
    private void expect(String label, Runnable r) {
        try {
            r.run();
            calls.add(label + " returned");
        } catch (IllegalStateException e) {
            calls.add(label + " ISE");
        } catch (NullPointerException e) {
            calls.add(label + " NPE");
        }
    }

    // SDS-AC-9; FR-APP-004 AC-1, T35
    @Test
    public void sdsAc9_FR_APP_004_AC1_secondRunThrowsAndDownstreamRanOnce() {
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> {
            next.run();
            expect("second run", next::run);
        });
        t.app().use(mw("m2"));
        t.app().get("/x", h("h"));
        assertEquals("h", t.send("GET", "/x").text());
        assertEquals(Arrays.asList("m2", "h", "second run ISE"), calls);
    }

    // SDS-AC-9; FR-APP-004 AC-5
    @Test
    public void sdsAc9_FR_APP_004_AC5_runAfterErrorAndErrorAfterRunThrow() {
        TestApp t = TestApp.create();
        t.app().use("/a", (req, res, next) -> {
            next.error(new IllegalArgumentException("a"));
            expect("run after error", next::run);
        });
        t.app().use("/b", (req, res, next) -> {
            next.run();
            expect("error after run", () -> next.error(new IllegalArgumentException("b")));
        });
        t.app().get("/a", h("ha"));
        t.app().get("/b", h("hb"));
        assertEquals(500, t.send("GET", "/a").status());
        assertEquals(200, t.send("GET", "/b").status());
        assertEquals(Arrays.asList("run after error ISE", "hb", "error after run ISE"), calls);
    }

    // SDS-AC-10; FR-APP-004 AC-2
    @Test
    public void sdsAc10_FR_APP_004_AC2_runAfterSendThrowsAndRunsNoDownstreamStep() {
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> {
            res.send("x");
            expect("run after send", next::run);
        });
        t.app().use(mw("m2"));
        t.app().get("/x", h("h"));
        ClientResponse r = t.send("GET", "/x");
        assertEquals("x", r.text());
        assertEquals(Collections.singletonList("run after send ISE"), calls);
    }

    // SDS-AC-10; FR-APP-004 AC-2
    @Test
    public void sdsAc10_FR_APP_004_AC2_keptNextCalledAfterTheExchangeEndedThrows() {
        AtomicReference<Next> kept = new AtomicReference<>();
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> {
            kept.set(next);
            res.status(204).end();
        });
        t.app().get("/x", h("h"));
        assertEquals(204, t.send("GET", "/x").status());
        assertNotNull(kept.get());
        expect("late run", kept.get()::run);
        expect("late error", () -> kept.get().error(new IllegalArgumentException("late")));
        assertEquals(Arrays.asList("late run ISE", "late error ISE"), calls);
    }

    // SDS-AC-10; FR-APP-004 AC-2
    @Test
    public void sdsAc10_FR_APP_004_AC2_keptNextOfAnUnansweredChainThrowsAfterTheExchangeEnded() {
        AtomicReference<Next> kept = new AtomicReference<>();
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> kept.set(next));
        t.app().get("/x", h("h"));
        assertEquals(200, t.send("GET", "/x").status());
        expect("late run", kept.get()::run);
        assertEquals(Collections.singletonList("late run ISE"), calls);
    }

    // SDS-AC-11; FR-APP-004 AC-3, FR-APP-004 AC-6
    @Test
    public void sdsAc11_FR_APP_004_AC3_AC6_markerAfterRunSeesTheHandlerAndACommittedResponse() {
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> {
            next.run();
            calls.add("after " + calls.contains("h") + " " + res.isCommitted());
        });
        t.app().get("/x", h("h"));
        t.send("GET", "/x");
        assertEquals(Arrays.asList("h", "after true true"), calls);
    }

    // SDS-AC-11; FR-APP-004 AC-4, T36
    @Test
    public void sdsAc11_FR_APP_004_AC4_middlewareThatAnswersWithoutNextStopsTheChain() {
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> res.status(401).send("no"));
        t.app().use(mw("m2"));
        t.app().get("/x", mw("r"), h("h"));
        ClientResponse r = t.send("GET", "/x");
        assertEquals(401, r.status());
        assertEquals("no", r.text());
        assertEquals(new ArrayList<String>(), calls);
    }

    // SDS-AC-11; FR-APP-004 AC-3; CON-APP-003 AC-1
    @Test
    public void sdsAc11_FR_APP_004_AC3_downstreamExceptionLeavesNextRunUnchanged() {
        IllegalArgumentException thrown = new IllegalArgumentException("boom");
        AtomicReference<Throwable> caught = new AtomicReference<>();
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> {
            try {
                next.run();
            } catch (RuntimeException e) {
                caught.set(e);
                throw e;
            }
        });
        t.app().get("/x", (req, res) -> {
            throw thrown;
        });
        assertEquals(500, t.send("GET", "/x").status());
        org.junit.Assert.assertSame(thrown, caught.get());
    }

    // SDS-AC-11; FR-APP-004 AC-3
    @Test
    public void sdsAc11_FR_APP_004_AC3_checkedDownstreamExceptionPassesThroughNextRunUnwrapped() {
        java.io.IOException thrown = new java.io.IOException("io");
        AtomicReference<Throwable> caught = new AtomicReference<>();
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> {
            try {
                next.run();
            } catch (Throwable e) {
                caught.set(e);
                throw e;
            }
        });
        t.app().get("/x", (req, res) -> {
            throw thrown;
        });
        assertEquals(500, t.send("GET", "/x").status());
        org.junit.Assert.assertSame(thrown, caught.get());
    }

    // SDS-AC-12; FR-APP-004 AC-5
    @Test
    public void sdsAc12_FR_APP_004_AC5_errorNullThrowsNpeAndALaterRunStillWorks() {
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> {
            expect("error(null)", () -> next.error(null));
            next.run();
        });
        t.app().get("/x", h("h"));
        assertEquals("h", t.send("GET", "/x").text());
        assertEquals(Arrays.asList("error(null) NPE", "h"), calls);
    }

    // SDS-AC-9; FR-APP-004 AC-1
    @Test
    public void sdsAc9_FR_APP_004_AC1_eachInvocationHasItsOwnContinuation() {
        TestApp t = TestApp.create();
        t.app().use(mw("m1"));
        t.app().use(mw("m2"));
        t.app().get("/x", h("h"));
        t.send("GET", "/x");
        t.send("GET", "/x");
        assertEquals(Arrays.asList("m1", "m2", "h", "m1", "m2", "h"), calls);
    }

    // SDS-AC-11; FR-APP-004 AC-3
    @Test
    public void sdsAc11_FR_APP_004_AC3_throwAfterNextErrorDoesNotReachTheErrorPathAgain() {
        IllegalArgumentException e = new IllegalArgumentException("once");
        MiddlewarePipeline pipeline = new MiddlewarePipeline(com.snoworca.naruserve.routing.RouterPolicy.strict());
        pipeline.addGlobal((req, res, next) -> {
            next.error(e);
            throw e;
        });
        RecordingExchange ex = new RecordingExchange("GET", "/x");
        DefaultResponse res = new DefaultResponse(ex, false);
        // A recording onError handler that does not answer, so the standard 500 still applies.
        List<Throwable> seen = Collections.synchronizedList(new ArrayList<>());
        ErrorHandler recording = (err, req, r) -> seen.add(err);
        // run returns normally, so the Dispatcher's catch never hands e to the error path a second time.
        pipeline.run(new DefaultRequest(ex, com.snoworca.naruserve.Method.GET, Collections.<String, String>emptyMap()),
                res, Collections.<Middleware>emptyList(), h("h"),
                new ErrorPath(Collections.singletonList(recording),
                        new SafeLogger(new RecordingLogger())),
                "0000000000000000");
        assertEquals(500, ex.status);
        assertEquals(1, ex.commits.get());
        assertEquals(new ArrayList<String>(), calls);
        assertEquals("the error path ran exactly once with e", Collections.singletonList(e), seen);
    }

    // FR-APP-004 AC-3 — a different exception thrown after next.error(e) is logged, not silently dropped
    @Test
    public void FR_APP_004_AC3_aDifferentExceptionAfterNextErrorIsLoggedAtError() {
        IllegalArgumentException routed = new IllegalArgumentException("routed");
        IllegalStateException later = new IllegalStateException("later");
        MiddlewarePipeline pipeline = new MiddlewarePipeline(com.snoworca.naruserve.routing.RouterPolicy.strict());
        pipeline.addGlobal((req, res, next) -> {
            next.error(routed);
            throw later;
        });
        RecordingExchange ex = new RecordingExchange("GET", "/x");
        DefaultResponse res = new DefaultResponse(ex, false);
        RecordingLogger log = new RecordingLogger();
        pipeline.run(new DefaultRequest(ex, com.snoworca.naruserve.Method.GET, Collections.<String, String>emptyMap()),
                res, Collections.<Middleware>emptyList(), h("h"),
                new ErrorPath(Collections.emptyList(), new SafeLogger(log)), "0123456789abcdef");
        assertEquals(500, ex.status);
        assertEquals(1, ex.commits.get());
        assertTrue(log.errors.toString(), log.errors.contains(later));
        boolean namesRequest = false;
        for (String line : log.lines) {
            namesRequest |= line.startsWith("ERROR ") && line.contains("0123456789abcdef")
                    && line.contains("after next.error");
        }
        assertTrue(log.lines.toString(), namesRequest);
    }

    // SDS-AC-8, SDS-AC-10; FR-APP-003 AC-8, FR-APP-004 AC-6
    @Test
    public void sdsAc8_FR_APP_004_AC6_nextCalledFromAnotherThreadDuringTheRequestThrows() {
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> {
            Thread other = new Thread(() -> expect("other thread run", next::run));
            other.start();
            other.join(5000);
            next.run();
        });
        t.app().get("/x", h("h"));
        assertEquals("h", t.send("GET", "/x").text());
        assertEquals(Arrays.asList("other thread run ISE", "h"), calls);
    }
}
