package com.snoworca.naruserve.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.internal.DefaultResponse;
import com.snoworca.naruserve.testing.RecordingExchange;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** The public header value predicate and the {@code res.header} value check that uses it. */
public class HttpHeaderValuesTest {

    @Test // IR-HTTP-012 AC-1
    public void irHttp012Ac1_isFieldValueIsPublicStaticBooleanBesideIsTokenWithoutNonNull() throws Exception {
        Method token = HttpHeaderValues.class.getMethod("isToken", String.class);
        assertTrue(Modifier.isPublic(token.getModifiers()));
        assertTrue(Modifier.isStatic(token.getModifiers()));

        Method value = HttpHeaderValues.class.getMethod("isFieldValue", String.class);
        assertEquals(HttpHeaderValues.class, value.getDeclaringClass());
        assertTrue(Modifier.isPublic(value.getModifiers()));
        assertTrue(Modifier.isStatic(value.getModifiers()));
        assertEquals(boolean.class, value.getReturnType());
        assertFalse(hasNonNull(value.getDeclaredAnnotations()));
        assertFalse(hasNonNull(value.getAnnotatedReturnType().getDeclaredAnnotations()));
        assertFalse(hasNonNull(value.getParameterAnnotations()[0]));
        assertFalse(hasNonNull(value.getAnnotatedParameterTypes()[0].getDeclaredAnnotations()));
    }

    @Test // IR-HTTP-012 AC-2
    public void irHttp012Ac2_emptyAndHtabPassWhileCrLfNulDelAndU0100Fail() {
        assertTrue(HttpHeaderValues.isFieldValue(""));
        assertTrue(HttpHeaderValues.isFieldValue("a\tb"));
        assertTrue(HttpHeaderValues.isFieldValue("\t"));
        assertFalse(HttpHeaderValues.isFieldValue("a\rb"));
        assertFalse(HttpHeaderValues.isFieldValue("a\nb"));
        assertFalse(HttpHeaderValues.isFieldValue("a\u0000b"));
        assertFalse(HttpHeaderValues.isFieldValue("a\u007Fb"));
        assertFalse(HttpHeaderValues.isFieldValue("aĀb"));
    }

    @Test // IR-HTTP-012 AC-3
    public void irHttp012Ac3_printableAsciiSpaceAndU00FFPassWhileU0001AndU001FFail() {
        StringBuilder printable = new StringBuilder();
        for (char c = 0x20; c <= 0x7E; c++) {
            printable.append(c);
        }
        assertTrue(HttpHeaderValues.isFieldValue(printable.toString()));
        assertTrue(HttpHeaderValues.isFieldValue("text/plain; charset=utf-8"));
        assertTrue(HttpHeaderValues.isFieldValue("cafÿ"));
        assertFalse(HttpHeaderValues.isFieldValue("a\u0001b"));
        assertFalse(HttpHeaderValues.isFieldValue("a\u001Fb"));
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_resHeaderThrowsExactlyWhenIsFieldValueIsFalseForEveryCharToU017F() throws Exception {
        Method internal = DefaultResponse.class.getDeclaredMethod("isFieldValue", String.class);
        internal.setAccessible(true);
        int rejected = 0;
        for (int code = 0x0000; code <= 0x017F; code++) {
            String value = "a" + (char) code + "b";
            boolean expected = HttpHeaderValues.isFieldValue(value);
            assertEquals("U+" + Integer.toHexString(code), expected, internal.invoke(null, value));
            DefaultResponse res = new DefaultResponse(new RecordingExchange("GET", "/h"), false);
            boolean threw;
            try {
                res.header("X", value);
                threw = false;
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            assertEquals("U+" + Integer.toHexString(code), !expected, threw);
            if (threw) {
                rejected++;
            }
        }
        // C0 controls but HTAB, DEL, then U+0100 to U+017F
        assertEquals(0x20 - 1 + 1 + (0x017F - 0x0100 + 1), rejected);
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_defaultResponseValueCheckDelegatesWithoutARuleOfItsOwn() throws Exception {
        File source = new File(System.getProperty("user.dir"),
                "src/main/java/com/snoworca/naruserve/internal/DefaultResponse.java");
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        String decl = "static boolean isFieldValue(String s) {";
        int start = text.indexOf(decl);
        assertTrue("DefaultResponse declares isFieldValue", start >= 0);
        assertEquals("one isFieldValue in DefaultResponse", -1, text.indexOf(decl, start + 1));
        int end = text.indexOf("\n    }", start);
        String body = text.substring(start + decl.length(), end);
        assertTrue(body, body.contains("HttpHeaderValues.isFieldValue(s)"));
        assertFalse(body, body.contains("for (") || body.contains("charAt"));
        assertFalse("no copy of the U+00FF bound left in DefaultResponse", text.contains("> 0xFF"));
    }

    private static boolean hasNonNull(Annotation[] annotations) {
        for (Annotation a : annotations) {
            if (a.annotationType().getSimpleName().equals("NonNull")) {
                return true;
            }
        }
        return false;
    }
}
