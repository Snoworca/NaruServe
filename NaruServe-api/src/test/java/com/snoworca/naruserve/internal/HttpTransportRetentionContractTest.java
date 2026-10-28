package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import com.snoworca.naruserve.common.RequestRetentions;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static com.snoworca.naruserve.internal.HttpExecutionContractTest.text;

/** Core ownership over the additive transport SPI; real Tiny/Servlet input recycling remains separately tested. */
public class HttpTransportRetentionContractTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    static final class RetainedExchange extends LifecycleExchange {
        final AtomicInteger acquisitions=new AtomicInteger(),releaseCalls=new AtomicInteger(),zeroTransitions=new AtomicInteger(),recycles=new AtomicInteger();
        final AtomicBoolean wireDone=new AtomicBoolean(),recycled=new AtomicBoolean();
        final List<String> events=Collections.synchronizedList(new ArrayList<>());
        volatile boolean closedAtRelease,retainedAtAbortSubscription;
        volatile Runnable observer=()->{};
        volatile RuntimeException releaseFailure,observerFailure;
        final RequestRetentions retentions=new RequestRetentions(()->{
            zeroTransitions.incrementAndGet();closedAtRelease=input.closed;events.add("released");recycleIfIdle();observer.run();
            if(observerFailure!=null)throw observerFailure;
        });
        final InputStream transportInput=new InputStream() {
            private void readable()throws IOException {if(recycled.get())throw new IOException("transport recycled unread input");}
            public int read()throws IOException {readable();return input.read();}
            public int read(byte[] bytes,int offset,int length)throws IOException {readable();return input.read(bytes,offset,length);}
            public void close()throws IOException {events.add("input-close");input.close();}
        };
        RetainedExchange(String method,String path){super(method,path);}
        RetainedExchange(String method,String path,byte[] bytes,String... headers){super(method,path,bytes,headers);}
        @Override public InputStream requestBody(){return transportInput;}
        @Override public Runnable retainRequest() {
            acquisitions.incrementAndGet();events.add("retained");
            Runnable release=retentions.retain();return ()->{releaseCalls.incrementAndGet();release.run();if(releaseFailure!=null)throw releaseFailure;};
        }
        @Override public void onAbort(Runnable listener){retainedAtAbortSubscription=retentions.isRetained();super.onAbort(listener);}
        @Override public void finish()throws IOException {super.finish();wireDone.set(true);events.add("wire-finish");recycleIfIdle();}
        @Override public void abort(){super.abort();wireDone.set(true);recycleIfIdle();}
        void recycleIfIdle(){if(wireDone.get()&&!retentions.isRetained()&&recycled.compareAndSet(false,true))recycles.incrementAndGet();}
    }
    static final class BodyCodec implements JsonCodec {
        final AtomicInteger decodes=new AtomicInteger();
        @SuppressWarnings("unchecked") public <T>T decode(Reader reader,Type type)throws IOException {
            decodes.incrementAndGet();StringBuilder value=new StringBuilder();int ch;while((ch=reader.read())!=-1)value.append((char)ch);return (T)value.toString();
        }
        public void encode(Object value,Writer writer)throws IOException {writer.write(String.valueOf(value));}
    }
    static RetainedExchange bodyExchange(){byte[] body="original-body".getBytes(StandardCharsets.UTF_8);return new RetainedExchange("POST","/x",body,"Content-Type","application/json","Content-Length",Integer.toString(body.length));}
    static void start(DefaultRestApp app,RetainedExchange exchange){app.freeze();app.dispatcher().handle(exchange);}
    static void released(RetainedExchange exchange) {
        assertEquals(1,exchange.acquisitions.get());assertEquals(1,exchange.releaseCalls.get());assertEquals(1,exchange.zeroTransitions.get());
        assertFalse(exchange.retentions.isRetained());assertTrue(exchange.closedAtRelease);assertEquals(1,exchange.input.closes.get());assertEquals(1,exchange.recycles.get());
        assertTrue(exchange.events.toString(),exchange.events.indexOf("input-close")<exchange.events.indexOf("released"));
    }
    @Test public void transportIsRetainedBeforeCallbacksAndAbortSubscriptionThenReleasedAfterCleanup() { // FR-APP-023 AC-8
        DefaultRestApp app=new DefaultRestApp();RetainedExchange ex=new RetainedExchange("GET","/x");AtomicBoolean callback=new AtomicBoolean();
        app.get("/x",(request,response)->{callback.set(true);assertTrue(ex.retentions.isRetained());assertTrue(ex.retainedAtAbortSubscription);assertEquals(0,ex.releaseCalls.get());response.send("ok");assertFalse(ex.recycled.get());});
        start(app,ex);assertTrue(callback.get());assertEquals("ok",text(ex.recording));assertEquals(1,ex.recording.finishes.get());released(ex);
    }
    @Test public void firstBodyReadAfterEarlyResponseUsesOriginalBytesUntilReturnedDeferredSettles() { // FR-APP-023 AC-3 AC-7 AC-8
        DefaultRestApp app=new DefaultRestApp();BodyCodec codec=new BodyCodec();app.jsonCodec(codec);Deferred result=Deferred.pending();AtomicReference<Request> request=new AtomicReference<>();
        app.methodAsync("POST","/x",(q,s,n)->{request.set(q);s.send("ack");return result;});RetainedExchange ex=bodyExchange();start(app,ex);
        assertEquals("ack",text(ex.recording));assertEquals(1,ex.recording.finishes.get());assertEquals(0,ex.input.closes.get());assertEquals(0,codec.decodes.get());
        assertEquals("original-body",request.get().body(String.class));assertEquals(1,codec.decodes.get());assertTrue(ex.retentions.isRetained());assertFalse(ex.recycled.get());assertTrue(result.resolve());released(ex);
    }
    @Test public void activeSynchronousCallbackCanFirstReadBodyAfterSendingItsResponse() { // FR-APP-023 AC-3 AC-8
        DefaultRestApp app=new DefaultRestApp();BodyCodec codec=new BodyCodec();app.jsonCodec(codec);
        app.post("/x",(q,s)->{s.send("ack");assertEquals("original-body",q.body(String.class));});RetainedExchange ex=bodyExchange();start(app,ex);
        assertEquals("ack",text(ex.recording));assertEquals(1,codec.decodes.get());released(ex);
    }
    @Test public void queuedPostResponseContinuationStillOwnsTheUnreadTransportInput() { // FR-APP-023 AC-3 AC-5 AC-8
        DefaultRestApp app=new DefaultRestApp();BodyCodec codec=new BodyCodec();app.jsonCodec(codec);List<String> trace=new ArrayList<>();
        app.use((q,s,n)->{Thread next=new Thread(n::run);next.start();next.join(1000);assertFalse(next.isAlive());s.send("ack");trace.add("sent");});
        app.post("/x",(q,s)->{assertEquals("original-body",q.body(String.class));trace.add("read");});RetainedExchange ex=bodyExchange();start(app,ex);
        assertEquals(Arrays.asList("sent","read"),trace);assertEquals(1,codec.decodes.get());released(ex);
    }
    @Test public void oneTransportHoldCoversMultipleIndependentDeferredWorkLeases() { // FR-APP-023 AC-7 AC-8
        DefaultRestApp app=new DefaultRestApp();Deferred first=Deferred.pending(),second=Deferred.pending();app.useAsync((q,s,n)->{n.run();return first;});app.getAsync("/x",(q,s,n)->{s.send("done");return second;});
        RetainedExchange ex=new RetainedExchange("GET","/x");start(app,ex);assertEquals(1,ex.acquisitions.get());assertTrue(ex.retentions.isRetained());assertEquals(0,ex.releaseCalls.get());
        first.resolve();assertTrue(ex.retentions.isRetained());assertEquals(0,ex.releaseCalls.get());second.resolve();released(ex);
    }
    @Test public void pendingUploadAndOwnedStreamAreCleanedBeforeTransportReleaseObserver() throws Exception { // FR-APP-023 AC-3 AC-7 AC-8
        File spool=temporary.newFolder();DefaultRestApp app=new DefaultRestApp();app.multipart(o->o.memoryThreshold(1).tempDirectory(spool));Deferred result=Deferred.pending();AtomicReference<UploadFile> part=new AtomicReference<>();AtomicReference<InputStream> stream=new AtomicReference<>();
        app.methodAsync("POST","/upload",(q,s,n)->{part.set(q.requireFile("file"));stream.set(part.get().inputStream());s.send("ack");return result;});
        byte[] bytes=HttpDeferredLifetimeContractTest.multipart();RetainedExchange ex=new RetainedExchange("POST","/upload",bytes,"Content-Type","multipart/form-data; boundary=BOUND","Content-Length",Integer.toString(bytes.length));
        AtomicBoolean uploadClosed=new AtomicBoolean(),streamClosed=new AtomicBoolean();AtomicInteger tempCount=new AtomicInteger(-1);
        ex.observer=()->{tempCount.set(HttpDeferredLifetimeContractTest.temps(spool));try{part.get().bytes();}catch(IllegalStateException expected){uploadClosed.set(true);}catch(IOException unexpected){throw new AssertionError(unexpected);}try{stream.get().read();}catch(IOException expected){streamClosed.set(true);}};
        start(app,ex);assertEquals(1,HttpDeferredLifetimeContractTest.temps(spool));assertTrue(ex.retentions.isRetained());assertEquals(0,ex.releaseCalls.get());result.resolve();
        released(ex);assertEquals(0,tempCount.get());assertTrue(uploadClosed.get());assertTrue(streamClosed.get());
    }
    @Test public void abortBeforeOrDuringSubscriptionReleasesAcquiredTransportOwnershipWithoutCallbacks() { // FR-APP-023 AC-8; IR-HTTP-009 AC-4 AC-6
        for(boolean before:Arrays.asList(true,false)) {
            DefaultRestApp app=new DefaultRestApp();AtomicInteger callbacks=new AtomicInteger();app.get("/x",(q,s)->callbacks.incrementAndGet());RetainedExchange ex=new RetainedExchange("GET","/x");
            if(before)ex.abort();else ex.abortOnSubscribe=true;start(app,ex);assertEquals(0,callbacks.get());assertTrue(ex.retainedAtAbortSubscription);released(ex);assertEquals(1,ex.recording.aborts.get());
        }
    }
    @Test public void cancellationWhenLifecycleWasAlreadyClosedStillReleasesAcquisition() { // FR-APP-023 AC-5 AC-8
        DefaultRestApp app=new DefaultRestApp();AtomicInteger callbacks=new AtomicInteger();app.get("/x",(q,s)->callbacks.incrementAndGet());app.freeze();app.shutdown(0,TimeUnit.SECONDS);
        RetainedExchange ex=new RetainedExchange("GET","/x");app.dispatcher().handle(ex);assertEquals(0,callbacks.get());assertEquals(AppState.TERMINATED,app.state());assertEquals(1,ex.recording.aborts.get());released(ex);
    }
    @Test public void shutdownRevokesCompletedResponseLeaseWithoutAbortingOrReleasingTwice() { // FR-APP-023 AC-3 AC-5 AC-7 AC-8
        SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),new RecordingLogger());Deferred result=Deferred.pending();app.getAsync("/x",(q,s,n)->{s.send("ack");return result;});app.listen(0);
        RetainedExchange ex=new RetainedExchange("GET","/x");adapter.handler.handle(ex);assertTrue(ex.retentions.isRetained());assertEquals(0,ex.releaseCalls.get());app.shutdown(0,TimeUnit.SECONDS);
        assertEquals(AppState.TERMINATED,app.state());assertEquals(0,ex.recording.aborts.get());released(ex);result.resolve();released(ex);
    }
    @Test(timeout=10000) public void concurrentResponseAndAbortReleaseOnlyAfterExactlyOnceInputCleanup() throws Exception { // FR-APP-023 AC-3 AC-5 AC-8
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {for(int round=0;round<20;round++) {
            DefaultRestApp app=new DefaultRestApp();AtomicReference<Response> response=new AtomicReference<>();app.get("/x",(q,s)->response.set(s));RetainedExchange ex=new RetainedExchange("GET","/x");start(app,ex);CountDownLatch go=new CountDownLatch(1);
            Future<?> send=pool.submit(()->{try{go.await();response.get().send("ok");}catch(IllegalStateException expectedRace){}catch(InterruptedException interrupted){throw new AssertionError(interrupted);}});
            Future<?> abort=pool.submit(()->{try{go.await();ex.abort();}catch(InterruptedException interrupted){throw new AssertionError(interrupted);}});
            go.countDown();send.get(3,TimeUnit.SECONDS);abort.get(3,TimeUnit.SECONDS);released(ex);assertTrue(ex.recording.commits.get()<=1);assertTrue(ex.recording.finishes.get()<=1);
        }} finally {pool.shutdownNow();}
    }
    static int tracked(DefaultRestApp app) {
        try {
            java.lang.reflect.Field field=AppLifecycle.class.getDeclaredField("executions");field.setAccessible(true);
            Set<?> executions=(Set<?>)field.get(app.lifecycle());synchronized(executions){return executions.size();}
        } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
    }
    @Test public void lifecycleObservationDetectsAnUnfinishedResponseAndItsNormalRemoval() { // FR-APP-023 AC-3 AC-8
        SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),new RecordingLogger());AtomicReference<Response> response=new AtomicReference<>();
        app.get("/x",(q,s)->response.set(s));app.listen(0);LifecycleExchange ex=new LifecycleExchange("GET","/x");
        try {adapter.handler.handle(ex);assertEquals(1,tracked(app));assertEquals(0,ex.input.closes.get());response.get().send("ok");assertEquals(0,tracked(app));assertEquals(1,ex.input.closes.get());}
        finally {app.shutdown(0,TimeUnit.SECONDS);}
    }
    @Test public void lifecycleObservationDetectsACompletedResponseWithPendingDeferredOwnership() { // FR-APP-023 AC-3 AC-7 AC-8
        SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),new RecordingLogger());Deferred pending=Deferred.pending();
        app.getAsync("/x",(q,s,n)->{s.send("ok");return pending;});app.listen(0);LifecycleExchange ex=new LifecycleExchange("GET","/x");
        try {adapter.handler.handle(ex);assertEquals(1,ex.recording.finishes.get());assertEquals(1,tracked(app));assertEquals(0,ex.input.closes.get());pending.resolve();assertEquals(0,tracked(app));assertEquals(1,ex.input.closes.get());}
        finally {pending.resolve();app.shutdown(0,TimeUnit.SECONDS);}
    }
    @Test public void releaseObserverOrCustomReleaseFailureCannotLeakLifecycleOwnership() { // FR-APP-023 AC-3 AC-8
        for(boolean callbackFailure:Arrays.asList(false,true)) {
            SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();RecordingLogger logger=new RecordingLogger();DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),logger);app.get("/x",(q,s)->s.send("ok"));app.listen(0);
            RuntimeException failure=new IllegalStateException("release failed");RetainedExchange ex=new RetainedExchange("GET","/x");if(callbackFailure)ex.observerFailure=failure;else ex.releaseFailure=failure;
            adapter.handler.handle(ex);released(ex);assertEquals("ok",text(ex.recording));assertEquals(1,logger.info().size());
            if(!callbackFailure)assertTrue(logger.errors.contains(failure));
            assertEquals("release failure leaked request ownership before any shutdown/forced cancellation",0,tracked(app));
            app.shutdown(1,TimeUnit.SECONDS);assertEquals(AppState.TERMINATED,app.state());
        }
    }
}
