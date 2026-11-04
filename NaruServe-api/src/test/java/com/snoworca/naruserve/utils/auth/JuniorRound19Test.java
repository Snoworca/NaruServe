package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The code findings of the nineteenth junior study are answered with the fix (OPS-AUTH-031). */
public class JuniorRound19Test {

    private final List<W8> fixtures = new ArrayList<>();

    @After
    public void stop() {
        for (W8 f : fixtures) {
            f.stop();
        }
    }

    private W8 fixture() {
        W8 f = new W8();
        fixtures.add(f);
        return f;
    }

    private static String message(ClientResponse r) {
        return String.valueOf(((Map<?, ?>) W8.object(r).get("error")).get("message"));
    }

    // OPS-AUTH-031 AC-1: the DEV protected-route WARN never advises a permit for auth's own endpoints
    @Test
    public void OPS_AUTH_031_AC1_noPermitAdviceForAuthEndpoints() {
        W8 f = fixture();
        f.start(b -> b.mode(AuthMode.DEV));
        assertEquals(401, f.get("/auth/me").status());
        assertEquals(401, f.get("/auth/admin/users").status());
        assertTrue(f.logger.lines.toString(),
                RecordingLogger.matching(f.logger.lines, "(?s)WARN .*default deny protects.*").isEmpty());
        assertEquals(401, f.get("/health").status()); // an app route still gets it
        assertEquals(1, RecordingLogger.matching(f.logger.lines, "(?s)WARN .*GET /health.*default deny protects.*").size());
    }

    // OPS-AUTH-031 AC-2: an empty Bearer value says it is empty
    @Test
    public void OPS_AUTH_031_AC2_emptyBearerIsNamed() {
        W8 f = fixture();
        f.start(b -> b);
        String m = message(f.get("/api/x", "Authorization", "Bearer "));
        assertTrue(m, m.contains("the Bearer value is empty"));
    }

    // OPS-AUTH-031 AC-3: a short AuthFileKey gives the decoded byte count, and a value that is not base64 says so
    @Test
    public void OPS_AUTH_031_AC3_shortFileKeyGivesTheCount() {
        try {
            AuthFileKey.of("q6Cz0bT4mW9xN2rE7kJ5vH8sL1pF3dG6aY0u"); // 27 bytes
            fail("accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("decodes to 27 bytes"));
        }
        try {
            AuthFileKey.of("not base64 !!");
            fail("accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not base64"));
        }
    }
}
