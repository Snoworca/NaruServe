package com.snoworca.naruserve.internal;

import java.io.File;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * One registered static mount: the normalised prefix, the directory as given and its options. {@link #freeze()} at
 * listen adds the canonical root and the resolver; a root that cannot be made canonical serves nothing.
 */
// @req IR-STATIC-001
// @req FR-STATIC-001
public final class StaticMount {

    private final String prefix;
    private final File dir;
    private final DefaultStaticOptions options;
    private final @Nullable File root;
    private final @Nullable FileResourceResolver resolver;

    StaticMount(String prefix, File dir, DefaultStaticOptions options) {
        this(prefix, dir, options, null);
    }

    private StaticMount(String prefix, File dir, DefaultStaticOptions options, @Nullable File root) {
        this.prefix = prefix;
        this.dir = dir;
        this.options = options;
        this.root = root;
        this.resolver = root == null ? null : new FileResourceResolver(root, options.allowSymlinksOn());
    }

    /** This mount with its canonical root, resolver and a private copy of its options taken now. */
    // @req FR-STATIC-001
    StaticMount freeze() {
        File canonical;
        try {
            canonical = dir.getCanonicalFile();
        } catch (IOException e) {
            canonical = null;
        }
        return new StaticMount(prefix, dir, options.copy(), canonical);
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

    /** The canonical root; null before {@link #freeze()} or when it could not be computed. */
    @Nullable File root() {
        return root;
    }

    @Nullable FileResourceResolver resolver() {
        return resolver;
    }

    /**
     * The raw path after the prefix and its '/', when the path lies under the prefix on a segment boundary
     * ({@code ""} for the prefix itself), else null.
     */
    // @req FR-STATIC-001
    @Nullable String relative(String rawPath) {
        if (prefix.equals("/")) {
            return rawPath.substring(1);
        }
        if (rawPath.equals(prefix)) {
            return "";
        }
        return rawPath.startsWith(prefix + "/") ? rawPath.substring(prefix.length() + 1) : null;
    }
}
