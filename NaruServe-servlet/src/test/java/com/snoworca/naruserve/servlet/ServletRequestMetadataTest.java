package com.snoworca.naruserve.servlet;

import static org.junit.Assert.*;
import com.snoworca.naruserve.*;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.servlet.http.HttpServletRequest;
import org.junit.*;

/** Post-response Next may run while the app is live; it must never observe a different container request. */
public class ServletRequestMetadataTest {
    @Rule public final ContainerRule container=new ContainerRule();
    private final ServletAdapter adapter=new ServletAdapter();
    private final BlockingQueue<String> returned=new LinkedBlockingQueue<>();
    private final AtomicReference<HttpServletRequest> raw=new AtomicReference<>();
    private RestApp app;

    @After public void cleanup(){if(app!=null)app.shutdown(0,TimeUnit.MILLISECONDS);}
    private void start()throws Exception{
        app=RestApp.builder().httpAdapter(adapter).jsonCodec(SharedApp.CODEC).logger(new RecordingLogger()).build();
    }
    private void listen()throws Exception{
        app.listen(0);container.filter((req,res,chain)->{
            HttpServletRequest request=(HttpServletRequest)req;raw.set(request);String uri=request.getRequestURI();
            try{chain.doFilter(req,res);}finally{returned.add(uri);}
        });
        container.servlet(new NaruServeServlet(adapter,""),"/*","/",holder->holder.setAsyncSupported(true));
    }
    private static List<Object> metadata(Request request){return Arrays.asList(request.methodToken(),request.rawPath(),request.url(),request.originalUrl(),request.header("X-Marker"),request.headers("X-Tag"),request.query("tag"),request.remoteAddress(),request.isSecure(),request.lastEventId());}
    private static void write(Socket socket,String request)throws IOException{socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));socket.getOutputStream().flush();}
    private static String first(){return "GET /first?tag=%2F HTTP/1.1\r\nHost: localhost\r\nX-Marker: FIRST\r\nX-Tag: one\r\nx-tag: two\r\nLast-Event-ID: original\r\n\r\n";}

    // FR-HTTP-011 AC-1; FR-SVL-002: names differing only by case must not multiply the same value enumeration.
    @Test(timeout=20000) public void mixedCaseDuplicateHeadersReturnEachReceivedValueOnce()throws Exception{
        start();AtomicReference<String> firstValue=new AtomicReference<>();AtomicReference<List<String>> all=new AtomicReference<>();
        app.get("/first",(q,s)->{firstValue.set(q.header("X-TAG"));all.set(q.headers("x-tag"));s.send("ack");});listen();
        try(Socket socket=new Socket("127.0.0.1",container.port())){
            socket.setSoTimeout(5000);write(socket,first());assertTrue(ServletRequestRetentionTest.fixedResponse(socket.getInputStream()).endsWith("\r\n\r\nack"));
            assertEquals("one",firstValue.get());assertEquals(Arrays.asList("one","two"),all.get());
        }
    }

    // FR-HTTP-027 AC-3 AC-5; FR-HTTP-011 AC-1 AC-2 AC-3; FR-APP-023 AC-8.
    @Test(timeout=20000) public void savedNextRetainsOriginalMetadataAfterContainerRecyclesCompletedRequest()throws Exception{
        start();AtomicReference<Next> next=new AtomicReference<>();AtomicReference<List<Object>> before=new AtomicReference<>(),after=new AtomicReference<>();CountDownLatch observed=new CountDownLatch(1);
        app.get("/first",(q,s,n)->{next.set(n);before.set(metadata(q));s.send("ack");});
        app.use((q,s,n)->{after.set(metadata(q));observed.countDown();});listen();
        try(Socket socket=new Socket("127.0.0.1",container.port())){
            socket.setSoTimeout(5000);write(socket,first());String ack=ServletRequestRetentionTest.fixedResponse(socket.getInputStream());assertTrue(ack,ack.endsWith("\r\n\r\nack"));
            assertEquals("/first",returned.poll(5,TimeUnit.SECONDS));HttpServletRequest containerRequest=raw.get();
            assertTrue("container never recycled completed request",SharedApp.until(()->containerRequest.getHeader("X-Marker")==null,5,TimeUnit.SECONDS));
            next.get().run();assertTrue("post-response callback did not run",observed.await(5,TimeUnit.SECONDS));
            assertEquals("same request metadata changed after container completion",before.get(),after.get());
        }
    }
    // FR-HTTP-027 AC-3 AC-5; FR-HTTP-011 AC-1 AC-2 AC-3: container reuse must not expose the next request's head.
    @Test(timeout=20000) public void savedNextCannotReadMetadataFromFollowingPendingRequestOnSameConnection()throws Exception{
        start();AtomicReference<Next> next=new AtomicReference<>();AtomicReference<List<Object>> before=new AtomicReference<>(),after=new AtomicReference<>();
        AtomicReference<Response> secondResponse=new AtomicReference<>();AtomicReference<String> secondMarker=new AtomicReference<>();CountDownLatch secondEntered=new CountDownLatch(1),observed=new CountDownLatch(1);
        app.get("/first",(q,s,n)->{next.set(n);before.set(metadata(q));s.send("ack");});
        app.post("/second",(q,s)->{secondMarker.set(q.header("X-Marker"));secondResponse.set(s);secondEntered.countDown();});
        app.use((q,s,n)->{after.set(metadata(q));observed.countDown();});listen();
        try(Socket socket=new Socket("127.0.0.1",container.port())){
            socket.setSoTimeout(5000);write(socket,first());assertTrue(ServletRequestRetentionTest.fixedResponse(socket.getInputStream()).endsWith("\r\n\r\nack"));assertEquals("/first",returned.poll(5,TimeUnit.SECONDS));
            write(socket,"POST /second?tag=second HTTP/1.1\r\nHost: localhost\r\nX-Marker: SECOND\r\nX-Tag: other\r\nLast-Event-ID: replaced\r\nContent-Length: 0\r\n\r\n");
            assertTrue("following request not dispatched",secondEntered.await(5,TimeUnit.SECONDS));assertEquals("SECOND",secondMarker.get());assertEquals("/second",returned.poll(5,TimeUnit.SECONDS));
            try{
                next.get().run();assertTrue("original callback did not run",observed.await(5,TimeUnit.SECONDS));
                assertEquals("original callback observed recycled request metadata",before.get(),after.get());
            }finally{secondResponse.get().send("second");}
            assertTrue(ServletRequestRetentionTest.fixedResponse(socket.getInputStream()).endsWith("\r\n\r\nsecond"));
        }
    }
}
