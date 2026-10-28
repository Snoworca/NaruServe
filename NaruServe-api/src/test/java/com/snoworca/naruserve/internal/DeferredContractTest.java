package com.snoworca.naruserve.internal;

import org.junit.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static com.snoworca.naruserve.internal.HttpExecutionContractTest.invoke;

/** Explicit Java8/API21 async result; executable RED before production Deferred exists. */
public class DeferredContractTest {
    static Class<?> type(){try{return Class.forName("com.snoworca.naruserve.Deferred");}catch(Exception e){throw new AssertionError(e);}}
    static Object pending(){try{return type().getMethod("pending").invoke(null);}catch(Exception e){throw new AssertionError(e);}}
    static Object resolved(){try{return type().getMethod("resolved").invoke(null);}catch(Exception e){throw new AssertionError(e);}}
    static Object rejected(Throwable failure){try{return type().getMethod("rejected",Throwable.class).invoke(null,failure);}catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(Exception e){throw new AssertionError(e);}}
    static boolean resolve(Object result){return (Boolean)invoke(result,"resolve",new Class<?>[0]);}
    static boolean reject(Object result,Throwable failure){return (Boolean)invoke(result,"reject",new Class<?>[]{Throwable.class},failure);}
    static boolean done(Object result){return (Boolean)invoke(result,"isDone",new Class<?>[0]);}
    interface FailureAction {void rejected(Throwable failure);}
    static void failure(Object result,FailureAction action){
        try{Class<?> callback=Class.forName("com.snoworca.naruserve.Deferred$FailureHandler");
            Object listener=Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},(p,m,args)->{action.rejected((Throwable)args[0]);return null;});
            invoke(result,"onRejected",new Class<?>[]{callback},listener);
        }catch(ClassNotFoundException e){throw new AssertionError(e);}
    }
    static void settled(Object result,Runnable listener){invoke(result,"onSettled",new Class<?>[]{Runnable.class},listener);}
    @Test public void pendingConstructorAndFactoriesHaveExplicitFirstSettlementWins() throws Exception { // FR-APP-023 AC-1 AC-2
        Object result=type().getConstructor().newInstance();assertFalse(done(result));assertTrue(resolve(result));assertTrue(done(result));
        assertFalse(resolve(result));assertFalse(reject(result,new IllegalStateException("too-late")));
        assertFalse(done(pending()));assertTrue(done(resolved()));assertTrue(done(rejected(new IllegalStateException("now"))));
    }
    @Test public void resolutionNotifiesOnlySettlementAndLateObserversImmediately() { // FR-APP-023 AC-1 AC-2
        Object result=pending();List<String> trace=new ArrayList<>();failure(result,e->fail("resolved result rejected"));settled(result,()->trace.add("settled"));
        assertTrue(resolve(result));assertEquals(Collections.singletonList("settled"),trace);
        settled(result,()->trace.add("late"));failure(result,e->fail());assertEquals(Arrays.asList("settled","late"),trace);
    }
    @Test public void rejectionPreservesIdentityAndRunsFailuresBeforeSettlementCallbacks() { // FR-APP-023 AC-1 AC-2
        Object result=pending();Throwable error=new IllegalStateException("rejected");List<String> trace=new ArrayList<>();
        settled(result,()->trace.add("settled"));failure(result,e->{assertSame(error,e);trace.add("failure");});
        assertTrue(reject(result,error));assertEquals(Arrays.asList("failure","settled"),trace);
        failure(result,e->{assertSame(error,e);trace.add("late-failure");});settled(result,()->trace.add("late-settled"));
        assertEquals(Arrays.asList("failure","settled","late-failure","late-settled"),trace);
    }
    @Test public void observerFailuresDoNotPreventOtherObserversOrCleanupNotification() { // FR-APP-023 AC-3
        Object result=pending();AtomicInteger seen=new AtomicInteger();
        failure(result,e->{throw new AssertionError("observer failed");});failure(result,e->seen.incrementAndGet());
        settled(result,()->{throw new IllegalStateException("settled observer failed");});settled(result,seen::incrementAndGet);
        reject(result,new IllegalArgumentException("failure"));assertEquals(2,seen.get());assertTrue(done(result));
    }
    @Test(timeout=5000) public void settlementObserversRunOutsideLocksAndCanJoinOtherSubscribers() throws Exception { // FR-APP-023 AC-4
        Object result=pending();AtomicBoolean joined=new AtomicBoolean();AtomicInteger late=new AtomicInteger();
        settled(result,()->{Thread other=new Thread(()->settled(result,late::incrementAndGet));other.start();try{other.join(1000);joined.set(!other.isAlive());}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        resolve(result);assertTrue(joined.get());assertEquals(1,late.get());
    }
    @Test(timeout=10000) public void concurrentResolveRejectAndLateSubscriptionSettleOnce() throws Exception { // FR-APP-023 AC-3 AC-5
        for(int round=0;round<20;round++) {
            Object result=pending();AtomicInteger winners=new AtomicInteger(),settlements=new AtomicInteger(),failures=new AtomicInteger();
            settled(result,settlements::incrementAndGet);failure(result,e->failures.incrementAndGet());
            ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch go=new CountDownLatch(1);List<Future<?>> futures=new ArrayList<>();
            try{for(int i=0;i<8;i++){final boolean reject=i%2==0;futures.add(pool.submit(()->{try{go.await();if(reject?reject(result,new IllegalStateException("race")):resolve(result))winners.incrementAndGet();}catch(InterruptedException e){throw new AssertionError(e);}}));}
                go.countDown();for(Future<?> future:futures)future.get(3,TimeUnit.SECONDS);
                assertEquals(1,winners.get());assertEquals(1,settlements.get());assertTrue(failures.get()==0||failures.get()==1);
                settled(result,settlements::incrementAndGet);assertEquals(2,settlements.get());
            }finally{pool.shutdownNow();}
        }
    }
    @Test(timeout=15000) public void observerRegistrationsRaceBothSettlementsWithoutLossOrDuplication() throws Exception { // FR-APP-023 AC-3 AC-5 AC-7
        ExecutorService pool=Executors.newFixedThreadPool(10);
        try {
            // Exercise guaranteed resolve, guaranteed reject, and competing outcomes.
            for(int mode=0;mode<3;mode++)for(int round=0;round<20;round++) {
                Object result=pending();CyclicBarrier go=new CyclicBarrier(10);
                AtomicIntegerArray settlements=new AtomicIntegerArray(4),failures=new AtomicIntegerArray(4);
                AtomicReferenceArray<Throwable> observed=new AtomicReferenceArray<>(4);
                AtomicReference<Throwable> winningError=new AtomicReference<>();AtomicInteger winner=new AtomicInteger(),wins=new AtomicInteger();
                List<Future<?>> futures=new ArrayList<>();
                for(int i=0;i<4;i++) {
                    final int listener=i;
                    futures.add(pool.submit(()->{awaitRace(go);settled(result,()->settlements.incrementAndGet(listener));}));
                    futures.add(pool.submit(()->{awaitRace(go);failure(result,error->{observed.set(listener,error);failures.incrementAndGet(listener);});}));
                }
                for(int i=0;i<2;i++) {
                    final boolean rejecting=mode==1||(mode==2&&i==1);
                    final Throwable error=new IllegalStateException("race-"+mode+"-"+round+"-"+i);
                    futures.add(pool.submit(()->{awaitRace(go);boolean won=rejecting?reject(result,error):resolve(result);
                        if(won){winningError.set(rejecting?error:null);winner.set(rejecting?2:1);wins.incrementAndGet();}}));
                }
                for(Future<?> future:futures)future.get(3,TimeUnit.SECONDS);
                assertEquals(1,wins.get());assertTrue(done(result));assertTrue(winner.get()==1||winner.get()==2);
                if(mode<2)assertEquals(mode==0?1:2,winner.get());
                for(int i=0;i<4;i++) {
                    assertEquals("settlement listener "+i,1,settlements.get(i));
                    assertEquals("rejection listener "+i,winner.get()==2?1:0,failures.get(i));
                    assertSame(winningError.get(),observed.get(i));
                }
                assertFalse(resolve(result));assertFalse(reject(result,new IllegalStateException("repeat")));
                AtomicInteger lateSettled=new AtomicInteger(),lateRejected=new AtomicInteger();AtomicReference<Throwable> lateError=new AtomicReference<>();
                settled(result,lateSettled::incrementAndGet);failure(result,error->{lateError.set(error);lateRejected.incrementAndGet();});
                assertEquals(1,lateSettled.get());assertEquals(winner.get()==2?1:0,lateRejected.get());assertSame(winningError.get(),lateError.get());
                // Verify callbacks again after repeated settlement and late subscription.
                for(int i=0;i<4;i++){assertEquals(1,settlements.get(i));assertEquals(winner.get()==2?1:0,failures.get(i));}
            }
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
    }
    static void awaitRace(CyclicBarrier barrier) {
        try{barrier.await(3,TimeUnit.SECONDS);}catch(Exception error){throw new AssertionError(error);}
    }
    @Test public void nullReasonsAndObserversAreRejectedInEveryState() throws Exception { // FR-APP-023 AC-2; FR-APP-022 AC-6
        Class<?> failureType=Class.forName("com.snoworca.naruserve.Deferred$FailureHandler");
        for(Object result:Arrays.asList(pending(),resolved(),rejected(new IllegalStateException("done")))) {
            try{reject(result,null);fail();}catch(NullPointerException expected){}
            try{settled(result,null);fail();}catch(NullPointerException expected){}
            try{invoke(result,"onRejected",new Class<?>[]{failureType},(Object)null);fail();}catch(NullPointerException expected){}
        }
        try{rejected(null);fail();}catch(NullPointerException expected){}
    }
    @Test public void mandatoryApiDoesNotRequireCompletionStageOrJavaUtilFunction() { // FR-APP-023 AC-2; FR-HTTP-027 AC-6
        for(Method method:type().getDeclaredMethods()) {
            List<Class<?>> signature=new ArrayList<>(Arrays.asList(method.getParameterTypes()));signature.add(method.getReturnType());
            for(Class<?> value:signature){assertFalse(value.getName(),value.getName().startsWith("java.util.function."));assertFalse(value.getName(),value.getName().contains("CompletionStage"));assertFalse(value.getName(),value.getName().contains("CompletableFuture"));}
        }
    }
}
