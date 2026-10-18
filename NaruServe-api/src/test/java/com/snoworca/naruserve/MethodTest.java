package com.snoworca.naruserve;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

public class MethodTest {

    private static final List<String> HTTP_NAMES =
            Arrays.asList("get", "head", "post", "put", "patch", "delete", "options", "trace", "connect");

    // SDS-AC-1; IR-ROUTE-001 AC-1
    @Test
    public void sdsAc1_IR_ROUTE_001_AC1_valuesInDeclarationOrder() {
        assertArrayEquals(new Method[] {Method.GET, Method.HEAD, Method.POST, Method.PUT, Method.PATCH,
                Method.DELETE, Method.OPTIONS, Method.TRACE, Method.CONNECT}, Method.values());
    }

    // SDS-AC-1; IR-ROUTE-001 AC-3
    @Test
    public void sdsAc1_IR_ROUTE_001_AC3_onlyStaticGetAnyOfBesideEnumBuiltIns() {
        Set<String> statics = new HashSet<>();
        for (java.lang.reflect.Method m : Method.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            String name = m.getName();
            assertTrue("non-static declared method " + name, Modifier.isStatic(m.getModifiers()));
            statics.add(name);
            if (HTTP_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                assertEquals("HTTP-named static method " + name, "get", name);
            }
        }
        assertEquals(new HashSet<>(Arrays.asList("values", "valueOf", "get", "any", "of")), statics);
        for (java.lang.reflect.Method m : Method.class.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                assertFalse("instance method " + m.getName(),
                        HTTP_NAMES.contains(m.getName().toLowerCase(Locale.ROOT)));
            }
        }
    }

    // SDS-AC-2; IR-ROUTE-001 AC-2
    @Test
    public void sdsAc2_IR_ROUTE_001_AC2_ofRejectsNullArrayAndNullElement() {
        try {
            Method.of((Method[]) null);
            fail("of(null) must throw");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            Method.of(Method.GET, null);
            fail("of(GET, null) must throw");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    // SDS-AC-2; IR-ROUTE-001 AC-2
    @Test
    public void sdsAc2_IR_ROUTE_001_AC2_factoriesReturnExactMembers() {
        MethodSet putPatch = Method.of(Method.PUT, Method.PATCH);
        assertEquals(2, putPatch.size());
        assertTrue(putPatch.contains(Method.PUT));
        assertTrue(putPatch.contains(Method.PATCH));
        MethodSet get = Method.get();
        assertEquals(1, get.size());
        assertTrue(get.contains(Method.GET));
        assertEquals(7, Method.any().size());
    }
}
