package com.snoworca.naruserve;

import static org.junit.Assert.*;

import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.routing.RoutingConfigException;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.routing.RouteMatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
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
    @Test public void legacyWebSocketPrefixAndRouteShareDecodedUnicodePolicyAndOriginalCapture() throws Exception {
        // CON-APP-010 AC-1 AC-2 AC-5; FR-APP-021 AC-6: preserve the old decoded profile on real WS dispatch.
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp app = TestApp.create(adapter);
        AtomicInteger checks = new AtomicInteger();
        ArrayBlockingQueue<String> captures = new ArrayBlockingQueue<>(2);
        app.app().routerPolicy(RouterPolicy.builder().caseInsensitive(true).build());
        app.app().use("/" + UPPER, (req, res, next) -> { checks.incrementAndGet(); next.run(); });
        app.app().ws("/" + UPPER + "/:name", (req, ws) -> captures.add(req.param("name")));
        app.start();
        try {
            for (String[] input : new String[][]{{RAW_LOWER, LOWER}, {RAW_UPPER, UPPER}}) {
                Future<InMemoryHttpAdapter.ClientResponse> response = adapter.submit(InMemoryHttpAdapter.ClientRequest.upgrade("/" + input[0] + "/" + input[0]));
                InMemoryWebSocketChannel channel = adapter.nextChannel(5, TimeUnit.SECONDS);
                assertEquals(input[1], captures.poll(5, TimeUnit.SECONDS));
                channel.clientClose(1000, "");
                assertEquals(101, response.get(5, TimeUnit.SECONDS).status());
            }
            assertEquals(2, checks.get());
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-ROUTE-006 AC-4; FR-ROUTE-008 AC-4: strict mode keeps the two independent routes.
    @Test public void strictLegacyDecodedUnicodeRoutesRemainDistinct() {
        // CON-APP-010 AC-1 AC-5; FR-ROUTE-006 AC-4; FR-ROUTE-008 AC-4: direct legacy matching remains decoded/strict.
        Router<String> legacy = new Router<>(RouterPolicy.strict());
        legacy.add(Method.GET, "/" + UPPER, "upper");
        legacy.add(Method.GET, "/" + LOWER, "lower");
        legacy.freeze();
        RouteMatch<String> upper = legacy.lookup("GET", "/" + RAW_UPPER, false);
        RouteMatch<String> lower = legacy.lookup("GET", "/" + RAW_LOWER, false);
        assertEquals(RouteMatch.Kind.MATCHED, upper.kind());
        assertEquals(RouteMatch.Kind.MATCHED, lower.kind());
        assertEquals("upper", upper.handler());
        assertEquals("lower", lower.handler());
    }
    // CON-APP-010 AC-1 AC-2 AC-5; FR-HTTP-027 AC-2 AC-3; FR-APP-021 AC-5
    @Test public void commonHttpMatchesRawUnicodeAndPercentLiteralsButDecodesOnlyCaptures() {
        TestApp app = TestApp.create();
        app.app().routerPolicy(RouterPolicy.strict()); // Explicit case sensitivity retains distinct literal registrations.
        AtomicInteger checks = new AtomicInteger();
        app.app().use("/" + UPPER, (q, r, n) -> { checks.incrementAndGet(); n.run(); });
        app.app().get("/" + UPPER + "/:name", (q, r) -> r.send(q.param("name")));
        app.app().get("/" + LOWER, (q, r) -> r.send("lower literal"));
        app.app().get("/encoded/" + RAW_UPPER, (q, r) -> r.send("encoded literal"));
        try {
            assertEquals(LOWER, app.send("GET", "/" + UPPER + "/" + RAW_LOWER).text());
            assertEquals(1, checks.get());
            assertEquals("lower literal", app.send("GET", "/" + LOWER).text());
            assertEquals("encoded literal", app.send("GET", "/encoded/" + RAW_UPPER).text());
            HttpAcceptanceTest.assertCommon404("GET", "/" + RAW_UPPER + "/" + RAW_LOWER,
                    app.send("GET", "/" + RAW_UPPER + "/" + RAW_LOWER));
            assertEquals("an encoded literal must not run a different decoded authorization prefix", 1, checks.get());
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

}
