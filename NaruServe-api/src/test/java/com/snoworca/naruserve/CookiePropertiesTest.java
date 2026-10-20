package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.Test;

/** Cookie follows the getX, setX and isX rules, so Kotlin reads its accessors as properties (IR-KT-001). */
public class CookiePropertiesTest {

    private static final String READ_ONLY = " (read-only)";

    /** A setter whose type differs from its getter, so it forms no property. */
    public static final class Mismatch {
        public long getSize() {
            return 0;
        }

        public void setSize(int size) {
        }
    }

    /** A boolean setter with only a non-boolean isX, which is no getter. */
    public static final class NotBooleanIs {
        public String isReady() {
            return "";
        }

        public void setReady(boolean ready) {
        }
    }

    private static String decapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static List<Method> accessors(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isPublic(mod) && !Modifier.isStatic(mod) && !m.isSynthetic()) {
                methods.add(m);
            }
        }
        return methods;
    }

    /** Getter of a property: getX() of any type, or isX() of boolean only. */
    private static Map<String, Method> getters(Class<?> type) {
        Map<String, Method> getters = new TreeMap<>();
        for (Method m : accessors(type)) {
            String n = m.getName();
            if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
                continue;
            }
            if (n.startsWith("get") && n.length() > 3) {
                getters.put(decapitalize(n.substring(3)), m);
            } else if (n.startsWith("is") && n.length() > 2 && m.getReturnType() == boolean.class) {
                getters.put(decapitalize(n.substring(2)), m);
            }
        }
        return getters;
    }

    /**
     * Property name (prefix get, is or set removed, first letter lowercased) to type name, with {@code (read-only)}
     * appended for a getter without a setter; fails naming the property when a setX has no getX or isX of its type.
     */
    // IR-KT-001 AC-1, IR-KT-001 AC-4
    static Map<String, String> properties(Class<?> type) {
        Map<String, Method> getters = getters(type);
        Map<String, String> properties = new TreeMap<>();
        TreeSet<String> failures = new TreeSet<>();
        for (Method m : accessors(type)) {
            String n = m.getName();
            if (!n.startsWith("set") || n.length() <= 3 || m.getParameterCount() != 1) {
                continue;
            }
            String name = decapitalize(n.substring(3));
            Class<?> t = m.getParameterTypes()[0];
            Method getter = getters.get(name);
            if (getter == null || getter.getReturnType() != t) {
                failures.add("property " + name + ": " + n + "(" + t.getName() + ") has no getter of that type");
            } else {
                properties.put(name, t.getName());
            }
        }
        if (!failures.isEmpty()) {
            fail(String.join("; ", failures));
        }
        for (Map.Entry<String, Method> e : getters.entrySet()) {
            if (!properties.containsKey(e.getKey())) {
                properties.put(e.getKey(), e.getValue().getReturnType().getName() + READ_ONLY);
            }
        }
        return properties;
    }

    /** The name Kotlin gives each property: isX for an isX getter, otherwise the property name. */
    private static List<String> kotlinNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Method> e : getters(type).entrySet()) {
            names.add(e.getValue().getName().startsWith("is") ? e.getValue().getName() : e.getKey());
        }
        return names;
    }

    private static String failureOf(Class<?> type) {
        try {
            properties(type);
        } catch (AssertionError e) {
            return e.getMessage();
        }
        return "";
    }

    // SDS-AC-9; IR-KT-001 AC-1
    @Test
    public void sdsAc9_IR_KT_001_AC1_everyCookieSetterHasAGetterOfTheSameType() {
        assertEquals("", failureOf(Cookie.class));
    }

    // SDS-AC-9; IR-KT-001 AC-1
    @Test
    public void sdsAc9_IR_KT_001_AC1_mismatchedOrNonBooleanIsGetterNamesTheProperty() {
        assertEquals("property size: setSize(int) has no getter of that type", failureOf(Mismatch.class));
        assertEquals("property ready: setReady(boolean) has no getter of that type", failureOf(NotBooleanIs.class));
    }

    // SDS-AC-12; IR-KT-001 AC-4
    @Test
    public void sdsAc12_IR_KT_001_AC4_cookiePropertiesAreExactlyTheListedEight() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("domain", "java.lang.String");
        expected.put("httpOnly", "boolean");
        expected.put("maxAge", "int");
        expected.put("name", "java.lang.String" + READ_ONLY);
        expected.put("path", "java.lang.String");
        expected.put("sameSite", SameSite.class.getName());
        expected.put("secure", "boolean");
        expected.put("value", "java.lang.String");
        assertEquals(expected, properties(Cookie.class));
        List<String> kotlin = kotlinNames(Cookie.class);
        Collections.sort(kotlin);
        assertEquals(Arrays.asList("domain", "isHttpOnly", "maxAge", "name", "path", "sameSite", "secure",
                "value"), kotlin);
    }
}
