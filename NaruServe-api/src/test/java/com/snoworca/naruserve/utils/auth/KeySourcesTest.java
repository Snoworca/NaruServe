package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import com.snoworca.naruserve.utils.auth.spi.KeySource;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** KeySource, KeySources and kid key sets (SEC-TOKEN-004). */
public class KeySourcesTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] key(char fill) {
        byte[] k = ("abcdefghijklmnopqrstuvwxyz012345" + fill).getBytes(StandardCharsets.US_ASCII);
        return k;
    }

    private static AuthConfigException refused(Auth.Builder b) {
        try {
            b.build();
        } catch (AuthConfigException e) {
            return e;
        }
        throw new AssertionError("built");
    }

    // SEC-TOKEN-004 AC-2: a key set has exactly one signing key; zero or two fail at build time
    @Test
    public void SEC_TOKEN_004_AC2_exactlyOneSigningKey() {
        try {
            JwtKeys.builder().verificationKey("k1", key('1')).build();
            fail("built a set without a signing key");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("exactly one signing key"));
        }
        try {
            JwtKeys.builder().signingKey("k1", key('1')).signingKey("k2", key('2')).build();
            fail("built a set with two signing keys");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("it has 2"));
        }
        JwtKeys set = JwtKeys.builder().signingKey("k2", key('2')).verificationKey("k1", key('1')).build();
        assertEquals("k2", set.getSigningKid());
        assertArrayEquals(key('2'), set.getSigningKey());
        assertArrayEquals(key('1'), set.verificationKey("k1"));
        assertNull(set.verificationKey("k3"));
        assertEquals(Arrays.asList("k2", "k1"), new java.util.ArrayList<>(set.getKids()));
        assertTrue(set.toString(), !set.toString().contains("abcdefgh"));
        // through Auth.builder: the rotating source keeps both kids and its signing kid
        Auth auth = Auth.builder(KeySources.rotating(set)).build();
        assertEquals("k2", auth.keySet().getSigningKid());
        assertNotNull(auth.keySet().verificationKey("k1"));
        // a weak verification key is refused in PROD like a weak signing key
        AuthConfigException weak = refused(Auth.builder(KeySources.rotating(JwtKeys.builder()
                .signingKey("k2", key('2')).verificationKey("k1", "short".getBytes(StandardCharsets.US_ASCII)).build())));
        assertTrue(weak.getMessage(), weak.getMessage().contains("verification key k1"));
    }

    // SEC-TOKEN-004 AC-3: PROD, Auth.builder(KeySources.of(weak secret)) is refused with "openssl rand -base64 32"
    @Test
    public void SEC_TOKEN_004_AC3_weakSecretThroughKeySourcesIsRefused() {
        for (String weak : new String[] {"short", "changeme", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}) {
            AuthConfigException e = refused(Auth.builder(KeySources.of(weak)));
            assertTrue(e.getMessage(), e.getMessage().contains("openssl rand -base64 32"));
        }
    }

    // an empty secret counts as missing: PROD refuses it with the generation advice, DEV uses a temporary key
    @Test
    public void emptySecretIsMissing() {
        AuthConfigException e = refused(Auth.builder(""));
        assertTrue(e.getMessage(), e.getMessage().contains("openssl rand -base64 32"));
        refused(Auth.builder(KeySources.fromBytes(new byte[0])));
        com.snoworca.naruserve.testing.RecordingLogger log = new com.snoworca.naruserve.testing.RecordingLogger();
        Auth dev = Auth.builder("").mode(AuthMode.DEV).logger(log).build();
        assertEquals(32, dev.keySet().getSigningKey().length);
        assertEquals(1, log.count("WARN"));
    }

    // SEC-TOKEN-004 AC-4: fromEnv(name) unset or empty fails at build() naming the variable
    @Test
    public void SEC_TOKEN_004_AC4_unsetEnvironmentVariableIsNamed() {
        AuthConfigException e = refused(Auth.builder(KeySources.fromEnv("NARU_TEST_KEY_SURELY_UNSET_51B2")));
        assertTrue(e.getMessage(), e.getMessage().contains("NARU_TEST_KEY_SURELY_UNSET_51B2"));
        assertNull(System.getenv("NARU_TEST_KEY_SURELY_UNSET_51B2"));
    }

    /** Runs in a child JVM holding the variable set and empty; exits non-zero unless build() refuses it by name. */
    public static class EmptyEnvProbe {
        static final String NAME = "NARU_TEST_KEY_EMPTY_51B2";

        public static void main(String[] args) {
            if (!"".equals(System.getenv(NAME))) {
                throw new AssertionError("the variable is not set and empty");
            }
            try {
                Auth.builder(KeySources.fromEnv(NAME)).build();
            } catch (AuthConfigException e) {
                if (!e.getMessage().contains(NAME)) {
                    throw new AssertionError(e.getMessage());
                }
                return;
            }
            throw new AssertionError("built with an empty variable");
        }
    }

    // SEC-TOKEN-004 AC-4: fromEnv(name) with the variable set but empty fails at build() naming the variable (a child
    // process holds the empty variable, as a JVM cannot change its own environment)
    @Test(timeout = 60_000)
    public void SEC_TOKEN_004_AC4_emptyEnvironmentVariableIsNamed() throws Exception {
        String cp = System.getProperty("java.class.path") + File.pathSeparator
                + new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getPath()
                + File.pathSeparator
                + new File(Auth.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        File output = tmp.newFile("empty-env.log");
        ProcessBuilder builder = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-cp", cp, EmptyEnvProbe.class.getName()).redirectErrorStream(true).redirectOutput(output);
        builder.environment().put(EmptyEnvProbe.NAME, "");
        Process child = builder.start();
        boolean done = child.waitFor(40, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) {
            child.destroyForcibly();
        }
        String log = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        assertTrue("the probe did not finish: " + log, done);
        assertEquals(log, 0, child.exitValue());
    }

    // SEC-TOKEN-004 AC-5: two missing files get different keys, so the 32 bytes are generated, not fixed (their source
    // SecureRandom is asserted on the class file in AuthArchitectureTest)
    @Test
    public void SEC_TOKEN_004_AC5_freshFilesGetDifferentKeys() throws Exception {
        byte[] a = KeySources.fromFile(new File(tmp.getRoot(), "a.key")).signingKey();
        byte[] b = KeySources.fromFile(new File(tmp.getRoot(), "b.key")).signingKey();
        assertEquals(32, a.length);
        assertEquals(32, b.length);
        assertTrue(!Arrays.equals(a, b));
    }

    // SEC-TOKEN-004 AC-5: fromFile creates a missing file with 32 random bytes, owner-only, returns the same key next
    // time, and never overwrites an existing file
    @Test
    public void SEC_TOKEN_004_AC5_fromFileCreatesOnceAndNeverOverwrites() throws Exception {
        File f = new File(tmp.getRoot(), "naru-auth.key");
        byte[] first = KeySources.fromFile(f).signingKey();
        assertEquals(32, first.length);
        assertTrue(f.isFile());
        assertEquals(32, f.length());
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(f.toPath());
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
        }
        assertArrayEquals(first, KeySources.fromFile(f).signingKey());
        // DEV: a key file without an AuthFileKey holds the raw bytes (FR-IAM-015 AC-4); PROD needs the key (AC-3)
        Auth a = Auth.builder(KeySources.fromFile(f)).mode(AuthMode.DEV).build();
        Auth b = Auth.builder(KeySources.fromFile(f)).mode(AuthMode.DEV).build();
        assertEquals(a.keySet().getSigningKid(), b.keySet().getSigningKid());
        assertArrayEquals(first, b.keySet().getSigningKey());

        File existing = new File(tmp.getRoot(), "existing.key");
        byte[] mine = key('9');
        try (FileOutputStream out = new FileOutputStream(existing)) {
            out.write(mine);
        }
        long modified = existing.lastModified();
        assertArrayEquals(mine, KeySources.fromFile(existing).signingKey());
        assertArrayEquals(mine, Files.readAllBytes(existing.toPath()));
        assertEquals(modified, existing.lastModified());
        assertTrue(Arrays.equals(mine, Auth.builder(KeySources.fromFile(existing)).mode(AuthMode.DEV).build().keySet().getSigningKey()));

        File missingDir = new File(new File(tmp.getRoot(), "no-such-dir"), "k.key");
        AuthConfigException e = refused(Auth.builder(KeySources.fromFile(missingDir)).mode(AuthMode.DEV));
        assertTrue(e.getMessage(), e.getMessage().contains("key file " + missingDir + " failed to provide a signing key"));
        File empty = tmp.newFile("empty.key");
        AuthConfigException emptyFile = refused(Auth.builder(KeySources.fromFile(empty)).mode(AuthMode.DEV));
        assertTrue(emptyFile.getMessage(), emptyFile.getMessage().contains("is empty"));
        assertEquals(0, empty.length());
        String[] left = tmp.getRoot().list();
        for (String name : left) {
            assertTrue(name, !name.contains(".new-")); // the staging directory is gone
        }
    }

    // SEC-TOKEN-004 AC-5: creators racing on one fresh path never overwrite each other's file: every caller gets the
    // bytes that end up on disk (two Auth builds in one JVM; across processes the same lock file serializes them)
    @Test
    public void SEC_TOKEN_004_AC5_concurrentCreatorsAgreeOnOneKey() throws Exception {
        final int threads = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            for (int trial = 0; trial < 150; trial++) {
                final File f = new File(tmp.getRoot(), "race-" + trial + ".key");
                final java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(threads);
                java.util.List<java.util.concurrent.Future<byte[]>> results = new java.util.ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    results.add(pool.submit(() -> {
                        start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        return KeySources.fromFile(f).signingKey();
                    }));
                }
                byte[] onDisk = null;
                for (java.util.concurrent.Future<byte[]> r : results) {
                    byte[] got = r.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (onDisk == null) {
                        onDisk = Files.readAllBytes(f.toPath());
                    }
                    assertArrayEquals("trial " + trial + ": a creator kept a key the file no longer holds", onDisk, got);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        for (String name : tmp.getRoot().list()) {
            assertTrue(name, !name.contains(".new-"));
        }
    }

    // SEC-TOKEN-004 component (the token-level checks need token signing, SEC-TOKEN-003): the same key material gets
    // the same kid and key set from of and fromBytes; an app KeySource names its own kid
    @Test
    public void sameMaterialSameKeySet() throws Exception {
        String secret = AuthTestSupport.SECRET;
        JwtKeys viaOf = Auth.builder(KeySources.of(secret)).build().keySet();
        JwtKeys viaBytes = Auth.builder(KeySources.fromBytes(secret.getBytes(StandardCharsets.UTF_8))).build().keySet();
        JwtKeys viaString = Auth.builder(secret).build().keySet();
        assertEquals(viaOf.getSigningKid(), viaBytes.getSigningKid());
        assertEquals(viaOf.getSigningKid(), viaString.getSigningKid());
        assertArrayEquals(viaOf.getSigningKey(), viaBytes.getSigningKey());
        final byte[] vault = key('v');
        KeySource custom = new KeySource() {
            @Override
            public byte[] signingKey() {
                return vault.clone();
            }

            @Override
            public String signingKid() {
                return "vault-1";
            }
        };
        assertEquals("vault-1", Auth.builder(custom).build().keySet().getSigningKid());
        KeySource sam = () -> vault.clone();
        assertEquals(JwtKeys.of(vault).getSigningKid(), sam.keys().getSigningKid());
    }
}
