package com.snoworca.naruserve.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * JUnit 4 rule for a test that starts a server (I-015): once the test and its {@code @After} methods are done, every
 * live {@code naruserve-*} and {@code tinyhttpd-*} (acceptor, watchdog) thread the test started must end within the
 * wait, else the test fails with the names.
 * Threads that were alive before the test began belong to someone else and are ignored.
 */
@NullMarked
public final class NoLeftoverThreads implements TestRule {
    /** The thread-name prefix of every thread an app starts. */
    public static final String PREFIX = "naruserve-";
    /** The thread-name prefix of the engine threads an app starts through the TinyHttpd adapter. */
    public static final String ENGINE_PREFIX = "tinyhttpd-";
    private static final long DEFAULT_WAIT_MILLIS = 5000;

    private final long waitMillis;

    public NoLeftoverThreads() {
        this(DEFAULT_WAIT_MILLIS);
    }

    public NoLeftoverThreads(long waitMillis) {
        this.waitMillis = waitMillis;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Set<Thread> before = Collections.newSetFromMap(new IdentityHashMap<Thread, Boolean>());
                before.addAll(live());
                base.evaluate(); // a failing test keeps its own failure
                List<Thread> left = leftovers(before);
                long deadline = System.nanoTime() + waitMillis * 1_000_000L;
                while (!left.isEmpty() && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                    left = leftovers(before);
                }
                if (!left.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (Thread t : left) {
                        names.add(t.getName());
                    }
                    throw new AssertionError("threads still alive " + waitMillis + " ms after the test: " + names);
                }
            }
        };
    }

    private static List<Thread> leftovers(Set<Thread> before) {
        List<Thread> out = new ArrayList<>();
        for (Thread t : live()) {
            if (!before.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<Thread> live() {
        List<Thread> out = new ArrayList<>(LiveThreads.withPrefix(PREFIX));
        out.addAll(LiveThreads.withPrefix(ENGINE_PREFIX));
        return out;
    }
}
