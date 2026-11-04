package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** SEC-APP-002 AC-6: one Core test fixes validate, watcher, apply, record for one valid registration. */
public class RegistrationOrderTest {

    private static final RouteHandler H = (req, res) -> res.send("h");
    private static final Middleware MW = (req, res, next) -> next.run();

    private static DefaultRestApp app(List<String> steps) {
        DefaultRestApp app = (DefaultRestApp) RestApp.builder().httpAdapter(new InMemoryHttpAdapter())
                .jsonCodec(new StubJsonCodec()).build();
        app.registrationLog().trace(steps::add);
        app.onRegistration(r -> steps.add("watcher"));
        return app;
    }

    // SEC-APP-002 AC-6
    @Test
    public void SEC_APP_002_AC6_oneRegistrationIsValidateWatcherApplyRecord() {
        List<String> steps = Collections.synchronizedList(new ArrayList<String>());
        DefaultRestApp app = app(steps);
        app.get("/x", H);
        assertEquals(Arrays.asList("validate", "watcher", "apply", "record"), steps);
    }

    // SEC-APP-002 AC-6: the same order holds through use, a router mount and a mounted router's later route
    @Test
    public void SEC_APP_002_AC6_sameOrderForUseMountAndMountedRouter() {
        List<String> steps = Collections.synchronizedList(new ArrayList<String>());
        DefaultRestApp app = app(steps);
        app.use("/p", MW);
        assertEquals(Arrays.asList("validate", "watcher", "apply", "record"), steps);
        steps.clear();
        Router r = Router.create();
        app.use("/r", r);
        assertEquals(Arrays.asList("validate", "watcher", "apply", "record"), steps);
        steps.clear();
        r.get("/late", H);
        assertEquals(Arrays.asList("validate", "watcher", "apply", "record"), steps);
    }

    // SEC-APP-002 AC-6: an invalid registration stops at validate
    @Test
    public void SEC_APP_002_AC6_invalidRegistrationStopsBeforeTheWatcher() {
        List<String> steps = Collections.synchronizedList(new ArrayList<String>());
        DefaultRestApp app = app(steps);
        // "validate" is traced once validation succeeded, so an invalid call leaves no step at all
        try {
            app.get("/x", (RouteHandler) null);
            org.junit.Assert.fail("null handler accepted");
        } catch (NullPointerException expected) {
            // validation failure
        }
        assertEquals(Collections.<String>emptyList(), steps);
    }
}
