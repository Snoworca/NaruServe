package com.snoworca.naruserve;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** Cookie and SameSite. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-3.sds.md. */
public class CookieTest {

    private static void assertRejected(String attribute, Runnable r) {
        try {
            r.run();
            fail("expected IllegalArgumentException naming " + attribute);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(attribute));
        }
    }

    // IR-COOKIE-001 AC-1
    @Test
    public void IR_COOKIE_001_AC1_publicFinalWithOneConstructorAndAFixedName() throws Exception {
        assertTrue(Modifier.isPublic(Cookie.class.getModifiers()));
        assertTrue(Modifier.isFinal(Cookie.class.getModifiers()));
        Constructor<?>[] ctors = Cookie.class.getConstructors();
        assertEquals(1, ctors.length);
        assertArrayEquals(new Class<?>[] {String.class, String.class}, ctors[0].getParameterTypes());
        assertEquals(String.class, Cookie.class.getMethod("getName").getReturnType());
        for (java.lang.reflect.Method m : Cookie.class.getMethods()) {
            assertFalse(m.getName().equals("setName"));
        }
        Cookie c = new Cookie("sid", "abc");
        assertEquals("sid", c.getName());
        assertEquals("abc", c.getValue());
        try {
            new Cookie(null, "v");
            fail("null name");
        } catch (NullPointerException expected) {
            // ok
        }
        try {
            new Cookie("n", null);
            fail("null value");
        } catch (NullPointerException expected) {
            // ok
        }
    }

    // SDS-AC-1; IR-COOKIE-001 AC-2, AC-4
    @Test
    public void sdsAc1_IR_COOKIE_001_AC2_AC4_newCookieDefaultsAndAccessorsRoundTrip() {
        Cookie c = new Cookie("sid", "abc");
        assertNull(c.getPath());
        assertNull(c.getDomain());
        assertNull(c.getSameSite());
        assertFalse(c.getSecure());
        assertFalse(c.isHttpOnly());
        assertEquals(-1, c.getMaxAge());

        c.setValue("x");
        c.setPath("/");
        c.setDomain("example.com");
        c.setSecure(true);
        c.setHttpOnly(true);
        c.setSameSite(SameSite.LAX);
        assertEquals("x", c.getValue());
        assertEquals("/", c.getPath());
        assertEquals("example.com", c.getDomain());
        assertTrue(c.getSecure());
        assertTrue(c.isHttpOnly());
        assertEquals(SameSite.LAX, c.getSameSite());
        for (int v : new int[] {Integer.MIN_VALUE, -5, -1, 0, 1, 3600, Integer.MAX_VALUE}) {
            c.setMaxAge(v);
            assertEquals(v, c.getMaxAge());
        }
        c.setPath(null);
        c.setDomain(null);
        c.setSameSite(null);
        assertNull(c.getPath());
        assertNull(c.getDomain());
        assertNull(c.getSameSite());
    }

    // SDS-AC-1; IR-COOKIE-001 AC-2: exact accessor signatures and nullable positions
    @Test
    public void sdsAc1_IR_COOKIE_001_AC2_accessorSignaturesAndNullablePositions() throws Exception {
        Set<String> nullable = new TreeSet<>();
        Set<String> sigs = new TreeSet<>();
        for (java.lang.reflect.Method m : Cookie.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic()) {
                continue;
            }
            sigs.add(m.getName() + Arrays.toString(m.getParameterTypes()) + "->" + m.getReturnType().getSimpleName());
            if (m.getAnnotatedReturnType().isAnnotationPresent(Nullable.class)) {
                nullable.add(m.getName() + "#return");
            }
            for (int i = 0; i < m.getParameterCount(); i++) {
                if (m.getAnnotatedParameterTypes()[i].isAnnotationPresent(Nullable.class)) {
                    nullable.add(m.getName() + "#p" + i);
                }
            }
        }
        assertEquals(new TreeSet<>(Arrays.asList(
                "getName[]->String",
                "getValue[]->String", "setValue[class java.lang.String]->void",
                "getPath[]->String", "setPath[class java.lang.String]->void",
                "getDomain[]->String", "setDomain[class java.lang.String]->void",
                "getMaxAge[]->int", "setMaxAge[int]->void",
                "getSecure[]->boolean", "setSecure[boolean]->void",
                "isHttpOnly[]->boolean", "setHttpOnly[boolean]->void",
                "getSameSite[]->SameSite", "setSameSite[class com.snoworca.naruserve.SameSite]->void")), sigs);
        assertEquals(new TreeSet<>(Arrays.asList("getPath#return", "setPath#p0", "getDomain#return", "setDomain#p0",
                "getSameSite#return", "setSameSite#p0")), nullable);
    }

    // SDS-AC-1; IR-COOKIE-001 AC-3
    @Test
    public void sdsAc1_IR_COOKIE_001_AC3_sameSiteValuesInOrder() {
        assertTrue(SameSite.class.isEnum());
        assertTrue(Modifier.isPublic(SameSite.class.getModifiers()));
        assertArrayEquals(new SameSite[] {SameSite.STRICT, SameSite.LAX, SameSite.NONE}, SameSite.values());
    }

    // SDS-AC-2; IR-COOKIE-001 AC-5
    @Test
    public void sdsAc2_IR_COOKIE_001_AC5_namesOutsideTheTokenGrammarAreRejected() {
        for (String bad : new String[] {"", "a b", "a=b", "a;b", "a,b", "a\rb", "a\nb", "a\"b", "a(b", "é"}) {
            assertRejected("name", () -> new Cookie(bad, "v"));
        }
        assertEquals("a!#$%&'*+-.^_`|~9", new Cookie("a!#$%&'*+-.^_`|~9", "v").getName());
    }

    // SDS-AC-2; IR-COOKIE-001 AC-5
    @Test
    public void sdsAc2_IR_COOKIE_001_AC5_valuesOutsideCookieOctetAreRejectedByConstructorAndSetter() {
        for (String bad : new String[] {"a b", "a;b", "a,b", "a\rb", "a\nb", "a\\b", "a\u0001b", "a\u007fb",
                "é", "\"", "a\"b", "\"a\"b\"", "\"a b\"", "\"\"\"\""}) {
            assertRejected("value", () -> new Cookie("n", bad));
            Cookie c = new Cookie("n", "ok");
            assertRejected("value", () -> c.setValue(bad));
            assertEquals("ok", c.getValue());
        }
        assertEquals("", new Cookie("sid", "").getValue());
        assertEquals("\"abc\"", new Cookie("sid", "\"abc\"").getValue());
        assertEquals("\"\"", new Cookie("sid", "\"\"").getValue());
        assertEquals("a%2Cb!#$&'()*+-./:<=>?@[]^_`{|}~", new Cookie("p", "a%2Cb!#$&'()*+-./:<=>?@[]^_`{|}~").getValue());
    }

    // SDS-AC-2; IR-COOKIE-001 AC-5
    @Test
    public void sdsAc2_IR_COOKIE_001_AC5_pathAndDomainRejectCrLfSemicolonAndControls() {
        for (String bad : new String[] {"/a\r", "/a\n", "/a;b", "/a\u0000", "/a\t", "/a\u007f", "/a\u0085"}) {
            Cookie c = new Cookie("n", "v");
            assertRejected("path", () -> c.setPath(bad));
            assertRejected("domain", () -> c.setDomain(bad));
            assertNull(c.getPath());
            assertNull(c.getDomain());
        }
    }

    // IR-COOKIE-001 AC-6: no public or protected member beyond AC-1, AC-2 and Object
    @Test
    public void IR_COOKIE_001_AC6_noExtraPublicOrProtectedMembers() throws Exception {
        for (Member m : Cookie.class.getDeclaredFields()) {
            int mods = m.getModifiers();
            assertFalse(m.getName(), Modifier.isPublic(mods) || Modifier.isProtected(mods));
        }
        for (Member m : Cookie.class.getDeclaredMethods()) {
            assertFalse(m.getName(), Modifier.isProtected(m.getModifiers()));
        }
        for (Member m : Cookie.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isProtected(m.getModifiers()));
        }
        java.lang.reflect.Method factory = Cookie.class.getDeclaredMethod("unvalidated", String.class, String.class);
        int mods = factory.getModifiers();
        assertTrue(Modifier.isStatic(mods));
        assertFalse(Modifier.isPublic(mods) || Modifier.isProtected(mods) || Modifier.isPrivate(mods));
        assertEquals(Cookie.class, factory.getReturnType());
    }
}
