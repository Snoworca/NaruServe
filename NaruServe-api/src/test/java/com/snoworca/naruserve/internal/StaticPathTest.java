package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** StaticPath checks on the mount-relative raw path. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-6.sds.md. */
public class StaticPathTest {

    // SDS-AC-1; FR-STATIC-005 AC-1
    @Test
    public void sdsAc1_FR_STATIC_005_AC1_traversalEncodedSlashNulAndDoubleEncodingAre400() {
        for (String rel : Arrays.asList("../secret.txt", "%2e%2e/secret.txt", "a/%2E%2E/%2E%2E/secret.txt", "a%2Fb",
                "a%2fb", "a%00.txt", "%252e%252e/secret.txt", "a%252Fb", "a%252fb", "x%2500", "%252E%252E/x",
                "./x", "a/../index.html", "a/./b", "%2e/x", "bad%zz")) {
            assertEquals(rel, 400, StaticPath.check(rel));
        }
    }

    // SDS-AC-2; FR-STATIC-005 AC-2
    @Test
    public void sdsAc2_FR_STATIC_005_AC2_dotSegmentsAndEmptySegmentsAre404AfterThe400Checks() {
        for (String rel : Arrays.asList(".env", ".git/config", "assets/.hidden/x.js", "/assets", "a//b", "%2eenv")) {
            assertEquals(rel, 404, StaticPath.check(rel));
        }
        assertEquals(400, StaticPath.check(".env/%2e%2e"));
        assertEquals(400, StaticPath.check("/a%252Fb"));
    }

    // SDS-AC-1; SDS-AC-2
    @Test
    public void sdsAc1_sdsAc2_plainPathsPassAndSegmentsAreDecodedOnce() {
        for (String rel : Arrays.asList("", "docs/", "assets/app.js", "my%20docs", "a.b/c", "a%25b")) {
            assertEquals(rel, 0, StaticPath.check(rel));
        }
        assertEquals(Arrays.asList("my docs", "a.txt"), StaticPath.segments("my%20docs/a.txt"));
        assertEquals(Collections.singletonList("docs"), StaticPath.segments("docs/"));
        assertEquals(Collections.<String>emptyList(), StaticPath.segments(""));
        assertEquals(Collections.singletonList("a%b"), StaticPath.segments("a%25b"));
    }
}
