package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Durable class-level rules of the NaruServe-api main classes, read from the compiled class files and from the public
 * types by reflection. The class files are those the module builds produce before this test runs.
 */
public class ClassConstraintsTest {

    private static final File ROOT = new File(BuildLayoutTest.property("naruserve.rootDir"));
    private static final String PKG = "com/snoworca/naruserve/";
    private static final File API_MAIN = new File(ROOT, "NaruServe-api/build/classes/java/main");

    /** Class files under {@code dir} (a main class directory) whose path below it starts with {@code prefix}. */
    private static List<File> classes(File dir, String prefix) {
        List<File> found = new ArrayList<>();
        for (File f : BuildLayoutTest.walk(dir, ".class")) {
            String rel = f.getPath().substring(dir.getPath().length() + 1).replace(File.separatorChar, '/');
            if (rel.startsWith(prefix)) {
                found.add(f);
            }
        }
        assertFalse("class files under " + dir + "/" + prefix, found.isEmpty());
        return found;
    }

    /** Every {@code "<class file> -> <forbidden>"} where the class file holds one of the forbidden constants. */
    private static List<String> references(List<File> files, String... forbidden) throws IOException {
        List<String> hits = new ArrayList<>();
        for (File f : files) {
            // Class names and descriptors sit in the constant pool as ASCII, so a byte-for-byte text search sees them.
            String text = BuildLayoutTest.read(f, StandardCharsets.ISO_8859_1);
            for (String s : forbidden) {
                if (text.contains(s)) {
                    hits.add(f.getName() + " -> " + s);
                }
            }
        }
        return hits;
    }

    // CON-ROUTE-003 AC-1
    @Test
    public void con_ROUTE_003_AC1_routingPackageReferencesNoAdapterGsonServletAndroidOrKotlinType() throws IOException {
        List<File> routing = classes(API_MAIN, PKG + "routing/");
        assertEquals(Arrays.asList(), references(routing, PKG + "spi/", PKG + "tinyhttpd/", PKG + "servlet/",
                PKG + "gson/", "com/snoworca/tinyhttpd/", "com/google/gson/", "javax/servlet/", "jakarta/servlet/",
                "android/", "kotlin/"));
    }

    // CON-ROUTE-004 AC-1
    @Test
    public void con_ROUTE_004_AC1_routingPackageUsesNoOptionalFunctionOrStreamAndApiIsSniffedAtApi21()
            throws IOException {
        List<File> routing = classes(API_MAIN, PKG + "routing/");
        assertEquals(Arrays.asList(),
                references(routing, "java/util/Optional", "java/util/function/", "java/util/stream/"));
        // The animal-sniffer android-api-level-21 check runs over the main classes of NaruServe-api under check.
        String build = BuildLayoutTest.read(new File(ROOT, "build.gradle"));
        assertTrue(build.contains("def androidModules = naruModules - [':NaruServe-servlet', ':NaruServe-slf4j']"));
        assertTrue(build.contains("signature 'net.sf.androidscents.signature:android-api-level-21:"));
    }

    // CON-ROUTE-005 AC-1: the second half of the rule (the first half, six kinds, is RouteMatchTest); CON-HTTP-003 AC-1
    @Test
    public void con_ROUTE_005_AC1_con_HTTP_003_AC1_routingBuildsNoResponseAndNamesNoCoreHttpType() throws IOException {
        List<File> routing = classes(API_MAIN, PKG + "routing/");
        assertEquals(Arrays.asList(), references(routing, PKG + "Request", PKG + "Response", PKG + "RouteHandler",
                PKG + "RestApp", PKG + "spi/", PKG + "internal/DefaultResponse", PKG + "internal/StandardErrors",
                PKG + "internal/Dispatcher"));
    }

