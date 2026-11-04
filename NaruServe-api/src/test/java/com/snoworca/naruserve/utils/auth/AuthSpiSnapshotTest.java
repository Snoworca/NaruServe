package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
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
import org.junit.Test;

/**
 * The binary-compatibility record of the user-implemented auth interfaces (CON-AUTH-006 AC-4): after the first
 * public release they gain default methods only. The record is the resource auth-spi-signatures.txt.
 */
public class AuthSpiSnapshotTest {

    /** The interfaces CON-AUTH-006 lists by name; every public interface of utils.auth.spi is added from the jar. */
    static final List<String> LISTED = Collections.unmodifiableList(Arrays.asList(
            "com.snoworca.naruserve.utils.auth.user.UserStore",
            "com.snoworca.naruserve.utils.auth.group.GroupStore",
            "com.snoworca.naruserve.utils.auth.group.GroupAdminStore",
            "com.snoworca.naruserve.utils.auth.rbac.RoleStore",
            "com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore",
            "com.snoworca.naruserve.utils.auth.audit.AuditSink",
            "com.snoworca.naruserve.utils.auth.AuthEvents",
            "com.snoworca.naruserve.utils.auth.password.PasswordHasher"));

    private static String types(Type[] ts) {
        StringBuilder b = new StringBuilder();
        for (Type t : ts) {
            b.append(b.length() == 0 ? "" : ", ").append(t.getTypeName());
        }
        return b.toString();
    }

    /** One line per declared method: owner, kind, name, parameters, return type and throws clause. */
    static List<String> lines(Class<?> type, String owner) {
        List<String> out = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (m.isSynthetic() || !Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            String kind = Modifier.isStatic(m.getModifiers()) ? "static" : m.isDefault() ? "default" : "abstract";
            String line = owner + " " + kind + " " + m.getName() + "(" + types(m.getGenericParameterTypes()) + ") "
                    + m.getGenericReturnType().getTypeName();
            if (m.getGenericExceptionTypes().length > 0) {
                line += " throws " + types(m.getGenericExceptionTypes());
            }
            out.add(line);
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Failures of {@code current} (owner name to its lines) against the {@code record}: a recorded line that is gone
     * or changed, an abstract line that is not recorded, and an interface with no recorded line at all.
     */
    static List<String> failures(List<String> record, Map<String, List<String>> current) {
        TreeSet<String> out = new TreeSet<>();
        TreeSet<String> now = new TreeSet<>();
        for (List<String> ls : current.values()) {
            now.addAll(ls);
        }
        for (String r : record) {
            String owner = r.substring(0, r.indexOf(' '));
            if (!now.contains(r)) {
                out.add((current.containsKey(owner) ? "removed or changed: " : "interface gone: ") + r);
            }
        }
        for (Map.Entry<String, List<String>> e : current.entrySet()) {
            boolean recorded = false;
            for (String r : record) {
                recorded |= r.startsWith(e.getKey() + " ");
            }
            if (!recorded) {
                out.add("unrecorded interface: " + e.getKey());
            }
            for (String l : e.getValue()) {
                if (l.startsWith(e.getKey() + " abstract ") && !record.contains(l)) {
                    out.add("new abstract method: " + l);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> record() throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = AuthSpiSnapshotTest.class.getResourceAsStream("auth-spi-signatures.txt")) {
            assertNotNull("auth-spi-signatures.txt", in);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String l; (l = r.readLine()) != null; ) {
                if (!l.trim().isEmpty() && !l.startsWith("#")) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    /** The listed interfaces that exist so far plus every public interface of utils.auth.spi in the main jar. */
    private static Map<String, List<String>> current() throws Exception {
        List<String> names = new ArrayList<>(LISTED);
        String path = System.getProperty("naruserve.apiJar");
        assertNotNull("naruserve.apiJar", path);
        try (JarFile jar = new JarFile(new File(path))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String e = entries.nextElement().getName();
                String spi = "com/snoworca/naruserve/utils/auth/spi/";
                if (e.startsWith(spi) && e.endsWith(".class") && e.indexOf('/', spi.length()) < 0
                        && !e.endsWith("package-info.class")) {
                    names.add(e.substring(0, e.length() - 6).replace('/', '.'));
                }
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String n : names) {
            Class<?> c;
            try {
                c = Class.forName(n, false, AuthSpiSnapshotTest.class.getClassLoader());
            } catch (ClassNotFoundException notYet) {
                continue; // introduced by a later 0.11.0 requirement; it must be recorded when it lands
            }
            if (c.isInterface() && Modifier.isPublic(c.getModifiers())) {
                out.put(c.getName(), lines(c, c.getName()));
            }
        }
        return out;
    }

    // CON-AUTH-006 AC-4: the committed record matches the user-implemented interfaces of this build
    @Test
    public void CON_AUTH_006_AC4_recordMatchesTheUserImplementedInterfaces() throws Exception {
        Map<String, List<String>> current = current();
        assertTrue(current.keySet().toString(), current.keySet().containsAll(Arrays.asList(
                "com.snoworca.naruserve.utils.auth.AuthEvents", "com.snoworca.naruserve.utils.auth.group.GroupStore",
                "com.snoworca.naruserve.utils.auth.rbac.RoleStore")));
        List<String> all = new ArrayList<>();
        for (List<String> ls : current.values()) {
            all.addAll(ls);
        }
        assertEquals("record:\n" + String.join("\n", all), Collections.emptyList(), failures(record(), current));
    }

    /** The recorded fixture shape and its later edits. */
    interface Base {
        void a(String x) throws Exception;

        default int b() {
            return 0;
        }
    }

    interface AddedAbstract {
        void a(String x) throws Exception;

        default int b() {
            return 0;
        }

        void c();
    }

    interface AddedDefault {
        void a(String x) throws Exception;

        default int b() {
            return 0;
        }

        default void c() {
        }
    }

    interface ChangedSignature {
        void a(Object x) throws Exception;

        default int b() {
            return 0;
        }
    }

    interface RemovedDefault {
        void a(String x) throws Exception;
    }

    private static List<String> against(Class<?> edited) {
        return failures(lines(Base.class, "Fixture"), Collections.singletonMap("Fixture", lines(edited, "Fixture")));
    }

    // CON-AUTH-006 AC-4: negative controls; an added abstract method or a changed or removed method fails
    @Test
    public void CON_AUTH_006_AC4_addedAbstractFailsAndAddedDefaultPasses() {
        assertEquals(Collections.emptyList(), against(Base.class));
        assertEquals(Collections.emptyList(), against(AddedDefault.class));
        assertEquals(Collections.singletonList("new abstract method: Fixture abstract c() void"),
                against(AddedAbstract.class));
        List<String> changed = against(ChangedSignature.class);
        assertTrue(changed.toString(), changed.contains(
                "removed or changed: Fixture abstract a(java.lang.String) void throws java.lang.Exception"));
        assertTrue(changed.toString(), changed.contains(
                "new abstract method: Fixture abstract a(java.lang.Object) void throws java.lang.Exception"));
        assertEquals(Collections.singletonList("removed or changed: Fixture default b() int"),
                against(RemovedDefault.class));
        assertFalse(failures(Collections.<String>emptyList(),
                Collections.singletonMap("Fixture", lines(Base.class, "Fixture"))).isEmpty());
    }
}
