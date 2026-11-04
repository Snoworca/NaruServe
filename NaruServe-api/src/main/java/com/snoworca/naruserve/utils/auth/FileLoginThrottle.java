package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.internal.AtomicFiles;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.spi.ForwardingLoginThrottle;
import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The login throttle beside a {@code FileUserStore} ({@code users-login-throttle.json} beside {@code users.json}): the
 * counters live in an {@link InMemoryLoginThrottle} and are written to the file as a snapshot every 60 seconds (by the
 * auth snapshot task) and when auth stops, never once per failed login. Every key is kept as the base64url SHA-256 of
 * the key, so neither memory nor the file holds a login name or an address, and every entry has the same size. The
 * file {@code {"format":"naru-login-throttle","version":1,"keys":[{"key":..,"failures":[..]}]}} is written through
 * {@code AtomicFiles}; an unknown version or a corrupt file fails construction with a message starting with
 * {@code FAIL} and is not overwritten. It honours the limit arguments like the in-memory throttle.
 */
// @req REL-FLOW-001
// @req IR-FLOW-003
final class FileLoginThrottle extends ForwardingLoginThrottle {

    static final String FORMAT = "naru-login-throttle";
    static final long VERSION = 1;
    /**
     * The file limit: a hashed key entry is at most about 1.5 KB (43 key characters and up to 100 failure times at the
     * largest built-in limit), so 100,000 keys fit in 256 MB.
     */
    static final long MAX_FILE_BYTES = 256L * 1024 * 1024;

    private final File file;
    private final InMemoryLoginThrottle memory;
    private final MiniJson.Limits limits = MiniJson.Limits.file(MAX_FILE_BYTES);
    private final Object writeLock = new Object();
    /** Counters changed since the last snapshot. */
    private volatile boolean dirty;
    private volatile int writes;
    /** The AuthFileKey of the FileUserStore beside it (FR-IAM-015 AC-2); throttle keys are hashes, not secrets. */
    private final @Nullable AuthFileKey fileKey;

    /** A throttle over {@code file}, restoring a snapshot written earlier; a corrupt or unknown file fails here. */
    // @req REL-FLOW-001
    FileLoginThrottle(File file, TimeSource time, Logger logger) throws IOException {
        this(file, time, logger, null);
    }

    /** {@link #FileLoginThrottle(File, TimeSource, Logger)} carrying the user store's key (FR-IAM-015 AC-2). */
    // @req FR-IAM-015
    FileLoginThrottle(File file, TimeSource time, Logger logger, @Nullable AuthFileKey fileKey) throws IOException {
        this(file, InMemoryLoginThrottle.builder().timeSource(time).logger(logger).build(), logger, fileKey);
    }

    private FileLoginThrottle(File file, InMemoryLoginThrottle memory, Logger logger, @Nullable AuthFileKey fileKey)
            throws IOException {
        super(memory);
        this.fileKey = fileKey;
        this.file = Objects.requireNonNull(file, "file");
        this.memory = memory;
        Map<String, long[]> loaded = AtomicFiles.load(file, limits.maxTotalBytes(), this::tooLarge, this::parse,
                logger);
        if (loaded != null) {
            memory.restore(loaded);
        }
    }

    /** The snapshot file. */
    File getFile() {
        return file;
    }

    /** The AuthFileKey this default throttle carries; null when none. */
    @Nullable AuthFileKey fileKey() {
        return fileKey;
    }

    /** How many snapshots this throttle wrote; for the tests. */
    int writes() {
        return writes;
    }

