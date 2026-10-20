package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.UnsupportedMediaTypeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** The core-built standard error body. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md. */
public class StandardErrorsTest {

    // SDS-AC-8; FR-APP-006 AC-1
    @Test
    public void sdsAc8_FR_APP_006_AC1_jsonIsExactlyTheThreeFieldsWithoutWhitespace() {
        byte[] body = StandardErrors.json(500, "Internal Server Error", "0123456789abcdef");
        assertEquals("{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"Internal Server Error\","
                + "\"requestId\":\"0123456789abcdef\"}}", new String(body, StandardCharsets.UTF_8));
    }

    // SDS-AC-9; FR-APP-006 AC-2
    @Test
    public void sdsAc9_FR_APP_006_AC2_everyListedStatusHasItsCodeAndOthersAreHttpStatus() {
        int[] statuses = {400, 404, 405, 413, 415, 422, 426, 500, 501, 503, 418, 599};
        String[] codes = {"BAD_REQUEST", "NOT_FOUND", "METHOD_NOT_ALLOWED", "PAYLOAD_TOO_LARGE",
            "UNSUPPORTED_MEDIA_TYPE", "UNPROCESSABLE_ENTITY", "UPGRADE_REQUIRED", "INTERNAL_ERROR", "NOT_IMPLEMENTED",
            "SERVICE_UNAVAILABLE", "HTTP_418", "HTTP_599"};
        for (int i = 0; i < statuses.length; i++) {
            assertEquals(String.valueOf(statuses[i]), codes[i], StandardErrors.code(statuses[i]));
        }
        assertEquals("Error", StandardErrors.reason(418));
    }

    // FR-APP-006 AC-3: the message is the status reason phrase, also for a status that has no named error code
    @Test
    public void fr_APP_006_AC3_everyStandardErrorStatusHasItsReasonPhrase() {
        int[] statuses = {401, 402, 403, 406, 407, 409, 410, 411, 412, 414, 416, 417, 423, 424, 428, 429, 431, 451,
            502, 504, 505, 507};
        String[] phrases = {"Unauthorized", "Payment Required", "Forbidden", "Not Acceptable",
            "Proxy Authentication Required", "Conflict", "Gone", "Length Required", "Precondition Failed",
            "URI Too Long", "Range Not Satisfiable", "Expectation Failed", "Locked", "Failed Dependency",
            "Precondition Required", "Too Many Requests", "Request Header Fields Too Large",
            "Unavailable For Legal Reasons", "Bad Gateway", "Gateway Timeout", "HTTP Version Not Supported",
            "Insufficient Storage"};
        for (int i = 0; i < statuses.length; i++) {
            assertEquals(String.valueOf(statuses[i]), phrases[i], StandardErrors.reason(statuses[i]));
        }
        assertEquals("Forbidden", StandardErrors.messageOf(new HttpException(403), 403));
        assertEquals("Error", StandardErrors.reason(599));
    }

    // SDS-AC-10; FR-APP-006 AC-3, FR-APP-006 AC-4
    @Test
    public void sdsAc10_FR_APP_006_AC3_AC4_statusAndMessageOfAnError() {
        assertEquals(400, StandardErrors.statusOf(new HttpException(400)));
        assertEquals(415, StandardErrors.statusOf(new UnsupportedMediaTypeException()));
        assertEquals(500, StandardErrors.statusOf(new IOException("x")));
        assertEquals(500, StandardErrors.statusOf(new IllegalStateException("x")));
        assertEquals("Not Found", StandardErrors.messageOf(new HttpException(404), 404));
        assertEquals("name required", StandardErrors.messageOf(new HttpException(422, "name required"), 422));
        assertEquals("Unsupported Media Type", StandardErrors.messageOf(new UnsupportedMediaTypeException(), 415));
        assertEquals("Internal Server Error", StandardErrors.messageOf(new HttpException(500, "leak"), 500));
        assertEquals("Internal Server Error", StandardErrors.messageOf(new RuntimeException("secret"), 500));
    }

    // SDS-AC-13; FR-APP-006 AC-8
    @Test
    public void sdsAc13_FR_APP_006_AC8_messageIsEscapedPerRfc8259AndNonAsciiStaysLiteral() {
        assertEquals("a\\\"b\\\\c\\nd\\u0001\\t한", StandardErrors.escape("a\"b\\c\nd\u0001\t한"));
        byte[] body = StandardErrors.json(400, "q\"\u001f한", "r");
        assertArrayEquals(("{\"error\":{\"code\":\"BAD_REQUEST\",\"message\":\"q\\\"\\u001f한\",\"requestId\":\"r\"}}")
                .getBytes(StandardCharsets.UTF_8), body);
    }

    // SDS-AC-7 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-4.sds.md; IR-ENGINE-002 AC-5, FR-ENGINE-005 AC-2
    @Test
    public void sdsAc7_IR_ENGINE_002_AC5_rendererGives421And408BodiesWithFreshRequestIds() {
        com.snoworca.naruserve.spi.ErrorRenderer r = StandardErrors.renderer();
        String a = r.render(421);
        String b = r.render(421);
        String pattern = "\\{\"error\":\\{\"code\":\"HTTP_421\",\"message\":\"Misdirected Request\","
                + "\"requestId\":\"[0-9a-f]{16}\"}}";
        org.junit.Assert.assertTrue(a, a.matches(pattern));
        org.junit.Assert.assertTrue(b, b.matches(pattern));
        org.junit.Assert.assertNotEquals(a, b);
        String t = r.render(408);
        org.junit.Assert.assertTrue(t, t.matches("\\{\"error\":\\{\"code\":\"HTTP_408\","
                + "\"message\":\"Request Timeout\",\"requestId\":\"[0-9a-f]{16}\"}}"));
        assertEquals("Request Timeout", StandardErrors.reason(408));
        assertEquals("Misdirected Request", StandardErrors.reason(421));
    }
}
