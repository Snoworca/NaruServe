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
 * Every artifactId is the project name in lower case (central-0.9.0).
 */
// @req CON-BUILD-018 AC-1 AC-2 AC-3
public class PublishedPomTest {

    // OPS-REL-009 AC-1: assert the new publication, including every generated internal edge.
    private static final String VERSION = "0.10.0";
    /** The version of the included TinyHttpd build (group com.snoworca since TinyHttpd 3.0.0). */
    private static final String TINYHTTPD_VERSION = "3.1.1";

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

    /** The published artifactIds: each included project name in lower case. */
    static java.util.Set<String> artifactIds() {
        java.util.Set<String> ids = new java.util.TreeSet<>();
        for (String module : BuildLayoutTest.MODULES) {
            ids.add(module.toLowerCase(java.util.Locale.ROOT));
        }
        if (BuildLayoutTest.androidIncluded()) {
            ids.add(BuildLayoutTest.ANDROID_MODULE.toLowerCase(java.util.Locale.ROOT));
        }
        return ids;
    }

    // SDS-AC-5; FR-REL-005 AC-1, FR-BUILD-001 AC-4
    @Test
    public void fr_REL_005_AC1_generatedPomsOfTheNineJarModulesAndTheAndroidAar() throws Exception {
        Map<String, Element> poms = poms();
        assertEquals(artifactIds(), poms.keySet());
        for (Element project : poms.values()) {
            assertEquals("com.snoworca.naruserve", text(project, "groupId"));
            assertEquals(VERSION, text(project, "version"));
        }
    }

    // CON-BUILD-018 AC-1 AC-2; SDS-AC-6; FR-REL-005 AC-2
    @Test
    public void fr_REL_005_AC2_dependenciesCarryResolvedVersionsAndScopes() throws Exception {
        Map<String, Element> poms = poms();
        Map<String, Dep> tiny = deps(poms.get("naruserve-tinyhttpd"));
        for (String t : new String[] {"tinyhttpd-core", "tinyhttpd-websocket"}) {
            Dep d = tiny.get("com.snoworca:" + t);
            assertNotNull(t + " in " + tiny, d);
            assertEquals(t, TINYHTTPD_VERSION, d.version);
            assertEquals(t+" remains implementation-scope", "runtime", d.scope);
        }
        Dep gson = deps(poms.get("naruserve-gson")).get("com.google.code.gson:gson");
        assertNotNull(gson);
        assertEquals("2.14.0", gson.version);
        assertEquals("compile", gson.scope);
        Map<String, Dep> kotlin = deps(poms.get("naruserve-kotlin"));
        Dep stdlib = kotlin.get("org.jetbrains.kotlin:kotlin-stdlib");
        assertNotNull(stdlib);
        assertEquals("2.2.0", stdlib.version);
        assertScope(kotlin, "naruserve", "compile");
        Dep slf4j = deps(poms.get("naruserve-slf4j")).get("org.slf4j:slf4j-api");
        assertNotNull(slf4j);
        assertEquals("1.7.36", slf4j.version);
        Map<String, Dep> servlet = deps(poms.get("naruserve-servlet"));
        assertTrue(servlet.toString(), servlet.containsKey("com.snoworca.naruserve:naruserve-api"));
        for (String key : servlet.keySet()) {
            assertFalse("no Servlet API in the servlet POM: " + key,
                    key.startsWith("javax.servlet:") || key.startsWith("jakarta.servlet:")
                            || key.startsWith("javax.websocket:"));
        }
        Map<String, Dep> bootstrap = deps(poms.get("naruserve"));
        assertScope(bootstrap, "naruserve-api", "compile");
        assertScope(bootstrap, "naruserve-gson", "compile");
        assertScope(bootstrap, "naruserve-tinyhttpd", "runtime");
        assertFalse("NaruServe stays free of the Logcat logger: " + bootstrap,
                bootstrap.containsKey("com.snoworca.naruserve:naruserve-logcat"));
        if (BuildLayoutTest.androidIncluded()) {
            Element android = poms.get("naruserve-android");
            assertEquals("aar", text(android, "packaging"));
            Map<String, Dep> androidDeps = deps(android);
            assertEquals(androidDeps.toString(), 2, androidDeps.size());
            assertScope(androidDeps, "naruserve", "compile");
            assertScope(androidDeps, "naruserve-logcat", "compile");
        }
        for (Element project : poms.values()) {
            for (Map.Entry<String, Dep> e : deps(project).entrySet()) {
                assertFalse(text(project, "artifactId") + " " + e.getKey() + " has a version", e.getValue().version.isEmpty());
                if (e.getKey().startsWith("com.snoworca.naruserve:")) {
                    assertEquals(text(project, "artifactId") + " " + e.getKey(), VERSION, e.getValue().version);
                }
            }
        }
    }

