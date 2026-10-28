package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.*;
import com.snoworca.naruserve.*;
import com.snoworca.naruserve.internal.PathDecodeException;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

/** Actual RestApp dispatcher over published Tiny sockets, distinct from the RAW SPI probe's200. */
public class RawFrameworkSocketTest {
    private final TinyHttpdAdapter adapter=new TinyHttpdAdapter();
    private final RestApp app=Http.app(adapter,new RecordingLogger());
    @After public void stop(){app.shutdown(2,TimeUnit.SECONDS);}

    // FR-ENGINE-012 AC-2; FR-HTTP-027 AC-2; FR-APP-024 AC-1: prove typed framework origin, not engine400.
    @Test(timeout=20000) public void malformedCapturesReachTypedFrameworkErrorMiddlewareWithExactRawValue() throws Exception {
        AtomicInteger errors=new AtomicInteger(),routes=new AtomicInteger();
        app.get("/x/:id",(request,response)->{routes.incrementAndGet();response.send("unexpected");});
        app.use((ErrorMiddleware)(failure,request,response,next)->{
            assertTrue(failure.toString(),failure instanceof PathDecodeException);assertEquals(400,((HttpException)failure).status());
            String raw=request.rawPath().substring("/x/".length());
            assertEquals("Failed to decode param '"+raw+"'",failure.getMessage());
            assertEquals(request.rawPath()+"?q=%252F",request.originalUrl());errors.incrementAndGet();
            response.status(400).header("X-Framework-Decode","typed").send("framework:"+failure.getMessage());
        });
        int port=app.listen(0).port();
        for(String raw:new String[]{"%ZZ","%","%0","%C0%AF","%ED%A0%80"}){
            String result=Http.send(port,Http.get("/x/"+raw+"?q=%252F"));
            assertEquals(result,400,Http.status(result));assertEquals("typed",Http.header(result,"X-Framework-Decode"));
            assertEquals("framework:Failed to decode param '"+raw+"'",Http.body(result));
        }
        assertEquals(5,errors.get());assertEquals(0,routes.get());
    }
    // FR-ENGINE-012 AC-1 AC-2; FR-HTTP-027 AC-1 AC-3: split raw first and decode scalar exactly once.
    @Test(timeout=25000) public void encodedScalarsRetainRawViewsAndDecodeOnceAcrossAllQueryStates() throws Exception {
        Map<String,String> decoded=new LinkedHashMap<>();decoded.put("a%2Fb","a/b");decoded.put("%00","\u0000");
        decoded.put("%2e",".");decoded.put("%2e%2e","..");decoded.put("%252F","%2F");decoded.put("%E2%98%83","\u2603");
        AtomicInteger calls=new AtomicInteger();
        app.get("/scalar/:id",(request,response)->{
            String raw=request.rawPath().substring("/scalar/".length());String value=decoded.get(raw);assertNotNull(raw,value);
            assertEquals(value,request.param("id"));assertEquals(Collections.singletonList(value),request.paramValues("id"));
            assertEquals(value,request.pathParams().get("id"));assertTrue(request.pathParams().get("id") instanceof String);
            assertEquals(request.rawPath(),request.path());assertEquals("",request.baseUrl());assertEquals(request.originalUrl(),request.url());
            if(request.originalUrl().contains("?q=")){assertEquals("%2F",request.query("q"));assertEquals("a b",request.query("plus"));}
            calls.incrementAndGet();response.send(request.originalUrl());
        });
        int port=app.listen(0).port();
        for(String raw:decoded.keySet())for(String suffix:new String[]{"","?","?q=%252F&plus=a+b"}){
            String target="/scalar/"+raw+suffix;String result=Http.send(port,Http.get(target));
            assertEquals(result,200,Http.status(result));assertEquals(target,Http.body(result));
        }
        assertEquals(18,calls.get());
    }
    // FR-ENGINE-012 AC-2; FR-HTTP-027 AC-1 AC-3: encoded slash is one wildcard item; literal slash preserves empties.
    @Test(timeout=20000) public void wildcardListsAndRepeatedSlashesStayDistinct() throws Exception {
        app.get("/wild/*rest",(request,response)->{
            assertEquals(Arrays.asList("a/b","","c"),request.paramValues("rest"));
            assertEquals(Arrays.asList("a/b","","c"),request.pathParams().get("rest"));
            assertTrue(request.pathParams().get("rest") instanceof List);assertEquals("a/b",request.param("rest"));
            assertEquals("/wild/a%2Fb//c?",request.originalUrl());assertEquals("/wild/a%2Fb//c",request.path());response.send("list");
        });
        app.get("/double//leaf",(request,response)->{assertEquals("/double//leaf",request.path());response.send("double");});
        int port=app.listen(0).port();assertEquals("list",Http.body(Http.send(port,Http.get("/wild/a%2Fb//c?"))));
        assertEquals("double",Http.body(Http.send(port,Http.get("/double//leaf"))));assertEquals(404,Http.status(Http.send(port,Http.get("/double/leaf"))));
    }
    // FR-ENGINE-012 AC-2; FR-HTTP-027 AC-3; FR-ROUTE-020 AC-2: actual matched mount plus raw query survives transport.
    @Test(timeout=20000) public void mountedRequestViewsUseMatchedRawPrefixAndOriginalQuery() throws Exception {
        Router child=Router.create();child.get("/leaf/:id",(request,response)->{
            assertEquals("a/b",request.param("id"));assertEquals("/leaf/a%2Fb",request.path());
            assertEquals("/api/outer/leaf/a%2Fb",request.rawPath());assertEquals("/api/outer",request.baseUrl());
            assertEquals("/leaf/a%2Fb?q=%252F",request.url());assertEquals("/api/outer/leaf/a%2Fb?q=%252F",request.originalUrl());
            assertEquals("%2F",request.query("q"));response.send("mounted");
        });app.use("/api/:outer",child);int port=app.listen(0).port();
        String result=Http.send(port,Http.get("/api/outer/leaf/a%2Fb?q=%252F"));assertEquals(result,200,Http.status(result));assertEquals("mounted",Http.body(result));
    }
}
