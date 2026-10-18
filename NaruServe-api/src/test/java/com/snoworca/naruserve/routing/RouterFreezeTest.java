package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Method;
import org.junit.Test;

public class RouterFreezeTest {

    private static String freezeError(Router<String> r) {
        try {
            r.freeze();
        } catch (RoutingConfigException e) {
            return e.getMessage();
        }
        fail("freeze passed");
        return "";
    }

    // SDS-AC-12; FR-ROUTE-008 AC-1
    @Test
    public void sdsAc12_FR_ROUTE_008_AC1_duplicateGetAndMethodSetOverlap() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/a", "1");
        r.add(Method.GET, "/a", "2");
        assertEquals("GET /a conflicts with GET /a", freezeError(r));

        Router<String> s = new Router<>(RouterPolicy.strict());
        s.add(Method.GET, "/a", "1");
        s.add(Method.get().post(), "/a", "2");
        assertEquals("GET /a conflicts with GET /a", freezeError(s));
    }

    // SDS-AC-12; FR-ROUTE-008 AC-2; FR-ROUTE-008 AC-3
    @Test
    public void sdsAc12_FR_ROUTE_008_AC2_AC3_variableNamesOnlyDifferAndOneLinePerPair() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/a/:x", "1");
        r.add(Method.GET, "/a/:y", "2");
        r.add(Method.PUT, "/b", "3");
        r.add(Method.PUT, "/b", "4");
        assertEquals("GET /a/:x conflicts with GET /a/:y\nPUT /b conflicts with PUT /b", freezeError(r));

        Router<String> ok = new Router<>(RouterPolicy.strict());
        ok.add(Method.GET, "/a/:x", "1");
        ok.add(Method.POST, "/a/:y", "2");
        ok.freeze();
    }

    // SDS-AC-12; FR-ROUTE-008 AC-3
    @Test
    public void sdsAc12_FR_ROUTE_008_AC3_registrationDoesNotRaiseBeforeFreeze() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/a", "1");
        r.add(Method.GET, "/a", "2");
        r.add(Method.TRACE, "/t", "3");
        assertEquals("GET /a conflicts with GET /a\nTRACE /t registered while TRACE routing is disabled",
                freezeError(r));
    }

    // SDS-AC-12; FR-ROUTE-008 AC-3
    @Test
    public void sdsAc12_FR_ROUTE_008_AC3_isDistinctFromIllegalStateAndArgument() {
        Throwable e = new RoutingConfigException("x");
        assertFalse(e instanceof IllegalStateException);
        assertFalse(e instanceof IllegalArgumentException);
    }

    // SDS-AC-13; FR-ROUTE-008 AC-4
    @Test
    public void sdsAc13_FR_ROUTE_008_AC4_conflictsOnlyUnderTheirPolicies() {
        RouterPolicy ci = RouterPolicy.builder().caseInsensitive(true).build();
        RouterPolicy trailing = RouterPolicy.builder().ignoreTrailingSlash(true).build();

        Router<String> c = new Router<>(ci);
        c.add(Method.GET, "/Users", "1");
        c.add(Method.GET, "/users", "2");
        assertEquals("GET /Users conflicts with GET /users", freezeError(c));

        Router<String> t = new Router<>(trailing);
        t.add(Method.GET, "/a", "1");
        t.add(Method.GET, "/a/", "2");
        assertEquals("GET /a conflicts with GET /a/", freezeError(t));

        Router<String> s = new Router<>(RouterPolicy.strict());
        s.add(Method.GET, "/Users", "1");
        s.add(Method.GET, "/users", "2");
        s.add(Method.GET, "/a", "3");
        s.add(Method.GET, "/a/", "4");
        s.freeze();

        assertEquals(RoutePattern.parse("/A/:x/").conflictKey(trailing), RoutePattern.parse("/A/:y").conflictKey(trailing));
        assertNotEquals(RoutePattern.parse("/A/:x/").conflictKey(RouterPolicy.strict()),
                RoutePattern.parse("/A/:y").conflictKey(RouterPolicy.strict()));
        assertNotEquals(RoutePattern.parse("/").conflictKey(trailing), RoutePattern.parse("/a").conflictKey(trailing));
    }

    // SDS-AC-13; FR-ROUTE-008 AC-4
    @Test
    public void sdsAc13_FR_ROUTE_008_AC4_conflictKeyFoldsCaseLikeMatching() {
        RouterPolicy ci = RouterPolicy.builder().caseInsensitive(true).build();
        Router<String> r = new Router<>(ci);
        r.add(Method.GET, "/ſ", "1");
        r.add(Method.GET, "/s", "2");
        assertEquals("GET /ſ conflicts with GET /s", freezeError(r));
    }

    // FR-ROUTE-008 AC-1
    @Test
    public void FR_ROUTE_008_AC1_registrationAfterFreezeCannotBypassConflictDetection() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/a", "1");
        r.freeze();
        try {
            r.add(Method.GET, "/a", "2");
            fail("add after freeze");
        } catch (IllegalStateException expected) {
            // the table is fixed after freeze
        }
        try {
            r.addWebSocket("/w", "3");
            fail("addWebSocket after freeze");
        } catch (IllegalStateException expected) {
            // the table is fixed after freeze
        }
    }

    // SDS-AC-14; FR-ROUTE-008 AC-5
    @Test
    public void sdsAc14_FR_ROUTE_008_AC5_traceAndConnectWhileDisabledFailFreeze() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.TRACE, "/t", "1");
        r.add(Method.of(Method.CONNECT), "/c", "2");
        assertEquals("TRACE /t registered while TRACE routing is disabled\n"
                + "CONNECT /c registered while CONNECT routing is disabled", freezeError(r));

        Router<String> onlyTrace = new Router<>(RouterPolicy.builder().enableTrace(true).build());
        onlyTrace.add(Method.TRACE, "/t", "1");
        onlyTrace.add(Method.CONNECT, "/c", "2");
        assertEquals("CONNECT /c registered while CONNECT routing is disabled", freezeError(onlyTrace));
    }

    // SDS-AC-14; FR-ROUTE-008 AC-5
    @Test
    public void sdsAc14_FR_ROUTE_008_AC5_enabledTraceAndConnectFreeze() {
        Router<String> r = new Router<>(RouterPolicy.builder().enableTrace(true).enableConnect(true).build());
        r.add(Method.TRACE, "/t", "1");
        r.add(Method.CONNECT, "/c", "2");
        r.freeze();
    }
}
