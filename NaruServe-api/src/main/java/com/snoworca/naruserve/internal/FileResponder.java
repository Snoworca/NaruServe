package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.CachePolicy;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.ResourceResolver;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.jspecify.annotations.Nullable;

/**
 * Engine-free file answers for static mounts, {@code res.file} and {@code res.download}: validators, the conditional
 * 304 (If-None-Match before If-Modified-Since), one byte range (206 or 416) and If-Range, following RFC 9110 §13 and
 * §14. Several or malformed ranges get the full 200. Only GET and HEAD evaluate conditionals; Range is GET only.
 */
// @req FR-STATIC-009
// @req FR-STATIC-010
// @req FR-STATIC-011
final class FileResponder {

    private static final String IMF = "EEE, dd MMM yyyy HH:mm:ss zzz";
    private static final String[] DATE_FORMATS = {IMF, "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy"};

    private FileResponder() {
    }

    /** Test hook wrapping the resolver a file answer reads through (no java.util.function type: absent from API 21). */
    // @req FR-STATIC-011
    interface ResolverTap {
        ResourceResolver wrap(ResourceResolver r);
    }

    static ResourceResolver tapped(ResourceResolver r, @Nullable ResolverTap tap) {
        return tap == null ? r : tap.wrap(r);
    }

    /**
     * Answers {@code path} of {@code r} with {@code type}; {@code policy}, when given, sets Cache-Control on every
     * outcome. A missing or refused file throws {@link java.io.FileNotFoundException} before the response changes.
     */
    // @req FR-STATIC-003
    // @req FR-STATIC-009
    // @req FR-STATIC-010
    // @req FR-STATIC-011
    static void respond(DefaultRequest req, DefaultResponse res, ResourceResolver r, String path, String type,
            @Nullable CachePolicy policy) throws IOException {
        long length = r.length(path);
        String tag = r.etag(path);
        long modified = r.modified(path);
        long seconds = modified / 1000;
        Method method = req.method();
        boolean conditional = method == Method.GET || method == Method.HEAD;

        int status = 200;
        long[] range = null;
        if (conditional) {
            String inm = req.header("If-None-Match");
            if (inm != null) {
                status = noneMatch(inm, tag) ? 304 : 200;
            } else {
                long ims = parseDate(req.header("If-Modified-Since"));
                status = ims != Long.MIN_VALUE && ims / 1000 >= seconds ? 304 : 200;
            }
        }
        if (status == 200 && method == Method.GET && ifRangeHolds(req.header("If-Range"), tag, seconds)) {
            range = range(req.header("Range"), length);
            if (range != null) {
                status = range[0] < 0 ? 416 : 206;
            }
        }
        InputStream in = status == 200 || status == 206 ? r.open(path) : null;
        try {
            answer(req, res, in, status, range, length, tag, seconds, type, policy);
        } catch (IOException | RuntimeException e) {
            if (in != null) {
                in.close(); // a refused header (response already committed) must not leak the descriptor
            }
            throw e;
        }
    }

    private static void answer(DefaultRequest req, DefaultResponse res, @Nullable InputStream in, int status,
            long @Nullable [] range, long length, String tag, long seconds, String type, @Nullable CachePolicy policy)
            throws IOException {
        res.header("ETag", tag).header("Last-Modified", format(seconds * 1000)).header("Accept-Ranges", "bytes")
                .header("X-Content-Type-Options", "nosniff");
        if (policy != null) {
            res.cache(policy);
        }
        if (status == 304) {
            res.status(304).end();
        } else if (status == 416) {
            res.status(416).header("Content-Range", "bytes */" + length)
                    .header("Content-Type", StandardErrors.JSON_TYPE);
            res.send(StandardErrors.json(416, StandardErrors.reason(416), req.requestId()));
        } else if (in != null) {
            res.header("Content-Type", type);
            if (range != null) {
                res.header("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + length);
                res.sendBody(206, in, range[0], range[1] - range[0] + 1);
            } else {
                res.sendBody(200, in, 0, length);
            }
        }
    }

    /** Strong, {@code "<hex length>-<hex modified millis>"}. */
    // @req FR-STATIC-009
    static String etag(long length, long modified) {
        return "\"" + Long.toHexString(length) + "-" + Long.toHexString(modified) + "\"";
    }

    /** RFC 9110 weak comparison against a list of entity tags or {@code *}. */
    // @req FR-STATIC-009
    static boolean noneMatch(@Nullable String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        String mine = weakless(etag);
        for (String t : ifNoneMatch.split(",")) {
            String v = t.trim();
            if (v.equals("*") || weakless(v).equals(mine)) {
                return true;
            }
        }
        return false;
    }

    private static String weakless(String tag) {
        return tag.startsWith("W/") ? tag.substring(2) : tag;
    }

    /**
     * The one range {@code {first, last}} of a {@code bytes=} header, the end clipped to the length; {@code {-1}}
     * when unsatisfiable (416); null for no header, several ranges or a malformed one (full 200).
     */
    // @req FR-STATIC-010
    static long @Nullable [] range(@Nullable String header, long length) {
        if (header == null) {
            return null;
        }
        String h = header.trim();
        if (!h.regionMatches(true, 0, "bytes=", 0, 6) || h.indexOf(',') >= 0) {
            return null;
        }
        String spec = h.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String a = spec.substring(0, dash).trim();
        String b = spec.substring(dash + 1).trim();
        if (a.isEmpty()) {
            long suffix = digits(b);
            if (suffix < 0) {
                return null;
            }
            return suffix == 0 || length == 0 ? new long[] {-1} : new long[] {Math.max(0, length - suffix), length - 1};
        }
        long first = digits(a);
        long last = b.isEmpty() ? Long.MAX_VALUE : digits(b);
        if (first < 0 || last < 0 || last < first) {
            return null;
        }
        if (first >= length) {
            return new long[] {-1};
        }
        return new long[] {first, Math.min(last, length - 1)};
    }

    /** The value of an all-digit string, or -1 (also on overflow). */
    private static long digits(String s) {
        if (s.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return -1;
            }
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * True for no If-Range, the current strong ETag, or a date whose whole seconds equal {@code modifiedSeconds}; a
     * weak tag, another tag or another date is false.
     */
    // @req FR-STATIC-010
    static boolean ifRangeHolds(@Nullable String ifRange, String etag, long modifiedSeconds) {
        if (ifRange == null) {
            return true;
        }
        String v = ifRange.trim();
        if (v.startsWith("W/")) {
            return false;
        }
        if (v.startsWith("\"")) {
            return v.equals(etag);
        }
        long d = parseDate(v);
        return d != Long.MIN_VALUE && d / 1000 == modifiedSeconds;
    }

    /** IMF-fixdate, RFC 850 or asctime in epoch millis; {@link Long#MIN_VALUE} when absent or unparsable. */
    static long parseDate(@Nullable String value) {
        if (value == null) {
            return Long.MIN_VALUE;
        }
        String v = value.trim().replaceAll("\\s+", " ");
        for (String f : DATE_FORMATS) {
            ParsePosition pos = new ParsePosition(0);
            Date d = gmt(f).parse(v, pos);
            if (d != null && pos.getIndex() == v.length()) {
                return d.getTime();
            }
        }
        return Long.MIN_VALUE;
    }

    static String format(long millis) {
        return gmt("EEE, dd MMM yyyy HH:mm:ss 'GMT'").format(new Date(millis));
    }

    // SimpleDateFormat is not thread-safe, so each call gets its own.
    private static SimpleDateFormat gmt(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        f.setLenient(false);
        return f;
    }
}
