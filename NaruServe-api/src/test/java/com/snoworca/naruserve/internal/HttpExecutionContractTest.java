package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import com.snoworca.naruserve.spi.*;
import com.snoworca.naruserve.testing.*;
import org.junit.Test;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Actual public app dispatch contracts. Reflection permits executable test-first RED before new API types exist. */
public class HttpExecutionContractTest {
    static Object invoke(Object target,String name,Class<?>[] types,Object... args) {
        try { return (target instanceof Next?Next.class:target.getClass()).getMethod(name,types).invoke(target,args); }
        catch(InvocationTargetException e) {
            Throwable t=e.getCause(); if(t instanceof RuntimeException)throw (RuntimeException)t;
            if(t instanceof Error)throw (Error)t; throw new AssertionError(t);
        } catch(Exception e) { throw new AssertionError("required public API "+name,e); }
    }
    static void on(Object target,String method,Object path,Middleware... handlers) {
        invoke(target,"method",new Class<?>[]{String.class,Object.class,Middleware[].class},method,path,handlers);
    }
    static void callback(RouteBuilder target,Middleware handler) {
        invoke(target,"get",new Class<?>[]{Middleware[].class},(Object)new Middleware[]{handler});
    }
    static void signal(Next next,String name) { invoke(next,name,new Class<?>[0]); }
    static Object view(Request req,String name) { return invoke(req,name,new Class<?>[0]); }
    static Object parameter(Request req,String name,String key) { return invoke(req,name,new Class<?>[]{String.class},key); }
    static RecordingExchange dispatch(DefaultRestApp app,String method,String path) {
        app.freeze(); RecordingExchange ex=new RecordingExchange(method,path); app.dispatcher().handle(ex); return ex;
    }
    static String text(RecordingExchange ex) { return new String(ex.body.toByteArray(),StandardCharsets.UTF_8); }

