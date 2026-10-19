package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/** HttpExchange.abort() over a real socket: thread safety and the wire result of an abort. */
public class AbortSocketTest {

    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();

    @After
    public void stop() {
        Http.stopQuietly(adapter);
    }

    // FR-ENGINE-001 AC-2: abort() before the response starts closes the connection abnormally, so the client never
    // gets a complete response (no plain 500 page with a matching Content-Length)
    @Test(timeout = 20000)
    public void FR_ENGINE_001_AC2_abortBeforeCommitGivesATruncatedResponse() throws Exception {
        adapter.bind(Http.settings().build(), ex -> ex.abort());
        String response = Http.send(adapter.boundPort(), Http.get("/abort"));
        assertEquals(500, Http.status(response));
        String head = response.substring(0, response.indexOf("\r\n\r\n")).toLowerCase(java.util.Locale.ROOT);
        int at = head.indexOf("content-length: ");
        assertTrue(head, at >= 0);
        int end = head.indexOf("\r\n", at);
        long declared = Long.parseLong(head.substring(at + 16, end < 0 ? head.length() : end).trim());
        assertTrue(response, Http.body(response).length() < declared);
    }

    // FR-ENGINE-001 AC-2: abort() from a third thread while another thread streams the body is thread safe: the
    // writer only ever fails with IOException and the worker hands back a response nobody is still writing
    @Test(timeout = 60000)
    public void FR_ENGINE_001_AC2_abortDuringAConcurrentWriteOnlyFailsTheWriterWithIOException() throws Exception {
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        AtomicReference<CountDownLatch> done = new AtomicReference<>();
        byte[] chunk = new byte[64 * 1024];
        adapter.bind(Http.settings().build(), ex -> new Thread(() -> {
            CountDownLatch latch = done.get();
            try {
                List<Map.Entry<String, String>> h = Collections.emptyList();
                OutputStream out = ex.commit(200, h, -1);
                out.write(chunk);
                new Thread(ex::abort).start();
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    out.write(chunk);
                    out.write('x');
                }
                unexpected.compareAndSet(null, new AssertionError("write never failed"));
            } catch (IOException expected) {
                // the documented failure after abort
            } catch (Throwable t) {
                unexpected.compareAndSet(null, t);
            } finally {
                latch.countDown();
            }
        }).start());
        for (int i = 0; i < 30 && unexpected.get() == null; i++) {
            done.set(new CountDownLatch(1));
            Http.send(adapter.boundPort(), Http.get("/stream"));
            assertTrue("writer thread did not end", done.get().await(10, TimeUnit.SECONDS));
        }
        assertFalse(String.valueOf(unexpected.get()), unexpected.get() != null);
    }
}
