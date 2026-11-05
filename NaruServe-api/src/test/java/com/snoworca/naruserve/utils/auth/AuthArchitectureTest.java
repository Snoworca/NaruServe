package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Architecture checks over the compiled utils classes of the NaruServe-api main jar (CON-AUTH-002, CON-AUTH-003). */
public class AuthArchitectureTest {

    private static final String UTILS = "com/snoworca/naruserve/utils/";

    /** One parsed class file: its name, major version, referenced class names and UTF-8 constants. */
    static final class ClassInfo {
        String name;
        int major;
        final TreeSet<String> refs = new TreeSet<>();
        final List<String> utf8 = new ArrayList<>();
    }

    private static final Pattern DESCRIPTOR = Pattern.compile("L([^;<>()\\[\\]]+);");

    /** The same constant-pool scan as the build's checkClassReferences: class constants and L...; descriptors. */
    static ClassInfo parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
        ClassInfo info = new ClassInfo();
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        info.major = in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Map<Integer, String> utf8 = new LinkedHashMap<>();
        Map<Integer, Integer> classes = new LinkedHashMap<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: utf8.put(i, in.readUTF()); break;
                case 7: classes.put(i, in.readUnsignedShort()); break;
                case 8: case 16: case 19: case 20: in.skipBytes(2); break;
                case 15: in.skipBytes(3); break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: in.skipBytes(4); break;
                case 5: case 6: in.skipBytes(8); i++; break;
                default: throw new IOException("unknown constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();
        info.name = utf8.get(classes.get(in.readUnsignedShort()));
        for (Integer idx : classes.values()) {
            info.refs.add(utf8.get(idx));
        }
        for (String s : utf8.values()) {
            info.utf8.add(s);
            Matcher m = DESCRIPTOR.matcher(s);
            while (m.find()) {
                info.refs.add(m.group(1));
            }
        }
        return info;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static File apiJar() {
        String path = System.getProperty("naruserve.apiJar");
        assertNotNull("naruserve.apiJar", path);
        return new File(path);
    }

    /** Every class file of the main jar under com/snoworca/naruserve/utils/, package-info included. */
    static List<ClassInfo> utilsClasses() throws IOException {
        List<ClassInfo> out = new ArrayList<>();
        try (JarFile jar = new JarFile(apiJar())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.getName().startsWith(UTILS) && e.getName().endsWith(".class")) {
                    try (InputStream in = jar.getInputStream(e)) {
                        out.add(parse(readAll(in)));
                    }
                }
            }
        }
        assertFalse("no utils classes in " + apiJar(), out.isEmpty());
        return out;
    }

    static List<String> violations(List<ClassInfo> classes, List<String> forbiddenPrefixes) {
        List<String> hits = new ArrayList<>();
        for (ClassInfo c : classes) {
            for (String ref : c.refs) {
                for (String p : forbiddenPrefixes) {
                    if (ref.startsWith(p)) {
                        hits.add(c.name + " -> " + ref);
                    }
                }
            }
        }
        return hits;
    }

    private static ClassInfo testClass(Class<?> c) throws IOException {
        try (InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
            return parse(readAll(in));
        }
    }

    private static ClassInfo utilsClass(String name) throws IOException {
        for (ClassInfo c : utilsClasses()) {
            if (c.name.equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no class " + name + " in the api jar");
    }

    // SEC-TOKEN-007 AC-6: no utils.auth class uses SecretKeyFactory ("PBKDF2WithHmacSHA256" is API 26+)
    @Test
    public void SEC_TOKEN_007_AC6_noSecretKeyFactory() throws IOException {
        List<ClassInfo> classes = utilsClasses();
        assertEquals(Collections.emptyList(), violations(classes,
                Collections.singletonList("javax/crypto/SecretKeyFactory")));
        for (ClassInfo c : classes) {
            for (String u : c.utf8) {
                assertFalse(c.name, u.contains("PBKDF2WithHmacSHA256"));
            }
        }
    }

    // SEC-TOKEN-007 AC-3 and SEC-TOKEN-008 AC-7: both hashers compare derived keys through ConstantTime
    @Test
    public void SEC_TOKEN_007_AC3_SEC_TOKEN_008_AC7_hashersCompareInConstantTime() throws IOException {
        String ct = "com/snoworca/naruserve/utils/auth/internal/ConstantTime";
        assertTrue(utilsClass("com/snoworca/naruserve/utils/auth/password/Pbkdf2PasswordHasher").refs.contains(ct));
        assertTrue(utilsClass("com/snoworca/naruserve/utils/auth/password/BcryptPasswordHasher").refs.contains(ct));
        for (String hasher : new String[] {"Pbkdf2PasswordHasher", "BcryptPasswordHasher"}) {
            ClassInfo c = utilsClass("com/snoworca/naruserve/utils/auth/password/" + hasher);
            assertFalse(hasher, c.refs.contains("java/security/MessageDigest"));
            assertFalse(hasher, c.utf8.contains("isEqual"));
        }
    }

    // SEC-TOKEN-004 AC-5 and SEC-TOKEN-007 AC-2: the generated key file bytes and the password salt come from
    // SecureRandom, and neither class references the predictable java.util.Random
    @Test
    public void SEC_TOKEN_004_AC5_SEC_TOKEN_007_AC2_keyAndSaltBytesComeFromSecureRandom() throws IOException {
        for (String name : new String[] {"com/snoworca/naruserve/utils/auth/KeySources",
            "com/snoworca/naruserve/utils/auth/password/Pbkdf2PasswordHasher"}) {
            ClassInfo c = utilsClass(name);
            assertTrue(name, c.refs.contains("java/security/SecureRandom"));
            assertFalse(name, c.refs.contains("java/util/Random"));
        }
    }

    // SEC-TOKEN-002 AC-7: no utils.auth class references Gson or the app JsonCodec; JWT parts and auth files go
    // through MiniJson
    @Test
    public void SEC_TOKEN_002_AC7_noGsonOrJsonCodecInAuth() throws IOException {
        assertEquals(Collections.emptyList(), violations(utilsClasses(),
                Arrays.asList("com/google/gson/", "com/snoworca/naruserve/spi/JsonCodec")));
    }

    // CON-IAM-001 AC-5: the api jar's auth classes do not reference the Kotlin stdlib
    @Test
    public void CON_IAM_001_AC5_noKotlinStdlib() throws IOException {
        assertEquals(Collections.emptyList(), violations(utilsClasses(), Collections.singletonList("kotlin/")));
    }

    // CON-AUTH-002 AC-1: no utils class references com.snoworca.naruserve.internal
    @Test
    public void CON_AUTH_002_AC1_utilsClassesReferenceNoCoreInternal() throws IOException {
        List<ClassInfo> classes = utilsClasses();
        assertEquals(Collections.emptyList(),
                violations(classes, Collections.singletonList("com/snoworca/naruserve/internal/")));
        // the auth-private internal package is not the Core one and is allowed
        boolean authInternal = false;
        for (ClassInfo c : classes) {
            authInternal |= c.name.startsWith("com/snoworca/naruserve/utils/auth/internal/");
        }
        assertTrue(authInternal);
    }

    // CON-AUTH-002 AC-2: the same check fails on a utils.auth fixture that references StandardErrors
    @Test
    public void CON_AUTH_002_AC2_negativeControlFixtureFails() throws IOException {
        ClassInfo fixture = testClass(InternalReferenceFixture.class);
        assertEquals("com/snoworca/naruserve/utils/auth/InternalReferenceFixture", fixture.name);
        assertEquals(Collections.singletonList(
                "com/snoworca/naruserve/utils/auth/InternalReferenceFixture -> com/snoworca/naruserve/internal/StandardErrors"),
                violations(Collections.singletonList(fixture),
                        Collections.singletonList("com/snoworca/naruserve/internal/")));
    }

    // CON-AUTH-002 AC-4: no utils class reaches a non-public Core member by reflection
    @Test
    public void CON_AUTH_002_AC4_noSetAccessibleInUtils() throws IOException {
        for (ClassInfo c : utilsClasses()) {
            assertFalse(c.name, c.utf8.contains("setAccessible"));
            assertFalse(c.name, c.utf8.contains("getDeclaredField"));
            assertFalse(c.name, c.utf8.contains("getDeclaredMethod"));
        }
        // and the Core accessors auth reads are public API
        for (String name : new String[] {"registrations", "socketAddress", "requestId", "trustProxy", "trustProxyHops",
                "trustProxyCidrs"}) {
            boolean found = false;
            for (Class<?> owner : new Class<?>[] {com.snoworca.naruserve.RestApp.class,
                    com.snoworca.naruserve.Request.class}) {
                for (Method m : owner.getMethods()) {
                    found |= m.getName().equals(name) && Modifier.isPublic(m.getModifiers())
                            && m.getParameterCount() == 0 && m.getReturnType() != void.class;
                }
            }
            assertTrue(name, found);
        }
    }

    // CON-AUTH-003 AC-1 (class side): no utils class is exempted from the API 21 check
    @Test
    public void CON_AUTH_003_AC1_noAnimalSnifferExemptionInUtils() throws IOException {
        assertEquals(Collections.emptyList(),
                violations(utilsClasses(), Collections.singletonList("org/codehaus/mojo/animal_sniffer/")));
    }

    // CON-AUTH-003 AC-2: no java.time, java.nio.file, Base64, CompletableFuture, java.util.function or java.net.http
    @Test
    public void CON_AUTH_003_AC2_noApiAboveLevel21() throws IOException {
        List<String> forbidden = Arrays.asList("java/time/", "java/nio/file/", "java/util/Base64",
                "java/util/concurrent/CompletableFuture", "java/util/function/", "java/net/http/", "javax/naming/");
        assertEquals(Collections.emptyList(), violations(utilsClasses(), forbidden));
        ClassInfo fixture = testClass(InternalReferenceFixture.class);
        assertEquals(Collections.singletonList(
                "com/snoworca/naruserve/utils/auth/InternalReferenceFixture -> java/util/function/Supplier"),
                violations(Collections.singletonList(fixture), forbidden));
    }

    // CON-AUTH-003 AC-4: java.text.Normalizer and FileDescriptor.sync() are referenced by auth classes (and the build's
    // API 21 animal-sniffer check over them passes); no utils class references java.sql or javax.sql (auth persistence
    // is SPI-only, CON-IAM-003), and javax.naming is referenced by none
    @Test
    public void CON_AUTH_003_AC4_openApiChecksSettled() throws IOException {
        TreeSet<String> refs = new TreeSet<>();
        boolean sync = false;
        for (ClassInfo c : utilsClasses()) {
            refs.addAll(c.refs);
            sync |= c.refs.contains("java/io/FileDescriptor") && c.utf8.contains("sync");
        }
        assertTrue("java.text.Normalizer", refs.contains("java/text/Normalizer"));
        assertTrue("FileDescriptor.sync", sync);
        assertEquals(Collections.emptyList(),
                violations(utilsClasses(), Arrays.asList("java/sql/", "javax/sql/", "javax/naming/")));
    }

    // CON-AUTH-003 AC-3: every utils class is Java 8 bytecode (major 52)
    @Test
    public void CON_AUTH_003_AC3_majorVersion52() throws IOException {
        for (ClassInfo c : utilsClasses()) {
            assertEquals(c.name, 52, c.major);
        }
    }

    // REL-IAM-004 AC-5: AtomicFiles uses java.io only, no java.nio.file
    @Test
    public void REL_IAM_004_AC5_atomicFilesUseNoNioFile() throws IOException {
        int seen = 0;
        boolean syncs = false;
        for (ClassInfo c : utilsClasses()) {
            if (c.name.startsWith("com/snoworca/naruserve/utils/auth/internal/AtomicFiles")) {
                seen++;
                assertEquals(c.name, Collections.emptyList(),
                        violations(Collections.singletonList(c), Collections.singletonList("java/nio/file/")));
                syncs |= c.refs.contains("java/io/FileDescriptor") && c.utf8.contains("sync");
            }
        }
        assertTrue(seen >= 1);
        assertTrue("AtomicFiles syncs through java.io.FileDescriptor", syncs);
    }

    private static final String INTERNAL = "com.snoworca.naruserve.utils.auth.internal.";

    private static void noInternal(List<String> hits, String where, Type... types) {
        for (Type t : types) {
            if (t.getTypeName().contains(INTERNAL)) {
                hits.add(where + " exposes " + t.getTypeName());
            }
        }
    }

    // CON-AUTH-006 AC-5: no public signature of the public auth packages names a utils.auth.internal type
    @Test
    public void CON_AUTH_006_AC5_internalTypesStayOutOfPublicSignatures() throws Exception {
        List<String> hits = new ArrayList<>();
        int types = 0;
        for (ClassInfo c : utilsClasses()) {
            String name = c.name.replace('/', '.');
            if (name.startsWith(INTERNAL) || name.endsWith("package-info")) {
                continue;
            }
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            if (!Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            types++;
            noInternal(hits, name + " supertype", type.getGenericInterfaces());
            if (type.getGenericSuperclass() != null) {
                noInternal(hits, name + " superclass", type.getGenericSuperclass());
            }
            for (Method m : type.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers())) {
                    noInternal(hits, name + "." + m.getName(), m.getGenericReturnType());
                    noInternal(hits, name + "." + m.getName(), m.getGenericParameterTypes());
                    noInternal(hits, name + "." + m.getName(), m.getGenericExceptionTypes());
                }
            }
            for (Constructor<?> k : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(k.getModifiers()) || Modifier.isProtected(k.getModifiers())) {
                    noInternal(hits, name + ".<init>", k.getGenericParameterTypes());
                }
            }
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isPublic(f.getModifiers()) || Modifier.isProtected(f.getModifiers())) {
                    noInternal(hits, name + "." + f.getName(), f.getGenericType());
                }
            }
        }
        assertEquals(Collections.emptyList(), hits);
        assertTrue("public auth types scanned: " + types, types >= 10);
    }
}
