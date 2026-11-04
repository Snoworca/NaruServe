package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.auth.internal.AtomicFiles;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.spi.RefreshRecord;
import com.snoworca.naruserve.utils.auth.spi.RefreshTokenStore;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Refresh-token records in one JSON file, the default beside a {@code FileUserStore} ({@code users-refresh-tokens.json}
 * beside {@code users.json}), so sessions survive a restart. The file
 * {@code {"format":"naru-refresh-tokens","version":1,"records":[...]}} is rewritten atomically (see
 * {@code AtomicFiles}) only when a record changes: a token issued, rotated or revoked, or expired records removed by
 * the cleanup; a failed login never writes it. A family keeps its current record and its 32 most recently used
 * predecessors (older used records are dropped when the next one is saved), so a long-lived session does not grow the
 * file; presenting one of those predecessors again still revokes the family, an older token is a plain 401. Saving a
 * record also drops every revoked record (a logged-out or reused family) and every record expired at the new
 * record's creation time, which answer a plain 401 either way, so login and logout churn does not grow the file
 * either. Every session the file holds can always rotate again: when the file would no longer have room for one more
 * rotation of every live family, each family keeps only its most recently used record (the predecessor of its
 * current token, whose reuse still revokes the family) instead of 32, so a rotation needs no new bytes. A new sign-in
 * (a record of a family the file does not hold) is refused with an {@code IOException} (503) when, even in that
 * compact form, the file could not hold one more rotation of every session including the new one, or would come into
 * the last eighth of its size limit; the existing sessions keep refreshing. An
 * unknown version or a file above the size limit fails construction with a message starting with {@code FAIL} and is
 * not overwritten. Thread-safe within one process; one file has one writer. Not shared between nodes, so
 * {@code Deployment.MULTI_INSTANCE} refuses it.
 */
// @req FR-TOKEN-002
public final class FileRefreshTokenStore implements RefreshTokenStore {

    static final String FORMAT = "naru-refresh-tokens";
    static final long VERSION = 1;
    /**
     * How many used records of one family the file keeps for reuse detection (32 rotations, 8 h at 15 min); one in
     * the compact form the file takes near its size limit.
     */
    static final int USED_PER_FAMILY = 32;

    private final File file;
    private final MiniJson.Limits limits;
    private Map<String, RefreshRecord> records = new LinkedHashMap<>();
    /**
     * The AuthFileKey of the FileUserStore this store is the default of (FR-IAM-015 AC-2); none of this file's values
     * is a secret (each record holds the SHA-256 of a 256-bit random token), so nothing here is encrypted with it.
     */
    private final @Nullable AuthFileKey fileKey;

    /** A store over {@code file}, created on the first write when absent; a corrupt file fails here. */
    // @req FR-TOKEN-002
    public FileRefreshTokenStore(File file) throws IOException {
        this(file, MiniJson.DEFAULT_FILE_BYTES, new ConsoleLogger());
    }

    /** A store over {@code file} read within {@code maxFileBytes}; recovery warnings go to {@code logger}. */
    // @req FR-TOKEN-002
    public FileRefreshTokenStore(File file, long maxFileBytes, Logger logger) throws IOException {
        this(file, maxFileBytes, logger, null);
    }

    /** The default beside a FileUserStore, carrying that store's key (FR-IAM-015 AC-2). */
    // @req FR-IAM-015
    FileRefreshTokenStore(File file, long maxFileBytes, Logger logger, @Nullable AuthFileKey fileKey)
            throws IOException {
        this.fileKey = fileKey;
        this.file = Objects.requireNonNull(file, "file");
        this.limits = MiniJson.Limits.file(maxFileBytes);
        Map<String, RefreshRecord> loaded = AtomicFiles.load(file, limits.maxTotalBytes(), this::tooLarge, this::parse,
                logger);
        if (loaded != null) {
            records = loaded;
        }
    }

    /** The file this store writes. */
    public File getFile() {
        return file;
    }

