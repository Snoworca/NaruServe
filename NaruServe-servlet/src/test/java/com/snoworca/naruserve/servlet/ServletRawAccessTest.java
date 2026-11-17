package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import org.junit.Rule;
import org.junit.Test;

/**
 * The raw access API over the servlet adapter on embedded Jetty 9.4: header grouping per name, the raw body, relay
 * framing and onAbort.
 */
public class ServletRawAccessTest {

    /** The deadline of {@link #drain}; a drained shutdown returns well before it. */
    private static final long STOP_SECONDS = 5;

    @Rule
    public final ContainerRule container = new ContainerRule();

    /** What a handler saw of the request headers. */
    private static final class Seen {
        volatile List<String> names = Collections.emptyList();
        volatile List<Map.Entry<String, String>> raw = Collections.emptyList();
        volatile boolean namesFrozen;
        volatile boolean rawFrozen;
    }

    private static void captureHeaders(RestApp app, Seen seen) {
        app.get("/h", (req, res) -> {
            Set<String> names = req.headerNames();
            List<Map.Entry<String, String>> raw = req.rawHeaders();
            seen.names = new ArrayList<>(names);
            seen.raw = new ArrayList<>(raw);
            List<Runnable> nameEdits = new ArrayList<>();
            nameEdits.add(() -> names.add("x-c"));
            nameEdits.add(() -> names.remove(names.iterator().next()));
            nameEdits.add(names::clear);
            nameEdits.add(() -> {
                Iterator<String> it = names.iterator();
                it.next();
                it.remove();
            });
            List<Runnable> rawEdits = new ArrayList<>();
            rawEdits.add(() -> raw.add(new SimpleImmutableEntry<>("x-c", "4")));
            rawEdits.add(() -> raw.set(0, raw.get(0)));
            rawEdits.add(() -> raw.remove(0));
            rawEdits.add(raw::clear);
            seen.namesFrozen = allRefused(nameEdits);
            seen.rawFrozen = allRefused(rawEdits);
            res.send("ok");
        });
    }

    /** True when every edit throws UnsupportedOperationException. */
    private static boolean allRefused(List<Runnable> edits) {
        for (Runnable edit : edits) {
            try {
                edit.run();
                return false;
            } catch (UnsupportedOperationException expected) {
                // refused, as required
            }
        }
        return true;
    }

