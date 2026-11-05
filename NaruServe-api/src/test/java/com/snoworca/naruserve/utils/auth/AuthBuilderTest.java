package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.group.FileGroupStore;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import com.snoworca.naruserve.utils.auth.internal.SecretRules;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Auth.builder and Auth.Builder.build(): secret rejection, aggregated validation, profile and deployment. */
public class AuthBuilderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static AuthConfigException refused(Auth.Builder b) {
        try {
            b.build();
            fail("build() accepted " + b);
            throw new AssertionError();
        } catch (AuthConfigException e) {
            return e;
        }
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    // SEC-AUTH-001 AC-1: PROD, a null secret: the message tells how to generate one and, for a temporary key, names
    // .mode(AuthMode.DEV) and .mode(AuthMode.fromEnv()) with NARU_ENV=dev (NARU_ENV alone does nothing)
    @Test
    public void SEC_AUTH_001_AC1_nullSecretInProdTellsHowToGenerateOne() {
        AuthConfigException e = refused(Auth.builder((String) null));
        assertTrue(e.getMessage(), e.getMessage().contains("openssl rand -base64 32"));
        assertTrue(e.getMessage(), e.getMessage().contains(".mode(AuthMode.DEV)"));
        assertTrue(e.getMessage(), e.getMessage().contains(".mode(AuthMode.fromEnv()) with NARU_ENV=dev"));
        assertFalse(e.getMessage(), e.getMessage().contains("or run with NARU_ENV=dev"));
        assertTrue(e.getMessage(), e.getMessage().contains("JWT_SECRET is not set"));
        AuthConfigException env = refused(Auth.builder(KeySources.fromEnv("NARU_TEST_SURELY_UNSET_7F3A")));
        assertTrue(env.getMessage(), env.getMessage().contains("NARU_TEST_SURELY_UNSET_7F3A"));
        assertTrue(env.getMessage(), env.getMessage().contains("openssl rand -base64 32"));
    }

    // SEC-AUTH-001 AC-2: PROD, shorter than 32 UTF-8 bytes: the message has the byte count and the minimum
    @Test
    public void SEC_AUTH_001_AC2_shortSecretStatesItsByteCountAndTheMinimum() {
        AuthConfigException e = refused(Auth.builder("Ab3$kL9#qW2!"));
        assertTrue(e.getMessage(), e.getMessage().contains("12 bytes"));
        assertTrue(e.getMessage(), e.getMessage().contains("at least 32 bytes"));
        // 31 bytes counted in UTF-8: ten 3-byte characters plus one ASCII byte
        AuthConfigException multibyte = refused(Auth.builder(repeat('한', 10) + "x"));
        assertTrue(multibyte.getMessage(), multibyte.getMessage().contains("31 bytes"));
        Auth.builder("Ab3$kL9#qW2!Ab3$kL9#qW2!Ab3$kL9#").build(); // exactly 32 bytes, 12 distinct values
    }

    // SEC-AUTH-001 AC-3: PROD, the example secrets, padded or not, and the documentation sample are refused
    @Test
    public void SEC_AUTH_001_AC3_exampleSecretsArePaddedOrNotRefused() {
        for (String example : Arrays.asList("secret", "changeme", "change-me-now", "your-256-bit-secret",
                SecretRules.DOCS_SAMPLE)) {
            for (String s : Arrays.asList(example, example + repeat('=', 40), repeat('*', 30) + example,
                    example.toUpperCase(java.util.Locale.ROOT) + repeat('0', 32), "  " + example + "  ")) {
                AuthConfigException e = refused(Auth.builder(s));
                assertTrue(s + ": " + e.getMessage(), e.getMessage().contains("example"));
            }
        }
    }

    // SEC-AUTH-001 AC-4: PROD, 32 or more bytes with fewer than 8 distinct byte values is refused
    @Test
    public void SEC_AUTH_001_AC4_lowVarietyKeyIsRefused() {
        AuthConfigException e = refused(Auth.builder(repeat('a', 40)));
        assertTrue(e.getMessage(), e.getMessage().contains("1 distinct byte value;"));
        refused(Auth.builder("abcdefg" + repeat('a', 33)));
        Auth.builder("abcdefgh" + repeat('a', 32)).build();
    }

    // SEC-AUTH-001 AC-8: DEV turns the short, example and low-variety findings into WARNs; build() succeeds
    @Test
    public void SEC_AUTH_001_AC8_devReportsWeakKeysAsWarnings() {
        for (String weak : Arrays.asList("short", "changeme", repeat('a', 40))) {
            RecordingLogger log = new RecordingLogger();
            Auth auth = Auth.builder(weak).mode(AuthMode.DEV).logger(log).build();
            assertNotNull(auth);
            assertEquals(weak, 1, log.count("WARN"));
        }
    }

