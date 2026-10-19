package com.snoworca.naruserve.internal;

import java.io.File;

/**
 * One registered static mount: the normalised prefix, the directory as given and its options. Minimal wave 6 part 5
 * version; the canonical root and resolver taken at listen arrive with FR-STATIC-001.
 */
// @req IR-STATIC-001
public final class StaticMount {

    private final String prefix;
    private final File dir;
    private final DefaultStaticOptions options;

    StaticMount(String prefix, File dir, DefaultStaticOptions options) {
        this.prefix = prefix;
        this.dir = dir;
        this.options = options;
    }

    /** The prefix without a trailing {@code /}, except {@code /} itself. */
    public String prefix() {
        return prefix;
    }

    /** The directory as registered; it may not exist. */
    public File dir() {
        return dir;
    }

    DefaultStaticOptions options() {
        return options;
    }
}
