package com.snoworca.naruserve;

/** Application logger; implementations decide the level threshold and the output. */
// @req IR-APP-006
public interface Logger {

    void trace(String message);

    void debug(String message);

    void info(String message);

    void warn(String message);

    void error(String message);

    void error(String message, Throwable error);
}
