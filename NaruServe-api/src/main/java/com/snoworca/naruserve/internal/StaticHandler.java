package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.CachePolicy;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The static mounts in registration order, consulted by {@link Dispatcher#fallback} after the router found no route
 * for the request (or, for GET and HEAD, a route of another method). A miss in one mount falls through to the next;
 * SPA fallback runs once after every mount missed.
 */
// @req FR-STATIC-001
// @req FR-STATIC-002
// @req FR-STATIC-004
final class StaticHandler {

    private final List<StaticMount> mounts;
    private final DefaultMimeTypes mimeTypes;
    private final @Nullable CachePolicy defaultCache;
    private volatile FileResponder.@Nullable ResolverTap tap;

    /** As below, with an empty MIME registry: the built-in table only. */
    StaticHandler(List<StaticMount> registered) {
        this(registered, new DefaultMimeTypes(new AppLifecycle()));
    }

    /** As below, with no app {@code defaultCache}. */
    StaticHandler(List<StaticMount> registered, DefaultMimeTypes mimeTypes) {
        this(registered, mimeTypes, null);
    }

    /** Freezes each mount, taking its canonical root now; {@code mimeTypes} is the app's registry. */
    // @req FR-STATIC-001
    // @req FR-STATIC-007
    // @req FR-STATIC-008
    StaticHandler(List<StaticMount> registered, DefaultMimeTypes mimeTypes, @Nullable CachePolicy defaultCache) {
        this.mimeTypes = mimeTypes;
        this.defaultCache = defaultCache;
        List<StaticMount> frozen = new ArrayList<>(registered.size());
        for (StaticMount m : registered) {
            frozen.add(m.freeze());
        }
        this.mounts = Collections.unmodifiableList(frozen);
    }

    /**
     * True when it answered: a static 400 or hidden-path 404, a file, an index, a 301, the SPA index, or for another
     * method the 405 with {@code Allow: GET, HEAD}. False leaves the router's answer to the caller.
     */
    // @req FR-STATIC-001
    // @req FR-STATIC-002
    // @req FR-STATIC-004
    boolean serve(DefaultRequest req, DefaultResponse res, boolean routeMethodMismatch) throws IOException {
        if (mounts.isEmpty()) {
            return false;
        }
        String rawPath = req.rawPath();
        for (StaticMount m : mounts) {
            String rel = m.relative(rawPath);
            if (rel != null && StaticPath.check(rel) == 400) {
                if (routeMethodMismatch) {
                    return false; // a route owns this path; its own 405 stands
                }
                error(req, res, 400);
                return true;
            }
        }
        String method = req.methodToken();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            if (!wouldServeGet(rawPath)) {
                return false;
            }
            res.header("Allow", "GET, HEAD");
            error(req, res, 405);
            return true;
        }
        for (StaticMount m : mounts) {
            String rel = m.relative(rawPath);
            if (rel != null && StaticPath.check(rel) == 0 && serveFrom(m, rel, req, res)) {
                return true;
            }
        }
        return spa(req, res);
    }

    /**
     * Whether a GET for {@code rawPath} would be answered from the file system: file, index, listing or 301. A path
     * outside the canonical root or through a refused symbolic link is not, as GET answers it 404.
     */
    // @req FR-STATIC-002
    // @req FR-STATIC-006
    boolean wouldServeGet(String rawPath) throws IOException {
        boolean slash = rawPath.endsWith("/");
        for (StaticMount m : mounts) {
            File f = target(m, rawPath);
            if (f == null || !reachable(m, f)) {
                continue;
            }
            if (f.isDirectory()) {
                File index = new File(f, m.options().indexName());
                if (!slash || index.isFile() && reachable(m, index) || m.options().directoryListingOn()) {
                    return true;
                }
            } else if (!slash && f.isFile()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code rawPath} lies under {@code /api}, {@code /ws} or one of {@code extra}, each stripped of a
     * trailing '/' and matched as equal or followed by '/'.
     */
    // @req FR-STATIC-004
    static boolean spaExcluded(String rawPath, List<String> extra) {
        List<String> all = new ArrayList<>(Arrays.asList("/api", "/ws"));
        all.addAll(extra);
        for (String p : all) {
            String q = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
            if (rawPath.equals(q) || rawPath.startsWith(q + "/")) {
                return true;
            }
        }
        return false;
    }

    /** The file {@code rawPath} names under {@code m}, or null when outside it, refused by the checks or unfrozen. */
    private static @Nullable File target(StaticMount m, String rawPath) {
        String rel = m.relative(rawPath);
        File root = m.root();
        if (rel == null || root == null || StaticPath.check(rel) != 0) {
            return null;
        }
        File f = root;
        for (String s : StaticPath.segments(rel)) {
            f = new File(f, s);
        }
        return f;
    }

    // @req FR-STATIC-001
    private boolean serveFrom(StaticMount m, String rel, DefaultRequest req, DefaultResponse res) throws IOException {
        File f = target(m, req.rawPath());
        if (f == null) {
            return false;
        }
        boolean slash = req.rawPath().endsWith("/");
        StringBuilder sb = new StringBuilder(); // String.join is API 26
        for (String s : StaticPath.segments(rel)) {
            sb.append(sb.length() == 0 ? "" : "/").append(s);
        }
        String path = sb.toString();
        if (f.isDirectory()) {
            if (!slash) {
                if (!reachable(m, f)) {
                    return false; // a refused symbolic link is missed like a missing file, never redirected
                }
                String q = req.rawQuery();
                res.status(301).header("Location", req.rawPath() + "/" + (q == null ? "" : "?" + q))
                        .header("X-Content-Type-Options", "nosniff");
                res.end();
                return true;
            }
            String index = path.isEmpty() ? m.options().indexName() : path + "/" + m.options().indexName();
            if (send(m, index, req, res)) {
                return true;
            }
            if (m.options().directoryListingOn()) {
                if (!reachable(m, f)) {
                    error(req, res, 404);
                    return true;
                }
                res.status(200).header("X-Content-Type-Options", "nosniff");
                res.html(DirectoryListing.render(req.rawPath(), f));
                return true;
            }
            return false;
        }
        return !slash && send(m, path, req, res);
    }

    /** SPA fallback on the first registered mount with spaFallback(true) whose prefix matches. */
    // @req FR-STATIC-004
    private boolean spa(DefaultRequest req, DefaultResponse res) throws IOException {
        String rawPath = req.rawPath();
        for (StaticMount m : mounts) {
            String rel = m.relative(rawPath);
            if (!m.options().spaFallbackOn() || rel == null) {
                continue;
            }
            if (StaticPath.check(rel) != 0 || !acceptsHtml(req) || spaExcluded(rawPath, m.options().spaExcludes())) {
                return false;
            }
            return send(m, m.options().indexName(), req, res);
        }
        return false;
    }

    private static boolean acceptsHtml(DefaultRequest req) {
        for (String v : req.headers("Accept")) {
            if (v.toLowerCase(Locale.ROOT).contains("text/html")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether file or directory {@code f} lies under the mount's canonical root and, unless symbolic links are
     * allowed, is reached through no symbolic link below it.
     */
    // @req FR-STATIC-003
    // @req FR-STATIC-006
    private static boolean reachable(StaticMount m, File f) throws IOException {
        FileResourceResolver r = m.resolver(); // rooted at the mount's canonical root
        return r != null && r.reachable(f);
    }

    /** Test hook for the resolver static mounts, {@code res.file} and {@code res.download} read through. */
    // @req FR-STATIC-011
    void resolverTap(FileResponder.@Nullable ResolverTap tap) {
        this.tap = tap;
    }

    FileResponder.@Nullable ResolverTap resolverTap() {
        return tap;
    }

    /** The app's MIME registry, also used by {@code res.file}. */
    DefaultMimeTypes mimeTypes() {
        return mimeTypes;
    }

    /** The app's {@code defaultCache}, which every response applies at commit. */
    @Nullable CachePolicy defaultCache() {
        return defaultCache;
    }

    /**
     * Answers the resolver's file at {@code path} through {@link FileResponder} with its MIME type and the first
     * matching cache pattern; false when it is missing or refused.
     */
    // @req FR-STATIC-007
    // @req FR-STATIC-008
    private boolean send(StaticMount m, String path, DefaultRequest req, DefaultResponse res) throws IOException {
        FileResourceResolver r = m.resolver();
        if (r == null) {
            return false;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        CachePolicy policy = CachePattern.select(m.options().cachePatterns(), "/" + path);
        try {
            FileResponder.respond(req, res, FileResponder.tapped(r, tap), path, mimeTypes.lookup(name, m.options().mimes()), policy);
        } catch (FileNotFoundException e) {
            return false;
        }
        return true;
    }

    private static void error(DefaultRequest req, DefaultResponse res, int status) {
        res.header("X-Content-Type-Options", "nosniff");
        Dispatcher.error(res, status, req.requestId());
    }
}
