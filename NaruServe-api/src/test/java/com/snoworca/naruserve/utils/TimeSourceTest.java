package com.snoworca.naruserve.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.ApiJarClassesAccess;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The TimeSource SAM and epoch-millisecond auth timestamps (IR-AUTH-001). */
public class TimeSourceTest {

    // IR-AUTH-001 AC-1 (Java side; the Kotlin lambda is in AuthFacadeKotlinTest): a public @FunctionalInterface with
    // exactly one abstract method long nowMillis()
    @Test
    public void IR_AUTH_001_AC1_samShape() {
        assertTrue(TimeSource.class.isAnnotationPresent(FunctionalInterface.class));
        assertTrue(Modifier.isPublic(TimeSource.class.getModifiers()));
        int abstracts = 0;
        for (Method m : TimeSource.class.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) {
                abstracts++;
                assertEquals("nowMillis", m.getName());
                assertEquals(long.class, m.getReturnType());
                assertEquals(0, m.getParameterCount());
            }
        }
        assertEquals(1, abstracts);
        TimeSource fixed = () -> 1_000L;
        assertEquals(1000L, fixed.nowMillis());
    }

    // IR-AUTH-001 AC-2: the system source follows System.currentTimeMillis within a second
    @Test
    public void IR_AUTH_001_AC2_systemSource() {
        long before = System.currentTimeMillis();
        long now = TimeSource.system().nowMillis();
        long after = System.currentTimeMillis();
        assertTrue(now >= before - 1000 && now <= after + 1000);
    }

    private static void check(List<String> hits, String where, Type t) {
        String n = t.getTypeName();
        if (n.contains("java.time.") || n.contains("java.util.Date")) {
            hits.add(where + " uses " + n);
        }
    }

    // IR-AUTH-001 AC-3: no public type of utils and utils.auth.* has a java.time or java.util.Date in a signature
    @Test
    public void IR_AUTH_001_AC3_noJavaTimeInPublicSignatures() {
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        for (Class<?> c : ApiJarClassesAccess.publicTypes()) {
            if (!c.getName().startsWith("com.snoworca.naruserve.utils.")) {
                continue;
            }
            scanned++;
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) && !Modifier.isProtected(m.getModifiers())) {
                    continue;
                }
                check(hits, c.getName() + "." + m.getName(), m.getGenericReturnType());
                for (Type p : m.getGenericParameterTypes()) {
                    check(hits, c.getName() + "." + m.getName(), p);
                }
            }
            for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                for (Type p : k.getGenericParameterTypes()) {
                    check(hits, c.getName() + ".<init>", p);
                }
            }
        }
        assertEquals(new ArrayList<String>(), hits);
        assertTrue("public utils types scanned: " + scanned, scanned >= 20);
    }
}
