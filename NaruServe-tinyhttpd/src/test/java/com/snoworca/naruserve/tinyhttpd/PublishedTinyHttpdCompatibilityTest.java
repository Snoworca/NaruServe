package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.*;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.websocket.WebSocket;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** OPS-BUILD-001 AC-4, CON-BUILD-006 AC-1: run only in tinyHttpdFromMaven mode. */
// @req CON-BUILD-018 AC-1 AC-2 AC-3
public class PublishedTinyHttpdCompatibilityTest {
    // Binary origins make a local-source substitution impossible to mistake for a publication check.
    @Test
    public void OPS_BUILD_001_AC4_pinnedJarContainsRequiredApis() throws Exception {
        for (Class<?> type : new Class<?>[] {Request.class, Response.class}) {
            URL source = type.getProtectionDomain().getCodeSource().getLocation();
            assertTrue("must load the release JAR, not local classes: " + source,
                    source.getPath().endsWith("tinyhttpd-core-3.1.1.jar"));
        }
        URL websocket = WebSocket.class.getProtectionDomain().getCodeSource().getLocation();
        assertTrue("must load the pinned WebSocket release JAR: " + websocket,
                websocket.getPath().endsWith("tinyhttpd-websocket-3.1.1.jar"));
        assertNotNull(Request.class.getMethod("getHeaderEntries"));
        assertTrue(Response.class.getMethod("abort").isDefault());
        Class<?> policy=Class.forName("com.snoworca.tinyhttpd.RequestTargetPolicy");
        assertEquals("RAW",policy.getField("RAW").get(null).toString());
        assertEquals("STRICT",policy.getField("STRICT").get(null).toString());
        assertNotNull(com.snoworca.tinyhttpd.TinyHttpd.Builder.class.getMethod("requestTargetPolicy",policy));
        Object defaultConfig=com.snoworca.tinyhttpd.TinyHttpd.builder().port(0).handler((request,response)->{}).build().getConfig();
        assertEquals("upstream safety remains opt-in", "STRICT",defaultConfig.getClass().getField("requestTargetPolicy").get(defaultConfig).toString());
    }

    // IR-HTTP-003 AC-1/AC-2, FR-ENGINE-011 AC-2/AC-7
    @Test
    public void IR_HTTP_003_AC1_AC2_binaryJarPreservesHeadersAndCancelsGetAndHead() throws Exception {
        TinyHttpdAdapter adapter = new TinyHttpdAdapter();
        AtomicReference<String> received = new AtomicReference<>();
        try {
            adapter.bind(RawTargetSocketTest.Http.settings().build(), ex -> {
                StringBuilder entries = new StringBuilder();
                for (java.util.Map.Entry<String, String> entry : ex.headers()) {
                    entries.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
                }
                received.set(entries.toString());
                ex.abort();
            });
            for (String method : new String[] {"GET", "HEAD"}) {
                String request = method + " / HTTP/1.1\r\nHost: 127.0.0.1\r\nA: 1\r\nB: 2\r\na: 3\r\nConnection: close\r\n\r\n";
                assertEquals("abort must emit no synthetic response", "",
                        RawTargetSocketTest.Http.send(adapter.boundPort(), request));
                assertEquals("Host=127.0.0.1;A=1;B=2;a=3;Connection=close;", received.get());
            }
        } finally {
            RawTargetSocketTest.Http.stopQuietly(adapter);
        }
    }
}
