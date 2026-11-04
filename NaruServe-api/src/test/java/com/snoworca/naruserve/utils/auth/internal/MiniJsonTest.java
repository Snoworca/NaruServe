package com.snoworca.naruserve.utils.auth.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.utils.auth.internal.MiniJson.JsonFormatException;
import com.snoworca.naruserve.utils.auth.internal.MiniJson.Limits;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** The strict MiniJson reader and writer with the CLAIMS and FILE profiles (SEC-TOKEN-002). */
public class MiniJsonTest {

    private static String nested(int depth) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            b.append("{\"a\":");
        }
        b.append("1");
        for (int i = 0; i < depth; i++) {
            b.append('}');
        }
        return b.toString();
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    private static JsonFormatException refused(String text, Limits limits) {
        try {
            MiniJson.parse(text, limits);
        } catch (JsonFormatException expected) {
            return expected;
        }
        throw new AssertionError("accepted " + (text.length() > 60 ? text.substring(0, 60) + "..." : text));
    }

    // SEC-TOKEN-002 AC-1: CLAIMS refuses depth 3 and accepts depth 2; FILE accepts 8 and refuses 9
    @Test
    public void SEC_TOKEN_002_AC1_depthLimits() {
        assertTrue(refused(nested(3), Limits.claims()).getMessage().contains("deeper than 2"));
        assertEquals(1L, ((Map<?, ?>) ((Map<?, ?>) MiniJson.parse(nested(2), Limits.claims())).get("a")).get("a"));
        MiniJson.parse("[[1]]", Limits.claims());
        refused("[[[1]]]", Limits.claims());
        MiniJson.parse(nested(8), Limits.file());
        assertTrue(refused(nested(9), Limits.file()).getMessage().contains("deeper than 8"));
    }

    // SEC-TOKEN-002 AC-2: CLAIMS 8 KB total and 1 KB strings; FILE the configured total (8 MB default) and 64 KB strings
    @Test
    public void SEC_TOKEN_002_AC2_sizeLimits() {
        String justFits = "{\"s\":\"" + repeat('a', 8 * 1024 - 8) + "\"}";
        assertEquals(8 * 1024, justFits.getBytes(StandardCharsets.UTF_8).length);
        refused(justFits, Limits.claims()); // the string inside is over 1 KB
        String tiny = "{\"s\":\"" + repeat(' ', 0) + "\"," + "\"t\":[" + repeat('1', 1) + "]}";
        MiniJson.parse(tiny, Limits.claims());
        // exactly 8192 bytes, every string under 1 KB: accepted; one byte more: refused at the total
        StringBuilder eightKb = new StringBuilder("[");
        while (eightKb.length() + 1003 + 1 <= 8 * 1024) {
            eightKb.append(eightKb.length() == 1 ? "" : ",").append('"').append(repeat('x', 1000)).append('"');
        }
        while (eightKb.length() < 8 * 1024 - 1) {
            eightKb.append(' ');
        }
        eightKb.append(']');
        assertEquals(8 * 1024, eightKb.toString().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(8, ((java.util.List<?>) MiniJson.parse(eightKb.toString(), Limits.claims())).size());
        MiniJson.parse(eightKb.toString().getBytes(StandardCharsets.UTF_8), Limits.claims());
        String overByOne = eightKb + " ";
        assertTrue(refused(overByOne, Limits.claims()).getMessage().contains("8192"));
        StringBuilder many = new StringBuilder("[");
        while (many.length() < 8 * 1024) {
            many.append("1,");
        }
        many.append("1]");
        assertTrue(refused(many.toString(), Limits.claims()).getMessage().contains("8192"));
        try {
            MiniJson.parse(many.toString().getBytes(StandardCharsets.UTF_8), Limits.claims());
            fail("accepted bytes over the total");
        } catch (JsonFormatException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("8192"));
        }
        assertEquals(repeat('b', 1024), MiniJson.parse("\"" + repeat('b', 1024) + "\"", Limits.claims()));
        refused("\"" + repeat('b', 1025) + "\"", Limits.claims());
        refused("\"" + repeat('\u00e9', 513) + "\"", Limits.claims()); // 1,026 UTF-8 bytes
        assertEquals(64 * 1024, ((String) MiniJson.parse("\"" + repeat('c', 64 * 1024) + "\"", Limits.file())).length());
        refused("\"" + repeat('c', 64 * 1024 + 1) + "\"", Limits.file());
        assertEquals(8L * 1024 * 1024, Limits.file().maxTotalBytes());
        String hundredBytes = "[" + repeat('1', 1) + repeat(' ', 97) + "]";
        assertEquals(100, hundredBytes.length());
        MiniJson.parse(hundredBytes, Limits.file(100));
        refused(hundredBytes + " ", Limits.file(100));
    }

    // SEC-TOKEN-002 AC-3: duplicate keys, NaN and Infinity, invalid escapes and trailing characters, in both profiles
    @Test
    public void SEC_TOKEN_002_AC3_strictness() {
        for (Limits l : new Limits[] {Limits.claims(), Limits.file()}) {
            assertTrue(refused("{\"alg\":\"HS256\",\"alg\":\"none\"}", l).getMessage().contains("duplicate"));
            refused("{\"a\":NaN}", l);
            refused("{\"a\":Infinity}", l);
            refused("{\"a\":-Infinity}", l);
            refused("[NaN]", l);
            refused("{\"a\":\"\\x41\"}", l);
            refused("{\"a\":\"\\u12\"}", l);
            refused("{\"a\":1} x", l);
            refused("{\"a\":1}}", l);
            refused("{\"a\":1},", l);
            refused("{\"a\":01}", l);
            refused("{\"a\":1.}", l);
            refused("{'a':1}", l);
            refused("{\"a\":\"tab\there\"}", l);
            refused("", l);
            refused("{\"a\":1,}", l);
            assertEquals("A", ((Map<?, ?>) MiniJson.parse(" {\"a\":\"\\u0041\"} \n", l)).get("a"));
        }
    }

    // SEC-TOKEN-002 AC-4: a 100-level array inside a JWT header or payload stops at the CLAIMS depth limit
    // SEC-TOKEN-002 AC-3: a \\u escape takes ASCII hex digits only (RFC 8259 HEXDIG): fullwidth or other Unicode
    // digits and letters are an invalid escape in both profiles, so one string has one spelling
    @Test
    public void SEC_TOKEN_002_AC3_unicodeEscapeTakesAsciiHexOnly() {
        for (Limits l : new Limits[] {Limits.claims(), Limits.file()}) {
            // U+FF10 U+FF10 U+FF14 U+FF11: fullwidth "0041"
            assertTrue(refused("{\"a\":\"\\u\uff10\uff10\uff14\uff11\"}", l).getMessage().contains("\\u escape"));
            refused("{\"a\":\"\\u00\uff21\uff21\"}", l); // fullwidth "AA"
            refused("{\"a\":\"\\u00\u0664\u0661\"}", l); // Arabic-Indic digits 4 and 1
            assertEquals("\u00aa\u00AA", ((Map<?, ?>) MiniJson.parse("{\"a\":\"\\u00aa\\u00AA\"}", l)).get("a"));
            assertEquals("\u09af", ((Map<?, ?>) MiniJson.parse("{\"a\":\"\\u09aF\"}", l)).get("a"));
        }
    }

    @Test
    public void SEC_TOKEN_002_AC4_deepArrayStopsAtTheLimitWithoutParsingFurther() {
        String deep = repeat('[', 100) + repeat(']', 100);
        assertTrue(refused(deep, Limits.claims()).getMessage().contains("deeper than 2"));
        // the content past the third level is not even JSON: the depth error comes first, so it was never parsed
        String hostile = "{\"alg\":\"HS256\",\"x\":" + repeat('[', 100) + "this is not json";
        JsonFormatException e = refused(hostile, Limits.claims());
        assertTrue(e.getMessage(), e.getMessage().contains("depth"));
    }

    // the writer keeps the FILE limits, so a store never writes a file its own start cannot read
    @Test
    public void writerRefusesWhatTheReaderWouldRefuse() {
        Object deep = 1L;
        for (int i = 0; i < 9; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("a", deep);
            deep = m;
        }
        try {
            MiniJson.write(deep, Limits.file());
            fail("wrote a depth-9 FILE document");
        } catch (JsonFormatException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("deeper than 8"));
        }
        try {
            MiniJson.write(repeat('x', 64 * 1024 + 1), Limits.file());
            fail("wrote a string over 64 KB");
        } catch (JsonFormatException expected) {
            // the string limit
        }
        try {
            MiniJson.write(Arrays.<Object>asList("abcdefghij", "abcdefghij"), Limits.file(10));
            fail("wrote a document over the total");
        } catch (JsonFormatException expected) {
            // the total limit
        }
    }

    // exact integers, strict UTF-8 and paired surrogates
    @Test
    public void numbersAndTextAreExact() {
        assertEquals(Long.MIN_VALUE, MiniJson.parse("-9223372036854775808", Limits.claims()));
        try {
            MiniJson.parse(new byte[] {'"', (byte) 0xC3, '"'}, Limits.claims());
            fail("accepted malformed UTF-8");
        } catch (JsonFormatException expected) {
            // refused, never replaced by U+FFFD
        }
        refused("\"\\ud800\"", Limits.claims());
        refused("\"\\udc00x\"", Limits.claims());
        assertEquals("\ud83d\ude00", MiniJson.parse("\"\\ud83d\\ude00\"", Limits.claims()));
    }

    // SEC-TOKEN-002 AC-5: a FILE document of depth 3 or more written by MiniJson reads back to the same values
    @Test
    public void SEC_TOKEN_002_AC5_fileDocumentsRoundTrip() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "u-1");
        user.put("ver", 9_007_199_254_740_993L); // beyond a double's exact range: stays exact
        user.put("enabled", Boolean.TRUE);
        user.put("hash", null);
        user.put("ratio", 0.25d);
        user.put("roles", Arrays.<Object>asList("admin", "editor"));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("quote", "a \"b\"\n\\c\u2028\u0001\ud55c");
        attrs.put("deep", Arrays.<Object>asList(Arrays.<Object>asList(1L, 2L)));
        user.put("attributes", attrs);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", 1L);
        doc.put("users", Arrays.<Object>asList(user));
        String text = MiniJson.write(doc);
        Object back = MiniJson.parse(text, Limits.file());
        assertEquals(doc, back);
        List<?> users = (List<?>) ((Map<?, ?>) back).get("users");
        assertEquals(Long.class, ((Map<?, ?>) users.get(0)).get("ver").getClass());
        assertEquals(text, MiniJson.write(back));
    }
}