    /** The NaruServe module {@code artifact} is in {@code deps} with this build's version and {@code scope}. */
    private static void assertScope(Map<String, Dep> deps, String artifact, String scope) {
        Dep d = deps.get("com.snoworca.naruserve:" + artifact);
        assertNotNull(artifact + " in " + deps, d);
        assertEquals(artifact + " " + d, scope, d.scope);
        assertEquals(artifact, VERSION, d.version);
    }

    /** The {@code publishing { ... }} block of build.gradle at or after {@code from}, braces balanced. */
    static String publishingBlock(String build) {
        return publishingBlock(build, 0);
    }

    static String publishingBlock(String build, int from) {
        int start = build.indexOf("publishing {", from);
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
    public void fr_REL_005_AC3_onlyTheLocalStagingRepositoryAndPinnedTinyHttpd() throws IOException {
        String build = file("build.gradle");
        String publishing = publishingBlock(build);
        // One repository declaration, a file repository under the root build directory, shared by every module.
        assertEquals("one centralStaging declaration", 1, count(build, "name = 'centralStaging'"));
        assertTrue(build, build.contains("rootProject.layout.buildDirectory.dir('central-staging')"));
        // No publishing block, the AAR's included, names a URL or declares its own repository.
        for (int at = build.indexOf("publishing {"); at >= 0; at = build.indexOf("publishing {", at + 1)) {
            String block = publishingBlock(build, at);
            assertFalse(block, block.contains("http"));
            assertFalse(block, block.contains("maven {"));
        }
        for (String remote : new String[] {"sonatype", "repo.maven.apache.org", "repo1.maven.org"}) {
            assertFalse("no remote repository URL: " + remote, build.contains(remote));
        }
        assertTrue(publishing, Pattern.compile("from\\s+(p\\.)?components\\.java").matcher(publishing).find());
        assertTrue(publishing, Pattern.compile("versionMapping\\s*\\{\\s*allVariants\\s*\\{\\s*fromResolutionResult\\(\\)")
                .matcher(publishing).find());
        assertTrue(build.contains("'com.snoworca:tinyhttpd-core:3.1.1'"));
        assertTrue(build.contains("'com.snoworca:tinyhttpd-websocket:3.1.1'"));
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            n++;
        }
        return n;
    }

    // FR-REL-005 AC-5
    @Test
    public void fr_REL_005_AC5_everyPomCarriesTheCentralMetadata() throws Exception {
        for (Map.Entry<String, Element> e : poms().entrySet()) {
            String id = e.getKey();
            Element project = e.getValue();
            assertEquals(id, "com.snoworca.naruserve", text(project, "groupId"));
            assertEquals(id, VERSION, text(project, "version"));
            assertEquals(id, id, text(project, "name"));
            assertFalse(id + " description", text(project, "description").isEmpty());
            assertEquals(id, "https://github.com/Snoworca/NaruServe", text(project, "url"));
            NodeList licenses = project.getElementsByTagNameNS("*", "license");
            assertEquals(id + " licenses", 1, licenses.getLength());
            assertEquals(id, "BSD 3-Clause License", text((Element) licenses.item(0), "name"));
            assertEquals(id, "https://github.com/Snoworca/NaruServe/blob/main/LICENSE",
                    text((Element) licenses.item(0), "url"));
            NodeList developers = project.getElementsByTagNameNS("*", "developer");
            assertEquals(id + " developers", 1, developers.getLength());
            assertEquals(id, "ice3x2", text((Element) developers.item(0), "id"));
            Element scm = (Element) project.getElementsByTagNameNS("*", "scm").item(0);
            assertNotNull(id + " scm", scm);
            assertEquals(id, "scm:git:https://github.com/Snoworca/NaruServe.git", text(scm, "connection"));
            assertEquals(id, "scm:git:ssh://git@github.com/Snoworca/NaruServe.git", text(scm, "developerConnection"));
            assertEquals(id, "https://github.com/Snoworca/NaruServe", text(scm, "url"));
            assertFalse(id + " names no NanoHttpd license", project.getTextContent().contains("NanoHttpd"));
        }
    }

    // SDS-AC-8; FR-REL-005 AC-4
    @Test
    public void fr_REL_005_AC4_mavenPublishAppliedInBuildGradleNotInReleaseGradle() throws IOException {
        assertTrue(file("build.gradle").contains("apply plugin: 'maven-publish'"));
        assertFalse(file("gradle/release.gradle").contains("maven-publish"));
    }
}
