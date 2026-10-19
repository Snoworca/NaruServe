package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.AccessLogOptions;
import com.snoworca.naruserve.LogLevel;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** The app's one mutable access log options: enabled, INFO, every include on, no exclude. */
// @req IR-APP-007
// @req FR-APP-015
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

    /** Exact, case-sensitive match of the raw path (no query) against an exclude entry. */
    // @req FR-APP-015
    synchronized boolean excluded(String rawPath) {
        return exclude.contains(rawPath);
    }

    synchronized boolean isEnabled() {
        return enabled;
    }

    synchronized LogLevel logLevel() {
        return level;
    }

    synchronized boolean requestIdOn() {
        return includeRequestId;
    }

    synchronized boolean durationOn() {
        return includeDuration;
    }

    synchronized boolean bytesOn() {
        return includeBytes;
    }

    @Override
    public synchronized String toString() {
        return "AccessLogOptions{enabled=" + enabled + ", level=" + level + ", includeRequestId=" + includeRequestId
                + ", includeDuration=" + includeDuration + ", includeBytes=" + includeBytes + ", exclude=" + exclude
                + "}";
    }
}
