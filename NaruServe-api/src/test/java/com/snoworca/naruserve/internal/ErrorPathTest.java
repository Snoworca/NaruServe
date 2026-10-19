package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.testing.RecordingExchange;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.ErrorHandler;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.UnsupportedMediaTypeException;
import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The one error path and its onError chain. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md. */
public class ErrorPathTest {

    /** What one onError call saw. */
    private static final class Seen {
        final Throwable error;
        final Request req;
        final Response res;

        Seen(Throwable error, Request req, Response res) {
            this.error = error;
            this.req = req;
            this.res = res;
        }
    }

    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final RecordingLogger log = new RecordingLogger();
    private final ErrorHandler recording = (error, req, res) -> seen.add(new Seen(error, req, res));

    private TestApp app() {
        TestApp t = TestApp.create();
        t.app().logger(log);
        return t;
    }

    private static RouteHandler throwing(Exception e) {
        return (req, res) -> {
            throw e;
        };
    }

    // SDS-AC-1; FR-APP-005 AC-1, T36
    @Test
    public void sdsAc1_FR_APP_005_AC1_eachFailureReachesOnErrorOnceWithTheSameInstance() {
        RuntimeException fromMw = new IllegalStateException("mw");
        IOException fromHandler = new IOException("handler");
        IllegalArgumentException viaNext = new IllegalArgumentException("next");
        List<Request> reqs = new ArrayList<>();
        List<Response> ress = new ArrayList<>();
        Middleware capture = (req, res, next) -> {
            reqs.add(req);
            ress.add(res);
            next.run();
        };
        TestApp t = app();
        t.app().use(capture);
        t.app().onError(recording);
        t.app().get("/mw", (req, res, next) -> {
            throw fromMw;
        }, (req, res) -> res.send("never"));
        t.app().get("/h", throwing(fromHandler));
        t.app().get("/next", (req, res, next) -> next.error(viaNext), (req, res) -> res.send("never"));
        Throwable[] expected = {fromMw, fromHandler, viaNext};
        String[] paths = {"/mw", "/h", "/next"};
        for (int i = 0; i < paths.length; i++) {
            seen.clear();
            assertEquals(paths[i], 500, t.send("GET", paths[i]).status());
            assertEquals(paths[i], 1, seen.size());
            assertSame(paths[i], expected[i], seen.get(0).error);
            assertSame(reqs.get(i), seen.get(0).req);
            assertSame(ress.get(i), seen.get(0).res);
        }
    }

    // SDS-AC-1; FR-APP-005 AC-1
    @Test
    public void sdsAc1_FR_APP_005_AC1_aThrownErrorIsNotRoutedButAbortsAndPropagates() {
        Router<RouteHandler> router = new Router<>(RouterPolicy.strict());
        StackOverflowError thrown = new StackOverflowError("handler");
        router.add(Method.GET, "/e", (req, res) -> {
            throw thrown;
        });
        router.freeze();
        Dispatcher d = new Dispatcher(router, new MiddlewarePipeline(RouterPolicy.strict()),
                new ErrorPath(Collections.singletonList(recording), new SafeLogger(log)), new AccessLog());
        RecordingExchange ex = new RecordingExchange("GET", "/e");
        try {
            d.handle(ex);
            fail("expected the Error to propagate");
        } catch (StackOverflowError e) {
            assertSame(thrown, e);
        }
        assertTrue(seen.isEmpty());
        assertEquals(1, ex.aborts.get());
        assertEquals(0, ex.commits.get());
    }

    // SDS-AC-2; FR-APP-005 AC-3
    @Test
    public void sdsAc2_FR_APP_005_AC3_theSecondOnErrorRunsOnlyWhileTheResponseIsNew() {
        IllegalStateException boom = new IllegalStateException("boom");
        TestApp t = app();
        t.app().onError((error, req, res) -> {
            calls.add("first " + req.path());
            if (req.path().equals("/answer")) {
                res.status(418).send("first");
            }
        });
        t.app().onError((error, req, res) -> {
            calls.add("second " + req.path());
            assertSame(boom, error);
        });
        t.app().get("/answer", throwing(boom));
        t.app().get("/pass", throwing(boom));
        ClientResponse answered = t.send("GET", "/answer");
        assertEquals(418, answered.status());
        assertEquals("first", answered.text());
        assertEquals(Collections.singletonList("first /answer"), calls);
        calls.clear();
        assertEquals(500, t.send("GET", "/pass").status());
        assertEquals(Arrays.asList("first /pass", "second /pass"), calls);
    }