    /** The stored form of {@code key}: base64url(SHA-256(UTF-8 key)). */
    // @req REL-FLOW-001
    static String hashed(String key) {
        try {
            return Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(
                    Objects.requireNonNull(key, "key").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    @Override
    public long retryAfter(String key) throws Exception {
        return super.retryAfter(hashed(key));
    }

    @Override
    public long retryAfter(String key, int max, long windowMillis) throws Exception {
        return super.retryAfter(hashed(key), max, windowMillis);
    }

    // the changes mark the counters dirty after they happened, so a snapshot taken meanwhile is followed by another

    @Override
    public long recordFailure(String key) throws Exception {
        try {
            return super.recordFailure(hashed(key));
        } finally {
            dirty = true;
        }
    }

    @Override
    public long recordFailure(String key, int max, long windowMillis) throws Exception {
        try {
            return super.recordFailure(hashed(key), max, windowMillis);
        } finally {
            dirty = true;
        }
    }

    @Override
    public void reset(String key) throws Exception {
        try {
            super.reset(hashed(key));
        } finally {
            dirty = true;
        }
    }

    /** Forgets the keys whose newest failure is older than {@code cutoffMillis}; see the in-memory throttle. */
    // @req REL-FLOW-001
    int purgeOlderThan(long cutoffMillis) {
        int n = memory.purgeOlderThan(cutoffMillis);
        if (n > 0) {
            dirty = true;
        }
        return n;
    }

    /** Writes the counters when they changed since the last snapshot; the snapshot task calls it every 60 seconds. */
    // @req REL-FLOW-001
    void snapshot() throws IOException {
        synchronized (writeLock) {
            if (!dirty) {
                return;
            }
            dirty = false;
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("format", FORMAT);
            doc.put("version", VERSION);
            List<Object> keys = new ArrayList<>();
            for (Map.Entry<String, long[]> e : memory.snapshot().entrySet()) {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("key", e.getKey());
                List<Object> times = new ArrayList<>();
                for (long t : e.getValue()) {
                    times.add(t);
                }
                k.put("failures", times);
                keys.add(k);
            }
            doc.put("keys", keys);
            try {
                AtomicFiles.write(file, MiniJson.write(doc, limits).getBytes(StandardCharsets.UTF_8));
                writes++;
            } catch (IOException | RuntimeException e) {
                dirty = true; // the next tick tries again
                throw e;
            }
        }
    }

    private String tooLarge(long length) {
        return "the login throttle file has " + length + " bytes, above the limit of " + limits.maxTotalBytes()
                + " bytes";
    }

    @SuppressWarnings("unchecked")
    private Map<String, long[]> parse(byte[] content) {
        if (content.length > limits.maxTotalBytes()) {
            throw new IllegalArgumentException(tooLarge(content.length));
        }
        Object doc = MiniJson.parse(content, limits);
        if (!(doc instanceof Map) || !FORMAT.equals(((Map<String, Object>) doc).get("format"))) {
            throw new IllegalArgumentException("not a " + FORMAT + " file");
        }
        Map<String, Object> m = (Map<String, Object>) doc;
        Object version = m.get("version");
        if (!Long.valueOf(VERSION).equals(version)) {
            throw new IllegalArgumentException("unknown " + FORMAT + " format version " + version + "; this release "
                    + "reads version " + VERSION);
        }
        Object list = m.get("keys");
        if (!(list instanceof List)) {
            throw new IllegalArgumentException("keys is not a list");
        }
        Map<String, long[]> out = new LinkedHashMap<>();
        for (Object o : (List<Object>) list) {
            if (!(o instanceof Map) || !(((Map<String, Object>) o).get("key") instanceof String)
                    || !(((Map<String, Object>) o).get("failures") instanceof List)) {
                throw new IllegalArgumentException("a key entry is not {key, failures}");
            }
            Map<String, Object> k = (Map<String, Object>) o;
            List<Object> times = (List<Object>) k.get("failures");
            long[] t = new long[times.size()];
            for (int i = 0; i < t.length; i++) {
                if (!(times.get(i) instanceof Long)) {
                    throw new IllegalArgumentException("a failure time is not an integer");
                }
                t[i] = (Long) times.get(i);
            }
            out.put((String) k.get("key"), t);
        }
        return out;
    }

    @Override
    public String toString() {
        return "FileLoginThrottle{file=" + file + "}";
    }
}
