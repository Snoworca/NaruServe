package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** auth.describe() and the startup log summary (OPS-AUTH-002). */
public class DescribeTest {

    /** A 40-character signing secret used as a marker. */
    private static final String SECRET = "MARKERsecretMARKERsecretMARKERsecret4040";

    private static int linesContaining(RecordingLogger log, String needle) {
        int n = 0;
        synchronized (log.lines) {
            for (String l : log.lines) {
                if (l.contains(needle)) {
                    n++;
                }
            }
        }
        return n;
    }

    // OPS-AUTH-002 AC-1: describe() names the mode, profile, deployment, transport, the permitted paths with their
    // methods, and the protected range with the mount path
    @Test
    public void OPS_AUTH_002_AC1_describeNamesTheEffectiveConfiguration() {
        Auth a = Auth.builder(SECRET).hashIterations(1000).logger(new RecordingLogger()).profile(AuthProfile.STRICT)
                .transport(TokenTransport.BOTH).permit("/health").permit(Method.POST, "/hook").permitPrefix("/docs")
                .build();
        String d = a.describe();
        for (String needle : new String[] {"mode: PROD", "profile: STRICT", "deployment: SINGLE_INSTANCE",
            "transport: BOTH", "GET,HEAD /health", "POST /hook", "* /docs/**", "mount: /auth", "admin: /auth/admin",
            "protected: every path that is not permitted"}) {
            assertTrue(d + "\nlacks " + needle, d.contains(needle));
        }
        Auth p = Auth.builder(SECRET).hashIterations(1000).logger(new RecordingLogger()).mode(AuthMode.DEV)
                .protectOnly("/api", "/admin-ui").mountPath("/api/auth").build();
        String dp = p.describe();
        assertTrue(dp, dp.contains("mode: DEV"));
        assertTrue(dp, dp.contains("protected: /api, /admin-ui, /api/auth"));
        assertTrue(dp, dp.contains("mount: /api/auth"));
    }

    // OPS-AUTH-002 AC-2: describe() and the startup log carry no secret, key or password; the one-time setup token and
    // the DEV temporary password each appear in exactly one startup log line and never in describe()
    @Test
    public void OPS_AUTH_002_AC2_noSecretsExceptTheOneLine() {
        RecordingLogger prodLog = new RecordingLogger();
        TestApp prod = TestApp.create();
        Auth a = Auth.builder(SECRET).hashIterations(1000).logger(prodLog).recovery(true)
                .user("admin", null, "admin").user("bob", "MARKERbobPassword", "admin").build();
        a.install(prod.app());
        prod.start();
        String token = a.pendingSetupToken();
        assertNotNull(token);
        assertEquals(prodLog.lines.toString(), 1, linesContaining(prodLog, token));
        assertEquals(0, linesContaining(prodLog, SECRET));
        assertEquals(0, linesContaining(prodLog, "MARKERbobPassword"));
        for (String s : new String[] {a.describe(), a.toString()}) {
            assertFalse(s, s.contains(token) || s.contains(SECRET) || s.contains("MARKER"));
        }

        RecordingLogger devLog = new RecordingLogger();
        TestApp dev = TestApp.create();
        Auth d = Auth.builder(SECRET).hashIterations(1000).logger(devLog).mode(AuthMode.DEV)
                .user("admin", null, "admin").build();
        d.install(dev.app());
        dev.start();
        String temporary = null;
        synchronized (devLog.lines) {
            for (String l : devLog.lines) {
                Matcher m = Pattern.compile("temporary password (\\S+) ").matcher(l);
                if (m.find()) {
                    temporary = m.group(1);
                }
            }
        }
        assertNotNull(devLog.lines.toString(), temporary);
        assertEquals(1, linesContaining(devLog, temporary));
        assertEquals(0, linesContaining(devLog, SECRET));
        assertFalse(d.describe().contains(temporary));
        assertFalse(d.toString().contains(temporary));
    }

    // OPS-AUTH-002 AC-3: the startup log states that .user never overwrites an existing account's password, the
    // restart invalidation of a DEV temporary key, one recovery WARN per start, and the allowInsecureTransport reason
    @Test
    public void OPS_AUTH_002_AC3_startupLogStatesTheOperatorFacts() {
        RecordingLogger log = new RecordingLogger();
        TestApp t = TestApp.create();
        Auth a = Auth.builder(SECRET).hashIterations(1000).logger(log).recovery(true)
                .allowInsecureTransport("device server before its TLS adapter").user("bob", "pw-pw-pw-pw", "admin")
                .build();
        a.install(t.app());
        int before = log.lines.size();
        t.start();
        String started = log.lines.subList(before, log.lines.size()).toString();
        assertEquals(started, 1, RecordingLogger.countMatching(log.lines,
                "INFO .*\\.user never overwrites an existing account or its password.*"));
        assertEquals(started, 1, RecordingLogger.countMatching(log.lines, "WARN .*recovery is enabled.*"));
        assertEquals(started, 1, RecordingLogger.countMatching(log.lines,
                "WARN .*device server before its TLS adapter.*"));

        // a second start of the same configuration logs each of them once more
        TestApp again = TestApp.create();
        Auth.builder(SECRET).hashIterations(1000).logger(log).recovery(true)
                .allowInsecureTransport("device server before its TLS adapter").user("bob", "pw-pw-pw-pw", "admin")
                .build().install(again.app());
        again.start();
        assertEquals(2, RecordingLogger.countMatching(log.lines, "WARN .*recovery is enabled.*"));
        assertEquals(2, RecordingLogger.countMatching(log.lines, "WARN .*device server before its TLS adapter.*"));
        assertEquals(2, RecordingLogger.countMatching(log.lines,
                "INFO .*\\.user never overwrites an existing account or its password.*"));

        RecordingLogger devLog = new RecordingLogger();
        TestApp dev = TestApp.create();
        Auth d = Auth.builder((String) null).hashIterations(1000).logger(devLog).mode(AuthMode.DEV).build();
        d.install(dev.app());
        int devBefore = devLog.lines.size();
        dev.start();
        String devStart = devLog.lines.subList(devBefore, devLog.lines.size()).toString();
        assertTrue(devStart, devStart.contains("temporary signing key") && devStart.contains("restart"));
        assertEquals(0, RecordingLogger.countMatching(devLog.lines, ".*recovery is enabled.*"));
        assertEquals(0, RecordingLogger.countMatching(devLog.lines, ".*\\.user never overwrites.*"));
    }
}
