package com.snoworca.naruserve;

/**
 * Options of one {@link RestApp#staticFiles(String, java.io.File, Configurer)} mount, like the options object of
 * Express {@code express.static}. Every setter returns these options.
 */
// @req IR-STATIC-001
public interface StaticOptions {

    /** The directory index file name; default {@code index.html}. Empty or holding {@code /} throws IAE. */
    StaticOptions index(String name);

    /** Whether a directory without an index lists its entries; default false. */
    StaticOptions directoryListing(boolean on);

    /** Whether an unmatched GET or HEAD falls back to the root index (SPA); default false. */
    StaticOptions spaFallback(boolean on);

    /**
     * Adds prefixes, each starting with {@code /} (else IAE), excluded from SPA fallback besides the always-excluded
     * {@code /api/} and {@code /ws/}.
     */
    StaticOptions spaExclude(String... prefixes);

    /** Sends {@code policy} as {@code Cache-Control} for files whose mount-relative path matches {@code pattern}. */
    StaticOptions cache(String pattern, CachePolicy policy);

    /** Maps {@code ext} (a leading dot is stripped) to {@code type} for this mount, before the app registry. */
    StaticOptions mime(String ext, String type);

    /** Whether symbolic links below the root are served; default false. Root containment still applies. */
    StaticOptions allowSymlinks(boolean allow);
}
