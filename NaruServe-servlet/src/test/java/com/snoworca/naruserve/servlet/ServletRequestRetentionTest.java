package com.snoworca.naruserve.servlet;

import static org.junit.Assert.*;
import com.snoworca.naruserve.*;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.io.*;
import java.lang.reflect.Type;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.ServletInputStream;
import javax.servlet.ReadListener;
import org.junit.*;

/** Wire response completion must not recycle a request still owned by a pending producer. */
public class ServletRequestRetentionTest {
    @Rule public final ContainerRule container=new ContainerRule();
    private final ServletAdapter adapter=new ServletAdapter();
    private final ExecutorService producer=Executors.newSingleThreadExecutor();
    private RestApp app;
    @After public void cleanup()throws Exception{
        if(app!=null)app.shutdown(0,TimeUnit.MILLISECONDS);producer.shutdownNow();assertTrue("producer leaked",producer.awaitTermination(12,TimeUnit.SECONDS));
    }
    static String fixedResponse(InputStream input)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int tail=0;
        while(tail!=0x0d0a0d0a){int b=input.read();if(b<0)throw new EOFException("response head ended");bytes.write(b);tail=(tail<<8)|b;}
        String head=new String(bytes.toByteArray(),StandardCharsets.ISO_8859_1);String length=null;
        for(String line:head.split("\r\n")){int colon=line.indexOf(':');if(colon>0&&line.substring(0,colon).equalsIgnoreCase("Content-Length"))length=line.substring(colon+1).trim();}
        if(length==null)throw new IOException("fixed response length missing");
        for(int left=Integer.parseInt(length);left>0;left--){int b=input.read();if(b<0)throw new EOFException("response body ended");bytes.write(b);}
        return new String(bytes.toByteArray(),StandardCharsets.ISO_8859_1);
    }
    private int activeCoreHttp()throws Exception{
        Field lifecycle=app.getClass().getDeclaredField("lifecycle");lifecycle.setAccessible(true);Object state=lifecycle.get(app);
        Field executions=state.getClass().getDeclaredField("executions");executions.setAccessible(true);java.util.Set<?> pending=(java.util.Set<?>)executions.get(state);
        synchronized(pending){return pending.size();}
    }
    // FR-SVL-009 AC-1 AC-2 AC-4; FR-APP-023 AC-3 AC-5 AC-7 AC-8; IR-HTTP-009 AC-2.
    @Test(timeout=20000) public void unstartedSavedNextWithoutAsyncSupportCancelsCoreAndCleansInput()throws Exception{
        unstartedWithoutAsyncSupport(false);
    }
    // Same terminal contract when a pending producer lease also survives the initial callback.
    @Test(timeout=20000) public void unstartedDeferredWithoutAsyncSupportCancelsCoreAndCleansInput()throws Exception{
        unstartedWithoutAsyncSupport(true);
    }
    private void unstartedWithoutAsyncSupport(boolean deferred)throws Exception{
        RecordingLogger log=new RecordingLogger();File uploads=container.uploads();Deferred work=Deferred.pending();
        AtomicInteger aborts=new AtomicInteger(),opens=new AtomicInteger(),closes=new AtomicInteger(),spooled=new AtomicInteger(),continued=new AtomicInteger();
        AtomicReference<HttpExchange> exchange=new AtomicReference<>();AtomicReference<Request> request=new AtomicReference<>();
        AtomicReference<Next> next=new AtomicReference<>();AtomicReference<UploadFile> upload=new AtomicReference<>();AtomicReference<InputStream> partInput=new AtomicReference<>();
        BlockingQueue<String> returned=new LinkedBlockingQueue<>();
        app=RestApp.builder().httpAdapter(adapter).logger(log).jsonCodec(SharedApp.CODEC).build();
        app.multipart(o->o.tempDirectory(uploads).memoryThreshold(16));
        Middleware pending=(q,s,n)->{request.set(q);next.set(n);UploadFile file=q.file("f");upload.set(file);partInput.set(file.inputStream());spooled.set(uploads.list().length);};
        if(deferred)app.methodAsync("POST","/pending",(q,s,n)->{pending.handle(q,s,n);return work;});
        else app.post("/pending",pending);
        app.post("/pending",(q,s)->{continued.incrementAndGet();s.send("late-response");});app.listen(0);
        Field handlerField=ServletAdapter.class.getDeclaredField("handler");handlerField.setAccessible(true);ExchangeHandler original=(ExchangeHandler)handlerField.get(adapter);
        handlerField.set(adapter,(ExchangeHandler)ex->{exchange.set(ex);ex.onAbort(aborts::incrementAndGet);original.handle(ex);});
        container.filter((req,res,chain)->{try{chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest)req){
            @Override public ServletInputStream getInputStream()throws IOException{
                opens.incrementAndGet();ServletInputStream input=super.getInputStream();return new ServletInputStream(){
                    @Override public int read()throws IOException{return input.read();}
                    @Override public int read(byte[] b,int off,int len)throws IOException{return input.read(b,off,len);}
                    @Override public boolean isFinished(){return input.isFinished();}
                    @Override public boolean isReady(){return input.isReady();}
                    @Override public void setReadListener(ReadListener listener){input.setReadListener(listener);}
                    @Override public void close()throws IOException{closes.incrementAndGet();input.close();}
                };
            }
        },res);}finally{returned.add("returned");}});
        container.servlet(new NaruServeServlet(adapter,""),"/*","/",holder->holder.setAsyncSupported(false));
        try(Socket socket=new Socket("127.0.0.1",container.port())){
            socket.setSoTimeout(5000);String payload="--b\r\nContent-Disposition: form-data; name=\"f\"; filename=\"a.bin\"\r\n\r\n"+
                    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"+"\r\n--b--\r\n";
            byte[] body=payload.getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("POST /pending HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: multipart/form-data; boundary=b\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));socket.getOutputStream().write(body);socket.getOutputStream().flush();
            String response=fixedResponse(socket.getInputStream());assertTrue(response,response.startsWith("HTTP/1.1 500"));
            String head=response.substring(0,response.indexOf("\r\n\r\n")),json=response.substring(response.indexOf("\r\n\r\n")+4);
            assertTrue(head,head.replace(" ","").toLowerCase(java.util.Locale.ROOT).contains("content-type:application/json;charset=utf-8"));
            assertTrue(json,json.matches("\\{\"error\":\\{\"code\":\"INTERNAL_ERROR\",\"message\":\"[^\"]*\",\"requestId\":\"[0-9a-f]{16}\"}}"));
            assertTrue(head,head.contains("Content-Length: "+json.getBytes(StandardCharsets.UTF_8).length));
            assertEquals("returned",returned.poll(5,TimeUnit.SECONDS));assertEquals("second response after adapter500",-1,socket.getInputStream().read());
            assertEquals("upload positive control did not spool",1,spooled.get());assertEquals(64,upload.get().size());assertEquals(1,opens.get());
            Field inFlight=ServletAdapter.class.getDeclaredField("inFlight");inFlight.setAccessible(true);
            assertEquals("adapter500 failed terminal cancellation: core="+activeCoreHttp()+", adapter="+inFlight.getInt(adapter)+", uploads="+uploads.list().length+", inputCloses="+closes.get(),1,aborts.get());
            assertEquals("core request registration",0,activeCoreHttp());assertEquals("adapter request admission",0,inFlight.getInt(adapter));assertEquals("upload cleanup",0,uploads.list().length);assertEquals("original request input close",1,closes.get());assertEquals("access log once",1,log.info().size());
            // FR-APP-019 AC-3 AC-5; IR-HTTP-009 AC-8: preserve the actual adapter500 and written JSON bytes.
            String access=log.info().get(0);
            assertTrue("wire500 access metrics: "+access,access.matches("POST /pending 500 aborted \\d+ms in="+body.length+" out="+json.getBytes(StandardCharsets.UTF_8).length+" rid=[0-9a-f]{16}"));
            try{partInput.get().read();fail("upload input remained open after cancellation");}catch(IOException expected){}
            try{upload.get().inputStream();fail("upload reopened after cancellation");}catch(IllegalStateException expected){}
            try{request.get().body(String.class);fail("core body reopened after cancellation");}catch(BodyAlreadyConsumedException expected){}
            try{exchange.get().requestBody();fail("transport body reopened after cancellation");}catch(UncheckedIOException|IllegalStateException expected){}
            next.get().run();next.get().error(new IllegalStateException("late continuation"));if(deferred){assertFalse(work.isDone());assertTrue(work.resolve());}
            exchange.get().retainRequest().run();exchange.get().abort();
            assertEquals(0,continued.get());assertEquals(1,aborts.get());assertEquals(0,activeCoreHttp());assertEquals(0,inFlight.getInt(adapter));assertEquals(1,opens.get());assertEquals(1,closes.get());assertEquals(1,log.info().size());
            assertEquals("late actions changed terminal access metrics",access,log.info().get(0));
        }
    }
    // FR-APP-023 AC-3 AC-5 AC-7 AC-8; FR-SVL-009 AC-2: inability to retain after an ack is terminal cancellation.
    @Test(timeout=20000) public void finishedRetainedRequestWithoutAsyncSupportAbortsBeforeContainerRecyclesInput()throws Exception{
        RecordingLogger log=new RecordingLogger();AtomicInteger opens=new AtomicInteger(),aborts=new AtomicInteger();
        AtomicReference<HttpExchange> exchange=new AtomicReference<>();AtomicReference<Request> request=new AtomicReference<>();
        BlockingQueue<String> returned=new LinkedBlockingQueue<>();Deferred work=Deferred.pending();
        app=RestApp.builder().httpAdapter(adapter).logger(log).jsonCodec(SharedApp.CODEC).build();
        app.methodAsync("POST","/no-async",(q,s,n)->{request.set(q);s.send("ack");return work;});app.listen(0);
        Field handlerField=ServletAdapter.class.getDeclaredField("handler");handlerField.setAccessible(true);
        ExchangeHandler original=(ExchangeHandler)handlerField.get(adapter);
        handlerField.set(adapter,(ExchangeHandler)ex->{exchange.set(ex);ex.onAbort(aborts::incrementAndGet);original.handle(ex);});
        container.filter((req,res,chain)->{try{chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest)req){
            @Override public ServletInputStream getInputStream()throws IOException{opens.incrementAndGet();return super.getInputStream();}
        },res);}finally{returned.add("returned");}});
        container.servlet(new NaruServeServlet(adapter,""),"/*","/",holder->holder.setAsyncSupported(false));
        try(Socket socket=new Socket("127.0.0.1",container.port())){
            socket.setSoTimeout(5000);String payload="\"unread\"";
            socket.getOutputStream().write(("POST /no-async HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "+payload.length()+"\r\n\r\n"+payload).getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            String ack=fixedResponse(socket.getInputStream());assertTrue(ack,ack.startsWith("HTTP/1.1 200"));assertTrue(ack,ack.endsWith("\r\n\r\nack"));
            assertEquals("returned",returned.poll(5,TimeUnit.SECONDS));assertEquals("second response after acknowledged body",-1,socket.getInputStream().read());
            assertEquals("unsupported async must cancel retained work before shutdown",1,aborts.get());
            Field inFlight=ServletAdapter.class.getDeclaredField("inFlight");inFlight.setAccessible(true);assertEquals(0,inFlight.getInt(adapter));
            assertEquals("core lease was not revoked and cleaned up",1,log.info().size());assertFalse("transport cancellation must not settle producer",work.isDone());
            assertEquals("unread input was eagerly opened",0,opens.get());
            try{exchange.get().requestBody();fail("cancelled request opened recycled input");}catch(UncheckedIOException|IllegalStateException expected){}
            assertEquals(0,opens.get());assertTrue(work.resolve());exchange.get().retainRequest().run();exchange.get().abort();
            assertEquals(1,aborts.get());assertEquals(0,inFlight.getInt(adapter));assertEquals(1,log.info().size());
            try{request.get().body(String.class);fail("settled producer resurrected request input");}catch(BodyAlreadyConsumedException expected){}
            assertEquals(0,opens.get());
        }
    }
    // FR-APP-023 AC-3 AC-7 AC-8: actual Jetty response bytes finish before the first pending producer body read.
    @Test(timeout=20000) public void pendingDeferredCanFirstReadJsonAfterCompletedResponseAndServletReturn()throws Exception{
        AtomicInteger decodes=new AtomicInteger();BlockingQueue<String> returned=new LinkedBlockingQueue<>();RecordingLogger log=new RecordingLogger();
        app=RestApp.builder().httpAdapter(adapter).logger(log).jsonCodec(new JsonCodec(){
            public <T>T decode(Reader reader,Type type)throws IOException{decodes.incrementAndGet();return SharedApp.CODEC.decode(reader,type);}
            public void encode(Object value,Writer writer)throws IOException{SharedApp.CODEC.encode(value,writer);}
        }).build();
        Deferred work=Deferred.pending();AtomicReference<Request> request=new AtomicReference<>();app.post("/control",(q,s)->s.send(q.body(String.class)));
        app.methodAsync("POST","/unread",(q,s,n)->{request.set(q);s.send("ack");return work;});app.listen(0);
        container.filter((req,res,chain)->{String uri=((HttpServletRequest)req).getRequestURI();try{chain.doFilter(req,res);}finally{returned.add(uri);}});
        container.servlet(new NaruServeServlet(adapter,""),"/*");String payload="\"delayed-payload\"";
        SharedApp.Reply control=SharedApp.http(container.port(),"POST","/control",payload.getBytes(StandardCharsets.UTF_8),"Content-Type","application/json");
        assertEquals(200,control.status);assertEquals(payload,control.text());assertEquals("/control",returned.poll(5,TimeUnit.SECONDS));assertEquals(1,decodes.get());decodes.set(0);
        try(Socket socket=new Socket("127.0.0.1",container.port())){
            socket.setSoTimeout(5000);socket.getOutputStream().write(("POST /unread HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\nContent-Type: application/json\r\nContent-Length: "+payload.length()+"\r\n\r\n"+payload).getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            String observed=fixedResponse(socket.getInputStream());assertTrue(observed,observed.startsWith("HTTP/1.1 200"));assertTrue(observed,observed.endsWith("\r\n\r\nack"));
            assertEquals("/unread",returned.poll(5,TimeUnit.SECONDS));assertFalse(work.isDone());assertEquals(0,decodes.get());
            CountDownLatch responseObserved=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
            Future<?> read=producer.submit(()->{try{responseObserved.await();Thread.sleep(150);value.set(request.get().body(String.class));}catch(Throwable problem){failure.set(problem);}finally{work.resolve();}});
            responseObserved.countDown();read.get(5,TimeUnit.SECONDS);
            assertNull("completed Servlet response lost retained input: "+failure.get()+", status="+(failure.get() instanceof HttpException?((HttpException)failure.get()).status():"none")+", decoderCalls="+decodes.get(),failure.get());
            assertEquals(payload,value.get());assertEquals(1,decodes.get());assertEquals(2,log.info().size());
        }
    }
}
