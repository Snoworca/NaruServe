package com.snoworca.naruserve;

import static org.junit.Assert.*;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class CookieConvenienceTest {
    private static List<String> cookies(ClientResponse response) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> h : response.headers()) {
            if (h.getKey().equalsIgnoreCase("Set-Cookie")) values.add(h.getValue());
        }
        return values;
    }

    // FR-DX-005 AC-1 AC-3: setters stay JavaBeans; the separate options support fluent configuration.
    @Test
    public void defaultAndConfiguredCookiesAppendSeparateHeaders() {
        TestApp app = TestApp.create();
        app.app().get("/cookies", (req, res) -> {
            assertSame(res, res.cookie("session", "token"));
            assertSame(res, res.cookie("theme", "dark", c -> c.path("/app").domain("example.com")
                    .maxAge(3600).secure(true).httpOnly(true).sameSite(SameSite.LAX)));
            res.end();
        });
        try {
            List<String> values = cookies(app.send("GET", "/cookies"));
            assertEquals(2, values.size());
            assertEquals("session=token; Path=/", values.get(0));
            assertEquals("theme=dark; Path=/app; Domain=example.com; Max-Age=3600; Secure; HttpOnly; SameSite=Lax", values.get(1));
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-005 AC-2: callbacks cannot accidentally undo cookie deletion with a positive maxAge.
    @Test
    public void clearCookieForcesExpiryAndPreservesScope() {
        TestApp app = TestApp.create();
        app.app().get("/clear", (req, res) -> {
            assertSame(res, res.clearCookie("session"));
            assertSame(res, res.clearCookie("other", c -> c.path("/app").domain("example.com")
                    .maxAge(3600).httpOnly(true).secure(true).sameSite(SameSite.STRICT)));
            res.end();
        });
        try {
            List<String> values = cookies(app.send("GET", "/clear"));
            assertEquals("session=; Path=/; Max-Age=0", values.get(0));
            assertEquals("other=; Path=/app; Domain=example.com; Max-Age=0; Secure; HttpOnly; SameSite=Strict", values.get(1));
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-005 AC-3: rejected values or a failed callback never append a partial header.
    @Test
    public void validationAndCallbackFailuresDoNotMutateResponse() {
        TestApp app = TestApp.create();
        app.app().get("/invalid", (req, res) -> {
            res.cookie("valid", "first");
            assertThrows(IllegalArgumentException.class, () -> res.cookie("bad name", "x"));
            assertThrows(IllegalArgumentException.class, () -> res.cookie("bad", "a b"));
            assertThrows(IllegalArgumentException.class, () -> res.cookie("bad", "x", c -> c.path("/;injected")));
            assertThrows(IllegalArgumentException.class, () -> res.clearCookie("bad", c -> c.domain("x\r\nInjected: yes")));
            assertThrows(NullPointerException.class, () -> res.cookie("bad", "x", null));
            assertThrows(NullPointerException.class, () -> res.clearCookie("bad", null));
            assertThrows(IllegalStateException.class, () -> res.cookie("bad", "x", c -> {
                c.path("/app");
                throw new IllegalStateException("callback failed");
            }));
            assertFalse(res.isCommitted());
            res.send("ok");
        });
        try {
            ClientResponse r = app.send("GET", "/invalid");
            assertEquals("ok", r.text());
            assertEquals(java.util.Collections.singletonList("valid=first; Path=/"), cookies(r));
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-005 AC-3: existing committed-response guard remains effective.
    @Test
    public void completedResponseRejectsCookieConveniences() {
        TestApp app = TestApp.create();
        app.app().get("/done", (req, res) -> {
            res.end();
            assertThrows(IllegalStateException.class, () -> res.cookie("x", "y"));
            assertThrows(IllegalStateException.class, () -> res.clearCookie("x"));
        });
        try { assertTrue(cookies(app.send("GET", "/done")).isEmpty()); }
        finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }
}
