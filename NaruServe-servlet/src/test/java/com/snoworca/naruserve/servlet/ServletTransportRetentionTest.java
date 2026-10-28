package com.snoworca.naruserve.servlet;

import static org.junit.Assert.*;
import com.snoworca.naruserve.spi.*;
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
import org.junit.*;

/** Actual container completion is independent of the already-delivered fixed-length wire response. */
public class ServletTransportRetentionTest {
    @Rule public final ContainerRule container=new ContainerRule();
    private final ServletAdapter adapter=new ServletAdapter();
    private final ExecutorService threads=Executors.newFixedThreadPool(4);
    private final BlockingQueue<HttpExchange> exchanges=new LinkedBlockingQueue<>();
    private final BlockingQueue<String> returned=new LinkedBlockingQueue<>();
    private final AtomicInteger starts=new AtomicInteger(),completes=new AtomicInteger(),aborts=new AtomicInteger(),bodyOpens=new AtomicInteger();
    private final ConcurrentMap<String,HttpExchange> byPath=new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> lockFailure=new AtomicReference<>();
    private final AtomicReference<Runnable> onStart=new AtomicReference<>();
    private final List<Runnable> releases=new ArrayList<>();
    private volatile long timeout;

    @After public void cleanup() throws Exception {
        for(Runnable release:releases)release.run();
        adapter.stop(0,TimeUnit.MILLISECONDS);adapter.release();
        threads.shutdownNow();assertTrue("test threads leaked",threads.awaitTermination(12,TimeUnit.SECONDS));
    }
    private void start(ExchangeHandler handler) throws Exception {
        adapter.bind(TestSupport.settings().maxSseStreams(1).build(),ex->{byPath.put(ex.rawPath(),ex);ex.onAbort(aborts::incrementAndGet);exchanges.add(ex);handler.handle(ex);});
        container.filter((request,response,chain)->{
            HttpServletRequestWrapper wrapped=new HttpServletRequestWrapper((HttpServletRequest)request){
                @Override public ServletInputStream getInputStream()throws IOException{bodyOpens.incrementAndGet();return super.getInputStream();}
                @Override public AsyncContext startAsync(){
                    AsyncContext real=super.startAsync();starts.incrementAndGet();if(timeout>0)real.setTimeout(timeout);
                    final HttpExchange exchange=byPath.get(getRequestURI());
                    AsyncContext tracked=RecordingRequest.delegating(AsyncContext.class,real,name->{
                        if(name.equals("complete")){
                            completes.incrementAndGet();
                            if(Thread.holdsLock(exchange)||((ServletExchange)exchange).writeLock.isHeldByCurrentThread())
                                lockFailure.compareAndSet(null,new AssertionError("container completion under exchange/IO lock"));
                        }
                    });
                    Runnable hook=onStart.getAndSet(null);if(hook!=null)hook.run();return tracked;
                }
            };
            String path=wrapped.getRequestURI();try{chain.doFilter(wrapped,response);}finally{returned.add(path);}
        });
        container.servlet(new NaruServeServlet(adapter,""),"/*","/",holder->holder.setAsyncSupported(true));
    }
    private void start()throws Exception{start(ex->{});}
    private Future<SharedApp.Reply> get(String path){return threads.submit(()->SharedApp.keepAliveGet(container.port(),path));}
    private HttpExchange next()throws Exception{HttpExchange ex=exchanges.poll(5,TimeUnit.SECONDS);assertNotNull("request not dispatched",ex);return ex;}
    private void returned()throws Exception{assertNotNull("servlet did not return",returned.poll(5,TimeUnit.SECONDS));}
    private Runnable retain(HttpExchange ex){Runnable release=ex.retainRequest();releases.add(release);return release;}
    private static void finish(HttpExchange ex)throws IOException{
        ex.commit(200,Arrays.asList(new AbstractMap.SimpleImmutableEntry<>("Content-Length","3")),3).write("ack".getBytes(StandardCharsets.UTF_8));ex.finish();
    }
    private int inFlight()throws Exception{Field f=ServletAdapter.class.getDeclaredField("inFlight");f.setAccessible(true);return f.getInt(adapter);}
    private void idle()throws Exception{assertTrue("in-flight retained",SharedApp.until(()->{try{return inFlight()==0;}catch(Exception e){throw new AssertionError(e);}},5,TimeUnit.SECONDS));assertEquals(0,adapter.pool().open());}
    private void once(int expected)throws Exception{assertTrue("context did not complete",SharedApp.until(()->completes.get()==expected,5,TimeUnit.SECONDS));assertEquals(expected,completes.get());}
    private static void pending(Future<?> future)throws Exception{try{future.get(100,TimeUnit.MILLISECONDS);fail("operation ignored retained request");}catch(TimeoutException expected){}}
    private static void closed(HttpExchange ex)throws Exception{try{ex.commit(200,Collections.emptyList(),0);fail("ended exchange restarted");}catch(IOException|IllegalStateException expected){}}

