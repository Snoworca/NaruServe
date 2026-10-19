package com.snoworca.naruserve;

/** Default logger: INFO and above, INFO to System.out and WARN, ERROR to System.err. */
// shortcut: no level option, timestamped line format or flush/close yet; the IR-APP-006 SDS completes it.
// @req IR-APP-006
public final class ConsoleLogger implements Logger {

    @Override
    public void trace(String message) {
    }

    @Override
    public void debug(String message) {
    }

    @Override
    public void info(String message) {
        System.out.println("INFO " + message);
    }

    @Override
    public void warn(String message) {
        System.err.println("WARN " + message);
    }

    @Override
    public void error(String message) {
        System.err.println("ERROR " + message);
    }

    // @req IR-APP-006
    @Override
    public void error(String message, Throwable error) {
        System.err.println("ERROR " + message);
        error.printStackTrace(System.err);
    }
}
