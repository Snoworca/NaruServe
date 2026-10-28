package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.testing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import static org.junit.Assert.*;
import static com.snoworca.naruserve.internal.HttpExecutionContractTest.*;
import static com.snoworca.naruserve.internal.HttpMethodAndErrorContractTest.error;

/** Retained extension/security contracts in the new ordered common HTTP profile. */
public class HttpExtensionRegressionContractTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    File root() throws IOException {File root=temporary.newFolder();Files.write(new File(root,"a.txt").toPath(),"file".getBytes(StandardCharsets.UTF_8));return root;}
    static RecordingExchange request(DefaultRestApp app,String path) {RecordingExchange ex=new RecordingExchange("GET",path);app.dispatcher().handle(ex);return ex;}
    static StaticHandler statics(DefaultRestApp app)throws Exception {Field field=Dispatcher.class.getDeclaredField("statics");field.setAccessible(true);return (StaticHandler)field.get(app.dispatcher());}

    @Test public void staticServiceOccupiesItsRegistrationPositionAndStopsLaterCallbacks() throws Exception { // FR-APP-021 AC-1; CON-APP-010 AC-4
        DefaultRestApp app=new DefaultRestApp();List<String> trace=new ArrayList<>();
        app.use((q,s,n)->{trace.add("before");s.header("X-Before","yes");n.run();trace.add("after-next");});
        app.staticFiles("/s",root());app.use((q,s,n)->{trace.add("too-late");n.run();});app.get("/s/a.txt",(q,s)->s.send("route"));
        RecordingExchange ex=dispatch(app,"GET","/s/a.txt");assertEquals("file",text(ex));assertEquals(Collections.singletonList("yes"),ex.committed("X-Before"));
        assertEquals(Arrays.asList("before","after-next"),trace);assertEquals(1,ex.commits.get());assertEquals(1,ex.finishes.get());
    }
    @Test public void earlierRouteStillPreventsStaticServiceAndMissContinuesWithOriginalViews() throws Exception { // FR-APP-021 AC-1; FR-HTTP-027 AC-3; CON-APP-010 AC-4
        DefaultRestApp app=new DefaultRestApp();app.get("/s/a.txt",(q,s)->s.send("route"));app.staticFiles("/s",root());
        app.get("/s/missing",(q,s)->{assertEquals("/s/missing",q.path());assertEquals("",q.baseUrl());assertEquals("/s/missing",q.rawPath());s.send("miss continued");});
        app.freeze();assertEquals("route",text(request(app,"/s/a.txt")));assertEquals("miss continued",text(request(app,"/s/missing")));
    }
    @Test public void staticFailureReachesOnlyLaterOrderedErrorsWithFileHeadersRemoved() throws Exception { // FR-APP-021 AC-1; FR-APP-024 AC-1 AC-5; FR-STATIC-011 AC-4
        DefaultRestApp app=new DefaultRestApp();List<String> trace=new ArrayList<>();IOException failure=new IOException("open failed");
        error(app,(e,q,s,n)->fail("earlier error layer restarted"));app.staticFiles("/s",root(),o->o.cache("*",CachePolicy.noStore()));
        app.onError((e,q,s)->{assertSame(failure,e);trace.add("onError");s.status(500).send("handled");});
        app.use((q,s,n)->fail("normal callback entered after static failure"));app.freeze();
        statics(app).resolverTap(source->new ResourceResolver() {
            public InputStream open(String path)throws IOException {throw failure;}
            public long length(String path)throws IOException{return source.length(path);}
            public String etag(String path)throws IOException{return source.etag(path);}
            public long modified(String path)throws IOException{return source.modified(path);}
        });
        RecordingExchange ex=request(app,"/s/a.txt");assertEquals(500,ex.status);assertEquals("handled",text(ex));assertEquals(Collections.singletonList("onError"),trace);
        for(String h:Arrays.asList("ETag","Last-Modified","Accept-Ranges","Cache-Control","Content-Range","Content-Disposition"))assertTrue(h,ex.committed(h).isEmpty());
        assertEquals(1,ex.commits.get());assertEquals(1,ex.finishes.get());
    }
    @Test public void staticMountsRemainOrderedWithARealMissBeforeTheNextMount() throws Exception { // FR-APP-021 AC-1; FR-STATIC-001 AC-3; CON-APP-010 AC-4
        File first=temporary.newFolder(),second=root();DefaultRestApp app=new DefaultRestApp();List<String> trace=new ArrayList<>();
        app.staticFiles("/s",first);app.use((q,s,n)->{trace.add("between");n.run();});app.staticFiles("/s",second);app.use((q,s,n)->fail("static answer continued"));
        RecordingExchange ex=dispatch(app,"GET","/s/a.txt");assertEquals("file",text(ex));assertEquals(Collections.singletonList("between"),trace);
    }
    @Test public void explicitPolicyAfterRouteRegistrationAppliesCaseAndTrailingRules() { // FR-APP-021 AC-5; FR-ROUTE-006 AC-1 AC-2 AC-3; CON-APP-010 AC-1
        for(boolean before:Arrays.asList(true,false)) {
            DefaultRestApp app=new DefaultRestApp();if(before)app.routerPolicy(RouterPolicy.strict());app.get("/Case",(q,s)->s.send("strict"));if(!before)app.routerPolicy(RouterPolicy.strict());
            app.freeze();assertEquals("strict",text(request(app,"/Case")));assertEquals(404,request(app,"/case").status);assertEquals(404,request(app,"/Case/").status);
        }
    }
    @Test public void explicitPolicySwitchesOperateIndependently() { // FR-APP-021 AC-5; FR-ROUTE-006 AC-1 AC-2 AC-3; CON-APP-010 AC-1
        for(int mode=0;mode<3;mode++) {
            DefaultRestApp app=new DefaultRestApp();app.get("/A/B",(q,s)->s.send("yes"));app.routerPolicy(RouterPolicy.builder().caseInsensitive(mode==0).ignoreTrailingSlash(mode==1).mergeSlashes(mode==2).build());app.freeze();
            assertEquals("yes",text(request(app,"/A/B")));
            assertEquals(mode==0?200:404,request(app,"/a/b").status);assertEquals(mode==1?200:404,request(app,"/A/B/").status);assertEquals(mode==2?200:404,request(app,"/A//B").status);
            assertEquals(404,request(app,"/A/B//").status);
        }
    }
    @Test public void explicitMergeAndTrailingPolicyRetainsRawRequestAndCaptureValues() { // FR-APP-021 AC-5; FR-HTTP-027 AC-1 AC-2 AC-3; FR-ROUTE-006 AC-1 AC-2 AC-3
        DefaultRestApp app=new DefaultRestApp();app.get("/A/:id",(q,s)->{assertEquals("/a//x%2Fy/",q.rawPath());assertEquals(q.rawPath(),q.path());assertEquals("x/y",q.param("id"));s.send("yes");});
        app.routerPolicy(RouterPolicy.builder().caseInsensitive(true).ignoreTrailingSlash(true).mergeSlashes(true).build());
        assertEquals("yes",text(dispatch(app,"GET","/a//x%2Fy/")));
    }
    @Test public void explicitMergePolicyMapsMountedConsumedOffsetsBackToTheOriginalRawPath() { // FR-APP-021 AC-5; FR-ROUTE-020 AC-2; FR-HTTP-027 AC-3 AC-4
        DefaultRestApp app=new DefaultRestApp();Router child=Router.create(true);List<String> trace=new ArrayList<>();
        child.get("/leaf",(q,s,n)->{
            assertEquals("/leaf",q.path());assertEquals("/api///x%2Fy",q.baseUrl());assertEquals("x/y",q.param("id"));
            assertEquals("/leaf?q=a%2Fb",q.url());assertEquals("/api///x%2Fy/leaf?q=a%2Fb",q.originalUrl());trace.add("child");n.router();
        });
        app.use("/API/:id",child);app.get("/API/:id/leaf",(q,s)->{
            assertEquals("/api///x%2Fy/leaf",q.path());assertEquals(q.path(),q.rawPath());assertEquals("",q.baseUrl());assertEquals(q.originalUrl(),q.url());assertEquals("x/y",q.param("id"));trace.add("parent");s.send("yes");
        });
        app.routerPolicy(RouterPolicy.builder().caseInsensitive(true).mergeSlashes(true).ignoreTrailingSlash(true).build());app.freeze();
        LifecycleExchange ex=new LifecycleExchange("GET","/api///x%2Fy/leaf");ex.query="q=a%2Fb";app.dispatcher().handle(ex);
        assertEquals("yes",text(ex.recording));assertEquals(Arrays.asList("child","parent"),trace);
    }
    @Test public void explicitPolicyPreservesRouteIdentityAndBuilderAddedAfterFreeze() { // FR-APP-021 AC-2 AC-4 AC-5
        DefaultRestApp app=new DefaultRestApp();RouteBuilder route=app.route("/Case");route.get((q,s,n)->n.route());
        app.get("/Case",(q,s)->s.send("separate"));app.routerPolicy(RouterPolicy.strict());app.freeze();route.get((q,s)->fail("route skip lost identity"));
        assertEquals("separate",text(request(app,"/Case")));assertEquals(404,request(app,"/case").status);
    }
    @Test public void javaPatternFlagsRemainIndependentOfAppStringPolicy() { // FR-ROUTE-019 AC-2; FR-APP-021 AC-5
        DefaultRestApp app=new DefaultRestApp();app.get(Pattern.compile("/Regex"),(q,s)->s.send("pattern"));app.routerPolicy(RouterPolicy.builder().caseInsensitive(true).ignoreTrailingSlash(true).mergeSlashes(true).build());app.freeze();
        assertEquals("pattern",text(request(app,"/Regex")));assertEquals(404,request(app,"/regex").status);assertEquals(404,request(app,"//Reg//ex").status);
    }
    @Test public void implicitCommonDefaultsRemainSeparateFromExplicitStrictPolicy() { // FR-APP-021 AC-5; CON-APP-010 AC-1
        DefaultRestApp app=new DefaultRestApp();app.get("/A/B",(q,s)->s.send("default"));app.freeze();
        assertEquals("default",text(request(app,"/a/b/")));assertEquals(404,request(app,"/a//b").status);
        on(app,"TRACE","/trace",(q,s,n)->s.send("trace"));assertEquals("trace",text(newRequest(app,"TRACE","/trace")));
    }
    static RecordingExchange newRequest(DefaultRestApp app,String method,String path){RecordingExchange ex=new RecordingExchange(method,path);app.dispatcher().handle(ex);return ex;}
    @Test public void explicitLegacyMethodFlagsDoNotDisableCommonRegisteredWireMethods() { // FR-HTTP-028 AC-1 AC-6; CON-APP-010 AC-3
        DefaultRestApp app=new DefaultRestApp();app.routerPolicy(RouterPolicy.strict());on(app,"TRACE","/x",(q,s,n)->s.send("trace"));on(app,"CONNECT","/x",(q,s,n)->s.send("connect"));app.freeze();
        assertEquals("trace",text(newRequest(app,"TRACE","/x")));assertEquals("connect",text(newRequest(app,"CONNECT","/x")));
    }
    @Test public void clientHttpExceptionsNeverCreateErrorLevelRecordsEvenWhenHandled() { // FR-APP-005 AC-8; CON-APP-010 AC-4
        for(boolean handled:Arrays.asList(false,true))for(int status:new int[]{400,404,413,499}) {
            RecordingLogger log=new RecordingLogger();DefaultRestApp app=new DefaultRestApp();app.logger(log);HttpException failure=new HttpException(status);app.get("/x",(q,s)->{throw failure;});
            if(handled)app.onError((e,q,s)->{assertSame(failure,e);s.status(status).send("handled");});
            assertEquals(status,dispatch(app,"GET","/x").status);assertEquals(0,log.count("ERROR"));assertEquals(1,log.info().size());
        }
    }
    @Test public void serverFailuresLogOriginalThrowableExactlyOnceBeforeOrAfterAnErrorAnswer() { // FR-APP-005 AC-8; FR-APP-024 AC-1 AC-4; CON-APP-010 AC-4
        for(boolean handled:Arrays.asList(false,true)) {
            RecordingLogger log=new RecordingLogger();DefaultRestApp app=new DefaultRestApp();app.logger(log);IOException failure=new IOException("server failure");app.get("/x",(q,s)->{throw failure;});
            if(handled)app.onError((e,q,s)->{assertSame(failure,e);s.status(500).send("handled");});
            RecordingExchange ex=dispatch(app,"GET","/x");assertEquals(500,ex.status);assertEquals(1,log.count("ERROR"));assertEquals(Collections.singletonList(failure),log.errors);assertEquals(1,log.info().size());assertEquals(1,ex.commits.get());
        }
    }
    @Test public void lateServerFailureRemainsObservableWithoutASecondCommitOrDuplicateLog() { // FR-APP-005 AC-8; FR-APP-024 AC-4
        RecordingLogger log=new RecordingLogger();DefaultRestApp app=new DefaultRestApp();app.logger(log);RuntimeException failure=new RuntimeException("late");
        app.get("/x",(q,s,n)->{s.send("done");n.error(failure);});app.onError((e,q,s)->assertSame(failure,e));
        RecordingExchange ex=dispatch(app,"GET","/x");assertEquals("done",text(ex));assertEquals(1,ex.commits.get());assertEquals(1,log.count("ERROR"));assertEquals(Collections.singletonList(failure),log.errors);
    }
    @Test public void invalidDecodedUpgradePathsRetainLegacy400WithoutChangingCommonRawMatching() { // FR-APP-021 AC-6; FR-ENGINE-012 AC-3; CON-APP-010 AC-1 AC-2
        DefaultRestApp app=new DefaultRestApp();AtomicInteger common=new AtomicInteger(),opened=new AtomicInteger();app.ws("/ws/:id",(q,s)->opened.incrementAndGet());
        app.get("/ws/:id",(q,s)->{common.incrementAndGet();s.send(q.param("id"));});app.freeze();
        for(String path:Arrays.asList("/ws/a%2Fb","/ws/%00","/ws/%ZZ")) {
            RecordingExchange ex=new RecordingExchange("GET",path,"Connection","Upgrade","Upgrade","websocket");app.dispatcher().handle(ex);
            assertEquals(path,400,ex.status);assertEquals(0,opened.get());assertEquals(0,common.get());
        }
        assertEquals("a/b",text(request(app,"/ws/a%2Fb")));assertEquals("\u0000",text(request(app,"/ws/%00")));assertEquals(2,common.get());
    }
}
