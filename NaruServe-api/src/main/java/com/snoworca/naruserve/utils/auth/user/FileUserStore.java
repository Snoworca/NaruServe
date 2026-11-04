package com.snoworca.naruserve.utils.auth.user;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.auth.AuthFileKey;
import com.snoworca.naruserve.utils.auth.internal.AtomicFiles;
import com.snoworca.naruserve.utils.auth.internal.FileStoreKeys;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.internal.SecretBox;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import org.jspecify.annotations.Nullable;

/**
 * Users in one JSON file, rewritten atomically on every change (temp file, sync, rename; see {@code AtomicFiles}) and
 * read back on construction, for small installations. The file is
 * {@code {"format":"naru-users","version":1,"nextId":..,"users":[...]}} and is read only by the strict auth JSON
 * reader, so a file larger than the limit (8 MB by default) or of an unknown version fails construction with a
 * message starting with {@code FAIL} and is never overwritten. Every row is written with its password hash: plain
 * without an {@link AuthFileKey} (format version 1), and as {@code enc(...)} under one (format version 2, so a reader
 * that knows only version 1 refuses the file instead of reading an encrypted value as a hash). Constructed with a key
 * over a version-1 file, the store rewrites it once as version 2; a version-2 file needs its key, and a value that
 * does not open (changed, another key, copied from another record) fails construction naming the file and the field,
 * never as a wrong password (SEC-IAM-008, FR-IAM-015). Ids are
 * decimal strings from a counter kept in the file, so a deleted id is never reused, even after a restart. Thread-safe
 * within one process (one lock over the name index for check-and-write); one file has one writer: a write finding
 * that the file changed since this instance last read or wrote it (a second {@code FileUserStore}, or another
 * process, wrote it) is refused with an {@link IOException} and one ERROR instead of rolling the other writer's change
 * (token version, password hash) back. Not shared between nodes, so {@code Deployment.MULTI_INSTANCE} refuses it.
 */
// @req FR-IAM-002
// @req SEC-IAM-008
// @req FR-IAM-015
public final class FileUserStore implements UserStore {

    /** The default size limit of the file: 8 MB. */
    public static final long DEFAULT_MAX_FILE_BYTES = MiniJson.DEFAULT_FILE_BYTES;

    static final String FORMAT = "naru-users";
    static final long VERSION = 1;
    /** The format version of a file whose password hashes are enc(...) values. */
    static final long ENCRYPTED_VERSION = 2;

    private final File file;
    private final MiniJson.Limits limits;
    private final Logger logger;
    private final @Nullable SecretBox box;
    /**
     * With a key: the enc(...) form of each row's hash as last read or written, by user id ({plain hash, enc value}),
     * so a write seals only the hashes that changed.
     */
    private Map<String, String[]> sealed = new LinkedHashMap<>();
    /** The length and CRC-32 of the file as this instance last read or wrote it; null while there was no file. */
    private long @Nullable [] seen;
    private boolean foreignReported;
    private Map<String, AuthUser> byId = new LinkedHashMap<>();
    private Map<String, String> idByName = new LinkedHashMap<>();
    private long nextId = 1;

    /** A store over {@code file}, created on the first write when absent; a corrupt file fails here. */
    // @req FR-IAM-002
    public FileUserStore(File file) throws IOException {
        this(file, DEFAULT_MAX_FILE_BYTES, new ConsoleLogger(), null);
    }

    /**
     * A store over {@code file} whose password hashes are written as {@code enc(...)} under {@code key}; null writes
     * them plain, as {@link #FileUserStore(File)} does. A plain (version-1) file is rewritten once, encrypted.
     */
    // @req FR-IAM-015
    public FileUserStore(File file, @Nullable AuthFileKey key) throws IOException {
        this(file, DEFAULT_MAX_FILE_BYTES, new ConsoleLogger(), key);
    }

    /**
     * A store over {@code file} read within {@code maxFileBytes}; start-up recovery warnings go to {@code logger}.
     */
    // @req FR-IAM-002
    public FileUserStore(File file, long maxFileBytes, Logger logger) throws IOException {
        this(file, maxFileBytes, logger, null);
    }

