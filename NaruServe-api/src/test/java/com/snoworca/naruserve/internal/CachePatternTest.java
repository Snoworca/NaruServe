package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.snoworca.naruserve.CachePolicy;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Mount-relative cache patterns. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-8.sds.md. */
public class CachePatternTest {

    private static final CachePolicy A = CachePolicy.noCache();
    private static final CachePolicy B = CachePolicy.publicFor(1, TimeUnit.HOURS);

    private static List<Map.Entry<String, CachePolicy>> of(Object... pairs) {
        List<Map.Entry<String, CachePolicy>> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(new SimpleImmutableEntry<>((String) pairs[i], (CachePolicy) pairs[i + 1]));
        }
        return list;
    }

    // SDS-AC-3; FR-STATIC-008 AC-4
    @Test
    public void sdsAc3_FR_STATIC_008_AC4_doubleStarSegmentsAndSingleStarWithinOne() {
        List<Map.Entry<String, CachePolicy>> html = of("**/*.html", A);
        assertSame(A, CachePattern.select(html, "/index.html"));
        assertSame(A, CachePattern.select(html, "/docs/a.html"));
        assertNull(CachePattern.select(html, "/a.htm"));
        List<Map.Entry<String, CachePolicy>> assets = of("/assets/**", A);
        assertSame(A, CachePattern.select(assets, "/assets/x.js"));
        assertSame(A, CachePattern.select(assets, "/assets/a/b.js"));
        assertNull(CachePattern.select(assets, "/x/assets/y.js"));
        List<Map.Entry<String, CachePolicy>> one = of("/a*.txt", A);
        assertSame(A, CachePattern.select(one, "/ab.txt"));
        assertNull(CachePattern.select(one, "/ab/c.txt"));
        assertSame(A, CachePattern.select(of("/a+b.txt", A), "/a+b.txt"));
        assertNull(CachePattern.select(of("/a+b.txt", A), "/aab.txt"));
    }

    // SDS-AC-3; FR-STATIC-008 AC-4
    @Test
    public void sdsAc3_FR_STATIC_008_AC4_patternWithoutSlashMatchesAnyDepth() {
        List<Map.Entry<String, CachePolicy>> css = of("*.css", A);
        assertSame(A, CachePattern.select(css, "/a/b.css"));
        assertSame(A, CachePattern.select(css, "/b.css"));
        assertNull(CachePattern.select(css, "/a/b.cssx"));
    }

    // SDS-AC-3; FR-STATIC-008 AC-4
    @Test
    public void sdsAc3_FR_STATIC_008_AC4_firstRegisteredWins() {
        List<Map.Entry<String, CachePolicy>> both = of("/assets/**", A, "**/*.js", B);
        assertSame(A, CachePattern.select(both, "/assets/x.js"));
        assertSame(B, CachePattern.select(both, "/x.js"));
        assertEquals(null, CachePattern.select(both, "/x.css"));
    }
}
