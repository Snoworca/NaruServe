package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.SseEvent;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** The event-stream text form. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-9.sds.md. */
public class SseWireTest {

    // SDS-AC-9; FR-SSE-001 AC-4
    @Test
    public void sdsAc9_FR_SSE_001_AC4_dataIsSplitAtCrLfCrAndLfIntoOneDataLineEach() {
        assertEquals("event: m\ndata: a\ndata: b\ndata: c\ndata: d\n\n", SseWire.encode("m", null, -1, "a\nb\r\nc\rd"));
        assertEquals("event: m\ndata: a\ndata: \n\n", SseWire.encode("m", null, -1, "a\n"));
        assertEquals("event: m\ndata: \ndata: \ndata: \n\n", SseWire.encode("m", null, -1, "\r\n\n"));
    }

    // SDS-AC-10; FR-SSE-001 AC-5
    @Test
    public void sdsAc10_FR_SSE_001_AC5_fieldsAreWrittenInTheFixedOrderWhateverTheSetterOrder() {
        String expected = "event: progress\nid: 100\nretry: 3000\ndata: {\"progress\":50}\n\n";
        assertEquals(expected, SseWire.wire(SseEvent.event("progress").id("100").data("{\"progress\":50}").retry(3000)));
        assertEquals(expected, SseWire.wire(SseEvent.event("progress").retry(3000).id("100").data("{\"progress\":50}")));
    }

    // SDS-AC-10; FR-SSE-001 AC-5
    @Test
    public void sdsAc10_FR_SSE_001_AC5_unsetIdAndRetryWriteNoLineAndNullDataWritesOneEmptyDataLine() {
        assertEquals("event: x\ndata: \n\n", SseWire.wire(SseEvent.event("x")));
        assertEquals("event: x\ndata: \n\n", SseWire.wire(SseEvent.event("x").data("y").data(null)));
        assertEquals("event: x\nretry: 0\ndata: \n\n", SseWire.wire(SseEvent.event("x").retry(0)));
        assertEquals("data: \n\n", SseWire.encode(null, null, -1, null));
    }

    // SDS-AC-11; FR-SSE-001 AC-6
    @Test
    public void sdsAc11_FR_SSE_001_AC6_checkFieldNamesTheFieldAndRefusesCrLfAndNul() {
        SseWire.checkField("event", "fine name");
        for (String bad : new String[] {"\r", "x\n", "\u0000y"}) {
            try {
                SseWire.checkField("id", bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException e) {
                assertEquals("id must not contain CR, LF or NUL", e.getMessage());
            }
        }
    }

    // SDS-AC-13; FR-SSE-001 AC-9
    @Test
    public void sdsAc13_FR_SSE_001_AC9_encodedTextBecomesUtf8Bytes() {
        byte[] expected = {'d', 'a', 't', 'a', ':', ' ', (byte) 0xED, (byte) 0x95, (byte) 0x9C, (byte) 0xEA,
            (byte) 0xB8, (byte) 0x80, '\n', '\n'};
        assertArrayEquals(expected, SseWire.encode(null, null, -1, "한글").getBytes(StandardCharsets.UTF_8));
    }
}
