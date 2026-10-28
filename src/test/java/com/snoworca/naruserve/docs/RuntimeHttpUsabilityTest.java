package com.snoworca.naruserve.docs;

import static org.junit.Assert.*;
import static com.snoworca.naruserve.common.RealHttpTestSupport.send;
import com.google.gson.JsonParser;
import com.snoworca.naruserve.*;
import com.snoworca.naruserve.common.RealHttpTestSupport.Reply;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.common.RunnableExampleContract;
import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Default bootstrap, real TinyHttpd socket, and default Gson; no transport/codec mocks. */
public class RuntimeHttpUsabilityTest {
    static final class User { String name="Ada"; }
    private static final String ORIGIN="https://app.example";

    // FR-DX-002 AC-1 AC-2 AC-3 AC-4
    @Test public void sendRuntimeTypesHaveCorrectWireBodyContentTypeAndStatus() throws Exception {
        RestApp app=RestApp.create();
        app.get("/string",(req,res)->res.send("hello"));
        app.get("/object-string",(req,res)->{Object value="hello";res.send(value);});
        app.get("/bytes",(req,res)->res.status(202).send(new byte[]{0,1,(byte)255}));
        app.get("/dto",(req,res)->res.status(201).send(new User()));
        app.get("/list",(req,res)->res.send(Arrays.asList(new User(),new User())));
        app.get("/number",(req,res)->res.send(42));
        app.get("/boolean",(req,res)->res.send(true));
        app.get("/null",(req,res)->res.send(null));
        app.get("/json-null",(req,res)->res.json(null));
        int port=app.listen(0).port();
        try {
            for(String path:new String[]{"/string","/object-string"}) {
                Reply r=send(port,"GET",path,null);assertEquals(200,r.status);assertEquals("hello",r.text());assertTrue(r.header("Content-Type").startsWith("text/plain"));assertEquals("5",r.header("Content-Length"));
            }
            Reply bytes=send(port,"GET","/bytes",null);assertEquals(202,bytes.status);assertArrayEquals(new byte[]{0,1,(byte)255},bytes.bytes);assertEquals("application/octet-stream",bytes.header("Content-Type"));assertEquals("3",bytes.header("Content-Length"));
            Reply dto=send(port,"GET","/dto",null);assertEquals(201,dto.status);assertTrue(dto.header("Content-Type").startsWith("application/json"));assertEquals("Ada",JsonParser.parseString(dto.text()).getAsJsonObject().get("name").getAsString());
            Reply list=send(port,"GET","/list",null);assertEquals(200,list.status);assertEquals(2,JsonParser.parseString(list.text()).getAsJsonArray().size());
            for(String path:new String[]{"/number","/boolean"}) {Reply r=send(port,"GET",path,null);assertEquals(200,r.status);assertTrue(r.header("Content-Type").startsWith("application/json"));assertEquals(path.equals("/number")?"42":"true",r.text());}
            Reply empty=send(port,"GET","/null",null);assertEquals(200,empty.status);assertEquals(0,empty.bytes.length);
            Reply nil=send(port,"GET","/json-null",null);assertEquals("null",nil.text());assertTrue(nil.header("Content-Type").startsWith("application/json"));
        } finally {app.shutdown(0,TimeUnit.SECONDS);}
    }

    // FR-DX-002 AC-5; CON-APP-010 AC-4 AC-5; FR-APP-024 AC-3
    @Test public void jsonEncodingFailureCannotLeakPartialBodyOrSuccessStatus() throws Exception {
        RestApp app=RestApp.create();
        app.jsonCodec(new JsonCodec(){
            public <T>T decode(Reader reader,Type type){throw new UnsupportedOperationException();}
            public void encode(Object value,Writer writer)throws IOException{writer.write("{\"partial-secret\":\"<secret>&\"}");throw new IOException("encoding failed");}
        });
        app.get("/fail",(req,res)->res.status(201).send(new User()));
        int port=app.listen(0).port();
        try {Reply r=send(port,"GET","/fail",null);assertEquals(500,r.status);assertFalse(r.text().contains("partial-secret"));assertFalse(r.text().contains("{\"partial-secret\":\"<secret>&\"}"));assertFalse(r.text().contains("&quot;partial-secret&quot;"));htmlError(r,"java.io.IOException: encoding failed");}
        finally {app.shutdown(0,TimeUnit.SECONDS);}
    }

