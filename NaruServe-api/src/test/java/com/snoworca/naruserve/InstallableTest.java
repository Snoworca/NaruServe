package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** app.use(mw) with an Installable middleware lets it install itself (FR-AUTH-010 AC-1). */
public class InstallableTest {

    /** A middleware that installs itself: one path-less use("", this) plus a route. */
    static final class Plugin implements Middleware, Installable {
        final List<RestApp> installs = new ArrayList<>();

        @Override
        public void install(RestApp app) {
            installs.add(app);
            app.use("", this);
            app.get("/plugin", (req, res) -> res.send("plugin"));
        }

        @Override
        public void handle(Request req, Response res, Next next) {
            res.header("X-Plugin", "on");
            next.run();
        }
    }

    // FR-AUTH-010 AC-1: use(Middleware) with an Installable calls install(app) exactly once, returns the app and adds no
    // MIDDLEWARE record of its own; a plain middleware is registered as before
    @Test
    public void FR_AUTH_010_AC1_useCallsInstallOnce() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        Plugin plugin = new Plugin();
        assertSame(app, app.use(plugin));
        assertEquals(1, plugin.installs.size());
        assertSame(app, plugin.installs.get(0));
        List<String> shape = new ArrayList<>();
        for (Registration r : app.registrations()) {
            shape.add(r.getKind() + " " + r.getPath());
        }
        // only what install itself registered: its use("", this) and its route
        assertEquals(java.util.Arrays.asList("MIDDLEWARE ", "ROUTE /plugin"), shape);
        Middleware plain = (req, res, next) -> next.run();
        app.use(plain);
        Registration last = app.registrations().get(app.registrations().size() - 1);
        assertEquals(RegistrationKind.MIDDLEWARE, last.getKind());
        assertSame(plain, last.getTarget());
        t.start();
        assertEquals("on", t.send("GET", "/plugin").header("X-Plugin"));
    }

    // FR-AUTH-010 AC-7: use(path, Installable) with a real path throws IllegalArgumentException naming app.use(mw) and
    // .protectOnly and registers nothing; use("", mw) and use("/", mw) stay plain middleware registrations
    @Test
    public void FR_AUTH_010_AC7_pathUseOfAnInstallableIsRefused() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        Plugin plugin = new Plugin();
        for (Runnable call : new Runnable[] {() -> app.use("/api", plugin), () -> app.use((Object) "/api", plugin)}) {
            try {
                call.run();
                org.junit.Assert.fail("use(\"/api\", installable) was accepted");
            } catch (IllegalArgumentException e) {
                org.junit.Assert.assertTrue(e.getMessage(), e.getMessage().contains("app.use(mw)"));
                org.junit.Assert.assertTrue(e.getMessage(), e.getMessage().contains(".protectOnly("));
            }
        }
        assertEquals(0, app.registrations().size());
        assertEquals(0, plugin.installs.size());
        app.use("", plugin); // still a plain middleware (Auth.install relies on it)
        assertEquals(1, app.registrations().size());
        assertEquals(0, plugin.installs.size());
        app.use("/", plugin); // OPS-AUTH-033 AC-4 (FR-AUTH-010 AC-7 amended): "/" installs like use(mw)
        assertEquals(1, plugin.installs.size());
        assertEquals(3, app.registrations().size()); // the plain use above, then what install registers (2)
    }
}
