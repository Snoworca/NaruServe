package com.snoworca.naruserve;

import static org.junit.Assert.*;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ResponseHelpersTest {
    // FR-DX-004 AC-1 AC-2: defaults and all explicit redirect statuses terminate with Location and no body.
    @Test
    public void redirectsAndLocationsComposeWithStatus() {
        TestApp app = TestApp.create();
        app.app().get("/default", (req, res) -> res.redirect("/login"));
        for (int code : new int[] {301, 302, 303, 307, 308}) {
            app.app().get("/r" + code, (req, res) -> res.redirect(code, "/next?q=1"));
        }
        app.app().get("/created", (req, res) -> {
            assertSame(res, res.location("/users/7"));
            res.sendStatus(201);
        });
        try {
            ClientResponse first = app.send("GET", "/default");
            assertEquals(302, first.status());
            assertEquals("/login", first.header("Location"));
            for (int code : new int[] {301, 302, 303, 307, 308}) {
                ClientResponse r = app.send("GET", "/r" + code);
                assertEquals(code, r.status());
                assertEquals("/next?q=1", r.header("Location"));
                assertEquals(0, r.body().length);
            }
            ClientResponse created = app.send("GET", "/created");
            assertEquals(201, created.status());
            assertEquals("/users/7", created.header("Location"));
            assertEquals("Created", created.text());
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-004 AC-1 AC-2: invalid redirects must not overwrite a previously selected status or Location.
    @Test
    public void invalidArgumentsLeaveResponseUnmodified() {
        TestApp app = TestApp.create();
        app.app().get("/invalid", (req, res) -> {
            res.status(202).location("/original");
            for (int code : new int[] {0, 200, 300, 304, 305, 306, 999}) {
                assertThrows(IllegalArgumentException.class, () -> res.redirect(code, "/bad"));
            }
            assertThrows(NullPointerException.class, () -> res.redirect(302, null));
            assertThrows(IllegalArgumentException.class, () -> res.redirect(302, ""));
            assertThrows(IllegalArgumentException.class, () -> res.redirect(302, "/x\r\nInjected: yes"));
            assertThrows(IllegalArgumentException.class, () -> res.type("text/plain\r\nInjected: yes"));
            assertThrows(IllegalArgumentException.class, () -> res.type(""));
            assertFalse(res.isCommitted());
            res.send("unchanged");
        });
        try {
            ClientResponse r = app.send("GET", "/invalid");
            assertEquals(202, r.status());
            assertEquals("/original", r.header("Location"));
            assertEquals("text/plain; charset=UTF-8", r.header("Content-Type"));
            assertEquals("unchanged", r.text());
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-004 AC-2: full media types are preserved, extensions use the existing MIME table.
    @Test
    public void mimeTypesAcceptExtensionsAndExplicitValues() {
        TestApp app = TestApp.create();
        String[] inputs = {"png", ".PNG", "json", "unknownextension", "text/plain; charset=UTF-8"};
        String[] expected = {"image/png", "image/png", "application/json", "application/octet-stream", "text/plain; charset=UTF-8"};
        for (int i = 0; i < inputs.length; i++) {
            String type = inputs[i];
            app.app().get("/t" + i, (req, res) -> {
                assertSame(res, res.type(type));
                res.send(new byte[] {1});
            });
        }
        try {
            for (int i = 0; i < inputs.length; i++) {
                assertEquals(expected[i], app.send("GET", "/t" + i).header("Content-Type"));
            }
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-004 AC-3 AC-4: use shared status phrases; HEAD/204/304 never send a body.
    @Test
    public void sendStatusUsesReasonPhrasesAndBodylessRules() {
        TestApp app = TestApp.create();
        for (int code : new int[] {200, 204, 304, 404, 599}) {
            app.app().get("/s" + code, (req, res) -> res.sendStatus(code));
        }
        try {
            assertEquals("OK", app.send("GET", "/s200").text());
            assertEquals("Not Found", app.send("GET", "/s404").text());
            assertEquals("Error", app.send("GET", "/s599").text());
            for (int code : new int[] {204, 304}) {
                ClientResponse r = app.send("GET", "/s" + code);
                assertEquals(code, r.status());
                assertEquals(0, r.body().length);
            }
            assertEquals(0, app.send("HEAD", "/s200").body().length);
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-004 AC-2: helpers retain the existing completion guard.
    @Test
    public void completedResponseRejectsHelpers() {
        TestApp app = TestApp.create();
        app.app().get("/done", (req, res) -> {
            res.end();
            assertThrows(IllegalStateException.class, () -> res.location("/x"));
            assertThrows(IllegalStateException.class, () -> res.type("png"));
            assertThrows(IllegalStateException.class, () -> res.redirect("/x"));
            assertThrows(IllegalStateException.class, () -> res.sendStatus(200));
        });
        try { assertEquals(200, app.send("GET", "/done").status()); }
        finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }
}
