package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.Method;
import java.util.Arrays;
import java.util.Collections;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class RouterMatchingTest {

    private static final RouterPolicy STRICT = RouterPolicy.strict();

    private static Router<String> router(String... patterns) {
        Router<String> r = new Router<>(STRICT);
        for (String p : patterns) {
            r.add(Method.GET, p, p);
        }
        r.freeze();
        return r;
    }

    private static Router.@Nullable Route<String> find(Router<String> r, String path) {
        return r.find(Method.GET, Router.decodeSegments(path, STRICT));
    }

    // SDS-AC-3; CON-ROUTE-001 AC-4; FR-ROUTE-005 AC-1
    @Test
    public void sdsAc3_CON_ROUTE_001_AC4_FR_ROUTE_005_AC1_queryIsDroppedBeforeDecoding() {
        Router<String> r = router("/users");
        assertEquals("/users", find(r, "/users?id=1").handler);
        assertEquals("/users", find(r, "/users").handler);
        assertEquals(Collections.singletonList("users"), Router.decodeSegments("/users?x=%2F/a?b", STRICT));
        assertEquals(Collections.singletonList("users"), Router.decodeSegments("/users?%zz", STRICT));
    }

    // SDS-AC-3; CON-ROUTE-001 AC-4
    @Test
    public void sdsAc3_CON_ROUTE_001_AC4_absoluteFormIsNotParsed() {
        // The text is split as a path; no scheme or host is stripped.
        assertEquals(Arrays.asList("http:", "", "h", "users"), Router.decodeSegments("http://h/users", STRICT));
        assertNull(find(router("/users"), "http://h/users"));
    }

    // SDS-AC-8; FR-ROUTE-005 AC-3
    @Test
    public void sdsAc8_FR_ROUTE_005_AC3_decodedOnceAsUtf8() {
        assertEquals(Arrays.asList("files", "%2e"), Router.decodeSegments("/files/%252e", STRICT));
        Router<String> r = router("/files/:name", "/café");
        Router.Route<String> files = find(r, "/files/%252e");
        assertNotNull(files);
        assertEquals("%2e", files.pattern.match(Router.decodeSegments("/files/%252e", STRICT), STRICT).get("name"));
        assertEquals("/café", find(r, "/caf%C3%A9").handler);
    }

    // SDS-AC-8; FR-ROUTE-005 AC-3
    @Test
    public void sdsAc8_FR_ROUTE_005_AC3_plusStaysPlus() {
        assertEquals(Collections.singletonList("a+b"), Router.decodeSegments("/a+b", STRICT));
        assertEquals(Collections.singletonList("a b"), Router.decodeSegments("/a%20b", STRICT));
    }

    // SDS-AC-8; FR-ROUTE-005 AC-2
    @Test
    public void sdsAc8_FR_ROUTE_005_AC2_strictDefaultCaseAndTrailingSlashSignificant() {
        Router<String> r = router("/users");
        assertNull(find(r, "/Users"));
        assertNull(find(r, "/users/"));
        Router<String> t = router("/users/");
        assertEquals("/users/", find(t, "/users/").handler);
        assertNull(find(t, "/users"));
    }

    // SDS-AC-8; FR-ROUTE-005 AC-4
    @Test
    public void sdsAc8_FR_ROUTE_005_AC4_emptySegmentNeverMatchesUnderStrictDefault() {
        Router<String> r = router("/a/:x/b", "/a/b");
        assertEquals(Arrays.asList("a", "", "b"), Router.decodeSegments("/a//b", STRICT));
        assertNull(find(r, "/a//b"));
    }

    // SDS-AC-8
    @Test
    public void sdsAc8_FR_ROUTE_005_rootPathIsOneEmptySegment() {
        assertEquals(Collections.singletonList(""), Router.decodeSegments("/", STRICT));
        assertEquals("/", find(router("/"), "/").handler);
    }
}
