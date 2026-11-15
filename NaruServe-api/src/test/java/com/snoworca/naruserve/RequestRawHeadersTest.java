package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultRequest;
import com.snoworca.naruserve.testing.RecordingExchange;
import com.snoworca.naruserve.testing.TestApp;
import java.util.AbstractMap.SimpleEntry;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** req.headerNames() and req.rawHeaders() over the in-memory adapter (FR-HTTP-032). */
public class RequestRawHeadersTest {

    /** What a handler saw: two calls of each enumeration and the per-name header lists. */
    private static final class Seen {
        Set<String> names;
        Set<String> namesAgain;
        List<Map.Entry<String, String>> raw;
        List<Map.Entry<String, String>> rawAgain;
        final List<List<String>> headersPerName = new ArrayList<>();
    }

    /** Sends GET /h with {@code headerPairs} and returns what the handler saw; the response must be 200. */
    private static Seen capture(String... headerPairs) {
        TestApp t = TestApp.create();
        AtomicReference<Seen> ref = new AtomicReference<>();
        t.app().get("/h", (req, res) -> {
            Seen s = new Seen();
            s.names = req.headerNames();
            s.raw = req.rawHeaders();
            s.namesAgain = req.headerNames();
            s.rawAgain = req.rawHeaders();
            for (String name : s.names) {
                s.headersPerName.add(req.headers(name));
            }
            ref.set(s);
            res.send("ok");
        });
        assertEquals(200, t.send("GET", "/h", headerPairs).status());
        Seen s = ref.get();
        assertTrue("handler did not run", s != null);
        return s;
    }

    private static Seen captureThree() {
        return capture("X-A", "1", "x-a", "2", "X-B", "3");
    }

    private static List<Map.Entry<String, String>> expectedRaw() {
        return Arrays.<Map.Entry<String, String>>asList(
                new SimpleImmutableEntry<>("X-A", "1"),
                new SimpleImmutableEntry<>("x-a", "2"),
                new SimpleImmutableEntry<>("X-B", "3"));
    }