    // FR-DX-010 AC-1 AC-2 AC-3 AC-4 AC-5 AC-6; CON-APP-010 AC-4 AC-5 AC-7; FR-APP-024 AC-3
    @Test public void corsActualAndPreflightAndErrorsPreserveExpectedWireHeaders() throws Exception {
        RestApp app=RestApp.create();AtomicInteger calls=new AtomicInteger();
        app.use((req,res,next)->{res.header("Vary","Accept-Encoding");res.header("X-Leak","private");next.run();});
        app.cors(o->o.allowedOrigins(ORIGIN).allowedMethods(Method.GET,Method.POST).allowedHeaders("X-Token").credentials(true));
        app.get("/ok",(req,res)->{calls.incrementAndGet();res.send("ok");});
        app.get("/bad",(req,res)->{throw new HttpException(400,"bad");});
        app.get("/auth",(req,res)->{throw new HttpException(401,"auth");});
        app.get("/error",(req,res)->{throw new IllegalStateException("failure");});
        int port=app.listen(0).port();
        try {
            Reply allowed=send(port,"GET","/ok",null,"Origin",ORIGIN);assertEquals(200,allowed.status);grant(allowed);assertTrue(allowed.header("Vary").contains("Accept-Encoding"));
            Reply denied=send(port,"GET","/ok",null,"Origin","https://evil.example");assertEquals(200,denied.status);assertNull(denied.header("Access-Control-Allow-Origin"));assertNull(denied.header("Access-Control-Allow-Credentials"));assertTrue(denied.header("Vary").contains("Origin"));
            Reply absent=send(port,"GET","/ok",null);assertEquals(200,absent.status);assertNull(absent.header("Access-Control-Allow-Origin"));assertTrue(absent.header("Vary").contains("Origin"));
            int before=calls.get();Reply preflight=send(port,"OPTIONS","/ok",null,"Origin",ORIGIN,"Access-Control-Request-Method","POST","Access-Control-Request-Headers","X-Token");assertEquals(204,preflight.status);assertEquals(0,preflight.bytes.length);grant(preflight);assertEquals(before,calls.get());assertTrue(preflight.header("Access-Control-Allow-Methods").contains("POST"));assertTrue(preflight.header("Access-Control-Allow-Headers").equalsIgnoreCase("X-Token"));assertTrue(preflight.header("Vary").contains("Access-Control-Request-Headers"));
            Reply rejected=send(port,"OPTIONS","/ok",null,"Origin","https://evil.example","Access-Control-Request-Method","POST");assertEquals(403,rejected.status);assertNull(rejected.header("Access-Control-Allow-Origin"));assertEquals(before,calls.get());
            String[] paths={"/bad","/auth","/error"};int[] statuses={400,401,500};
            for(int i=0;i<paths.length;i++){Reply error=send(port,"GET",paths[i],null,"Origin",ORIGIN);assertEquals(statuses[i],error.status);grant(error);htmlError(error,i==2?"java.lang.IllegalStateException: failure":"com.snoworca.naruserve.HttpException: "+(i==0?"bad":"auth"));assertNull(error.header("X-Leak"));assertEquals("Origin",error.header("Vary"));}
        } finally {app.shutdown(0,TimeUnit.SECONDS);}
    }
    private static void htmlError(Reply reply,String expectedInfo){RunnableExampleContract.assertCommonHtmlError(reply.text(),reply.bytes.length,reply::header,expectedInfo);}
    private static void grant(Reply reply){assertEquals(ORIGIN,reply.header("Access-Control-Allow-Origin"));assertEquals("true",reply.header("Access-Control-Allow-Credentials"));assertTrue(reply.header("Vary").toLowerCase(Locale.ROOT).contains("origin"));}
}
