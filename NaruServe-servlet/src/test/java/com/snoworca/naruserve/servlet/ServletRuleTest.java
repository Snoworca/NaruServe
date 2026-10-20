package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * The shared after-shutdown assertions of {@link ContainerRule}: a leaked SSE thread or a leftover upload file
 * fails the test that left it. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-8-8.sds.md.
 */
public class ServletRuleTest {

    private static void run(ContainerRule rule, Statement body) throws Throwable {
        rule.apply(body, Description.EMPTY).evaluate();
    }

    private static AssertionError expectFailure(ContainerRule rule, Statement body) throws Throwable {
        try {
            run(rule, body);
        } catch (AssertionError e) {
            return e;
        }
        fail("the rule passed a test that left something behind");
        throw new AssertionError("unreachable");
    }

    // FR-SVL-006 AC-5 SDS-AC-5: a started and stopped container passes the rule
    @Test
    public void sdsAc5_FR_SVL_006_AC5_aCleanTestPasses() throws Throwable {
        ContainerRule rule = new ContainerRule();
        run(rule, new Statement() {
            @Override
            public void evaluate() throws Throwable {
                rule.servlet(new HttpServlet() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    protected void service(HttpServletRequest req, HttpServletResponse res) {
                        res.setStatus(204);
                    }
                }, "/*");
                assertTrue(SharedApp.http(rule.port(), "GET", "/", null).status == 204);
            }
        });
    }

    // FR-SVL-006 AC-5 SDS-AC-5: the rule fails on a naruserve-servlet-sse-* thread still alive after 2 s
    @Test
    public void sdsAc5_FR_SVL_006_AC5_ruleFailsOnALeakedSseThread() throws Throwable {
        CountDownLatch stop = new CountDownLatch(1);
        Thread leak = new Thread(() -> {
            try {
                stop.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "naruserve-servlet-sse-leak");
        leak.setDaemon(true);
        try {
            AssertionError e = expectFailure(new ContainerRule(), new Statement() {
                @Override
                public void evaluate() {
                    leak.start();
                }
            });
            assertTrue(e.getMessage(), e.getMessage().contains("naruserve-servlet-sse-leak"));
        } finally {
            stop.countDown();
            leak.join(5000);
        }
    }

    // FR-SVL-006 AC-5 SDS-AC-5: the rule fails on an upload temporary file left in the app's tempDirectory
    @Test
    public void sdsAc5_FR_SVL_006_AC5_ruleFailsOnALeftoverUploadFile() throws Throwable {
        ContainerRule rule = new ContainerRule();
        File[] left = new File[1];
        try {
            AssertionError e = expectFailure(rule, new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    left[0] = File.createTempFile("naru-upload-", ".tmp", rule.uploads());
                }
            });
            assertTrue(e.getMessage(), e.getMessage().contains(left[0].getName()));
        } finally {
            if (left[0] != null) {
                left[0].delete();
                left[0].getParentFile().delete();
            }
        }
    }
}