    private static void assertThrowsUnsupported(String what, Runnable action) {
        try {
            action.run();
            fail(what + " did not throw UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // the enumeration is read-only
        }
    }

    // FR-HTTP-032 AC-1: X-A: 1, x-a: 2, X-B: 3 gives headerNames() iterating exactly [x-a, x-b]
    @Test
    public void FR_HTTP_032_AC1_headerNamesAreLowercasedDistinctAndInFirstSeenOrder() {
        Seen s = captureThree();
        assertEquals(Arrays.asList("x-a", "x-b"), new ArrayList<>(s.names));
    }

    // FR-HTTP-032 AC-1: the set keeps first-seen order, not sorted order: X-B, X-A, x-b iterates [x-b, x-a]
    @Test
    public void FR_HTTP_032_AC1_headerNamesKeepFirstSeenOrderRatherThanSortedOrder() {
        Seen s = capture("X-B", "3", "X-A", "1", "x-b", "4");
        assertEquals(Arrays.asList("x-b", "x-a"), new ArrayList<>(s.names));
    }

    // FR-HTTP-032 AC-2: rawHeaders() holds (X-A, 1), (x-a, 2), (X-B, 3) in that order, case and duplicates kept
    @Test
    public void FR_HTTP_032_AC2_rawHeadersKeepOrderCaseAndDuplicates() {
        Seen s = captureThree();
        assertEquals(expectedRaw(), new ArrayList<>(s.raw));
        assertEquals("X-A", s.raw.get(0).getKey());
        assertEquals("x-a", s.raw.get(1).getKey());
    }

    // FR-HTTP-032 AC-3: add/remove/clear on headerNames(), add/set/remove on rawHeaders() and setValue on an entry
    // each throw UnsupportedOperationException, and the results stay unchanged
    @Test
    public void FR_HTTP_032_AC3_bothEnumerationsAndTheirEntriesAreUnmodifiable() {
        Seen s = captureThree();
        Set<String> names = s.names;
        List<Map.Entry<String, String>> raw = s.raw;
        assertThrowsUnsupported("headerNames().add", () -> names.add("x-c"));
        assertThrowsUnsupported("headerNames().remove", () -> names.remove("x-a"));
        assertThrowsUnsupported("headerNames().clear", names::clear);
        assertThrowsUnsupported("rawHeaders().add", () -> raw.add(new SimpleImmutableEntry<>("X-C", "4")));
        assertThrowsUnsupported("rawHeaders().set", () -> raw.set(0, new SimpleImmutableEntry<>("X-C", "4")));
        assertThrowsUnsupported("rawHeaders().remove", () -> raw.remove(0));
        for (int i = 0; i < raw.size(); i++) {
            Map.Entry<String, String> e = raw.get(i);
            assertThrowsUnsupported("rawHeaders().get(" + i + ").setValue", () -> e.setValue("changed"));
        }
        assertEquals(Arrays.asList("x-a", "x-b"), new ArrayList<>(names));
        assertEquals(expectedRaw(), new ArrayList<>(raw));
    }

    // FR-HTTP-032 AC-3: entries stay immutable when the adapter reports mutable entries, and the adapter's own entries
    // are not changed through rawHeaders()
    @Test
    public void FR_HTTP_032_AC3_entriesAreImmutableEvenWhenTheAdapterReportsMutableOnes() {
        RecordingExchange ex = new RecordingExchange("GET", "/h");
        ex.requestHeaders.add(new SimpleEntry<>("X-A", "1"));
        ex.requestHeaders.add(new SimpleEntry<>("x-a", "2"));
        ex.requestHeaders.add(new SimpleEntry<>("X-B", "3"));
        DefaultRequest req = new DefaultRequest(ex, Method.GET, Collections.<String, String>emptyMap());
        List<Map.Entry<String, String>> raw = req.rawHeaders();
        assertEquals(expectedRaw(), new ArrayList<>(raw));
        for (int i = 0; i < raw.size(); i++) {
            Map.Entry<String, String> e = raw.get(i);
            assertThrowsUnsupported("rawHeaders().get(" + i + ").setValue", () -> e.setValue("changed"));
        }
        assertEquals(expectedRaw(), new ArrayList<>(raw));
        assertEquals(expectedRaw(), new ArrayList<>(ex.requestHeaders));
    }

    // FR-HTTP-032 AC-4: under the default locale tr-TR, TITLE: x appears in headerNames() as title (Locale.ROOT)
    @Test
    public void FR_HTTP_032_AC4_namesAreLowercasedWithLocaleRootUnderTurkishDefault() {
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertEquals("the default locale lowercases the dotted I", "t\u0131tle", "TITLE".toLowerCase());
            Seen s = capture("TITLE", "x");
            assertEquals(Arrays.asList("title"), new ArrayList<>(s.names));
        } finally {
            Locale.setDefault(saved);
        }
    }

    // FR-HTTP-032 AC-5: two calls of headerNames(), and two of rawHeaders(), in one request return the same instance
    @Test
    public void FR_HTTP_032_AC5_eachEnumerationIsBuiltOncePerRequest() {
        Seen s = captureThree();
        assertEquals(Arrays.asList("x-a", "x-b"), new ArrayList<>(s.names));
        assertEquals(expectedRaw(), new ArrayList<>(s.raw));
        assertSame(s.names, s.namesAgain);
        assertSame(s.raw, s.rawAgain);
    }

    // FR-HTTP-032 AC-6: for every name of headerNames(), req.headers(name) equals in order the values of the
    // rawHeaders() entries whose name matches it case-insensitively
    @Test
    public void FR_HTTP_032_AC6_headersOfEveryNameMatchTheRawEntriesOfThatName() {
        Seen s = captureThree();
        assertEquals(Arrays.asList("x-a", "x-b"), new ArrayList<>(s.names));
        List<String> names = new ArrayList<>(s.names);
        for (int i = 0; i < names.size(); i++) {
            List<String> fromRaw = new ArrayList<>();
            for (Map.Entry<String, String> e : s.raw) {
                if (e.getKey().equalsIgnoreCase(names.get(i))) {
                    fromRaw.add(e.getValue());
                }
            }
            assertEquals(names.get(i), fromRaw, s.headersPerName.get(i));
        }
        assertEquals(Arrays.asList("1", "2"), s.headersPerName.get(0));
        assertEquals(Arrays.asList("3"), s.headersPerName.get(1));
    }
}
