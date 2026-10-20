package com.snoworca.naruserve;

import static org.junit.Assert.*;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ResponseBoundaryTest {
    // FR-DX-004 AC-2: direct Location validation must be atomic, without changing the selected response.
    @Test public void rejectedLocationDoesNotChangeStatusOrExistingLocation() {
        TestApp app = TestApp.create();
        app.app().get("/location", (req, res) -> {
            res.status(202).location("/original");
            assertThrows(NullPointerException.class, () -> res.location(null));
            for (String value : new String[] {"", "   ", "/x\r\nInjected: yes", "/x\u0000", "/x\u010A"}) {
                assertThrows(value, IllegalArgumentException.class, () -> res.location(value));
            }
            assertFalse(res.isCommitted());
            res.send("unchanged");
        });
        try {
            ClientResponse response = app.send("GET", "/location");
            assertEquals(202, response.status());
            assertEquals("/original", response.header("Location"));
            assertEquals("unchanged", response.text());
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-004 AC-4: sendStatus retains the status writer's invalid-range guard without partial output.
    @Test public void invalidSendStatusLeavesResponseUncommittedAndUnchanged() {
        TestApp app = TestApp.create();
        app.app().get("/status", (req, res) -> {
            res.status(202).header("X-Original", "kept");
            for (int code : new int[] {Integer.MIN_VALUE, -1, 0, 99, 1000, Integer.MAX_VALUE}) {
                assertThrows(IllegalArgumentException.class, () -> res.sendStatus(code));
                assertFalse(res.isCommitted());
            }
            res.send("unchanged");
        });
        try {
            ClientResponse response = app.send("GET", "/status");
            assertEquals(202, response.status());
            assertEquals("kept", response.header("X-Original"));
            assertEquals("unchanged", response.text());
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-005 AC-1; FR-DX-005 AC-3: nullable scope options omit attributes; retained options cannot rewrite a header.
    @Test public void retainedCookieOptionsCannotMutateAlreadySerializedHeader() {
        TestApp app = TestApp.create();
        AtomicReference<CookieOptions> options = new AtomicReference<>();
        app.app().get("/cookie", (req, res) -> {
            res.cookie("session", "token", c -> {
                options.set(c);
                c.path(null).domain(null).httpOnly(true);
            });
            options.get().path("/changed").domain("example.com").maxAge(999).httpOnly(false);
            res.end();
        });
        try {
            ClientResponse response = app.send("GET", "/cookie");
            assertEquals("session=token; HttpOnly", response.header("Set-Cookie"));
            options.get().path("/after-response");
            assertEquals("session=token; HttpOnly", response.header("Set-Cookie"));
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-005 AC-2; FR-DX-005 AC-3: explicitly omitted Path/Domain stay omitted when expiring a cookie.
    @Test public void clearCookieAllowsExplicitlyOmittedScopeAndStillForcesExpiry() {
        TestApp app = TestApp.create();
        app.app().get("/clear", (req, res) -> res.clearCookie("session", c ->
                c.path(null).domain(null).maxAge(999)).end());
        try { assertEquals("session=; Max-Age=0", app.send("GET", "/clear").header("Set-Cookie")); }
        finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }
}