    /** The AuthFileKey this default store carries; null when none. */
    @Nullable AuthFileKey fileKey() {
        return fileKey;
    }

    private String tooLarge(long length) {
        return "the refresh token file has " + length + " bytes, above the limit of " + limits.maxTotalBytes()
                + " bytes; raise it with new FileRefreshTokenStore(file, maxFileBytes, logger), or move to an "
                + "app-provided RefreshTokenStore backed by the application's database";
    }

    @SuppressWarnings("unchecked")
    private Map<String, RefreshRecord> parse(byte[] content) {
        if (content.length > limits.maxTotalBytes()) {
            throw new IllegalArgumentException(tooLarge(content.length));
        }
        Object doc = MiniJson.parse(content, limits);
        if (!(doc instanceof Map) || !FORMAT.equals(((Map<String, Object>) doc).get("format"))) {
            throw new IllegalArgumentException("not a naru-refresh-tokens file");
        }
        Map<String, Object> m = (Map<String, Object>) doc;
        Object version = m.get("version");
        if (!Long.valueOf(VERSION).equals(version)) {
            throw new IllegalArgumentException("unknown naru-refresh-tokens format version " + version + "; this "
                    + "release reads version " + VERSION);
        }
        Object list = m.get("records");
        if (!(list instanceof List)) {
            throw new IllegalArgumentException("records is not a list");
        }
        Map<String, RefreshRecord> out = new LinkedHashMap<>();
        for (Object o : (List<Object>) list) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("a record is not an object");
            }
            Map<String, Object> r = (Map<String, Object>) o;
            Object used = r.get("usedAt");
            RefreshRecord rec = RefreshRecord.builder().tokenId(text(r, "tokenId")).familyId(text(r, "familyId"))
                    .userId(text(r, "userId")).tokenHash(text(r, "tokenHash")).createdAt(number(r, "createdAt"))
                    .expiresAt(number(r, "expiresAt")).usedAt(used == null ? null : number(r, "usedAt"))
                    .revoked(Boolean.TRUE.equals(r.get("revoked"))).build();
            out.put(rec.getTokenId(), rec);
        }
        return out;
    }

    private static String text(Map<String, Object> r, String name) {
        Object v = r.get(name);
        if (!(v instanceof String)) {
            throw new IllegalArgumentException(name + " is not a string");
        }
        return (String) v;
    }

    private static long number(Map<String, Object> r, String name) {
        Object v = r.get(name);
        if (!(v instanceof Long)) {
            throw new IllegalArgumentException(name + " is not an integer");
        }
        return (Long) v;
    }

    /** Writes {@code next} and makes it current; a failed write leaves memory unchanged. */
    private void commit(Map<String, RefreshRecord> next) throws IOException {
        commit(next, encode(next));
    }

    private void commit(Map<String, RefreshRecord> next, byte[] content) throws IOException {
        AtomicFiles.write(file, content);
        records = next;
    }

    /** The file content of {@code next}; a document above the size limit is a MiniJson.JsonFormatException. */
    private byte[] encode(Map<String, RefreshRecord> next) {
        Map<String, Object> doc = header();
        List<Object> list = new ArrayList<>();
        for (RefreshRecord r : next.values()) {
            list.add(json(r));
        }
        doc.put("records", list);
        return MiniJson.write(doc, limits).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> header() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", FORMAT);
        doc.put("version", VERSION);
        return doc;
    }

    private static Map<String, Object> json(RefreshRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tokenId", r.getTokenId());
        m.put("familyId", r.getFamilyId());
        m.put("userId", r.getUserId());
        m.put("tokenHash", r.getTokenHash());
        m.put("createdAt", r.getCreatedAt());
        m.put("expiresAt", r.getExpiresAt());
        m.put("usedAt", r.getUsedAt());
        m.put("revoked", r.isRevoked());
        return m;
    }

    /** How many bytes a record grows when it is marked used: {@code null} becomes at most 19 digits. */
    private static final int USED_AT_GROWTH = 15;

    /** The file content of {@code next} as {@link #encode} writes it, with the bytes it would need (see need). */
    private final class Measured {
        final byte[] content;
        /**
         * The bytes the file needs for this content to take one more rotation of every live family: each unused record
         * grows when it is marked used, and a family holding no used record yet gains one record of its current one's
         * size when it rotates. A family that already holds a used record rotates in place in the compact form (its
         * older used records make room for the new one), so it needs nothing more.
         */
        final long need;

        Measured(Map<String, RefreshRecord> next) {
            StringBuilder b = new StringBuilder();
            Map<String, Object> doc = header();
            doc.put("records", new ArrayList<Object>());
            String empty = MiniJson.write(doc, limits); // ...,"records":[]}
            b.append(empty, 0, empty.length() - 2);
            Map<String, Long> unusedHead = new LinkedHashMap<>();
            Map<String, Boolean> hasUsed = new LinkedHashMap<>();
            long extra = 0;
            boolean first = true;
            for (RefreshRecord r : next.values()) {
                String one = MiniJson.write(json(r), limits);
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(one);
                if (r.isRevoked()) {
                    continue;
                }
                if (r.getUsedAt() != null) {
                    hasUsed.put(r.getFamilyId(), Boolean.TRUE);
                } else {
                    extra += USED_AT_GROWTH;
                    unusedHead.put(r.getFamilyId(), one.getBytes(StandardCharsets.UTF_8).length + 1L
                            + USED_AT_GROWTH * 2L);
                }
            }
            b.append("]}");
            this.content = b.toString().getBytes(StandardCharsets.UTF_8);
            for (Map.Entry<String, Long> e : unusedHead.entrySet()) {
                if (!hasUsed.containsKey(e.getKey())) {
                    extra += e.getValue();
                }
            }
            this.need = content.length + extra;
        }
    }

    /**
     * {@code next} in the compact form: every family keeps only its most recently used record (the predecessor of its
     * current token), so presenting that one again still revokes the family.
     */
    // @req SEC-TOKEN-006
    private static Map<String, RefreshRecord> compact(Map<String, RefreshRecord> next) {
        Map<String, RefreshRecord> newestUsed = new LinkedHashMap<>();
        for (RefreshRecord r : next.values()) {
            if (r.getUsedAt() != null) {
                RefreshRecord kept = newestUsed.get(r.getFamilyId());
                // of equal usedAt the record stored later counts as more recent
                if (kept == null || usedAt(r) >= usedAt(kept)) {
                    newestUsed.put(r.getFamilyId(), r);
                }
            }
        }
        Map<String, RefreshRecord> out = new LinkedHashMap<>();
        for (RefreshRecord r : next.values()) {
            if (r.getUsedAt() == null || newestUsed.get(r.getFamilyId()) == r) {
                out.put(r.getTokenId(), r);
            }
        }
        return out;
    }

    // @req FR-TOKEN-002
    @Override
    public synchronized void save(RefreshRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        if (records.containsKey(record.getTokenId())) {
            throw new IllegalArgumentException("token id " + record.getTokenId() + " is stored already");
        }
        long now = record.getCreatedAt();
        boolean signIn = true;
        Map<String, RefreshRecord> next = new LinkedHashMap<>();
        for (RefreshRecord r : records.values()) {
            if (r.getFamilyId().equals(record.getFamilyId())) {
                signIn = false;
            }
            // a revoked or expired record answers like an unknown token (401), so it need not stay in the file
            if (!r.isRevoked() && r.getExpiresAt() > now) {
                next.put(r.getTokenId(), r);
            }
        }
        pruneUsed(next, record.getFamilyId());
        next.put(record.getTokenId(), record);
        long limit = limits.maxTotalBytes();
        Measured m = new Measured(next);
        if (m.need > limit) {
            // close to the limit: every family keeps only its most recently used record, so a rotation needs no new
            // bytes and every admitted session can keep refreshing
            next = compact(next);
            m = new Measured(next);
        }
        long admission = limit - limit / 8;
        if (signIn && (m.need > limit || m.content.length > admission)) {
            throw new IOException("the refresh token file would grow to " + m.content.length + " bytes and would "
                    + "need " + m.need + " bytes for one more rotation of every session, above its limit of " + limit
                    + " bytes or within its last eighth, so new sign-ins are refused until sessions expire or log out "
                    + "(existing sessions keep refreshing); raise the limit with new FileRefreshTokenStore(file, "
                    + "maxFileBytes, logger), or move to an app-provided RefreshTokenStore backed by the "
                    + "application's database");
        }
        if (m.content.length > limit) {
            throw new IOException("the refresh token file would grow to " + m.content.length + " bytes, above its "
                    + "limit of " + limit + " bytes; raise the limit with new FileRefreshTokenStore(file, maxFileBytes, "
                    + "logger), or move to an app-provided RefreshTokenStore backed by the application's database");
        }
        byte[] content = m.content;
        commit(next, content);
    }

    /**
     * Keeps the file from growing with a long-lived session: of the used records of {@code familyId} only the
     * {@link #USED_PER_FAMILY} most recently used ones stay (the predecessors of the record being saved, so presenting
     * one of them again is still detected as reuse); older used records of the family are dropped. A token older than
     * that is then answered as an unknown token (401) without revoking the family.
     */
    // @req FR-TOKEN-002
    private static void pruneUsed(Map<String, RefreshRecord> next, String familyId) {
        List<RefreshRecord> used = new ArrayList<>();
        for (RefreshRecord r : next.values()) {
            if (r.getUsedAt() != null && r.getFamilyId().equals(familyId)) {
                used.add(r);
            }
        }
        if (used.size() <= USED_PER_FAMILY) {
            return;
        }
        // stable: of equal usedAt the record stored later counts as more recent
        Collections.sort(used, new Comparator<RefreshRecord>() {
            @Override
            public int compare(RefreshRecord a, RefreshRecord b) {
                return Long.compare(usedAt(a), usedAt(b));
            }
        });
        for (RefreshRecord r : used.subList(0, used.size() - USED_PER_FAMILY)) {
            next.remove(r.getTokenId());
        }
    }

    private static long usedAt(RefreshRecord r) {
        Long v = r.getUsedAt();
        return v == null ? 0L : v;
    }

    @Override
    public synchronized @Nullable RefreshRecord find(String tokenId) {
        return records.get(tokenId);
    }

    // @req FR-TOKEN-002
    @Override
    public synchronized boolean markUsed(String tokenId, long nowMillis) throws IOException {
        RefreshRecord r = records.get(tokenId);
        if (r == null || r.getUsedAt() != null || r.isRevoked()) {
            return false;
        }
        Map<String, RefreshRecord> next = new LinkedHashMap<>(records);
        next.put(tokenId, r.toBuilder().usedAt(nowMillis).build());
        commit(next);
        return true;
    }

    @Override
    public synchronized int revokeFamily(String familyId) throws IOException {
        Map<String, RefreshRecord> next = new LinkedHashMap<>(records);
        int n = 0;
        for (Map.Entry<String, RefreshRecord> e : next.entrySet()) {
            RefreshRecord r = e.getValue();
            if (r.getFamilyId().equals(familyId) && !r.isRevoked()) {
                e.setValue(r.toBuilder().revoked(true).build());
                n++;
            }
        }
        if (n > 0) {
            commit(next);
        }
        return n;
    }

    // @req FR-TOKEN-002
    @Override
    public synchronized int purgeExpired(long nowMillis) throws IOException {
        Map<String, RefreshRecord> next = new LinkedHashMap<>();
        for (RefreshRecord r : records.values()) {
            if (r.getExpiresAt() > nowMillis) {
                next.put(r.getTokenId(), r);
            }
        }
        int n = records.size() - next.size();
        if (n > 0) {
            commit(next);
        }
        return n;
    }

    @Override
    public synchronized String toString() {
        return "FileRefreshTokenStore{file=" + file + ", records=" + records.size() + "}";
    }
}
