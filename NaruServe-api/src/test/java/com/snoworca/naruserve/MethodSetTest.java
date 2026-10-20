package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import org.junit.Test;

public class MethodSetTest {

    private static List<Method> members(MethodSet s) {
        List<Method> out = new ArrayList<>();
        for (Method m : s) {
            out.add(m);
        }
        return out;
    }

    // SDS-AC-3; IR-ROUTE-002 AC-4
    @Test
    public void sdsAc3_IR_ROUTE_002_AC4_iteratorRemoveThrowsAndSetUnchanged() {
        MethodSet s = Method.get().post();
        Iterator<Method> it = s.iterator();
        it.next();
        try {
            it.remove();
            fail("remove must throw");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        assertEquals(Arrays.asList(Method.GET, Method.POST), members(s));
    }

    // SDS-AC-3; IR-ROUTE-002 AC-4
    @Test
    public void sdsAc3_IR_ROUTE_002_AC4_noPublicConstructorAndOnlyFinalFields() {
        assertTrue(Modifier.isFinal(MethodSet.class.getModifiers()));
        assertEquals(0, MethodSet.class.getConstructors().length);
        for (Field f : MethodSet.class.getDeclaredFields()) {
            assertTrue("field " + f.getName(), Modifier.isFinal(f.getModifiers()));
        }
    }

    // SDS-AC-4; IR-ROUTE-002 AC-1
    @Test
    public void sdsAc4_IR_ROUTE_002_AC1_combinatorsReturnNewSetAndLeaveReceiverUnchanged() {
        MethodSet s = Method.get();
        assertEquals(Arrays.asList(Method.GET, Method.POST), members(s.post()));
        assertNotSame(s, s.post());
        assertNotSame(s, s.head());
        assertNotSame(s, s.put());
        assertNotSame(s, s.patch());
        assertNotSame(s, s.delete());
        assertNotSame(s, s.options());
        assertNotSame(s, s.with(Method.TRACE));
        assertNotSame(s, s.except(Method.GET));
        assertEquals(Collections.singletonList(Method.GET), members(s));
    }

    // SDS-AC-4; IR-ROUTE-002 AC-2
    @Test
    public void sdsAc4_IR_ROUTE_002_AC2_eachCombinatorAddsExactlyItsOwnConstant() {
        MethodSet empty = Method.get().except(Method.GET);
        assertEquals(0, empty.size());
        assertEquals(Collections.singletonList(Method.GET), members(empty.get()));
        assertEquals(Collections.singletonList(Method.HEAD), members(empty.head()));
        assertEquals(Collections.singletonList(Method.POST), members(empty.post()));
        assertEquals(Collections.singletonList(Method.PUT), members(empty.put()));
        assertEquals(Collections.singletonList(Method.PATCH), members(empty.patch()));
        assertEquals(Collections.singletonList(Method.DELETE), members(empty.delete()));
        assertEquals(Collections.singletonList(Method.OPTIONS), members(empty.options()));
        assertEquals(Collections.singletonList(Method.CONNECT), members(empty.with(Method.CONNECT)));
        assertEquals(Arrays.asList(Method.GET, Method.POST, Method.DELETE), members(Method.get().delete().post()));
    }

    // SDS-AC-4; IR-ROUTE-002 AC-3
    @Test
    public void sdsAc4_IR_ROUTE_002_AC3_exceptRemovesAndNonMemberGivesEqualSet() {
        assertEquals(Collections.singletonList(Method.GET), members(Method.get().post().except(Method.POST)));
        MethodSet s = Method.get().post();
        assertEquals(s, s.except(Method.DELETE));
        assertEquals(s, s.post());
    }

    // SDS-AC-5; FR-ROUTE-001 AC-1
    @Test
    public void sdsAc5_FR_ROUTE_001_AC1_anyHasSevenMembersWithoutTraceOrConnect() {
        assertEquals(Arrays.asList(Method.GET, Method.HEAD, Method.POST, Method.PUT, Method.PATCH,
                Method.DELETE, Method.OPTIONS), members(Method.any()));
        assertFalse(Method.any().contains(Method.TRACE));
        assertFalse(Method.any().contains(Method.CONNECT));
    }

    // SDS-AC-5; FR-ROUTE-001 AC-2
    @Test
    public void sdsAc5_FR_ROUTE_001_AC2_anyExceptDeleteEquality() {
        assertEquals(Method.of(Method.GET, Method.HEAD, Method.POST, Method.PUT, Method.PATCH, Method.OPTIONS),
                Method.any().except(Method.DELETE));
    }

    // SDS-AC-5; FR-ROUTE-001 AC-3
    // shortcut: RouterPolicy is written by the wave-2-2 lane, so this only pins any() as a constant that
    // naming TRACE/CONNECT elsewhere does not change; add the built-router case once RouterPolicy is on this branch.
    @Test
    public void sdsAc5_FR_ROUTE_001_AC3_anyIsConstantAndTraceConnectOnlyWhenNamed() {
        MethodSet before = Method.any();
        MethodSet named = Method.of(Method.TRACE, Method.CONNECT);
        assertEquals(Arrays.asList(Method.TRACE, Method.CONNECT), members(named));
        assertEquals(before, Method.any());
        assertFalse(Method.any().contains(Method.TRACE));
        assertFalse(Method.any().contains(Method.CONNECT));
    }

    // SDS-AC-6; IR-ROUTE-003 AC-1
    @Test
    public void sdsAc6_IR_ROUTE_003_AC1_ordinalIterationAndContains() {
        MethodSet s = Method.of(Method.DELETE, Method.GET, Method.POST);
        assertEquals(Arrays.asList(Method.GET, Method.POST, Method.DELETE), members(s));
        for (Method m : Method.values()) {
            boolean member = m == Method.GET || m == Method.POST || m == Method.DELETE;
            assertEquals(m.name(), member, s.contains(m));
        }
        assertEquals(3, s.size());
    }

    // SDS-AC-7; IR-ROUTE-003 AC-2
    @Test
    public void sdsAc7_IR_ROUTE_003_AC2_valueEqualityHashCodeAndToString() {
        MethodSet a = Method.get().post();
        MethodSet b = Method.of(Method.POST, Method.GET);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, Method.get());
        assertEquals("GET, POST", a.toString());
        assertEquals("GET, POST, DELETE", Method.of(Method.DELETE, Method.POST, Method.GET).toString());
    }

