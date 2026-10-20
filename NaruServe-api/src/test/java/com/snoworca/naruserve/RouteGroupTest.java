package com.snoworca.naruserve;

import static org.junit.Assert.*;

import com.snoworca.naruserve.routing.RoutingConfigException;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** End-to-end group contracts. FR-DX-009 AC-1 through AC-4. */
public class RouteGroupTest {
    // FR-DX-009 AC-1, AC-2, AC-3
    @Test public void nestedRoutesInheritAllGroupMiddlewareWithoutLeakingToSiblings() {
        TestApp t = TestApp.create();
        List<String> calls = new ArrayList<>();
        t.app().use((req, res, next) -> { calls.add("app"); next.run(); });
        t.app().group("/api/", api -> {
            api.get("/first", (req, res) -> res.send("first"));
            api.group("/v1/", v1 -> {
                v1.route("/users").use((req, res, next) -> {
                    calls.add("route"); next.run();
                }).get((req, res) -> res.send("users"));
                v1.use((req, res, next) -> { calls.add("nested"); next.run(); });
            });
            api.use((req, res, next) -> { calls.add("auth"); next.run(); });
        });
        t.app().get("/api/outside", (req, res) -> res.send("outside"));
        assertEquals("users", t.send("GET", "/api/v1/users").text());
        assertEquals(Arrays.asList("app", "auth", "nested", "route"), calls);
        calls.clear();
        assertEquals("first", t.send("GET", "/api/first").text());
        assertEquals(Arrays.asList("app", "auth"), calls);
        calls.clear();
        assertEquals("outside", t.send("GET", "/api/outside").text());
        assertEquals(Arrays.asList("app"), calls);
    }

    // FR-DX-009 AC-1, AC-3
    @Test public void sevenVerbsAndGroupRootRegisterNormally() {
        TestApp t = TestApp.create();
        t.app().group("/api", api -> {
            api.get("/", (req, res) -> res.send("root"));
            api.get("/verbs", (req, res) -> res.send("GET"));
            api.post("/verbs", (req, res) -> res.send("POST"));
            api.put("/verbs", (req, res) -> res.send("PUT"));
            api.patch("/verbs", (req, res) -> res.send("PATCH"));
            api.delete("/verbs", (req, res) -> res.send("DELETE"));
            api.head("/verbs", (req, res) -> res.header("X-Verb", "HEAD").end());
            api.options("/verbs", (req, res) -> res.send("OPTIONS"));
        });
        assertEquals("root", t.send("GET", "/api").text());
        for (String verb : new String[]{"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}) {
            assertEquals(verb, t.send(verb, "/api/verbs").text());
        }
        assertEquals("HEAD", t.send("HEAD", "/api/verbs").header("X-Verb"));
    }

    // FR-DX-009 AC-1 — unsafe joins must never register routes outside the group.
    @Test public void unsafePathsAreRejectedAndFailedConfigurationRegistersNothing() {
        for (String path : new String[]{"../escape", "/../escape", "/%2e%2e/escape", "/x?y", "/x#y", "/x\\y"}) {
            TestApp t = TestApp.create();
            assertThrows(IllegalArgumentException.class, () -> t.app().group("/api", api ->
                api.get(path, (req, res) -> res.send("bad"))));
            assertEquals(404, t.send("GET", "/escape").status());
        }
        TestApp t = TestApp.create();
        assertThrows(IllegalStateException.class, () -> t.app().group("/api", api -> {
            api.get("/partial", (req, res) -> res.send("partial"));
            throw new IllegalStateException("configuration failed");
        }));
        assertEquals(404, t.send("GET", "/api/partial").status());
    }

    // FR-DX-009 AC-4
    @Test public void retainedGroupsAndBuildersCannotMutateAfterConfigurationOrListen() {
        TestApp t = TestApp.create();
        AtomicReference<RouteGroup> group = new AtomicReference<>();
        AtomicReference<RouteBuilder> builder = new AtomicReference<>();
        t.app().group("/api", api -> {
            group.set(api);
            builder.set(api.route("/x"));
            builder.get().get((req, res) -> res.send("x"));
        });
        assertThrows(IllegalStateException.class, () -> group.get().get("/late", (req, res) -> res.end()));
        assertThrows(IllegalStateException.class, () -> builder.get().post((req, res) -> res.end()));
        t.start();
        boolean[] called = {false};
        assertThrows(IllegalStateException.class, () -> t.app().group("/late", api -> called[0] = true));
        assertFalse(called[0]);
        assertThrows(IllegalStateException.class, () -> group.get().use((req, res, next) -> next.run()));
    }

    // FR-DX-009 AC-1 — existing router duplicate detection remains authoritative.
    @Test public void groupsDoNotHideDuplicateRoutes() {
        TestApp t = TestApp.create();
        t.app().get("/api/x", (req, res) -> res.end());
        t.app().group("/api", api -> api.get("/x", (req, res) -> res.end()));
        assertThrows(RoutingConfigException.class, t::start);
    }
}
