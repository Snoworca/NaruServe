package com.snoworca.naruserve.docs;

import static org.junit.Assert.*;
import static com.snoworca.naruserve.common.RealHttpTestSupport.send;
import com.google.gson.JsonParser;
import com.snoworca.naruserve.*;
import com.snoworca.naruserve.common.RealHttpTestSupport.Reply;
import com.snoworca.naruserve.gson.GsonJsonCodec;
import com.snoworca.naruserve.spi.JsonCodec;
import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Real Gson decoding and actual TinyHttpd sockets; the counting wrapper delegates every decode unchanged. */
public class RealGsonBodyCacheTest {
    public static class User { public String name; public User(){} }
    private static final class CountingGson implements JsonCodec {
        final GsonJsonCodec gson=new GsonJsonCodec(); final AtomicInteger decodes=new AtomicInteger();
        public <T>T decode(Reader reader,Type type)throws IOException{decodes.incrementAndGet();return gson.decode(reader,type);}
        public void encode(Object value,Writer writer)throws IOException{gson.encode(value,writer);}
    }

    // IR-JSON-002 AC-6 current superseding contract: FR-DX-007 AC-1 AC-2; FR-DX-006 AC-1
    @Test public void middlewareAndHandlerReuseActualGsonDtoGenericCollectionsAndNull() throws Exception {
        RestApp app=RestApp.create();CountingGson codec=new CountingGson();app.jsonCodec(codec);
        app.use((req,res,next)->{
            Object first;
            if(req.path().equals("/list"))first=req.body(new TypeRef<List<User>>(){});
            else if(req.path().equals("/nested"))first=req.body(new TypeRef<Map<String,List<User>>>(){});
            else first=req.body(User.class);
            req.attribute("first",first);next.run();
        });
        app.post("/dto",(req,res)->{
            User user=req.body(new TypeRef<User>(){});assertSame(req.attributeAs("first",User.class),user);assertEquals("Ada",user.name);
            assertThrows(BodyAlreadyConsumedException.class,()->req.body(String.class));assertThrows(BodyAlreadyConsumedException.class,()->req.form("name"));res.send(user);
        });
        app.post("/list",(req,res)->{
            List<User> users=req.body(new TypeRef<List<User>>(){});assertSame(req.attributeAs("first",List.class),users);assertEquals(User.class,users.get(0).getClass());
            assertThrows(BodyAlreadyConsumedException.class,()->req.body(new TypeRef<List<String>>(){}));res.send(users);
        });
        app.post("/nested",(req,res)->{
            Map<String,List<User>> groups=req.body(new TypeRef<Map<String,List<User>>>(){});assertSame(req.attributeAs("first",Map.class),groups);assertEquals(User.class,groups.get("team").get(0).getClass());res.send(groups);
        });
        app.post("/null",(req,res)->{assertNull(req.body(User.class));assertNull(req.body(new TypeRef<User>(){}));res.json(null);});
        int port=app.listen(0).port();
        try {
            String[] paths={"/dto","/list","/nested","/null"};String[] bodies={"{\"name\":\"Ada\"}","[{\"name\":\"Ada\"}]","{\"team\":[{\"name\":\"Ada\"}]}","null"};
            for(int i=0;i<paths.length;i++){
                Reply r=send(port,"POST",paths[i],bodies[i],"Content-Type","application/json");assertEquals(r.text(),200,r.status);assertEquals(JsonParser.parseString(bodies[i]),JsonParser.parseString(r.text()));assertEquals(i+1,codec.decodes.get());
            }
        } finally {app.shutdown(0,TimeUnit.SECONDS);}
    }

    // IR-JSON-002 AC-6 current superseding contract: FR-DX-007 AC-2 AC-3
    @Test public void malformedAndOverlimitBodiesAreNeverCachedOrRetried() throws Exception {
        RestApp app=RestApp.create();CountingGson codec=new CountingGson();app.jsonCodec(codec).jsonLimit(32);
        app.post("/malformed",(req,res)->{
            HttpException first=assertThrows(HttpException.class,()->req.body(User.class));assertEquals(400,first.status());
            assertThrows(BodyAlreadyConsumedException.class,()->req.body(User.class));assertThrows(BodyAlreadyConsumedException.class,()->req.form("name"));throw first;
        });
        app.post("/large",(req,res)->{
            HttpException first=assertThrows(HttpException.class,()->req.body(User.class));assertEquals(413,first.status());
            assertThrows(BodyAlreadyConsumedException.class,()->req.body(User.class));throw first;
        });
        app.post("/form",(req,res)->{assertEquals("Ada",req.form("name"));assertThrows(BodyAlreadyConsumedException.class,()->req.body(User.class));res.send("ok");});
        int port=app.listen(0).port();
        try {
            Reply broken=send(port,"POST","/malformed","{\"name\":", "Content-Type","application/json");assertEquals(400,broken.status);assertEquals(1,codec.decodes.get());
            Reply large=send(port,"POST","/large","{\"name\":\"1234567890123456789012345678901234567890\"}","Content-Type","application/json");assertEquals(413,large.status);assertEquals(1,codec.decodes.get());
            Reply form=send(port,"POST","/form","name=Ada","Content-Type","application/x-www-form-urlencoded");assertEquals(form.text(),200,form.status);assertEquals(1,codec.decodes.get());
        } finally {app.shutdown(0,TimeUnit.SECONDS);}
    }
}
