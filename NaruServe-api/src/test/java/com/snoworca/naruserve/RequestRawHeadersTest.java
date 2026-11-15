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

    /** What a handler saw: two calls of each enumeration, the per-name header lists and the first value per name. */
    private static final class Seen {
        Set<String> names;
        Set<String> namesAgain;
        List<Map.Entry<String, String>> raw;
        List<Map.Entry<String, String>> rawAgain;
        final List<List<String>> headersPerName = new ArrayList<>();
        final List<String> firstValuePerName = new ArrayList<>();
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
                s.firstValuePerName.add(req.header(name));
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

    // FR-HTTP-032 AC-4: under the default locale tr-TR, TITLE: x appears in headerNames() as title, each character
    // lowercased by Character.toLowerCase without any locale (the method name keeps its historical LocaleRoot wording)
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

    // FR-HTTP-032 AC-6: a name whose String.toLowerCase is longer than the raw name (U+0130 capital dotted I
    // lowercases to i + U+0307 there) is listed per character as x-id and still finds its values through
    // headers(name) and header(name)
    @Test
    public void FR_HTTP_032_AC6_headersOfANameWhoseLowercaseChangesLengthMatchTheRawEntries() {
        Seen s = capture("X-\u0130D", "a", "X-B", "b");
        assertEquals(Arrays.asList("x-id", "x-b"), new ArrayList<>(s.names));
        assertEquals(Arrays.asList(Arrays.asList("a"), Arrays.asList("b")), s.headersPerName);
        assertEquals(Arrays.asList("a", "b"), s.firstValuePerName);
    }

    // FR-HTTP-032 AC-6: names X-<U+03A3> and x-<U+03C3> match case-insensitively although Locale.ROOT lowercases the
    // word-final capital sigma to U+03C2, so headerNames() lists one name and headers(thatName) keeps both values
    @Test
    public void FR_HTTP_032_AC6_namesThatMatchCaseInsensitivelyButLowercaseDifferentlyAreOneName() {
        Seen s = capture("X-\u03a3", "1", "x-\u03c3", "2");
        assertEquals(1, s.names.size());
        String name = s.names.iterator().next();
        List<String> fromRaw = new ArrayList<>();
        for (Map.Entry<String, String> e : s.raw) {
            if (e.getKey().equalsIgnoreCase(name)) {
                fromRaw.add(e.getValue());
            }
        }
        assertEquals(Arrays.asList("1", "2"), fromRaw);
        assertEquals(Arrays.asList(Arrays.asList("1", "2")), s.headersPerName);
    }

    // FR-HTTP-011 AC-1: header()/headers() match names case-insensitively, so a header sent as X-<U+03A3> is
    // found through x-<U+03C3> and X-<U+03C3> even though their Locale.ROOT lowercases differ
    @Test
    public void FR_HTTP_011_AC1_headerAndHeadersMatchASigmaNameCaseInsensitively() {
        TestApp t = TestApp.create();
        AtomicReference<String> single = new AtomicReference<>();
        AtomicReference<List<String>> all = new AtomicReference<>();
        t.app().get("/h", (req, res) -> {
            single.set(req.header("x-\u03c3"));
            all.set(req.headers("X-\u03c3"));
            res.send("ok");
        });
        assertEquals(200, t.send("GET", "/h", "X-\u03a3", "1").status());
        assertEquals("1", single.get());
        assertEquals(Arrays.asList("1"), all.get());
    }

    /** The character-by-character {@link Character#toLowerCase(char)} of {@code name}, without any locale. */
    private static String perCharLower(String name) {
        char[] c = name.toCharArray();
        for (int i = 0; i < c.length; i++) {
            c[i] = Character.toLowerCase(c[i]);
        }
        return new String(c);
    }

    /**
     * Every listed name is the per-character lowercase of the first raw name it matches by equalsIgnoreCase, so it
     * has that raw name's length.
     */
    private static void assertListedNamesArePerCharLowercaseOfFirstRawName(Seen s) {
        for (String name : s.names) {
            String firstRaw = null;
            for (Map.Entry<String, String> e : s.raw) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    firstRaw = e.getKey();
                    break;
                }
            }
            assertTrue("listed name " + name + " matches no raw name", firstRaw != null);
            assertEquals(perCharLower(firstRaw), name);
            assertEquals(firstRaw.length(), name.length());
        }
    }

    /** Walking headerNames() and headers(name) reaches every raw value exactly once. */
    private static void assertEachValueReachedOnce(Seen s, String... values) {
        List<String> reached = new ArrayList<>();
        for (List<String> v : s.headersPerName) {
            reached.addAll(v);
        }
        Collections.sort(reached);
        List<String> expected = new ArrayList<>(Arrays.asList(values));
        Collections.sort(expected);
        assertEquals("values reached through headerNames()/headers(name)", expected, reached);
    }

    // FR-HTTP-032 AC-4: under the default locale tr-TR each listed name is the character-by-character
    // Character.toLowerCase of its first raw name: TITLE -> title, X-<U+0130>D -> x-id (not i + U+0307),
    // X-<U+03A3><U+03A3> -> x-<U+03C3><U+03C3> (no word-final U+03C2), each the length of its raw name
    @Test
    public void FR_HTTP_032_AC4_namesAreLowercasedCharacterByCharacterKeepingTheRawLength() {
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            Seen s = capture("TITLE", "a", "X-\u0130D", "b", "X-\u03a3\u03a3", "c");
            assertEquals(Arrays.asList("title", "x-id", "x-\u03c3\u03c3"), new ArrayList<>(s.names));
            assertListedNamesArePerCharLowercaseOfFirstRawName(s);
            assertEquals(Arrays.asList(Arrays.asList("a"), Arrays.asList("b"), Arrays.asList("c")), s.headersPerName);
        } finally {
            Locale.setDefault(saved);
        }
    }

    // FR-HTTP-032 AC-7: X-<U+0130>d: 1 with x-id: 2 gives one listed name x-id whose headers are [1, 2]
    @Test
    public void FR_HTTP_032_AC7_capitalDottedIAndPlainIAreOneNameListedAsXId() {
        Seen s = capture("X-\u0130d", "1", "x-id", "2");
        assertEquals(Arrays.asList("x-id"), new ArrayList<>(s.names));
        assertEquals(Arrays.asList(Arrays.asList("1", "2")), s.headersPerName);
        assertListedNamesArePerCharLowercaseOfFirstRawName(s);
    }

    // FR-HTTP-032 AC-7: X-<U+03A3>: 1 with x-<U+03C3>: 2 gives one name x-<U+03C3> whose headers are [1, 2]
    @Test
    public void FR_HTTP_032_AC7_capitalAndSmallSigmaAreOneNameListedWithSmallSigma() {
        Seen s = capture("X-\u03a3", "1", "x-\u03c3", "2");
        assertEquals(Arrays.asList("x-\u03c3"), new ArrayList<>(s.names));
        assertEquals(Arrays.asList(Arrays.asList("1", "2")), s.headersPerName);
        assertListedNamesArePerCharLowercaseOfFirstRawName(s);
    }

    // FR-HTTP-032 AC-7: x-<U+0131>d: 1, X-<U+0130>d: 2 and x-i<U+0307>d: 3 give names whose headers together hold
    // each value exactly once, and every listed name has the length of its first raw name
    @Test
    public void FR_HTTP_032_AC7_dotlessDottedAndCombiningDotNamesReachEachValueOnce() {
        Seen s = capture("x-\u0131d", "1", "X-\u0130d", "2", "x-i\u0307d", "3");
        assertEachValueReachedOnce(s, "1", "2", "3");
        assertListedNamesArePerCharLowercaseOfFirstRawName(s);
        assertEquals(Arrays.asList("x-\u0131d", "x-i\u0307d"), new ArrayList<>(s.names));
        assertEquals(Arrays.asList(Arrays.asList("1", "2"), Arrays.asList("3")), s.headersPerName);
    }

    // FR-HTTP-032 AC-6: X-<U+0130>d: 1 then x-id: 2 keeps value 2 reachable through a listed name (loop P case 1)
    @Test
    public void FR_HTTP_032_AC6_valueAfterACapitalDottedINameStaysReachable() {
        Seen s = capture("X-\u0130d", "1", "x-id", "2");
        assertEachValueReachedOnce(s, "1", "2");
    }

    // FR-HTTP-032 AC-6: X-<U+0130>d after x-id and x-i<U+0307>d does not bridge them, so walking headerNames() and
    // headers(name) reports every value exactly once (loop P case 2)
    @Test
    public void FR_HTTP_032_AC6_capitalDottedIDoesNotBridgeTwoNamesIntoADuplicateValue() {
        Seen s = capture("x-id", "1", "x-i\u0307d", "2", "X-\u0130d", "3");
        assertEachValueReachedOnce(s, "1", "2", "3");
        assertEquals(Arrays.asList("x-id", "x-i\u0307d"), new ArrayList<>(s.names));
        assertEquals(Arrays.asList(Arrays.asList("1", "3"), Arrays.asList("2")), s.headersPerName);
    }

    // FR-HTTP-032 AC-6: the same bridge through a dotless x-<U+0131>d first name reports every value exactly once
    // (loop P case 3)
    @Test
    public void FR_HTTP_032_AC6_dotlessINameIsNotBridgedIntoADuplicateValue() {
        Seen s = capture("x-\u0131d", "1", "x-i\u0307d", "2", "X-\u0130d", "3");
        assertEachValueReachedOnce(s, "1", "2", "3");
        assertEquals(Arrays.asList("x-\u0131d", "x-i\u0307d"), new ArrayList<>(s.names));
        assertEquals(Arrays.asList(Arrays.asList("1", "3"), Arrays.asList("2")), s.headersPerName);
    }
}
