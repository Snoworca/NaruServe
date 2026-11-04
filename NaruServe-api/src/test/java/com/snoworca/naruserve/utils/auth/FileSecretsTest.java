package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import com.snoworca.naruserve.utils.auth.internal.Doctor;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.internal.SecretBox;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.spi.KeySource;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The enc(...) secrets of the auth file stores (SEC-IAM-008) and their wiring (FR-IAM-015): the users-file
 * passwordHash and the KeySources.fromFile key file under an AuthFileKey, fail-closed reads, PROD and DEV policy,
 * the first-load migration and the doctor count. Real files, real HS256 logins over the in-memory adapter; nothing is a
 * mock.
 */
public class FileSecretsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<W8> nodes = new ArrayList<>();

    @After
    public void stop() {
        for (W8 w : nodes) {
            w.stop();
        }
    }

    private static final String PW = "pw-1234567890";

    /** The test master key: 32 fixed bytes. */
    private static byte[] master() {
        byte[] m = new byte[32];
        for (int i = 0; i < m.length; i++) {
            m[i] = (byte) (i * 7 + 3);
        }
        return m;
    }

    private static byte[] otherMaster() {
        byte[] m = master();
        m[5] ^= 0x55;
        return m;
    }

    private static AuthFileKey key() {
        return AuthFileKey.of(master());
    }

    private W8 node(com.snoworca.naruserve.utils.auth.user.UserStore users, @org.jspecify.annotations.Nullable KeySource keys,
            UnaryOperator<Auth.Builder> config) {
        W8 w = new W8();
        nodes.add(w);
        w.users = users;
        w.keys = keys;
        return w.start(config);
    }

    private static byte[] bytes(File f) throws IOException {
        return Files.readAllBytes(f.toPath());
    }

    private static String text(File f) throws IOException {
        return new String(bytes(f), StandardCharsets.UTF_8);
    }

    private static void write(File f, String s) throws IOException {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(File usersFile) throws IOException {
        Map<String, Object> doc = (Map<String, Object>) MiniJson.parse(bytes(usersFile), MiniJson.Limits.file());
        return (List<Map<String, Object>>) (List<?>) doc.get("users");
    }

    @SuppressWarnings("unchecked")
    private static Object version(File usersFile) throws IOException {
        return ((Map<String, Object>) MiniJson.parse(bytes(usersFile), MiniJson.Limits.file())).get("version");
    }

    private static String storedHash(File usersFile, String username) throws IOException {
        for (Map<String, Object> r : rows(usersFile)) {
            if (username.equals(r.get("username"))) {
                return (String) r.get("passwordHash");
            }
        }
        throw new AssertionError("no row " + username);
    }

    private static byte[] payload(String sealed) {
        assertTrue(sealed, sealed.matches("^enc\\([A-Za-z0-9_-]+\\)$"));
        return Base64Url.decode(sealed.substring(4, sealed.length() - 1));
    }

    private static String sealedOf(byte[] payload) {
        return "enc(" + Base64Url.encode(payload) + ")";
    }

    private static String hashOf(String pw) {
        return new Pbkdf2PasswordHasher(1000).hash(pw.toCharArray());
    }

    private static List<String> listing(File dir) {
        String[] names = dir.list();
        List<String> l = new ArrayList<>(Arrays.asList(names == null ? new String[0] : names));
        java.util.Collections.sort(l);
        return l;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(Integer.toHexString((x & 0xff) | 0x100).substring(1));
        }
        return s.toString();
    }

    /** The master key and each derived key, as raw bytes and as base64, base64url and hex texts. */
    private static List<byte[]> secrets(byte[] master) throws Exception {
        List<byte[]> out = new ArrayList<>();
        out.add(master);
        for (String purpose : new String[] {SecretBox.USERS_PASSWORD_HASH, SecretBox.SIGNING_KEY}) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            out.add(mac.doFinal(("naru-auth-file/v1/" + purpose).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static List<String> encodings(byte[] k) {
        String b64 = Base64.getEncoder().encodeToString(k);
        return Arrays.asList(b64, b64.replace("=", ""), Base64.getUrlEncoder().withoutPadding().encodeToString(k),
                hex(k), hex(k).toUpperCase(java.util.Locale.ROOT), new String(k, StandardCharsets.ISO_8859_1));
    }

    private static void assertNoKeyMaterial(String where, String text, byte[] master) throws Exception {
        for (byte[] k : secrets(master)) {
            for (String e : encodings(k)) {
                assertFalse(where + " holds key material", text.contains(e));
            }
        }
    }

    private static boolean containsBytes(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static String messages(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            b.append(c).append('\n');
        }
        return b.toString();
    }

    /** The AC-5 fixture: a version-1 users file (three users with plain hashes, one service account) and a raw key. */
    private static void plainFixture(File usersFile, File keyFile) throws Exception {
        FileUserStore plain = new FileUserStore(usersFile);
        for (String n : new String[] {"alice", "bob", "carol"}) {
            plain.create(AuthUser.builder().username(n).passwordHash(hashOf(PW + n)).roles("reader").build());
        }
        plain.create(AuthUser.builder().username("svc").serviceAccount(true).build());
        KeySources.fromFile(keyFile).signingKey();
    }

    // ------------------------------------------------------------------------------------------------ SEC-IAM-008

    // SEC-IAM-008 AC-1: with a 32-byte master key, auth.users().create("alice", "pw-1234567890") stores
    // passwordHash as enc(...) whose payload is version 1, a 12-byte IV, the hash and a 16-byte tag; the plain hash is
    // nowhere in the file; a new FileUserStore with the same key loads it and alice logs in
    @Test
    public void SEC_IAM_008_AC1_passwordHashIsStoredEncrypted() throws Exception {
        File usersFile = new File(tmp.newFolder(), "users.json");
        FileUserStore store = new FileUserStore(usersFile, key());
        W8 w = node(store, null, b -> b);
        w.auth.users().create("alice", PW.toCharArray());
        String plain = store.findByUsername("alice").getPasswordHash();
        assertNotNull(plain);
        String stored = storedHash(usersFile, "alice");
        byte[] p = payload(stored);
        assertEquals(1, p[0]);
        assertEquals(1 + 12 + plain.getBytes(StandardCharsets.UTF_8).length + 16, p.length);
        assertFalse("the plain hash is in the file", text(usersFile).contains(plain));
        w.stop();
        FileUserStore reopened = new FileUserStore(usersFile, key());
        assertEquals(plain, reopened.findByUsername("alice").getPasswordHash());
        W8 again = node(reopened, null, b -> b);
        assertNotNull(again.loginBody("alice", PW).get("accessToken"));
    }

    // SEC-IAM-008 (statement; the IV rule itself is AC-2 in SecretBoxTest): a write seals each changed hash under a
    // fresh IV and leaves the enc(...) form of unchanged rows
    // as it was, so a write costs one seal, not one per user
    @Test
    public void SEC_IAM_008_onlyChangedHashesAreSealedAgain() throws Exception {
        File usersFile = new File(tmp.newFolder(), "users.json");
        FileUserStore store = new FileUserStore(usersFile, key());
        AuthUser alice = store.create(AuthUser.builder().username("alice").passwordHash(hashOf("a-password-1")).build());
        store.create(AuthUser.builder().username("bob").passwordHash(hashOf("b-password-2")).build());
        String a = storedHash(usersFile, "alice");
        String b = storedHash(usersFile, "bob");
        store.update(store.findById(alice.getId()).toBuilder().passwordHash(hashOf("a-password-3")).build());
        assertEquals("bob was not sealed again", b, storedHash(usersFile, "bob"));
        assertFalse("alice's new hash has a new enc(...) value", a.equals(storedHash(usersFile, "alice")));
        FileUserStore reopened = new FileUserStore(usersFile, key());
        reopened.create(AuthUser.builder().username("carol").passwordHash(hashOf("c-password-4")).build());
        assertEquals("a reopened store keeps the loaded form", b, storedHash(usersFile, "bob"));
        assertEquals(store.findByUsername("bob").getPasswordHash(),
                new FileUserStore(usersFile, key()).findByUsername("bob").getPasswordHash());
    }

    // SEC-IAM-008 AC-5: a migration that stopped after moving the raw key aside (the fallback where rename does not
    // replace) leaves .<name>.bak; the next read restores it instead of making a new key
    @Test
    public void SEC_IAM_008_AC5_interruptedKeyMigrationRestoresTheOldKey() throws Exception {
        File dir = tmp.newFolder();
        File keyFile = new File(dir, "naru-auth.key");
        byte[] raw = KeySources.fromFile(keyFile).signingKey();
        File bak = new File(dir, ".naru-auth.key.bak");
        assertTrue(keyFile.renameTo(bak));
        assertArrayEquals("the old key came back", raw, KeySources.fromFile(keyFile, key()).signingKey());
        assertFalse(bak.exists());
        assertTrue(SecretBox.isSealed(text(keyFile)));
        assertArrayEquals(raw, KeySources.fromFile(keyFile, key()).signingKey());
        assertTrue(keyFile.renameTo(bak));
        assertArrayEquals("an encrypted backup comes back too", raw, KeySources.fromFile(keyFile, key()).signingKey());
    }

    // SEC-IAM-008 AC-5 and FR-IAM-015 AC-5 (R2-M1-F06): where rename cannot replace an existing file (Windows,
    // simulated here through the package-private moves seam), a .bak that cannot be deleted after a successful
    // enc(...) migration does not fail the build: one WARN without key material, the migrated key is used, and the
    // next read deletes the leftover raw .bak
    @Test
    public void SEC_IAM_008_AC5_undeletableBackupAfterMigrationOnlyWarns() throws Exception {
        File dir = tmp.newFolder();
        File keyFile = new File(dir, "naru-auth.key");
        final File bak = new File(dir, ".naru-auth.key.bak");
        byte[] raw = KeySources.fromFile(keyFile).signingKey();
        KeySources.FileKey windows = (KeySources.FileKey) KeySources.fromFile(keyFile, key());
        windows.moves(new KeySources.FileKey.Moves() {
            @Override
            public boolean rename(File from, File to) {
                return !to.exists() && from.renameTo(to); // never replaces an existing file
            }

            @Override
            public boolean delete(File f) {
                return !f.equals(bak) && f.delete(); // the raw .bak stays locked
            }
        });
        RecordingLogger log = new RecordingLogger();
        Auth auth = Auth.builder(windows).mode(AuthMode.PROD).hashIterations(1000).logger(log)
                .users(new InMemoryUserStore()).build();
        assertArrayEquals("the migrated key is used", raw, auth.keySet().getSigningKey());
        assertTrue(SecretBox.isSealed(text(keyFile)));
        assertTrue("the simulated lock kept the .bak", bak.exists());
        List<String> warns = RecordingLogger.matching(log.lines, "WARN .*" + java.util.regex.Pattern.quote(
                bak.toString()) + ".*");
        assertEquals(log.lines.toString(), 1, warns.size());
        String rawText = new String(raw, StandardCharsets.ISO_8859_1);
        for (String line : log.lines) {
            assertFalse(line, line.contains(rawText) || line.contains(Base64Url.encode(raw))
                    || line.contains(Base64.getEncoder().encodeToString(raw)));
        }
        assertArrayEquals(raw, KeySources.fromFile(keyFile, key()).signingKey());
        assertFalse("the next read deletes the leftover raw .bak", bak.exists());
        assertTrue(SecretBox.isSealed(text(keyFile)));
    }

    // SEC-IAM-008 AC-5: a key file above the read limit is refused with the limit it broke
    @Test
    public void SEC_IAM_008_AC5_oversizedKeyFileNamesItsLimit() throws Exception {
        File keyFile = new File(tmp.newFolder(), "naru-auth.key");
        Files.write(keyFile.toPath(), new byte[9000]);
        try {
            KeySources.fromFile(keyFile, key()).signingKey();
            fail("a 9000-byte key file was read");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("larger than 8192 bytes"));
        }
        Files.write(keyFile.toPath(), new byte[5000]);
        try {
            KeySources.fromFile(keyFile, key()).signingKey();
            fail("a 5000-byte raw key file was read");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("larger than 4096 bytes"));
        }
        assertEquals(5000, keyFile.length());
    }

    // SEC-IAM-008 AC-3: an enc(...) value copied from the key file into a passwordHash, or from user A's record into
    // user B's, does not authenticate and the store refuses to load
    @Test
    public void SEC_IAM_008_AC3_copiedValuesMakeTheStoreRefuseToLoad() throws Exception {
        File dir = tmp.newFolder();
        File usersFile = new File(dir, "users.json");
        File keyFile = new File(dir, "naru-auth.key");
        FileUserStore store = new FileUserStore(usersFile, key());
        store.create(AuthUser.builder().username("alice").passwordHash(hashOf("a-password-1")).build());
        store.create(AuthUser.builder().username("bob").passwordHash(hashOf("b-password-2")).build());
        KeySources.fromFile(keyFile, key()).signingKey();
        String good = text(usersFile);
        String a = storedHash(usersFile, "alice");
        String b = storedHash(usersFile, "bob");
        String fromKeyFile = text(keyFile).trim();
        String[] tampered = {good.replace(a, "\u0000").replace(b, a).replace("\u0000", b), good.replace(a, fromKeyFile)};
        for (String t : tampered) {
            write(usersFile, t);
            byte[] before = bytes(usersFile);
            try {
                new FileUserStore(usersFile, key());
                fail("a copied value was accepted");
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("passwordHash"));
                assertTrue(e.getMessage(), e.getMessage().contains("does not authenticate"));
            }
            assertArrayEquals(before, bytes(usersFile));
        }
    }

    // SEC-IAM-008 AC-4: a flipped payload byte, another master key, a version byte other than 1, bad base64url and a
    // too-short payload each make the FileUserStore constructor throw an IOException naming the file and passwordHash,
    // with neither the stored value nor key material; the message is the REL-IAM-004 FAIL and the file is unchanged
    @Test
    public void SEC_IAM_008_AC4_usersFileFailsClosed() throws Exception {
        File usersFile = new File(tmp.newFolder(), "users.json");
        FileUserStore store = new FileUserStore(usersFile, key());
        store.create(AuthUser.builder().username("alice").passwordHash(hashOf(PW)).build());
        String good = text(usersFile);
        String enc = storedHash(usersFile, "alice");
        byte[] p = payload(enc);
        byte[] flipped = p.clone();
        flipped[20] ^= 1;
        byte[] version = p.clone();
        version[0] = 2;
        String[] variants = {good.replace(enc, sealedOf(flipped)), good, good.replace(enc, sealedOf(version)),
                good.replace(enc, "enc(**not*base64url**)"), good.replace(enc, sealedOf(new byte[10]))};
        for (int i = 0; i < variants.length; i++) {
            write(usersFile, variants[i]);
            byte[] before = bytes(usersFile);
            AuthFileKey k = i == 1 ? AuthFileKey.of(otherMaster()) : key();
            try {
                new FileUserStore(usersFile, k);
                fail("variant " + i + " was loaded");
            } catch (IOException e) {
                String m = e.getMessage();
                assertTrue(m, m.startsWith("FAIL: auth store file " + usersFile));
                assertTrue(m, m.contains("passwordHash"));
                assertFalse(m, m.contains(enc));
                assertFalse(m, m.contains(enc.substring(4, enc.length() - 1)));
                assertNoKeyMaterial("variant " + i, messages(e), master());
                assertNoKeyMaterial("variant " + i, messages(e), otherMaster());
            }
            assertArrayEquals("variant " + i + " changed the file", before, bytes(usersFile));
        }
        write(usersFile, good);
        assertEquals(1, new FileUserStore(usersFile, key()).list(0, 10, null).getTotal());
    }

    // SEC-IAM-008 AC-5: a key file holding a tampered enc(...) value, or opened with another master key, makes the key
    // read throw an IOException naming the file and Auth build fail; the file is unchanged and no key is made instead
    @Test
    public void SEC_IAM_008_AC5_keyFileFailsClosed() throws Exception {
        File dir = tmp.newFolder();
        File keyFile = new File(dir, "naru-auth.key");
        KeySources.fromFile(keyFile, key()).signingKey();
        String good = text(keyFile);
        assertTrue(good, good.matches("^enc\\([A-Za-z0-9_-]+\\)$"));
        byte[] p = payload(good);
        p[p.length - 2] ^= 1;
        String[][] cases = {{sealedOf(p), "same"}, {good, "other"}};
        for (String[] c : cases) {
            write(keyFile, c[0]);
            byte[] before = bytes(keyFile);
            List<String> names = listing(dir);
            AuthFileKey k = c[1].equals("same") ? key() : AuthFileKey.of(otherMaster());
            try {
                KeySources.fromFile(keyFile, k).signingKey();
                fail("the key file opened");
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(keyFile.toString()));
                assertNoKeyMaterial("key read", messages(e), master());
            }
            try {
                Auth.builder(KeySources.fromFile(keyFile, k)).mode(AuthMode.DEV).hashIterations(1000)
                        .users(new InMemoryUserStore()).build();
                fail("auth was built over a key file that does not open");
            } catch (AuthConfigException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(keyFile.toString()));
                assertNoKeyMaterial("build", messages(e), master());
            }
            assertArrayEquals(before, bytes(keyFile));
            assertEquals("no new key or staging file", names, listing(dir));
        }
    }

    // SEC-IAM-008 AC-7: AuthFileKey.of(byte[]) copies its argument and needs 32 bytes; of(String) takes standard or
    // URL-safe base64 with or without padding of at least 32 bytes, else an IllegalArgumentException stating the rule
    // without the input; fromEnv() reads ENV_VARIABLE (NARU_AUTH_FILE_KEY): null when unset or empty, an exception
    // naming the variable but not its value when invalid
    @Test
    public void SEC_IAM_008_AC7_authFileKeyConstruction() throws Exception {
        assertTrue(Modifier.isFinal(AuthFileKey.class.getModifiers()));
        assertTrue(Modifier.isPublic(AuthFileKey.class.getModifiers()));
        assertEquals("NARU_AUTH_FILE_KEY", AuthFileKey.ENV_VARIABLE);
        byte[] raw = master();
        AuthFileKey k = AuthFileKey.of(raw);
        raw[0] ^= 1; // the key copied its argument
        String sealed = SecretBox.of(AuthFileKey.of(master())).seal(SecretBox.SIGNING_KEY, "f", new byte[] {1, 2});
        assertArrayEquals(new byte[] {1, 2}, SecretBox.of(k).open(SecretBox.SIGNING_KEY, "f", sealed));
        try {
            AuthFileKey.of(new byte[31]);
            fail("31 bytes were accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("32"));
        }
        String std = Base64.getEncoder().encodeToString(master());
        String url = Base64.getUrlEncoder().withoutPadding().encodeToString(master());
        for (String form : new String[] {std, std.replace("=", ""), url, url + "=", "  " + std + "\n"}) {
            assertArrayEquals(form, new byte[] {1, 2},
                    SecretBox.of(AuthFileKey.of(form)).open(SecretBox.SIGNING_KEY, "f", sealed));
        }
        String shortKey = Base64.getEncoder().encodeToString(new byte[31]);
        for (String bad : new String[] {shortKey, "not base64 at all!!", "", "QUJD=X"}) {
            try {
                AuthFileKey.of(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("at least 32 bytes"));
                if (!bad.isEmpty()) {
                    assertFalse(e.getMessage(), e.getMessage().contains(bad));
                }
            }
        }
        assertNull(AuthFileKey.fromEnvValue(null));
        assertNull(AuthFileKey.fromEnvValue(""));
        assertNotNull(AuthFileKey.fromEnvValue(std));
        try {
            AuthFileKey.fromEnvValue("c2VjcmV0LXZhbHVl");
            fail("an invalid environment value was accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("NARU_AUTH_FILE_KEY"));
            assertFalse(e.getMessage(), e.getMessage().contains("c2VjcmV0LXZhbHVl"));
        }
        if (System.getenv(AuthFileKey.ENV_VARIABLE) == null) {
            assertNull("fromEnv() is null while the variable is unset", AuthFileKey.fromEnv());
        }
    }

    // SEC-IAM-008 AC-8: the master key is never disclosed: a fixed toString, not Serializable, and after creating users,
    // rewriting the files and triggering each AC-4 failure, no log line, exception message or data-directory file holds
    // the raw key bytes or their base64, base64url or hex forms, nor a derived key
    @Test
    public void SEC_IAM_008_AC8_masterKeyIsNeverDisclosed() throws Exception {
        assertFalse(Serializable.class.isAssignableFrom(AuthFileKey.class));
        assertEquals(AuthFileKey.of(master()).toString(), AuthFileKey.of(otherMaster()).toString());
        assertNoKeyMaterial("toString", AuthFileKey.of(master()).toString(), master());
        File dir = tmp.newFolder();
        File usersFile = new File(dir, "users.json");
        File keyFile = new File(dir, "naru-auth.key");
        FileUserStore store = new FileUserStore(usersFile, key());
        W8 w = node(store, KeySources.fromFile(keyFile, key()), b -> b);
        AuthUser alice = w.auth.users().create("alice", PW.toCharArray());
        w.auth.users().create("bob", (PW + "b").toCharArray());
        w.auth.users().rename(alice.getId(), "alicia");
        w.loginBody("alicia", PW);
        w.post("/auth/login", W8.login("bob", "wrong"));
        w.auth.snapshotRun();
        w.stop();
        StringBuilder seen = new StringBuilder();
        String good = text(usersFile);
        String enc = storedHash(usersFile, "alicia");
        byte[] p = payload(enc);
        byte[] version = p.clone();
        version[0] = 7;
        p[p.length - 1] ^= 1;
        for (String variant : new String[] {good.replace(enc, sealedOf(p)), good.replace(enc, "enc(%%%)"),
                good.replace(enc, sealedOf(version)), good.replace(enc, sealedOf(new byte[5]))}) {
            write(usersFile, variant);
            try {
                new FileUserStore(usersFile, key());
                fail();
            } catch (IOException e) {
                seen.append(messages(e));
            }
        }
        try {
            new FileUserStore(usersFile, AuthFileKey.of(otherMaster()));
            fail();
        } catch (IOException e) {
            seen.append(messages(e));
        }
        write(usersFile, good);
        synchronized (w.logger.lines) {
            for (String l : w.logger.lines) {
                seen.append(l).append('\n');
            }
        }
        assertNoKeyMaterial("logs and exceptions", seen.toString(), master());
        File[] files = dir.listFiles();
        assertNotNull(files);
        assertTrue(files.length >= 3);
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            byte[] content = bytes(f);
            assertNoKeyMaterial(f.getName(), new String(content, StandardCharsets.ISO_8859_1), master());
            for (byte[] s : secrets(master())) {
                assertFalse(f.getName() + " holds raw key bytes", containsBytes(content, s));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ FR-IAM-015

    // FR-IAM-015 AC-1: the additive overloads exist beside the unchanged signatures, and the old ones behave as the
    // overloads with a null key (version-1 users file with the plain hash; raw 32-byte key file)
    @Test
    public void FR_IAM_015_AC1_additiveOverloads() throws Exception {
        Constructor<?>[] wanted = {FileUserStore.class.getConstructor(File.class),
                FileUserStore.class.getConstructor(File.class, AuthFileKey.class),
                FileUserStore.class.getConstructor(File.class, long.class, Logger.class),
                FileUserStore.class.getConstructor(File.class, long.class, Logger.class, AuthFileKey.class)};
        for (Constructor<?> c : wanted) {
            assertTrue(c.toString(), Modifier.isPublic(c.getModifiers()));
        }
        Method fromFile = KeySources.class.getMethod("fromFile", File.class);
        Method fromFileKeyed = KeySources.class.getMethod("fromFile", File.class, AuthFileKey.class);
        assertTrue(Modifier.isStatic(fromFile.getModifiers()) && Modifier.isStatic(fromFileKeyed.getModifiers()));
        assertEquals(KeySource.class, fromFileKeyed.getReturnType());
        File dir = tmp.newFolder();
        File a = new File(dir, "a.json");
        File b = new File(dir, "b.json");
        String hash = hashOf(PW);
        new FileUserStore(a).create(AuthUser.builder().username("alice").passwordHash(hash).build());
        new FileUserStore(b, (AuthFileKey) null).create(AuthUser.builder().username("alice").passwordHash(hash).build());
        new FileUserStore(new File(dir, "c.json"), FileUserStore.DEFAULT_MAX_FILE_BYTES, new RecordingLogger(), null)
                .create(AuthUser.builder().username("alice").passwordHash(hash).build());
        assertEquals(text(a), text(b));
        assertEquals(text(a), text(new File(dir, "c.json")));
        assertEquals(1L, version(a));
        assertEquals(hash, storedHash(a, "alice"));
        File k1 = new File(dir, "k1.key");
        File k2 = new File(dir, "k2.key");
        assertArrayEquals(KeySources.fromFile(k1).signingKey(), bytes(k1));
        assertArrayEquals(KeySources.fromFile(k2, null).signingKey(), bytes(k2));
        assertEquals(32, bytes(k1).length);
        assertEquals(32, bytes(k2).length);
    }

    // FR-IAM-015 AC-2: with .users(new FileUserStore(file, key)) and no refresh, throttle or device-trust store, the
    // default file stores carry the same AuthFileKey instance; their files keep their fields and format version 1
    @Test
    public void FR_IAM_015_AC2_fileDefaultsInheritTheKey() throws Exception {
        File dir = tmp.newFolder();
        AuthFileKey k = key();
        FileUserStore store = new FileUserStore(new File(dir, "users.json"), k);
        W8 w = node(store, null, b -> b);
        assertSame(k, ((FileRefreshTokenStore) w.auth.refreshTokens()).fileKey());
        assertSame(k, ((FileLoginThrottle) w.auth.loginThrottle()).fileKey());
        assertSame(k, ((FileDeviceTrust) w.auth.deviceTrust()).fileKey());
        w.auth.users().create("alice", PW.toCharArray());
        w.loginBody("alice", PW);
        w.post("/auth/login", W8.login("alice", "wrong"));
        w.auth.snapshotRun();
        File refresh = new File(dir, "users-refresh-tokens.json");
        File throttle = new File(dir, "users-login-throttle.json");
        for (File f : new File[] {refresh, throttle}) {
            String t = text(f);
            assertTrue(f.getName() + ": " + t, t.contains("\"version\":1"));
            assertFalse(f.getName() + ": " + t, t.contains("enc("));
        }
        assertTrue(text(refresh), text(refresh).contains("\"tokenHash\""));
        // a plain FileUserStore leaves the defaults without a key
        W8 plain = node(new FileUserStore(new File(tmp.newFolder(), "users.json")), null, b -> b.mode(AuthMode.DEV));
        assertNull(((FileRefreshTokenStore) plain.auth.refreshTokens()).fileKey());
    }

    // FR-IAM-015 AC-3: in PROD, a FileUserStore or a KeySources.fromFile source without a key makes build() throw an
    // AuthConfigException naming the missing options and NARU_AUTH_FILE_KEY; only the one that lacks a key is named;
    // with keys for both the build succeeds; an Auth with neither file store is not affected
    @Test
    public void FR_IAM_015_AC3_prodRefusesUnencryptedFileSecrets() throws Exception {
        File dir = tmp.newFolder();
        File usersFile = new File(dir, "users.json");
        File keyFile = new File(dir, "naru-auth.key");
        String userOption = "FileUserStore(file, AuthFileKey)";
        String keyOption = "KeySources.fromFile(file, AuthFileKey)";
        try {
            Auth.builder(KeySources.fromFile(keyFile)).mode(AuthMode.PROD).hashIterations(1000)
                    .users(new FileUserStore(usersFile)).build();
            fail("PROD built without file keys");
        } catch (AuthConfigException e) {
            String m = e.getMessage();
            assertTrue(m, m.contains(userOption) && m.contains(keyOption) && m.contains("NARU_AUTH_FILE_KEY"));
        }
        assertFalse("PROD wrote no plain key file", keyFile.exists());
        try {
            Auth.builder(KeySources.fromFile(keyFile, key())).mode(AuthMode.PROD).hashIterations(1000)
                    .users(new FileUserStore(usersFile)).build();
            fail("PROD built with a plain users file");
        } catch (AuthConfigException e) {
            String m = e.getMessage();
            assertTrue(m, m.contains(userOption) && m.contains("NARU_AUTH_FILE_KEY"));
            assertFalse(m, m.contains(keyOption));
        }
        try {
            Auth.builder(KeySources.fromFile(keyFile)).mode(AuthMode.PROD).hashIterations(1000)
                    .users(new FileUserStore(usersFile, key())).build();
            fail("PROD built with a plain key file");
        } catch (AuthConfigException e) {
            String m = e.getMessage();
            assertTrue(m, m.contains(keyOption) && m.contains("NARU_AUTH_FILE_KEY"));
            assertFalse(m, m.contains(userOption));
        }
        assertNotNull(Auth.builder(KeySources.fromFile(keyFile, key())).mode(AuthMode.PROD).hashIterations(1000)
                .users(new FileUserStore(usersFile, key())).build());
        assertNotNull(Auth.builder(KeySources.of(AuthTestSupport.SECRET)).mode(AuthMode.PROD).hashIterations(1000)
                .users(new InMemoryUserStore()).build());
    }

    // FR-IAM-015 AC-3 and AC-7 (R2-M1-F09): a FileUserStore inside ForwardingUserStore decorators (the documented
    // base of every decorator) is held to the same PROD key rule and doctor count as a bare one
    @Test
    public void FR_IAM_015_AC3_decoratedFileUserStoreIsHeldToTheKeyRule() throws Exception {
        File dir = tmp.newFolder();
        File usersFile = new File(dir, "users.json");
        String userOption = "FileUserStore(file, AuthFileKey)";
        FileUserStore inner = new FileUserStore(usersFile);
        com.snoworca.naruserve.utils.auth.user.UserStore once = new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(inner) { };
        com.snoworca.naruserve.utils.auth.user.UserStore twice = new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(once) { };
        for (com.snoworca.naruserve.utils.auth.user.UserStore decorated
                : new com.snoworca.naruserve.utils.auth.user.UserStore[] {once, twice}) {
            try {
                Auth.builder(KeySources.of(AuthTestSupport.SECRET)).mode(AuthMode.PROD).hashIterations(1000)
                        .users(decorated).build();
                fail("PROD built over a decorated FileUserStore without a key");
            } catch (AuthConfigException e) {
                String m = e.getMessage();
                assertTrue(m, m.contains(userOption) && m.contains(usersFile.toString())
                        && m.contains("NARU_AUTH_FILE_KEY"));
            }
        }
        com.snoworca.naruserve.utils.auth.user.UserStore keyed = new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(
                new FileUserStore(new File(tmp.newFolder(), "users.json"), key())) { };
        assertNotNull(Auth.builder(KeySources.of(AuthTestSupport.SECRET)).mode(AuthMode.PROD).hashIterations(1000)
                .users(keyed).build());
        // the doctor counts the plain hashes of the decorated store as it does for a bare one
        inner.create(AuthUser.builder().username("alice").passwordHash("pbkdf2-sha256$1000$x$y").build());
        W8 dev = node(twice, null, b -> b.mode(AuthMode.DEV));
        Doctor.Finding found = null;
        for (Doctor.Finding f : dev.auth.doctor(dev.app).findings()) {
            if (f.getItem().equals("plain secret values in auth files")) {
                found = f;
            }
        }
        assertNotNull("no plain-secrets item for a decorated FileUserStore", found);
        assertTrue(found.getMessage(), found.getMessage().startsWith("1 "));
        dev.stop();
    }

    /** A decorator with value equality over a field set after the base constructor (N3-01). */
    static final class TenantUsers extends com.snoworca.naruserve.utils.auth.user.ForwardingUserStore {
        final String tenant;

        TenantUsers(String tenant, com.snoworca.naruserve.utils.auth.user.UserStore inner) {
            super(inner);
            this.tenant = tenant;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TenantUsers && java.util.Objects.equals(tenant, ((TenantUsers) o).tenant);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(tenant);
        }
    }

    /** A decorator whose hashCode dereferences its own field, as a Kotlin data class does (N3-01). */
    static final class StrictTenantUsers extends com.snoworca.naruserve.utils.auth.user.ForwardingUserStore {
        final String tenant;

        StrictTenantUsers(String tenant, com.snoworca.naruserve.utils.auth.user.UserStore inner) {
            super(inner);
            this.tenant = tenant;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StrictTenantUsers && tenant.equals(((StrictTenantUsers) o).tenant);
        }

        @Override
        public int hashCode() {
            return tenant.hashCode();
        }
    }

    // FR-IAM-015 AC-3 (N3-01): a decorator with value equals/hashCode over its own fields is constructible and its
    // FileUserStore is still held to the PROD key rule: the base constructor runs no overridable method on the
    // half-built decorator
    @Test
    public void FR_IAM_015_AC3_valueEqualityDecoratorsAreHeldToTheKeyRule() throws Exception {
        File usersFile = new File(tmp.newFolder(), "users.json");
        FileUserStore inner = new FileUserStore(usersFile);
        // a hash read before the subclass set its field: recorded under a stale bucket, the inner store was missed
        assertRefusedWithoutKey(new TenantUsers("acme", inner), usersFile);
        // a hashCode that dereferences the field: the constructor threw a NullPointerException
        com.snoworca.naruserve.utils.auth.user.UserStore strict = new StrictTenantUsers("acme", inner);
        assertRefusedWithoutKey(strict, usersFile);
        assertRefusedWithoutKey(new TenantUsers("t", strict), usersFile);
        assertNotNull(Auth.builder(KeySources.of(AuthTestSupport.SECRET)).mode(AuthMode.PROD).hashIterations(1000)
                .users(new TenantUsers("acme", new FileUserStore(new File(tmp.newFolder(), "users.json"),
                        AuthTestSupport.FILE_KEY))).build());
    }

    private static void assertRefusedWithoutKey(com.snoworca.naruserve.utils.auth.user.UserStore decorated, File usersFile) {
        try {
            Auth.builder(KeySources.of(AuthTestSupport.SECRET)).mode(AuthMode.PROD).hashIterations(1000)
                    .users(decorated).build();
            fail("PROD built over " + decorated.getClass().getSimpleName() + "(FileUserStore) without a key");
        } catch (AuthConfigException e) {
            String m = e.getMessage();
            assertTrue(m, m.contains("FileUserStore(file, AuthFileKey)") && m.contains(usersFile.toString()));
        }
    }

    // FR-IAM-015 AC-4: in DEV without a key the same configuration builds, logs exactly one WARN per build() naming
    // each unencrypted file and NARU_AUTH_FILE_KEY, and the stores write as before: users file version 1 with the plain
    // hash, key file the raw 32 bytes
    @Test
    public void FR_IAM_015_AC4_devWritesPlainWithOneWarn() throws Exception {
        File dir = tmp.newFolder();
        File usersFile = new File(dir, "users.json");
        File keyFile = new File(dir, "naru-auth.key");
        RecordingLogger log = new RecordingLogger();
        FileUserStore store = new FileUserStore(usersFile);
        Auth auth = Auth.builder(KeySources.fromFile(keyFile)).mode(AuthMode.DEV).hashIterations(1000).logger(log)
                .users(store).build();
        List<String> warns = RecordingLogger.matching(log.lines, "WARN .*NARU_AUTH_FILE_KEY.*");
        assertEquals(log.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains(usersFile.toString()) && warns.get(0).contains(keyFile.toString()));
        auth.users().create("alice", PW.toCharArray());
        assertEquals(1L, version(usersFile));
        assertEquals(store.findByUsername("alice").getPasswordHash(), storedHash(usersFile, "alice"));
        assertEquals(32, bytes(keyFile).length);
        assertFalse(text(keyFile).startsWith("enc("));
        Auth.builder(KeySources.fromFile(keyFile)).mode(AuthMode.DEV).hashIterations(1000).logger(log).users(store)
                .build();
        assertEquals("one WARN per build()", 2, RecordingLogger.countMatching(log.lines, "WARN .*NARU_AUTH_FILE_KEY.*"));
    }

    // FR-IAM-015 AC-5: the first load with a key rewrites a version-1 users file (three plain hashes, a null-hash
    // service account) and a raw key file once and atomically: users file version 2 with enc(...) hashes, the null
    // hash still null, the key file enc(...), no .bak, temp or staging file; the users log in with their old passwords
    // and a token signed before the migration still verifies after it
    @Test
    public void FR_IAM_015_AC5_firstLoadWithAKeyMigrates() throws Exception {
        File dir = tmp.newFolder();
        File usersFile = new File(dir, "users.json");
        File keyFile = new File(dir, "naru-auth.key");
        plainFixture(usersFile, keyFile);
        byte[] rawKey = bytes(keyFile);
        FileUserStore before = new FileUserStore(usersFile);
        Auth old = Auth.builder(KeySources.fromFile(keyFile)).mode(AuthMode.DEV).hashIterations(1000).users(before)
                .build();
        String token = old.tokens().issue(before.findByUsername("alice")).getAccessToken();

        RecordingLogger migrationLog = new RecordingLogger();
        FileUserStore migrated = new FileUserStore(usersFile, FileUserStore.DEFAULT_MAX_FILE_BYTES, migrationLog, key());
        assertEquals(2L, version(usersFile));
        assertEquals(migrationLog.lines.toString(), 1, RecordingLogger.countMatching(migrationLog.lines,
                "INFO auth store: encrypted 3 password hashes of " + java.util.regex.Pattern.quote(usersFile.toString())
                        + " .*"));
        for (Map<String, Object> r : rows(usersFile)) {
            Object h = r.get("passwordHash");
            if ("svc".equals(r.get("username"))) {
                assertNull(h);
            } else {
                assertTrue(String.valueOf(h), SecretBox.isSealed((String) h));
            }
        }
        byte[] usersAfter = bytes(usersFile);
        new FileUserStore(usersFile, key()); // a second load with the key does not rewrite
        assertArrayEquals("rewritten once", usersAfter, bytes(usersFile));

        Auth after = Auth.builder(KeySources.fromFile(keyFile, key())).mode(AuthMode.PROD).hashIterations(1000)
                .users(migrated).build();
        String keyText = text(keyFile);
        assertTrue(keyText, keyText.matches("^enc\\([A-Za-z0-9_-]+\\)$"));
        assertArrayEquals(rawKey, SecretBox.of(key()).open(SecretBox.SIGNING_KEY, keyFile.getName(), keyText));
        assertNotNull(after.tokens().verifyAccess(token));
        for (String n : listing(dir)) {
            assertFalse(n, n.endsWith(".bak") || n.endsWith(".tmp") || n.contains(".new-"));
        }
        W8 w = node(migrated, KeySources.fromFile(keyFile, key()), b -> b);
        for (String n : new String[] {"alice", "bob", "carol"}) {
            assertNotNull(n, w.loginBody(n, PW + n).get("accessToken"));
        }
        assertArrayEquals("the key file is rewritten once", keyText.getBytes(StandardCharsets.UTF_8), bytes(keyFile));
    }

    // FR-IAM-015 AC-6: after the migration, opening the users file or the key file without a key (DEV or PROD) fails
    // closed with an IOException stating the file holds encrypted values and an AuthFileKey is needed, and the file is
    // unchanged; the migrated users file is format version 2, which a version-1-only reader refuses as unknown
    @Test
    public void FR_IAM_015_AC6_migratedFilesNeedTheKey() throws Exception {
        File dir = tmp.newFolder();
        File usersFile = new File(dir, "users.json");
        File keyFile = new File(dir, "naru-auth.key");
        plainFixture(usersFile, keyFile);
        new FileUserStore(usersFile, key());
        KeySources.fromFile(keyFile, key()).signingKey();
        byte[] users = bytes(usersFile);
        byte[] keyBytes = bytes(keyFile);
        assertEquals(2L, version(usersFile));
        try {
            new FileUserStore(usersFile);
            fail("an encrypted users file was opened without a key");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("encrypted values")
                    && e.getMessage().contains("AuthFileKey") && e.getMessage().contains(usersFile.toString()));
        }
        try {
            KeySources.fromFile(keyFile).signingKey();
            fail("an encrypted key file was read without a key");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("encrypted value")
                    && e.getMessage().contains("AuthFileKey") && e.getMessage().contains(keyFile.toString()));
        }
        for (AuthMode mode : AuthMode.values()) {
            try {
                Auth.builder(KeySources.fromFile(keyFile)).mode(mode).hashIterations(1000).users(new InMemoryUserStore())
                        .build();
                fail(mode + " built over an encrypted key file without a key");
            } catch (AuthConfigException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(keyFile.toString()));
            }
        }
        assertArrayEquals(users, bytes(usersFile));
        assertArrayEquals(keyBytes, bytes(keyFile));
        // REL-IAM-004 AC-7: a reader refuses a version it does not know, naming it, without touching the file; a
        // version-1-only reader meets version 2 the way this release meets version 3
        String v2 = text(usersFile);
        assertTrue(v2, v2.contains("\"version\":2"));
        write(usersFile, v2.replace("\"version\":2", "\"version\":3"));
        byte[] v3 = bytes(usersFile);
        try {
            new FileUserStore(usersFile, key());
            fail("an unknown version was read");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unknown naru-users format version 3"));
            assertFalse(e.getMessage(), e.getMessage().contains("passwordHash of user"));
        }
        assertArrayEquals(v3, bytes(usersFile));
    }

    // FR-IAM-015 AC-7: the doctor item 'plain secret values in auth files' counts plain passwordHash values plus plain
    // key files: DEV without a key over the AC-5 fixture -> 4 at WARN; with a key after migration -> 0 and no WARN;
    // an Auth with no file store has no such item
    @Test
    public void FR_IAM_015_AC7_doctorCountsPlainSecrets() throws Exception {
        String item = "plain secret values in auth files";
        File dir = tmp.newFolder();
        File usersFile = new File(dir, "users.json");
        File keyFile = new File(dir, "naru-auth.key");
        plainFixture(usersFile, keyFile);
        W8 dev = node(new FileUserStore(usersFile), KeySources.fromFile(keyFile), b -> b.mode(AuthMode.DEV));
        Doctor.Finding found = null;
        for (Doctor.Finding f : dev.auth.doctor(dev.app).findings()) {
            if (f.getItem().equals(item)) {
                found = f;
            }
        }
        assertNotNull(found);
        assertEquals(Doctor.Level.WARN, found.getLevel());
        assertTrue(found.getMessage(), found.getMessage().startsWith("4 "));
        assertEquals(1, RecordingLogger.countMatching(dev.logger.lines, "WARN auth doctor WARN " + item + ": 4 .*"));
        dev.stop();

        W8 keyed = node(new FileUserStore(usersFile, key()), KeySources.fromFile(keyFile, key()), b -> b);
        for (Doctor.Finding f : keyed.auth.doctor(keyed.app).findings()) {
            assertFalse(f.toString(), f.getItem().equals(item));
        }
        assertEquals(0, RecordingLogger.countMatching(keyed.logger.lines, "WARN .*" + item + ".*"));
        assertTrue(RecordingLogger.countMatching(keyed.logger.lines, "INFO auth doctor: " + item + ": 0") >= 1);

        W8 memory = node(new InMemoryUserStore(), null, b -> b);
        for (Doctor.Finding f : memory.auth.doctor(memory.app).findings()) {
            assertFalse(f.toString(), f.getItem().equals(item));
        }
        assertEquals(0, RecordingLogger.countMatching(memory.logger.lines, ".*" + item + ".*"));
    }

    // FR-IAM-015 AC-8: no public method re-encrypts files under a new key (rotation is out of scope); another key
    // fails closed (SEC-IAM-008 AC-4 and AC-5); docs/api states the rotation limit and the reserved version byte
    @Test
    public void FR_IAM_015_AC8_noRotation() throws Exception {
        for (Class<?> c : new Class<?>[] {AuthFileKey.class, FileUserStore.class, KeySources.class, Auth.class,
                Auth.Builder.class}) {
            for (Method m : c.getMethods()) {
                int keys = 0;
                for (Class<?> p : m.getParameterTypes()) {
                    keys += p == AuthFileKey.class ? 1 : 0;
                }
                assertTrue(m.toString(), keys < 2);
                String n = m.getName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(m.toString(), n.contains("encrypt") || n.contains("rekey") || n.contains("rotatefile"));
            }
        }
        File usersFile = new File(tmp.newFolder(), "users.json");
        new FileUserStore(usersFile, key()).create(AuthUser.builder().username("a").passwordHash("h").build());
        try {
            new FileUserStore(usersFile, AuthFileKey.of(otherMaster()));
            fail("another key opened the file");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("does not authenticate"));
        }
        String ko = section(page("auth.md"), "## 파일 비밀 값 암호화: AuthFileKey");
        String en = section(page("en/auth.md"), "## Encrypting file secrets: AuthFileKey");
        has("auth.md", ko, "키 회전은 0.11.0에서 지원하지 않습니다", "버전 바이트");
        has("en/auth.md", en, "Key rotation is not supported in 0.11.0", "version byte");
    }

    // FR-IAM-015 AC-10: docs/api/auth.md and en/auth.md describe the enc(...) form, what is encrypted and what stays
    // plain with the reason, how to make a key, AuthFileKey.of/fromEnv and NARU_AUTH_FILE_KEY, the PROD failure, the
    // DEV WARN and what DEV stores, the first-load migration, the doctor item and the rotation limit, with the Express
    // framing and Java and Kotlin examples
    @Test
    public void FR_IAM_015_AC10_docsDescribeFileSecrets() throws Exception {
        String ko = section(page("auth.md"), "## 파일 비밀 값 암호화: AuthFileKey");
        String en = section(page("en/auth.md"), "## Encrypting file secrets: AuthFileKey");
        for (String[] pair : new String[][] {{"auth.md", ko}, {"en/auth.md", en}}) {
            has(pair[0], pair[1], "`enc(<payload>)`", "AES-256-GCM", "`passwordHash`", "`KeySources.fromFile`",
                    "`tokenHash`", "`attributes`", "openssl rand -base64 32", "`AuthFileKey.of(", "`AuthFileKey.fromEnv()`",
                    "`NARU_AUTH_FILE_KEY`", "`AuthConfigException`", "plain secret values in auth files", "```java",
                    "```kotlin");
        }
        has("auth.md", ko, "Express 대응", "암호화하는 값", "평문으로 두는 값", "PROD에서", "DEV에서", "처음 읽을 때",
                "형식 버전 2", "원시 32바이트", "키 회전은 0.11.0에서 지원하지 않습니다");
        has("en/auth.md", en, "Express counterpart", "Encrypted values", "Values kept plain", "In PROD", "In DEV",
                "first load", "format version 2", "raw 32 bytes", "Key rotation is not supported in 0.11.0");
        for (String p : new String[] {"auth.md", "en/auth.md"}) {
            String all = page(p);
            assertFalse(p + " still marks the AuthFileKey names as not final",
                    all.contains("AuthFileKey") && (all.contains("AuthFileKey`(미확정)") || all.contains("(name not final)")));
        }
    }

    private static String page(String path) throws IOException {
        File f = new File(System.getProperty("user.dir"), "../docs/api/" + path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[8192];
            for (int r; (r = in.read(b)) > 0;) {
                out.write(b, 0, r);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String section(String page, String heading) {
        int start = page.indexOf(heading);
        assertTrue("no section " + heading, start >= 0);
        int end = page.indexOf("\n## ", start + heading.length());
        return page.substring(start, end < 0 ? page.length() : end);
    }

    private static void has(String where, String text, String... needles) {
        for (String n : needles) {
            assertTrue(where + " lacks: " + n, text.contains(n));
        }
    }
}
