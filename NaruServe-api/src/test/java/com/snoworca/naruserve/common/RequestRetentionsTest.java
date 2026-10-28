package com.snoworca.naruserve.common;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** FR-APP-023 AC-8 standalone counting contract; core/adapters own terminal-state and transport integration tests. */
public class RequestRetentionsTest {
    private static Class<?> helper() {
        try { return Class.forName("com.snoworca.naruserve.common.RequestRetentions"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("FR-APP-023 AC-8 requires common RequestRetentions", missing); }
    }
    private static Object registry(Runnable callback) throws Exception {
        try { return helper().getConstructor(Runnable.class).newInstance(callback); }
        catch (InvocationTargetException e) { return rethrow(e.getCause()); }
    }
    private static Object call(Object registry,String method) throws Exception {
        try { return helper().getMethod(method).invoke(registry); }
        catch (InvocationTargetException e) { return rethrow(e.getCause()); }
    }
    private static Object rethrow(Throwable cause) throws Exception {
        if(cause instanceof Exception)throw(Exception)cause;throw(Error)cause;
    }
    private static Runnable retain(Object registry) throws Exception {
        Object release=call(registry,"retain");assertNotNull("retention release",release);assertTrue(release instanceof Runnable);return(Runnable)release;
    }
    private static boolean held(Object registry) throws Exception {return(Boolean)call(registry,"isRetained");}

    @Test public void helperHasTheFrozenPublicConstructorAndMethods() throws Exception { // FR-APP-023 AC-8
        Class<?> type=helper();assertTrue(Modifier.isPublic(type.getModifiers()));
        assertEquals(1,type.getConstructors().length);assertArrayEquals(new Class<?>[]{Runnable.class},type.getConstructors()[0].getParameterTypes());
        Set<String> methods=new TreeSet<>();for(Method method:type.getDeclaredMethods())if(Modifier.isPublic(method.getModifiers())&&!method.isSynthetic())
            methods.add(method.getName()+Arrays.toString(method.getParameterTypes())+"->"+method.getReturnType().getName());
        assertEquals(new TreeSet<>(Arrays.asList("retain[]->java.lang.Runnable","isRetained[]->boolean")),methods);
    }
    @Test public void startsUnretainedAndConstructorNeverNotifies() throws Exception { // FR-APP-023 AC-8
        AtomicInteger calls=new AtomicInteger();Object registry=registry(calls::incrementAndGet);
        assertFalse(held(registry));assertFalse(held(registry));assertEquals(0,calls.get());
    }
    @Test public void nullReleaseCallbackIsRejected() throws Exception { // FR-APP-023 AC-8
        try {registry(null);fail("null callback accepted");}catch(NullPointerException expected){}
    }
    @Test public void twoHoldsReleaseOnlyAtTheLastTransitionAndDuplicatesAreHarmless() throws Exception { // FR-APP-023 AC-8
        AtomicInteger calls=new AtomicInteger();Object registry=registry(calls::incrementAndGet);
        Runnable first=retain(registry),second=retain(registry);assertTrue(held(registry));assertEquals(0,calls.get());
        first.run();first.run();assertTrue(held(registry));assertEquals(0,calls.get());
        second.run();assertFalse(held(registry));assertEquals(1,calls.get());second.run();first.run();assertEquals(1,calls.get());
    }
    @Test public void staleReleaseCannotConsumeAHoldFromALaterCycle() throws Exception { // FR-APP-023 AC-8
        AtomicInteger calls=new AtomicInteger();Object registry=registry(calls::incrementAndGet);
        Runnable old=retain(registry);old.run();assertFalse(held(registry));assertEquals(1,calls.get());
        Runnable current=retain(registry);old.run();assertTrue(held(registry));assertEquals(1,calls.get());
        current.run();assertFalse(held(registry));assertEquals(2,calls.get());
        for(int i=0;i<20;i++){Runnable release=retain(registry);assertTrue(held(registry));release.run();release.run();assertFalse(held(registry));}
        assertEquals(22,calls.get());
    }
    @Test public void registriesOwnIndependentCountsAndCallbacks() throws Exception { // FR-APP-023 AC-8
        AtomicInteger a=new AtomicInteger(),b=new AtomicInteger();Object first=registry(a::incrementAndGet),second=registry(b::incrementAndGet);
        Runnable one=retain(first),two=retain(second);one.run();assertFalse(held(first));assertTrue(held(second));assertEquals(1,a.get());assertEquals(0,b.get());
        two.run();assertFalse(held(second));assertEquals(1,b.get());
    }
    @Test public void callbackObservesTheCompletedZeroTransition() throws Exception { // FR-APP-023 AC-8
        AtomicReference<Object> registry=new AtomicReference<>();AtomicReference<Boolean> observed=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        registry.set(registry(()->{try{observed.set(held(registry.get()));}catch(Throwable e){failure.set(e);}}));
        retain(registry.get()).run();assertNull(failure.get());assertEquals(Boolean.FALSE,observed.get());
    }
    @Test public void callbackFailuresAreFailSoftAndNeverPoisonLaterRetentionCycles() throws Exception { // FR-APP-023 AC-8
        for(boolean error:new boolean[]{false,true}) {
            AtomicInteger calls=new AtomicInteger();Object registry=registry(()->{calls.incrementAndGet();if(error)throw new AssertionError("release callback error");throw new IllegalStateException("release callback failure");});
            Runnable first=retain(registry);first.run();first.run();assertEquals(1,calls.get());assertFalse(held(registry));
            Runnable second=retain(registry);assertTrue(held(registry));second.run();assertFalse(held(registry));assertEquals(2,calls.get());
        }
    }
    @Test(timeout=10000) public void releaseCallbackRunsOutsideTheHelperLock() throws Exception { // FR-APP-023 AC-8
        AtomicReference<Object> registry=new AtomicReference<>();AtomicReference<Runnable> childHold=new AtomicReference<>();AtomicReference<Thread> other=new AtomicReference<>();
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean joined=new AtomicBoolean();AtomicInteger calls=new AtomicInteger();
        registry.set(registry(()->{if(calls.incrementAndGet()!=1)return;
            Thread thread=new Thread(()->{try{childHold.set(retain(registry.get()));}catch(Throwable e){failure.set(e);}},"retention-outside-lock-probe");other.set(thread);
            thread.start();try{thread.join(1000);joined.set(!thread.isAlive());}catch(InterruptedException e){Thread.currentThread().interrupt();failure.set(e);}
        }));
        retain(registry.get()).run();
        Thread thread=other.get();assertNotNull(thread);thread.join(5000);
        try {assertFalse("probe thread leaked",thread.isAlive());assertNull(failure.get());assertTrue("release callback held helper lock",joined.get());assertNotNull(childHold.get());assertTrue(held(registry.get()));assertEquals(1,calls.get());}
        finally {if(childHold.get()!=null)childHold.get().run();}
        assertFalse(held(registry.get()));assertEquals(2,calls.get());
    }
    @Test public void callbackMayReenterRetainAndReleaseWithoutCorruptingTheNextCycle() throws Exception { // FR-APP-023 AC-8
        AtomicReference<Object> registry=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();AtomicBoolean heldInside=new AtomicBoolean();
        registry.set(registry(()->{if(calls.incrementAndGet()!=1)return;try{Runnable nested=retain(registry.get());heldInside.set(held(registry.get()));nested.run();}catch(Throwable e){failure.set(e);}}));
        retain(registry.get()).run();assertNull(failure.get());assertTrue(heldInside.get());assertEquals(2,calls.get());assertFalse(held(registry.get()));
        retain(registry.get()).run();assertEquals(3,calls.get());assertFalse(held(registry.get()));
    }
    @Test(timeout=10000) public void newRetentionDuringAnOldReleaseCallbackRemainsOwned() throws Exception { // FR-APP-023 AC-8
        CountDownLatch entered=new CountDownLatch(1),allowReturn=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
        Object registry=registry(()->{if(calls.incrementAndGet()!=1)return;entered.countDown();try{if(!allowReturn.await(5,TimeUnit.SECONDS))failure.set(new AssertionError("release callback not unblocked"));}catch(Throwable e){failure.set(e);}});
        Runnable first=retain(registry),second=null;ExecutorService pool=Executors.newSingleThreadExecutor();Future<?> oldRelease=pool.submit(first);
        try {assertTrue(entered.await(5,TimeUnit.SECONDS));second=retain(registry);assertTrue(held(registry));assertEquals(1,calls.get());allowReturn.countDown();oldRelease.get(5,TimeUnit.SECONDS);assertNull(failure.get());assertTrue(held(registry));first.run();assertTrue(held(registry));}
        finally {allowReturn.countDown();if(second!=null)second.run();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(2,calls.get());assertFalse(held(registry));
    }
    @Test(timeout=15000) public void concurrentDuplicateReleaseIsExactlyOnceAndDoesNotUnderflow() throws Exception { // FR-APP-023 AC-8
        AtomicInteger calls=new AtomicInteger();Object registry=registry(calls::incrementAndGet);ExecutorService pool=Executors.newFixedThreadPool(8);
        try {for(int round=0;round<20;round++) {
            Runnable release=retain(registry);CountDownLatch start=new CountDownLatch(1);List<Future<?>> workers=new ArrayList<>();
            for(int worker=0;worker<8;worker++)workers.add(pool.submit(()->{try{start.await();for(int i=0;i<200;i++)release.run();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}));
            start.countDown();for(Future<?> worker:workers)worker.get(5,TimeUnit.SECONDS);
            assertFalse(held(registry));assertEquals("one zero transition per cycle",round+1,calls.get());
        }}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test(timeout=15000) public void concurrentRetainAndReleaseCannotNotifyWhileAnotherHoldRemains() throws Exception { // FR-APP-023 AC-8
        AtomicInteger calls=new AtomicInteger();Object registry=registry(calls::incrementAndGet);ExecutorService pool=Executors.newFixedThreadPool(8);
        try {for(int round=0;round<20;round++) {
            Runnable sentinel=retain(registry);CountDownLatch start=new CountDownLatch(1);List<Future<?>> workers=new ArrayList<>();
            for(int worker=0;worker<8;worker++)workers.add(pool.submit(()->{try{start.await();for(int i=0;i<100;i++){Runnable release=retain(registry);release.run();release.run();}}catch(Exception e){throw new AssertionError(e);}}));
            start.countDown();for(Future<?> worker:workers)worker.get(5,TimeUnit.SECONDS);
            assertTrue("sentinel hold lost",held(registry));assertEquals(round,calls.get());sentinel.run();assertFalse(held(registry));assertEquals(round+1,calls.get());
        }}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
