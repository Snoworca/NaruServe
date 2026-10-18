package com.snoworca.naruserve.routing;

/** Immutable router switches; every switch is off unless set on the {@link Builder}. */
// @req FR-ROUTE-006
public final class RouterPolicy {

    private static final RouterPolicy STRICT = new Builder().build();

    private final boolean mergeSlashes;
    private final boolean ignoreTrailingSlash;
    private final boolean caseInsensitive;
    private final boolean enableTrace;
    private final boolean enableConnect;

    private RouterPolicy(Builder b) {
        this.mergeSlashes = b.mergeSlashes;
        this.ignoreTrailingSlash = b.ignoreTrailingSlash;
        this.caseInsensitive = b.caseInsensitive;
        this.enableTrace = b.enableTrace;
        this.enableConnect = b.enableConnect;
    }

    /** Every switch off: case-sensitive, trailing slash significant, repeated slashes kept, TRACE/CONNECT off. */
    // @req FR-ROUTE-006
    public static RouterPolicy strict() {
        return STRICT;
    }

    // @req FR-ROUTE-006
    public static Builder builder() {
        return new Builder();
    }

    /** {@code /a//b} is treated as {@code /a/b}. */
    public boolean mergeSlashes() {
        return mergeSlashes;
    }

    /** One trailing slash is dropped from paths and patterns; {@code /} stays {@code /}. */
    public boolean ignoreTrailingSlash() {
        return ignoreTrailingSlash;
    }

    /** Literal segments compare case-insensitively, independent of the default locale. */
    public boolean caseInsensitive() {
        return caseInsensitive;
    }

    /** {@code TRACE} routes may be registered. */
    public boolean enableTrace() {
        return enableTrace;
    }

    /** {@code CONNECT} routes may be registered. */
    public boolean enableConnect() {
        return enableConnect;
    }

    /** Mutable builder; {@link #build()} snapshots the current values. */
    // @req FR-ROUTE-006
    public static final class Builder {
        private boolean mergeSlashes;
        private boolean ignoreTrailingSlash;
        private boolean caseInsensitive;
        private boolean enableTrace;
        private boolean enableConnect;

        private Builder() {
        }

        public Builder mergeSlashes(boolean on) {
            mergeSlashes = on;
            return this;
        }

        public Builder ignoreTrailingSlash(boolean on) {
            ignoreTrailingSlash = on;
            return this;
        }

        public Builder caseInsensitive(boolean on) {
            caseInsensitive = on;
            return this;
        }

        public Builder enableTrace(boolean on) {
            enableTrace = on;
            return this;
        }

        public Builder enableConnect(boolean on) {
            enableConnect = on;
            return this;
        }

        public RouterPolicy build() {
            return new RouterPolicy(this);
        }
    }
}
