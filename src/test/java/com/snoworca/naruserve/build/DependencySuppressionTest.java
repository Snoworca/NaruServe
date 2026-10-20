package com.snoworca.naruserve.build;

import static org.junit.Assert.*;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** FR-REL-002 AC-4: an audited false positive must never hide another artifact, version or vulnerability. */
public class DependencySuppressionTest {
    private static final String PURL = "pkg:maven/org.jetbrains.kotlin/kotlin-stdlib@2.2.0";
    private static final String CVE = "CVE-2026-53914";
    private static final String NS = "https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd";
    private static final File ROOT = new File(BuildLayoutTest.property("naruserve.rootDir"));

    private static Element exactRule(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
        assertEquals("suppressions", root.getLocalName());
        assertEquals(NS, root.getNamespaceURI());
        int rules = 0;
        Element rule = null;
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                assertEquals("suppress", child.getLocalName());
                rule = (Element) child;
                rules++;
            }
        }
        assertEquals("only one audited artifact/CVE pair is permitted", 1, rules);
        assertNotNull(rule);
        assertEquals(0, rule.getAttributes().getLength());
        int purls = 0;
        int cves = 0;
        int notes = 0;
        for (Node child = rule.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element)) continue;
            Element element = (Element) child;
            assertEquals(0, element.getAttributes().getLength());
            switch (element.getLocalName()) {
                case "notes":
                    notes++;
                    assertTrue(element.getTextContent().contains("GHSA-r937-wjx7-w2jp"));
                    assertTrue(element.getTextContent().contains("security-review.md"));
                    break;
                case "packageUrl":
                    purls++;
                    assertEquals(PURL, element.getTextContent());
                    break;
                case "cve":
                    cves++;
                    assertEquals(CVE, element.getTextContent());
                    break;
                default: fail("unexpected broad selector " + element.getLocalName());
            }
        }
        assertEquals(1, purls);
        assertEquals(1, cves);
        assertEquals(1, notes);
        return rule;
    }

    private static String sourceXml() throws Exception {
        return new String(Files.readAllBytes(new File(ROOT, "gradle/dependency-check-suppressions.xml").toPath()),
                StandardCharsets.UTF_8);
    }

    // FR-REL-002 AC-4: scanner configuration loads the exact reviewed rule, not a global CVE exclusion.
    @Test public void releaseScannerLoadsOneExactArtifactAndCveRule() throws Exception {
        exactRule(sourceXml());
        String script = BuildLayoutTest.read(new File(ROOT, "gradle/release.gradle"));
        int child = script.indexOf("if (project.hasProperty('releaseScanChild'))");
        int end = script.indexOf("tasks.register('compileReleaseTools'", child);
        assertTrue(script.substring(child, end).contains(
                "suppressionFiles = [rootProject.file('gradle/dependency-check-suppressions.xml').absolutePath]"));
        assertTrue(script.substring(child, end).contains("providers.gradleProperty('nvdDatafeedUrl')"));
        assertTrue(script.substring(child, end).contains("nvd.datafeedUrl = mirror"));
        assertTrue(script.contains("gradle.startParameter.projectProperties.each"));
    }

    // FR-REL-002 AC-4: negative fixtures forbid regexes, product-wide CPEs and unconditional CVE suppression.
    @Test public void broaderSelectorsAreRejected() throws Exception {
        String exact = sourceXml();
        for (String malicious : new String[] {
            exact.replace("<packageUrl>", "<packageUrl regex=\"true\">"),
            exact.replace(PURL, "pkg:maven/org.jetbrains.kotlin/kotlin-stdlib@.*"),
            exact.replace("<packageUrl>" + PURL + "</packageUrl>", ""),
            exact.replace("<cve>" + CVE + "</cve>", ""),
            exact.replace("</suppress>", "<cpe>cpe:/a:jetbrains:kotlin</cpe></suppress>"),
            exact.replace("<cve>" + CVE + "</cve>", "<cve>" + CVE + "</cve><cve>CVE-2002-1819</cve>"),
            exact.replace("<packageUrl>" + PURL + "</packageUrl>", "<packageUrl>" + PURL + "</packageUrl><filePath>.*</filePath>")
        }) {
            assertThrows(AssertionError.class, () -> exactRule(malicious));
        }
    }

    // FR-REL-002 AC-4: both exact selectors are required; the actual affected build plugin is never excluded.
    @Test public void affectedPluginOtherVersionsAndOtherCvesRemainUnmatched() throws Exception {
        Element rule = exactRule(sourceXml());
        String purl = rule.getElementsByTagNameNS(NS, "packageUrl").item(0).getTextContent();
        String cve = rule.getElementsByTagNameNS(NS, "cve").item(0).getTextContent();
        assertTrue(purl.equals(PURL) && cve.equals(CVE));
        for (String other : new String[] {"pkg:maven/org.jetbrains.kotlin/kotlin-gradle-plugin@2.2.0",
                "pkg:maven/org.jetbrains.kotlin/kotlin-stdlib@2.2.1",
                "pkg:maven/com.snoworca.tinyhttpd/tinyhttpd-core@0.1.0-SNAPSHOT"}) {
            assertFalse(other.equals(purl) && CVE.equals(cve));
        }
        assertFalse(PURL.equals(purl) && "CVE-2026-00000".equals(cve));
    }
}
