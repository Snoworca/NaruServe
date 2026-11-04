package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.internal.AtomicFiles;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.spi.DeviceToken;
import com.snoworca.naruserve.utils.auth.spi.DeviceTrust;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The device trust beside a {@code FileUserStore} ({@code users-device-trust.json} beside {@code users.json}): the
 * per-token state lives in an {@link InMemoryDeviceTrust}; the failure counts are written with the 60-second snapshot
 * and when auth stops, while an invalidation is written at once, before {@link #recordFailure} returns, so an
 * invalidated token stays invalid after a restart. The file
 * {@code {"format":"naru-device-trust","version":1,"tokens":[{"tokenId","expiresAt","failures","windowStart",
 * "invalidated"}]}} is written through {@code AtomicFiles}; an unknown version or a corrupt file fails construction
 * with a message starting with {@code FAIL} and is not overwritten.
 */
// @req REL-FLOW-001
final class FileDeviceTrust implements DeviceTrust {

    static final String FORMAT = "naru-device-trust";
    static final long VERSION = 1;

    private final File file;
    private final InMemoryDeviceTrust memory;
    private final MiniJson.Limits limits = MiniJson.Limits.file(FileLoginThrottle.MAX_FILE_BYTES);
    private final Object writeLock = new Object();
    private volatile boolean dirty;
    private volatile int writes;
    /** The AuthFileKey of the FileUserStore beside it (FR-IAM-015 AC-2); token ids and counters are not secrets. */
    private final @Nullable AuthFileKey fileKey;

    /** A trust store over {@code file}, restoring what was written earlier; a corrupt or unknown file fails here. */
    // @req REL-FLOW-001
    FileDeviceTrust(File file, TimeSource time, Logger logger) throws IOException {
        this(file, time, logger, null);
    }

    /** {@link #FileDeviceTrust(File, TimeSource, Logger)} carrying the user store's key (FR-IAM-015 AC-2). */
    // @req FR-IAM-015
    FileDeviceTrust(File file, TimeSource time, Logger logger, @Nullable AuthFileKey fileKey) throws IOException {
        this.fileKey = fileKey;
        this.file = Objects.requireNonNull(file, "file");
        this.memory = new InMemoryDeviceTrust(time, InMemoryLoginThrottle.defaultMaxKeys(
                System.getProperty("java.vm.name"), System.getProperty("java.vendor")), logger);
        Map<String, long[]> loaded = AtomicFiles.load(file, limits.maxTotalBytes(), this::tooLarge, this::parse,
                logger);
        if (loaded != null) {
            memory.restore(loaded);
        }
    }

    /** The file. */
    File getFile() {
        return file;
    }

    /** The AuthFileKey this default store carries; null when none. */
    @Nullable AuthFileKey fileKey() {
        return fileKey;
    }

    /** How many times this store wrote its file; for the tests. */
    int writes() {
        return writes;
    }

    @Override
    public boolean isInvalidated(DeviceToken token) {
        return memory.isInvalidated(token);
    }

    /** Counts the failure; an invalidation it causes is on disk before this returns. */
    // @req REL-FLOW-001
    @Override
    public boolean recordFailure(DeviceToken token, int max, long windowMillis) throws IOException {
        boolean before = memory.isInvalidated(token);
        boolean after = memory.recordFailure(token, max, windowMillis);
        dirty = true;
        if (after && !before) {
            write(true);
        }
        return after;
    }

    @Override
    public int purgeExpired(long nowMillis) {
        int n = memory.purgeExpired(nowMillis);
        if (n > 0) {
            dirty = true;
        }
        return n;
    }

    /** Writes the state when it changed since the last write; the snapshot task calls it every 60 seconds. */
    // @req REL-FLOW-001
    void snapshot() throws IOException {
        write(false);
    }

    private void write(boolean always) throws IOException {
        synchronized (writeLock) {
            if (!always && !dirty) {
                return;
            }
            dirty = false;
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("format", FORMAT);
            doc.put("version", VERSION);
            List<Object> tokens = new ArrayList<>();
            for (Map.Entry<String, long[]> e : memory.snapshot().entrySet()) {
                long[] v = e.getValue();
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("tokenId", e.getKey());
                t.put("expiresAt", v[0]);
                t.put("failures", v[1]);
                t.put("windowStart", v[2]);
                t.put("invalidated", v[3] != 0);
                tokens.add(t);
            }
            doc.put("tokens", tokens);
            try {
                AtomicFiles.write(file, MiniJson.write(doc, limits).getBytes(StandardCharsets.UTF_8));
                writes++;
            } catch (IOException | RuntimeException e) {
                dirty = true;
                throw e;
            }
        }
    }

    private String tooLarge(long length) {
        return "the device trust file has " + length + " bytes, above the limit of " + limits.maxTotalBytes()
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
        Object list = m.get("tokens");
        if (!(list instanceof List)) {
            throw new IllegalArgumentException("tokens is not a list");
        }
        Map<String, long[]> out = new LinkedHashMap<>();
        for (Object o : (List<Object>) list) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("a token entry is not an object");
            }
            Map<String, Object> t = (Map<String, Object>) o;
            Object id = t.get("tokenId");
            if (!(id instanceof String)) {
                throw new IllegalArgumentException("tokenId is not a string");
            }
            out.put((String) id, new long[] {number(t, "expiresAt"), number(t, "failures"), number(t, "windowStart"),
                Boolean.TRUE.equals(t.get("invalidated")) ? 1 : 0});
        }
        return out;
    }

    private static long number(Map<String, Object> t, String name) {
        Object v = t.get(name);
        if (!(v instanceof Long)) {
            throw new IllegalArgumentException(name + " is not an integer");
        }
        return (Long) v;
    }

    @Override
    public String toString() {
        return "FileDeviceTrust{file=" + file + "}";
    }
}
