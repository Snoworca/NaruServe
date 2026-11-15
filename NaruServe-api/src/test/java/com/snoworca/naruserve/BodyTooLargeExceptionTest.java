package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Test;

/**
 * The over-limit body exception lives in the public package. The class is looked up by name so this test also
 * describes a jar built before the move.
 */
public class BodyTooLargeExceptionTest {

    private static final String PUBLIC_NAME = "com.snoworca.naruserve.BodyTooLargeException";
    private static final String INTERNAL_NAME = "com.snoworca.naruserve.internal.BodyTooLargeException";

    private static Class<?> publicClass(ClassLoader loader) {
        try {
            return Class.forName(PUBLIC_NAME, false, loader);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(PUBLIC_NAME + " is missing from " + loader, e);
        }
    }

    private static void assertShape(Class<?> type) throws Exception {
        int mods = type.getModifiers();
        assertTrue(type + " public", Modifier.isPublic(mods));
        assertTrue(type + " final", Modifier.isFinal(mods));
        assertSame(IOException.class, type.getSuperclass());
        Constructor<?> ctor = type.getDeclaredConstructor(long.class);
        assertTrue("(long limit) is public", Modifier.isPublic(ctor.getModifiers()));
        Method limit = type.getDeclaredMethod("limit");
        assertTrue("limit() is public", Modifier.isPublic(limit.getModifiers()));
        assertSame(long.class, limit.getReturnType());
        for (long value : new long[] {1L, 10L, 1048576L, Long.MAX_VALUE}) {
            Object e = ctor.newInstance(value);
            assertEquals(value, ((Long) limit.invoke(e)).longValue());
            assertTrue(((IOException) e).getMessage(), ((IOException) e).getMessage().contains(String.valueOf(value)));
        }
    }

    // IR-HTTP-011 AC-1: public, final, a direct IOException subclass with a public (long limit) and long limit()
    @Test
    public void IR_HTTP_011_AC1_publicFinalIOExceptionWithLimitConstructorAndAccessor() throws Exception {
        assertShape(publicClass(BodyTooLargeExceptionTest.class.getClassLoader()));
        assertShape(publicClass(ApiJarClasses.loader()));
    }

    // IR-HTTP-011 AC-2: the internal class is gone, from the class path and from the NaruServe-api jar
    @Test
    public void IR_HTTP_011_AC2_internalClassIsGoneWithNoAliasInTheJar() throws Exception {
        for (ClassLoader loader : new ClassLoader[] {BodyTooLargeExceptionTest.class.getClassLoader(),
                ApiJarClasses.loader()}) {
            try {
                Class.forName(INTERNAL_NAME, false, loader);
                fail(INTERNAL_NAME + " still loads from " + loader);
            } catch (ClassNotFoundException expected) {
                // moved, no alias
            }
        }
        String jarPath = System.getProperty("naruserve.apiJar");
        assertNotNull("naruserve.apiJar", jarPath);
        Class<?> moved = publicClass(ApiJarClasses.loader());
        List<String> sameName = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        try (JarFile jar = new JarFile(new File(jarPath))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (!entry.endsWith(".class") || entry.endsWith("package-info.class")
                        || entry.equals("module-info.class")) {
                    continue;
                }
                String name = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
                String simple = name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
                if (simple.equals("BodyTooLargeException") && !name.equals(PUBLIC_NAME)) {
                    sameName.add(name);
                }
                if (!name.equals(PUBLIC_NAME)
                        && moved.isAssignableFrom(Class.forName(name, false, ApiJarClasses.loader()))) {
                    aliases.add(name);
                }
            }
        }
        assertEquals(Collections.emptyList(), sameName);
        assertEquals(Collections.emptyList(), aliases);
    }

    // IR-HTTP-011 AC-5: the nullability reflection check of FR-KT-001 finds no @NonNull on the moved class
    @Test
    public void IR_HTTP_011_AC5_noNonNullOnTheMovedClass() {
        Class<?> moved = publicClass(ApiJarClasses.loader());
        assertTrue(PUBLIC_NAME + " enumerated as public API", ApiJarClasses.publicTypes().contains(moved));
        assertEquals(Collections.emptyList(),
                KotlinContractChecks.nonNullFailures(Collections.<Class<?>>singletonList(moved)));
    }
}
