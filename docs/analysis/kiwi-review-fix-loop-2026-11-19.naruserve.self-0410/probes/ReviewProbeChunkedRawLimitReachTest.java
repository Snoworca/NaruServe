package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Probe for ProxyApiTest#ir_PROXY_001_AC1_chunkedClientBodyOverTheRawLimitGives413WithoutReachingTheBackend: the same
 * request (rawBodyLimit 8, chunked 64-byte body) against a backend that counts accepted connections and records the
 * bytes it received. The test asserts "the backend saw no request" with RawBackend.requestCount(), which only counts
 * requests whose body ended; this probe expects no backend connection, as the test's name says.
 */
public class ReviewProbeChunkedRawLimitReachTest {

    @Test(timeout = 30000)
    public void chunkedOverTheRawLimitDoesNotReachTheBackend() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        AtomicReference<String> seen = new AtomicReference<>("");
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            Thread acceptor = new Thread(() -> {
                try (Socket s = server.accept()) {
                    accepted.incrementAndGet();
                    s.setSoTimeout(5000);
                    InputStream in = s.getInputStream();
                    ByteArrayOutputStream got = new ByteArrayOutputStream();
                    byte[] b = new byte[1024];
                    try {
                        for (int n; (n = in.read(b)) >= 0; ) {
                            got.write(b, 0, n);
                            seen.set(new String(got.toByteArray(), StandardCharsets.ISO_8859_1));
                        }
                    } catch (java.io.IOException ignored) {
                        // reset or timeout ends the recording
                    }
                } catch (java.io.IOException ignored) {
                    // server closed
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
            TestApp t = TestApp.create(adapter);
            RestApp app = t.app();
            app.rawBodyLimit(8);
            app.use("/api/orders", Proxy.to("http://127.0.0.1:" + server.getLocalPort()));
            t.start();
            try {
                ClientResponse r = adapter.send(ClientRequest.of("POST", "/api/orders/x")
                        .header("Transfer-Encoding", "chunked").body(new byte[64]));
                assertEquals(413, r.status());
                acceptor.join(3000);
                System.out.println("PROBE accepted=" + accepted.get() + " received=" + seen.get().replace("\r\n", "\\r\\n"));
                assertEquals("backend connections (bytes received: " + seen.get().replace("\r\n", "\\r\\n") + ")", 0,
                        accepted.get());
            } finally {
                app.shutdown(2, TimeUnit.SECONDS);
            }
        }
    }
}