    /** {@link #FileUserStore(File, long, Logger)} whose password hashes are encrypted under {@code key} (null: plain). */
    // @req FR-IAM-015
    // @req SEC-IAM-008
    public FileUserStore(File file, long maxFileBytes, Logger logger, @Nullable AuthFileKey key) throws IOException {
        this.file = file;
        this.limits = MiniJson.Limits.file(maxFileBytes);
        this.logger = logger;
        this.box = key == null ? null : SecretBox.of(key);
        State s = AtomicFiles.load(file, limits.maxTotalBytes(), this::tooLarge, this::parse, logger);
        if (s != null) {
            this.byId = s.byId;
            this.idByName = s.idByName;
            this.nextId = s.nextId;
            this.sealed = s.sealed;
            if (s.plain && box != null) {
                // FR-IAM-015 AC-5: the first load with a key rewrites every plain hash as enc(...) in one atomic write
                commit(new LinkedHashMap<>(byId), nextId);
                int n = 0;
                for (AuthUser u : byId.values()) {
                    n += u.getPasswordHash() == null ? 0 : 1;
                }
                logger.info("auth store: encrypted " + n + (n == 1 ? " password hash of " : " password hashes of ")
                        + file + " under the AuthFileKey (format version " + ENCRYPTED_VERSION + ")");
            }
        }
        FileStoreKeys.record(this, key);
    }

    /** The file this store writes. */
    public File getFile() {
        return file;
    }

    private static final class State {
        final Map<String, AuthUser> byId = new LinkedHashMap<>();
        final Map<String, String> idByName = new LinkedHashMap<>();
        long nextId = 1;
        /** True when the file was format version 1 (plain hashes). */
        boolean plain;
        /** The enc(...) value read for each hash of a version-2 file, by user id: {plain hash, enc value}. */
        final Map<String, String[]> sealed = new LinkedHashMap<>();
    }

    private String tooLarge(long length) {
        return "the users file has " + length + " bytes, above the limit of " + limits.maxTotalBytes() + " bytes; "
                + "raise it with new FileUserStore(file, maxFileBytes, logger), or move to an app-provided UserStore "
                + "implementation backed by the application's database, which suits this many users better";
    }

    private static long[] fingerprint(byte[] content) {
        CRC32 crc = new CRC32();
        crc.update(content, 0, content.length);
        return new long[] {content.length, crc.getValue()};
    }

