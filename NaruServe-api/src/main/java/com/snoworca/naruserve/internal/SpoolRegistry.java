package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The open temporary upload files of one app; no static state. */
// @req FR-MULTIPART-004
// @req FR-MULTIPART-006
public final class SpoolRegistry {

    private final Set<File> open = new LinkedHashSet<>();
    private boolean closed;

    /** A new server-named temp file in {@code dir}, never named after client input; HttpException(500) once closed. */
    // @req FR-MULTIPART-004
    public File createTemp(File dir) throws IOException {
        synchronized (this) {
            if (closed) {
                throw new HttpException(500, "upload spool is closed");
            }
        }
        File f = File.createTempFile("naru-upload-", ".tmp", dir);
        synchronized (this) {
            if (!closed) {
                open.add(f);
                return f;
            }
        }
        f.delete();
        throw new HttpException(500, "upload spool is closed");
    }

    /** Deletes {@code f} and stops tracking it. */
    // @req FR-MULTIPART-004
    public void forget(File f) {
        synchronized (this) {
            open.remove(f);
        }
        f.delete();
    }

    /** Deletes every open temp file, then refuses new ones. */
    // @req FR-MULTIPART-006
    public void closeAndDeleteAll() {
        List<File> files;
        synchronized (this) {
            closed = true;
            files = new ArrayList<>(open);
            open.clear();
        }
        for (File f : files) {
            f.delete();
        }
    }
}
