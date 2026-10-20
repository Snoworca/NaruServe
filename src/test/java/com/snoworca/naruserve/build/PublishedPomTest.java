package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The maven-publish POMs the outer build generated (generatePomFileForMavenPublication), read through
 * naruserve.pomFiles without a nested build, and the publishing text of build.gradle and release.gradle.
 */
public class PublishedPomTest {

    private static final String VERSION = "0.1.0-SNAPSHOT";

    /** One parsed dependency: version and scope. */
    static final class Dep {
        final String version;
        final String scope;

        Dep(String version, String scope) {
            this.version = version;
            this.scope = scope;
        }

        @Override
        public String toString() {
            return version + "/" + scope;
        }
    }

    private static String text(Element parent, String child) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && child.equals(n.getLocalName() != null ? n.getLocalName() : n.getNodeName())) {
                return n.getTextContent().trim();
            }
        }
        return "";
    }

    /** artifactId → root element of each POM named by naruserve.pomFiles. */
    static Map<String, Element> poms() throws Exception {
        String files = System.getProperty("naruserve.pomFiles");
        assertNotNull("naruserve.pomFiles system property", files);
        Map<String, Element> poms = new TreeMap<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        for (String path : files.split(",")) {
            File f = new File(path);
            assertTrue("generated POM " + f, f.isFile());
            Document doc = factory.newDocumentBuilder().parse(f);
            Element project = doc.getDocumentElement();
            poms.put(text(project, "artifactId"), project);
        }
        return poms;
    }

    /** {@code group:artifact} → dependency of one POM. */
    static Map<String, Dep> deps(Element project) {
        Map<String, Dep> deps = new LinkedHashMap<>();
        NodeList list = project.getElementsByTagNameNS("*", "dependency");
        for (int i = 0; i < list.getLength(); i++) {
            Element d = (Element) list.item(i);
            String scope = text(d, "scope");
            deps.put(text(d, "groupId") + ":" + text(d, "artifactId"),
                    new Dep(text(d, "version"), scope.isEmpty() ? "compile" : scope));
        }
        return deps;
    }

    private static String file(String path) throws IOException {
        return BuildLayoutTest.read(new File(BuildLayoutTest.property("naruserve.rootDir"), path));
    }

    // SDS-AC-5; FR-REL-005 AC-1
    @Test
    public void fr_REL_005_AC1_nineGeneratedPomsWithTheModuleCoordinates() throws Exception {
        Map<String, Element> poms = poms();
        assertEquals(new java.util.TreeSet<>(BuildLayoutTest.MODULES), poms.keySet());
        for (Element project : poms.values()) {
            assertEquals("com.snoworca.naruserve", text(project, "groupId"));
            assertEquals(VERSION, text(project, "version"));
        }
    }

    // SDS-AC-6; FR-REL-005 AC-2
    @Test
    public void fr_REL_005_AC2_dependenciesCarryResolvedVersionsAndScopes() throws Exception {
        Map<String, Element> poms = poms();
        Map<String, Dep> tiny = deps(poms.get("NaruServe-tinyhttpd"));
        for (String t : new String[] {"tinyhttpd-core", "tinyhttpd-websocket"}) {
            Dep d = tiny.get("com.snoworca.tinyhttpd:" + t);
            assertNotNull(t + " in " + tiny, d);
            assertEquals(t, VERSION, d.version);
        }
        Dep gson = deps(poms.get("NaruServe-gson")).get("com.google.code.gson:gson");
        assertNotNull(gson);
        assertEquals("2.14.0", gson.version);
        assertEquals("compile", gson.scope);
        Dep stdlib = deps(poms.get("NaruServe-kotlin")).get("org.jetbrains.kotlin:kotlin-stdlib");
        assertNotNull(stdlib);
        assertEquals("2.2.0", stdlib.version);
        Dep slf4j = deps(poms.get("NaruServe-slf4j")).get("org.slf4j:slf4j-api");
        assertNotNull(slf4j);
        assertEquals("1.7.36", slf4j.version);
        Map<String, Dep> servlet = deps(poms.get("NaruServe-servlet"));
        assertTrue(servlet.toString(), servlet.containsKey("com.snoworca.naruserve:NaruServe-api"));
        for (String key : servlet.keySet()) {
            assertFalse("no Servlet API in the servlet POM: " + key,
                    key.startsWith("javax.servlet:") || key.startsWith("jakarta.servlet:")
                            || key.startsWith("javax.websocket:"));
        }
        Map<String, Dep> bootstrap = deps(poms.get("NaruServe"));
        for (String m : new String[] {"NaruServe-tinyhttpd", "NaruServe-gson", "NaruServe-logcat"}) {
            Dep d = bootstrap.get("com.snoworca.naruserve:" + m);
            assertNotNull(m + " in " + bootstrap, d);
            assertEquals(m, "runtime", d.scope);
            assertEquals(m, VERSION, d.version);
        }
        for (Element project : poms.values()) {
            for (Map.Entry<String, Dep> e : deps(project).entrySet()) {
                assertFalse(text(project, "artifactId") + " " + e.getKey() + " has a version", e.getValue().version.isEmpty());
            }
        }
    }

    /** The {@code publishing { ... }} block of build.gradle, braces balanced. */
    static String publishingBlock(String build) {
        int start = build.indexOf("publishing {");
        assertTrue("build.gradle has a publishing block", start >= 0);
        int depth = 0;
        for (int i = build.indexOf('{', start); i < build.length(); i++) {
            char c = build.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return build.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced publishing block");
    }

    // SDS-AC-7; FR-REL-005 AC-3
    @Test
    public void fr_REL_005_AC3_noPublishingRepositoryAndVersionlessTinyHttpd() throws IOException {
        String build = file("build.gradle");
        String publishing = publishingBlock(build);
        assertFalse(publishing, publishing.contains("repositories"));
        assertFalse("build.gradle names no publishing repository", build.contains("publishing.repositories"));
        assertTrue(publishing, Pattern.compile("from\\s+(p\\.)?components\\.java").matcher(publishing).find());
        assertTrue(publishing, Pattern.compile("versionMapping\\s*\\{\\s*allVariants\\s*\\{\\s*fromResolutionResult\\(\\)")
                .matcher(publishing).find());
        assertTrue(build.contains("'com.snoworca.tinyhttpd:tinyhttpd-core'"));
        assertTrue(build.contains("'com.snoworca.tinyhttpd:tinyhttpd-websocket'"));
        assertFalse("TinyHttpd declarations stay versionless",
                Pattern.compile("com\\.snoworca\\.tinyhttpd:tinyhttpd-[a-z]+:").matcher(build).find());
    }

    // SDS-AC-8; FR-REL-005 AC-4
    @Test
    public void fr_REL_005_AC4_mavenPublishAppliedInBuildGradleNotInReleaseGradle() throws IOException {
        assertTrue(file("build.gradle").contains("apply plugin: 'maven-publish'"));
        assertFalse(file("gradle/release.gradle").contains("maven-publish"));
    }
}
