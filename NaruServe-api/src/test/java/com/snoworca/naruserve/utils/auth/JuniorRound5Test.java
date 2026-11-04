package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The mistakes of the fifth junior study are answered with the fix (OPS-AUTH-014). */
public class JuniorRound5Test {

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

    // OPS-AUTH-014 AC-1: an unparsable body and a JSON value that is not an object get the same 400 message
    @Test
    public void OPS_AUTH_014_AC1_oneMessageForANonObjectBody() {
        W8 f = fixture();
        f.start(b -> b);
        for (String body : new String[] {"{\"username\":", "[1]", "\"alice\""}) {
            ClientResponse r = f.post("/auth/login", body);
            assertEquals(body + ": " + r.text(), 400, r.status());
            assertEquals("BAD_REQUEST", W8.code(r));
            assertEquals("the request body must be a JSON object", message(r));
        }
    }

    // OPS-AUTH-014 AC-2: a POST-only auth endpoint called with another method and no credential names POST
    @Test
    public void OPS_AUTH_014_AC2_wrongMethodOnAnAuthEndpointNamesPost() {
        W8 f = fixture();
        f.start(b -> b);
        for (String path : new String[] {"/auth/login", "/auth/logout", "/auth/refresh", "/auth/password"}) {
            ClientResponse r = f.get(path);
            assertEquals(path + ": " + r.text(), 401, r.status());
            assertEquals("unauthenticated", W8.code(r));
            assertTrue(r.text(), message(r).contains("POST " + path));
        }
        assertEquals("authentication is required", message(f.get("/api/nope")));
    }

    // OPS-AUTH-014 AC-3: the insecure_transport refusal points to the server log
    @Test
    public void OPS_AUTH_014_AC3_insecureTransportPointsToTheLog() {
        W8 f = fixture();
        f.start("192.168.0.20", false, b -> b);
        ClientResponse r = f.post("/auth/login", W8.login("alice", W8.PW));
        assertEquals(r.text(), 403, r.status());
        assertEquals("insecure_transport", W8.code(r));
        assertTrue(r.text(), message(r).contains("server log"));
    }

    // OPS-AUTH-014 AC-4: the appended error layer logs one ERROR line for auth.user(req) on a public path
    @Test
    public void OPS_AUTH_014_AC4_appendedLayerLogsOnce() {
        W8 f = fixture();
        f.errors = null;
        f.routes = w -> w.app.get("/pub", (req, res) -> res.send(w.auth.user(req).getId()));
        f.start(b -> b.permit("/pub"));
        int before = RecordingLogger.matching(f.logger.lines, "(?s)ERROR .*").size();
        ClientResponse r = f.get("/pub");
        assertEquals(r.text(), 500, r.status());
        assertEquals("INTERNAL_ERROR", W8.code(r));
        List<String> errors = RecordingLogger.matching(f.logger.lines, "(?s)ERROR .*");
        assertEquals(f.logger.lines.toString(), before + 1, errors.size());
    }
}
