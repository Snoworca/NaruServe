package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.MultipartOptions;
import java.io.File;
import java.util.Objects;

/**
 * The app's multipart settings: range checks on every setter, the defaults until set. The part header limits are
 * fixed constants with no setter.
 */
// @req IR-MULTIPART-001
public final class MultipartSettings implements MultipartOptions {

    /** Bytes of one part header line. */
    public static final int MAX_HEADER_LINE = 8192;

    /** Bytes of all header lines of one part. */
    public static final int MAX_HEADER_TOTAL = 16384;

    private static final long MAX_SIZE = 4294967296L;

    private long maxFileSize = 33554432L;
    private long maxRequestSize = 67108864L;
    private int maxParts = 20;
    private int memoryThreshold = 65536;
    private File tempDirectory;

    /** The defaults, with {@code java.io.tmpdir} read now rather than at each parse. */
    // @req IR-MULTIPART-001
    public MultipartSettings() {
        tempDirectory = new File(System.getProperty("java.io.tmpdir"));
    }

    // @req IR-MULTIPART-001
    private static long range(String name, long value, long max) {
        if (value < 1 || value > max) {
            throw new IllegalArgumentException(name + " must be in 1.." + max + ": " + value);
        }
        return value;
    }

    /** A field-by-field copy, so a configurer that throws leaves this one untouched. */
    // @req IR-MULTIPART-001
    public MultipartSettings copy() {
        MultipartSettings c = new MultipartSettings();
        c.maxFileSize = maxFileSize;
        c.maxRequestSize = maxRequestSize;
        c.maxParts = maxParts;
        c.memoryThreshold = memoryThreshold;
        c.tempDirectory = tempDirectory;
        return c;
    }

    // @req IR-MULTIPART-001
    @Override
    public MultipartOptions maxFileSize(long bytes) {
        maxFileSize = range("maxFileSize", bytes, MAX_SIZE);
        return this;
    }

    // @req IR-MULTIPART-001
    @Override
    public MultipartOptions maxRequestSize(long bytes) {
        maxRequestSize = range("maxRequestSize", bytes, MAX_SIZE);
        return this;
    }

    // @req IR-MULTIPART-001
    @Override
    public MultipartOptions maxParts(int count) {
        maxParts = (int) range("maxParts", count, 10000);
        return this;
    }

    // @req IR-MULTIPART-001
    @Override
    public MultipartOptions memoryThreshold(int bytes) {
        memoryThreshold = (int) range("memoryThreshold", bytes, 16777216);
        return this;
    }

    // @req IR-MULTIPART-001
    @Override
    public MultipartOptions tempDirectory(File dir) {
        tempDirectory = Objects.requireNonNull(dir, "dir");
        return this;
    }

    public long maxFileSize() {
        return maxFileSize;
    }

    public long maxRequestSize() {
        return maxRequestSize;
    }

    public int maxParts() {
        return maxParts;
    }

    public int memoryThreshold() {
        return memoryThreshold;
    }

    public File tempDirectory() {
        return tempDirectory;
    }
}
