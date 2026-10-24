package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import com.snoworca.naruserve.spi.HttpExchange;
import org.junit.After;
import org.junit.Test;

/** HttpExchange.abort() over a real socket: thread safety and the wire result of an abort. */
public class AbortSocketTest {

    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();

    @After
    public void stop() {
        Http.stopQuietly(adapter);
    }

    // IR-HTTP-003 AC-2: a cancellation must not wait for the transport write timeout.
    @Test(timeout = 20000)
    public void IR_HTTP_003_AC2_abortUnblocksAStalledWriter() throws Exception {
        AtomicReference<HttpExchange> exchange = new AtomicReference<>();
        AtomicReference<Thread> writerThread = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong written = new AtomicLong();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        adapter.bind(Http.settings().writeTimeoutMillis(10000).build(), ex -> {
            exchange.set(ex);
            Thread writer = new Thread(() -> {
                writerThread.set(Thread.currentThread());
                try {
                    OutputStream out = ex.commit(200, Collections.emptyList(), -1);
                    started.countDown();
                    byte[] data = new byte[16 * 1024];
                    for (;;) { out.write(data); written.addAndGet(data.length); }
                } catch (Throwable problem) { failure.set(problem); }
                finally { ended.countDown(); }
            }, "abort-probe-writer");
            writer.setDaemon(true);
            writer.start();
        });
        try (Socket socket = new Socket("127.0.0.1", adapter.boundPort())) {
            socket.setReceiveBufferSize(1024);
            socket.getOutputStream().write(Http.get("/blocked").getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            long since = -1;
            long previous = -1;
            boolean stalled = false;
            while (System.nanoTime() < deadline) {
                long progress = written.get();
                assertEquals("writer ended before abort", 1, ended.getCount());
                if (progress > 0 && progress == previous && Http.inSocketWrite(writerThread.get())) {
                    if (since < 0) since = System.nanoTime();
                    if (System.nanoTime() - since >= TimeUnit.MILLISECONDS.toNanos(300)) {
                        stalled = true;
                        break;
                    }
                } else { since = -1; }
                previous = progress;
                Thread.sleep(5);
            }
            assertTrue("writer must be stalled before cancellation", stalled);
            exchange.get().abort();
            assertTrue("writer must wake before the 10s write timeout", ended.await(1500, TimeUnit.MILLISECONDS));
            assertTrue(String.valueOf(failure.get()), failure.get() instanceof IOException);
        } finally {
            assertTrue("writer must leave after socket cleanup", ended.await(3, TimeUnit.SECONDS));
        }
    }

    // FR-ENGINE-011 AC-2, IR-HTTP-003 AC-2: HEAD also closes without a fabricated complete 500 response.
    @Test(timeout = 20000)
    public void FR_ENGINE_011_AC2_IR_HTTP_003_AC2_getAndHeadAbortSendNoResponse() throws Exception {
        adapter.bind(Http.settings().build(), ex -> ex.abort());
        for (String method : new String[] {"GET", "HEAD"}) {
            String raw = method + " /abort HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
            assertEquals(method + " abort must not fabricate a complete response", "",
                    Http.send(adapter.boundPort(), raw));
        }
    }

    // FR-ENGINE-011 AC-2: cancellation closes the transport before any fabricated error response.
    @Test(timeout = 20000)
    public void FR_ENGINE_011_AC2_abortBeforeCommitSendsNoFabricatedResponse() throws Exception {
        adapter.bind(Http.settings().build(), ex -> ex.abort());
        String response = Http.send(adapter.boundPort(), Http.get("/abort"));
        assertEquals("", response);
    }

    // FR-ENGINE-011 AC-2: abort() from a third thread while another thread streams the body is thread safe: the
    // writer only ever fails with IOException and the worker hands back a response nobody is still writing
    @Test(timeout = 60000)
    public void FR_ENGINE_011_AC2_abortDuringAConcurrentWriteOnlyFailsTheWriterWithIOException() throws Exception {
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
