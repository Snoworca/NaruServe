package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
}
