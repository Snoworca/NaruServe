package com.snoworca.naruserve.common;

import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.testing.RecordingExchange;
import org.junit.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Standalone test-first contract; no dependency on the new common HTTP dispatcher. */
public class AbortListenersTest {
    private static Object registry() throws Exception {
        return Class.forName("com.snoworca.naruserve.common.AbortListeners").getConstructor().newInstance();
    }
    private static Object call(Object target,String name,Class<?>[] types,Object... args) throws Exception {
        try{return target.getClass().getMethod(name,types).invoke(target,args);}
        catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw (Error)cause;}
    }
    private static void add(Object registry,Runnable callback) throws Exception {call(registry,"onAbort",new Class<?>[]{Runnable.class},callback);}
    private static void abort(Object registry) throws Exception {call(registry,"abort",new Class<?>[0]);}

    @Test public void defaultHookAddsNoAbstractObligationAndNormalFinishDoesNotAbort() throws Exception { // IR-HTTP-009 AC-1 AC-5
        Method method=HttpExchange.class.getMethod("onAbort",Runnable.class);
        assertTrue(method.isDefault());assertEquals(void.class,method.getReturnType());
        // RecordingExchange predates the hook and intentionally does not override it.
        assertEquals(HttpExchange.class,RecordingExchange.class.getMethod("onAbort",Runnable.class).getDeclaringClass());
        RecordingExchange legacy=new RecordingExchange("GET","/");AtomicInteger calls=new AtomicInteger();
        method.invoke(legacy,(Runnable)calls::incrementAndGet);
        legacy.finish();assertEquals(1,legacy.finishes.get());assertEquals(0,calls.get());
        legacy.abort();assertEquals(0,calls.get()); // custom legacy default remains a no-op
        try{method.invoke(legacy,new Object[]{null});fail();}
        catch(InvocationTargetException expected){assertTrue(expected.getCause() instanceof NullPointerException);}
    }
    @Test public void listenersFireOncePerRegistrationAndLateListenersFireImmediately() throws Exception { // IR-HTTP-009 AC-2 AC-6
        Object registry=registry();AtomicInteger calls=new AtomicInteger();
        Runnable listener=calls::incrementAndGet;
        add(registry,listener);add(registry,listener);
        assertEquals(false,call(registry,"isAborted",new Class<?>[0]));assertEquals(0,calls.get());
        abort(registry);abort(registry);assertEquals(2,calls.get());
        assertEquals(true,call(registry,"isAborted",new Class<?>[0]));
        add(registry,listener);assertEquals(3,calls.get());
        abort(registry);assertEquals(3,calls.get());
    }
    @Test public void throwingListenersDoNotStopRemainingOrLateNotifications() throws Exception { // IR-HTTP-009 AC-3
        Object registry=registry();AtomicInteger calls=new AtomicInteger();
        add(registry,()->{throw new IllegalStateException("listener failed");});
        add(registry,()->{throw new AssertionError("listener error");});add(registry,calls::incrementAndGet);
        abort(registry);assertEquals(1,calls.get());
        add(registry,()->{throw new IllegalArgumentException("late failed");});
        add(registry,calls::incrementAndGet);assertEquals(2,calls.get());
    }
    @Test public void reentrantAbortAndRegistrationDoNotDuplicate() throws Exception { // IR-HTTP-009 AC-2 AC-3 AC-6
        Object registry=registry();AtomicInteger first=new AtomicInteger(),late=new AtomicInteger();
        add(registry,()->{first.incrementAndGet();try{abort(registry);add(registry,late::incrementAndGet);}catch(Exception e){throw new AssertionError(e);}});
        abort(registry);abort(registry);assertEquals(1,first.get());assertEquals(1,late.get());
    }
    @Test(timeout=5000) public void userCallbacksExecuteOutsideRegistryLock() throws Exception { // IR-HTTP-009 AC-2
        Object registry=registry();AtomicBoolean joined=new AtomicBoolean();AtomicInteger late=new AtomicInteger();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        add(registry,()->{
            Thread other=new Thread(()->{try{add(registry,late::incrementAndGet);}catch(Throwable t){failure.set(t);}});
            other.start();try{other.join(1000);joined.set(!other.isAlive());}catch(InterruptedException e){Thread.currentThread().interrupt();}
        });
        abort(registry);assertTrue("listener held registration lock",joined.get());assertNull(failure.get());assertEquals(1,late.get());
    }
    @Test(timeout=15000) public void concurrentAbortAndRegistrationsRemainExactlyOnce() throws Exception { // IR-HTTP-009 AC-2 AC-6
        for(int round=0;round<20;round++) {
            Object registry=registry();AtomicIntegerArray calls=new AtomicIntegerArray(400);CountDownLatch start=new CountDownLatch(1);
            ExecutorService pool=Executors.newFixedThreadPool(9);List<Future<?>> futures=new ArrayList<>();
            try {
                for(int worker=0;worker<8;worker++){final int offset=worker*50;
                    futures.add(pool.submit(()->{try{start.await();for(int i=0;i<50;i++){final int id=offset+i;add(registry,()->calls.incrementAndGet(id));}}
                        catch(Exception e){throw new AssertionError(e);}}));}
                futures.add(pool.submit(()->{try{start.await();for(int i=0;i<20;i++)abort(registry);}catch(Exception e){throw new AssertionError(e);}}));
                start.countDown();for(Future<?> future:futures)future.get(5,TimeUnit.SECONDS);
                for(int i=0;i<400;i++)assertEquals("round "+round+" listener "+i,1,calls.get(i));
            }finally{pool.shutdownNow();}
        }
    }
    @Test public void nullListenerIsRejectedBeforeAndAfterAbort() throws Exception { // IR-HTTP-009 AC-1 AC-2
        Object registry=registry();
        try{add(registry,null);fail();}catch(NullPointerException expected){}
        abort(registry);try{add(registry,null);fail();}catch(NullPointerException expected){}
        assertEquals(true,call(registry,"isAborted",new Class<?>[0]));
    }
}
