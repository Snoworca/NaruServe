package com.snoworca.naruserve;

/** Access log options; each method returns these options. */
// @req IR-APP-007
public interface AccessLogOptions {

    AccessLogOptions enabled(boolean enabled);

    AccessLogOptions level(LogLevel level);

    AccessLogOptions includeRequestId(boolean include);

    AccessLogOptions includeDuration(boolean include);

    AccessLogOptions includeBytes(boolean include);

    /** Writes no line for requests whose raw path equals {@code path}. */
    AccessLogOptions exclude(String path);
}