    @Test public void registrationOrderInterleavesUseAndRoutes() { // FR-APP-021 AC-1; CON-APP-010 AC-1
        DefaultRestApp app=new DefaultRestApp(); List<String> trace=new ArrayList<>();
        app.use((q,s,n)->{trace.add("before");n.run();trace.add("after");});
        app.get("/x",(q,s)->{trace.add("route");s.send("ok");});
        app.use((q,s,n)->{trace.add("too-late");n.run();});
        assertEquals(200,dispatch(app,"GET","/x").status);
        assertEquals(Arrays.asList("before","route","after"),trace);
    }
    @Test public void overlappingDuplicateRoutesContinueInOrder() { // FR-APP-021 AC-1 AC-5; FR-APP-022 AC-1
        DefaultRestApp app=new DefaultRestApp(); List<String> trace=new ArrayList<>();
        on(app,"GET","/:id",(q,s,n)->{trace.add(q.param("id"));n.run();});
        on(app,"GET","/fixed",(q,s,n)->{trace.add("fixed");n.run();});
        app.get("/fixed",(q,s)->s.send("last"));
        assertEquals("last",text(dispatch(app,"GET","/fixed")));
        assertEquals(Arrays.asList("fixed","fixed"),trace);
    }
    @Test public void builderInsertionIdentityAndRouteSkip() { // FR-APP-021 AC-2; FR-APP-022 AC-2
        DefaultRestApp app=new DefaultRestApp(); List<String> trace=new ArrayList<>();
        RouteBuilder builder=app.route("/x");
        app.get("/x",(q,s)->{trace.add("separate");s.send("ok");});
        callback(builder,(q,s,n)->{trace.add("first");signal(n,"route");});
        callback(builder,(q,s,n)->{trace.add("skipped");n.run();});
        assertEquals("ok",text(dispatch(app,"GET","/x")));
        assertEquals(Arrays.asList("first","separate"),trace);
    }
    @Test public void sameRouterAfterNextSeesDownstreamParameterView() { // FR-HTTP-027 AC-4; FR-APP-022 AC-1 AC-5
        DefaultRestApp app=new DefaultRestApp(); List<Object> trace=new ArrayList<>(); AtomicReference<Request> identity=new AtomicReference<>();
        on(app,"GET","/:first",(q,s,n)->{identity.set(q);trace.add(q.param("first"));n.run();trace.add(parameter(q,"paramOrNull","first"));trace.add(q.param("second"));});
        on(app,"GET","/:second",(q,s,n)->{assertSame(identity.get(),q);s.send("ok");});
        dispatch(app,"GET","/value");
        assertEquals(Arrays.asList("value",null,"value"),trace);
    }
    @Test public void listScalarOptionalRawViewsAndDecodeExactlyOnce() { // FR-HTTP-027 AC-1 AC-2 AC-3 AC-6
        DefaultRestApp app=new DefaultRestApp();
        app.get("/files/:id{/*parts}",(q,s)->{
            assertEquals("/files/a%252Fb/x%2Fy//z",q.path());
            assertEquals(q.rawPath(),q.path());
            assertEquals(q.path(),view(q,"originalUrl")); assertEquals(q.path(),view(q,"url"));
            assertEquals("",view(q,"baseUrl"));
            assertEquals("a%2Fb",q.param("id"));
            assertEquals(Arrays.asList("x/y","","z"),parameter(q,"paramValues","parts"));
            @SuppressWarnings("unchecked") Map<String,Object> params=(Map<String,Object>)view(q,"pathParams");
            assertEquals("a%2Fb",params.get("id")); assertEquals(Arrays.asList("x/y","","z"),params.get("parts"));
            try{params.put("x","y");fail();}catch(UnsupportedOperationException expected){}
            @SuppressWarnings("unchecked") List<String> parts=(List<String>)params.get("parts");
            try{parts.add("x");fail();}catch(UnsupportedOperationException expected){}
            assertNull(parameter(q,"paramOrNull","absent"));
            assertEquals(Collections.emptyList(),parameter(q,"paramValues","absent"));
            s.send("ok");
        });
        assertEquals("ok",text(dispatch(app,"GET","/files/a%252Fb/x%2Fy//z")));
    }
    @Test public void unmountedUrlsPreserveRawQueryAndEmptyQuestionMark() { // FR-HTTP-027 AC-3
        DefaultRestApp app=new DefaultRestApp(); List<List<Object>> seen=new ArrayList<>();
        app.get("/x/:id",(q,s)->{
            seen.add(Arrays.asList(q.path(),q.rawPath(),view(q,"url"),view(q,"originalUrl"),view(q,"baseUrl")));
            s.send("ok");
        });
        app.freeze(); InMemoryHttpAdapter adapter=new InMemoryHttpAdapter();
        adapter.bind("127.0.0.1",0,app.dispatcher());
        assertEquals(200,adapter.send(InMemoryHttpAdapter.ClientRequest.of("GET","/x/a%2Fb?q=%252F&v=1")).status());
        assertEquals(200,adapter.send(InMemoryHttpAdapter.ClientRequest.of("GET","/x/a%2Fb?")).status());
        assertEquals(200,adapter.send(InMemoryHttpAdapter.ClientRequest.of("GET","/x/a%2Fb")).status());
        assertEquals(Arrays.asList("/x/a%2Fb","/x/a%2Fb","/x/a%2Fb?q=%252F&v=1","/x/a%2Fb?q=%252F&v=1",""),seen.get(0));
        assertEquals(Arrays.asList("/x/a%2Fb","/x/a%2Fb","/x/a%2Fb?","/x/a%2Fb?",""),seen.get(1));
        assertEquals(Arrays.asList("/x/a%2Fb","/x/a%2Fb","/x/a%2Fb","/x/a%2Fb",""),seen.get(2));
    }
    @Test public void absentOptionalAndOneSegmentWildcardRetainShape() { // FR-HTTP-027 AC-1
        DefaultRestApp app=new DefaultRestApp(); List<Object> params=new ArrayList<>();
        app.get("/x{/*rest}",(q,s)->{params.add(view(q,"pathParams"));s.end();});
        dispatch(app,"GET","/x"); dispatch(app,"GET","/x/a");
        assertEquals(Collections.emptyMap(),params.get(0));
        assertEquals(Collections.singletonMap("rest",Collections.singletonList("a")),params.get(1));
    }
    @Test public void malformedOnlyCapturedPercentSequencesReject() { // FR-HTTP-027 AC-2; CON-APP-010 AC-2
        DefaultRestApp app=new DefaultRestApp(); AtomicInteger hits=new AtomicInteger();
        app.get("/literal/%ZZ",(q,s)->{hits.incrementAndGet();s.send("literal");});
        app.get("/capture/:id",(q,s)->{hits.incrementAndGet();s.send("capture");});
        assertEquals(200,dispatch(app,"GET","/literal/%ZZ").status);
        assertEquals(400,dispatch(app,"GET","/capture/%ZZ").status);
        assertEquals(400,dispatch(app,"GET","/capture/%C0%AF").status);
        assertEquals(200,dispatch(app,"GET","/capture/%00").status);
        assertEquals(2,hits.get());
    }
    @Test public void commonDefaultsKeepRepeatedSlashesMeaningful() { // FR-APP-021 AC-5; FR-HTTP-028 AC-2
        DefaultRestApp app=new DefaultRestApp(); app.get("/Case/Path",(q,s)->s.send("ok"));
        assertEquals(200,dispatch(app,"GET","/case/path/").status);
        assertEquals(404,dispatch(app,"GET","/case//path").status);
        assertEquals(404,dispatch(app,"GET","/case/path//").status);
    }
    @Test public void explicitNextAfterResponseAndRepeatedNextStillAdvance() { // FR-APP-022 AC-3; FR-APP-023 AC-5
        DefaultRestApp app=new DefaultRestApp(); List<String> trace=new ArrayList<>();
        app.use((q,s,n)->{s.send("once");n.run();trace.add("between");n.run();});
        app.use((q,s,n)->trace.add("second"));
        app.use((q,s,n)->trace.add("third"));
        RecordingExchange ex=dispatch(app,"GET","/x");
        assertEquals(Arrays.asList("second","between","third"),trace);
        assertEquals(1,ex.commits.get()); assertEquals(1,ex.finishes.get()); assertEquals("once",text(ex));
    }
    @Test public void initialReturnWithoutResponseOrNextStaysPendingThenResumes() { // FR-APP-022 AC-1; FR-APP-023 AC-1 AC-2
        DefaultRestApp app=new DefaultRestApp(); AtomicReference<Next> saved=new AtomicReference<>();
        app.use((q,s,n)->saved.set(n)); app.get("/x",(q,s)->s.send("later"));
        RecordingExchange ex=dispatch(app,"GET","/x");
        assertEquals(0,ex.commits.get()); assertEquals(0,ex.finishes.get());
        saved.get().run();
        assertEquals("later",text(ex)); assertEquals(1,ex.finishes.get());
    }
    @Test(timeout=5000) public void offThreadNextCanBeJoinedAndIsQueuedUntilCallbackReturns() throws Exception { // FR-APP-022 AC-5; FR-APP-023 AC-4 AC-5
        DefaultRestApp app=new DefaultRestApp(); List<String> trace=Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> failure=new AtomicReference<>();
        app.use((q,s,n)->{trace.add("before");Thread t=new Thread(()->{try{n.run();trace.add("queued");}catch(Throwable e){failure.set(e);}});t.start();t.join(2000);assertFalse("next blocked on user callback",t.isAlive());trace.add("return");});
        app.get("/x",(q,s)->{trace.add("route");s.send("ok");});
        assertEquals("ok",text(dispatch(app,"GET","/x"))); assertNull(failure.get());
        assertEquals(Arrays.asList("before","queued","return","route"),trace);
    }
    @Test public void lateRegistrationAndBuilderAttachmentArePublished() { // FR-APP-021 AC-4
        SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();
        DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),new ConsoleLogger());
        RouteBuilder b=app.route("/builder"); app.listen(0);
        try {
            assertEquals(AppState.RUNNING,app.state());
            app.get("/late",(q,s)->s.send("late")); b.get((q,s)->s.send("attached"));
            RecordingExchange first=new RecordingExchange("GET","/late"), second=new RecordingExchange("GET","/builder");
            adapter.handler.handle(first); adapter.handler.handle(second);
            assertEquals("late",text(first)); assertEquals("attached",text(second));
            try{app.jsonLimit(32);fail("configuration freeze removed");}catch(IllegalStateException expected){}
        } finally { app.shutdown(0,TimeUnit.SECONDS); }
    }
    @Test public void actualDuplicateWinnerDeterminesScalarOrWildcardShape() { // FR-HTTP-027 AC-1
        DefaultRestApp optional=new DefaultRestApp(); List<Object> seen=new ArrayList<>();
        optional.get("/:x{/*x}",(q,s)->{
            seen.add(view(q,"pathParams"));
            String expected=q.path().equals("/one")?"one":"two";
            assertEquals(expected,parameter(q,"paramOrNull","x"));
            assertEquals(Collections.singletonList(expected),parameter(q,"paramValues","x"));
            s.end();
        });
        dispatch(optional,"GET","/one"); dispatch(optional,"GET","/one/two");
        assertEquals(Collections.singletonMap("x","one"),seen.get(0));
        assertEquals(Collections.singletonMap("x",Collections.singletonList("two")),seen.get(1));
        DefaultRestApp scalar=new DefaultRestApp();
        scalar.get("/*x/:x",(q,s)->{
            seen.add(view(q,"pathParams"));
            assertEquals("three",parameter(q,"paramOrNull","x"));
            assertEquals(Collections.singletonList("three"),parameter(q,"paramValues","x"));
            s.end();
        });
        dispatch(scalar,"GET","/one/two/three");
        assertEquals(Collections.singletonMap("x","three"),seen.get(2));
    }
    @Test public void continuationCanObserveAtomicallyAppendedLayer() { // FR-APP-021 AC-4; FR-APP-022 AC-1
        DefaultRestApp app=new DefaultRestApp();
        app.use((q,s,n)->{app.get("/dynamic",(q2,s2)->s2.send("added"));n.run();});
        assertEquals("added",text(dispatch(app,"GET","/dynamic")));
    }
    @Test public void routeSkipInUseBehavesLikeNormalNext() { // FR-APP-022 AC-2
        DefaultRestApp app=new DefaultRestApp(); app.use((q,s,n)->signal(n,"route"));app.get("/x",(q,s)->s.send("ok"));
        assertEquals("ok",text(dispatch(app,"GET","/x")));
    }
    @Test public void invalidRegistrationIsAtomicAndDoesNotHideValidRoute() { // FR-APP-021 AC-3
        DefaultRestApp app=new DefaultRestApp();
        try{on(app,"GET",new Object[]{"/x","/:"},(q,s,n)->s.send("partial"));fail();}catch(RuntimeException expected){}
        app.get("/x",(q,s)->s.send("valid"));assertEquals("valid",text(dispatch(app,"GET","/x")));
    }
}
