package com.snoworca.naruserve;

import static org.junit.Assert.*;

import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.routing.RoutingConfigException;
import com.snoworca.naruserve.testing.TestApp;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Supplementary Unicode uses the same case policy in route matches, conflicts and middleware. */
public class UnicodeRoutingContractTest {
    private static final String UPPER = "\uD801\uDC00";
    private static final String LOWER = "\uD801\uDC28";
    private static final String RAW_UPPER = "%F0%90%90%80";
    private static final String RAW_LOWER = "%F0%90%90%A8";

    // FR-ROUTE-008 AC-4: supplementary case variants cannot bypass normalized duplicate detection.
    @Test public void supplementaryCaseVariantsConflictAtFreeze() {
        Router<String> router = new Router<>(RouterPolicy.builder().caseInsensitive(true).build());
        router.add(Method.GET, "/" + UPPER, "upper");
        router.add(Method.GET, "/" + LOWER, "lower");
        RoutingConfigException failure = assertThrows(RoutingConfigException.class, router::freeze);
        assertTrue(failure.getMessage(), failure.getMessage().contains(UPPER));
        assertTrue(failure.getMessage(), failure.getMessage().contains(LOWER));
    }

    // FR-ROUTE-006 AC-3; FR-APP-003 AC-3: prefix authorization and routes must share the same case comparison.
    @Test public void supplementaryRouteAndPrefixMatchAndPreserveOriginalParam() {
        TestApp app = TestApp.create();
        AtomicInteger checks = new AtomicInteger();
        app.app().routerPolicy(RouterPolicy.builder().caseInsensitive(true).build());
        app.app().use("/" + UPPER, (req, res, next) -> { checks.incrementAndGet(); next.run(); });
        app.app().get("/" + UPPER + "/:name", (req, res) -> res.send(req.param("name")));
        try {
            assertEquals(LOWER, app.send("GET", "/" + RAW_LOWER + "/" + RAW_LOWER).text());
            assertEquals(UPPER, app.send("GET", "/" + RAW_UPPER + "/" + RAW_UPPER).text());
            assertEquals(2, checks.get());
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-ROUTE-006 AC-4; FR-ROUTE-008 AC-4: strict mode keeps the two independent routes.
    @Test public void strictRoutesRemainDistinct() {
        TestApp app = TestApp.create();
        app.app().get("/" + UPPER, (req, res) -> res.send("upper"));
        app.app().get("/" + LOWER, (req, res) -> res.send("lower"));
        try {
            assertEquals("upper", app.send("GET", "/" + RAW_UPPER).text());
            assertEquals("lower", app.send("GET", "/" + RAW_LOWER).text());
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }
}
