package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.release.LicenseCheck;
import com.snoworca.naruserve.release.LicenseCheck.License;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The license decision of gradle/release-tools over POM fixtures, child POM first, no network. */
public class LicenseCheckTest {

    static String pom(String... licenses) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n  <groupId>g</groupId>\n  <artifactId>a</artifactId>\n"
                + "  <version>1</version>\n");
        if (licenses.length > 0) {
            b.append("  <licenses>\n");
            for (String name : licenses) {
                b.append("    <license>\n      <name>").append(name).append("</name>\n      <url>https://example.org/")
                        .append(name).append("</url>\n    </license>\n");
            }
            b.append("  </licenses>\n");
        }
        // A dependency's own <licenses> inside <dependencies> is not the project's license.
        b.append("  <dependencies><dependency><licenses><license><name>GPL-2.0</name></license></licenses>"
                + "</dependency></dependencies>\n</project>\n");
        return b.toString();
    }

    private static boolean passes(List<String> chain) {
        return LicenseCheck.verdict(LicenseCheck.licensesOf(chain)).pass;
    }

    // SDS-AC-2; FR-REL-004 AC-2
    @Test
    public void fr_REL_004_AC2_gplFamilyAndUnknownFail() {
        for (String gpl : new String[] {"GPL-2.0", "LGPL-3.0", "AGPL-3.0",
                "GNU General Public License, version 2", "GNU Lesser General Public License v3", "SSPL-1.0",
                "BUSL-1.1", "Proprietary"}) {
            assertFalse(gpl, passes(Collections.singletonList(pom(gpl))));
        }
        assertFalse("no license in the child or its parent",
                passes(Arrays.asList(pom(), pom())));
        assertFalse("an empty chain", passes(Collections.<String>emptyList()));
        assertFalse("empty license list", LicenseCheck.verdict(Collections.<License>emptyList()).pass);
    }

    // SDS-AC-2; FR-REL-004 AC-2
    @Test
    public void fr_REL_004_AC2_permissiveAndDualLicensesPass() {
        for (String ok : new String[] {"Apache-2.0", "The Apache Software License, Version 2.0", "MIT",
                "BSD-3-Clause"}) {
            assertTrue(ok, passes(Collections.singletonList(pom(ok))));
        }
        assertTrue("dual license with one non-GPL member", passes(Collections.singletonList(pom("GPL-2.0", "MIT"))));
        assertFalse("two GPL-family members", passes(Collections.singletonList(pom("GPL-2.0", "LGPL-3.0"))));
        assertFalse("GPL or commercial", passes(Collections.singletonList(pom("GPL-2.0", "Commercial"))));
    }

    // SDS-AC-2; FR-REL-004 AC-2 — slf4j-api 1.7.36 declares MIT only in its parent slf4j-parent
    @Test
    public void fr_REL_004_AC2_parentOnlyLicenseCountsAndTheFirstDeclaringPomWins() {
        List<License> parentOnly = LicenseCheck.licensesOf(Arrays.asList(pom(), pom("MIT")));
        assertEquals(1, parentOnly.size());
        assertEquals("MIT", parentOnly.get(0).name);
        assertEquals("https://example.org/MIT", parentOnly.get(0).url);
        assertTrue(LicenseCheck.verdict(parentOnly).pass);

        List<License> child = LicenseCheck.licensesOf(Arrays.asList(pom("Apache-2.0"), pom("GPL-2.0")));
        assertEquals(1, child.size());
        assertEquals("Apache-2.0", child.get(0).name);
        assertTrue(LicenseCheck.verdict(child).pass);

        assertEquals(2, LicenseCheck.licensesOf(Collections.singletonList(pom("GPL-2.0", "MIT"))).size());
    }

    // SDS-AC-2; FR-REL-004 AC-2
    @Test
    public void fr_REL_004_AC2_failingVerdictNamesTheReason() {
        assertTrue(LicenseCheck.verdict(LicenseCheck.licensesOf(Collections.singletonList(pom("AGPL-3.0"))))
                .message.contains("AGPL-3.0"));
        assertTrue(LicenseCheck.verdict(Collections.<License>emptyList()).message.contains("unknown"));
    }
}