    // FR-APP-023 AC-8; FR-SVL-009 AC-2: a response finished in the initial callback can still own request input.
    @Test(timeout=20000) public void initialCompletedWireResponseStartsAsyncWhileRequestIsRetained()throws Exception{
        AtomicReference<Runnable> hold=new AtomicReference<>();start(ex->{hold.set(ex.retainRequest());try{finish(ex);}catch(IOException e){throw new UncheckedIOException(e);}});
        Future<SharedApp.Reply> reply=get("/initial");HttpExchange ex=next();returned();releases.add(hold.get());
        assertEquals("ack",reply.get(5,TimeUnit.SECONDS).text());assertEquals(1,starts.get());assertEquals(0,completes.get());assertEquals(1,inFlight());assertEquals(0,aborts.get());
        hold.get().run();hold.get().run();once(1);idle();assertEquals(0,aborts.get());closed(ex);assertNull(lockFailure.get());
    }
    // FR-APP-023 AC-8: releasing one of two holds (including duplicate release) cannot dispose the context.
    @Test(timeout=20000) public void twoHoldsReleaseOnlyOnLastAndNeverCompleteUnderInternalLocks()throws Exception{
        start();Future<SharedApp.Reply> reply=get("/two");HttpExchange ex=next();returned();Runnable one=retain(ex),two=retain(ex);
        finish(ex);assertEquals("ack",reply.get(5,TimeUnit.SECONDS).text());one.run();one.run();assertEquals(0,completes.get());assertEquals(1,inFlight());
        two.run();two.run();once(1);idle();assertEquals(0,aborts.get());assertNull(lockFailure.get());closed(ex);
    }
    // FR-APP-023 AC-8: pending retained input remains readable after the client observes a full response.
    @Test(timeout=20000) public void directSpiCanFirstReadOriginalInputAfterWireFinish()throws Exception{
        start();String payload="original unread bytes";
        try(Socket socket=new Socket("127.0.0.1",container.port())){
            socket.setSoTimeout(5000);socket.getOutputStream().write(("POST /body HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nContent-Length: "+payload.length()+"\r\n\r\n"+payload).getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            HttpExchange ex=next();returned();Runnable release=retain(ex);finish(ex);
            String ack=ServletRequestRetentionTest.fixedResponse(socket.getInputStream());assertTrue(ack,ack.endsWith("\r\n\r\nack"));assertEquals("adapter eagerly opened unread input",0,bodyOpens.get());
            ByteArrayOutputStream body=new ByteArrayOutputStream();InputStream input=ex.requestBody();for(int left=payload.length();left>0;left--){int b=input.read();assertTrue("input recycled before holder released",b>=0);body.write(b);}
            assertEquals(payload,new String(body.toByteArray(),StandardCharsets.UTF_8));assertEquals(0,completes.get());release.run();once(1);idle();assertEquals(0,aborts.get());
        }
    }
    // FR-SVL-009 AC-1 AC-2: ordinary synchronous completions still avoid AsyncContext entirely.
    @Test(timeout=20000) public void synchronousUnretainedCompletionStaysSynchronous()throws Exception{
        start(ex->{try{finish(ex);}catch(IOException e){throw new UncheckedIOException(e);}});
        assertEquals("ack",get("/sync").get(5,TimeUnit.SECONDS).text());next();returned();idle();assertEquals(0,starts.get());assertEquals(0,completes.get());assertEquals(0,aborts.get());
    }
    // FR-APP-023 AC-1 AC-8: releasing a hold does not implicitly finish an otherwise pending response.
    @Test(timeout=20000) public void earlyReleaseDoesNotEndOrAbortPendingResponse()throws Exception{
        start();Future<SharedApp.Reply> reply=get("/early-release");HttpExchange ex=next();returned();Runnable release=retain(ex);
        release.run();release.run();assertEquals(0,completes.get());assertEquals(1,inFlight());assertEquals(0,aborts.get());pending(reply);
        finish(ex);assertEquals("ack",reply.get(5,TimeUnit.SECONDS).text());once(1);idle();assertEquals(0,aborts.get());
    }
    // FR-APP-023 AC-8: one request's retained context does not prevent another request completing normally.
    @Test(timeout=20000) public void independentRequestsKeepSeparateCompletionOwnership()throws Exception{
        start();Future<SharedApp.Reply> first=get("/held");HttpExchange held=next();returned();Runnable release=retain(held);
        Future<SharedApp.Reply> other=get("/other");HttpExchange ex=next();returned();finish(ex);assertEquals("ack",other.get(5,TimeUnit.SECONDS).text());once(1);
        assertEquals(1,inFlight());assertEquals(0,aborts.get());pending(first);release.run();assertEquals(1,completes.get());
        finish(held);assertEquals("ack",first.get(5,TimeUnit.SECONDS).text());once(2);idle();assertEquals(0,aborts.get());
    }
    // FR-APP-023 AC-3 AC-8; IR-HTTP-009 AC-1: retained finished response still participates in graceful stop.
    @Test(timeout=20000) public void gracefulStopWaitsUntilLastRetainedRequestRelease()throws Exception{
        start();Future<SharedApp.Reply> reply=get("/stop");HttpExchange ex=next();returned();Runnable release=retain(ex);finish(ex);assertEquals("ack",reply.get(5,TimeUnit.SECONDS).text());
        Future<?> stop=threads.submit(()->adapter.stop(5,TimeUnit.SECONDS));assertTrue(SharedApp.until(adapter::stopping,5,TimeUnit.SECONDS));pending(stop);
        release.run();stop.get(5,TimeUnit.SECONDS);once(1);idle();assertEquals(0,aborts.get());closed(ex);
    }
    // FR-APP-023 AC-5 AC-8; IR-HTTP-009 AC-2: forced cancellation revokes the context even with outstanding holders.
    @Test(timeout=20000) public void forcedStopAfterWireFinishNotifiesOnceAndLateReleaseCannotResurrect()throws Exception{
        start();Future<SharedApp.Reply> reply=get("/force");HttpExchange ex=next();returned();Runnable release=retain(ex);finish(ex);assertEquals("ack",reply.get(5,TimeUnit.SECONDS).text());
        long before=System.nanoTime();adapter.stop(30,TimeUnit.MILLISECONDS);assertTrue("stop unbounded",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<1500);
        assertEquals(1,aborts.get());once(1);idle();release.run();release.run();ex.abort();assertEquals(1,completes.get());assertEquals(1,aborts.get());closed(ex);
    }
    // FR-APP-023 AC-5 AC-8: abort must reject a new body handle before touching a possibly recycled container request.
    @Test(timeout=20000) public void abortedExchangeCannotOpenInputFromRecycledContainerRequest()throws Exception{
        start();Future<SharedApp.Reply> reply=get("/aborted-input");HttpExchange ex=next();returned();Runnable release=retain(ex);
        ex.abort();reply.get(5,TimeUnit.SECONDS);once(1);idle();int before=bodyOpens.get();
        try{ex.requestBody();fail("aborted exchange opened a new body handle");}catch(UncheckedIOException|IllegalStateException expected){}
        assertEquals("aborted body access reached recycled Servlet request",before,bodyOpens.get());release.run();assertEquals(1,completes.get());assertEquals(1,aborts.get());
    }
    // FR-APP-023 AC-8; IR-HTTP-009 AC-2: the real Servlet async timeout cannot be suppressed by finished wire bytes.
    @Test(timeout=20000) public void containerTimeoutAfterWireFinishAbortsRetainedRequest()throws Exception{
        timeout=150;start();Future<SharedApp.Reply> reply=get("/timeout");HttpExchange ex=next();returned();Runnable release=retain(ex);finish(ex);assertEquals("ack",reply.get(5,TimeUnit.SECONDS).text());
        assertTrue("no terminal timeout notification",SharedApp.until(()->aborts.get()==1,5,TimeUnit.SECONDS));once(1);idle();release.run();assertEquals(1,completes.get());closed(ex);
    }
    // FR-APP-023 AC-8; FR-SVL-009 AC-2: a release inside container startAsync must neither leak nor complete twice.
    @Test(timeout=20000) public void lastReleaseDuringAsyncAttachmentCompletesExactlyOnce()throws Exception{
        AtomicReference<Runnable> release=new AtomicReference<>();onStart.set(()->release.get().run());
        start(ex->{release.set(ex.retainRequest());try{finish(ex);}catch(IOException e){throw new UncheckedIOException(e);}});
        Future<SharedApp.Reply> reply=get("/start-race");next();returned();releases.add(release.get());assertEquals("ack",reply.get(5,TimeUnit.SECONDS).text());
        assertEquals(1,starts.get());once(1);idle();release.get().run();assertEquals(1,completes.get());assertEquals(0,aborts.get());assertNull(lockFailure.get());
    }
    // FR-APP-023 AC-8: normal last-release/finish races are not mistaken for abort and retain exact completion ownership.
    @Test(timeout=30000) public void lastReleaseRacingFinishNeverLosesOrDuplicatesCompletion()throws Exception{
        start();for(int i=0;i<15;i++){
            Future<SharedApp.Reply> reply=get("/race");HttpExchange ex=next();returned();Runnable release=retain(ex);CountDownLatch go=new CountDownLatch(1);
            Future<?> finished=threads.submit(()->{try{go.await();finish(ex);}catch(Exception e){throw new RuntimeException(e);}});
            Future<?> released=threads.submit(()->{try{go.await();release.run();release.run();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}});
            go.countDown();finished.get(5,TimeUnit.SECONDS);released.get(5,TimeUnit.SECONDS);assertEquals("ack",reply.get(5,TimeUnit.SECONDS).text());once(i+1);idle();
        }assertEquals(0,aborts.get());assertNull(lockFailure.get());
    }
}
