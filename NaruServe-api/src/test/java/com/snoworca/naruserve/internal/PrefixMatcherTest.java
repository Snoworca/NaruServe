package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import org.junit.Test;

/** Segment-boundary prefix test. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md. */
public class PrefixMatcherTest {

    private static boolean matches(String prefix, String path, RouterPolicy policy) {
        return new PrefixMatcher(prefix, policy).matches(Router.decodeSegments(path, policy));
    }

    private static boolean matches(String prefix, String path) {
        return matches(prefix, path, RouterPolicy.strict());
    }

    // SDS-AC-3; FR-APP-003 AC-2
    @Test
    public void sdsAc3_FR_APP_003_AC2_prefixMatchesOnSegmentBoundaryOnly() {
        assertTrue(matches("/api", "/api"));
        assertTrue(matches("/api", "/api/users"));
        assertTrue(matches("/api", "/api/"));
        assertFalse(matches("/api", "/apix"));
        assertFalse(matches("/api", "/ap"));
        assertFalse(matches("/api", "/"));
        assertFalse(matches("/api/users", "/api"));
    }

    // SDS-AC-3; FR-APP-003 AC-2
    @Test
    public void sdsAc3_FR_APP_003_AC2_trailingSlashPrefixEqualsBareAndRootCoversEveryPath() {
        for (String path : new String[] {"/api", "/api/users", "/apix", "/ap"}) {
            assertTrue(path, matches("/api/", path) == matches("/api", path));
        }
        for (String path : new String[] {"/", "/api", "/api/users", "/x/y/z", "/apix"}) {
            assertTrue(path, matches("/", path));
        }
    }

    // SDS-AC-4; FR-APP-003 AC-3
    @Test
    public void sdsAc4_FR_APP_003_AC3_caseInsensitivePolicyAppliesToThePrefix() {
        RouterPolicy ci = RouterPolicy.builder().caseInsensitive(true).build();
        assertTrue(matches("/api", "/API/users", ci));
        assertTrue(matches("/API", "/api/users", ci));
        assertFalse(matches("/api", "/API/users"));
    }

    // SDS-AC-4; FR-APP-003 AC-3
    @Test
    public void sdsAc4_FR_APP_003_AC3_mergeSlashesPolicyAppliesToThePathAndPrefix() {
        RouterPolicy merge = RouterPolicy.builder().mergeSlashes(true).build();
        assertTrue(matches("/api", "//api//users", merge));
        assertTrue(matches("//api", "/api/users", merge));
        assertFalse(matches("/api", "//api//users"));
    }

    // SDS-AC-3; FR-APP-003 AC-2
    @Test
    public void sdsAc3_FR_APP_003_AC2_percentEncodedPrefixComparesDecoded() {
        assertTrue(matches("/a%20b", "/a b/c"));
        assertTrue(matches("/a b", "/a%20b"));
    }
}
