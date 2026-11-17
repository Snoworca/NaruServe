package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * res.append over the in-memory adapter: it adds a header line under the res.header check, keeps earlier same-name
 * lines, returns the response and is rejected on a committed response. Handlers only record outcomes; the test
 * thread asserts, so a failed expectation never hides behind the standard error response.
 */
public class ResponseAppendTest {

    private static final long WAIT_SECONDS = 10;

    /** The values of every {@code name} line the client received, in the order sent. */
    private static List<String> lines(ClientResponse r, String name) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> h : r.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                out.add(h.getValue());
            }
        }
        return out;
    }

    /** Records the simple name of what {@code call} throws, or "ok" when it returns. */
    private static String outcome(Runnable call) {
        try {
            call.run();
            return "ok";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    // FR-HTTP-033 AC-1
    @Test
    public void FR_HTTP_033_AC1_twoAppendedSetCookieLinesReachTheClientInOrder() {
        TestApp t = TestApp.create();
        t.app().get("/c", (req, res) -> {
            res.append("Set-Cookie", "a=1");
            res.append("Set-Cookie", "b=2");
            res.end();
        });
        try {
            ClientResponse r = t.send("GET", "/c");
            assertEquals(200, r.status());
            assertEquals(Arrays.asList("a=1", "b=2"), lines(r, "Set-Cookie"));
        } finally {
            t.app().shutdown(5, TimeUnit.SECONDS);
        }
    }

    // FR-HTTP-033 AC-2
    @Test
    public void FR_HTTP_033_AC2_headerReplacesWhileAppendAfterHeaderKeepsBothLines() {
        TestApp t = TestApp.create();
        t.app().get("/replace", (req, res) -> {
            res.header("X", "1");
            res.header("X", "2");
            res.end();
        });
        t.app().get("/append", (req, res) -> {
            res.header("X", "1");
            res.append("X", "2");
            res.end();
        });
        try {
            ClientResponse replaced = t.send("GET", "/replace");
            assertEquals(200, replaced.status());
            assertEquals(Collections.singletonList("2"), lines(replaced, "X"));
            ClientResponse appended = t.send("GET", "/append");
            assertEquals(200, appended.status());
            assertEquals(Arrays.asList("1", "2"), lines(appended, "X"));
        } finally {
            t.app().shutdown(5, TimeUnit.SECONDS);
        }
    }

    // FR-HTTP-033 AC-3
    @Test
    public void FR_HTTP_033_AC3_appendReturnsTheSameResponseAndChains() {
        TestApp t = TestApp.create();
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        t.app().get("/c", (req, res) -> {
            seen.add(res.append("A", "1") == res);
            seen.add(res.append("A", "2").append("A", "3").status(201) == res);
            res.end();
        });
        try {
            ClientResponse r = t.send("GET", "/c");
            assertEquals(Arrays.<Object>asList(true, true), seen);
            assertEquals(201, r.status());
            assertEquals(Arrays.asList("1", "2", "3"), lines(r, "A"));
        } finally {
            t.app().shutdown(5, TimeUnit.SECONDS);
        }
    }

    // FR-HTTP-033 AC-4
    @Test
    public void FR_HTTP_033_AC4_badValueThrowsLikeHeaderAndAddsNoLine() {
        String[] bad = {"a\rb", "a\nb", "a\u0000b", "a\u007fb", "aĀb"};
        TestApp t = TestApp.create();
        List<String> appendOutcomes = Collections.synchronizedList(new ArrayList<String>());
        List<String> headerOutcomes = Collections.synchronizedList(new ArrayList<String>());
        t.app().get("/c", (req, res) -> {
            for (String value : bad) {
                appendOutcomes.add(outcome(() -> res.append("X", value)));
                headerOutcomes.add(outcome(() -> res.header("X", value)));
            }
            res.end();
        });
        try {
            ClientResponse r = t.send("GET", "/c");
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < bad.length; i++) {
                expected.add("IllegalArgumentException");
            }
            assertEquals(expected, appendOutcomes);
            assertEquals(appendOutcomes, headerOutcomes);
            assertEquals(200, r.status());
            assertEquals(Collections.<String>emptyList(), lines(r, "X"));
        } finally {
            t.app().shutdown(5, TimeUnit.SECONDS);
        }
    }

    // FR-HTTP-033 AC-5
    @Test
    public void FR_HTTP_033_AC5_nonTokenNameThrowsAndAddsNothingWhileHtabAndLatin1ValuesPass() {
        String[] badNames = {"", "X Y", "X:Y"};
        TestApp t = TestApp.create();
        List<String> outcomes = Collections.synchronizedList(new ArrayList<String>());
        t.app().get("/c", (req, res) -> {
            for (String name : badNames) {
                outcomes.add(outcome(() -> res.append(name, "v")));
            }
            outcomes.add(outcome(() -> res.append("X-Tab", "a\tb")));
            outcomes.add(outcome(() -> res.append("X-Latin", "aÿb")));
            res.end();
        });
        try {
            ClientResponse r = t.send("GET", "/c");
            assertEquals(Arrays.asList("IllegalArgumentException", "IllegalArgumentException",
                    "IllegalArgumentException", "ok", "ok"), outcomes);
            assertEquals(200, r.status());
            for (Map.Entry<String, String> h : r.headers()) {
                for (String name : badNames) {
                    assertTrue("no line named [" + name + "]: " + h, !h.getKey().equals(name));
                }
                assertTrue("no line carries the rejected appends' value: " + h, !"v".equals(h.getValue()));
            }
            assertEquals(Collections.singletonList("a\tb"), lines(r, "X-Tab"));
            assertEquals(Collections.singletonList("aÿb"), lines(r, "X-Latin"));
        } finally {
            t.app().shutdown(5, TimeUnit.SECONDS);
        }
    }

    // FR-HTTP-033 AC-6
    @Test
    public void FR_HTTP_033_AC6_committedResponseRejectsAppendLikeHeaderAndAddCookie() throws Exception {
        TestApp t = TestApp.create();
        List<String> outcomes = Collections.synchronizedList(new ArrayList<String>());
        CountDownLatch handlerDone = new CountDownLatch(1);
        t.app().get("/c", (req, res) -> {
            try {
                res.append("X", "1");
                res.end();
                outcomes.add(outcome(() -> res.append("X", "2")));
                outcomes.add(outcome(() -> res.header("X", "3")));
                outcomes.add(outcome(() -> res.addCookie(new Cookie("a", "1"))));
            } finally {
                handlerDone.countDown();
            }
        });
        try {
            ClientResponse r = t.send("GET", "/c");
            assertTrue("handler finished", handlerDone.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("IllegalStateException", "IllegalStateException", "IllegalStateException"),
                    outcomes);
            assertEquals(200, r.status());
            assertEquals(Collections.singletonList("1"), lines(r, "X"));
            assertEquals(Collections.<String>emptyList(), lines(r, "Set-Cookie"));
        } finally {
            t.app().shutdown(5, TimeUnit.SECONDS);
        }
    }
}
