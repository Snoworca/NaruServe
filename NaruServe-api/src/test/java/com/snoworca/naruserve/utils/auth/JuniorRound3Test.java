package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/** The silent mistakes of the third junior study are named at startup (OPS-AUTH-010). */
public class JuniorRound3Test {

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

    private static List<String> permitWarns(W8 f) {
        return RecordingLogger.matching(f.logger.lines, "(?s)WARN auth doctor WARN permit method.*");
    }

    // OPS-AUTH-010 AC-1: a route whose method the exact-path .permit rule does not cover gives one WARN naming the fix;
    // a covered method, another .permit rule for the method and a path without a .permit rule give none
    @Test
    public void OPS_AUTH_010_AC1_uncoveredPermitMethodIsWarned() {
        W8 f = fixture();
        f.routes = w -> {
            w.app.post("/health", (req, res) -> res.send("posted"));
            w.app.post("/hook", (req, res) -> res.send("hook"));
            w.app.post("/api/x", (req, res) -> res.send("x"));
        };
        f.start(b -> b.permit("/health").permit("/hook").permit(Method.POST, "/hook"));
        List<String> warns = permitWarns(f);
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        String w = warns.get(0);
        assertTrue(w, w.contains("POST /health") && w.contains("GET,HEAD")
                && w.contains(".permit(Method.POST, \"/health\")"));
    }

    // OPS-AUTH-010 AC-1: a .permitPrefix that makes the route public suppresses the WARN
    @Test
    public void OPS_AUTH_010_AC1_prefixPublicRouteIsNotWarned() {
        W8 f = fixture();
        f.routes = w -> w.app.post("/pub/form", (req, res) -> res.send("form"));
        f.start(b -> b.permit("/pub/form").permitPrefix("/pub"));
        assertEquals(f.logger.lines.toString(), 0, permitWarns(f).size());
    }

    // OPS-AUTH-010 AC-2: the errors placement WARN says what the missing auth error layer looks like at runtime
    @Test
    public void OPS_AUTH_010_AC2_errorsPlacementNamesTheHtmlStackTrace() {
        RecordingLogger log = new RecordingLogger();
        Auth auth = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/api/x", (req, res) -> res.send("x"));
        t.start();
        {
            List<String> warns = RecordingLogger.matching(log.lines, "(?s)WARN auth doctor WARN errors placement.*");
            assertEquals(log.lines.toString(), 1, warns.size());
            assertTrue(warns.get(0), warns.get(0).contains("HTML") && warns.get(0).contains("stack trace")
                && warns.get(0).contains("appends auth.errorMiddleware()"));
        }
    }
}