    // CON-HTTP-002 AC-1
    @Test
    public void con_HTTP_002_AC1_publicTypesOfTheCorePackageHaveNoOptionalFunctionStreamOrNioFileSignature()
            throws Exception {
        String[] forbidden = {"java.util.Optional", "java.util.function.", "java.util.stream.", "java.nio.file."};
        List<String> hits = new ArrayList<>();
        int types = 0;
        File dir = new File(API_MAIN, PKG);
        File[] files = dir.listFiles();
        assertTrue(dir.getPath(), files != null);
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".class") || name.equals("package-info.class")) {
                continue;
            }
            Class<?> type = Class.forName("com.snoworca.naruserve." + name.substring(0, name.length() - 6), false,
                    ClassConstraintsTest.class.getClassLoader());
            if (!Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            types++;
            List<String> signatures = new ArrayList<>();
            signatures.add(type.toGenericString());
            for (Method m : type.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers())) {
                    signatures.add(m.toGenericString());
                }
            }
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(c.getModifiers()) || Modifier.isProtected(c.getModifiers())) {
                    signatures.add(c.toGenericString());
                }
            }
            for (Field fld : type.getDeclaredFields()) {
                if (Modifier.isPublic(fld.getModifiers()) || Modifier.isProtected(fld.getModifiers())) {
                    signatures.add(fld.toGenericString());
                }
            }
            for (String signature : signatures) {
                for (String s : forbidden) {
                    if (signature.contains(s)) {
                        hits.add(signature);
                    }
                }
            }
        }
        assertTrue("public types of com.snoworca.naruserve: " + types, types >= 20);
        assertEquals(Arrays.asList(), hits);
        // The build side of the rule: NaruServe-api is sniffed at API 21 under check.
        assertTrue(BuildLayoutTest.read(new File(ROOT, "build.gradle"))
                .contains("p.tasks.named('check') { dependsOn checkClassReferences }"));
    }

    // CON-HTTP-004 AC-1; CON-BUILD-015 AC-1 for every main class of NaruServe-api
    @Test
    public void con_HTTP_004_AC1_con_BUILD_015_AC1_spiAndInternalAndEveryApiMainClassStayFreeOfEngineAndPlatformTypes()
            throws IOException {
        String[] forbidden = {"com/snoworca/tinyhttpd/", "javax/servlet/", "jakarta/servlet/", "com/google/gson/",
            "android/", "kotlin/"};
        assertEquals(Arrays.asList(), references(classes(API_MAIN, PKG + "spi/"), forbidden));
        assertEquals(Arrays.asList(), references(classes(API_MAIN, PKG + "internal/"), forbidden));
        assertEquals(Arrays.asList(), references(classes(API_MAIN, PKG), forbidden));
    }

    // CON-HTTP-005 AC-1
    @Test
    public void con_HTTP_005_AC1_apiMainHoldsNoTestingClassAndNoMainClassReferencesOne() throws IOException {
        assertFalse("the testing package belongs to the testFixtures source set",
                new File(API_MAIN, PKG + "testing").exists());
        assertEquals(Arrays.asList(), references(classes(API_MAIN, PKG), PKG + "testing/"));
    }

    // CON-HTTP-006 AC-1: the dispatcher alone reads a RouteMatch kind, so no other class maps one to a status
    @Test
    public void con_HTTP_006_AC1_onlyTheDispatcherReadsRouteMatchOutsideTheRoutingPackage() throws IOException {
        List<File> outside = new ArrayList<>();
        for (File f : classes(API_MAIN, PKG)) {
            String rel = f.getPath().substring(API_MAIN.getPath().length() + 1).replace(File.separatorChar, '/');
            if (!rel.startsWith(PKG + "routing/") && !rel.startsWith(PKG + "internal/Dispatcher")) {
                outside.add(f);
            }
        }
        assertEquals(Arrays.asList(), references(outside, PKG + "routing/RouteMatch"));
        assertTrue(references(classes(API_MAIN, PKG + "internal/Dispatcher"), PKG + "routing/RouteMatch$Kind")
                .size() > 0);
        for (String adapter : new String[] {"NaruServe-tinyhttpd", "NaruServe-servlet"}) {
            File main = new File(ROOT, adapter + "/build/classes/java/main");
            assertEquals(adapter, Arrays.asList(), references(classes(main, "com/snoworca/naruserve/"),
                    PKG + "routing/", PKG + "internal/Dispatcher"));
        }
    }
}