    // SDS-AC-3; FR-APP-005 AC-4, FR-APP-006 AC-1
    @Test
    public void sdsAc3_FR_APP_005_AC4_noOnErrorOrOnErrorLeavingNewGivesTheStandardBodyWithoutAppHeaders() {
        TestApp none = app();
        none.app().get("/f", throwing(new IllegalStateException("x")));
        DispatcherTest.assertError(500, "INTERNAL_ERROR", "Internal Server Error", none.send("GET", "/f"));

        TestApp t = app();
        t.app().onError((error, req, res) -> res.status(201).header("X-A", "1"));
        t.app().get("/f", throwing(new HttpException(404)));
        ClientResponse r = t.send("GET", "/f");
        DispatcherTest.assertError(404, "NOT_FOUND", "Not Found", r);
        assertNull(r.header("X-A"));
        assertEquals(2, r.headers().size());
    }

    // SDS-AC-4; FR-APP-005 AC-5
    @Test
    public void sdsAc4_FR_APP_005_AC5_aThrowingOnErrorIsLoggedStopsTheChainAndGives500() {
        IllegalStateException broken = new IllegalStateException("onError broke");
        TestApp t = app();
        t.app().onError((error, req, res) -> {
            calls.add("first");
            throw broken;
        });
        t.app().onError((error, req, res) -> calls.add("second"));
        t.app().get("/f", throwing(new HttpException(400)));
        ClientResponse r = t.send("GET", "/f");
        DispatcherTest.assertError(500, "INTERNAL_ERROR", "Internal Server Error", r);
        assertEquals(Collections.singletonList("first"), calls);
        assertEquals(1, log.count("ERROR"));
        assertEquals(Collections.<Throwable>singletonList(broken), log.errors);
    }

    // SDS-AC-5; FR-APP-005 AC-6, T37
    @Test
    public void sdsAc5_FR_APP_005_AC6_streamingIsAbortedAfterOnErrorAndCompletedStaysCompleted() {
        TestApp t = app();
        t.app().onError(recording);
        t.app().get("/s", (req, res) -> {
            res.stream(new ByteArrayInputStream(new byte[100]), "text/plain");
            throw new IOException("after stream");
        });
        t.app().get("/c", (req, res) -> {
            res.send("x");
            throw new IllegalStateException("after send");
        });
        ClientResponse s = t.send("GET", "/s");
        assertTrue(s.abnormallyClosed());
        assertEquals(1, seen.size());
        assertEquals(ResponseState.ABORTED, ((DefaultResponse) seen.get(0).res).state());
        ClientResponse c = t.send("GET", "/c");
        assertFalse(c.abnormallyClosed());
        assertEquals(200, c.status());
        assertEquals("x", c.text());
        assertEquals(2, seen.size());
        assertEquals(ResponseState.COMPLETED, ((DefaultResponse) seen.get(1).res).state());
    }

    // SDS-AC-5, SDS-AC-7; FR-APP-005 AC-9
    @Test
    public void sdsAc7_FR_APP_005_AC9_aStreamStartedByOnErrorOnANewResponseIsCopiedNotAborted() {
        TestApp t = app();
        t.app().onError((error, req, res) ->
                res.status(503).stream(new ByteArrayInputStream("down".getBytes(StandardCharsets.UTF_8)),
                        "text/plain"));
        t.app().get("/f", throwing(new IllegalStateException("x")));
        ClientResponse r = t.send("GET", "/f");
        assertEquals(503, r.status());
        assertEquals("down", r.text());
        assertFalse(r.abnormallyClosed());
    }

