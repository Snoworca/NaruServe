package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * T17 on the Servlet adapter: a client that closes the socket halfway through a spooled part leaves no upload file
 * once the container's request thread returned. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-8-7.sds.md.
 */
public class ServletMultipartDisconnectTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // SDS-AC-4; FR-SVL-004 AC-4
    @Test(timeout = 30000)
    public void sdsAc4_FR_SVL_004_AC4_aClientCutMidUploadLeavesNoUploadFileAfterTheRequestThreadReturned()
            throws Exception {
        File dir = tmp.newFolder("spool");
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        // Counts down once the container's request thread is back out of the servlet.
        container.filter(new Filter() {
            @Override
            public void init(FilterConfig config) {
            }

            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws java.io.IOException, javax.servlet.ServletException {
                try {
                    chain.doFilter(req, res);
                } finally {
                    returned.countDown();
                }
            }

            @Override
            public void destroy() {
            }
        });
        SharedApp.onJetty(container, "/*", app -> {
            app.multipart(o -> o.memoryThreshold(16).tempDirectory(dir));
            app.post("/up", (req, res) -> {
                try {
                    req.file("f");
                } catch (RuntimeException e) {
                    failed.countDown();
                    throw e;
                }
                res.send("complete?");
            });
        });
        int port = container.port();
        String head = "--b\r\nContent-Disposition: form-data; name=\"f\"; filename=\"a.bin\"\r\n\r\n";
        byte[] half = new byte[20000];
        Arrays.fill(half, (byte) 'h');
        boolean spooled;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            OutputStream out = s.getOutputStream();
            out.write(("POST /up HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: multipart/form-data; boundary=b"
                    + "\r\nContent-Length: 100000\r\n\r\n" + head).getBytes(StandardCharsets.ISO_8859_1));
            out.write(half);
            out.flush();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (dir.list().length == 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            spooled = dir.list().length == 1;
        }
        assertTrue("the part spooled before the disconnect", spooled);
        assertTrue("the read failure reached the core", failed.await(10, TimeUnit.SECONDS));
        assertTrue("the container thread returned", returned.await(10, TimeUnit.SECONDS));
        assertEquals(Arrays.asList(), Arrays.asList(dir.list()));
    }
}
