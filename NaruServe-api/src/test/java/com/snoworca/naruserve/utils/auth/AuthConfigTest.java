package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** AuthConfig.load(file): Properties values, env: and file: secret references, unknown keys (FR-AUTH-002). */
public class AuthConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File config(String... lines) throws IOException {
        File f = tmp.newFile();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    private static AuthConfigException refused(Auth.Builder b) {
        try {
            b.build();
            fail("build() accepted the configuration");
            throw new AssertionError();
        } catch (AuthConfigException e) {
            return e;
        }
    }

    // FR-AUTH-002 AC-1: env:NAME resolves to the variable; an unset variable is named, without any secret value
    @Test
    public void FR_AUTH_002_AC1_envReferenceResolvesOrNamesTheVariable() throws Exception {
        Auth auth = Auth.builder((String) null)
                .config(AuthConfig.load(config("naru.auth.secret=env:NARU_TEST_AUTH_SECRET"))).build();
        assertEquals(AuthMode.PROD, auth.mode());
        AuthConfigException e = refused(Auth.builder("Q8w-not-used-but-strong-enough-1234567")
                .config(AuthConfig.load(config("naru.auth.secret=env:JWT_KEYS_SURELY_UNSET_93B1"))));
        assertTrue(e.getMessage(), e.getMessage().contains("JWT_KEYS_SURELY_UNSET_93B1"));
        assertFalse(e.getMessage(), e.getMessage().contains("Q8w-not-used"));
        assertFalse(e.getMessage(), e.getMessage().contains(System.getenv("NARU_TEST_AUTH_SECRET")));
    }

    // FR-AUTH-002 AC-2: file:/path resolves to the file content; a missing file is named
    @Test
    public void FR_AUTH_002_AC2_fileReferenceResolvesOrNamesThePath() throws Exception {
        File secret = tmp.newFile("x");
        try (OutputStream out = new FileOutputStream(secret)) {
            out.write("Zr8+Lq3/Vn6=Kt1Xw9Hb4Mc7Pd2Fs5Gy0Jq\n".getBytes(StandardCharsets.UTF_8));
        }
        Auth.builder((String) null).config(AuthConfig.load(config("naru.auth.secret=file:" + secret.getPath())))
                .build();
        File missing = new File(tmp.getRoot(), "run/secrets/x");
        AuthConfigException e = refused(Auth.builder((String) null)
                .config(AuthConfig.load(config("naru.auth.secret=file:" + missing.getPath()))));
        assertTrue(e.getMessage(), e.getMessage().contains(missing.getPath()));
        // a short secret file is refused like a short secret
        File weak = tmp.newFile("weak");
        try (OutputStream out = new FileOutputStream(weak)) {
            out.write("tiny".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(refused(Auth.builder((String) null).config(AuthConfig.load(config("naru.auth.secret=file:"
                + weak.getPath())))).getMessage().contains("4 bytes"));
    }

    // FR-AUTH-002 AC-3: an unknown key is named and never ignored
    @Test
    public void FR_AUTH_002_AC3_unknownKeyIsNamed() throws Exception {
        AuthConfigException e = refused(AuthTestSupport.builder()
                .config(AuthConfig.load(config("naru.auth.acess-ttl-minutes=15", "naru.auth.mode=prod"))));
        assertEquals(e.getViolations().toString(), 1, e.getViolations().size());
        assertTrue(e.getMessage(), e.getMessage().contains("naru.auth.acess-ttl-minutes"));
    }

    // FR-AUTH-002 AC-4: the format is java.util.Properties and no key names a class
    @Test
    public void FR_AUTH_002_AC4_propertiesFormatAndNoClassKeys() throws Exception {
        Properties p = new Properties();
        p.setProperty("naru.auth.mode", "dev");
        p.setProperty("naru.auth.mount-path", "/api/auth");
        p.setProperty("naru.auth.permit", "/health, /status");
        Auth auth = Auth.builder((String) null).config(AuthConfig.from(p)).build();
        assertEquals(AuthMode.DEV, auth.mode());
        assertEquals("/api/auth", auth.mountPath());
        Auth fromFile = Auth.builder((String) null).config(AuthConfig.load(config("# operator file",
                "naru.auth.mode = DEV", "naru.auth.profile=strict", "naru.auth.deployment=single-instance",
                "naru.auth.transport=header", "naru.auth.stateless-access=true", "naru.auth.api-key-scheme=ak",
                "naru.auth.permit-prefix=/public", "naru.auth.protect-only=/api"))).build();
        assertTrue(fromFile.toString(), fromFile.toString().contains("profile=STRICT"));
        for (String key : AuthConfig.KEYS) {
            String k = key.toLowerCase(java.util.Locale.ROOT);
            assertFalse(key, k.contains("class") || k.contains("store") || k.contains("sink") || k.contains("factory"));
        }
        AuthConfigException e = refused(AuthTestSupport.builder().config(AuthConfig.load(config(
                "naru.auth.user-store=com.example.MyStore"))));
        assertTrue(e.getMessage(), e.getMessage().contains("unknown key naru.auth.user-store"));
    }

    // FR-AUTH-002 AC-5: file violations and builder violations are reported in one AuthConfigException
    @Test
    public void FR_AUTH_002_AC5_configAndBuilderViolationsAggregate() throws Exception {
        AuthConfigException e = refused(Auth.builder("short").permitPrefix("/")
                .config(AuthConfig.load(config("naru.auth.unknown=1", "naru.auth.mode=staging",
                        "naru.auth.secret=plain-text-in-the-file"))));
        String all = e.getViolations().toString();
        assertEquals(all, 5, e.getViolations().size());
        assertTrue(all, all.contains(".permitPrefix(\"/\")"));
        assertTrue(all, all.contains("naru.auth.unknown"));
        assertTrue(all, all.contains("naru.auth.mode has an unknown value staging"));
        assertTrue(all, all.contains("must be a reference"));
        assertFalse(all, all.contains("plain-text-in-the-file"));
    }
}
