package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.*;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class RawPolicyGuardTest {
    @Rule public final TemporaryFolder tmp=new TemporaryFolder();
    private final TinyHttpdAdapter adapter=new TinyHttpdAdapter();
    @After public void stop(){Http.stopQuietly(adapter);}
    // FR-ENGINE-012 AC-3: RAW admission never disables request syntax, Host, framing or pipeline isolation.
    @Test(timeout=25000) public void parserHostAndFramingRejectBeforeHandlerAndClosePipeline() throws Exception {
        AtomicInteger calls=new AtomicInteger();adapter.bind(Http.settings().build(),Http.ok(ex->calls.incrementAndGet()));
        String[] bad={
            "GET /x/a b HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
            "GET /x/a#b HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
            "GET /x/a\u0000b HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
            "GET /x/a\tb HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
            "GET /x/%ZZ HTTP/1.1\r\n\r\n",
            "GET /x/%ZZ HTTP/1.1\r\nHost: 127.0.0.1\r\nHost: evil.example\r\n\r\n",
            "GET /x/%ZZ HTTP/1.1\r\nHost: bad host\r\n\r\n",
            "POST /x/ok HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nx",
            "POST /x/ok HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n0\r\n\r\n"};
        for(String request:bad){String response=Http.send(adapter.boundPort(),request+Http.get("/healthy"));
            assertEquals(request+response,400,Http.status(response));assertEquals("close",Http.header(response,"Connection"));
            assertEquals("pipeline must not dispatch",0,calls.get());assertEquals(1,response.split("HTTP/1.1 ",-1).length-1);}
        assertEquals(200,Http.status(Http.send(adapter.boundPort(),Http.get("/healthy"))));assertEquals(1,calls.get());
    }
    // FR-ENGINE-012 AC-3: decoding ownership changes must not disable the configured Host allowlist.
    @Test(timeout=15000) public void disallowedHostStillRejectsRawMalformedPathBeforeSpi() throws Exception {
        AtomicInteger calls=new AtomicInteger();adapter.bind(Http.settings().allowedHosts("good.example").build(),Http.ok(ex->calls.incrementAndGet()));
        String response=Http.send(adapter.boundPort(),"GET /x/%ZZ HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n");
        assertEquals(response,421,Http.status(response));assertEquals(0,calls.get());
    }
    // FR-ENGINE-012 AC-3: canonical containment remains a framework security invariant after RAW admission.
    @Test(timeout=20000) public void staticTraversalNeverExposesOutsideSentinel() throws Exception {
        File root=tmp.newFolder("public"),secret=tmp.newFile("private.txt");
        Files.write(secret.toPath(),"outside-secret".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root,"ok.txt").toPath(),"inside".getBytes(StandardCharsets.UTF_8));
        RestApp app=Http.app(adapter,new RecordingLogger());
        try{app.staticFiles("/files",root);int port=app.listen(0).port();
            assertEquals("inside",Http.body(Http.send(port,Http.get("/files/ok.txt"))));
            for(String target:new String[]{"/files/../private.txt","/files/%2e%2e/private.txt","/files/%2e%2e%2fprivate.txt","/files/%252e%252e/private.txt"}){
                String response=Http.send(port,Http.get(target));assertNotEquals(response,200,Http.status(response));assertFalse(response,Http.body(response).contains("outside-secret"));}
        }finally{app.shutdown(2,TimeUnit.SECONDS);}
    }
    // FR-ENGINE-012 AC-3: RAW HTTP policy does not authorize decoded-path WebSocket upgrade bypasses.
    @Test(timeout=25000) public void invalidDecodedWebSocketTargetsNeverUpgrade() throws Exception {
        RestApp app=Http.app(adapter,new RecordingLogger());AtomicInteger opens=new AtomicInteger();
        try{app.ws("/ws/:id",(request,session)->{opens.incrementAndGet();session.close();});int port=app.listen(0).port();
            for(String target:new String[]{"/ws/a%2Fb","/ws/%00","/ws/%ZZ"})try(RawWebSocketClient client=RawWebSocketClient.open(port,target)){
                assertEquals(client.handshake,400,client.status());assertEquals(0,opens.get());}
            try(RawWebSocketClient client=RawWebSocketClient.open(port,"/ws/ok")){assertEquals(client.handshake,101,client.status());}
        }finally{app.shutdown(2,TimeUnit.SECONDS);}
    }
}
