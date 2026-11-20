package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Probe for ProxyRetryTest#fr_PROXY_018_AC2_retriesKeepTheOneSlotAndCloseEveryRetriedConnection, which claims that
 * "every retried connection is closed by the proxy". Its only oracle is RawBackend.requestCount(). Here a client that
 * behaves like a leaking proxy (reads the 503 head and never closes its socket) talks to the same RawBackend: if the
 * backend kept the connection open until the peer closed it, a leak would be observable; this probe expects that and
 * fails, because RawBackend closes every connection itself right after the response, so the test passes whether or
 * not the proxy closes the retried connections.
 */
public class ReviewProbeRawBackendCloseTest {

    @Test(timeout = 30000)
    public void aClientThatNeverClosesIsIndistinguishableFromOneThatDoes() throws Exception {
        try (RawBackend backend = RawBackend.answering(RawBackend.response(503, "busy"))) {
            Socket leaked1 = new Socket("127.0.0.1", backend.port());
            Socket leaked2 = new Socket("127.0.0.1", backend.port());
            Socket leaked3 = new Socket("127.0.0.1", backend.port());
            for (Socket s : new Socket[] {leaked1, leaked2, leaked3}) {
                s.setSoTimeout(1000);
                OutputStream out = s.getOutputStream();
                out.write("GET /x HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
            // Three never-closed connections are all served and counted, exactly like three closed ones.
            InputStream in = leaked1.getInputStream();
            byte[] buf = new byte[256];
            int total = 0;
            try {
                for (int n; (n = in.read(buf)) >= 0; ) {
                    total += n;
                }
                fail("RawBackend closed the connection itself after " + total + " response bytes although the "
                        + "client never closed it; requestCount=" + backend.requestCount()
                        + ", so a proxy that leaks retried connections passes the test");
            } catch (SocketTimeoutException stillOpen) {
                // what an oracle able to see a leak would need
            }
            assertEquals(3, backend.requestCount());
        }
    }
}
