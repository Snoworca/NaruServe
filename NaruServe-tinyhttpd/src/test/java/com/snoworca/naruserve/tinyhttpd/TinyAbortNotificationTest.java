package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.*;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.spi.*;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.ResponseWrapper;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;

/** Real engine sockets plus controlled public ResponseWrapper faults at the adapter's transport seam. */
public class TinyAbortNotificationTest {
    private TinyHttpd server;
    private final TinyHttpdEventStream.Slots slots=new TinyHttpdEventStream.Slots(1);
    private final BlockingQueue<HttpExchange> exchanges=new LinkedBlockingQueue<>();
    private final List<HttpExchange> all=new CopyOnWriteArrayList<>();
    private final ExecutorService clients=Executors.newFixedThreadPool(4),races=Executors.newFixedThreadPool(4);
    private final AtomicInteger transportAborts=new AtomicInteger(),exited=new AtomicInteger();
    private final AtomicReference<String> fault=new AtomicReference<>();
    private final AtomicReference<IOException> injected=new AtomicReference<>();
    private IOException injected(String point){IOException failure=new IOException("injected "+point+" IO");injected.set(failure);return failure;}
    @After public void cleanup()throws Exception{for(HttpExchange ex:all)ex.abort();if(server!=null)server.stop(0);clients.shutdownNow();races.shutdownNow();assertTrue(clients.awaitTermination(12,TimeUnit.SECONDS));assertTrue(races.awaitTermination(5,TimeUnit.SECONDS));}
    private void start(ExchangeHandler callback)throws Exception{
        server=TinyHttpdAdapter.configure(Http.settings().bodyReadTimeoutMillis(200).build(),(request,response)->{
            ResponseWrapper controlled=new ResponseWrapper(response){
                @Override public OutputStream getOutputStream()throws IOException{
                    if(fault.compareAndSet("commit",null))throw new IOException("injected commit IO");
                    return new FilterOutputStream(super.getOutputStream()){
                        @Override public void write(int b)throws IOException{if(fault.compareAndSet("write",null))throw injected("write");out.write(b);}
                        @Override public void write(byte[] b,int off,int len)throws IOException{if(fault.compareAndSet("write",null))throw injected("write");out.write(b,off,len);}
                        @Override public void flush()throws IOException{if(fault.compareAndSet("flush",null))throw new IOException("injected flush IO");out.flush();}
                    };
                }
                @Override public void flushBuffer()throws IOException{if(fault.compareAndSet("finish",null))throw new IOException("injected finish IO");super.flushBuffer();}
                @Override public void abort(){transportAborts.incrementAndGet();super.abort();}
            };
            try{TinyHttpdExchange.run(request,controlled,ex->{all.add(ex);exchanges.add(ex);callback.handle(ex);},slots);}finally{exited.incrementAndGet();}
        }).build();server.start();
    }
    private Future<String> get(){return clients.submit(()->Http.send(server.getPort(),Http.get("/pending")));}
    private HttpExchange next()throws Exception{HttpExchange ex=exchanges.poll(5,TimeUnit.SECONDS);assertNotNull(ex);return ex;}
    private static void until(Callable<Boolean> condition,String message)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.call()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(message,condition.call());}
    private static void unlocked(HttpExchange ex,AtomicReference<Throwable> failure){try{
        assertFalse("abort listener ran under response IO monitor",Thread.holdsLock(ex));
        java.lang.reflect.Field state=TinyHttpdExchange.class.getDeclaredField("state");state.setAccessible(true);
        assertFalse("abort listener ran under lifecycle state monitor",Thread.holdsLock(state.get(ex)));
    }catch(Throwable e){failure.compareAndSet(null,e);}}
    private void terminal(Future<String> reply)throws Exception{try{reply.get(5,TimeUnit.SECONDS);}catch(ExecutionException e){assertTrue(e.getCause() instanceof IOException);}until(()->exited.get()==1,"Tiny run still owns worker after terminal event");assertEquals(0,slots.open());}
    private static void failsIo(Io action)throws Exception{try{action.run();fail("transport operation did not fail");}catch(IOException expected){}}
    interface Io{void run()throws IOException;}

    // IR-HTTP-009 AC-2 AC-3 AC-6: explicit repeated/concurrent aborts notify early and late registrations once, fail-soft and unlocked.
    @Test(timeout=20000) public void earlyLateAndConcurrentAbortListenersAreOnceUnlockedAndFailSoft()throws Exception{
        start(ex->{});Future<String> reply=get();HttpExchange ex=next();AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();
        ex.onAbort(()->{throw new AssertionError("broken listener");});ex.onAbort(()->{unlocked(ex,failure);calls.incrementAndGet();});
        CountDownLatch go=new CountDownLatch(1);List<Future<?>> tasks=new ArrayList<>();
        tasks.add(races.submit(()->{try{go.await();ex.abort();ex.abort();}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
        for(int i=0;i<20;i++)tasks.add(races.submit(()->{try{go.await();ex.onAbort(()->{unlocked(ex,failure);calls.incrementAndGet();});}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
        go.countDown();for(Future<?> task:tasks)task.get(5,TimeUnit.SECONDS);
        ex.onAbort(()->{unlocked(ex,failure);calls.incrementAndGet();});assertNull(failure.get());assertEquals(22,calls.get());terminal(reply);assertEquals(1,transportAborts.get());failsIo(ex::finish);assertEquals(22,calls.get());
    }
    // IR-HTTP-009 AC-1 AC-5: synchronous and later normal finish must not turn into abort notification.
    @Test(timeout=20000) public void normalFinishNeverNotifiesAbortIncludingLateRegistration()throws Exception{
        AtomicInteger calls=new AtomicInteger();start(ex->{});Future<String> reply=get();HttpExchange ex=next();ex.onAbort(calls::incrementAndGet);
        byte[] body="ok".getBytes(StandardCharsets.UTF_8);ex.commit(200,Collections.singletonList(new AbstractMap.SimpleImmutableEntry<>("Content-Length","2")),2).write(body);ex.finish();
        assertEquals("ok",Http.body(reply.get(5,TimeUnit.SECONDS)));ex.onAbort(calls::incrementAndGet);assertEquals(0,calls.get());assertEquals(0,transportAborts.get());
    }
    private void injectedFailure(String point)throws Exception{
        start(ex->{});Future<String> reply=get();HttpExchange ex=next();AtomicInteger calls=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
        ex.onAbort(()->{throw new IllegalStateException("broken observer");});ex.onAbort(()->{unlocked(ex,failure);calls.incrementAndGet();});
        if(point.equals("commit")){fault.set(point);failsIo(()->ex.commit(200,Collections.emptyList(),-1));}
        else{OutputStream out=ex.commit(200,Collections.emptyList(),-1);fault.set(point);if(point.equals("write"))failsIo(()->out.write('x'));else if(point.equals("flush"))failsIo(out::flush);else failsIo(ex::finish);}
        assertEquals("detected "+point+" failure did not notify",1,calls.get());assertNull(failure.get());ex.onAbort(()->{unlocked(ex,failure);calls.incrementAndGet();});assertNull(failure.get());assertEquals(2,calls.get());
        terminal(reply);ex.abort();assertEquals(2,calls.get());assertEquals(1,transportAborts.get());
    }
    // IR-HTTP-009 AC-2 AC-3 AC-6: failing output acquisition cannot retain a pending request/worker.
    @Test(timeout=20000) public void commitIoFailureNotifiesAndReleasesOnce()throws Exception{injectedFailure("commit");}
    // IR-HTTP-009 AC-2 AC-3 AC-6: detecting body write IO notifies after leaving the exchange monitor.
    @Test(timeout=20000) public void writeIoFailureNotifiesAndReleasesOnce()throws Exception{injectedFailure("write");}
    // IR-HTTP-009 AC-2 AC-3 AC-6: detecting ordinary output flush IO notifies outside the write monitor.
    @Test(timeout=20000) public void flushIoFailureNotifiesAndReleasesOnce()throws Exception{injectedFailure("flush");}
    // IR-HTTP-009 AC-2 AC-3 AC-6: normal finish must observe final flush failure rather than report successful completion.
    @Test(timeout=20000) public void finishFlushIoFailureNotifiesAndReleasesOnce()throws Exception{injectedFailure("finish");}
    // IR-HTTP-009 AC-2 AC-3 AC-6: actual reset while reading is terminal, unlike a renderable protocol400.
    @Test(timeout=20000) public void resetDuringRequestReadNotifiesDetectedTerminalIo()throws Exception{
        AtomicInteger calls=new AtomicInteger();AtomicReference<Throwable> lockFailure=new AtomicReference<>();CountDownLatch reading=new CountDownLatch(1),failed=new CountDownLatch(1);AtomicReference<Throwable> readFailure=new AtomicReference<>();
        start(ex->{ex.onAbort(()->{unlocked(ex,lockFailure);calls.incrementAndGet();});try{InputStream in=ex.requestBody();reading.countDown();while(in.read()!=-1){}}catch(Throwable e){readFailure.set(e);failed.countDown();}});
        try(Socket socket=new Socket("127.0.0.1",server.getPort())){socket.setSoLinger(true,0);socket.getOutputStream().write("POST /reset HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\nx".getBytes(StandardCharsets.ISO_8859_1));socket.getOutputStream().flush();assertTrue(reading.await(5,TimeUnit.SECONDS));}
        assertTrue(failed.await(5,TimeUnit.SECONDS));assertTrue(String.valueOf(readFailure.get()),readFailure.get() instanceof IOException);assertEquals(1,calls.get());assertNull(lockFailure.get());until(()->exited.get()==1,"reset read retained worker");assertEquals(1,transportAborts.get());
    }
    // IR-HTTP-009 AC-7; FR-ENGINE-005: writable truncated input and read timeout retain their framework400/408 response.
    @Test(timeout=20000) public void recoverableBodyProtocolAndReadTimeoutDoNotEagerlyAbortWritableResponse()throws Exception{
        AtomicInteger calls=new AtomicInteger();start(ex->{ex.onAbort(calls::incrementAndGet);try{InputStream in=ex.requestBody();while(in.read()!=-1){}throw new AssertionError("body unexpectedly complete");}
            catch(HttpException failure){try{byte[] bytes="recoverable".getBytes(StandardCharsets.UTF_8);ex.commit(failure.status(),Collections.singletonList(new AbstractMap.SimpleImmutableEntry<>("Content-Length",Integer.toString(bytes.length))),bytes.length).write(bytes);ex.finish();}catch(IOException e){throw new UncheckedIOException(e);}}
            catch(IOException failure){throw new UncheckedIOException(failure);}});
        for(boolean eof:new boolean[]{true,false})try(Socket socket=new Socket("127.0.0.1",server.getPort())){
            socket.setSoTimeout(5000);socket.getOutputStream().write("POST /read HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 100\r\n\r\nx".getBytes(StandardCharsets.ISO_8859_1));socket.getOutputStream().flush();if(eof)socket.shutdownOutput();
            String result=Http.readAll(socket.getInputStream());assertEquals(result,eof?400:408,Http.status(result));assertEquals("recoverable",Http.body(result));assertEquals(0,calls.get());
        }
        until(()->exited.get()==2,"recoverable response retained worker");assertEquals(0,transportAborts.get());
    }
    // IR-HTTP-009 AC-2 AC-3 AC-6; FR-APP-023 AC-3: admitted SSE setup IO cancels its not-yet-attached drain once.
    @Test(timeout=20000) public void acceptedSseSetupIoAbortsAndCancelsBeforeAttachWithoutSlotLeak()throws Exception{
        AtomicInteger notices=new AtomicInteger(),attached=new AtomicInteger(),drained=new AtomicInteger(),cancelled=new AtomicInteger();CountDownLatch setupFailed=new CountDownLatch(1);AtomicReference<Throwable> lockFailure=new AtomicReference<>();
        start(ex->{ex.onAbort(()->{throw new AssertionError("observer failure");});ex.onAbort(()->{unlocked(ex,lockFailure);notices.incrementAndGet();});fault.set("finish");
            try{ex.eventStream(200,Collections.emptyList(),new StreamDrain(){public void attach(OutputStream out){attached.incrementAndGet();}public void drain(OutputStream out){drained.incrementAndGet();}public void abort(){cancelled.incrementAndGet();}});throw new AssertionError("SSE setup should fail");}
            catch(IOException expected){setupFailed.countDown();}});
        Future<String> reply=get();HttpExchange ex=next();assertTrue(setupFailed.await(5,TimeUnit.SECONDS));
        assertEquals(0,attached.get());assertEquals(0,drained.get());assertEquals(1,cancelled.get());assertEquals(1,notices.get());assertNull(lockFailure.get());assertEquals(0,slots.open());terminal(reply);ex.abort();assertEquals(1,cancelled.get());assertEquals(1,notices.get());assertEquals(1,transportAborts.get());
    }

    // IR-HTTP-009 AC-2 AC-3; FR-APP-023 AC-3: swallowing a body IO must not convert known failure to normal completion.
    @Test(timeout=20000) public void customDrainCatchingIoStillNotifiesAtOwnershipUnwind()throws Exception{
        AtomicInteger notices=new AtomicInteger();AtomicReference<IOException> caught=new AtomicReference<>();AtomicReference<Throwable> lockFailure=new AtomicReference<>();
        start(ex->{ex.onAbort(()->{unlocked(ex,lockFailure);notices.incrementAndGet();});try{ex.eventStream(200,Collections.emptyList(),out->{fault.set("write");try{out.write('x');}catch(IOException original){caught.set(original);}});}catch(IOException failure){throw new UncheckedIOException(failure);}});
        Future<String> reply=get();next();terminal(reply);assertNotNull(caught.get());assertSame(injected.get(),caught.get());assertEquals(1,notices.get());assertNull(lockFailure.get());assertEquals(1,transportAborts.get());
    }

}
