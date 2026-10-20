package com.snoworca.naruserve.testing;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/** Looks up the JVM's live threads by name for tests that count the threads an app started. */
@NullMarked
public final class LiveThreads {
    private LiveThreads() {
    }

    /** Live threads whose name starts with {@code prefix}. */
    public static List<Thread> withPrefix(String prefix) {
        List<Thread> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith(prefix)) {
                out.add(t);
            }
        }
        return out;
    }
}