    // review P1-001; IR-ROUTE-002: Method.<clinit> builds ANY through MethodSet, so MethodSet.<clinit> must never
    // need Method, or two threads first touching one class each deadlock on the class-init locks
    @Test(timeout = 120000)
    public void review_P1_001_firstUseOfMethodAndMethodSetFromTwoThreadsDoesNotDeadlock() throws Exception {
        URL[] classes = {MethodSet.class.getProtectionDomain().getCodeSource().getLocation()};
        for (int round = 0; round < 200; round++) {
            // a null parent so both classes are initialised from scratch in every round
            try (URLClassLoader loader = new URLClassLoader(classes, null)) {
                final CyclicBarrier start = new CyclicBarrier(2);
                final String[] names = {"com.snoworca.naruserve.Method", "com.snoworca.naruserve.MethodSet"};
                Thread[] threads = new Thread[2];
                for (int t = 0; t < 2; t++) {
                    final String name = names[t];
                    final ClassLoader cl = loader;
                    threads[t] = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                start.await();
                                Class.forName(name, true, cl);
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        }
                    }, "init-" + t);
                    threads[t].setDaemon(true);
                    threads[t].start();
                }
                for (Thread t : threads) {
                    t.join(5000);
                    assertFalse("class-init deadlock in round " + round, t.isAlive());
                }
            }
        }
    }
}
