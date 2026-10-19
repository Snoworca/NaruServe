package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The one strict UTF-8 percent decoder shared by the path validator and the request (w3-D-001). */
public class PercentDecoderTest {

    // SDS-AC-17 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-ROUTE-005; FR-HTTP-009; FR-HTTP-025 AC-2
    @Test
    public void pathFormKeepsPlusAndQueryFormMapsItToSpace() {
        assertEquals("a+b é", PercentDecoder.decode("a+b%20%C3%A9", false));
        assertEquals("a b é", PercentDecoder.decode("a+b%20%C3%A9", true));
        assertEquals("a b", PercentDecoder.decode("a+b", true));
        assertEquals("plain", PercentDecoder.decode("plain", false));
    }

    // SDS-AC-16 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-ROUTE-005; FR-HTTP-025 AC-3
    @Test
    public void malformedInputIsNull() {
        assertNull(PercentDecoder.decode("%", false));
        assertNull(PercentDecoder.decode("%4", true));
        assertNull(PercentDecoder.decode("%G1", false));
        assertNull(PercentDecoder.decode("%C3", true)); // truncated UTF-8 sequence
        assertNull(PercentDecoder.decode("%FF", false));
    }
}
