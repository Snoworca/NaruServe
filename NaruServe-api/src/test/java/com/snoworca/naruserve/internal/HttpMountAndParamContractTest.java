package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import com.snoworca.naruserve.testing.*;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;
import static org.junit.Assert.*;
import static com.snoworca.naruserve.internal.HttpExecutionContractTest.*;

/** Pinned Express5 router-layer and parameter contracts; public app execution, not helper-only checks. */
public class HttpMountAndParamContractTest {
    static Object router(boolean merge,boolean sensitive,boolean strict) {
        try{return Class.forName("com.snoworca.naruserve.Router").getMethod("create",boolean.class,boolean.class,boolean.class).invoke(null,merge,sensitive,strict);}
        catch(Exception e){throw new AssertionError("required mounted Router API",e);}
    }
    static Object router(boolean merge){return router(merge,false,false);}
    static void mount(Object parent,Object path,Object child) {
        try{invoke(parent,"use",new Class<?>[]{Object.class,Class.forName("com.snoworca.naruserve.Router")},path,child);}
        catch(ClassNotFoundException e){throw new AssertionError(e);}
    }
    interface ParamAction { void run(Request request,Response response,Next next,List<String> value,String name) throws Exception; }
    static void hook(Object target,String name,ParamAction action) {
        try {
            Class<?> type=Class.forName("com.snoworca.naruserve.ParamHandler");
            Object callback=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,args)->{
                @SuppressWarnings("unchecked") List<String> values=(List<String>)args[3];
                action.run((Request)args[0],(Response)args[1],(Next)args[2],values,(String)args[4]);return null;
            });
            invoke(target,"param",new Class<?>[]{String.class,type},name,callback);
        }catch(ClassNotFoundException e){throw new AssertionError("required param callback API",e);}
    }
    static void set(Request request,String key,String value){invoke(request,"param",new Class<?>[]{String.class,String.class},key,value);}
    static InMemoryHttpAdapter.ClientResponse send(DefaultRestApp app,String method,String target) {
        app.freeze();InMemoryHttpAdapter adapter=new InMemoryHttpAdapter();adapter.bind("127.0.0.1",0,app.dispatcher());
        return adapter.send(InMemoryHttpAdapter.ClientRequest.of(method,target));
    }
    @Test public void mountedRawViewsAndOriginalQueryUseActualMatchedText() { // FR-ROUTE-020 AC-1 AC-2; FR-HTTP-027 AC-3
        DefaultRestApp app=new DefaultRestApp();Object child=router(false);
        on(child,"GET","/leaf",(q,s,n)->{
            assertEquals("/leaf",q.path());assertEquals("/API/a%2Fb/leaf",q.rawPath());
            assertEquals("/leaf?x=%252F",view(q,"url"));assertEquals("/API/a%2Fb",view(q,"baseUrl"));
            assertEquals("/API/a%2Fb/leaf?x=%252F",view(q,"originalUrl"));
            assertNull(parameter(q,"paramOrNull","id"));s.send("child");
        });
        mount(app,"/api/:id",child);
        assertEquals("child",send(app,"GET","/API/a%2Fb/leaf?x=%252F").text());
    }
    @Test public void childRouterExitDefersParentAndRestoresViews() { // FR-ROUTE-020 AC-4; FR-HTTP-027 AC-4; FR-APP-022 AC-2 AC-5
        for(String operation:Arrays.asList("run","router")) {
            DefaultRestApp app=new DefaultRestApp();Object child=router(false);List<String> trace=new ArrayList<>();
            app.use((q,s,n)->{trace.add("before "+view(q,"url"));n.run();trace.add("after "+view(q,"url")+" "+view(q,"baseUrl"));});
            on(child,"GET","/leaf",(q,s,n)->{trace.add("child");signal(n,operation);trace.add("child-after "+q.path());});
            mount(app,"/api/:id",child);
            app.use((q,s,n)->{trace.add("parent "+view(q,"url")+" "+view(q,"baseUrl"));s.send("last");});
            assertEquals("last",send(app,"GET","/api/a/leaf?x=1").text());
            assertEquals(Arrays.asList("before /api/a/leaf?x=1","child","child-after /leaf","after /leaf?x=1 /api/a","parent /api/a/leaf?x=1 "),trace);
        }
    }
    @Test public void childSendWithoutNextRetainsChildViewForAfterNextObserver() { // FR-ROUTE-020 AC-4; FR-HTTP-027 AC-4
        DefaultRestApp app=new DefaultRestApp();Object child=router(false);List<String> trace=new ArrayList<>();
        app.use((q,s,n)->{n.run();trace.add(q.path()+" "+view(q,"baseUrl"));});
        on(child,"GET","/leaf",(q,s,n)->s.send("ok"));mount(app,"/api",child);
        assertEquals("ok",send(app,"GET","/api/leaf").text());assertEquals(Collections.singletonList("/leaf /api"),trace);
    }
    @Test public void ordinaryPrefixMiddlewareTrimsThenRestoresBeforeSynchronousNext() { // FR-ROUTE-020 AC-2; FR-HTTP-027 AC-4
        DefaultRestApp app=new DefaultRestApp();List<String> trace=new ArrayList<>();
        app.use("/api/:id",(q,s,n)->{trace.add(q.path()+" "+view(q,"baseUrl")+" "+q.param("id"));n.run();trace.add(q.path()+" "+view(q,"baseUrl"));});
        app.get("/api/:id/leaf",(q,s)->s.send("ok"));
        assertEquals("ok",send(app,"GET","/api/a/leaf").text());
        assertEquals(Arrays.asList("/leaf /api/a a","/api/a/leaf "),trace);
    }
    @Test public void rootEmptyNestedAndRepeatedSlashMounts() { // FR-ROUTE-020 AC-2
        for(String prefix:Arrays.asList("","/")) {
            DefaultRestApp app=new DefaultRestApp();Object outer=router(false),inner=router(false);
            on(inner,"GET","//leaf",(q,s,n)->{assertEquals("//leaf?",view(q,"url"));assertEquals("/api/v1",view(q,"baseUrl"));s.send("ok");});
            mount(outer,"/v1",inner);mount(app,prefix,outer);
            Object root=router(false);mount(root,"/api",outer); // root helper is independently reusable
            DefaultRestApp nested=new DefaultRestApp();mount(nested,prefix,root);
            assertEquals("ok",send(nested,"GET","/api/v1//leaf?").text());
        }
    }
    @Test public void mountBoundaryAndNonstrictRegistrationLoosening() { // FR-ROUTE-020 AC-2; FR-APP-021 AC-5
        DefaultRestApp app=new DefaultRestApp();Object child=router(false);
        on(child,"GET","/leaf",(q,s,n)->s.send("child"));mount(app,"/api///",child);
        assertEquals("child",send(app,"GET","/api/leaf").text());
        assertEquals(404,send(app,"GET","/apix/leaf").status());
        DefaultRestApp full=new DefaultRestApp();full.get("/x///",(q,s)->s.send("full"));
        assertEquals("full",send(full,"GET","/x").text());assertEquals("full",send(full,"GET","/x/").text());
        assertEquals(404,send(full,"GET","/x//").status());
    }
    @Test public void routerOptionsAndMergeParamsRemainLocal() { // FR-ROUTE-020 AC-1 AC-3
        DefaultRestApp app=new DefaultRestApp();Object merged=router(true,true,true),plain=router(false);
        on(merged,"GET","/Case/:id/",(q,s,n)->{
            assertEquals("child",q.param("id"));assertEquals("parent",q.param("outer"));s.send("merged");
        });
        mount(app,"/m/:outer/:id",merged);
        on(plain,"GET","/:id",(q,s,n)->{assertNull(parameter(q,"paramOrNull","outer"));s.send(q.param("id"));});mount(app,"/p/:outer",plain);
        assertEquals("merged",send(app,"GET","/m/parent/old/Case/child/").text());
        assertEquals(404,send(app,"GET","/m/parent/old/case/child/").status());
        assertEquals(404,send(app,"GET","/m/parent/old/Case/child").status());
        assertEquals("new",send(app,"GET","/p/parent/new").text());
    }
    @Test public void separateMountInvocationsDoNotLeakParamsOrHookCache() { // FR-ROUTE-020 AC-3 AC-4; FR-ROUTE-021 AC-3
        DefaultRestApp app=new DefaultRestApp();Object child=router(false);AtomicInteger calls=new AtomicInteger();
        hook(child,"id",(q,s,n,v,k)->{calls.incrementAndGet();n.run();});
        on(child,"GET","/:id",(q,s,n)->signal(n,"router"));mount(app,"/api",child);mount(app,"/api",child);
        app.use((q,s,n)->{assertEquals("/api/x",q.path());assertEquals("",view(q,"baseUrl"));s.send("done");});
        assertEquals("done",send(app,"GET","/api/x").text());assertEquals(2,calls.get());
    }
    @Test public void parameterHooksCacheTransformationsAndUseActualKeyOrder() { // FR-ROUTE-021 AC-1 AC-2 AC-4 AC-5
        DefaultRestApp app=new DefaultRestApp();List<String> calls=new ArrayList<>();
        hook(app,"id",(q,s,n,v,k)->{calls.add(k+v);set(q,k,"transformed");n.run();});
        on(app,"GET","/:id",(q,s,n)->{assertEquals("transformed",q.param("id"));n.run();});
        on(app,"GET","/:id",(q,s,n)->{assertEquals("transformed",q.param("id"));s.send("ok");});
        assertEquals("ok",send(app,"GET","/x").text());assertEquals(Collections.singletonList("id[x]"),calls);
        DefaultRestApp order=new DefaultRestApp();List<String> names=new ArrayList<>();
        for(String name:Arrays.asList("10","2","a"))hook(order,name,(q,s,n,v,k)->{names.add(k);n.run();});
        order.get("/:\"10\"/:a/:\"2\"",(q,s)->s.end());send(order,"GET","/ten/A/two");
        assertEquals(Arrays.asList("2","10","a"),names);
    }
    @Test public void lastValueCacheRerunsWhenValueChangesAndRecurs() { // FR-ROUTE-021 AC-2
        DefaultRestApp app=new DefaultRestApp();List<String> calls=new ArrayList<>();
        hook(app,"id",(q,s,n,v,k)->{calls.add(v.get(0));n.run();});
        on(app,"GET","/:id/:other",(q,s,n)->n.run());on(app,"GET","/:other/:id",(q,s,n)->n.run());
        on(app,"GET","/:id/:other",(q,s,n)->s.send("ok"));
        assertEquals("ok",send(app,"GET","/one/two").text());assertEquals(Arrays.asList("one","two","one"),calls);
    }
    @Test public void wildcardArraysUseIdentityRatherThanScalarValueCache() { // FR-ROUTE-021 AC-2; FR-HTTP-027 AC-1
        DefaultRestApp app=new DefaultRestApp();AtomicInteger calls=new AtomicInteger();
        hook(app,"id",(q,s,n,v,k)->{assertEquals(Arrays.asList("a","b"),v);calls.incrementAndGet();n.run();});
        on(app,"GET","/*id",(q,s,n)->n.run());on(app,"GET","/*id",(q,s,n)->s.send("ok"));
        assertEquals("ok",send(app,"GET","/a/b").text());assertEquals(2,calls.get());
    }
    @Test public void hooksAreRouterLocalAndInheritedParamsDoNotTriggerUndeclaredChildHook() { // FR-ROUTE-021 AC-3
        DefaultRestApp app=new DefaultRestApp();Object child=router(true);List<String> calls=new ArrayList<>();
        hook(app,"id",(q,s,n,v,k)->{calls.add("parent");n.run();});hook(child,"id",(q,s,n,v,k)->{calls.add("child");n.run();});
        on(child,"GET","/leaf",(q,s,n)->{assertEquals("x",q.param("id"));n.run();});
        on(child,"GET","/:id",(q,s,n)->s.send("ok"));mount(app,"/:id",child);
        assertEquals("ok",send(app,"GET","/x/leaf").text());assertEquals(Arrays.asList("parent","child"),calls);
    }
    @Test public void delayedMultipleHooksAndRouteSkipUseSharedCursor() { // FR-ROUTE-021 AC-2 AC-4
        DefaultRestApp app=new DefaultRestApp();List<String> calls=new ArrayList<>();AtomicReference<Next> delayed=new AtomicReference<>();
        hook(app,"id",(q,s,n,v,k)->{calls.add("first");delayed.set(n);});
        hook(app,"id",(q,s,n,v,k)->{calls.add("second");signal(n,"route");});
        on(app,"GET","/:id",(q,s,n)->fail("skipped route ran"));
        on(app,"GET","/:id",(q,s,n)->fail("cached skip route ran"));
        app.get("/x",(q,s)->s.send("fallback"));
        RecordingExchange ex=dispatch(app,"GET","/x");assertEquals(0,ex.commits.get());
        delayed.get().run();assertEquals("fallback",text(ex));assertEquals(Arrays.asList("first","second"),calls);
    }
    @Test public void hookThrowAndExplicitErrorReachErrorHandling() { // FR-ROUTE-021 AC-4
        for(boolean explicit:Arrays.asList(true,false)) {
            DefaultRestApp app=new DefaultRestApp();
            hook(app,"id",(q,s,n,v,k)->{if(explicit)n.error(new HttpException(403));else throw new HttpException(403);});
            app.get("/:id",(q,s)->fail("handler after hook failure"));
            assertEquals(403,send(app,"GET","/x").status());
        }
    }
    @Test public void invalidParameterRegistrationsAreAtomic() throws Exception { // FR-ROUTE-021 AC-5
        DefaultRestApp app=new DefaultRestApp();Class<?> type=Class.forName("com.snoworca.naruserve.ParamHandler");
        try{invoke(app,"param",new Class<?>[]{String.class,type},"id",null);fail();}catch(NullPointerException expected){}
        try{hook(app,null,(q,s,n,v,k)->fail());fail();}catch(NullPointerException expected){}
        app.get("/:id",(q,s)->s.send(q.param("id")));assertEquals("x",send(app,"GET","/x").text());
    }
    @Test public void orderedNestedAlternativesAndJavaPatternSearchIntegrate() { // FR-ROUTE-019 AC-1 AC-2 AC-3 AC-4
        DefaultRestApp app=new DefaultRestApp();
        on(app,"GET",Arrays.asList(Arrays.asList(Pattern.compile("(?<id>[^/]+)$")),"/*all"),(q,s,n)->{
            assertEquals("a/b",q.param("id"));assertNull(parameter(q,"paramOrNull","all"));s.send("regex");
        });
        assertEquals("regex",send(app,"GET","/r/a%2Fb").text());
        DefaultRestApp first=new DefaultRestApp();on(first,"GET",new Object[]{"/*all","/specific"},(q,s,n)->{
            assertEquals(Collections.singletonList("specific"),parameter(q,"paramValues","all"));s.send("first");
        });assertEquals("first",send(first,"GET","/specific").text());
    }
    @Test public void regexMountBoundarySearchAndNumericMerge() { // FR-ROUTE-019 AC-2 AC-3; FR-ROUTE-020 AC-2 AC-3
        DefaultRestApp app=new DefaultRestApp();Object child=router(true);
        on(child,"GET",Pattern.compile("^/(\\w+)/(\\w+)$"),(q,s,n)->{
            assertEquals("one",q.param("0"));assertEquals("two",q.param("1"));assertEquals("three",q.param("2"));
            assertEquals("/p/one",view(q,"baseUrl"));s.send("merged");
        });mount(app,Pattern.compile("^/p/(\\w+)"),child);
        assertEquals("merged",send(app,"GET","/p/one/two/three").text());
        DefaultRestApp search=new DefaultRestApp();Object never=router(false);
        on(never,"GET","/leaf",(q,s,n)->fail("nonprefix search accepted as mount"));mount(search,Pattern.compile("/api"),never);
        search.get("/x/api/leaf",(q,s)->s.send("outside"));assertEquals("outside",send(search,"GET","/x/api/leaf").text());
    }
    @Test public void advancedCompiledRegexPathInputIsAccepted() throws Exception { // FR-ROUTE-019 AC-6
        Class<?> type=Class.forName("com.snoworca.naruserve.routing.RegexPathPattern");
        Object path=type.getMethod("compile",String.class,int.class).invoke(null,"^/r/(?<id>.*)$",Pattern.CASE_INSENSITIVE);
        DefaultRestApp app=new DefaultRestApp();on(app,"GET",path,(q,s,n)->s.send(q.param("id")));
        assertEquals("a/b",send(app,"GET","/R/a%2Fb").text());
    }
}
