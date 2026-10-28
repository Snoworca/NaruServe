package com.snoworca.naruserve.internal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The {@code directoryListing(true)} body: one relative link per entry, sorted by {@link String#compareTo} on the raw
 * name, the text HTML-escaped and the href percent-encoded UTF-8; dot entries, symbolic links and names StaticPath refuses are left out.
 */
// @req FR-STATIC-003
final class DirectoryListing {

    private DirectoryListing() {
    }

    // @req FR-STATIC-003
    static String render(String rawDirPath, File dir) throws IOException {
        String base = dir.getCanonicalPath();
        String[] names = dir.list();
        List<String> sorted = new ArrayList<>();
        if (names != null) {
            Collections.addAll(sorted, names);
        }
        Collections.sort(sorted);
        String title = escape(rawDirPath);
        StringBuilder sb = new StringBuilder("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>Index of ")
                .append(title).append("</title></head><body><h1>Index of ").append(title).append("</h1><ul>\n");
        for (String name : sorted) {
            File f = new File(dir, name);
            String expected = base.endsWith(File.separator) ? base + name : base + File.separator + name;
            if (name.startsWith(".") || !f.getCanonicalPath().equals(expected) || StaticPath.check(encode(name)) != 0) {
                continue; // hidden, a symbolic link, or a name whose own link StaticPath would refuse
            }
            String slash = f.isDirectory() ? "/" : "";
            sb.append("<li><a href=\"").append(encode(name)).append(slash).append("\">").append(escape(name))
                    .append(slash).append("</a></li>\n");
        }
        return sb.append("</ul></body></html>\n").toString();
    }

    static String escape(String s) {
        return com.snoworca.naruserve.common.HtmlEscaping.escape(s);
    }

    /** UTF-8 percent-encoding of everything but RFC 3986 unreserved characters. */
    static String encode(String s) {
        return PercentDecoder.percentEncode(s, "-._~");
    }
}
