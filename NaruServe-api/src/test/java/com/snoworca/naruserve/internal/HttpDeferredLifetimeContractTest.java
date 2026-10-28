package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static com.snoworca.naruserve.internal.HttpExecutionContractTest.*;
import static com.snoworca.naruserve.internal.HttpMountAndParamContractTest.*;
import static com.snoworca.naruserve.internal.HttpMethodAndErrorContractTest.error;
import static com.snoworca.naruserve.internal.DeferredContractTest.*;

/** Core-only detected transport and async ownership contracts; actual adapters remain separate integration gates. */
public class HttpDeferredLifetimeContractTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    interface AsyncAction {Object handle(Request request,Response response,Next next)throws Exception;}
    static Class<?> asyncType(){try{return Class.forName("com.snoworca.naruserve.AsyncRouteHandler");}catch(ClassNotFoundException e){throw new AssertionError(e);}}
    static Object handler(Class<?> callback,AsyncAction action) {
        return Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},(p,m,args)->action.handle((Request)args[0],(Response)args[1],(Next)args[2]));
    }
    static void async(Object target,String operation,Object path,AsyncAction action) {
        Class<?> callback=asyncType();invoke(target,operation,new Class<?>[]{Object.class,callback},path,handler(callback,action));
    }
    static void asyncMethod(Object target,String method,Object path,AsyncAction action) {
        Class<?> callback=asyncType();invoke(target,"methodAsync",new Class<?>[]{String.class,Object.class,callback},method,path,handler(callback,action));
    }
    static void start(DefaultRestApp app,LifecycleExchange exchange){app.freeze();app.dispatcher().handle(exchange);}
    static String body(LifecycleExchange exchange){return text(exchange.recording);}
    static void ended(LifecycleExchange exchange){assertEquals(1,exchange.recording.finishes.get());assertEquals(1,exchange.input.closes.get());}
    static byte[] multipart(){return ("--BOUND\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\nContent-Type: text/plain\r\n\r\npayload\r\n--BOUND--\r\n").getBytes(StandardCharsets.UTF_8);}
    static LifecycleExchange upload(){byte[] bytes=multipart();return new LifecycleExchange("POST","/upload",bytes,"Content-Type","multipart/form-data; boundary=BOUND","Content-Length",Integer.toString(bytes.length));}
    static int temps(File directory){File[] files=directory.listFiles((dir,name)->name.startsWith("naru-upload-"));return files==null?0:files.length;}
    static void released(UploadFile file)throws IOException{try{file.bytes();fail("upload content resurrected after cleanup");}catch(IllegalStateException expected){}}
    static void awaitState(DefaultRestApp app,AppState expected)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(app.state()!=expected&&System.nanoTime()<end)Thread.yield();assertEquals(expected,app.state());}

    @Test public void resolvedAsyncWorkDoesNotAutomaticallyNextOrFinishPendingResponse() { // FR-APP-023 AC-1 AC-2
        DefaultRestApp app=new DefaultRestApp();Object result=pending();AtomicReference<Response> saved=new AtomicReference<>();AtomicInteger downstream=new AtomicInteger();
        async(app,"getAsync","/x",(q,s,n)->{saved.set(s);return result;});app.use((q,s,n)->downstream.incrementAndGet());
        LifecycleExchange ex=new LifecycleExchange("GET","/x");start(app,ex);
        assertEquals(0,ex.recording.commits.get());assertEquals(0,ex.input.closes.get());assertTrue(resolve(result));
        assertEquals(0,ex.recording.commits.get());assertEquals(0,ex.input.closes.get());assertEquals(0,downstream.get());
        saved.get().send("later");assertEquals("later",body(ex));ended(ex);
    }
    @Test public void alreadyResolvedResultStillNeedsExplicitContinuation() { // FR-APP-023 AC-1
        DefaultRestApp app=new DefaultRestApp();AtomicReference<Next> saved=new AtomicReference<>();
        async(app,"getAsync","/x",(q,s,n)->{saved.set(n);return resolved();});app.get("/x",(q,s)->s.send("next"));
        LifecycleExchange ex=new LifecycleExchange("GET","/x");start(app,ex);assertEquals(0,ex.recording.commits.get());
        saved.get().run();assertEquals("next",body(ex));ended(ex);
    }
    @Test(timeout=15000) public void settlementBeforeAndDuringCallbackReturnCannotLoseTheCleanupLease() throws Exception { // FR-APP-023 AC-3 AC-5 AC-7
        ExecutorService producer=Executors.newSingleThreadExecutor();
        try {
            for(boolean beforeReturn:Arrays.asList(true,false))for(int round=0;round<20;round++) {
                DefaultRestApp app=new DefaultRestApp();Object result=pending();CyclicBarrier returning=new CyclicBarrier(2);
                AtomicReference<Future<?>> settlement=new AtomicReference<>();AtomicInteger callbacks=new AtomicInteger();
                async(app,"getAsync","/x",(q,s,n)->{
                    callbacks.incrementAndGet();s.send("done");
                    settlement.set(producer.submit(()->{awaitRace(returning);assertTrue(resolve(result));}));
                    awaitRace(returning);
                    if(beforeReturn)settlement.get().get(3,TimeUnit.SECONDS);
                    return result;
                });
                LifecycleExchange ex=new LifecycleExchange("GET","/x");start(app,ex);
                settlement.get().get(3,TimeUnit.SECONDS);
                assertEquals("done",body(ex));assertEquals(1,callbacks.get());ended(ex);
                assertFalse(resolve(result));assertFalse(reject(result,new IllegalStateException("late")));ended(ex);
            }
        } finally {producer.shutdownNow();assertTrue(producer.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test public void delayedAndAlreadyRejectedResultsReachOnlyDownstreamErrorLayers() { // FR-APP-023 AC-1; FR-APP-024 AC-1
        for(boolean immediate:Arrays.asList(false,true)) {
            DefaultRestApp app=new DefaultRestApp();Throwable failure=new IllegalStateException("async");Object result=immediate?rejected(failure):pending();List<String> trace=new ArrayList<>();AtomicReference<Request> identity=new AtomicReference<>();
            app.use((q,s,n)->{trace.add("before");n.run();trace.add("after");});error(app,(e,q,s,n)->fail("earlier error layer restarted"));
            async(app,"getAsync","/x",(q,s,n)->{identity.set(q);trace.add("async");return result;});
            app.use((q,s,n)->fail("normal callback entered on rejection"));
            error(app,(e,q,s,n)->{assertSame(failure,e);assertSame(identity.get(),q);assertTrue(app.lifecycle().onRequestThread());trace.add("error");s.status(409).send("rejected");});
            LifecycleExchange ex=new LifecycleExchange("GET","/x");start(app,ex);
            if(!immediate){assertEquals(0,ex.recording.commits.get());assertTrue(reject(result,failure));}
            assertEquals(409,ex.recording.status);assertEquals("rejected",body(ex));ended(ex);
            assertEquals(immediate?Arrays.asList("before","async","error","after"):Arrays.asList("before","async","after","error"),trace);
        }
    }
    @Test public void asyncMiddlewareRetainsPrefixViewUntilExplicitNextRestoresIt() { // FR-APP-023 AC-1; FR-HTTP-027 AC-3 AC-4
        DefaultRestApp app=new DefaultRestApp();Object result=pending();AtomicReference<Next> saved=new AtomicReference<>();AtomicReference<Request> identity=new AtomicReference<>();
        async(app,"useAsync","/api",(q,s,n)->{assertEquals("/x",q.path());assertEquals("/api",view(q,"baseUrl"));identity.set(q);saved.set(n);return result;});
        app.get("/api/x",(q,s)->{assertSame(identity.get(),q);assertEquals("",view(q,"baseUrl"));s.send("ok");});
        LifecycleExchange ex=new LifecycleExchange("GET","/api/x");start(app,ex);assertEquals(0,ex.recording.commits.get());saved.get().run();
        assertEquals("ok",body(ex));assertEquals(0,ex.input.closes.get());resolve(result);ended(ex);
    }
    @Test public void multipleReturnedResultsHoldIndependentOwnershipUntilBothSettle() { // FR-APP-023 AC-3
        DefaultRestApp app=new DefaultRestApp();Object first=pending(),second=pending();
        async(app,"useAsync","",(q,s,n)->{n.run();return first;});
        async(app,"getAsync","/x",(q,s,n)->{s.send("done");return second;});
        LifecycleExchange ex=new LifecycleExchange("GET","/x");start(app,ex);assertEquals("done",body(ex));assertEquals(0,ex.input.closes.get());
        resolve(first);assertEquals(0,ex.input.closes.get());resolve(second);ended(ex);
    }
    @Test public void nullAsyncReturnAndSynchronousThrowUseTheCurrentErrorCursor() { // FR-APP-023 AC-1 AC-2; FR-APP-024 AC-1
        for(boolean nullResult:Arrays.asList(true,false)) {
            DefaultRestApp app=new DefaultRestApp();IOException failure=new IOException("async callback throw");
            async(app,"getAsync","/x",(q,s,n)->{if(nullResult)return null;throw failure;});
            error(app,(e,q,s,n)->{if(nullResult)assertTrue(e instanceof NullPointerException);else assertSame(failure,e);s.status(500).send("error");});
            LifecycleExchange ex=new LifecycleExchange("GET","/x");start(app,ex);assertEquals(500,ex.recording.status);assertEquals("error",body(ex));ended(ex);
        }
    }
    @Test public void pendingResultOwnsUploadAfterResponseUntilProducerSettles() throws Exception { // FR-APP-023 AC-3; FR-HTTP-027 AC-5
        File spool=temporary.newFolder("lease");DefaultRestApp app=new DefaultRestApp();app.multipart(o->o.memoryThreshold(1).tempDirectory(spool));
        Object result=pending();AtomicReference<UploadFile> upload=new AtomicReference<>();AtomicReference<InputStream> stream=new AtomicReference<>();
        RecordingLogger logger=new RecordingLogger();app.logger(logger);
        asyncMethod(app,"POST","/upload",(q,s,n)->{
            UploadFile file=q.requireFile("file");upload.set(file);stream.set(file.inputStream());s.send("done");return result;
        });
        LifecycleExchange ex=upload();start(app,ex);assertEquals("done",body(ex));assertEquals(1,ex.recording.finishes.get());
        assertEquals(0,ex.input.closes.get());assertEquals(1,temps(spool));assertEquals("payload",new String(upload.get().bytes(),StandardCharsets.UTF_8));
        assertEquals(1,logger.info().size());assertTrue(resolve(result));ended(ex);assertEquals(0,temps(spool));released(upload.get());
        try{stream.get().read();fail("owned upload stream was not closed");}catch(IOException expected){}
        assertFalse(reject(result,new IllegalStateException("late")));assertEquals(1,ex.input.closes.get());assertEquals(1,logger.info().size());
    }
    @Test public void sendThenSaveWithinActiveCallbackKeepsUploadUntilUnwind() throws Exception { // FR-APP-023 AC-3; FR-HTTP-027 AC-5
        File spool=temporary.newFolder("active"),saved=temporary.newFile("saved.txt");DefaultRestApp app=new DefaultRestApp();app.multipart(o->o.memoryThreshold(1).tempDirectory(spool));
        AtomicReference<UploadFile> file=new AtomicReference<>();
        app.post("/upload",(q,s)->{UploadFile part=q.requireFile("file");file.set(part);s.send("done");assertEquals(1,temps(spool));part.save(saved);});
        LifecycleExchange ex=upload();start(app,ex);ended(ex);assertEquals("payload",new String(java.nio.file.Files.readAllBytes(saved.toPath()),StandardCharsets.UTF_8));assertEquals(0,temps(spool));released(file.get());
    }
    @Test public void queuedPostResponseCallbackStillOwnsParsedUploadUntilQueueDrains() throws Exception { // FR-APP-023 AC-3 AC-4 AC-5
        File spool=temporary.newFolder("queued");DefaultRestApp app=new DefaultRestApp();app.multipart(o->o.memoryThreshold(1).tempDirectory(spool));List<String> trace=new ArrayList<>();AtomicReference<UploadFile> file=new AtomicReference<>();
        app.use((q,s,n)->{UploadFile part=q.requireFile("file");file.set(part);trace.add("before");Thread other=new Thread(n::run);other.start();other.join(1000);assertFalse(other.isAlive());s.send("first");assertEquals("payload",new String(part.bytes(),StandardCharsets.UTF_8));trace.add("after-send");});
        app.use((q,s,n)->{trace.add("queued");assertEquals("payload",new String(file.get().bytes(),StandardCharsets.UTF_8));});
        LifecycleExchange ex=upload();start(app,ex);assertEquals(Arrays.asList("before","after-send","queued"),trace);assertEquals("first",body(ex));ended(ex);assertEquals(0,temps(spool));released(file.get());
    }
    @Test public void sameBodyCacheCookieAttributesAndServicesSurviveDeferredMountedLayers() throws Exception { // FR-HTTP-027 AC-5; FR-APP-023 AC-3
        SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();Object value=new Object(),service=new Object(),attribute=new Object();AtomicInteger decodes=new AtomicInteger();
        JsonCodec codec=new JsonCodec(){@SuppressWarnings("unchecked")public <T>T decode(Reader in,Type type)throws IOException{decodes.incrementAndGet();while(in.read()!=-1){}return (T)value;}public void encode(Object o,Writer out)throws IOException{out.write("null");}};
        DefaultRestApp app=new DefaultRestApp(adapter,codec,new RecordingLogger());app.onStart(ctx->ctx.registerExternal(Object.class,service));Object child=router(false);
        AtomicReference<Next> next=new AtomicReference<>();AtomicReference<Request> identity=new AtomicReference<>();AtomicReference<Cookie> cookie=new AtomicReference<>();AtomicReference<String> requestId=new AtomicReference<>();AtomicLong started=new AtomicLong();
        app.use((q,s,n)->{identity.set(q);assertSame(value,q.body(Object.class));q.attribute("marker",attribute);cookie.set(q.cookie("sid"));requestId.set(((DefaultRequest)q).requestId());started.set(((DefaultRequest)q).startNanos());next.set(n);});
        on(child,"POST","/x",(q,s,n)->{assertSame(identity.get(),q);assertSame(value,q.body(Object.class));assertSame(attribute,q.attributeAs("marker",Object.class));assertSame(cookie.get(),q.cookie("sid"));assertSame(service,q.service(Object.class));assertEquals(requestId.get(),((DefaultRequest)q).requestId());assertEquals(started.get(),((DefaultRequest)q).startNanos());s.send("ok");assertSame(value,q.body(Object.class));});
        mount(app,"/api",child);app.listen(0);
        try{byte[] bytes="{}".getBytes(StandardCharsets.UTF_8);LifecycleExchange ex=new LifecycleExchange("POST","/api/x",bytes,"Content-Type","application/json","Content-Length","2","Cookie","sid=abc");
            adapter.handler.handle(ex);assertEquals(0,ex.input.closes.get());assertEquals(0,ex.recording.finishes.get());next.get().run();assertEquals("ok",body(ex));ended(ex);assertEquals(1,decodes.get());
        }finally{app.shutdown(0,TimeUnit.SECONDS);}
    }
    @Test public void detectedAbortBeforeOrDuringRegistrationRunsNoUserCallbackAndCleansOnce() { // IR-HTTP-009 AC-4 AC-5 AC-6; FR-APP-023 AC-3
        for(boolean before:Arrays.asList(true,false)) {
            DefaultRestApp app=new DefaultRestApp();AtomicInteger user=new AtomicInteger();app.use((q,s,n)->user.incrementAndGet());RecordingLogger log=new RecordingLogger();app.logger(log);
            LifecycleExchange ex=new LifecycleExchange("GET","/x");if(before)ex.abort();else ex.abortOnSubscribe=true;
            start(app,ex);assertEquals(1,ex.subscriptions.get());assertEquals(0,user.get());assertEquals(1,ex.recording.aborts.get());assertEquals(0,ex.recording.commits.get());assertEquals(1,ex.input.closes.get());assertEquals(1,log.info().size());
        }
    }
    @Test public void detectedAbortOfPendingRequestReleasesUploadsAndLateNextCannotResurrect() throws Exception { // IR-HTTP-009 AC-4; FR-APP-023 AC-3 AC-5
        File spool=temporary.newFolder("abort");DefaultRestApp app=new DefaultRestApp();app.multipart(o->o.memoryThreshold(1).tempDirectory(spool));AtomicReference<UploadFile> file=new AtomicReference<>();AtomicReference<Next> saved=new AtomicReference<>();AtomicInteger downstream=new AtomicInteger();RecordingLogger log=new RecordingLogger();app.logger(log);
        app.use((q,s,n)->{file.set(q.requireFile("file"));saved.set(n);});app.use((q,s,n)->downstream.incrementAndGet());
        LifecycleExchange ex=upload();start(app,ex);assertEquals(1,temps(spool));assertEquals(0,ex.input.closes.get());ex.abort();ex.abort();saved.get().run();
        assertEquals(0,downstream.get());assertEquals(1,ex.recording.aborts.get());assertEquals(1,ex.input.closes.get());assertEquals(0,temps(spool));released(file.get());assertEquals(1,log.info().size());assertTrue(log.info().get(0),log.info().get(0).contains("aborted"));
    }
    @Test public void detectedAbortDuringActiveCallbackWaitsForItsOwnedUploadAccess() throws Exception { // IR-HTTP-009 AC-4; FR-APP-023 AC-3 AC-4
        File spool=temporary.newFolder("abort-active");DefaultRestApp app=new DefaultRestApp();app.multipart(o->o.memoryThreshold(1).tempDirectory(spool));LifecycleExchange ex=upload();AtomicReference<UploadFile> file=new AtomicReference<>();
        app.post("/upload",(q,s)->{UploadFile part=q.requireFile("file");file.set(part);Thread detector=new Thread(ex::abort);detector.start();detector.join(1000);assertFalse(detector.isAlive());assertEquals(0,ex.input.closes.get());assertEquals("payload",new String(part.bytes(),StandardCharsets.UTF_8));});
        start(app,ex);assertEquals(1,ex.input.closes.get());assertEquals(1,ex.recording.aborts.get());released(file.get());assertEquals(0,temps(spool));
    }
    @Test public void abortCancelsPendingDeferredLeaseAndLateSettlementDoesNotCleanTwice() throws Exception { // IR-HTTP-009 AC-4; FR-APP-023 AC-3 AC-5
        DefaultRestApp app=new DefaultRestApp();Object result=pending();async(app,"getAsync","/x",(q,s,n)->result);
        LifecycleExchange ex=new LifecycleExchange("GET","/x");start(app,ex);ex.abort();assertEquals(1,ex.input.closes.get());
        resolve(result);assertEquals(1,ex.input.closes.get());assertEquals(1,ex.recording.aborts.get());assertEquals(0,ex.recording.commits.get());
    }
    @Test public void normalFinishNeverSignalsAbortAndCleanupCloseFailuresAreIsolated() { // IR-HTTP-009 AC-5; FR-APP-023 AC-3
        DefaultRestApp app=new DefaultRestApp();RecordingLogger log=new RecordingLogger();app.logger(log);app.get("/x",(q,s)->s.send("ok"));
        LifecycleExchange ex=new LifecycleExchange("GET","/x");IOException failure=new IOException("close failed");ex.input.closeFailure=failure;
        AtomicInteger abortNotices=new AtomicInteger();ex.onAbort(abortNotices::incrementAndGet);start(app,ex);
        assertEquals("ok",body(ex));ended(ex);assertEquals(0,ex.recording.aborts.get());assertEquals(0,abortNotices.get());assertEquals(1,log.info().size());assertEquals(1,Collections.frequency(log.errors,failure));
    }
    @Test public void lateJsonAndStreamCompletionCloseRequestAndOwnedStreamOnce() { // FR-APP-023 AC-1 AC-3
        for(boolean stream:Arrays.asList(false,true)) {
            DefaultRestApp app=new DefaultRestApp();app.jsonCodec(new StubJsonCodec());AtomicReference<Response> saved=new AtomicReference<>();RecordingLogger log=new RecordingLogger();app.logger(log);
            app.get("/x",(q,s)->saved.set(s));LifecycleExchange ex=new LifecycleExchange("GET","/x");start(app,ex);assertEquals(0,ex.recording.commits.get());assertEquals(0,log.info().size());
            LifecycleExchange.CountingInput content=new LifecycleExchange.CountingInput("stream".getBytes(StandardCharsets.UTF_8));
            if(stream){saved.get().stream(content,"text/plain");assertEquals("stream",body(ex));assertEquals(1,content.closes.get());}else{saved.get().json(true);assertEquals("true",body(ex));}
            ended(ex);assertEquals(1,log.info().size());try{saved.get().send("again");fail();}catch(IllegalStateException expected){}assertEquals(1,ex.recording.commits.get());
        }
    }
    @Test public void lateSseCompletionKeepsItsDedicatedTerminalLogAndClosesRequestOnce() throws Exception { // FR-APP-023 AC-1 AC-3; FR-SSE-004
        DefaultRestApp app=new DefaultRestApp();AtomicReference<Response> saved=new AtomicReference<>();RecordingLogger log=new RecordingLogger();app.logger(log);app.get("/events",(q,s)->saved.set(s));
        LifecycleExchange ex=new LifecycleExchange("GET","/events");start(app,ex);assertEquals(0,ex.recording.commits.get());
        SseClient client=saved.get().sse();AtomicInteger closed=new AtomicInteger();client.onClose(closed::incrementAndGet);assertTrue(client.send("one"));client.close();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ex.recording.drain().drain(bytes);assertEquals("data: one\n\n",new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        ended(ex);assertEquals(1,closed.get());assertEquals(1,log.info().size());assertTrue(log.info().get(0),log.info().get(0).contains("sse start="));
    }
    @Test(timeout=5000) public void shutdownCancelsPendingOrdinaryRequestsAndClosesOwnedServicesOnce() throws Exception { // FR-APP-023 AC-3 AC-5 AC-6
        SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();RecordingLogger log=new RecordingLogger();DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),log);AtomicInteger owned=new AtomicInteger();AtomicReference<Next> saved=new AtomicReference<>();AtomicInteger downstream=new AtomicInteger();
        app.onStart(ctx->ctx.own(owned::incrementAndGet));app.use((q,s,n)->saved.set(n));app.use((q,s,n)->downstream.incrementAndGet());app.listen(0);
        LifecycleExchange ex=new LifecycleExchange("GET","/x");adapter.handler.handle(ex);long begin=System.nanoTime();app.shutdown(0,TimeUnit.SECONDS);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)<2000);assertEquals(AppState.TERMINATED,app.state());assertEquals(1,ex.recording.aborts.get());assertEquals(1,ex.input.closes.get());assertEquals(1,owned.get());
        saved.get().run();app.shutdown(0,TimeUnit.SECONDS);assertEquals(0,downstream.get());assertEquals(1,owned.get());assertEquals(1,log.info().size());
    }
    @Test(timeout=5000) public void shutdownCancelsCompletedResponseLeaseWithoutAbortingSuccessfulTransport() throws Exception { // FR-APP-023 AC-3 AC-5; IR-HTTP-009 AC-5
        SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();RecordingLogger logger=new RecordingLogger();DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),logger);Object result=pending();AtomicInteger owned=new AtomicInteger();
        app.onStart(ctx->ctx.own(owned::incrementAndGet));async(app,"getAsync","/x",(q,s,n)->{s.send("done");return result;});app.listen(0);
        LifecycleExchange ex=new LifecycleExchange("GET","/x");adapter.handler.handle(ex);assertEquals(1,ex.recording.finishes.get());assertEquals(0,ex.input.closes.get());
        app.shutdown(0,TimeUnit.SECONDS);assertEquals(AppState.TERMINATED,app.state());assertEquals(1,owned.get());assertEquals(1,ex.input.closes.get());assertEquals(0,ex.recording.aborts.get());
        resolve(result);assertEquals(1,ex.input.closes.get());assertEquals(1,logger.info().size());assertFalse(logger.info().get(0),logger.info().get(0).contains("aborted"));
    }
    @Test(timeout=10000) public void completionAndDetectedAbortRaceHaveOneCleanupAndOneAccessRecord() throws Exception { // FR-APP-023 AC-3 AC-5; IR-HTTP-009 AC-4 AC-6
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{for(int round=0;round<20;round++) {
            DefaultRestApp app=new DefaultRestApp();RecordingLogger logger=new RecordingLogger();app.logger(logger);AtomicReference<Response> response=new AtomicReference<>();app.get("/x",(q,s)->response.set(s));
            LifecycleExchange ex=new LifecycleExchange("GET","/x");start(app,ex);CountDownLatch go=new CountDownLatch(1);
            Future<?> send=pool.submit(()->{try{go.await();response.get().send("ok");}catch(IllegalStateException expectedAbortRace){}catch(InterruptedException e){throw new AssertionError(e);}});
            Future<?> abort=pool.submit(()->{try{go.await();ex.abort();}catch(InterruptedException e){throw new AssertionError(e);}});
            go.countDown();send.get(3,TimeUnit.SECONDS);abort.get(3,TimeUnit.SECONDS);
            assertEquals(1,ex.input.closes.get());assertTrue(ex.recording.commits.get()<=1);assertTrue(ex.recording.finishes.get()<=1);assertEquals(1,ex.recording.aborts.get());assertEquals(1,logger.info().size());
        }}finally{pool.shutdownNow();}
    }
    @Test(timeout=6000) public void shutdownGraceAllowsPendingContinuationToCompleteWithoutAbort() throws Exception { // FR-APP-023 AC-3 AC-5 AC-6
        SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),new RecordingLogger());AtomicReference<Next> saved=new AtomicReference<>();
        app.use((q,s,n)->saved.set(n));app.get("/x",(q,s)->s.send("finished-in-grace"));app.listen(0);LifecycleExchange ex=new LifecycleExchange("GET","/x");adapter.handler.handle(ex);
        Thread shutdown=new Thread(()->app.shutdown(2,TimeUnit.SECONDS));shutdown.start();awaitState(app,AppState.STOPPING);assertTrue("shutdown skipped pending core request",shutdown.isAlive());
        saved.get().run();shutdown.join(3000);assertFalse(shutdown.isAlive());assertEquals("finished-in-grace",body(ex));ended(ex);assertEquals(0,ex.recording.aborts.get());assertEquals(AppState.TERMINATED,app.state());
    }
    @Test(timeout=6000) public void deferredReentryRestoresLifecycleAndShutdownDoesNotWaitOnItself() throws Exception { // FR-APP-023 AC-4
        SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),new RecordingLogger());AtomicReference<Next> saved=new AtomicReference<>();AtomicReference<Thread> worker=new AtomicReference<>();AtomicInteger created=new AtomicInteger();
        app.shutdownThreads=task->{created.incrementAndGet();Thread t=new Thread(task,"lifetime-shutdown");worker.set(t);return t;};
        app.use((q,s,n)->saved.set(n));app.get("/x",(q,s)->{assertTrue(app.lifecycle().onRequestThread());app.shutdown(1,TimeUnit.SECONDS);s.send("done");});app.listen(0);
        LifecycleExchange ex=new LifecycleExchange("GET","/x");adapter.handler.handle(ex);assertFalse(app.lifecycle().onRequestThread());saved.get().run();assertFalse(app.lifecycle().onRequestThread());
        assertEquals(1,created.get());worker.get().join(2500);assertFalse(worker.get().isAlive());assertEquals("done",body(ex));ended(ex);assertEquals(AppState.TERMINATED,app.state());
    }
    @Test public void nestedGatedDispatchRestoresOuterLifecycleScope() { // FR-APP-023 AC-4
        SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),new RecordingLogger());
        app.get("/inner",(q,s)->{assertTrue(app.lifecycle().onRequestThread());s.send("inner");});
        app.get("/outer",(q,s)->{assertTrue(app.lifecycle().onRequestThread());LifecycleExchange nested=new LifecycleExchange("GET","/inner");adapter.handler.handle(nested);assertTrue("nested gate cleared outer request context",app.lifecycle().onRequestThread());s.send("outer");});
        app.listen(0);try{LifecycleExchange ex=new LifecycleExchange("GET","/outer");adapter.handler.handle(ex);assertEquals("outer",body(ex));assertFalse(app.lifecycle().onRequestThread());ended(ex);}finally{app.shutdown(0,TimeUnit.SECONDS);}
    }
    @Test(timeout=10000) public void deepSynchronousContinuationChainUsesBoundedExecutionStack() { // FR-APP-022 AC-5; FR-APP-023 AC-5
        DefaultRestApp app=new DefaultRestApp();AtomicInteger callbacks=new AtomicInteger();
        for(int i=0;i<1500;i++)app.use((q,s,n)->{callbacks.incrementAndGet();n.run();});
        app.get("/x",(q,s)->s.send("ok"));LifecycleExchange ex=new LifecycleExchange("GET","/x");start(app,ex);
        assertEquals("ok",body(ex));assertEquals(1500,callbacks.get());ended(ex);
    }
}
