package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.AccessLogOptions;
import com.snoworca.naruserve.LogLevel;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** The app's one mutable access log options: enabled, INFO, every include on, no exclude. */
// @req IR-APP-007
final class DefaultAccessLogOptions implements AccessLogOptions {

    // Guarded by this.
    private boolean enabled = true;
    private LogLevel level = LogLevel.INFO;
    private boolean includeRequestId = true;
    private boolean includeDuration = true;
    private boolean includeBytes = true;
    private final Set<String> exclude = new LinkedHashSet<>();

    @Override
    public synchronized AccessLogOptions enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    // @req IR-APP-007
    @Override
    public synchronized AccessLogOptions level(LogLevel level) {
        this.level = Objects.requireNonNull(level, "level");
        return this;
    }

    @Override
    public synchronized AccessLogOptions includeRequestId(boolean include) {
        this.includeRequestId = include;
        return this;
    }

    @Override
    public synchronized AccessLogOptions includeDuration(boolean include) {
        this.includeDuration = include;
        return this;
    }

    @Override
    public synchronized AccessLogOptions includeBytes(boolean include) {
        this.includeBytes = include;
        return this;
    }

    // @req IR-APP-007
    @Override
    public synchronized AccessLogOptions exclude(String path) {
        exclude.add(Objects.requireNonNull(path, "path"));
        return this;
    }

    @Override
    public synchronized String toString() {
        return "AccessLogOptions{enabled=" + enabled + ", level=" + level + ", includeRequestId=" + includeRequestId
                + ", includeDuration=" + includeDuration + ", includeBytes=" + includeBytes + ", exclude=" + exclude
                + "}";
    }
}
