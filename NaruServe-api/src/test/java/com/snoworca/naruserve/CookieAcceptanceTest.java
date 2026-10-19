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
import org.junit.Test;

/** T33 and T34 over the in-memory adapter. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-3.sds.md. */
public class CookieAcceptanceTest {

    private static List<String> setCookies(ClientResponse r) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> h : r.headers()) {
            if (h.getKey().equalsIgnoreCase("Set-Cookie")) {
                out.add(h.getValue());
            }
        }
        return out;
    }

    private static List<String> pairs(Cookie[] cookies) {
        List<String> out = new ArrayList<>();
        for (Cookie c : cookies) {
            out.add(c.getName() + "=" + c.getValue());
        }
        return out;
    }

    // FR-COOKIE-001 AC-1
    @Test
    public void FR_COOKIE_001_AC1_addCookieReturnsTheResponseAndAppendsOneLinePerCall() {
        TestApp t = TestApp.create();
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        t.app().get("/c", (req, res) -> {
            seen.add(res.addCookie(new Cookie("a", "1")) == res);
            res.addCookie(new Cookie("a", "2")).header("X", "y");
            try {
                res.addCookie(null);
            } catch (NullPointerException e) {
                seen.add("npe");
            }
            res.end();
        });
        ClientResponse r = t.send("GET", "/c");
        assertEquals(Arrays.asList("a=1", "a=2"), setCookies(r));
        assertEquals(Arrays.<Object>asList(true, "npe"), seen);
    }

    // SDS-AC-4; FR-COOKIE-001 AC-2 (T33)
    @Test
    public void sdsAc4_FR_COOKIE_001_AC2_fullAttributeLineAndSameSiteValues() {
        TestApp t = TestApp.create();
        t.app().get("/c", (req, res) -> {
            Cookie c = new Cookie("sid", "abc");
            c.setPath("/");
            c.setDomain("example.com");
            c.setMaxAge(3600);
            c.setSecure(true);
            c.setHttpOnly(true);
            c.setSameSite(SameSite.LAX);
            res.addCookie(c);
            res.addCookie(new Cookie("sid", "abc"));
            Cookie s = new Cookie("s", "1");
            s.setSameSite(SameSite.STRICT);
            res.addCookie(s);
            Cookie n = new Cookie("n", "1");
            n.setSameSite(SameSite.NONE);
            n.setSecure(true);
            res.addCookie(n);
            res.end();
        });
        assertEquals(Arrays.asList(
                "sid=abc; Path=/; Domain=example.com; Max-Age=3600; Secure; HttpOnly; SameSite=Lax",
                "sid=abc", "s=1; SameSite=Strict", "n=1; Secure; SameSite=None"), setCookies(t.send("GET", "/c")));
    }

    // SDS-AC-5; FR-COOKIE-001 AC-3, AC-4 (T33)
    @Test
    public void sdsAc5_FR_COOKIE_001_AC3_AC4_negativeMaxAgeOmitsMaxAgeAndZeroDeletes() {
        TestApp t = TestApp.create();
        t.app().get("/c", (req, res) -> {
            res.addCookie(new Cookie("d", "1"));
            Cookie m = new Cookie("m", "1");
            m.setMaxAge(-5);
            res.addCookie(m);
            Cookie del = new Cookie("sid", "");
            del.setMaxAge(0);
            del.setPath("/");
            del.setDomain("example.com");
            res.addCookie(del);
            res.end();
        });
        List<String> lines = setCookies(t.send("GET", "/c"));
        assertEquals(Arrays.asList("d=1", "m=1", "sid=; Path=/; Domain=example.com; Max-Age=0"), lines);
        for (String l : lines) {
            assertTrue(l, !l.contains("Expires"));
        }
    }

    // SDS-AC-6; FR-COOKIE-001 AC-5
    @Test
    public void sdsAc6_FR_COOKIE_001_AC5_echoingAnInvalidRequestCookieThrowsAndAddsNoHeader() {
        TestApp t = TestApp.create();
        List<String> caught = Collections.synchronizedList(new ArrayList<String>());
        t.app().get("/c", (req, res) -> {
            for (Cookie c : req.getCookies()) {
                try {
                    res.addCookie(c);
                    caught.add("added " + c.getName());
                } catch (IllegalArgumentException e) {
                    caught.add("rejected " + c.getName());
                }
            }
            res.end();
        });
        ClientResponse r = t.send("GET", "/c", "Cookie", "sp=\"x y\"; sc=a,b; ok=1; ct=a\u0001b", "Cookie", "q=a\"b");
        assertEquals(Arrays.asList("rejected sp", "rejected sc", "added ok", "rejected ct", "rejected q"), caught);
        assertEquals(Arrays.asList("ok=1"), setCookies(r));
        for (Map.Entry<String, String> h : r.headers()) {
            assertTrue(h.getValue().indexOf('\r') < 0 && h.getValue().indexOf('\n') < 0);
        }
    }

    // SDS-AC-7; FR-COOKIE-001 AC-6
    @Test
    public void sdsAc7_FR_COOKIE_001_AC6_addCookieAfterSendThrowsIllegalStateException() {
        TestApp t = TestApp.create();
        List<String> caught = Collections.synchronizedList(new ArrayList<String>());
        t.app().get("/c", (req, res) -> {
            res.send("x");
            try {
                res.addCookie(new Cookie("a", "1"));
            } catch (IllegalStateException e) {
                caught.add("ise");
            }
        });
        ClientResponse r = t.send("GET", "/c");
        assertEquals(Arrays.asList("ise"), caught);
        assertEquals(Collections.<String>emptyList(), setCookies(r));
    }

    // SDS-AC-10; FR-COOKIE-002 AC-1, AC-2, AC-3 (T34)
    @Test
    public void sdsAc10_FR_COOKIE_002_AC1_AC2_AC3_readersReturnFreshArraysAndTheFirstMatch() {
        TestApp t = TestApp.create();
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        t.app().get("/c", (req, res) -> {
            Cookie[] first = req.getCookies();
            Cookie[] second = req.getCookies();
            seen.add(first != second);
            seen.add(pairs(first));
            if (first.length > 0) {
                first[0] = null;
                seen.add(pairs(req.getCookies()));
                Cookie a = req.cookie("a");
                seen.add(a.getValue());
                seen.add(a.getPath() == null && a.getDomain() == null && a.getSameSite() == null
                        && a.getMaxAge() == -1 && !a.getSecure() && !a.isHttpOnly());
            }
            seen.add(req.cookie("zz") == null);
            res.end();
        });
        t.send("GET", "/c", "Cookie", "a=1; b=2; a=3");
        assertEquals(Arrays.<Object>asList(true, Arrays.asList("a=1", "b=2", "a=3"),
                Arrays.asList("a=1", "b=2", "a=3"), "1", true, true), seen);
        seen.clear();
        t.send("GET", "/c");
        assertEquals(Arrays.<Object>asList(true, Collections.emptyList(), true), seen);
    }

    // FR-COOKIE-001 AC-5: a path char above U+00FF is refused like res.header, since a byte-truncating adapter
    // would turn U+010D into CR
    @Test
    public void FR_COOKIE_001_AC5_pathCharAboveLatin1IsRejectedAndNothingAdded() {
        TestApp t = TestApp.create();
        List<String> caught = Collections.synchronizedList(new ArrayList<String>());
        t.app().get("/c", (req, res) -> {
            Cookie c = new Cookie("a", "1");
            c.setPath("/č");
            try {
                res.addCookie(c);
            } catch (IllegalArgumentException e) {
                caught.add("iae");
            }
            res.end();
        });
        ClientResponse r = t.send("GET", "/c");
        assertEquals(Arrays.asList("iae"), caught);
        assertEquals(Collections.<String>emptyList(), setCookies(r));
    }

    // FR-COOKIE-002 AC-3: changing a returned cookie leaves later reads with the parsed defaults
    @Test
    public void FR_COOKIE_002_AC3_returnedCookiesAreCopies() {
        TestApp t = TestApp.create();
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        t.app().get("/c", (req, res) -> {
            req.cookie("sid").setMaxAge(0);
            req.getCookies()[0].setPath("/");
            Cookie again = req.cookie("sid");
            seen.add(again.getMaxAge());
            seen.add(req.getCookies()[0].getPath() == null);
            res.end();
        });
        t.send("GET", "/c", "Cookie", "sid=1");
        assertEquals(Arrays.<Object>asList(-1, true), seen);
    }

    // FR-COOKIE-002 AC-6
    @Test
    public void FR_COOKIE_002_AC6_cookieOfNullThrowsAndOddValuesAreReturned() {
        TestApp t = TestApp.create();
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        t.app().get("/c", (req, res) -> {
            try {
                req.cookie(null);
            } catch (NullPointerException e) {
                seen.add("npe");
            }
            seen.add(req.cookie("q").getValue());
            res.end();
        });
        t.send("GET", "/c", "Cookie", "q=x y,z");
        assertEquals(Arrays.<Object>asList("npe", "x y,z"), seen);
    }

    // FR-COOKIE-002 AC-5 (T34 round trip)
    @Test
    public void FR_COOKIE_002_AC5_percentValueRoundTripsUnchanged() {
        TestApp t = TestApp.create();
        List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        t.app().get("/set", (req, res) -> res.addCookie(new Cookie("x", "a%2Cb")).end());
        t.app().get("/echo", (req, res) -> {
            Cookie x = req.cookie("x");
            seen.add(x.getValue());
            res.addCookie(x).end();
        });
        List<String> set = setCookies(t.send("GET", "/set"));
        assertEquals(Arrays.asList("x=a%2Cb"), set);
        ClientResponse echo = t.send("GET", "/echo", "Cookie", set.get(0));
        assertEquals(Arrays.asList("a%2Cb"), seen);
        assertEquals(Arrays.asList("x=a%2Cb"), setCookies(echo));
    }

    // FR-COOKIE-001 AC-5: no framework-written Set-Cookie holds CR or LF
    @Test
    public void FR_COOKIE_001_AC5_crOrLfNeverReachesASetCookieLine() {
        TestApp t = TestApp.create();
        List<String> caught = Collections.synchronizedList(new ArrayList<String>());
        t.app().get("/c", (req, res) -> {
            Cookie v = req.cookie("v");
            for (String bad : new String[] {"a\r\nSet-Cookie: x=1", "a\nb"}) {
                try {
                    v.setValue(bad);
                } catch (IllegalArgumentException e) {
                    caught.add("value");
                }
                try {
                    v.setPath("/" + bad);
                } catch (IllegalArgumentException e) {
                    caught.add("path");
                }
            }
            res.addCookie(v).end();
        });
        ClientResponse r = t.send("GET", "/c", "Cookie", "v=a");
        assertEquals(Arrays.asList("value", "path", "value", "path"), caught);
        assertEquals(Arrays.asList("v=a"), setCookies(r));
    }
}
