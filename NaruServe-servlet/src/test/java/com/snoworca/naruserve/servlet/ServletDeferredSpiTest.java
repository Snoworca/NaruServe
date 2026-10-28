package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.testing.TestSupport;
import java.io.*;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.*;
import static org.junit.Assert.*;

/** Existing SPI callbacks over real Jetty: no new core routing API is needed to establish adapter ownership. */
public class ServletDeferredSpiTest {
    @Rule public final ContainerRule container = new ContainerRule();
    private ServletAdapter adapter;
    private final ExecutorService clients = Executors.newFixedThreadPool(8);
    private final ExecutorService callbacks = Executors.newFixedThreadPool(4);
    private final BlockingQueue<HttpExchange> exchanges = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> returned = new LinkedBlockingQueue<>();
    private final AtomicInteger starts = new AtomicInteger(), completes = new AtomicInteger();
    private volatile long ordinaryTimeout;
    private final List<CountDownLatch> releases = new ArrayList<>();

    @After public void cleanup() throws Exception {
        for(CountDownLatch release:releases) release.countDown();
        if(adapter!=null) {adapter.stop(0,TimeUnit.MILLISECONDS);adapter.release();}
        callbacks.shutdownNow();clients.shutdownNow();
        assertTrue("callback threads leaked",callbacks.awaitTermination(5,TimeUnit.SECONDS));
        assertTrue("client threads leaked",clients.awaitTermination(12,TimeUnit.SECONDS));
    }
    private void start(ExchangeHandler handler) throws Exception { start(handler,true); }
    private void start(ExchangeHandler handler,boolean asyncSupported) throws Exception {
        adapter=new ServletAdapter();adapter.bind(TestSupport.settings().maxSseStreams(1)
                .errorRenderer(status -> "{\"error\":{\"status\":"+status+"}}") .build(),handler);
        container.filter((request,response,chain)->{
            HttpServletRequestWrapper wrapper=new HttpServletRequestWrapper((HttpServletRequest)request) {
                @Override public AsyncContext startAsync() {
                    AsyncContext real=super.startAsync();starts.incrementAndGet();
                    if(ordinaryTimeout>0) real.setTimeout(ordinaryTimeout);
                    return RecordingRequest.delegating(AsyncContext.class,real,name->{if(name.equals("complete"))completes.incrementAndGet();});
                }
            };
            try {chain.doFilter(wrapper,response);} finally {returned.offer(wrapper.getRequestURI());}
        });
        container.servlet(new NaruServeServlet(adapter,""),"/*","/",holder -> holder.setAsyncSupported(asyncSupported));
    }
    private Future<SharedApp.Reply> request(String path) { return clients.submit(()->SharedApp.http(container.port(),"GET",path,null)); }
    private HttpExchange next() throws Exception {HttpExchange ex=exchanges.poll(5,TimeUnit.SECONDS);assertNotNull("handler did not receive exchange",ex);return ex;}
    private void returned() throws Exception {assertNotNull("initial service callback did not return",returned.poll(5,TimeUnit.SECONDS));}
    private static void pending(Future<?> reply) throws Exception {
        try {reply.get(150,TimeUnit.MILLISECONDS);fail("request ended before its SPI completion");}
        catch(TimeoutException expected) { }
    }
    private static void send(HttpExchange ex,String contentType,String body) throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        List<Map.Entry<String,String>> headers=Collections.singletonList(new AbstractMap.SimpleImmutableEntry<>("Content-Type",contentType));
        ex.commit(200,headers,bytes.length).write(bytes);ex.finish();
    }
    private int inFlight() throws Exception {Field field=ServletAdapter.class.getDeclaredField("inFlight");field.setAccessible(true);return field.getInt(adapter);}
    private void idle() throws Exception {assertTrue("in-flight exchange not released",SharedApp.until(()->{try{return inFlight()==0;}catch(Exception e){throw new AssertionError(e);}},5,TimeUnit.SECONDS));assertEquals(0,adapter.pool().open());}
    private void completeOnce(int expected) throws Exception {assertTrue("async completion not observed",SharedApp.until(()->completes.get()==expected,5,TimeUnit.SECONDS));assertEquals(expected,completes.get());}
    private static void closed(HttpExchange ex) throws Exception {
        try {ex.commit(200,Collections.emptyList(),0);fail("closed exchange resurrected");}
        catch(IOException|IllegalStateException expected) { }
    }

    // FR-SVL-009 AC-1 AC-2 AC-5
    @Test(timeout=20000) public void ordinaryRequestStaysPendingUntilLaterTextAndCompletesOnce() throws Exception {
        start(exchanges::add);Future<SharedApp.Reply> reply=request("/pending");HttpExchange ex=next();returned();
        pending(reply);assertEquals(1,inFlight());assertEquals(0,adapter.pool().open());
        send(ex,"text/plain; charset=utf-8","later");ex.finish();
        SharedApp.Reply result=reply.get(5,TimeUnit.SECONDS);assertEquals(200,result.status);assertEquals("later",result.text());
        completeOnce(1);idle();closed(ex);
    }
    // FR-SVL-009 AC-1 AC-2
    @Test(timeout=20000) public void lateJsonAndOrdinaryStreamDoNotUseSseSlots() throws Exception {
        start(exchanges::add);
        for(String body:new String[]{"{\"answer\":42}",new String(SharedApp.pattern(90000),StandardCharsets.ISO_8859_1)}) {
            Future<SharedApp.Reply> reply=request("/later");HttpExchange ex=next();returned();pending(reply);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);OutputStream out=ex.commit(200,Collections.emptyList(),bytes.length);
            try(InputStream in=new ByteArrayInputStream(bytes)){byte[] b=new byte[1024];for(int n;(n=in.read(b))>=0;)out.write(b,0,n);}
            ex.finish();assertArrayEquals(bytes,reply.get(5,TimeUnit.SECONDS).body);assertEquals(0,adapter.pool().open());idle();
        }
        completeOnce(2);
    }
    // FR-SVL-009 AC-1 AC-2: synchronous completion remains valid even if async is unsupported.
    @Test(timeout=20000) public void synchronousCompletedRequestNeedsNoAsyncContext() throws Exception {
        start(ex->{try{send(ex,"text/plain","sync");}catch(IOException e){throw new UncheckedIOException(e);}},false);
        SharedApp.Reply result=request("/sync").get(5,TimeUnit.SECONDS);assertEquals("sync",result.text());assertEquals(200,result.status);
        assertEquals(0,starts.get());assertEquals(0,completes.get());idle();
    }
    // FR-SVL-009 AC-2 AC-4: callback completion may race with the end of the initial service invocation.
    @Test(timeout=30000) public void completionRacingInitialReturnNeverLosesOrDuplicatesOwnership() throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        start(ex->callbacks.submit(()->{try{send(ex,"text/plain","race");}catch(Throwable e){failure.compareAndSet(null,e);ex.abort();}}));
        for(int i=0;i<30;i++)assertEquals("race",request("/race").get(5,TimeUnit.SECONDS).text());
        assertNull(failure.get());idle();
        assertTrue(SharedApp.until(()->completes.get()==starts.get(),5,TimeUnit.SECONDS));assertEquals(starts.get(),completes.get());
    }
    // FR-SVL-009 AC-3 AC-5
    @Test(timeout=20000) public void eventStreamRegisteredAfterInitialReturnDrainsAndReleasesSlot() throws Exception {
        start(exchanges::add);Future<SharedApp.Reply> reply=request("/events");HttpExchange ex=next();returned();pending(reply);
        AtomicInteger drainCalls=new AtomicInteger(),abortCalls=new AtomicInteger();AtomicReference<String> thread=new AtomicReference<>();
        ex.eventStream(200,Collections.singletonList(new AbstractMap.SimpleImmutableEntry<>("Content-Type","text/event-stream")),new StreamDrain(){
            @Override public void drain(OutputStream out)throws IOException{drainCalls.incrementAndGet();thread.set(Thread.currentThread().getName());out.write("data: later\n\n".getBytes(StandardCharsets.UTF_8));}
            @Override public void abort(){abortCalls.incrementAndGet();}
        });
        assertEquals("data: later\n\n",reply.get(5,TimeUnit.SECONDS).text());assertEquals(1,drainCalls.get());assertEquals(0,abortCalls.get());
        assertTrue(thread.get(),thread.get().startsWith("naruserve-servlet-sse-"));completeOnce(1);idle();
    }
    // FR-SVL-009 AC-1 AC-3 AC-4: ordinary pending requests do not consume the single configured SSE slot.
    @Test(timeout=20000) public void ordinaryPendingAndLateStreamHaveSeparateCapacityOwnership() throws Exception {
        start(exchanges::add);List<Future<SharedApp.Reply>> replies=new ArrayList<>();List<HttpExchange> pending=new ArrayList<>();
        for(int i=0;i<3;i++){replies.add(request("/ordinary"+i));pending.add(next());returned();}
        Future<SharedApp.Reply> events=request("/events");HttpExchange stream=next();returned();CountDownLatch release=new CountDownLatch(1);releases.add(release);
        stream.eventStream(200,Collections.emptyList(),out->{out.write('x');try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertEquals(4,inFlight());assertEquals(1,adapter.pool().open());
        for(int i=0;i<pending.size();i++){send(pending.get(i),"text/plain","ok");assertEquals("ok",replies.get(i).get(5,TimeUnit.SECONDS).text());}
        assertEquals(1,inFlight());assertEquals(1,adapter.pool().open());release.countDown();assertEquals("x",events.get(5,TimeUnit.SECONDS).text());completeOnce(4);idle();
    }
    // FR-SVL-009 AC-4 AC-5: use the actual container async timeout, not a manually invoked fake listener.
    @Test(timeout=20000) public void containerTimeoutClosesOrdinaryPendingAndRejectsLateCallback() throws Exception {
        ordinaryTimeout=150;start(exchanges::add);Future<SharedApp.Reply> reply=request("/timeout");HttpExchange ex=next();returned();
        reply.get(5,TimeUnit.SECONDS);completeOnce(1);idle();closed(ex);
    }
    // FR-SVL-009 AC-4 AC-5: a failed ordinary response write must release adapter ownership too.
    @Test(timeout=20000) public void clientResetDuringLaterWriteAbortsAndReleasesPendingExchange() throws Exception {
        start(exchanges::add);Socket client=new Socket("127.0.0.1",container.port());client.setSoLinger(true,0);
        client.getOutputStream().write("GET /reset HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        HttpExchange ex=next();returned();client.close();
        Future<Boolean> failed=callbacks.submit(()->{try{OutputStream out=ex.commit(200,Collections.emptyList(),-1);byte[] b=new byte[8192];for(int i=0;i<4096;i++){out.write(b);out.flush();}return false;}catch(IOException expected){return true;}});
        assertTrue("reset client never failed write",failed.get(5,TimeUnit.SECONDS));completeOnce(1);idle();closed(ex);
    }
    // FR-SVL-009 AC-4: a pending ordinary request stays in-flight until explicit completion, not initial return.
    @Test(timeout=20000) public void stopWaitsForOrdinaryPendingUntilItFinishes() throws Exception {
        start(exchanges::add);Future<SharedApp.Reply> reply=request("/pending");HttpExchange ex=next();returned();pending(reply);
        Future<?> stop=callbacks.submit(()->adapter.stop(8,TimeUnit.SECONDS));assertTrue(SharedApp.until(adapter::stopping,5,TimeUnit.SECONDS));pending(stop);
        send(ex,"text/plain","finished");assertEquals("finished",reply.get(5,TimeUnit.SECONDS).text());stop.get(5,TimeUnit.SECONDS);completeOnce(1);idle();
    }
    // FR-SVL-009 AC-4 AC-5
    @Test(timeout=20000) public void shutdownDeadlineAbortsPendingOnceAndCannotResurrect() throws Exception {
        start(exchanges::add);Future<SharedApp.Reply> reply=request("/pending");HttpExchange ex=next();returned();pending(reply);
        long at=System.nanoTime();adapter.stop(100,TimeUnit.MILLISECONDS);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at)<1500);
        reply.get(5,TimeUnit.SECONDS);ex.abort();ex.abort();completeOnce(1);idle();closed(ex);
        assertEquals(503,request("/refused").get(5,TimeUnit.SECONDS).status);
    }
    // FR-SVL-009 AC-2 AC-4: unsupported registration has an explicit error instead of an early empty200.
    @Test(timeout=20000) public void unsupportedAsyncConfigurationAnswers500AndReleasesAdmission() throws Exception {
        start(exchanges::add,false);SharedApp.Reply result=request("/pending").get(5,TimeUnit.SECONDS);
        assertEquals(500,result.status);assertTrue(result.text(),result.text().startsWith("{\"error\""));assertEquals(0,starts.get());idle();
    }
    // FR-SVL-009 AC-2 AC-4: concurrent explicit failure/completion must not double-complete or decrement belowzero.
    @Test(timeout=20000) public void abortAndFinishRaceCompletesEachContextOnce() throws Exception {
        start(exchanges::add);
        for(int i=0;i<12;i++){
            Future<SharedApp.Reply> reply=request("/race");HttpExchange ex=next();returned();CountDownLatch go=new CountDownLatch(1);
            Future<?> finish=callbacks.submit(()->{try{go.await();ex.finish();}catch(IOException expected){}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            Future<?> abort=callbacks.submit(()->{try{go.await();ex.abort();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            go.countDown();finish.get(5,TimeUnit.SECONDS);abort.get(5,TimeUnit.SECONDS);reply.get(5,TimeUnit.SECONDS);closed(ex);idle();
        }
        completeOnce(12);
    }
    // FR-SVL-009 AC-3 AC-4: both late drain failure classes abort ownership, rather than only normal completion.
    @Test(timeout=20000) public void lateDrainFailuresAbortAndReleaseContextInflightAndSlotOnce() throws Exception {
        start(exchanges::add);
        for(boolean ioFailure:new boolean[]{true,false}) {
            Future<SharedApp.Reply> reply=request("/failed-stream");HttpExchange ex=next();returned();pending(reply);
            AtomicInteger calls=new AtomicInteger();CountDownLatch drained=new CountDownLatch(1);
            ex.eventStream(200,Collections.emptyList(),out->{
                calls.incrementAndGet();drained.countDown();
                if(ioFailure)throw new IOException("late drain io");
                throw new IllegalStateException("late drain runtime");
            });
            assertTrue(drained.await(5,TimeUnit.SECONDS));
            try {reply.get(5,TimeUnit.SECONDS);}catch(ExecutionException ended){assertTrue(ended.getCause() instanceof IOException);}
            completeOnce(ioFailure?1:2);idle();assertEquals(1,calls.get());
            try {((ServletExchange)ex).checkNotAborted();fail("drain failure did not abort exchange");}catch(IOException expected){}
            ex.abort();ex.abort();assertEquals(ioFailure?1:2,completes.get());closed(ex);
        }
    }
    // FR-SVL-009 AC-3 AC-4: late promotion still honors the existing single-slot limit and leaves rejection writable.
    @Test(timeout=20000) public void secondLateSseIsRejectedWithoutTakingOrLeakingTheOccupiedSlot() throws Exception {
        start(exchanges::add);Future<SharedApp.Reply> first=request("/stream-one");HttpExchange open=next();returned();
        CountDownLatch release=new CountDownLatch(1),draining=new CountDownLatch(1);releases.add(release);
        open.eventStream(200,Collections.emptyList(),out->{draining.countDown();out.write("done".getBytes(StandardCharsets.UTF_8));try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(draining.await(5,TimeUnit.SECONDS));assertEquals(1,adapter.pool().open());
        Future<SharedApp.Reply> second=request("/stream-two");HttpExchange rejected=next();returned();AtomicInteger rejectedDrains=new AtomicInteger();
        try {rejected.eventStream(200,Collections.emptyList(),out->rejectedDrains.incrementAndGet());fail("second late stream exceeded capacity");}
        catch(com.snoworca.naruserve.HttpException expected){assertEquals(503,expected.status());}
        byte[] body="full".getBytes(StandardCharsets.UTF_8);rejected.commit(503,Collections.emptyList(),body.length).write(body);rejected.finish();
        SharedApp.Reply rejection=second.get(5,TimeUnit.SECONDS);assertEquals(503,rejection.status);assertEquals("full",rejection.text());
        assertEquals(0,rejectedDrains.get());assertEquals(1,adapter.pool().open());assertEquals(1,inFlight());
        release.countDown();assertEquals("done",first.get(5,TimeUnit.SECONDS).text());completeOnce(2);idle();closed(rejected);
    }

}
