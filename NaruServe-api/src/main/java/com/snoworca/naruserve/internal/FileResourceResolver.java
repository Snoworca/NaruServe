package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.ResourceResolver;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * The built-in {@code java.io.File} resolver, rooted at a canonical directory. Minimal wave 6 part 5 version: it
 * refuses only paths that are not regular files.
 */
// shortcut: no canonical containment or symlink check yet, wired to no request path; FR-STATIC-006 adds both
// @req IR-STATIC-001
final class FileResourceResolver implements ResourceResolver {

    private final File root;
    private final boolean allowSymlinks;

    FileResourceResolver(File canonicalRoot, boolean allowSymlinks) {
        this.root = canonicalRoot;
        this.allowSymlinks = allowSymlinks;
    }

    // @req IR-STATIC-001
    private File file(String path) throws FileNotFoundException {
        File f = new File(root, path);
        if (!f.isFile()) {
            throw new FileNotFoundException(path);
        }
        return f;
    }

    // @req IR-STATIC-001
    @Override
    public InputStream open(String path) throws IOException {
        return new FileInputStream(file(path));
    }

    // @req IR-STATIC-001
    @Override
    public long length(String path) throws IOException {
        return file(path).length();
    }

    // @req IR-STATIC-001
    @Override
    public String etag(String path) throws IOException {
        File f = file(path);
        return "\"" + Long.toHexString(f.length()) + "-" + Long.toHexString(f.lastModified()) + "\"";
    }

    // @req IR-STATIC-001
    @Override
    public long modified(String path) throws IOException {
        return file(path).lastModified();
    }

    boolean allowSymlinks() {
        return allowSymlinks;
    }
}
