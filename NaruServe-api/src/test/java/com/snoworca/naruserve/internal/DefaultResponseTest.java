package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** DefaultResponse through the TestApp harness and the in-memory adapter. */
public class DefaultResponseTest {

    private final TestApp t = TestApp.create();
    private final AtomicReference<DefaultResponse> last = new AtomicReference<>();

    /** Registers GET /r running {@code h} and remembers the response the Dispatcher built. */
    private ClientResponse get(RouteHandler h) {
        t.app().get("/r", (req, res) -> {
            last.set((DefaultResponse) res);
            h.handle(req, res);
        });
        return t.send("GET", "/r");
    }

    private static int count(ClientResponse r, String name) {
        int n = 0;
        for (Map.Entry<String, String> h : r.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                n++;
            }
        }
        return n;
    }

    private static void assertIse(Runnable call, String state) {
        try {
            call.run();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(state));
        }
    }

    // SDS-AC-1; FR-HTTP-013 AC-1
    @Test
    public void sdsAc1_FR_HTTP_013_AC1_statusAndHeaderReturnThisAndChain() {
        ClientResponse r = get((req, res) -> {
            assertSame(res, res.status(201));
            assertSame(res, res.header("X-A", "1"));
            res.status(201).header("X-A", "1").send("ok");
        });
        assertEquals(201, r.status());
        assertEquals("1", r.header("X-A"));
        assertEquals(1, count(r, "X-A"));
        assertEquals("ok", new String(r.body(), StandardCharsets.UTF_8));
    }

    // SDS-AC-1; FR-HTTP-013 AC-2
    @Test
    public void sdsAc1_FR_HTTP_013_AC2_defaultStatusIs200() {
        ClientResponse r = get((req, res) -> res.send("ok"));
        assertEquals(200, r.status());
        assertEquals("2", r.header("Content-Length"));
    }

    // SDS-AC-1; FR-HTTP-013 AC-1
    @Test
    public void sdsAc1_FR_HTTP_013_AC1_sameNameInOtherCaseReplacesTheEntry() {
        ClientResponse r = get((req, res) -> res.header("x-a", "1").header("X-A", "2").send("ok"));
        assertEquals(1, count(r, "x-a"));
        assertEquals("2", r.header("X-A"));
    }

    // SDS-AC-2; FR-HTTP-014 AC-1
    @Test
    public void sdsAc2_FR_HTTP_014_AC1_sendStringIsUtf8TextPlain() {
        ClientResponse r = get((req, res) -> res.send("hé"));
        assertArrayEquals(new byte[] {0x68, (byte) 0xC3, (byte) 0xA9}, r.body());
        assertEquals("text/plain; charset=UTF-8", r.header("Content-Type"));
        assertEquals("3", r.header("Content-Length"));
    }

    // SDS-AC-2; FR-HTTP-014 AC-1
    @Test
    public void sdsAc2_FR_HTTP_014_AC1_explicitContentTypeIsKeptInAnyCase() {
        ClientResponse r = get((req, res) -> res.header("content-type", "text/csv").send("a,b"));
        assertEquals("text/csv", r.header("Content-Type"));
        assertEquals(1, count(r, "Content-Type"));
    }

    // SDS-AC-2; FR-HTTP-014 AC-2
    @Test
    public void sdsAc2_FR_HTTP_014_AC2_htmlIsUtf8TextHtml() {
        ClientResponse r = get((req, res) -> res.html("<p>é</p>"));
        assertArrayEquals("<p>é</p>".getBytes(StandardCharsets.UTF_8), r.body());
        assertEquals("text/html; charset=UTF-8", r.header("Content-Type"));
    }

    // SDS-AC-2; FR-HTTP-014 AC-3
    @Test
    public void sdsAc2_FR_HTTP_014_AC3_sendBytesIsOctetStreamUnlessSet() {
        ClientResponse r = get((req, res) -> res.send(new byte[] {0, (byte) 0xFF, 10}));
        assertArrayEquals(new byte[] {0, (byte) 0xFF, 10}, r.body());
        assertEquals("application/octet-stream", r.header("Content-Type"));
        assertEquals("3", r.header("Content-Length"));
        TestApp t2 = TestApp.create();
        t2.app().get("/r", (req, res) -> res.header("Content-Type", "image/png").send(new byte[] {1}));
        assertEquals("image/png", t2.send("GET", "/r").header("Content-Type"));
    }

    // SDS-AC-6; FR-HTTP-014 AC-6
    @Test
    public void sdsAc6_FR_HTTP_014_AC6_endKeepsStatusAndHeadersWithEmptyBody() {
        ClientResponse r = get((req, res) -> res.status(202).header("X-A", "1").end());
        assertEquals(202, r.status());
        assertEquals("1", r.header("X-A"));
        assertEquals("0", r.header("Content-Length"));
        assertEquals(0, r.body().length);
        assertFalse(r.abnormallyClosed());
        assertEquals(ResponseState.COMPLETED, last.get().state());
    }

    // SDS-AC-7; FR-HTTP-015 AC-4
    @Test
    public void sdsAc7_FR_HTTP_015_AC4_crOrLfInHeaderIsRefusedAndNothingAdded() {
        List<String> refused = new ArrayList<>();
        ClientResponse r = get((req, res) -> {
            String[][] bad = {{"X\r\nY", "1"}, {"X", "a\nb"}, {"X", "a\rb"}};
            for (String[] h : bad) {
                try {
                    res.header(h[0], h[1]);
                } catch (IllegalArgumentException e) {
                    refused.add(h[0] + "=" + h[1]);
                }
            }
            res.send("ok");
        });
        assertEquals(3, refused.size());
        assertNull(r.header("X"));
        assertNull(r.header("X\r\nY"));
        assertEquals(200, r.status());
    }

    // SDS-AC-7; FR-HTTP-015 AC-4, FR-HTTP-016 AC-2
    @Test
    public void sdsAc7_FR_HTTP_015_AC4_stateCheckRunsBeforeTheCrLfCheck() {
        get((req, res) -> {
            res.send("ok");
            assertIse(() -> res.header("X", "a\nb"), "COMPLETED");
        });
        assertEquals(ResponseState.COMPLETED, last.get().state());
    }

    // SDS-AC-8; FR-HTTP-015 AC-1, FR-HTTP-016 AC-1
    @Test
    public void sdsAc8_FR_HTTP_015_AC1_FR_HTTP_016_AC1_isCommittedFlipsOnSendAndCommitIsSingle() {
        List<Object> seen = new ArrayList<>();
        ClientResponse r = get((req, res) -> {
            DefaultResponse d = (DefaultResponse) res;
            seen.add(res.isCommitted());
            seen.add(d.state());
            res.send("x");
            seen.add(res.isCommitted());
            seen.add(d.state());
        });
        assertEquals(false, seen.get(0));
        assertEquals(ResponseState.NEW, seen.get(1));
        assertEquals(true, seen.get(2));
        assertEquals(ResponseState.COMPLETED, seen.get(3));
        // The in-memory exchange throws on a second start, so a clean 200 proves a single commit.
        assertEquals(200, r.status());
        assertFalse(r.abnormallyClosed());
    }

    // SDS-AC-8; FR-HTTP-015 AC-1
    @Test
    public void sdsAc8_FR_HTTP_015_AC1_abortBeforeCommitWritesNoStatus() {
        ClientResponse r = get((req, res) -> {
            res.status(201).abort(new IllegalStateException("x"));
            assertFalse(res.isCommitted());
        });
        assertEquals(ResponseState.ABORTED, last.get().state());
        assertEquals(0, r.status());
        assertTrue(r.abnormallyClosed());
    }

    // SDS-AC-10; FR-HTTP-016 AC-2, FR-HTTP-016 AC-3, FR-HTTP-018 AC-1
    @Test
    public void sdsAc10_FR_HTTP_016_AC2_AC3_FR_HTTP_018_AC1_everyMutationAfterCompletionThrows() {
        ClientResponse r = get((req, res) -> {
            res.send("a");
            List<Runnable> calls = new ArrayList<>();
            calls.add(() -> res.status(201));
            calls.add(() -> res.status(500));
            calls.add(() -> res.header("X", "1"));
            calls.add(() -> res.send("b"));
            calls.add(() -> res.html("c"));
            calls.add(() -> res.send(new byte[0]));
            calls.add(() -> res.stream(new ByteArrayInputStream(new byte[] {1}), "text/plain"));
            calls.add(res::end);
            for (Runnable c : calls) {
                assertIse(c, "COMPLETED");
            }
        });
        assertEquals(200, r.status());
        assertNull(r.header("X"));
        assertEquals("a", new String(r.body(), StandardCharsets.UTF_8));
        assertEquals(ResponseState.COMPLETED, last.get().state());
    }

    // SDS-AC-10; FR-HTTP-016 AC-2
    @Test
    public void sdsAc10_FR_HTTP_016_AC2_mutationAfterAbortThrowsNamingAborted() {
        get((req, res) -> {
            res.abort(null);
            assertIse(() -> res.status(201), "ABORTED");
            assertIse(() -> res.send("x"), "ABORTED");
        });
        assertEquals(ResponseState.ABORTED, last.get().state());
    }

    // SDS-AC-12; FR-HTTP-017 AC-1
    @Test
    public void sdsAc12_FR_HTTP_017_AC1_status204DropsTheBodyAndContentLength() {
        ClientResponse r = get((req, res) -> res.status(204).send("x"));
        assertEquals(204, r.status());
        assertEquals(0, r.body().length);
        assertNull(r.header("Content-Length"));
        assertFalse(r.abnormallyClosed());
        assertTrue(last.get().bodyless());
    }

    // SDS-AC-12; FR-HTTP-017 AC-2
    @Test
    public void sdsAc12_FR_HTTP_017_AC2_status304DropsTheBytes() {
        ClientResponse r = get((req, res) -> res.status(304).send(new byte[] {1, 2}));
        assertEquals(304, r.status());
        assertEquals(0, r.body().length);
        assertNull(r.header("Content-Length"));
    }

    // SDS-AC-12; FR-HTTP-017 AC-4
    @Test
    public void sdsAc12_FR_HTTP_017_AC4_automatic204AnswersCarryNoBody() {
        t.app().get("/r", (req, res) -> res.send("x"));
        for (ClientResponse r : new ClientResponse[] {t.send("OPTIONS", "/r"), t.send("OPTIONS", "*")}) {
            assertEquals(204, r.status());
            assertEquals(0, r.body().length);
        }
    }

    // SDS-AC-13; FR-HTTP-017 AC-3
    @Test
    public void sdsAc13_FR_HTTP_017_AC3_explicitHeadRouteSendsHeadersWithZeroBodyBytes() {
        List<Method> methods = new ArrayList<>();
        t.app().head("/h", (req, res) -> {
            last.set((DefaultResponse) res);
            methods.add(req.method());
            res.header("X-A", "1").send("body");
        });
        ClientResponse r = t.send("HEAD", "/h");
        assertEquals(200, r.status());
        assertEquals("1", r.header("X-A"));
        assertEquals("4", r.header("Content-Length"));
        assertEquals(0, r.body().length);
        assertFalse(r.abnormallyClosed());
        assertTrue(last.get().bodyless());
        assertEquals(ResponseState.COMPLETED, last.get().state());
        assertEquals(Method.HEAD, methods.get(0));
    }

    // SDS-AC-14; FR-HTTP-018 AC-2
    @Test
    public void sdsAc14_FR_HTTP_018_AC2_readAccessorsWorkAfterCompletion() {
        List<Object> seen = new ArrayList<>();
        t.app().get("/r", (req, res) -> {
            req.attribute("k", "v");
            res.send("done");
            DefaultResponse d = (DefaultResponse) res;
            seen.add(d.state());
            seen.add(req.path());
            seen.add(req.query("q"));
            seen.add(req.header("H"));
            seen.add(req.attribute("k", Object.class));
            seen.add(res.isCommitted());
        });
        ClientResponse r = t.send("GET", "/r?q=1", "H", "h");
        assertEquals(200, r.status());
        List<Object> expected = new ArrayList<>();
        expected.add(ResponseState.COMPLETED);
        expected.add("/r");
        expected.add("1");
        expected.add("h");
        expected.add("v");
        expected.add(true);
        assertEquals(expected, seen);
    }

    // SDS-AC-14; FR-HTTP-018 AC-3, FR-HTTP-015 AC-3
    @Test
    public void sdsAc14_FR_HTTP_018_AC3_abortAfterCompletionChangesNothing() {
        ClientResponse r = get((req, res) -> {
            res.send("x");
            res.abort(new IllegalStateException("late"));
            res.abort(null);
        });
        assertEquals(ResponseState.COMPLETED, last.get().state());
        assertEquals(200, r.status());
        assertFalse(r.abnormallyClosed());
        assertEquals("x", new String(r.body(), StandardCharsets.UTF_8));
    }

    // SDS-AC-10; FR-HTTP-016 AC-1
    @Test
    public void sdsAc10_FR_HTTP_016_AC1_stateEnumListsTheFiveStates() {
        assertEquals("[NEW, COMMITTED, STREAMING, COMPLETED, ABORTED]",
                java.util.Arrays.toString(ResponseState.values()));
    }

    // SDS-AC-8; FR-HTTP-015 AC-1, SDS-AC-9; FR-HTTP-015 AC-3
    @Test
    public void sdsAc8_sdsAc9_FR_HTTP_015_AC1_AC3_abortBeforeCommitAbortsTheExchangeOnceWithoutCommit() {
        ResponseStreamTest.RecordingExchange ex = new ResponseStreamTest.RecordingExchange("GET", "/r");
        DefaultResponse res = new DefaultResponse(ex, false);
        res.status(201).abort(new IllegalStateException("x"));
        res.abort(null);
        assertFalse(res.isCommitted());
        assertEquals(ResponseState.ABORTED, res.state());
        assertEquals(0, ex.commits);
        assertEquals(1, ex.aborts);
    }

    // SDS-AC-10; FR-HTTP-016 AC-1, FR-HTTP-016 AC-2
    @Test
    public void sdsAc10_FR_HTTP_016_AC1_AC2_mutationWhileCommittedThrowsAndCommitIsSingle() {
        ResponseStreamTest.RecordingExchange ex = new ResponseStreamTest.RecordingExchange("GET", "/r");
        DefaultResponse res = new DefaultResponse(ex, false);
        List<Object> during = new ArrayList<>();
        ex.onWrite = () -> {
            during.add(res.state());
            during.add(res.isCommitted());
            try {
                res.status(500);
            } catch (IllegalStateException e) {
                during.add(e.getMessage());
            }
        };
        res.send("abc");
        assertEquals(ResponseState.COMMITTED, during.get(0));
        assertEquals(true, during.get(1));
        assertTrue(String.valueOf(during.get(2)), String.valueOf(during.get(2)).contains("COMMITTED"));
        assertEquals(ResponseState.COMPLETED, res.state());
        assertEquals(1, ex.commits);
        assertEquals(200, ex.status);
        assertEquals(1, ex.finishes);
        assertEquals(0, ex.aborts);
    }
}
