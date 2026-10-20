package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncListener;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Test;

/** abort, the single start and their IOException and IllegalStateException, over a recording response. */
public class ServletAbortTest {

    /** A response proxy recording each call (name and arguments) and the size of every body write. */
    static final class FakeResponse {
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        final List<Integer> writes = Collections.synchronizedList(new ArrayList<Integer>());
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final HttpServletResponse proxy;

        FakeResponse() {
            ServletOutputStream out = new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                }

                @Override
                public void write(int b) {
                    writes.add(1);
                    body.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    writes.add(len);
                    body.write(b, off, len);
                }
            };
            proxy = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class}, (p, m, args) -> {
                        if (m.getName().equals("getOutputStream")) {
                            return out;
                        }
                        calls.add(m.getName() + (args == null ? "[]" : Arrays.toString(args)));
                        Class<?> t = m.getReturnType();
                        return t == boolean.class ? Boolean.FALSE : t == int.class ? Integer.valueOf(0) : null;
                    });
        }
    }

    static HttpServletRequest request() {
        return (HttpServletRequest) Proxy.newProxyInstance(ServletAbortTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (p, m, args) -> {
                    throw new AssertionError("request." + m.getName() + " was not expected");
                });
    }

    static List<Map.Entry<String, String>> headers(String... pairs) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.add(new SimpleImmutableEntry<>(pairs[i], pairs[i + 1]));
        }
        return out;
    }

    private static void assertIOException(String what, IORunnable r) {
        try {
            r.run();
            fail(what + " did not throw IOException");
        } catch (IOException expected) {
            // the aborted exchange
        }
    }

    private static void assertIllegalState(String what, IORunnable r) throws IOException {
        try {
            r.run();
            fail(what + " did not throw IllegalStateException");
        } catch (IllegalStateException expected) {
            // the second start
        }
    }

    @FunctionalInterface
    interface IORunnable {
        void run() throws IOException;
    }

    // SDS-AC-9; FR-SVL-003 AC-3
    @Test
    public void sdsAc9_FR_SVL_003_AC3_afterAbortWriteAndFinishThrowIOException() throws Exception {
        FakeResponse res = new FakeResponse();
        ServletExchange ex = new ServletExchange(request(), res.proxy, "/");
        OutputStream out = ex.commit(200, headers("Content-Length", "4"), 4);
        out.write(new byte[] {1, 2}, 0, 2);
        int callsBefore = res.calls.size();
        ex.abort();
        assertEquals("abort makes no container call", callsBefore, res.calls.size());
        assertIOException("write", () -> out.write(3));
        assertIOException("write array", () -> out.write(new byte[] {3, 4}, 0, 2));
        assertIOException("finish", ex::finish);
        assertEquals(2, res.body.size());
    }

    // SDS-AC-9; FR-SVL-003 AC-3
    @Test
    public void sdsAc9_FR_SVL_003_AC3_abortBeforeCommitMakesCommitThrowIOException() throws Exception {
        FakeResponse res = new FakeResponse();
        ServletExchange ex = new ServletExchange(request(), res.proxy, "/");
        ex.abort();
        ex.abort();
        assertIOException("commit", () -> ex.commit(200, headers(), 0));
        assertEquals("nothing reached the container", Collections.emptyList(), res.calls);
    }

    // SDS-AC-9; FR-SVL-003 AC-3
    @Test
    public void sdsAc9_FR_SVL_003_AC3_abortFromAnotherThreadFailsTheWriter() throws Exception {
        FakeResponse res = new FakeResponse();
        ServletExchange ex = new ServletExchange(request(), res.proxy, "/");
        OutputStream out = ex.commit(200, headers(), -1);
        Thread t = new Thread(() -> {
            ex.abort();
            ex.abort();
        });
        t.start();
        t.join(10000);
        assertFalse("the aborting thread ended", t.isAlive());
        assertIOException("write after the other thread's abort", () -> out.write(1));
    }

    // SDS-AC-9; FR-SVL-003 AC-3
    @Test
    public void sdsAc9_FR_SVL_003_AC3_secondCommitOrSendFileThrowsIllegalState() throws Exception {
        File f = File.createTempFile("naruserve-abort", ".bin");
        f.deleteOnExit();
        FakeResponse res = new FakeResponse();
        ServletExchange ex = new ServletExchange(request(), res.proxy, "/");
        ex.commit(200, headers(), 0);
        assertIllegalState("second commit", () -> ex.commit(200, headers(), 0));
        assertIllegalState("sendFile after commit", () -> ex.sendFile(200, headers(), f, 0, 0));

        ServletExchange ex2 = new ServletExchange(request(), new FakeResponse().proxy, "/");
        ex2.sendFile(200, headers("Content-Length", "0"), f, 0, 0);
        assertIllegalState("commit after sendFile", () -> ex2.commit(200, headers(), 0));
        assertIllegalState("second sendFile", () -> ex2.sendFile(200, headers(), f, 0, 0));
    }

    /** An AsyncContext proxy that keeps its listeners and counts complete(); any other call returns a default. */
    static final class FakeAsync {
        final List<AsyncListener> listeners = Collections.synchronizedList(new ArrayList<AsyncListener>());
        final AtomicInteger completes = new AtomicInteger();
        final AsyncContext proxy = (AsyncContext) Proxy.newProxyInstance(ServletAbortTest.class.getClassLoader(),
                new Class<?>[] {AsyncContext.class}, (p, m, args) -> {
                    if (m.getName().equals("addListener")) {
                        listeners.add((AsyncListener) args[0]);
                    } else if (m.getName().equals("complete")) {
                        completes.incrementAndGet();
                    }
                    return null;
                });
    }

    /** A started event stream exchange registered in {@code pool}, and the drain's stream over it. */
    private static OutputStream stream(ServletEventStream.Pool pool, FakeAsync ac) throws IOException {
        FakeResponse res = new FakeResponse();
        ServletExchange ex = new ServletExchange(request(), res.proxy, "/");
        ex.async(ac.proxy);
        pool.track(ex, ac.proxy);
        return new ServletEventStream.FlushingStream(ex, res.proxy.getOutputStream());
    }

    // FR-SVL-006 AC-3 (stop completes every open stream): a drain write after completeAll() fails, so the drain
    // ends instead of writing to a completed and possibly recycled response (review G2-001)
    @Test
    public void fr_SVL_006_AC3_streamWriteAfterCompleteAllThrowsIOException() throws Exception {
        ServletEventStream.Pool pool = new ServletEventStream.Pool(1);
        try {
            FakeAsync ac = new FakeAsync();
            OutputStream out = stream(pool, ac);
            out.write(1);
            pool.completeAll();
            assertEquals(1, ac.completes.get());
            assertIOException("write after completeAll", () -> out.write(2));
            assertIOException("flush after completeAll", out::flush);
        } finally {
            pool.shutdownNow();
        }
    }

    // FR-SVL-005 AC-5 (one complete per stream): a container onError/onTimeout aborts the exchange, completes the
    // context once and fails the drain's next write (review G2-001)
    @Test
    public void fr_SVL_005_AC5_containerErrorOrTimeoutAbortsAndCompletesOnce() throws Exception {
        ServletEventStream.Pool pool = new ServletEventStream.Pool(2);
        try {
            FakeAsync errored = new FakeAsync();
            OutputStream a = stream(pool, errored);
            errored.listeners.get(0).onError(null);
            assertEquals(1, errored.completes.get());
            assertIOException("write after onError", () -> a.write(1));

            FakeAsync timedOut = new FakeAsync();
            OutputStream b = stream(pool, timedOut);
            timedOut.listeners.get(0).onTimeout(null);
            timedOut.listeners.get(0).onError(null);
            assertEquals(1, timedOut.completes.get());
            assertIOException("write after onTimeout", () -> b.write(1));
        } finally {
            pool.shutdownNow();
        }
    }

    // SDS-AC-7; FR-SVL-003 AC-1 — setStatus, addHeader in order, Content-Length through setHeader, no sendError
    @Test
    public void sdsAc7_FR_SVL_003_AC1_commitWritesStatusAndHeadersAsGiven() throws Exception {
        FakeResponse res = new FakeResponse();
        ServletExchange ex = new ServletExchange(request(), res.proxy, "/");
        ex.commit(201, headers("X-A", "1", "Content-Length", "2", "X-A", "2"), 2).write("ok".getBytes("UTF-8"));
        ex.finish();
        assertEquals(Arrays.asList("setStatus[201]", "addHeader[X-A, 1]", "setHeader[Content-Length, 2]",
                "addHeader[X-A, 2]", "flushBuffer[]"), res.calls);
        for (String c : res.calls) {
            assertTrue(c, !c.startsWith("sendError") && !c.startsWith("setContentLength"));
        }
    }
}
