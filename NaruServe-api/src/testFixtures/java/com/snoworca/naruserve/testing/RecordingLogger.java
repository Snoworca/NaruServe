package com.snoworca.naruserve.testing;

import com.snoworca.naruserve.Logger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;

/**
 * A {@link Logger} that keeps every record as {@code "<LEVEL> <message>"} (an error's message appended after a
 * space) and every logged {@link Throwable}.
 */
@NullMarked
public class RecordingLogger implements Logger {

    public final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
    public final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());

    @Override
    public void trace(String message) {
        lines.add("TRACE " + message);
    }

    @Override
    public void debug(String message) {
        lines.add("DEBUG " + message);
    }

    @Override
    public void info(String message) {
        lines.add("INFO " + message);
    }

    @Override
    public void warn(String message) {
        lines.add("WARN " + message);
    }

    @Override
    public void error(String message) {
        lines.add("ERROR " + message);
    }

    @Override
    public void error(String message, Throwable error) {
        lines.add("ERROR " + message + " " + error.getMessage());
        errors.add(error);
    }

    /** The INFO lines, without the level prefix. */
    public List<String> info() {
        List<String> out = new ArrayList<>();
        synchronized (lines) {
            for (String l : lines) {
                if (l.startsWith("INFO ")) {
                    out.add(l.substring(5));
                }
            }
        }
        return out;
    }

    /** The number of records at {@code level}. */
    public int count(String level) {
        int n = 0;
        for (String l : new ArrayList<>(lines)) {
            if (l.startsWith(level + " ")) {
                n++;
            }
        }
        return n;
    }

    /** The entries of a snapshot of {@code lines} that match {@code regex} entirely. */
    public static List<String> matching(List<String> lines, String regex) {
        List<String> out = new ArrayList<>();
        for (String l : new ArrayList<>(lines)) {
            if (l.matches(regex)) {
                out.add(l);
            }
        }
        return out;
    }

    /** The number of entries of {@code lines} that match {@code regex} entirely. */
    public static int countMatching(List<String> lines, String regex) {
        return matching(lines, regex).size();
    }

    /**
     * Polls {@code lines} (for example {@code log.info()} through a method reference) for up to 5 s until at least
     * {@code n} entries match {@code regex} and returns the matches.
     *
     * @throws AssertionError when fewer than {@code n} matched in time
     */
    public static List<String> awaitMatching(Supplier<List<String>> lines, String regex, int n)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<String> found = matching(lines.get(), regex);
        while (found.size() < n && System.nanoTime() < end) {
            Thread.sleep(5);
            found = matching(lines.get(), regex);
        }
        if (found.size() < n) {
            throw new AssertionError(found.size() + " lines matching " + regex + ", expected " + n + ": " + lines.get());
        }
        return found;
    }
}
