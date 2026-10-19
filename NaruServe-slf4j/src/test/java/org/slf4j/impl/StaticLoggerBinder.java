package org.slf4j.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.ILoggerFactory;
import org.slf4j.helpers.NOPLogger;
import org.slf4j.spi.LoggerFactoryBinder;

/**
 * Hand-written test-scope binding of the slf4j-api 1.7.36 static binder mechanism: its factory records each
 * requested logger name and hands out the no-op logger, so no SLF4J binding reaches the runtime dependencies.
 */
public final class StaticLoggerBinder implements LoggerFactoryBinder {

    // Read by LoggerFactory for the version check; not final so the compiler does not inline it.
    public static String REQUESTED_API_VERSION = "1.6.99";

    public static final List<String> REQUESTED_NAMES = Collections.synchronizedList(new ArrayList<String>());

    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();

    private final ILoggerFactory factory = name -> {
        REQUESTED_NAMES.add(name);
        return NOPLogger.NOP_LOGGER;
    };

    private StaticLoggerBinder() {
    }

    public static StaticLoggerBinder getSingleton() {
        return SINGLETON;
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return factory;
    }

    @Override
    public String getLoggerFactoryClassStr() {
        return StaticLoggerBinder.class.getName();
    }
}
