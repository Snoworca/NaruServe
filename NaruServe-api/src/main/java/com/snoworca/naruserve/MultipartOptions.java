package com.snoworca.naruserve;

import java.io.File;

/**
 * Multipart settings, configured through {@code app.multipart(o -> o.maxFileSize(1024 * 1024))}. Each setter returns
 * these options; a value outside its range throws {@link IllegalArgumentException} naming the setting and the range.
 * There is no unlimited value; each setting has the default and range shown on its setter. The dispatcher uses a copy
 * taken at {@code listen}, so changing a retained options object afterwards has no effect.
 */
// @req IR-MULTIPART-001
public interface MultipartOptions {

    /** Bytes of one uploaded file, {@code 1..4294967296}; default 33554432 (32 MiB). */
    MultipartOptions maxFileSize(long bytes);

    /** Bytes of the whole multipart body, {@code 1..4294967296}; default 67108864 (64 MiB). */
    MultipartOptions maxRequestSize(long bytes);

    /** Parts in one request, {@code 1..10000}; default 20. */
    MultipartOptions maxParts(int count);

    /** Bytes a file part keeps in memory before it spools to disk, {@code 1..16777216}; default 65536 (64 KiB). */
    MultipartOptions memoryThreshold(int bytes);

    /**
     * Directory for spooled parts; default {@code java.io.tmpdir} as read when the app was created. A null
     * {@code dir} throws {@link NullPointerException}.
     */
    MultipartOptions tempDirectory(File dir);
}
