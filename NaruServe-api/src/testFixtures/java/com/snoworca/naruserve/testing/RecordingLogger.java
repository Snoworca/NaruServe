package com.snoworca.naruserve.testing;

import com.snoworca.naruserve.Logger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
}
