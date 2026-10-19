package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Method;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
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

    /** As below, with an empty MIME registry: the built-in table only. */
    StaticHandler(List<StaticMount> registered) {
        this(registered, new DefaultMimeTypes(new AppLifecycle()));
    }

    /** Freezes each mount, taking its canonical root now; {@code mimeTypes} is the app's registry. */
    // @req FR-STATIC-001
    // @req FR-STATIC-007
    StaticHandler(List<StaticMount> registered, DefaultMimeTypes mimeTypes) {
        this.mimeTypes = mimeTypes;
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
        Method method = req.method();
        if (method != Method.GET && method != Method.HEAD) {
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

    /** Whether a GET for {@code rawPath} would be answered from the file system: file, index, listing or 301. */
    // @req FR-STATIC-002
    boolean wouldServeGet(String rawPath) {
        boolean slash = rawPath.endsWith("/");
        for (StaticMount m : mounts) {
            File f = target(m, rawPath);
            if (f == null) {
                continue;
            }
            if (f.isDirectory()) {
                if (!slash || new File(f, m.options().indexName()).isFile() || m.options().directoryListingOn()) {
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
                String q = req.rawQuery();
                res.status(301).header("Location", req.rawPath() + "/" + (q == null ? "" : "?" + q));
                res.end();
                return true;
            }
            String index = path.isEmpty() ? m.options().indexName() : path + "/" + m.options().indexName();
            if (send(m, index, res)) {
                return true;
            }
            if (m.options().directoryListingOn()) {
                // shortcut: a listing directory answers 404 until FR-STATIC-003 adds the listing body
                error(req, res, 404);
                return true;
            }
            return false;
        }
        return !slash && send(m, path, res);
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
            return send(m, m.options().indexName(), res);
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

    /** The app's MIME registry, also used by {@code res.file}. */
    DefaultMimeTypes mimeTypes() {
        return mimeTypes;
    }

    /** Streams the resolver's file at {@code path} with its MIME type; false when it is missing or refused. */
    // @req FR-STATIC-007
    private boolean send(StaticMount m, String path, DefaultResponse res) throws IOException {
        FileResourceResolver r = m.resolver();
        if (r == null) {
            return false;
        }
        InputStream in;
        try {
            in = r.open(path);
        } catch (FileNotFoundException e) {
            return false;
        }
        // shortcut: no cache headers yet; FR-STATIC-003 replaces this body
        String name = path.substring(path.lastIndexOf('/') + 1);
        res.status(200).stream(in, mimeTypes.lookup(name, m.options().mimes()));
        return true;
    }

    private static void error(DefaultRequest req, DefaultResponse res, int status) {
        res.status(status).header("Content-Type", StandardErrors.JSON_TYPE);
        res.send(StandardErrors.json(status, StandardErrors.reason(status), req.requestId()));
    }
}
