package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Logger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The startup check report: every finding is a FAIL or a WARN with its item and message. {@link #conclude(Logger)}
 * logs each WARN once and, when there is a FAIL, throws an {@link IllegalStateException} naming every FAIL, which
 * stops the app's start before the port is bound.
 */
// @req OPS-AUTH-001
public final class Doctor {

    /** The level of a finding. */
    public enum Level {
        /** Start stops. */
        FAIL,
        /** Logged; start continues. */
        WARN
    }

    /** One finding. */
    public static final class Finding {
        private final Level level;
        private final String item;
        private final String message;

        Finding(Level level, String item, String message) {
            this.level = level;
            this.item = item;
            this.message = message;
        }

        /** FAIL or WARN. */
        public Level getLevel() {
            return level;
        }

        /** The check that reported it. */
        public String getItem() {
            return item;
        }

        /** What is wrong and what to do. */
        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return level + " " + item + ": " + message;
        }
    }

    private final List<Finding> findings = new ArrayList<>();

    /** Records a FAIL. */
    // @req OPS-AUTH-001
    public void fail(String item, String message) {
        findings.add(new Finding(Level.FAIL, item, message));
    }

    /** Records a WARN. */
    // @req OPS-AUTH-001
    public void warn(String item, String message) {
        findings.add(new Finding(Level.WARN, item, message));
    }

    /** The findings so far, unmodifiable. */
    public List<Finding> findings() {
        return Collections.unmodifiableList(new ArrayList<>(findings));
    }

    /** Logs every WARN once and throws naming every FAIL when there is one. */
    // @req OPS-AUTH-001
    public void conclude(Logger logger) {
        List<String> fails = new ArrayList<>();
        for (Finding f : findings) {
            if (f.level == Level.WARN) {
                logger.warn("auth doctor WARN " + f.item + ": " + f.message);
            } else {
                fails.add(f.item + ": " + f.message);
                logger.error("auth doctor FAIL " + f.item + ": " + f.message);
            }
        }
        if (!fails.isEmpty()) {
            StringBuilder b = new StringBuilder("auth doctor found ").append(fails.size())
                    .append(fails.size() == 1 ? " FAIL" : " FAILs").append("; the app does not start:");
            for (String s : fails) {
                b.append("\n  FAIL ").append(s);
            }
            throw new IllegalStateException(b.toString());
        }
    }
}
