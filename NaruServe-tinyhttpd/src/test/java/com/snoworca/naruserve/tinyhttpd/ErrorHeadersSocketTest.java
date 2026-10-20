package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * HttpException response headers over a TinyHttpd loopback socket. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-2.sds.md.
 */
public class ErrorHeadersSocketTest {

    private final RecordingLogger log = new RecordingLogger();
    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();
    private RestApp app;

    @After
    public void stop() {
        if (app != null) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        Http.stopQuietly(adapter);
    }

    // SDS-AC-9; IR-HTTP-008 AC-6: a thrown 503 with Retry-After and Connection: close reaches the client with both
    // headers, and the server closes the keep-alive connection after the response
    @Test(timeout = 30000)
    public void sdsAc9_IR_HTTP_008_AC6_a503WithRetryAfterAndConnectionCloseClosesTheConnection() throws Exception {
        app = Http.app(adapter, log);
        app.get("/busy", (req, res) -> {
            throw new HttpException(503).withHeader("Retry-After", "1").withHeader("Connection", "close");
        });
        int port = app.listen(0).port();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            // keep-alive by default: no Connection header from the client, so only the exception's header closes it
            out.write("GET /busy HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String response;
            try {
                response = Http.readAll(s.getInputStream());
            } catch (SocketTimeoutException e) {
                fail("the server kept the connection open after the 503");
                return;
            }
            assertEquals(response, 503, Http.status(response));
            assertEquals(response, "1", Http.header(response, "Retry-After"));
            assertEquals(response, "close", Http.header(response, "Connection"));
            int length = Integer.parseInt(Http.header(response, "Content-Length"));
            byte[] body = Http.body(response).getBytes(StandardCharsets.ISO_8859_1);
            assertEquals(response, length, body.length);
            assertTrue(response, response.indexOf("HTTP/1.1 ", 1) < 0);
        }
    }
}
