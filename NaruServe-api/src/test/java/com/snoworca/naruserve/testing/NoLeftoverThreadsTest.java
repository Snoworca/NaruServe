package com.snoworca.naruserve.testing;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/** The NoLeftoverThreads rule (I-015): a thread named naruserve-* that outlives a test fails it. */
public class NoLeftoverThreadsTest {

    private static final Description TEST = Description.createTestDescription(NoLeftoverThreadsTest.class, "t");

    private static Thread start(String name, CountDownLatch release) {
        Thread t = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
                // ends the thread
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // I-015
    @Test(timeout = 20000)
    public void i015_aNaruServeThreadStartedByTheTestAndStillAliveFailsItWithItsName() throws Throwable {
        CountDownLatch release = new CountDownLatch(1);
        try {
            new NoLeftoverThreads(200).apply(new Statement() {
                @Override
                public void evaluate() {
                    start("naruserve-9-leftover-test", release);
                }
            }, TEST).evaluate();
            fail("a leftover thread must fail the test");
        } catch (AssertionError e) {
            assertTrue(e.getMessage(), e.getMessage().contains("naruserve-9-leftover-test"));
        } finally {
            release.countDown();
        }
    }

    // I-015
    @Test(timeout = 20000)
    public void i015_aThreadThatEndsWithinTheWaitAndAThreadAliveBeforeTheTestAreNotLeftovers() throws Throwable {
        CountDownLatch before = new CountDownLatch(1);
        start("naruserve-9-before-test", before);
        try {
            new NoLeftoverThreads(5000).apply(new Statement() {
                @Override
                public void evaluate() {
                    CountDownLatch soon = new CountDownLatch(1);
                    start("naruserve-9-ends-soon", soon);
                    soon.countDown();
                }
            }, TEST).evaluate();
        } finally {
            before.countDown();
        }
    }

    // I-015: a failing test keeps its own failure
    @Test(timeout = 20000)
    public void i015_aFailureOfTheTestItselfIsRethrownUnchanged() throws Throwable {
        IllegalStateException boom = new IllegalStateException("boom");
        try {
            new NoLeftoverThreads(50).apply(new Statement() {
                @Override
                public void evaluate() {
                    throw boom;
                }
            }, TEST).evaluate();
            fail("the failure must propagate");
        } catch (IllegalStateException e) {
            assertTrue(e == boom);
        }
    }
}
