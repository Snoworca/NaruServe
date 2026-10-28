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
            try{request.get().body(String.class);fail("settled producer resurrected request input");}catch(UncheckedIOException|IllegalStateException|HttpException expected){}
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
