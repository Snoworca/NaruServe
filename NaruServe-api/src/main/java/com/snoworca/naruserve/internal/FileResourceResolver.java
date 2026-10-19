package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.ResourceResolver;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import org.jspecify.annotations.Nullable;

/**
 * The built-in {@code java.io.File} resolver, rooted at a canonical directory. A path is served only when its
 * canonical path lies under the root and, unless symbolic links are allowed, no component below the root is a
 * symbolic link; both checks run again after the open. Residual limit: {@code java.io} cannot bind the check to the
 * opened descriptor, so a component swapped between the re-check and the read is not seen.
 */
// @req IR-STATIC-001
// @req FR-STATIC-006
final class FileResourceResolver implements ResourceResolver {

    private final File root;
    private final String rootPath;
    private final boolean allowSymlinks;
    private volatile @Nullable Runnable afterCheck;

    FileResourceResolver(File canonicalRoot, boolean allowSymlinks) {
        this.root = canonicalRoot;
        this.rootPath = canonicalRoot.getPath();
        this.allowSymlinks = allowSymlinks;
    }

    /**
     * The resolver for {@code res.file} and {@code res.download}: rooted at the canonical absolute parent of
     * {@code file}, read by the file's name, symbolic links refused. Ancestors above that parent are not checked.
     */
    // @req FR-STATIC-006
    static FileResourceResolver forFile(File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null) {
            throw new FileNotFoundException(file.getPath());
        }
        return new FileResourceResolver(parent.getCanonicalFile(), false);
    }

    /** Test hook run between the first check and the open. */
    void afterCheck(@Nullable Runnable hook) {
        this.afterCheck = hook;
    }

    /** The canonical path of {@code root/path} after the containment, link and regular-file checks. */
    // @req FR-STATIC-006
    private String check(File f, String path) throws IOException {
        String canonical = f.getCanonicalPath();
        if (!under(canonical) || (!allowSymlinks && !linkFree(f)) || !new File(canonical).isFile()) {
            throw new FileNotFoundException(path);
        }
        return canonical;
    }

    /** Root itself, or below it on a separator boundary: {@code /root2} is not under {@code /root}. */
    private boolean under(String canonical) {
        String prefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
        return canonical.startsWith(prefix);
    }

    /**
     * Whether no component of {@code f} below the root is a symbolic link: walking down from the root, each
     * component's canonical path equals its parent's canonical path plus separator plus its name.
     */
    // @req FR-STATIC-006
    boolean linkFree(File f) throws IOException {
        Deque<String> names = new ArrayDeque<>();
        File cur = f;
        while (cur != null && !cur.getPath().equals(rootPath)) {
            names.push(cur.getName());
            cur = cur.getParentFile();
        }
        if (cur == null) {
            return false;
        }
        String parent = rootPath;
        for (String name : names) {
            String expected = parent.endsWith(File.separator) ? parent + name : parent + File.separator + name;
            if (!new File(expected).getCanonicalPath().equals(expected)) {
                return false;
            }
            parent = expected;
        }
        return true;
    }

    private File file(String path) throws IOException {
        File f = new File(root, path);
        check(f, path);
        return f;
    }

    // @req IR-STATIC-001
    // @req FR-STATIC-006
    @Override
    public InputStream open(String path) throws IOException {
        File f = new File(root, path);
        String before = check(f, path);
        Runnable hook = afterCheck;
        if (hook != null) {
            hook.run();
        }
        InputStream in = new FileInputStream(f);
        boolean ok = false;
        try {
            ok = check(f, path).equals(before);
        } catch (FileNotFoundException e) {
            // the re-check refused it; answered like a missing file below
        } finally {
            if (!ok) {
                in.close();
            }
        }
        if (!ok) {
            throw new FileNotFoundException(path);
        }
        return in;
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
