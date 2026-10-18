package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class RouterPolicyTest {

    private static Router.@Nullable Route<String> find(RouterPolicy policy, String pattern, String path) {
        Router<String> r = new Router<>(policy);
        r.add(Method.GET, pattern, pattern);
        r.freeze();
        return r.find(Method.GET, Router.decodeSegments(path, policy));
    }

    // SDS-AC-9; FR-ROUTE-006 AC-3
    @Test
    public void sdsAc9_FR_ROUTE_006_AC3_caseInsensitiveLiteralsKeepParamAsReceived() {
        RouterPolicy ci = RouterPolicy.builder().caseInsensitive(true).build();
        Router<String> r = new Router<>(ci);
        r.add(Method.GET, "/users/:name", "h");
        r.freeze();
        Router.Route<String> route = r.find(Method.GET, Router.decodeSegments("/USERS/Bob", ci));
        assertNotNull(route);
        assertEquals("Bob", route.pattern.match(Router.decodeSegments("/USERS/Bob", ci), ci).get("name"));
        assertNull(find(RouterPolicy.strict(), "/users/:name", "/USERS/Bob"));
    }

    // SDS-AC-9; FR-ROUTE-006 AC-3
    @Test
    public void sdsAc9_FR_ROUTE_006_AC3_turkishDefaultLocaleDoesNotChangeComparison() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            RouterPolicy ci = RouterPolicy.builder().caseInsensitive(true).build();
            assertNotNull(find(ci, "/list", "/LIST"));
            assertNotNull(find(ci, "/LIST", "/list"));
            assertEquals(RoutePattern.parse("/LIST").conflictKey(ci), RoutePattern.parse("/list").conflictKey(ci));
        } finally {
            Locale.setDefault(saved);
        }
    }

    // SDS-AC-10; FR-ROUTE-006 AC-1
    @Test
    public void sdsAc10_FR_ROUTE_006_AC1_mergeSlashesAlone() {
        RouterPolicy merge = RouterPolicy.builder().mergeSlashes(true).build();
        assertTrue(merge.mergeSlashes());
        assertFalse(merge.ignoreTrailingSlash());
        assertFalse(merge.caseInsensitive());
        assertNotNull(find(merge, "/a/b", "/a//b"));
        assertNull(find(RouterPolicy.strict(), "/a/b", "/a//b"));
    }

    // SDS-AC-10; FR-ROUTE-006 AC-2
    @Test
    public void sdsAc10_FR_ROUTE_006_AC2_ignoreTrailingSlashAloneAndWithMerge() {
        RouterPolicy trailing = RouterPolicy.builder().ignoreTrailingSlash(true).build();
        assertTrue(trailing.ignoreTrailingSlash());
        assertFalse(trailing.mergeSlashes());
        assertNotNull(find(trailing, "/users", "/users/"));
        assertNotNull(find(trailing, "/users/", "/users"));
        assertNull(find(trailing, "/users", "/users//"));
        RouterPolicy both = RouterPolicy.builder().ignoreTrailingSlash(true).mergeSlashes(true).build();
        assertNotNull(find(both, "/users", "/users//"));
        assertEquals(Collections.singletonList(""), Router.decodeSegments("/", trailing));
        assertEquals(Collections.singletonList(""), Router.decodeSegments("//", both));
        assertNotNull(find(trailing, "/", "/"));
        assertNotNull(find(both, "/", "//"));
    }

    // SDS-AC-10; FR-ROUTE-006 AC-4
    @Test
    public void sdsAc10_FR_ROUTE_006_AC4_strictEqualsUnsetBuilderAndBuiltPolicyIsFixed() {
        for (RouterPolicy p : new RouterPolicy[] {RouterPolicy.strict(), RouterPolicy.builder().build()}) {
            assertFalse(p.mergeSlashes());
            assertFalse(p.ignoreTrailingSlash());
            assertFalse(p.caseInsensitive());
            assertFalse(p.enableTrace());
            assertFalse(p.enableConnect());
            assertNull(find(p, "/users", "/Users"));
            assertNull(find(p, "/users", "/users/"));
            assertNull(find(p, "/a/b", "/a//b"));
        }
        RouterPolicy.Builder b = RouterPolicy.builder();
        RouterPolicy first = b.build();
        b.caseInsensitive(true);
        assertFalse("a built policy must not follow later builder calls", first.caseInsensitive());
        assertTrue(b.build().caseInsensitive());
    }

    // SDS-AC-10; FR-ROUTE-006 AC-4
    @Test
    public void sdsAc10_FR_ROUTE_006_AC4_policyAndRouterOfferNoSetter() {
        for (Class<?> c : new Class<?>[] {RouterPolicy.class, Router.class}) {
            assertTrue(Modifier.isFinal(c.getModifiers()));
            for (java.lang.reflect.Method m : c.getMethods()) {
                assertFalse(c + "." + m.getName(), m.getName().startsWith("set"));
            }
        }
        for (Field f : RouterPolicy.class.getDeclaredFields()) {
            assertTrue(f.getName(), Modifier.isFinal(f.getModifiers()));
        }
        for (Field f : Router.class.getDeclaredFields()) {
            if (f.getType() == RouterPolicy.class) {
                assertTrue(f.getName(), Modifier.isFinal(f.getModifiers()));
            }
        }
        for (java.lang.reflect.Method m : RouterPolicy.class.getMethods()) {
            if (m.getDeclaringClass() == RouterPolicy.class && !Modifier.isStatic(m.getModifiers())) {
                assertEquals(m.getName(), 0, m.getParameterTypes().length);
            }
        }
    }

    // FR-ROUTE-001 AC-3
    @Test
    public void FR_ROUTE_001_AC3_anyUnchangedOnRouterWithTraceAndConnectEnabled() {
        RouterPolicy enabled = RouterPolicy.builder().enableTrace(true).enableConnect(true).build();
        Router<String> r = new Router<>(enabled);
        r.add(Method.any(), "/x", "any");
        r.add(Method.of(Method.TRACE), "/t", "trace");
        r.freeze();
        assertEquals(Method.of(Method.GET, Method.HEAD, Method.POST, Method.PUT, Method.PATCH, Method.DELETE,
                Method.OPTIONS), Method.any());
        assertNull(r.find(Method.TRACE, Router.decodeSegments("/x", enabled)));
        assertNull(r.find(Method.CONNECT, Router.decodeSegments("/x", enabled)));
        assertEquals("trace", r.find(Method.TRACE, Router.decodeSegments("/t", enabled)).handler);
    }
}
