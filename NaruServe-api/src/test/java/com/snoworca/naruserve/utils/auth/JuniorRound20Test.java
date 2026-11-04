package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.testing.AuthTesting;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/** The code findings of the twentieth junior study are answered with the fix (OPS-AUTH-032). */
public class JuniorRound20Test {

    private final List<W8> fixtures = new ArrayList<>();

    @After
    public void stop() {
        for (W8 f : fixtures) {
            f.stop();
        }
    }

    /** A store breaking two independent clauses: changePassword never applies, and list() returns null. */
    private static final class TwoBroken extends ForwardingUserStore {
        TwoBroken() {
            super(new InMemoryUserStore());
        }

        @Override
        public boolean changePassword(String id, @org.jspecify.annotations.Nullable String expectedOldHash,
                long expectedVer, String newHash) {
            return false;
        }

        @Override
        public com.snoworca.naruserve.utils.auth.user.UserPage list(int offset, int limit,
                @org.jspecify.annotations.Nullable String search) {
            return null;
        }
    }

    // OPS-AUTH-032 AC-1: the test kit reports every failed clause in one AssertionError
    @Test
    public void OPS_AUTH_032_AC1_kitReportsEveryClause() {
        try {
            AuthTesting.verifyUserStore(new TwoBroken());
            fail("passed");
        } catch (AssertionError e) {
            String m = e.getMessage();
            assertTrue(m, m.contains("2 clauses failed") && m.contains("(list)") && m.contains("change"));
        }
    }

    // OPS-AUTH-032 AC-2: the DEV default-deny WARN says a route meant to need a login needs no change
    @Test
    public void OPS_AUTH_032_AC2_devWarnDoesNotNudgeToPublic() {
        W8 f = new W8();
        fixtures.add(f);
        f.start(b -> b.mode(AuthMode.DEV));
        assertEquals(401, f.get("/health").status());
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN .*default deny protects.*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("only if it is meant to be public")
                && warns.get(0).contains("a route that should need a login needs no change"));
    }

    // OPS-AUTH-032 AC-3: an AuthRejection with a non-4xx status says what to throw instead
    @Test
    public void OPS_AUTH_032_AC3_nonClientRejectionSaysWhatToDo() {
        try {
            new AuthRejection(500, "oops");
            fail("built");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("AuthRejection status must be 4xx: 500")
                    && e.getMessage().contains("AuthUnavailableException"));
        }
        AuthUser.builder(); // keeps the import used
    }
}
