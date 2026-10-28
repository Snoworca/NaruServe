package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import com.snoworca.naruserve.testing.*;
import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static com.snoworca.naruserve.internal.HttpExecutionContractTest.text;
import static com.snoworca.naruserve.internal.HttpTransportRetentionContractTest.tracked;

/** Deterministic input-cleanup/post-response admission interleaving; no old cleanup may unlink new work. */
public class HttpCleanupAdmissionContractTest {
    static final class BlockingCloseExchange extends LifecycleExchange {
        final CountDownLatch closeEntered=new CountDownLatch(1),closeAllowed=new CountDownLatch(1);
        final AtomicInteger bodyLookups=new AtomicInteger(),closeCalls=new AtomicInteger();
        final InputStream body=new InputStream() {
            public int read()throws IOException{return input.read();}
            public void close()throws IOException {
                closeCalls.incrementAndGet();closeEntered.countDown();
                try {if(!closeAllowed.await(4,TimeUnit.SECONDS))throw new IOException("test did not release input close");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
                input.close();
            }
        };
        BlockingCloseExchange(){super("GET","/x");}
        public InputStream requestBody(){bodyLookups.incrementAndGet();return body;}
    }
    static final class Owned implements AutoCloseable {
        final AtomicInteger closes=new AtomicInteger();final List<String> trace;
        Owned(List<String> trace){this.trace=trace;}
        public void close(){trace.add("service-close");closes.incrementAndGet();}
    }
    @Test(timeout=10000) public void oldInputCleanupCannotUnregisterAnAdmittedRunningCallback() throws Exception { // FR-APP-023 AC-3 AC-5 AC-8; CON-APP-010 AC-3
        exercise(false);
    }
    @Test(timeout=10000) public void reenteredCallbackCanTransferLifetimeToItsReturnedDeferredAfterOldCleanup() throws Exception { // FR-APP-023 AC-3 AC-5 AC-7 AC-8
        exercise(true);
    }
    private static void exercise(boolean deferred) throws Exception {
        ExecutorService threads=Executors.newFixedThreadPool(3);SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();RecordingLogger logger=new RecordingLogger();
        DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),logger);List<String> trace=Collections.synchronizedList(new ArrayList<>());Owned service=new Owned(trace);
        CountDownLatch callbackEntered=new CountDownLatch(1),callbackAllowed=new CountDownLatch(1);AtomicReference<Next> saved=new AtomicReference<>();AtomicReference<Request> identity=new AtomicReference<>();AtomicInteger stopped=new AtomicInteger();Deferred result=Deferred.pending();BlockingCloseExchange ex=new BlockingCloseExchange();
        app.onStart(context->context.register(Owned.class,service));app.onStop(context->{trace.add("on-stop");stopped.incrementAndGet();});
        app.get("/x",(q,s,n)->{identity.set(q);saved.set(n);s.send("ok");});
        AsyncRouteHandler callback=(q,s,n)->{
            assertSame(identity.get(),q);assertSame(service,q.service(Owned.class));assertEquals(0,service.closes.get());trace.add("callback-enter");callbackEntered.countDown();
            assertTrue("test did not release callback",callbackAllowed.await(4,TimeUnit.SECONDS));
            assertEquals("app closed its service under the active callback",0,service.closes.get());assertEquals(0,stopped.get());trace.add("callback-exit");return result;
        };
        if(deferred)app.useAsync(callback);else app.use((q,s,n)->callback.handle(q,s,n));
        app.listen(0);
        try {
            Future<?> initial=threads.submit(()->adapter.handler.handle(ex));
            assertTrue("input cleanup did not start",ex.closeEntered.await(2,TimeUnit.SECONDS));assertEquals(1,tracked(app));
            Future<?> resumed=threads.submit(()->saved.get().run());
            assertTrue("post-response callback was not admitted while app RUNNING",callbackEntered.await(2,TimeUnit.SECONDS));assertEquals(1,tracked(app));
            ex.closeAllowed.countDown();initial.get(2,TimeUnit.SECONDS);
            assertEquals("old cleanup removed newly active callback ownership",1,tracked(app));
            Future<?> shutdown=threads.submit(()->app.shutdown(3,TimeUnit.SECONDS));HttpDeferredLifetimeContractTest.awaitState(app,AppState.STOPPING);
            expectWaiting(shutdown);assertEquals(0,service.closes.get());assertEquals(0,stopped.get());assertEquals(1,tracked(app));
            callbackAllowed.countDown();resumed.get(2,TimeUnit.SECONDS);
            if(deferred) {assertEquals(1,tracked(app));expectWaiting(shutdown);assertEquals(0,service.closes.get());result.resolve();}
            shutdown.get(2,TimeUnit.SECONDS);assertEquals(AppState.TERMINATED,app.state());assertEquals(0,tracked(app));
            assertEquals(1,service.closes.get());assertEquals(1,stopped.get());assertTrue(trace.toString(),trace.indexOf("callback-exit")<trace.indexOf("on-stop"));assertTrue(trace.toString(),trace.indexOf("callback-exit")<trace.indexOf("service-close"));
            assertEquals("ok",text(ex.recording));assertEquals(1,ex.recording.commits.get());assertEquals(1,ex.recording.finishes.get());assertEquals(0,ex.recording.aborts.get());assertEquals(1,logger.info().size());
            assertEquals("closed request input must not be acquired again",1,ex.bodyLookups.get());assertEquals(1,ex.closeCalls.get());assertEquals(1,ex.input.closes.get());
        } finally {
            ex.closeAllowed.countDown();callbackAllowed.countDown();result.resolve();threads.shutdownNow();assertTrue(threads.awaitTermination(3,TimeUnit.SECONDS));app.shutdown(0,TimeUnit.SECONDS);
        }
    }
    private static void expectWaiting(Future<?> future)throws Exception {
        try {future.get(100,TimeUnit.MILLISECONDS);fail("shutdown disposed app resources while callback/Deferred remained owned");}
        catch(TimeoutException expected) { /* Joined below after deterministic ownership release. */ }
    }
}
