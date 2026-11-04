package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Test;

/** Where the auth module lives and what it may depend on (CON-AUTH-001, CON-IAM-003 AC-2, CON-AUTH-002 AC-3). */
public class AuthPlacementTest {

    private static final File ROOT = new File(BuildLayoutTest.property("naruserve.rootDir"));

    private static final List<String> SUBPACKAGES = Collections.unmodifiableList(Arrays.asList(
            "jwt", "password", "user", "group", "rbac", "audit", "apikey", "spi", "internal", "testing"));

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(new File(ROOT, path).toPath()), StandardCharsets.UTF_8);
    }

    private static List<String> entries(File jar) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                out.add(e.nextElement().getName());
            }
        }
        return out;
    }

    private static File jar(String module, String suffix) {
        File f = new File(ROOT, module + "/build/libs/" + module + "-0.11.0" + suffix + ".jar");
        assertTrue(f.getPath(), f.isFile());
        return f;
    }

    // CON-AUTH-001 AC-1: NaruServe-api main dependencies stay empty and no module gains a main notation
    @Test
    public void CON_AUTH_001_AC1_mainDependenciesUnchanged() throws IOException {
        String build = read("build.gradle");
        assertTrue(build.contains("':NaruServe-api'      : [:],"));
        Matcher m = Pattern.compile("def mainDependencies = \\[(.*?)\\n\\]", Pattern.DOTALL).matcher(build);
        assertTrue(m.find());
        TreeSet<String> notations = new TreeSet<>();
        Matcher n = Pattern.compile("'([a-z][^':]*:[^':]+:[^']+)'|\"([a-z][^\":]*:[^\":]+:[^\"]+)\"").matcher(m.group(1));
        while (n.find()) {
            notations.add(n.group(1) != null ? n.group(1) : n.group(2));
        }
        assertEquals(new TreeSet<>(Arrays.asList("com.google.code.gson:gson:2.14.0", "com.snoworca:tinyhttpd-core:3.1.1",
                "com.snoworca:tinyhttpd-websocket:3.1.1", "org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion}",
                "org.slf4j:slf4j-api:1.7.36")), notations);
    }

    // CON-AUTH-001 AC-2: no auth Gradle project; the module list is the one of 0.10.0
    @Test
    public void CON_AUTH_001_AC2_noNewProject() throws IOException {
        String build = read("build.gradle");
        assertTrue(build.contains("def naruModules = [':NaruServe-api', ':NaruServe-servlet', ':NaruServe-tinyhttpd', "
                + "':NaruServe-gson', ':NaruServe',\n                   ':NaruServe-kotlin', ':NaruServe-slf4j', "
                + "':NaruServe-filelog', ':NaruServe-logcat']"));
        String settings = read("settings.gradle");
        TreeSet<String> includes = new TreeSet<>();
        Matcher m = Pattern.compile("include '([^']+)'").matcher(settings);
        while (m.find()) {
            includes.add(m.group(1));
        }
        assertEquals(new TreeSet<>(Arrays.asList(":NaruServe-api", ":NaruServe-servlet", ":NaruServe-tinyhttpd",
                ":NaruServe-gson", ":NaruServe", ":NaruServe-kotlin", ":NaruServe-slf4j", ":NaruServe-filelog",
                ":NaruServe-logcat", ":NaruServe-android")), includes);
        for (String forbidden : new String[] {"NaruServe-utils", "NaruServe-utils-jdbc", "NaruServe-auth"}) {
            assertFalse(forbidden, settings.contains(forbidden));
            assertFalse(forbidden, new File(ROOT, forbidden).exists());
        }
    }

    // CON-AUTH-001 AC-3: the auth packages are in the NaruServe-api main jar and in no other jar, and no utils/auth/jdbc
    // subpackage exists
    @Test
    public void CON_AUTH_001_AC3_authClassesOnlyInTheApiMainJar() throws IOException {
        List<String> api = entries(jar("NaruServe-api", ""));
        String base = "com/snoworca/naruserve/utils/auth/";
        assertTrue(api.contains(base + "package-info.class"));
        for (String sub : SUBPACKAGES) {
            boolean found = false;
            for (String e : api) {
                found |= e.startsWith(base + sub + "/") && e.endsWith(".class");
            }
            assertTrue(sub, found);
        }
        for (String v2 : new String[] {"oidc", "mfa", "tenant", "scim", "jdbc"}) {
            for (String e : api) {
                assertFalse(e, e.startsWith(base + v2 + "/"));
            }
        }
        List<File> others = new ArrayList<>();
        others.add(jar("NaruServe-api", "-test-fixtures"));
        for (String module : new String[] {"NaruServe", "NaruServe-servlet", "NaruServe-tinyhttpd", "NaruServe-gson",
                "NaruServe-kotlin", "NaruServe-slf4j", "NaruServe-filelog", "NaruServe-logcat"}) {
            others.add(jar(module, ""));
        }
        for (File other : others) {
            for (String e : entries(other)) {
                assertFalse(other.getName() + " holds " + e, e.startsWith("com/snoworca/naruserve/utils/"));
            }
        }
    }

    // CON-AUTH-001 AC-4: the NaruServe-api runtime classpath resolves no external module
    @Test
    public void CON_AUTH_001_AC4_runtimeClasspathHasNoExternalModule() throws IOException {
        List<String> external = new ArrayList<>();
        int rows = 0;
        for (String line : BuildLayoutTest.read(new File(BuildLayoutTest.property("naruserve.depReport")))
                .split("\n")) {
            String[] row = line.trim().split("\\|");
            if (row.length == 3 && row[0].equals(":NaruServe-api") && row[1].equals("runtimeClasspath")) {
                rows++;
                if (!row[2].startsWith("com.snoworca.naruserve:NaruServe-api:")) {
                    external.add(row[2]);
                }
            }
        }
        assertEquals(1, rows);
        assertEquals(Collections.emptyList(), external);
    }

    /** group:name prefixes of JDBC drivers; none may appear in the build or on a NaruServe-api classpath. */
    private static final Pattern JDBC_DRIVER = Pattern.compile("org\\.xerial:sqlite-jdbc|com\\.h2database:|org\\.hsqldb:"
            + "|org\\.apache\\.derby:|mysql:mysql-connector|com\\.mysql:mysql-connector|org\\.postgresql:postgresql"
            + "|org\\.mariadb\\.jdbc:|com\\.oracle\\.database\\.jdbc:|com\\.microsoft\\.sqlserver:mssql-jdbc"
            + "|sqlite-jdbc|-jdbc:");

    // CON-IAM-003 AC-2: the root build.gradle declares no sqlite-jdbc or other JDBC driver in any configuration (main,
    // test or testFixtures), no NaruServe-api configuration resolves one, and mainDependencies of ':NaruServe-api'
    // stays [:]
    @Test
    public void CON_IAM_003_AC2_noJdbcDriverInAnyApiConfiguration() throws IOException {
        String build = read("build.gradle");
        assertTrue(build.contains("':NaruServe-api'      : [:],"));
        for (String file : new String[] {"build.gradle", "gradle/release.gradle", "settings.gradle"}) {
            String text = read(file).toLowerCase(java.util.Locale.ROOT);
            assertFalse(file + " names a JDBC artifact", text.contains("jdbc") || text.contains("sqlite"));
        }
        Matcher declared = Pattern.compile("(\\w+)\\s*\\(?\\s*['\"]([^'\"]+:[^'\"]+)['\"]").matcher(build);
        List<String> drivers = new ArrayList<>();
        int notations = 0;
        while (declared.find()) {
            notations++;
            if (JDBC_DRIVER.matcher(declared.group(2)).find()) {
                drivers.add(declared.group(1) + " " + declared.group(2));
            }
        }
        assertTrue("the build declares dependencies", notations > 5);
        assertEquals(Collections.emptyList(), drivers);
        int start = build.indexOf("project(':NaruServe-api') {");
        assertTrue(start > 0);
        String apiBlock = build.substring(start, build.indexOf("\n}\n", start));
        assertFalse(apiBlock, apiBlock.toLowerCase(java.util.Locale.ROOT).contains("jdbc"));
        TreeSet<String> apiConfigurations = new TreeSet<>();
        List<String> resolved = new ArrayList<>();
        for (String line : BuildLayoutTest.read(new File(BuildLayoutTest.property("naruserve.depReport")))
                .split("\n")) {
            String[] row = line.trim().split("\\|");
            if (row.length == 3 && row[0].equals(":NaruServe-api")) {
                apiConfigurations.add(row[1]);
                if (JDBC_DRIVER.matcher(row[2]).find()) {
                    resolved.add(line);
                }
            }
        }
        assertTrue(apiConfigurations.toString(), apiConfigurations.containsAll(Arrays.asList("compileClasspath",
                "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")));
        assertEquals(Collections.emptyList(), resolved);
        String poms = BuildLayoutTest.property("naruserve.pomFiles");
        int checked = 0;
        for (String pom : poms.split(",")) {
            String text = BuildLayoutTest.read(new File(pom));
            assertFalse(pom, text.contains("sqlite"));
            assertFalse(pom, text.contains("jdbc"));
            checked++;
        }
        assertTrue(checked >= 9);
        File apiModule = new File(ROOT, "NaruServe-api/build/publications/maven/module.json");
        assertTrue(apiModule.getPath(), apiModule.isFile());
        String module = BuildLayoutTest.read(apiModule);
        assertFalse(module.contains("sqlite"));
        assertFalse(module.contains("jdbc"));
    }

    // CON-AUTH-002 AC-3 and CON-AUTH-003 AC-1 (build side): no exemption for utils in the class and API 21 checks
    @Test
    public void CON_AUTH_002_AC3_CON_AUTH_003_AC1_checksRunWithoutAUtilsExemption() throws IOException {
        String build = read("build.gradle");
        int start = build.indexOf("def classRefPrefixes = { String path ->");
        int end = build.indexOf("\n}\n", start);
        assertTrue(start > 0 && end > start);
        String prefixes = build.substring(start, end);
        assertFalse(prefixes, prefixes.contains("utils"));
        for (String p : new String[] {"'android/'", "'jakarta/servlet/'", "'com/google/gson/'",
                "'com/snoworca/tinyhttpd/'", "'kotlin/'", "'org/slf4j/'", "'javax/servlet/'"}) {
            assertTrue(p, prefixes.contains(p));
        }
        int sniff = build.indexOf("apply plugin: 'ru.vyarus.animalsniffer'");
        String animal = build.substring(sniff, build.indexOf("\n}\n", sniff));
        assertTrue(animal, animal.contains("android-api-level-21:5.0.1_r2@signature"));
        assertTrue(animal, animal.contains("sourceSets = [sourceSets.main]"));
        for (String exemption : new String[] {"exclude", "ignore", "annotation"}) {
            assertFalse(exemption, animal.contains(exemption));
        }
        assertTrue(build.contains("def androidModules = naruModules - [':NaruServe-servlet', ':NaruServe-slf4j']"));
        assertNotNull(read("NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/package-info.java"));
    }
}
