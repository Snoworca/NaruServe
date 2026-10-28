package consumer;

import com.google.gson.Gson;
import com.snoworca.naruserve.Deferred;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.ServerHandle;
import com.snoworca.naruserve.spi.AppFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Private artifact consumer: no source project, composite build or local-Maven dependency. */
public final class Main {
    public static final class Payload { public String message; }
    private static void require(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    private static final class Reply {
        final int status;final String text;
        Reply(int status,String text){this.status=status;this.text=text;}
    }
    private static Socket open(int port,String method,String target,String body,String type)throws Exception {
        Socket socket=new Socket();socket.connect(new InetSocketAddress("127.0.0.1",port),5000);socket.setSoTimeout(5000);
        byte[] bytes=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);
        String head=method+" "+target+" HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n";
        if(body!=null)head+="Content-Type: "+type+"\r\nContent-Length: "+bytes.length+"\r\n";
        OutputStream out=socket.getOutputStream();out.write((head+"\r\n").getBytes(StandardCharsets.ISO_8859_1));out.write(bytes);out.flush();return socket;
    }
    private static Reply read(Socket socket)throws Exception {
        InputStream input=socket.getInputStream();ByteArrayOutputStream headBytes=new ByteArrayOutputStream();int tail=0;
        while(tail!=0x0d0a0d0a){int b=input.read();if(b<0)throw new AssertionError("truncated response head");headBytes.write(b);tail=(tail<<8)|b;}
        String[] lines=new String(headBytes.toByteArray(),StandardCharsets.ISO_8859_1).split("\r\n");int status=Integer.parseInt(lines[0].split(" ")[1]);int length=-1;
        for(String line:lines){int c=line.indexOf(':');if(c>0&&line.substring(0,c).toLowerCase(Locale.ROOT).equals("content-length"))length=Integer.parseInt(line.substring(c+1).trim());}
        require(length>=0,"consumer endpoint omitted fixed Content-Length");ByteArrayOutputStream body=new ByteArrayOutputStream();
        for(int i=0;i<length;i++){int b=input.read();if(b<0)throw new AssertionError("truncated fixed response body");body.write(b);}
        return new Reply(status,new String(body.toByteArray(),StandardCharsets.UTF_8));
    }
    private static Reply call(int port,String method,String path)throws Exception {
        try(Socket socket=open(port,method,path,null,null)){return read(socket);}
    }
    public static void main(String[] args)throws Exception {
        require(System.getProperty("java.specification.version").equals("1.8"),"actual Java8 runtime required");
        List<String> factories=new ArrayList<>();for(AppFactory factory:ServiceLoader.load(AppFactory.class))factories.add(factory.getClass().getName());
        require(factories.equals(Collections.singletonList("com.snoworca.naruserve.bootstrap.DefaultAppFactory")),"unexpected service factories "+factories);
        RestApp app=RestApp.create();ExecutorService producer=Executors.newSingleThreadExecutor();ServerHandle handle=null;
        Deferred bodyWork=Deferred.pending(),laterWork=Deferred.pending();AtomicReference<Request> retained=new AtomicReference<>();AtomicReference<Response> laterResponse=new AtomicReference<>();CountDownLatch laterReady=new CountDownLatch(1);
        Map<String,Object> result=new LinkedHashMap<>();
        try {
            Router router=Router.create();router.get("/files/*parts",(request,response)->response.json(Collections.singletonMap("parts",request.paramValues("parts"))));app.use("/api",router);
            app.get("/hello",(request,response)->response.send("hello"));
            app.getAsync("/later",(request,response,next)->{laterResponse.set(response);laterReady.countDown();return laterWork;});
            app.methodAsync("POST","/retained",(request,response,next)->{retained.set(request);response.send("ack");return bodyWork;});
            handle=app.listen(0);int port=handle.port();require(port>0,"no actual bound port");
            Reply hello=call(port,"GET","/hello");require(hello.status==200&&hello.text.equals("hello"),"default bootstrap HTTP failed");
            Reply wildcard=call(port,"GET","/API/files/a%2Fb/c?x=1");require(wildcard.status==200&&wildcard.text.equals("{\"parts\":[\"a/b\",\"c\"]}"),"mounted named wildcard/strict capture decode failed: "+wildcard.text);
            try(Socket socket=open(port,"GET","/later",null,null)) {
                require(laterReady.await(5,TimeUnit.SECONDS),"getAsync handler not entered");
                Future<?> late=producer.submit(()->{laterResponse.get().send("later");laterWork.resolve();});
                Reply reply=read(socket);late.get(5,TimeUnit.SECONDS);require(reply.status==200&&reply.text.equals("later"),"getAsync Deferred response failed");
            }
            try(Socket socket=open(port,"POST","/retained","{\"message\":\"kept after wire ack\"}","application/json")) {
                Reply ack=read(socket);require(ack.status==200&&ack.text.equals("ack"),"wire ack failed");require(!bodyWork.isDone(),"producer settled before first body read");
                Payload payload=retained.get().body(Payload.class);require(payload.message.equals("kept after wire ack"),"original body was lost after ack");require(payload==retained.get().body(Payload.class),"same-Type JSON cache changed");require(bodyWork.resolve(),"body producer did not settle");
            }
            Reply miss=call(port,"DELETE","/hello");require(miss.status==404&&miss.text.contains("Cannot DELETE /hello"),"common method fallthrough did not use404");
            result.put("javaVersion",System.getProperty("java.version"));result.put("javaHome",System.getProperty("java.home"));result.put("serviceFactories",factories);result.put("logger",app.logger().getClass().getName());
            result.put("checks",java.util.Arrays.asList("RestApp.create actual HTTP","mounted Router named wildcard and encoded slash","getAsync returned Deferred and later response","first JSON body read after completed wire ack with pending Deferred","same-Type JSON cache","common wrong-method HTML404"));result.put("passed",true);
        } finally {
            bodyWork.resolve();laterWork.resolve();if(handle!=null)handle.shutdown(2,TimeUnit.SECONDS);else app.shutdown(0,TimeUnit.MILLISECONDS);
            producer.shutdownNow();require(producer.awaitTermination(5,TimeUnit.SECONDS),"consumer producer thread leaked");
        }
        String json=new Gson().toJson(result);Path report=Paths.get(System.getProperty("consumer.report","build/evidence/runtime.json"));Files.createDirectories(report.toAbsolutePath().getParent());Files.write(report,json.getBytes(StandardCharsets.UTF_8));System.out.println(json);
    }
}
