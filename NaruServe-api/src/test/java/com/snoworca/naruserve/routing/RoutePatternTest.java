package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

public class RoutePatternTest {

    private static void assertRejectedAtAdd(String pattern) {
        Router<String> r = new Router<>(RouterPolicy.strict());
        try {
            r.add(Method.GET, pattern, "h");
            fail("add accepted " + pattern);
        } catch (RoutingConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(pattern));
        }
    }

    // SDS-AC-5; FR-ROUTE-003 AC-2
    @Test
    public void sdsAc5_FR_ROUTE_003_AC2_wildcardAndRegexSyntaxRejectedAtAdd() {
        for (String p : new String[] {"/a?", "/a/*", "/a+", "/a/(b)", "/a)", "/:id(\\d+)"}) {
            assertRejectedAtAdd(p);
        }
    }

    // SDS-AC-5; FR-ROUTE-003 AC-3
    @Test
    public void sdsAc5_FR_ROUTE_003_AC3_emptyOrRepeatedVariableNameRejectedAtAdd() {
        assertRejectedAtAdd("/a/:");
        assertRejectedAtAdd("/a/:id/:id");
    }

    // SDS-AC-5; FR-ROUTE-003 AC-4
    @Test
    public void sdsAc5_FR_ROUTE_003_AC4_emptyInteriorSegmentRejectedRootAndTrailingAccepted() {
        assertRejectedAtAdd("/a//b");
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/", "root");
        r.add(Method.GET, "/users/", "trailing");
        r.freeze();
    }

    // w2-P2-001; FR-ROUTE-003 AC-1; FR-ROUTE-003 AC-3
    @Test
    public void w2P2_001_FR_ROUTE_003_AC1_AC3_variableNameMustBeIdentifierFillingTheSegment() {
        for (String p : new String[] {"/:id.json", "/:a-b", "/:from-:to", "/a/:1st", "/:na me", "/:caf\u00e9", "/:a:b"}) {
            assertRejectedAtAdd(p);
        }
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/:_id/:userId2/:A_b", "ok");
        r.freeze();
    }

    // w2-P2-002; FR-ROUTE-003 AC-1; FR-ROUTE-004 AC-1
    @Test
    public void w2P2_002_FR_ROUTE_003_AC1_FR_ROUTE_004_AC1_unmatchableLiteralRejectedAtAdd() {
        for (String p : new String[] {"/a b", "/a#b", "/a\u0001", "/a\u007f", "/a%20b", "/a%2Fb", "/a\tb", "/a/.",
            "/a/../b"}) {
            assertRejectedAtAdd(p);
        }
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/caf\u00e9/.hidden/a~b", "ok");
        r.freeze();
    }

    // SDS-AC-5; FR-ROUTE-003 AC-3
    @Test
    public void sdsAc5_FR_ROUTE_003_AC3_parseNamesThePattern() {
        try {
            RoutePattern.parse("/a/:id/:id");
            fail();
        } catch (RoutingConfigException e) {
            assertTrue(e.getMessage().contains("/a/:id/:id"));
        }
    }

    // FR-ROUTE-003 AC-1
    @Test
    public void FR_ROUTE_003_AC1_variableMatchesExactlyOneNonEmptySegment() {
        RoutePattern p = RoutePattern.parse("/users/:id");
        RouterPolicy s = RouterPolicy.strict();
        Map<String, String> m = p.match(Arrays.asList("users", "42"), s);
        assertNotNull(m);
        assertEquals(Collections.singletonMap("id", "42"), m);
        assertNull(p.match(Collections.singletonList("users"), s));
        assertNull(p.match(Arrays.asList("users", ""), s));
        assertNull(p.match(Arrays.asList("users", "42", "posts"), s));
    }

    // FR-ROUTE-005 AC-3
    @Test(expected = UnsupportedOperationException.class)
    public void FR_ROUTE_005_AC3_paramsAreUnmodifiable() {
        RoutePattern.parse("/u/:id").match(Arrays.asList("u", "1"), RouterPolicy.strict()).put("x", "y");
    }
}
