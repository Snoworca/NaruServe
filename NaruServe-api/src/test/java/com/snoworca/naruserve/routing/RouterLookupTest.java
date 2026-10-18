package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class RouterLookupTest {

    private static Router<String> frozen() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/files/:name", "files");
        r.add(Method.GET, "/a/b", "ab");
        r.freeze();
        return r;
    }

    private static void assertRejected(Router<String> r, String token, String path) {
        try {
            r.lookup(token, path, false);
            fail("accepted " + token + " " + path);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(path));
        }
    }

    // SDS-AC-2; IR-ROUTE-004 AC-4
    @Test
    public void sdsAc2_IR_ROUTE_004_AC4_rejectedPathThrowsBeforeTokenIsRead() {
        Router<String> r = frozen();
        assertRejected(r, "FOO", "/a%zz");
        assertRejected(r, "GET", "/a%zz");
        assertRejected(r, "GET", "/a/..");
        assertRejected(r, "TRACE", "/a/..");
    }

    // SDS-AC-3; IR-ROUTE-004 AC-5
    @Test
    public void sdsAc3_IR_ROUTE_004_AC5_lookupBeforeFreezeThrows() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/a", "a");
        try {
            r.lookup("GET", "/a", false);
            fail("lookup before freeze");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    // SDS-AC-3; IR-ROUTE-004 AC-5
    @Test
    public void sdsAc3_IR_ROUTE_004_AC5_lookupAfterFailedFreezeThrows() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/a", "1");
        r.add(Method.GET, "/a", "2");
        try {
            r.freeze();
            fail("freeze passed");
        } catch (RoutingConfigException expected) {
            // expected
        }
        try {
            r.lookup("GET", "/a", false);
            fail("lookup after failed freeze");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    // SDS-AC-3; IR-ROUTE-004 AC-5
    @Test
    public void sdsAc3_IR_ROUTE_004_AC5_addAfterFreezeThrows() {
        Router<String> r = frozen();
        try {
            r.add(Method.GET, "/z", "z");
            fail("add after freeze");
        } catch (IllegalStateException expected) {
            // expected
        }
        try {
            r.addWebSocket("/z", "z");
            fail("addWebSocket after freeze");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    // SDS-AC-3; IR-ROUTE-004 AC-5
    @Test
    public void sdsAc3_IR_ROUTE_004_AC5_upgradeIgnoredUnlessGet() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.POST, "/w", "post");
        r.addWebSocket("/w", "ws");
        r.freeze();
        RouteMatch<String> post = r.lookup("POST", "/w", true);
        assertEquals(RouteMatch.Kind.MATCHED, post.kind());
        assertEquals("post", post.handler());
        assertEquals(RouteKind.HTTP, post.routeKind());
        assertEquals(RouteMatch.Kind.WEBSOCKET_ONLY, r.lookup("HEAD", "/w", true).kind());
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, r.lookup("PUT", "/w", true).kind());
        assertEquals(RouteMatch.Kind.AUTO_OPTIONS, r.lookup("OPTIONS", "/w", true).kind());
    }

    // SDS-AC-4; IR-ROUTE-004 AC-3
    @Test
    public void sdsAc4_IR_ROUTE_004_AC3_precedenceOnOnePath() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/x", "get");
        r.add(Method.OPTIONS, "/x", "options");
        r.add(Method.HEAD, "/x", "head");
        r.addWebSocket("/x", "ws");
        r.freeze();
        // A route of the request method wins over automatic OPTIONS and automatic HEAD.
        assertEquals("options", r.lookup("OPTIONS", "/x", false).handler());
        RouteMatch<String> head = r.lookup("HEAD", "/x", false);
        assertEquals("head", head.handler());
        assertFalse(head.autoHead());
        assertEquals("get", r.lookup("GET", "/x", false).handler());
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, r.lookup("DELETE", "/x", false).kind());

        Router<String> s = new Router<>(RouterPolicy.strict());
        s.add(Method.GET, "/y", "get");
        s.add(Method.POST, "/y", "post");
        s.addWebSocket("/y", "ws");
        s.freeze();
        // Automatic OPTIONS and automatic HEAD win over WebSocket-only and method mismatch.
        assertEquals(RouteMatch.Kind.AUTO_OPTIONS, s.lookup("OPTIONS", "/y", false).kind());
        RouteMatch<String> autoHead = s.lookup("HEAD", "/y", false);
        assertEquals(RouteMatch.Kind.MATCHED, autoHead.kind());
        assertTrue(autoHead.autoHead());
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, s.lookup("PUT", "/y", false).kind());

        Router<String> t = new Router<>(RouterPolicy.strict());
        t.add(Method.POST, "/z", "post");
        t.addWebSocket("/z", "ws");
        t.freeze();
        // WebSocket-only wins over method mismatch for GET and HEAD.
        assertEquals(RouteMatch.Kind.WEBSOCKET_ONLY, t.lookup("GET", "/z", false).kind());
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, t.lookup("PUT", "/z", false).kind());
    }

    // SDS-AC-4; IR-ROUTE-004 AC-4
    @Test
    public void sdsAc4_IR_ROUTE_004_AC4_rawPathDecodedOnceIntoParams() {
        RouteMatch<String> m = frozen().lookup("GET", "/files/%252e", false);
        assertEquals(RouteMatch.Kind.MATCHED, m.kind());
        assertEquals("files", m.handler());
        assertEquals("%2e", m.params().get("name"));
        assertEquals("files", frozen().lookup("GET", "/files/x?q=%zz", false).handler());
    }


    private static Router<String> router(RouterPolicy policy, String... patterns) {
        Router<String> r = new Router<>(policy);
        for (String p : patterns) {
            r.add(Method.GET, p, p);
        }
        r.freeze();
        return r;
    }

    private static void assertMatched(RouteMatch<String> m, String handler) {
        assertEquals(RouteMatch.Kind.MATCHED, m.kind());
        assertEquals(handler, m.handler());
    }

    // FR-ROUTE-002 AC-1
    @Test
    public void FR_ROUTE_002_AC1_methodSetRegistrationMatchesEachMember() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.get().delete().post(), "/items", "h");
        r.freeze();
        for (String token : new String[] {"GET", "DELETE", "POST"}) {
            RouteMatch<String> m = r.lookup(token, "/items", false);
            assertMatched(m, "h");
            assertEquals(Method.valueOf(token), m.method());
        }
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, r.lookup("PUT", "/items", false).kind());
    }

    // FR-ROUTE-002 AC-2
    @Test
    public void FR_ROUTE_002_AC2_perMethodHandlersWithParams() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/users/:id", "h1");
        r.add(Method.DELETE, "/users/:id", "h2");
        r.freeze();
        RouteMatch<String> get = r.lookup("GET", "/users/1", false);
        assertMatched(get, "h1");
        assertEquals(Collections.singletonMap("id", "1"), get.params());
        RouteMatch<String> del = r.lookup("DELETE", "/users/1", false);
        assertMatched(del, "h2");
        assertEquals(Collections.singletonMap("id", "1"), del.params());
    }

    // FR-ROUTE-006 AC-1
    @Test
    public void FR_ROUTE_006_AC1_mergeSlashesThroughLookup() {
        RouterPolicy merge = RouterPolicy.builder().mergeSlashes(true).build();
        assertMatched(router(merge, "/a/b").lookup("GET", "/a//b", false), "/a/b");
        assertEquals(RouteMatch.Kind.NO_MATCH, router(RouterPolicy.strict(), "/a/b").lookup("GET", "/a//b", false).kind());
    }

    // FR-ROUTE-006 AC-2
    @Test
    public void FR_ROUTE_006_AC2_trailingSlashThroughLookup() {
        RouterPolicy trailing = RouterPolicy.builder().ignoreTrailingSlash(true).build();
        assertMatched(router(trailing, "/users").lookup("GET", "/users/", false), "/users");
        assertMatched(router(trailing, "/users/").lookup("GET", "/users", false), "/users/");
        assertEquals(RouteMatch.Kind.NO_MATCH, router(trailing, "/users").lookup("GET", "/users//", false).kind());
        RouterPolicy both = RouterPolicy.builder().ignoreTrailingSlash(true).mergeSlashes(true).build();
        assertMatched(router(both, "/users").lookup("GET", "/users//", false), "/users");
        assertMatched(router(trailing, "/").lookup("GET", "/", false), "/");
        assertEquals(RouteMatch.Kind.NO_MATCH, router(RouterPolicy.strict(), "/users").lookup("GET", "/users/", false).kind());
    }

    // FR-ROUTE-006 AC-3
    @Test
    public void FR_ROUTE_006_AC3_caseInsensitiveKeepsParamAsReceived() {
        RouterPolicy ci = RouterPolicy.builder().caseInsensitive(true).build();
        RouteMatch<String> m = router(ci, "/users/:name").lookup("GET", "/USERS/Bob", false);
        assertMatched(m, "/users/:name");
        assertEquals(Collections.singletonMap("name", "Bob"), m.params());
        assertEquals(RouteMatch.Kind.NO_MATCH,
                router(RouterPolicy.strict(), "/users/:name").lookup("GET", "/USERS/Bob", false).kind());
    }

    // FR-ROUTE-007 AC-1
    @Test
    public void FR_ROUTE_007_AC1_literalBeatsVariableInEitherOrder() {
        for (Router<String> r : Arrays.asList(router(RouterPolicy.strict(), "/users/:id", "/users/me"),
                router(RouterPolicy.strict(), "/users/me", "/users/:id"))) {
            assertMatched(r.lookup("GET", "/users/me", false), "/users/me");
            RouteMatch<String> id = r.lookup("GET", "/users/42", false);
            assertMatched(id, "/users/:id");
            assertEquals(Collections.singletonMap("id", "42"), id.params());
        }
    }

    // FR-ROUTE-007 AC-2
    @Test
    public void FR_ROUTE_007_AC2_leftmostLiteralWins() {
        RouteMatch<String> m = router(RouterPolicy.strict(), "/a/:x/c", "/a/b/:y").lookup("GET", "/a/b/c", false);
        assertMatched(m, "/a/b/:y");
        assertEquals(Collections.singletonMap("y", "c"), m.params());
    }

    // FR-ROUTE-007 AC-3
    @Test
    public void FR_ROUTE_007_AC3_priorityIsPerMethod() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/users/me", "me");
        r.add(Method.DELETE, "/users/:id", "del");
        r.freeze();
        RouteMatch<String> m = r.lookup("DELETE", "/users/me", false);
        assertMatched(m, "del");
        assertEquals(Method.DELETE, m.method());
        assertEquals(Collections.singletonMap("id", "me"), m.params());
    }

    // FR-ROUTE-005 AC-1; FR-ROUTE-006: the allocation-free probe agrees with match() (review FND-002).
    @Test
    public void FR_ROUTE_005_AC1_matchesAgreesWithMatch() {
        RouterPolicy[] policies = {RouterPolicy.strict(), RouterPolicy.builder().caseInsensitive(true)
                .ignoreTrailingSlash(true).mergeSlashes(true).build()};
        String[] patterns = {"/", "/users", "/users/", "/users/:id", "/a/:x/c"};
        String[] paths = {"/", "/users", "/USERS/", "/users/7", "/users/", "/a/b/c", "/a//c", "//"};
        for (RouterPolicy p : policies) {
            for (String pattern : patterns) {
                RoutePattern rp = RoutePattern.parse(pattern);
                for (String path : paths) {
                    List<String> segs = Router.decodeSegments(path, p);
                    assertEquals(pattern + " vs " + path, rp.match(segs, p) != null, rp.matches(segs, p));
                }
            }
        }
        assertTrue(RoutePattern.parse("/users/:id").matches(Arrays.asList("users", "7"), RouterPolicy.strict()));
        assertFalse(RoutePattern.parse("/users/:id").matches(Arrays.asList("users", ""), RouterPolicy.strict()));
    }
}