    private static List<String> valuesOf(List<Map.Entry<String, String>> raw, String name) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : raw) {
            if (e.getKey().equalsIgnoreCase(name)) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** The names of {@code raw} in order, with each run of one name (case-insensitively) collapsed to its first entry. */
    private static List<String> groupOrder(List<Map.Entry<String, String>> raw) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : raw) {
            if (out.isEmpty() || !out.get(out.size() - 1).equalsIgnoreCase(e.getKey())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** {@code names} with later case-insensitive repeats dropped, keeping the first spelling. */
    private static List<String> dedupe(List<String> names) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String n : names) {
            if (seen.add(n.toLowerCase(Locale.ROOT))) {
                out.add(n);
            }
        }
        return out;
    }

    // FR-SVL-010 AC-1: exactly x-a and x-b in any order, values 1, 2, 3, x-a values 1 then 2, both unmodifiable.
    @Test
    public void FR_SVL_010_AC1_headerNamesAndRawHeadersGroupPerNameAndAreUnmodifiable() throws Exception {
        Seen seen = new Seen();
        SharedApp.onJetty(container, "/*", app -> captureHeaders(app, seen));
        // HTTP/1.0 without Host or Connection, so the request carries only the three header lines under test.
        SharedApp.Reply r = SharedApp.raw(container.port(),
                "GET /h HTTP/1.0\r\nX-A: 1\r\nx-a: 2\r\nX-B: 3\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(r.text(), 200, r.status);

        List<String> lower = new ArrayList<>();
        for (String n : seen.names) {
            lower.add(n.toLowerCase(Locale.ROOT));
        }
        assertEquals("headerNames " + seen.names, 2, seen.names.size());
        assertEquals("headerNames " + seen.names, new HashSet<>(Arrays.asList("x-a", "x-b")),
                new HashSet<>(lower));

        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> e : seen.raw) {
            values.add(e.getValue());
        }
        Collections.sort(values);
        assertEquals("rawHeaders " + seen.raw, Arrays.asList("1", "2", "3"), values);
        assertEquals("rawHeaders " + seen.raw, Arrays.asList("1", "2"), valuesOf(seen.raw, "x-a"));
        assertEquals("rawHeaders " + seen.raw, Arrays.asList("3"), valuesOf(seen.raw, "x-b"));
        assertTrue("headerNames() accepted a modification", seen.namesFrozen);
        assertTrue("rawHeaders() accepted a modification", seen.rawFrozen);
    }

    // FR-SVL-010 AC-2: one name's entries are contiguous and the names follow Jetty's own getHeaderNames order.
    @Test
    public void FR_SVL_010_AC2_rawHeadersFollowTheContainerNameEnumerationOrder() throws Exception {
        Seen seen = new Seen();
        AtomicReference<List<String>> containerOrder = new AtomicReference<>();
        container.filter((req, res, chain) -> {
            containerOrder.set(Collections.list(((HttpServletRequest) req).getHeaderNames()));
            chain.doFilter(req, res);
        });
        SharedApp.onJetty(container, "/*", app -> captureHeaders(app, seen));
        // x-a is split by X-B on the wire, so wire order and per-name grouping differ.
        SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/h", null, "X-A", "1", "X-B", "3", "x-a", "2",
                "X-C", "4");
        assertEquals(r.text(), 200, r.status);

        List<String> expected = dedupe(containerOrder.get());
        assertEquals("rawHeaders " + seen.raw + " vs container " + containerOrder.get(), expected,
                groupOrder(seen.raw));
        // Each name appears as one contiguous run: no name after collapsing runs repeats.
        assertEquals("non-contiguous name in " + seen.raw, dedupe(groupOrder(seen.raw)), groupOrder(seen.raw));
        assertEquals(Arrays.asList("1", "2"), valuesOf(seen.raw, "x-a"));
        List<String> lowerExpected = new ArrayList<>();
        for (String n : expected) {
            lowerExpected.add(n.toLowerCase(Locale.ROOT));
        }
        assertEquals("headerNames follows the same order", lowerExpected, seen.names);
    }

    // FR-SVL-010 AC-2: with a container that enumerates X-B, x-a, X-A (not wire order), rawHeaders groups
    // per name in that order, under the first spelling, so the X-A spelling of value 1 is not preserved.
    @Test
    public void FR_SVL_010_AC2_rawHeadersGroupInAGivenEnumerationOrderAndDropCaseDifferences() throws Exception {
        Seen seen = new Seen();
        container.filter((req, res, chain) -> chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest) req) {
            @Override
            public Enumeration<String> getHeaderNames() {
                List<String> fixed = new ArrayList<>(Arrays.asList("X-B", "x-a", "X-A"));
                for (String n : Collections.list(super.getHeaderNames())) {
                    if (!n.equalsIgnoreCase("x-a") && !n.equalsIgnoreCase("x-b")) {
                        fixed.add(n);
                    }
                }
                return Collections.enumeration(fixed);
            }
        }, res));
        SharedApp.onJetty(container, "/*", app -> captureHeaders(app, seen));
        SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/h", null, "X-A", "1", "x-a", "2", "X-B", "3");
        assertEquals(r.text(), 200, r.status);

        List<Map.Entry<String, String>> xs = new ArrayList<>();
        for (Map.Entry<String, String> e : seen.raw) {
            if (e.getKey().toLowerCase(Locale.ROOT).startsWith("x-")) {
                xs.add(e);
            }
        }
        List<Map.Entry<String, String>> expected = new ArrayList<>();
        expected.add(new SimpleImmutableEntry<>("X-B", "3"));
        expected.add(new SimpleImmutableEntry<>("x-a", "1"));
        expected.add(new SimpleImmutableEntry<>("x-a", "2"));
        assertEquals(expected, xs);
        assertEquals(Arrays.asList("x-b", "x-a"), seen.names.subList(0, 2));
    }

    // FR-SVL-010 AC-3: bodyStream() returns exactly the bytes of a JSON body with spaces and line breaks.
    @Test
    public void FR_SVL_010_AC3_bodyStreamReturnsTheExactJsonBytes() throws Exception {
        AtomicReference<byte[]> got = new AtomicReference<>();
        SharedApp.onJetty(container, "/*", app -> app.post("/b", (req, res) -> {
            got.set(readAll(req.bodyStream()));
            res.send("ok");
        }));
        byte[] json = "{ \"a\" :  1,\r\n  \"b\":\n\t[ 2 , 3 ]  }\n".getBytes(StandardCharsets.UTF_8);
        SharedApp.Reply r = SharedApp.http(container.port(), "POST", "/b", json,
                "Content-Type", "application/json");
        assertEquals(r.text(), 200, r.status);
        assertArrayEquals(json, got.get());
    }

    // FR-SVL-010 AC-3: bodyStream() returns a Content-Encoding: gzip body still compressed.
    @Test
    public void FR_SVL_010_AC3_bodyStreamReturnsAGzipBodyStillCompressed() throws Exception {
        AtomicReference<byte[]> got = new AtomicReference<>();
        SharedApp.onJetty(container, "/*", app -> app.post("/b", (req, res) -> {
            got.set(readAll(req.bodyStream()));
            res.send("ok");
        }));
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(z)) {
            gz.write("{\"a\": 1,\n \"b\": [2, 3]}".getBytes(StandardCharsets.UTF_8));
        }
        byte[] gzip = z.toByteArray();
        SharedApp.Reply r = SharedApp.http(container.port(), "POST", "/b", gzip,
                "Content-Type", "application/json", "Content-Encoding", "gzip");
        assertEquals(r.text(), 200, r.status);
        assertArrayEquals(gzip, got.get());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void relayRoutes(RestApp app) {
        app.get("/relay", (req, res) -> res.relay(new ByteArrayInputStream("hello".getBytes(StandardCharsets.US_ASCII)), 5));
        app.get("/relay304", (req, res) -> res.status(304)
                .relay(new ByteArrayInputStream("hello".getBytes(StandardCharsets.US_ASCII)), 5));
        app.get("/relay204", (req, res) -> res.status(204)
                .relay(new ByteArrayInputStream("hello".getBytes(StandardCharsets.US_ASCII)), 5));
    }

    // FR-SVL-010 AC-4: a relay without Content-Type gives no Content-Type, Content-Length: 5 and the 5 bytes.
    @Test
    public void FR_SVL_010_AC4_relayWithoutContentTypeSendsLengthAndBytesOnly() throws Exception {
        SharedApp.onJetty(container, "/*", ServletRawAccessTest::relayRoutes);
        SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/relay", null);
        assertEquals(200, r.status);
        assertNull("Content-Type " + r.headers, r.header("Content-Type"));
        assertEquals("5", r.header("Content-Length"));
        assertNull(r.header("Transfer-Encoding"));
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), r.body);
    }

    // FR-SVL-010 AC-4: relay answers HEAD with Content-Length: 5 and no body.
    @Test
    public void FR_SVL_010_AC4_relayAnswersHeadWithLengthAndNoBody() throws Exception {
        SharedApp.onJetty(container, "/*", ServletRawAccessTest::relayRoutes);
        SharedApp.Reply r = SharedApp.http(container.port(), "HEAD", "/relay", null);
        assertEquals(200, r.status);
        assertEquals("5", r.header("Content-Length"));
        assertEquals(0, r.body.length);
    }

    // FR-SVL-010 AC-4: relay answers a 304 with Content-Length: 5 and no body.
    @Test
    public void FR_SVL_010_AC4_relayAnswers304WithLengthAndNoBody() throws Exception {
        SharedApp.onJetty(container, "/*", ServletRawAccessTest::relayRoutes);
        SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/relay304", null);
        assertEquals(304, r.status);
        assertEquals("headers " + r.headers, "5", r.header("Content-Length"));
        assertEquals(0, r.body.length);
    }

    // FR-SVL-010 AC-4: relay answers a 204 with neither Content-Length nor a body.
    @Test
    public void FR_SVL_010_AC4_relayAnswers204WithoutLengthOrBody() throws Exception {
        SharedApp.onJetty(container, "/*", ServletRawAccessTest::relayRoutes);
        SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/relay204", null);
        assertEquals(204, r.status);
        assertNull("headers " + r.headers, r.header("Content-Length"));
        assertNull(r.header("Transfer-Encoding"));
        assertEquals(0, r.body.length);
    }

    /**
     * Counts down once the container's call into the app servlet for the next request has returned or thrown,
     * after the exchange's afterHandler; install it before {@link SharedApp#onJetty}.
     */
    private CountDownLatch servedOnce() {
        CountDownLatch served = new CountDownLatch(1);
        container.filter((req, res, chain) -> {
            try {
                chain.doFilter(req, res);
            } finally {
                served.countDown();
            }
        });
        return served;
    }

    /**
     * Shuts {@code app} down and asserts it drained before the deadline. The servlet adapter's stop waits until no
     * admitted request is in flight, and an exchange leaves the count only when it retires, after its completion and
     * cleanup ran; so any listener run that completion or cleanup would cause has happened when this returns.
     */
    private static void drain(RestApp app) {
        long start = System.nanoTime();
        app.shutdown(STOP_SECONDS, TimeUnit.SECONDS);
        long took = System.nanoTime() - start;
        assertTrue("shutdown reached its deadline, so an exchange never retired (" + took / 1000000 + " ms)",
                took < TimeUnit.SECONDS.toNanos(STOP_SECONDS));
    }

    // FR-SVL-010 AC-5: an onAbort listener runs exactly once when the handler calls res.abort(...).
    @Test
    public void FR_SVL_010_AC5_onAbortRunsOnceOnAbort() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        CountDownLatch served = servedOnce();
        RestApp restApp = SharedApp.onJetty(container, "/*", app -> app.get("/abort", (req, res) -> {
            res.onAbort(runs::incrementAndGet);
            res.abort(new IllegalStateException("upstream gone"));
            res.abort(new IllegalStateException("second abort"));
            handled.incrementAndGet();
        }));
        try {
            SharedApp.http(container.port(), "GET", "/abort", null);
        } catch (IOException connectionCut) {
            // an aborted response may reach the client as a cut connection
        }
        assertTrue("handler did not run", SharedApp.until(() -> handled.get() == 1, 5, TimeUnit.SECONDS));
        assertTrue("onAbort never ran", SharedApp.until(() -> runs.get() >= 1, 5, TimeUnit.SECONDS));
        // Count only once the servlet call ended and the exchange retired, so a duplicate run from the container's
        // completion or the adapter's cleanup of this exchange cannot fall after the check.
        assertTrue("servlet call did not end", served.await(STOP_SECONDS, TimeUnit.SECONDS));
        drain(restApp);
        assertEquals("onAbort runs", 1, runs.get());
    }

    // FR-SVL-010 AC-5: an onAbort listener never runs when the response completes normally.
    @Test
    public void FR_SVL_010_AC5_onAbortNeverRunsOnNormalCompletion() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        CountDownLatch served = servedOnce();
        RestApp restApp = SharedApp.onJetty(container, "/*", app -> app.get("/ok", (req, res) -> {
            res.onAbort(runs::incrementAndGet);
            res.send("ok");
            handled.incrementAndGet();
        }));
        SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/ok", null);
        assertEquals(200, r.status);
        assertEquals("ok", r.text());
        assertEquals("2", r.header("Content-Length"));
        assertTrue("handler did not return", SharedApp.until(() -> handled.get() == 1, 5, TimeUnit.SECONDS));
        // A listener that would run late (on container completion or cleanup) has run once the servlet call ended
        // and the exchange retired, and so does one the app's shutdown would wrongly run for a finished exchange.
        assertTrue("servlet call did not end", served.await(STOP_SECONDS, TimeUnit.SECONDS));
        drain(restApp);
        assertEquals("onAbort runs", 0, runs.get());
    }
}