    // SEC-AUTH-001 AC-8: the short, example and low-variety cases do not stop start in DEV: the app serves
    @Test
    public void SEC_AUTH_001_AC8_devStartContinuesWithWeakKeys() {
        for (String weak : Arrays.asList("short", "changeme", repeat('a', 40))) {
            com.snoworca.naruserve.testing.TestApp t = com.snoworca.naruserve.testing.TestApp.create();
            Auth auth = Auth.builder(weak).mode(AuthMode.DEV).hashIterations(1000).logger(new RecordingLogger())
                    .permit("/x").build();
            auth.install(t.app());
            t.app().get("/x", (req, res) -> res.send("x"));
            try {
                t.start();
                assertEquals(weak, 200, t.send("GET", "/x").status());
            } finally {
                t.app().shutdown(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    // SEC-AUTH-001 statement (DEV temporary key): a null secret in DEV builds and logs one WARN about restarts
    @Test
    public void devTemporaryKeyIsOneWarning() {
        RecordingLogger log = new RecordingLogger();
        Auth.builder((String) null).mode(AuthMode.DEV).logger(log).build();
        assertEquals(1, log.count("WARN"));
        assertEquals(1, RecordingLogger.countMatching(log.lines, ".*temporary signing key.*invalidates all tokens.*"));
    }

    // SEC-AUTH-001 component (secret hygiene; its acceptance is the SEC-AUTH-008 marker test): no message, toString or build log line carries the secret
    @Test
    public void secretNeverAppearsInMessagesOrToString() {
        String weak = "S3cr3tValue-XYZ";
        AuthConfigException e = refused(Auth.builder(weak));
        assertFalse(e.getMessage().contains(weak));
        RecordingLogger log = new RecordingLogger();
        Auth.Builder b = Auth.builder(weak).mode(AuthMode.DEV).logger(log);
        assertFalse(b.toString().contains(weak));
        Auth auth = b.build();
        assertFalse(auth.toString().contains(weak));
        for (String line : log.lines) {
            assertFalse(line, line.contains(weak));
        }
        assertFalse(Auth.builder(AuthTestSupport.SECRET).build().toString().contains(AuthTestSupport.SECRET));
    }

    // FR-AUTH-001 AC-1: MULTI_INSTANCE + InMemoryUserStore + permitPrefix("/") are reported in one exception
    @Test
    public void FR_AUTH_001_AC1_twoViolationsInOneException() {
        AuthConfigException e = refused(Auth.builder(AuthTestSupport.SECRET).deployment(Deployment.MULTI_INSTANCE)
                .users(new InMemoryUserStore()).permitPrefix("/"));
        List<String> v = e.getViolations();
        assertEquals(v.toString(), 2, v.size());
        assertTrue(v.toString(), v.get(0).contains(".permitPrefix(\"/\")"));
        assertTrue(v.toString(), v.get(1).contains("MULTI_INSTANCE") && v.get(1).contains("InMemoryUserStore"));
    }

    // FR-AUTH-001 AC-2: each of the listed rules alone makes build() throw
    @Test
    public void FR_AUTH_001_AC2_eachRuleAloneFails() throws Exception {
        File groups = tmp.newFile("groups.txt");
        assertTrue(groups.delete());
        List<Auth.Builder> bad = Arrays.asList(
                AuthTestSupport.builder().permitPrefix("/"),
                AuthTestSupport.builder().permit(""),
                AuthTestSupport.builder().permitPrefix(""),
                AuthTestSupport.builder().permit("/café"),
                AuthTestSupport.builder().permitPrefix("/über"),
                AuthTestSupport.builder().protectOnly("/한"),
                AuthTestSupport.builder().permit(Method.POST, "/hook "),
                AuthTestSupport.builder().deployment(Deployment.MULTI_INSTANCE),
                AuthTestSupport.builder().deployment(Deployment.MULTI_INSTANCE).users(new InMemoryUserStore()),
                AuthTestSupport.builder().deployment(Deployment.MULTI_INSTANCE).groups(new InMemoryGroupStore()),
                AuthTestSupport.builder().deployment(Deployment.MULTI_INSTANCE).groups(new FileGroupStore(groups)),
                AuthTestSupport.builder().mountPath("/").transport(TokenTransport.COOKIE),
                AuthTestSupport.builder().mountPath("/").transport(TokenTransport.BOTH));
        for (Auth.Builder b : bad) {
            assertEquals(b.toString(), 1, refused(b).getViolations().size());
        }
        AuthTestSupport.builder().mountPath("/").transport(TokenTransport.HEADER).build();
        AuthTestSupport.builder().permit("/").permitPrefix("/public").protectOnly("/api").build();
    }

    // FR-AUTH-001 AC-3: the violations are a list, one entry each, and the message contains every entry
    @Test
    public void FR_AUTH_001_AC3_violationListAndMessage() {
        AuthConfigException e = refused(Auth.builder("short").permit("").permitPrefix("/")
                .mountPath("/").transport(TokenTransport.COOKIE).apiKeyScheme("Bearer"));
        assertEquals(e.getViolations().toString(), 5, e.getViolations().size());
        for (String v : e.getViolations()) {
            assertTrue(v, e.getMessage().contains(v));
        }
        try {
            e.getViolations().add("x");
            fail("violations are modifiable");
        } catch (UnsupportedOperationException expected) {
            // unmodifiable
        }
    }

    // FR-AUTH-001 AC-4: a valid builder builds; build() twice gives two independent instances
    @Test
    public void FR_AUTH_001_AC4_validBuilderBuildsTwiceIndependently() {
        Auth.Builder b = Auth.builder(AuthTestSupport.SECRET).permit("/health");
        Auth first = b.build();
        b.permit("/later").mountPath("/api/auth");
        Auth second = b.build();
        assertNotSame(first, second);
        assertEquals("/auth", first.mountPath());
        assertEquals("/api/auth", second.mountPath());
        assertNotSame(first.routes(), second.routes());
        assertNotSame(first.users(), second.users());
        assertNotSame(first.userStore(), second.userStore());
    }

    // FR-AUTH-001 AC-5: invalid apiKeyScheme names join the same aggregated exception
    @Test
    public void FR_AUTH_001_AC5_apiKeySchemeViolationsAreAggregated() {
        for (String s : Arrays.asList("Bearer", "basic", "Digest", "DPoP", "NEGOTIATE", "Api Key", "")) {
            AuthConfigException e = refused(AuthTestSupport.builder().apiKeyScheme(s).permitPrefix("/"));
            assertEquals(s, 2, e.getViolations().size());
            assertTrue(s, e.getViolations().get(1).contains(".apiKeyScheme"));
        }
        AuthTestSupport.builder().apiKeyScheme("ak").build();
        // a reserved auth-* scope is RolePolicy's own IllegalArgumentException, before Auth is built
        try {
            com.snoworca.naruserve.utils.auth.rbac.RolePolicy.builder().role("ops", "auth-secrets:read");
            fail("reserved scope accepted");
        } catch (IllegalArgumentException expected) {
            // not part of the aggregate
        }
    }

    // FR-AUTH-003 AC-1: the enum values are exactly the listed ones; no .deployment(...) is SINGLE_INSTANCE
    @Test
    public void FR_AUTH_003_AC1_profileAndDeploymentValues() {
        assertEquals(EnumSet.of(AuthProfile.BASELINE, AuthProfile.STANDARD, AuthProfile.STRICT),
                EnumSet.allOf(AuthProfile.class));
        assertEquals(EnumSet.of(Deployment.SINGLE_INSTANCE, Deployment.MULTI_INSTANCE),
                EnumSet.allOf(Deployment.class));
        // SINGLE_INSTANCE accepts the in-memory stores that MULTI_INSTANCE refuses
        Auth auth = AuthTestSupport.builder().users(new InMemoryUserStore()).groups(new InMemoryGroupStore()).build();
        assertTrue(auth.toString(), auth.toString().contains("deployment=SINGLE_INSTANCE"));
    }

    // FR-AUTH-003 component (deployment rule): MULTI_INSTANCE refuses the in-memory and file stores and the implicit in-memory
    // refresh, throttle and device-trust defaults (building with app-provided shared stores: Wave7MultiInstanceTest)
    @Test
    public void multiInstanceRefusesLocalStoresAndImplicitDefaults() {
        AuthConfigException e = refused(AuthTestSupport.builder().deployment(Deployment.MULTI_INSTANCE)
                .users(new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(new InMemoryUserStore()) { }));
        assertTrue(e.getMessage(), e.getMessage().contains("refresh token store, login throttle and device trust"));
        assertFalse(e.getMessage(), e.getMessage().contains("(user store)"));
    }

    // SEC-AUTH-002 AC-1: fromEnv maps NARU_ENV; a builder without .mode(...) runs as PROD
    @Test
    public void SEC_AUTH_002_AC1_fromEnvAndDefaultMode() {
        assertEquals(AuthMode.DEV, AuthMode.fromEnvValue("dev"));
        assertEquals(AuthMode.DEV, AuthMode.fromEnvValue("DEV"));
        for (String other : Arrays.asList(null, "", "prod", "production", "develop", " dev")) {
            assertEquals(String.valueOf(other), AuthMode.PROD, AuthMode.fromEnvValue(other));
        }
        assertEquals(AuthMode.fromEnvValue(System.getenv("NARU_ENV")), AuthMode.fromEnv());
        assertEquals(AuthMode.PROD, Auth.builder(AuthTestSupport.SECRET).build().mode());
        refused(Auth.builder((String) null)); // PROD refuses what DEV would accept
    }

    // KeySources.fromEnv reads a set variable (the Gradle test task sets NARU_TEST_AUTH_SECRET)
    @Test
    public void keySourceFromEnvReadsTheVariable() throws Exception {
        String value = System.getenv("NARU_TEST_AUTH_SECRET");
        assertNotNull("the test task sets NARU_TEST_AUTH_SECRET", value);
        assertTrue(Arrays.equals(value.getBytes(StandardCharsets.UTF_8),
                KeySources.fromEnv("NARU_TEST_AUTH_SECRET").signingKey()));
        Auth.builder(KeySources.fromEnv("NARU_TEST_AUTH_SECRET")).build();
    }
}
