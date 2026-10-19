package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Lenient request-cookie parsing. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-3.sds.md. */
public class CookieParserTest {

    private static List<String> pairs(Cookie[] cookies) {
        List<String> out = new ArrayList<>();
        for (Cookie c : cookies) {
            out.add(c.getName() + "=" + c.getValue());
        }
        return out;
    }

    // SDS-AC-8; FR-COOKIE-002 AC-1
    @Test
    public void sdsAc8_FR_COOKIE_002_AC1_duplicateNamesAndSeveralHeaderLinesKeepHeaderOrder() {
        assertEquals(Arrays.asList("a=1", "b=2", "a=3"),
                pairs(CookieParser.parse(Collections.singletonList("a=1; b=2; a=3"))));
        assertEquals(Arrays.asList("a=1", "b=2", "c=3", "a=4"),
                pairs(CookieParser.parse(Arrays.asList("a=1; b=2", "c=3;a=4"))));
        assertEquals(0, CookieParser.parse(Collections.<String>emptyList()).length);
    }

    // SDS-AC-9; FR-COOKIE-002 AC-4
    @Test
    public void sdsAc9_FR_COOKIE_002_AC4_badPairsAreSkippedAndValuesKeptVerbatim() {
        Cookie[] cs = CookieParser.parse(Collections.singletonList("bad name=1; ok=2; =3; noeq; q=\"x y\"; p=a%2Cb"));
        assertEquals(Arrays.asList("ok=2", "q=\"x y\"", "p=a%2Cb"), pairs(cs));
        assertEquals(Arrays.asList("e=", "s=a=b"), pairs(CookieParser.parse(Collections.singletonList(
                " ;; e= ;  s=a=b  ;"))));
        assertEquals(0, CookieParser.parse(Arrays.asList("", ";", "a\u0000b=1", "é=1")).length);
    }

    // SDS-AC-3; FR-COOKIE-002 AC-3, AC-6; IR-COOKIE-001 AC-6
    @Test
    public void sdsAc3_FR_COOKIE_002_AC3_AC6_parsedCookiesComeFromTheUnvalidatedFactoryWithDefaults() {
        Cookie[] cs = CookieParser.parse(Collections.singletonList("q=\"x y\"; c=a,b; t=a\u0001b; ok=1"));
        assertEquals(Arrays.asList("q=\"x y\"", "c=a,b", "t=a\u0001b", "ok=1"), pairs(cs));
        for (Cookie c : cs) {
            assertNull(c.getPath());
            assertNull(c.getDomain());
            assertNull(c.getSameSite());
            assertEquals(-1, c.getMaxAge());
            assertFalse(c.getSecure());
            assertFalse(c.isHttpOnly());
        }
        assertEquals("x y", CookieFactoryHook.create("n", "x y").getValue());
    }

    // FR-COOKIE-001 AC-5: the send-time check catches every unvalidated value the parser cannot produce, too
    @Test
    public void FR_COOKIE_001_AC5_serializerRevalidatesUnvalidatedCookies() {
        for (String bad : new String[] {"x;y", "x y", "x,y", "x\ry", "x\ny", "x\u0001y"}) {
            try {
                CookieSerializer.serialize(CookieFactoryHook.create("a", bad));
                org.junit.Assert.fail(bad);
            } catch (IllegalArgumentException expected) {
                org.junit.Assert.assertTrue(expected.getMessage().contains("value"));
            }
        }
    }
}
