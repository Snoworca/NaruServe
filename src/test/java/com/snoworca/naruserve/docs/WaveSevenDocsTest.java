package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.WaveFiveDocsTest.contains;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.doc;

import java.io.IOException;
import org.junit.Test;

/** Wave 7 docs/api statements, found by fixed phrases. SDS-AC-n cites the wave 7 SDS named on each case. */
// @req FR-WS-004
public final class WaveSevenDocsTest {

    /** The deferred TinyHttpd gap of the 426 answer (FR-WS-004 AC-3). */
    static final String TINYHTTPD_426_PHRASE =
            "over the TinyHttpd adapter the `426` answer carries `Upgrade: websocket` but not `Connection: Upgrade`";

    // SDS-AC-12 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-3.sds.md; FR-WS-004 AC-3
    @Test
    public void sdsAc12_FR_WS_004_AC3_websocketMdStatesTheTinyHttpd426Gap() throws IOException {
        contains("websocket.md", doc("websocket.md"), TINYHTTPD_426_PHRASE, "`ExchangeResponse`",
                "deferred TinyHttpd limitation");
    }
}
