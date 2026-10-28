package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.*;
import com.snoworca.naruserve.*;
import com.snoworca.naruserve.spi.*;
import com.snoworca.naruserve.testing.*;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Deferred behavior over the real adapter; returning from ExchangeHandler is observed before later work. */
public class TinyDeferredSocketTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    private final TinyHttpdAdapter engine=new TinyHttpdAdapter();
    private final BlockingQueue<HttpExchange> entered=new LinkedBlockingQueue<>();
    private final List<HttpExchange> all=new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> releases=new ArrayList<>();
    private final ExecutorService clients=Executors.newFixedThreadPool(4);
    private RestApp app;
    private final RecordingLogger log=new RecordingLogger();
    @After public void cleanup() throws Exception {
        for(CountDownLatch release:releases)release.countDown();
        for(HttpExchange ex:all)ex.abort();
        if(app!=null)app.shutdown(0,TimeUnit.MILLISECONDS);Http.stopQuietly(engine);
        clients.shutdownNow();assertTrue("client threads leaked",clients.awaitTermination(12,TimeUnit.SECONDS));
    }
    private void capture(HttpExchange ex){all.add(ex);entered.add(ex);}
    private void start(ExchangeHandler handler) throws Exception {engine.bind(Http.settings().maxSseStreams(1).build(),ex->{capture(ex);handler.handle(ex);});}
    private RestApp core(){app=RestApp.builder().httpAdapter(new HttpAdapter(){
        public void bind(String host,int port,ExchangeHandler handler)throws IOException{engine.bind(host,port,ex->{capture(ex);handler.handle(ex);});}
        public void bind(ServerSettings settings,ExchangeHandler handler)throws IOException{engine.bind(settings,ex->{capture(ex);handler.handle(ex);});}
        public int boundPort(){return engine.boundPort();}public void stop(long timeout,TimeUnit unit){engine.stop(timeout,unit);}public void release(){engine.release();}
    }).jsonCodec(new StubJsonCodec()).logger(log).build();return app;}
    private Future<String> get(String target){return clients.submit(()->Http.send(engine.boundPort(),Http.get(target)));}
    private HttpExchange returned() throws Exception {HttpExchange ex=entered.poll(5,TimeUnit.SECONDS);assertNotNull("handler not entered",ex);until(()->((TinyHttpdExchange)ex).handlerReturned(),"initial handler not returned");return ex;}
    interface Check {boolean ok() throws Exception;}
    private static void until(Check check,String failure)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!check.ok()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(failure,check.ok());}
    private static void pending(Future<?> result)throws Exception{try{result.get(120,TimeUnit.MILLISECONDS);fail("response completed before later callback");}catch(TimeoutException expected){}}
    private static void send(HttpExchange ex,String body)throws IOException{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);ex.commit(200,Collections.singletonList(new AbstractMap.SimpleImmutableEntry<>("Content-Length",Integer.toString(bytes.length))),bytes.length).write(bytes);ex.finish();}
    private static void closed(HttpExchange ex)throws Exception{try{ex.commit(200,Collections.emptyList(),0);fail("terminal exchange resurrected");}catch(IOException|IllegalStateException expected){}}
    private static void released(UploadFile file)throws Exception{try{file.bytes();fail("request upload still usable after terminal cleanup");}catch(IllegalStateException expected){}}

    // FR-APP-023 AC-1 AC-3 AC-6; IR-HTTP-009 AC-5: actual ordinary late JSON/stream and owned stream close.
    @Test(timeout=20000) public void lateJsonAndOwnedStreamCompleteAfterInitialReturnWithoutAbort() throws Exception {
        RestApp a=core();AtomicReference<Response> response=new AtomicReference<>();a.get("/later",(q,s)->response.set(s));a.listen(0);
        for(boolean stream:new boolean[]{false,true}){
            Future<String> reply=get("/later");HttpExchange ex=returned();pending(reply);AtomicInteger aborts=new AtomicInteger();ex.onAbort(aborts::incrementAndGet);
            AtomicInteger closes=new AtomicInteger();InputStream body=new ByteArrayInputStream("stream".getBytes(StandardCharsets.UTF_8)){@Override public void close()throws IOException{closes.incrementAndGet();super.close();}};
            if(stream)response.get().stream(body,"text/plain");else response.get().json(true);
            String result=reply.get(5,TimeUnit.SECONDS);assertEquals(result,200,Http.status(result));assertTrue(result,Http.body(result).contains(stream?"stream":"true"));
            assertEquals(stream?1:0,closes.get());assertEquals(0,aborts.get());closed(ex);
        }
        until(()->log.info().size()==2,"access completion was not once per request");
    }
    // FR-APP-023 AC-1 AC-3 AC-6: the existing worker waiting for ordinary completion must wake for late SSE.
    @Test(timeout=20000) public void lateCoreSseWakesWorkerAndCompletesDedicatedLogOnce() throws Exception {
        RestApp a=core();AtomicReference<Response> response=new AtomicReference<>();a.get("/events",(q,s)->response.set(s));a.listen(0);
        Future<String> reply=get("/events");HttpExchange ex=returned();pending(reply);AtomicInteger closes=new AtomicInteger(),aborts=new AtomicInteger();ex.onAbort(aborts::incrementAndGet);
        SseClient client=response.get().sse();client.onClose(closes::incrementAndGet);assertTrue(client.send("later"));client.close();
        String result=reply.get(5,TimeUnit.SECONDS);assertEquals(200,Http.status(result));assertTrue(result,result.contains("data: later\n\n"));
        until(()->((TinyHttpdExchange)ex).slots().open()==0,"SSE slot retained");until(()->log.info().size()==1,"SSE access log missing");
        assertTrue(log.info().get(0),log.info().get(0).contains("sse start="));assertEquals(1,closes.get());assertEquals(0,aborts.get());closed(ex);
    }
    // FR-APP-023 AC-1 AC-3 AC-6: one late running drain owns the slot; pre-admission503 does not abort.
    @Test(timeout=20000) public void lateSpiSseKeepsOneSlotAndRejectsSecondBeforeStart() throws Exception {
        start(ex->{});Future<String> first=get("/one");HttpExchange one=returned();CountDownLatch drainEntered=new CountDownLatch(1),release=new CountDownLatch(1);releases.add(release);
        AtomicInteger drains=new AtomicInteger(),cancels=new AtomicInteger();
        one.eventStream(200,Collections.emptyList(),new StreamDrain(){public void drain(OutputStream out)throws IOException{drains.incrementAndGet();drainEntered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}out.write("later".getBytes(StandardCharsets.UTF_8));one.finish();}public void abort(){cancels.incrementAndGet();}});
        assertTrue(drainEntered.await(5,TimeUnit.SECONDS));assertEquals(1,((TinyHttpdExchange)one).slots().open());
        Future<String> second=get("/two");HttpExchange two=returned();AtomicInteger rejectedDrains=new AtomicInteger(),rejectedAborts=new AtomicInteger();two.onAbort(rejectedAborts::incrementAndGet);
        try{two.eventStream(200,Collections.emptyList(),out->rejectedDrains.incrementAndGet());fail("second late SSE exceeded capacity");}catch(HttpException full){assertEquals(503,full.status());}
        send(two,"full");assertEquals("full",Http.body(second.get(5,TimeUnit.SECONDS)));assertEquals(0,rejectedDrains.get());assertEquals(0,rejectedAborts.get());assertEquals(1,((TinyHttpdExchange)one).slots().open());
        release.countDown();assertTrue(first.get(5,TimeUnit.SECONDS).contains("later"));until(()->((TinyHttpdExchange)one).slots().open()==0,"slot not returned");assertEquals(1,drains.get());assertEquals(0,cancels.get());
    }
    // FR-APP-023 AC-3 AC-6; IR-HTTP-009 AC-2 AC-3: abort during accepted attach cancels the unstarted drain once.
    @Test(timeout=20000) public void abortDuringAcceptedAttachCancelsDrainOnceAndReturnsSlot() throws Exception {
        AtomicInteger attached=new AtomicInteger(),drained=new AtomicInteger(),cancelled=new AtomicInteger(),notices=new AtomicInteger();
        start(ex->{ex.onAbort(notices::incrementAndGet);try{ex.eventStream(200,Collections.emptyList(),new StreamDrain(){
            public void attach(OutputStream out){attached.incrementAndGet();ex.abort();}public void drain(OutputStream out){drained.incrementAndGet();}public void abort(){cancelled.incrementAndGet();}
        });}catch(IOException|IllegalStateException expected){}});
        Future<String> reply=get("/attach-abort");HttpExchange ex=returned();reply.get(5,TimeUnit.SECONDS);
        until(()->((TinyHttpdExchange)ex).slots().open()==0,"accepted slot leaked");assertEquals(1,attached.get());assertEquals(0,drained.get());assertEquals(1,cancelled.get());assertEquals(1,notices.get());ex.abort();assertEquals(1,cancelled.get());assertEquals(1,notices.get());closed(ex);
    }
    // FR-APP-023 AC-3 AC-6; IR-HTTP-009 AC-2 AC-3: shutdown deadline does not release an actually running drain's slot early.
    @Test(timeout=20000) public void forcedStopOfLateRunningDrainNotifiesOnceAndKeepsSlotUntilExit() throws Exception {
        start(ex->{});Future<String> reply=get("/stop-sse");HttpExchange ex=returned();CountDownLatch running=new CountDownLatch(1),release=new CountDownLatch(1);releases.add(release);
        AtomicInteger notices=new AtomicInteger();ex.onAbort(notices::incrementAndGet);
        ex.eventStream(200,Collections.emptyList(),out->{running.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}try{ex.finish();}catch(IOException expected){}});
        assertTrue(running.await(5,TimeUnit.SECONDS));long begin=System.nanoTime();engine.stop(100,TimeUnit.MILLISECONDS);
        assertTrue("stop exceeded bounded deadline",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)<1500);assertEquals(1,notices.get());assertEquals(1,((TinyHttpdExchange)ex).slots().open());
        release.countDown();reply.get(5,TimeUnit.SECONDS);until(()->((TinyHttpdExchange)ex).slots().open()==0,"running drain did not retire");ex.abort();assertEquals(1,notices.get());closed(ex);
    }
    private Future<String> upload(){String body="--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.bin\"\r\n\r\npayload\r\n--b--\r\n";return clients.submit(()->Http.send(engine.boundPort(),"POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: multipart/form-data; boundary=b\r\nContent-Length: "+body.length()+"\r\n\r\n"+body));}
    // FR-APP-023 AC-1 AC-3 AC-6: response completion cannot revoke a pending producer's upload lease.
    @Test(timeout=20000) public void deferredSuccessKeepsUploadUntilSettlementAfterResponse() throws Exception {
        File dir=temporary.newFolder("success");RestApp a=core();a.multipart(o->o.memoryThreshold(1).tempDirectory(dir));Deferred work=Deferred.pending();AtomicReference<Response> response=new AtomicReference<>();AtomicReference<UploadFile> file=new AtomicReference<>();
        a.methodAsync("POST","/upload",(q,s,n)->{file.set(q.requireFile("file"));response.set(s);return work;});a.listen(0);Future<String> reply=upload();returned();pending(reply);assertEquals(1,dir.list().length);
        response.get().send("done");assertEquals("done",Http.body(reply.get(5,TimeUnit.SECONDS)));assertEquals("payload",new String(file.get().bytes(),StandardCharsets.UTF_8));assertEquals(1,dir.list().length);assertEquals(1,log.info().size());
        assertTrue(work.resolve());assertFalse(work.resolve());until(()->dir.list().length==0,"settled upload not removed");released(file.get());until(()->log.info().size()==1,"settled completion log missing");
    }
    // FR-APP-023 AC-1 AC-3 AC-6: rejection preserves the actual error and request upload through its error callback.
    @Test(timeout=20000) public void deferredRejectionRunsErrorLayerAndReleasesUploadOnce() throws Exception {
        File dir=temporary.newFolder("reject");RestApp a=core();a.multipart(o->o.memoryThreshold(1).tempDirectory(dir));Deferred work=Deferred.pending();RuntimeException failure=new IllegalStateException("deferred");AtomicReference<UploadFile> file=new AtomicReference<>();AtomicInteger errors=new AtomicInteger();
        a.methodAsync("POST","/upload",(q,s,n)->{file.set(q.requireFile("file"));return work;});a.use((ErrorMiddleware)(e,q,s,n)->{assertSame(failure,e);assertEquals("payload",new String(file.get().bytes(),StandardCharsets.UTF_8));errors.incrementAndGet();s.status(409).send("rejected");});a.listen(0);
        Future<String> reply=upload();returned();pending(reply);assertTrue(work.reject(failure));String result=reply.get(5,TimeUnit.SECONDS);assertEquals(409,Http.status(result));assertEquals("rejected",Http.body(result));assertEquals(1,errors.get());until(()->dir.list().length==0,"rejected upload not removed");released(file.get());until(()->log.info().size()==1,"rejection logged more/less than once");
    }
    // IR-HTTP-009 AC-2 AC-3 AC-4 AC-6; FR-APP-023 AC-3: real adapter abort must reach common execution cleanup.
    @Test(timeout=20000) public void detectedAbortReleasesPendingDeferredUploadAndLateSettlementCannotResurrect() throws Exception {
        File dir=temporary.newFolder("abort");RestApp a=core();a.multipart(o->o.memoryThreshold(1).tempDirectory(dir));Deferred work=Deferred.pending();AtomicReference<UploadFile> file=new AtomicReference<>();
        a.methodAsync("POST","/upload",(q,s,n)->{file.set(q.requireFile("file"));return work;});a.listen(0);Future<String> reply=upload();HttpExchange ex=returned();pending(reply);assertEquals(1,dir.list().length);
        ex.abort();ex.abort();reply.get(5,TimeUnit.SECONDS);until(()->dir.list().length==0,"detected abort did not release core upload");released(file.get());until(()->log.info().size()==1,"abort log missing");assertTrue(log.info().get(0),log.info().get(0).contains("aborted"));
        work.resolve();assertEquals(0,dir.list().length);assertEquals(1,log.info().size());closed(ex);
    }
    // FR-APP-023 AC-3 AC-6: actual adapter/core shutdown cancels pending leases within the requested budget.
    @Test(timeout=20000) public void shutdownReclaimsPendingDeferredUploadAndOwnedServiceOnce() throws Exception {
        File dir=temporary.newFolder("stop");RestApp a=core();a.multipart(o->o.memoryThreshold(1).tempDirectory(dir));Deferred work=Deferred.pending();AtomicReference<UploadFile> file=new AtomicReference<>();AtomicInteger owned=new AtomicInteger();
        a.onStart(context->context.own(owned::incrementAndGet));a.methodAsync("POST","/upload",(q,s,n)->{file.set(q.requireFile("file"));return work;});a.listen(0);Future<String> reply=upload();returned();pending(reply);
        long begin=System.nanoTime();a.shutdown(100,TimeUnit.MILLISECONDS);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)<2000);reply.get(5,TimeUnit.SECONDS);
        assertEquals(AppState.TERMINATED,a.state());assertEquals(1,owned.get());assertEquals(0,dir.list().length);released(file.get());work.resolve();a.shutdown(0,TimeUnit.MILLISECONDS);assertEquals(1,owned.get());assertEquals(1,log.info().size());
    }
    // FR-APP-023 AC-3 AC-6; IR-HTTP-009 AC-3: a late attach callback owns the response/slot until it actually exits.
    @Test(timeout=20000) public void forcedStopDuringLateAttachKeepsSlotUntilSetupOwnerExits()throws Exception{
        start(ex->{});Future<String> reply=get("/stop-attach");HttpExchange ex=returned();CountDownLatch attaching=new CountDownLatch(1),release=new CountDownLatch(1);releases.add(release);
        AtomicInteger cancelled=new AtomicInteger(),drained=new AtomicInteger();AtomicReference<Throwable> failed=new AtomicReference<>();
        Future<?> setup=clients.submit(()->{try{ex.eventStream(200,Collections.emptyList(),new StreamDrain(){
            public void attach(OutputStream out){attaching.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}}
            public void drain(OutputStream out){drained.incrementAndGet();}public void abort(){cancelled.incrementAndGet();}
        });}catch(Throwable failure){failed.set(failure);}});
        assertTrue(attaching.await(5,TimeUnit.SECONDS));assertEquals(1,((TinyHttpdExchange)ex).slots().open());long begin=System.nanoTime();engine.stop(100,TimeUnit.MILLISECONDS);
        assertTrue("stop deadline exceeded",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)<1500);
        assertEquals("late attach still owns response and slot",1,((TinyHttpdExchange)ex).slots().open());
        release.countDown();setup.get(5,TimeUnit.SECONDS);assertTrue(String.valueOf(failed.get()),failed.get() instanceof IOException);reply.get(5,TimeUnit.SECONDS);
        until(()->((TinyHttpdExchange)ex).slots().open()==0,"completed setup did not release slot");assertEquals(0,drained.get());assertEquals(1,cancelled.get());closed(ex);
    }

    private static void cancellationUnlocked(HttpExchange ex, AtomicReference<Throwable> failure) {
        try {
            java.lang.reflect.Field field = TinyHttpdExchange.class.getDeclaredField("state");
            field.setAccessible(true);
            assertFalse("cancellation holds IO monitor", Thread.holdsLock(ex));
            assertFalse("cancellation holds lifecycle monitor", Thread.holdsLock(field.get(ex)));
        } catch (Throwable problem) { failure.compareAndSet(null, problem); }
    }
    private static void brokenCancellation(boolean error) {
        if (error) throw new AssertionError("broken drain cancellation");
        throw new IllegalStateException("broken drain cancellation");
    }
    private void throwingCancellationDuringLateAttach(boolean error) throws Exception {
        start(ex -> {});
        Future<String> reply = get("/throwing-attach");
        HttpExchange ex = returned();
        AtomicInteger notices = new AtomicInteger(), otherNotices = new AtomicInteger();
        AtomicReference<Throwable> lockFailure = new AtomicReference<>();
        ex.onAbort(() -> { cancellationUnlocked(ex, lockFailure); notices.incrementAndGet(); });
        Future<String> otherReply = get("/other-pending");
        HttpExchange other = returned();
        other.onAbort(() -> { cancellationUnlocked(other, lockFailure); otherNotices.incrementAndGet(); });
        CountDownLatch attaching = new CountDownLatch(1), release = new CountDownLatch(1);
        releases.add(release);
        AtomicInteger cancelled = new AtomicInteger(), drained = new AtomicInteger();
        AtomicReference<Throwable> setupFailure = new AtomicReference<>(), stopFailure = new AtomicReference<>();
        Future<?> setup = clients.submit(() -> {
            try {
                ex.eventStream(200, Collections.emptyList(), new StreamDrain() {
                    public void attach(OutputStream out) {
                        attaching.countDown();
                        while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
                    }
                    public void drain(OutputStream out) { drained.incrementAndGet(); }
                    public void abort() {
                        cancellationUnlocked(ex, lockFailure);
                        cancelled.incrementAndGet();
                        brokenCancellation(error);
                    }
                });
            } catch (Throwable problem) { setupFailure.set(problem); }
        });
        assertTrue(attaching.await(5, TimeUnit.SECONDS));
        long begin = System.nanoTime();
        try { engine.stop(100, TimeUnit.MILLISECONDS); } catch (Throwable problem) { stopFailure.set(problem); }
        assertTrue("throwing cancellation exceeded stop deadline",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1500);
        until(() -> cancelled.get() == 1, "accepted drain was not cancelled");
        assertFalse("attach unexpectedly stopped owning response", setup.isDone());
        // Wait for the interrupted worker's exceptional unwind to become observable, keeping attach blocked.
        long observationEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150);
        while (((TinyHttpdExchange) ex).slots().open() == 1 && System.nanoTime() < observationEnd) Thread.sleep(5);
        assertEquals("throwing cancellation released the still-owned attach slot", 1,
                ((TinyHttpdExchange) ex).slots().open());
        assertNull("user cancellation escaped adapter stop", stopFailure.get());
        assertEquals(1, notices.get());
        assertEquals("stop skipped another active exchange", 1, otherNotices.get());
        assertNull(lockFailure.get());
        release.countDown();
        setup.get(5, TimeUnit.SECONDS);
        assertTrue(String.valueOf(setupFailure.get()), setupFailure.get() instanceof IOException);
        reply.get(5, TimeUnit.SECONDS);
        otherReply.get(5, TimeUnit.SECONDS);
        until(() -> ((TinyHttpdExchange) ex).slots().open() == 0, "slot retained after setup exited");
        assertEquals(1, cancelled.get()); assertEquals(0, drained.get());
        ex.abort(); other.abort();
        assertEquals(1, cancelled.get()); assertEquals(1, notices.get()); assertEquals(1, otherNotices.get());
        closed(ex); closed(other);
    }
    // FR-APP-023 AC-3/6; IR-HTTP-009 AC-3: user cancellation exceptions cannot
    // release a slot or engine response before an accepted late attach actually exits.
    @Test(timeout=20000) public void runtimeThrowingCancellationKeepsLateAttachOwnership() throws Exception {
        throwingCancellationDuringLateAttach(false);
    }
    // FR-APP-023 AC-3/6; IR-HTTP-009 AC-3: Error is contained at the same ownership boundary.
    @Test(timeout=20000) public void errorThrowingCancellationKeepsLateAttachOwnership() throws Exception {
        throwingCancellationDuringLateAttach(true);
    }
    private void throwingCancellationCannotBreakStopLoop(boolean error) throws Exception {
        CountDownLatch keepHandler = new CountDownLatch(1), accepted = new CountDownLatch(1);
        releases.add(keepHandler);
        AtomicInteger cancelled = new AtomicInteger(), notices = new AtomicInteger(), otherNotices = new AtomicInteger(),
                drained = new AtomicInteger();
        AtomicReference<Throwable> lockFailure = new AtomicReference<>();
        start(ex -> {
            if (ex.rawPath().equals("/owned-in-handler")) {
                ex.onAbort(() -> { cancellationUnlocked(ex, lockFailure); notices.incrementAndGet(); });
                try {
                    ex.eventStream(200, Collections.emptyList(), new StreamDrain() {
                        public void drain(OutputStream out) { drained.incrementAndGet(); }
                        public void abort() {
                            cancellationUnlocked(ex, lockFailure);
                            cancelled.incrementAndGet();
                            brokenCancellation(error);
                        }
                    });
                } catch (IOException problem) { throw new UncheckedIOException(problem); }
                accepted.countDown();
            } else {
                ex.onAbort(() -> { cancellationUnlocked(ex, lockFailure); otherNotices.incrementAndGet(); });
            }
            // Keep workers in user callbacks so engine interruption cannot claim cancellation first.
            while (keepHandler.getCount() > 0) try { keepHandler.await(); } catch (InterruptedException ignored) { }
        });
        Future<String> reply = get("/owned-in-handler");
        HttpExchange ex = entered.poll(5, TimeUnit.SECONDS);
        assertNotNull(ex); assertTrue(accepted.await(5, TimeUnit.SECONDS));
        Future<String> otherReply = get("/ordinary");
        HttpExchange other = entered.poll(5, TimeUnit.SECONDS);
        assertNotNull(other);
        // Capture happens before callback registration; install an additional late-safe observer ourselves.
        AtomicInteger observedOther = new AtomicInteger();
        other.onAbort(observedOther::incrementAndGet);
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        long begin = System.nanoTime();
        try { engine.stop(100, TimeUnit.MILLISECONDS); } catch (Throwable problem) { stopFailure.set(problem); }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1500);
        assertNull("cancellation escaped stop and can truncate the active-exchange loop", stopFailure.get());
        assertEquals(1, cancelled.get()); assertEquals(1, notices.get());
        assertEquals("stop did not abort remaining ordinary exchange", 1, observedOther.get());
        assertNull(lockFailure.get());
        keepHandler.countDown();
        reply.get(5, TimeUnit.SECONDS); otherReply.get(5, TimeUnit.SECONDS);
        until(() -> ((TinyHttpdExchange) ex).slots().open() == 0, "cancelled slot leaked");
        assertEquals("cancelled drain was started", 0, drained.get());
        ex.abort(); other.abort();
        assertEquals(1, cancelled.get()); assertEquals(1, notices.get()); assertEquals(1, observedOther.get());
    }
    // FR-APP-023 AC-3/6; IR-HTTP-009 AC-3: adapter-wide abort must survive a user RuntimeException.
    @Test(timeout=20000) public void runtimeThrowingCancellationCannotEscapeStopOrSkipOtherExchanges() throws Exception {
        throwingCancellationCannotBreakStopLoop(false);
    }
    // FR-APP-023 AC-3/6; IR-HTTP-009 AC-3: adapter-wide abort must survive a user Error.
    @Test(timeout=20000) public void errorThrowingCancellationCannotEscapeStopOrSkipOtherExchanges() throws Exception {
        throwingCancellationCannotBreakStopLoop(true);
    }

    private static String fixedResponse(InputStream input)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int tail=0;
        while(tail!=0x0d0a0d0a){int b=input.read();if(b<0)throw new EOFException("response head ended");bytes.write(b);tail=(tail<<8)|b;}
        String head=new String(bytes.toByteArray(),StandardCharsets.ISO_8859_1);String length=Http.header(head,"Content-Length");if(length==null)throw new IOException("fixed response length missing");
        for(int remaining=Integer.parseInt(length);remaining>0;remaining--){int b=input.read();if(b<0)throw new EOFException("fixed response body ended");bytes.write(b);}
        return new String(bytes.toByteArray(),StandardCharsets.ISO_8859_1);
    }
    // FR-APP-023 AC-3 AC-7: an unread request input remains owned by a pending producer after response bytes finish.
    @Test(timeout=20000) public void pendingDeferredCanFirstReadJsonAfterClientObservesCompletedResponse()throws Exception{
        RestApp a=core();AtomicInteger decodes=new AtomicInteger();a.jsonCodec(new JsonCodec(){
            @SuppressWarnings("unchecked") public <T>T decode(Reader reader,java.lang.reflect.Type type)throws IOException{decodes.incrementAndGet();StringBuilder raw=new StringBuilder();for(int c;(c=reader.read())!=-1;)raw.append((char)c);if(raw.length()<2||raw.charAt(0)!='"'||raw.charAt(raw.length()-1)!='"')throw new IOException("expected JSON string");return (T)raw.substring(1,raw.length()-1);}
            public void encode(Object value,Writer writer)throws IOException{throw new UnsupportedOperationException();}
        });
        Deferred work=Deferred.pending();AtomicReference<Request> request=new AtomicReference<>();
        a.post("/control",(q,s)->s.send(q.body(String.class)));
        a.methodAsync("POST","/unread",(q,s,n)->{request.set(q);s.send("ack");return work;});a.listen(0);
        String payload="\"delayed-payload\"";
        String control=Http.send(engine.boundPort(),"POST /control HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "+payload.length()+"\r\n\r\n"+payload);
        assertEquals(control,200,Http.status(control));assertEquals("delayed-payload",Http.body(control));returned();assertEquals(1,decodes.get());decodes.set(0);
        try(Socket socket=new Socket("127.0.0.1",engine.boundPort())){
            socket.setSoTimeout(5000);socket.getOutputStream().write(("POST /unread HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\nContent-Type: application/json\r\nContent-Length: "+payload.length()+"\r\n\r\n"+payload).getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            String observed=fixedResponse(socket.getInputStream());assertEquals(200,Http.status(observed));assertEquals("ack",Http.body(observed));returned();assertFalse(work.isDone());assertEquals(0,decodes.get());
            CountDownLatch responseObserved=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
            Future<?> producer=clients.submit(()->{try{responseObserved.await();Thread.sleep(150);value.set(request.get().body(String.class));}catch(Throwable problem){failure.set(problem);}finally{work.resolve();}});
            responseObserved.countDown();producer.get(5,TimeUnit.SECONDS);assertNull("first body read after completed response lost its pending lease: "+failure.get()+", status="+(failure.get() instanceof HttpException?((HttpException)failure.get()).status():"none")+", decoderCalls="+decodes.get(),failure.get());
            assertEquals("delayed-payload",value.get());assertEquals(1,decodes.get());assertEquals(2,log.info().size());
        }
    }

}
