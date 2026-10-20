package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class HttpExceptionTest {

    // SDS-AC-9; IR-APP-003 AC-2
    @Test
    public void sdsAc9_IR_APP_003_AC2_statusOutside400To599IsRefusedNamingTheStatus() {
        for (int bad : new int[] {399, 600, 200, -1}) {
            try {
                new HttpException(bad);
                fail("status " + bad);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(String.valueOf(bad)));
            }
            try {
                new HttpException(bad, "m", new RuntimeException());
                fail("status " + bad);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(String.valueOf(bad)));
            }
        }
    }

    // SDS-AC-9; IR-APP-003 AC-2
    @Test
    public void sdsAc9_IR_APP_003_AC2_boundaryStatusesAreAcceptedAndKept() {
        assertEquals(400, new HttpException(400).status());
        assertEquals(599, new HttpException(599).status());
        HttpException e = new HttpException(422, "bad field");
        assertEquals(422, e.status());
        assertEquals("bad field", e.getMessage());
        Throwable cause = new IllegalStateException("c");
        HttpException withCause = new HttpException(503, "down", cause);
        assertSame(cause, withCause.getCause());
        assertNull(new HttpException(404).getMessage());
        assertTrue(RuntimeException.class.isAssignableFrom(HttpException.class));
    }

    // SDS-AC-10; IR-APP-003 AC-3
    @Test
    public void sdsAc10_IR_APP_003_AC3_codeIsTheStandardCodeOfTheStatus() {
        assertEquals("BAD_REQUEST", new HttpException(400).code());
        assertEquals("UNPROCESSABLE_ENTITY", new HttpException(422).code());
        assertEquals("NOT_FOUND", new HttpException(404).code());
        assertEquals("INTERNAL_ERROR", new HttpException(500).code());
        assertEquals("HTTP_418", new HttpException(418).code());
    }

    // SDS-AC-10; IR-APP-003 AC-4
    @Test
    public void sdsAc10_IR_APP_003_AC4_unsupportedMediaTypeIs415() {
        for (HttpException e : new HttpException[] {new UnsupportedMediaTypeException(),
                new UnsupportedMediaTypeException("m")}) {
            assertEquals(415, e.status());
            assertEquals("UNSUPPORTED_MEDIA_TYPE", e.code());
        }
        assertEquals("m", new UnsupportedMediaTypeException("m").getMessage());
    }

    private static List<Map.Entry<String, String>> entries(String... nameValue) {
        List<Map.Entry<String, String>> out = new java.util.ArrayList<>();
        for (int i = 0; i < nameValue.length; i += 2) {
            out.add(new SimpleImmutableEntry<>(nameValue[i], nameValue[i + 1]));
        }
        return out;
    }

    // IR-HTTP-008 AC-1
    @Test
    public void IR_HTTP_008_AC1_withHeaderAndHeadersAreDeclaredAndTheOldSignaturesKept() throws Exception {
        java.lang.reflect.Method with = HttpException.class.getMethod("withHeader", String.class, String.class);
        assertEquals(HttpException.class, with.getReturnType());
        java.lang.reflect.Method headers = HttpException.class.getMethod("headers");
        assertEquals("java.util.List<java.util.Map$Entry<java.lang.String, java.lang.String>>",
                headers.getGenericReturnType().getTypeName());
        HttpException.class.getConstructor(int.class);
        HttpException.class.getConstructor(int.class, String.class);
        HttpException.class.getConstructor(int.class, String.class, Throwable.class);
        assertEquals(int.class, HttpException.class.getMethod("status").getReturnType());
        assertEquals(String.class, HttpException.class.getMethod("code").getReturnType());
        assertEquals(Collections.emptyList(), new HttpException(400).headers());
        HttpException e = new HttpException(503);
        assertSame(e, e.withHeader("Retry-After", "1"));
    }

    // SDS-AC-8; IR-HTTP-008 AC-2
    @Test
    public void sdsAc8_IR_HTTP_008_AC2_headersKeepOrderAndDuplicatesAndAreUnmodifiable() {
        HttpException e = new HttpException(503).withHeader("Retry-After", "1").withHeader("Connection", "close")
                .withHeader("Retry-After", "2");
        assertEquals(entries("Retry-After", "1", "Connection", "close", "Retry-After", "2"), e.headers());
        try {
            e.headers().add(new SimpleImmutableEntry<>("X", "y"));
            fail("add accepted");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            e.headers().remove(0);
            fail("remove accepted");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        assertEquals(3, e.headers().size());
    }

    // SDS-AC-8; IR-HTTP-008 AC-2: the headers survive Java serialization with the exception
    @Test
    public void sdsAc8_IR_HTTP_008_AC2_anExceptionWithHeadersSerializesAndKeepsThem() throws Exception {
        HttpException e = new HttpException(426, "upgrade").withHeader("Sec-WebSocket-Version", "13");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(e);
        }
        HttpException back;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            back = (HttpException) in.readObject();
        }
        assertEquals(426, back.status());
        assertEquals("upgrade", back.getMessage());
        assertEquals(entries("Sec-WebSocket-Version", "13"), back.headers());
    }

    // SDS-AC-7; IR-HTTP-008 AC-3
    @Test
    public void sdsAc7_IR_HTTP_008_AC3_aBadHeaderIsRefusedLikeResHeaderAndLeavesHeadersUnchanged() {
        HttpException e = new HttpException(503).withHeader("Retry-After", "1");
        String[][] bad = {
            {"X-A\r", "v"}, {"X-A\n", "v"}, {"X-A", "a\rb"}, {"X-A", "a\nb"}, {"a b", "v"}, {"", "v"},
            {"X-A", "\u0100"}, {"X-A", "a\u0001b"},
        };
        for (String[] nv : bad) {
            try {
                e.withHeader(nv[0], nv[1]);
                fail("accepted " + Arrays.toString(nv));
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
        for (String[] nv : new String[][] {{null, "v"}, {"X-A", null}}) {
            try {
                e.withHeader(nv[0], nv[1]);
                fail("accepted " + Arrays.toString(nv));
            } catch (NullPointerException expected) {
                // expected
            }
        }
        assertEquals(entries("Retry-After", "1"), e.headers());
        e.withHeader("X-Tab", "a\tb\u00ff");
        assertEquals(2, e.headers().size());
    }
}
