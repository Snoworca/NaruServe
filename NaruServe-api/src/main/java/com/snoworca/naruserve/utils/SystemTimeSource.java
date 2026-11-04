package com.snoworca.naruserve.utils;

/** The one system {@link TimeSource}. */
// @req IR-AUTH-001
final class SystemTimeSource implements TimeSource {

    static final SystemTimeSource INSTANCE = new SystemTimeSource();

    private SystemTimeSource() {
    }

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "TimeSource.system()";
    }
}
