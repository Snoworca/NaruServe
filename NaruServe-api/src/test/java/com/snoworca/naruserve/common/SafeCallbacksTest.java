package com.snoworca.naruserve.common;

import org.junit.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Fail-soft resource notification boundary; callback observations are asserted by the test thread. */
public class SafeCallbacksTest {
    private static Class<?> utility() throws Exception {
        return Class.forName("com.snoworca.naruserve.common.SafeCallbacks");
    }
    private static void run(Runnable callback) throws Exception {
        try { utility().getMethod("run",Runnable.class).invoke(null,callback); }
        catch(InvocationTargetException failure) {
            Throwable cause=failure.getCause();
            if(cause instanceof Exception)throw (Exception)cause;
            throw (Error)cause;
        }
    }
    @Test public void focusedPublicUtilityInvokesSynchronouslyExactlyOnce() throws Exception { // IR-HTTP-009 AC-3
        Class<?> type=utility();assertTrue(Modifier.isPublic(type.getModifiers()));assertTrue(Modifier.isFinal(type.getModifiers()));
        Constructor<?>[] constructors=type.getDeclaredConstructors();assertEquals(1,constructors.length);assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));
        Method method=type.getDeclaredMethod("run",Runnable.class);assertTrue(Modifier.isPublic(method.getModifiers()));assertTrue(Modifier.isStatic(method.getModifiers()));assertEquals(void.class,method.getReturnType());
        Thread caller=Thread.currentThread();AtomicReference<Thread> actual=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();
        List<String> trace=new ArrayList<>();trace.add("before");run(()->{actual.set(Thread.currentThread());calls.incrementAndGet();trace.add("callback");});trace.add("after");
        assertSame(caller,actual.get());assertEquals(1,calls.get());assertEquals(Arrays.asList("before","callback","after"),trace);
    }
    @Test public void nullIsRejectedRatherThanSilentlyIsolated() throws Exception { // IR-HTTP-009 AC-3
        try {run(null);fail("null callback accepted");} catch(NullPointerException expected) { }
    }
    @Test public void runtimeExceptionAndErrorAreIsolatedBeforeSubsequentCallbacks() throws Exception { // IR-HTTP-009 AC-3
        AtomicInteger calls=new AtomicInteger();List<String> trace=new ArrayList<>();
        run(()->{calls.incrementAndGet();trace.add("runtime");throw new IllegalStateException("resource notification");});
        run(()->{calls.incrementAndGet();trace.add("error");throw new AssertionError("resource notification");});
        run(()->{calls.incrementAndGet();trace.add("continued");});
        assertEquals(3,calls.get());assertEquals(Arrays.asList("runtime","error","continued"),trace);
    }
    @Test(timeout=5000) public void callbacksCanReenterAndJoinAnotherCallerWithoutSharedLock() throws Exception { // IR-HTTP-009 AC-3
        ExecutorService worker=Executors.newSingleThreadExecutor();AtomicInteger calls=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
        try {
            run(()->{try {
                run(calls::incrementAndGet);
                worker.submit(()->{try {run(calls::incrementAndGet);}catch(Exception problem){throw new AssertionError(problem);}}).get(1,TimeUnit.SECONDS);
            }catch(Throwable problem){failure.set(problem);}});
            assertNull("reentry or joined callback failed",failure.get());assertEquals(2,calls.get());
        }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test public void callbackDoesNotOwnUtilityClassMonitor() throws Exception { // IR-HTTP-009 AC-3
        Class<?> type=utility();AtomicBoolean held=new AtomicBoolean(true);run(()->held.set(Thread.holdsLock(type)));assertFalse(held.get());
    }
}