    // SDS-AC-5, SDS-AC-7; FR-APP-005 AC-9
    @Test
    public void sdsAc5_FR_APP_005_AC9_aResponseCommittedButNotCompletedIsAborted() throws Exception {
        RecordingExchange ex = new RecordingExchange("GET", "/f");
        java.util.concurrent.CountDownLatch inCommit = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ex.onCommit = () -> {
            inCommit.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        DefaultResponse res = new DefaultResponse(ex, false);
        DefaultRequest req = new DefaultRequest(ex, Method.GET, Collections.<String, String>emptyMap());
        Thread writer = new Thread(() -> res.send("x"));
        writer.start();
        assertTrue(inCommit.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(ResponseState.COMMITTED, res.state());
        new ErrorPath(Collections.<ErrorHandler>emptyList(), new SafeLogger(log))
                .handle(new IllegalStateException("x"), req, res, "0000000000000000");
        release.countDown();
        writer.join(5000);
        assertEquals(ResponseState.ABORTED, res.state());
        assertEquals(1, ex.commits.get());
        assertEquals(1, ex.aborts.get());
    }

    // SDS-AC-1; FR-APP-005 AC-1
    @Test
    public void sdsAc1_FR_APP_005_AC1_anErrorFromTheLoggerAbortsTheResponseAndPropagates() {
        AssertionError fromLogger = new AssertionError("logger");
        Logger throwingLogger = new RecordingLogger() {
            @Override
            public void error(String message, Throwable error) {
                throw fromLogger;
            }
        };
        RecordingExchange ex = new RecordingExchange("GET", "/f");
        DefaultResponse res = new DefaultResponse(ex, false);
        DefaultRequest req = new DefaultRequest(ex, Method.GET, Collections.<String, String>emptyMap());
        try {
            new ErrorPath(Collections.<ErrorHandler>emptyList(), new SafeLogger(throwingLogger))
                    .handle(new IllegalStateException("x"), req, res, "0000000000000000");
            fail("expected the Error to propagate");
        } catch (AssertionError e) {
            assertSame(fromLogger, e);
        }
        assertEquals(ResponseState.ABORTED, res.state());
        assertEquals(1, ex.aborts.get());
        assertEquals(0, ex.commits.get());
    }

    // SDS-AC-6; FR-APP-005 AC-8
    @Test
    public void sdsAc6_FR_APP_005_AC8_only500IsLoggedAtErrorWithItsThrowableWhateverTheState() {
        IllegalStateException boom = new IllegalStateException("boom");
        IllegalStateException late = new IllegalStateException("late");
        TestApp t = app();
        t.app().get("/500", throwing(boom));
        t.app().get("/400", throwing(new HttpException(400, "bad")));
        t.app().get("/late", (req, res) -> {
            res.send("x");
            throw late;
        });
        t.send("GET", "/400");
        assertEquals(0, log.count("ERROR"));
        t.send("GET", "/500");
        assertEquals(1, log.count("ERROR"));
        assertEquals(Collections.<Throwable>singletonList(boom), log.errors);
        t.send("GET", "/late");
        assertEquals(2, log.count("ERROR"));
        assertSame(late, log.errors.get(1));
    }

    // SDS-AC-7; FR-APP-005 AC-9
    @Test
    public void sdsAc7_FR_APP_005_AC9_anOnErrorThatSendsDecidesTheOutcomeWithNoSecondWrite() {
        Router<RouteHandler> router = new Router<>(RouterPolicy.strict());
        router.add(Method.GET, "/f", throwing(new IllegalStateException("x")));
        router.freeze();
        ErrorHandler answering = (error, req, res) -> res.status(418).send("tea");
        Dispatcher d = new Dispatcher(router, new MiddlewarePipeline(RouterPolicy.strict()),
                new ErrorPath(Collections.singletonList(answering), new SafeLogger(log)), new AccessLog());
        RecordingExchange ex = new RecordingExchange("GET", "/f");
        d.handle(ex);
        assertEquals(418, ex.status);
        assertEquals(1, ex.commits.get());
        assertEquals(1, ex.finishes.get());
        assertEquals(0, ex.aborts.get());
        assertEquals("tea", new String(ex.body.toByteArray(), StandardCharsets.UTF_8));
    }

    // SDS-AC-8; FR-APP-006 AC-1
    @Test
    public void sdsAc8_FR_APP_006_AC1_a500BodyHidesTheMessageClassAndStack() {
        TestApp t = app();
        t.app().get("/f", throwing(new RuntimeException("secret com.example.Foo")));
        ClientResponse r = t.send("GET", "/f");
        DispatcherTest.assertError(500, "INTERNAL_ERROR", "Internal Server Error", r);
        String body = r.text();
        assertFalse(body.contains("secret"));
        assertFalse(body.contains("RuntimeException"));
        assertFalse(body.contains("at "));
    }

    // SDS-AC-10; FR-APP-006 AC-3, FR-APP-006 AC-4, FR-APP-005 AC-2
    @Test
    public void sdsAc10_FR_APP_006_AC3_AC4_FR_APP_005_AC2_httpExceptionsKeepTheirStatusAndAppMessage() {
        List<Integer> statuses = new ArrayList<>();
        TestApp t = app();
        t.app().onError((error, req, res) -> statuses.add(((HttpException) error).status()));
        t.app().get("/400", throwing(new HttpException(400)));
        t.app().get("/422", throwing(new HttpException(422, "name required")));
        t.app().get("/413", throwing(new HttpException(413)));
        t.app().get("/415", throwing(new UnsupportedMediaTypeException()));
        t.app().get("/500", throwing(new HttpException(500, "leak")));
        DispatcherTest.assertError(400, "BAD_REQUEST", "Bad Request", t.send("GET", "/400"));
        DispatcherTest.assertError(422, "UNPROCESSABLE_ENTITY", "name required", t.send("GET", "/422"));
        DispatcherTest.assertError(413, "PAYLOAD_TOO_LARGE", "Payload Too Large", t.send("GET", "/413"));
        DispatcherTest.assertError(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type", t.send("GET", "/415"));
        DispatcherTest.assertError(500, "INTERNAL_ERROR", "Internal Server Error", t.send("GET", "/500"));
        assertEquals(Arrays.asList(400, 422, 413, 415, 500), statuses);
    }

    // SDS-AC-11; FR-APP-006 AC-6
    @Test
    public void sdsAc11_FR_APP_006_AC6_headKeepsTheHeadersAndContentLengthWithNoBody() {
        TestApp t = app();
        t.app().get("/f", throwing(new IllegalStateException("x")));
        ClientResponse get = t.send("GET", "/f");
        ClientResponse head = t.send("HEAD", "/f");
        assertEquals(500, head.status());
        assertEquals("application/json; charset=UTF-8", head.header("Content-Type"));
        assertEquals(String.valueOf(get.body().length), head.header("Content-Length"));
        assertEquals(0, head.body().length);
        assertEquals(0L, head.contentLength());
        assertFalse(head.abnormallyClosed());
    }

    // SDS-AC-12; FR-APP-006 AC-7
    @Test
    public void sdsAc12_FR_APP_006_AC7_theBodyCarriesTheRequestIdHandedToTheErrorPath() {
        RecordingExchange ex = new RecordingExchange("GET", "/f");
        DefaultRequest req = new DefaultRequest(ex, Method.GET, Collections.<String, String>emptyMap());
        DefaultResponse res = new DefaultResponse(ex, false);
        new ErrorPath(Collections.<ErrorHandler>emptyList(), new SafeLogger(log))
                .handle(new HttpException(404), req, res, "00000000deadbeef");
        assertEquals("{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"Not Found\",\"requestId\":\"00000000deadbeef\"}}",
                new String(ex.body.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(ResponseState.COMPLETED, res.state());
    }

    // SDS-AC-16; FR-HTTP-025 AC-3, FR-APP-005 AC-2
    @Test
    public void sdsAc16_FR_HTTP_025_AC3_FR_APP_005_AC2_malformedQueryReachesOnErrorAfterTheMiddleware() {
        TestApp t = app();
        t.app().use((req, res, next) -> {
            calls.add("g");
            next.run();
        });
        t.app().onError((error, req, res) -> calls.add("onError " + ((HttpException) error).status()));
        t.app().get("/x", (req, res, next) -> {
            calls.add("r");
            next.run();
        }, (req, res) -> calls.add("h"));
        for (String target : new String[] {"/x?x=%zz", "/x?x=%E0%A4", "/x?x=%C3%28"}) {
            calls.clear();
            DispatcherTest.assertError(400, "BAD_REQUEST", "Bad Request", t.send("GET", target));
            assertEquals(target, Arrays.asList("g", "r", "onError 400"), calls);
        }
    }

    // FR-APP-006 AC-5, FR-APP-006 AC-9, FR-APP-005 AC-7
    @Test
    public void FR_APP_006_AC5_AC9_automaticAnswersCarryTheStandardBodyAndAreNotReplaceable() {
        TestApp t = app().webSocketRoute("/ws");
        t.app().onError(recording);
        t.app().get("/r", (req, res) -> res.send("ok"));
        DispatcherTest.assertError(404, "NOT_FOUND", "Not Found", t.send("GET", "/missing"));
        ClientResponse m = t.send("DELETE", "/r");
        DispatcherTest.assertError(405, "METHOD_NOT_ALLOWED", "Method Not Allowed", m);
        assertEquals("GET, HEAD, OPTIONS", m.header("Allow"));
        ClientResponse ws = t.send("GET", "/ws");
        DispatcherTest.assertError(426, "UPGRADE_REQUIRED", "Upgrade Required", ws);
        assertEquals("websocket", ws.header("Upgrade"));
        assertEquals("Upgrade", ws.header("Connection"));
        DispatcherTest.assertError(501, "NOT_IMPLEMENTED", "Not Implemented", t.send("BREW", "/r"));
        DispatcherTest.assertError(400, "BAD_REQUEST", "Bad Request", t.send("GET", "abc"));
        ClientResponse options = t.send("OPTIONS", "/r");
        assertEquals(204, options.status());
        assertEquals(0, options.body().length);
        assertTrue(seen.isEmpty());
        for (java.lang.reflect.Method rm : com.snoworca.naruserve.RestApp.class.getMethods()) {
            assertFalse(rm.getName(), rm.getName().toLowerCase(java.util.Locale.ROOT)
                    .matches(".*(notfound|methodnotallowed|errorbody|fallback).*"));
        }
    }

    // SDS-AC-16; FR-HTTP-025 AC-3
    @Test
    public void sdsAc16_FR_HTTP_025_AC3_aMiddlewareReadingTheQueryGetsTheHttpExceptionFirst() {
        List<Object> got = new ArrayList<>();
        TestApp t = app();
        t.app().use((req, res, next) -> {
            try {
                req.query("x");
            } catch (HttpException e) {
                got.add(e.status());
                throw e;
            }
            next.run();
        });
        t.app().onError((error, req, res) -> got.add(error));
        t.app().get("/x", (req, res) -> got.add("h"));
        DispatcherTest.assertError(400, "BAD_REQUEST", "Bad Request", t.send("GET", "/x?x=%zz"));
        assertEquals(2, got.size());
        assertEquals(400, got.get(0));
        assertTrue(got.get(1) instanceof HttpException);
    }

    // SDS-AC-12 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-4.sds.md; FR-ENGINE-005 AC-2
    @Test
    public void sdsAc12_FR_ENGINE_005_AC2_every408CarriesConnectionCloseAndRequestTimeout() {
        TestApp t = app();
        t.app().get("/slow", throwing(new HttpException(408)));
        t.app().get("/slow-set", (req, res) -> {
            res.header("Connection", "keep-alive");
            throw new HttpException(408);
        });
        t.app().get("/bad", throwing(new HttpException(400)));
        for (String path : new String[] {"/slow", "/slow-set"}) {
            ClientResponse r = t.send("GET", path);
            DispatcherTest.assertError(408, "HTTP_408", "Request Timeout", r);
            assertEquals("close", r.header("Connection"));
        }
        assertNull(t.send("GET", "/bad").header("Connection"));
    }
}
