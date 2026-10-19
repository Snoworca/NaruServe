package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.Logger;
import com.snoworca.tinyhttpd.log.TinyLog;
import java.util.logging.Level;
import org.jspecify.annotations.Nullable;

/** TinyHttpd's log sink, written through the app logger; the app logger does the level filtering. */
// @req FR-ENGINE-005
final class TinyLogBridge implements TinyLog {

    private final Logger logger;

    TinyLogBridge(Logger logger) {
        this.logger = logger;
    }

    @Override
    public boolean isLoggable(Level level) {
        return true;
    }

    /**
     * SEVERE to error (with the throwable), WARNING to warn, INFO to info, CONFIG and FINE to debug, FINER and
     * FINEST to trace; below SEVERE the throwable is appended to the message, as Logger has no warn(String, Throwable).
     */
    // @req FR-ENGINE-005
    @Override
    public void log(Level level, String message, @Nullable Throwable thrown) {
        try {
            write(level.intValue(), message, thrown);
        } catch (RuntimeException ignored) {
            // A throwing app logger must not break TinyHttpd, e.g. skip the end of its stop.
        }
    }

    private void write(int v, String message, @Nullable Throwable thrown) {
        if (v >= Level.SEVERE.intValue()) {
            if (thrown != null) {
                logger.error(message, thrown);
            } else {
                logger.error(message);
            }
            return;
        }
        String text = thrown == null ? message : message + ": " + thrown;
        if (v >= Level.WARNING.intValue()) {
            logger.warn(text);
        } else if (v >= Level.INFO.intValue()) {
            logger.info(text);
        } else if (v >= Level.FINE.intValue()) {
            logger.debug(text);
        } else {
            logger.trace(text);
        }
    }
}
