package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.CachePolicy;
import com.snoworca.naruserve.StaticOptions;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One mount's option values: index.html, listing off, SPA off, symlinks off unless a setter changed them. */
// @req IR-STATIC-001
final class DefaultStaticOptions implements StaticOptions {

    private String index = "index.html";
    private boolean directoryListing;
    private boolean spaFallback;
    private final List<String> spaExcludes = new ArrayList<>();
    private final List<Map.Entry<String, CachePolicy>> cachePatterns = new ArrayList<>();
    private final Map<String, String> mimes = new LinkedHashMap<>();
    private boolean allowSymlinks;

    // @req IR-STATIC-001
    @Override
    public StaticOptions index(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            throw new IllegalArgumentException("index must be a non-empty name without '/': " + name);
        }
        this.index = name;
        return this;
    }

    @Override
    public StaticOptions directoryListing(boolean on) {
        this.directoryListing = on;
        return this;
    }

    @Override
    public StaticOptions spaFallback(boolean on) {
        this.spaFallback = on;
        return this;
    }

    // @req IR-STATIC-001
    @Override
    public StaticOptions spaExclude(String... prefixes) {
        Objects.requireNonNull(prefixes, "prefixes");
        for (String p : prefixes) {
            Objects.requireNonNull(p, "prefix");
            if (!p.startsWith("/")) {
                throw new IllegalArgumentException("spaExclude prefix must start with '/': " + p);
            }
        }
        Collections.addAll(spaExcludes, prefixes);
        return this;
    }

    // @req IR-STATIC-001
    @Override
    public StaticOptions cache(String pattern, CachePolicy policy) {
        cachePatterns.add(new SimpleImmutableEntry<>(Objects.requireNonNull(pattern, "pattern"),
                Objects.requireNonNull(policy, "policy")));
        return this;
    }

    // @req IR-STATIC-001
    @Override
    public StaticOptions mime(String ext, String type) {
        mimes.put(DefaultMimeTypes.extension(ext), DefaultMimeTypes.type(type));
        return this;
    }

    @Override
    public StaticOptions allowSymlinks(boolean allow) {
        this.allowSymlinks = allow;
        return this;
    }

    String indexName() {
        return index;
    }

    boolean directoryListingOn() {
        return directoryListing;
    }

    boolean spaFallbackOn() {
        return spaFallback;
    }

    List<String> spaExcludes() {
        return Collections.unmodifiableList(spaExcludes);
    }

    List<Map.Entry<String, CachePolicy>> cachePatterns() {
        return Collections.unmodifiableList(cachePatterns);
    }

    /** The mount's own MIME mapping for a lower-case extension without the dot, or null. */
    @org.jspecify.annotations.Nullable String mime(String ext) {
        return mimes.get(ext.toLowerCase(Locale.ROOT));
    }

    /** The mount's own MIME mappings, keyed by lower-case extension without the dot. */
    Map<String, String> mimes() {
        return Collections.unmodifiableMap(mimes);
    }

    boolean allowSymlinksOn() {
        return allowSymlinks;
    }
}