    @SuppressWarnings("unchecked")
    private State parse(byte[] content) {
        if (content.length > limits.maxTotalBytes()) {
            throw new IllegalArgumentException(tooLarge(content.length));
        }
        seen = fingerprint(content);
        Object doc = MiniJson.parse(content, limits);
        if (!(doc instanceof Map)) {
            throw new IllegalArgumentException("not a naru-users file");
        }
        Map<String, Object> m = (Map<String, Object>) doc;
        if (!FORMAT.equals(m.get("format"))) {
            throw new IllegalArgumentException("not a naru-users file");
        }
        Object version = m.get("version");
        boolean encrypted = Long.valueOf(ENCRYPTED_VERSION).equals(version);
        if (!Long.valueOf(VERSION).equals(version) && !encrypted) {
            throw new IllegalArgumentException("unknown naru-users format version " + version + "; this release "
                    + "reads versions " + VERSION + " and " + ENCRYPTED_VERSION);
        }
        SecretBox opener = box;
        if (encrypted && opener == null) {
            throw new IllegalArgumentException("the file holds encrypted values (format version " + ENCRYPTED_VERSION
                    + ", passwordHash as enc(...)); an AuthFileKey is needed: new FileUserStore(file, AuthFileKey), "
                    + "for example AuthFileKey.fromEnv() reading " + AuthFileKey.ENV_VARIABLE);
        }
        State s = new State();
        s.plain = !encrypted;
        s.nextId = number(m.get("nextId"), "nextId");
        long highest = 0;
        Object users = m.get("users");
        if (!(users instanceof List)) {
            throw new IllegalArgumentException("users is not a list");
        }
        for (Object o : (List<Object>) users) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("a user is not an object");
            }
            AuthUser u = user((Map<String, Object>) o, encrypted ? opener : null);
            String id = u.getId();
            if (id == null || s.byId.containsKey(id) || s.idByName.containsKey(u.getUsername())) {
                throw new IllegalArgumentException("a user id or name is missing or repeated");
            }
            s.byId.put(id, u);
            Object stored = ((Map<String, Object>) o).get("passwordHash");
            if (encrypted && u.getPasswordHash() != null && stored instanceof String) {
                s.sealed.put(id, new String[] {u.getPasswordHash(), (String) stored});
            }
            s.idByName.put(u.getUsername(), id);
            Long numeric = AuthIds.parseLong(id);
            if (numeric != null && numeric > highest) {
                highest = numeric;
            }
        }
        // a hand-edited or merged file with a lower counter must never hand out a stored id again
        if (s.nextId <= highest) {
            s.nextId = highest + 1;
        }
        return s;
    }

    /** One row; {@code opener} opens the enc(...) hash of a version-2 file, null reads it plain (version 1). */
    @SuppressWarnings("unchecked")
    private static AuthUser user(Map<String, Object> m, @Nullable SecretBox opener) {
        String id = text(m.get("id"), "id");
        AuthUser.Builder b = AuthUser.builder().id(id).username(text(m.get("username"), "username"));
        Object hash = m.get("passwordHash");
        String h = hash == null ? null : text(hash, "passwordHash");
        if (h != null && opener != null) {
            try {
                h = new String(opener.open(SecretBox.USERS_PASSWORD_HASH, id, h), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // SEC-IAM-008 AC-4: fail closed naming the field and the record, never the value or the key
                throw new IllegalArgumentException("the passwordHash of user id " + id + " " + e.getMessage());
            }
        }
        b.passwordHash(h);
        b.tokenVersion(number(m.get("ver"), "ver"));
        b.enabled(flag(m.get("enabled"), "enabled"));
        b.serviceAccount(flag(m.get("serviceAccount"), "serviceAccount"));
        List<String> roles = new ArrayList<>();
        Object rs = m.get("roles");
        if (!(rs instanceof List)) {
            throw new IllegalArgumentException("roles is not a list");
        }
        for (Object r : (List<Object>) rs) {
            roles.add(text(r, "role"));
        }
        b.roles(roles);
        Object as = m.get("attributes");
        if (!(as instanceof Map)) {
            throw new IllegalArgumentException("attributes is not an object");
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) as).entrySet()) {
            b.attribute(e.getKey(), text(e.getValue(), "attribute"));
        }
        return b.build();
    }

    private static String text(@Nullable Object v, String what) {
        if (!(v instanceof String)) {
            throw new IllegalArgumentException(what + " is not a string");
        }
        return (String) v;
    }

    private static long number(@Nullable Object v, String what) {
        if (!(v instanceof Long)) {
            throw new IllegalArgumentException(what + " is not an integer");
        }
        return (Long) v;
    }

    private static boolean flag(@Nullable Object v, String what) {
        if (!(v instanceof Boolean)) {
            throw new IllegalArgumentException(what + " is not a boolean");
        }
        return (Boolean) v;
    }

    /** The file content of {@code rows}; with a key, the enc(...) form of every hash goes to {@code out}. */
    private byte[] render(Map<String, AuthUser> rows, long next, Map<String, String[]> out) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", FORMAT);
        SecretBox sealer = box;
        doc.put("version", sealer == null ? VERSION : ENCRYPTED_VERSION);
        doc.put("nextId", next);
        List<Object> users = new ArrayList<>();
        for (AuthUser u : rows.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            String hash = u.getPasswordHash();
            String id = u.getId();
            String value = hash;
            if (hash != null && sealer != null && id != null) {
                String[] known = sealed.get(id);
                value = known != null && known[0].equals(hash) ? known[1]
                        : sealer.seal(SecretBox.USERS_PASSWORD_HASH, id, hash.getBytes(StandardCharsets.UTF_8));
                out.put(id, new String[] {hash, value});
            }
            m.put("passwordHash", value);
            m.put("ver", u.getTokenVersion());
            m.put("enabled", u.isEnabled());
            m.put("roles", new ArrayList<Object>(u.getRoles()));
            m.put("attributes", new LinkedHashMap<String, Object>(u.getAttributes()));
            m.put("serviceAccount", u.isServiceAccount());
            users.add(m);
        }
        doc.put("users", users);
        return MiniJson.write(doc, limits).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes the given state, then makes it current; a failed write leaves memory unchanged. A file changed by another
     * writer since this instance last read or wrote it is not overwritten.
     */
    // @req FR-IAM-002
    private void commit(Map<String, AuthUser> rows, long next) throws IOException {
        Map<String, String[]> nextSealed = new LinkedHashMap<>();
        byte[] content = render(rows, next, nextSealed);
        synchronized (AtomicFiles.directoryLockFor(file)) {
            refuseForeignChange();
            AtomicFiles.write(file, content);
            seen = fingerprint(content);
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (AuthUser u : rows.values()) {
            names.put(u.getUsername(), u.getId());
        }
        byId = rows;
        idByName = names;
        nextId = next;
        sealed = nextSealed;
    }

    private static long crcOf(File f) throws IOException {
        CRC32 crc = new CRC32();
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                crc.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return crc.getValue();
    }

    /** Throws when the file on disk is not the one this instance last read or wrote. */
    private void refuseForeignChange() throws IOException {
        long[] expected = seen;
        boolean changed;
        if (!file.exists()) {
            changed = expected != null;
        } else if (expected == null || file.length() != expected[0]) {
            changed = true;
        } else {
            changed = crcOf(file) != expected[1];
        }
        if (!changed) {
            return;
        }
        String why = "the users file " + file + " was changed by another writer since this FileUserStore read it; "
                + "the write is refused so the other change (a token version, a password hash) is not rolled back. "
                + "Use one FileUserStore per file, and restart to read the file again";
        if (!foreignReported) {
            foreignReported = true;
            logger.error("auth: " + why);
        }
        throw new IOException(why);
    }

    @Override
    public synchronized @Nullable AuthUser findByUsername(String username) {
        String id = idByName.get(username);
        return id == null ? null : byId.get(id);
    }

    @Override
    public synchronized @Nullable AuthUser findById(String id) {
        return byId.get(id);
    }

    // @req FR-IAM-002
    @Override
    public synchronized AuthUser create(AuthUser user) throws IOException {
        if (user.getId() != null) {
            throw new IllegalArgumentException("a new user has no id yet: " + user.getId());
        }
        if (idByName.containsKey(user.getUsername())) {
            throw new DuplicateUserException("user " + user.getUsername() + " already exists");
        }
        long next = nextId;
        while (byId.containsKey(String.valueOf(next))) {
            next++;
        }
        String id = String.valueOf(next);
        AuthUser stored = user.toBuilder().id(id).build();
        Map<String, AuthUser> rows = new LinkedHashMap<>(byId);
        rows.put(id, stored);
        commit(rows, next + 1);
        return stored;
    }

    // @req FR-IAM-002
    @Override
    public synchronized @Nullable AuthUser update(AuthUser user) throws IOException {
        String id = user.getId();
        if (id == null || !byId.containsKey(id)) {
            return null;
        }
        String owner = idByName.get(user.getUsername());
        if (owner != null && !owner.equals(id)) {
            throw new DuplicateUserException("user " + user.getUsername() + " already exists");
        }
        Map<String, AuthUser> rows = new LinkedHashMap<>(byId);
        rows.put(id, user);
        commit(rows, nextId);
        return user;
    }

    // @req FR-IAM-002
    @Override
    public synchronized boolean delete(String id) throws IOException {
        if (!byId.containsKey(id)) {
            return false;
        }
        Map<String, AuthUser> rows = new LinkedHashMap<>(byId);
        rows.remove(id);
        commit(rows, nextId);
        return true;
    }

    /** Users in creation order whose username contains {@code search} (ignoring case); null matches all. */
    // @req FR-IAM-002
    @Override
    public synchronized UserPage list(int offset, int limit, @Nullable String search) {
        if (offset < 0 || limit < 0) {
            throw new IllegalArgumentException("offset and limit must not be negative: " + offset + ", " + limit);
        }
        String needle = search == null ? null : search.toLowerCase(Locale.ROOT);
        List<AuthUser> matching = new ArrayList<>();
        for (AuthUser u : byId.values()) {
            if (needle == null || u.getUsername().toLowerCase(Locale.ROOT).contains(needle)) {
                matching.add(u);
            }
        }
        int from = Math.min(offset, matching.size());
        int to = (int) Math.min((long) from + limit, matching.size());
        return new UserPage(matching.subList(from, to), matching.size(), offset, limit);
    }

    @Override
    public synchronized String toString() {
        return "FileUserStore{file=" + file + ", users=" + byId.size() + "}";
    }
}
