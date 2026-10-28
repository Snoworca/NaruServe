package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import com.snoworca.naruserve.testing.*;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static com.snoworca.naruserve.internal.HttpExecutionContractTest.*;
import static com.snoworca.naruserve.internal.HttpMountAndParamContractTest.*;

/** Ordered common HTTP methods/errors; legacy component JSON/propagation tests remain separate. */
public class HttpMethodAndErrorContractTest {
    interface ErrorAction { void run(Throwable error,Request request,Response response,Next next) throws Exception; }
    static void error(Object target,Object prefix,ErrorAction action) {
        try {
            Class<?> type=Class.forName("com.snoworca.naruserve.ErrorMiddleware");
            Object callback=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,args)->{
                action.run((Throwable)args[0],(Request)args[1],(Response)args[2],(Next)args[3]);return null;
            });
            invoke(target,"use",new Class<?>[]{Object.class,type},prefix,callback);
        }catch(ClassNotFoundException e){throw new AssertionError("required ordered error middleware API",e);}
    }
    static void error(Object target,ErrorAction action){error(target,"",action);}
    static final String NOT_FOUND="<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>Error</title>\n</head>\n<body>\n<pre>Cannot GET /missing</pre>\n</body>\n</html>\n";

    @Test public void extendedMethodsAreRegisteredAndObservedWithoutInventingLegacyEnumValues() { // FR-HTTP-028 AC-1
        for(String token:Arrays.asList("COPY","TRACE","CONNECT","M-SEARCH")) {
            DefaultRestApp app=new DefaultRestApp();on(app,token,"/x",(q,s,n)->{
                assertEquals(token,view(q,"methodToken"));
                if(token.equals("COPY")||token.equals("M-SEARCH")) {
                    try{q.method();fail("unrepresentable method silently mapped");}catch(IllegalStateException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("methodToken"));}
                }else assertEquals(Method.valueOf(token),q.method());
                s.send(token);
            });
            assertEquals(token,text(dispatch(app,token,"/x")));
        }
    }
    @Test public void requestMethodTokenComparisonIsCaseSensitiveAndUnknownFallsThrough() { // FR-HTTP-028 AC-1 AC-5
        DefaultRestApp app=new DefaultRestApp();on(app,"get","/x",(q,s,n)->s.send("get"));
        assertEquals("get",text(dispatch(app,"GET","/x")));
        assertEquals(404,dispatch(app,"get","/x").status);
        assertEquals(404,dispatch(app,"BREW","/x").status);
    }
    @Test public void legacyAnySetDoesNotSuppressExplicitTraceOrExpandToCopy() { // FR-HTTP-028 AC-1 AC-6
        DefaultRestApp app=new DefaultRestApp();app.route(Method.any(),"/x",(q,s)->s.send("any"));
        on(app,"TRACE","/x",(q,s,n)->s.send("trace"));
        assertEquals("any",text(dispatch(app,"POST","/x")));assertEquals("trace",text(dispatch(app,"TRACE","/x")));
        assertEquals(404,dispatch(app,"COPY","/x").status);
        assertEquals(7,Method.any().size());
    }
    @Test public void extendedMethodWithCorsUsesWireTokenWithoutLegacyEnumFailure() { // FR-HTTP-028 AC-1 AC-6
        DefaultRestApp app=new DefaultRestApp();app.cors(o->o.allowedOrigins("*"));
        on(app,"COPY","/x",(q,s,n)->s.send("copy"));app.freeze();
        RecordingExchange ex=new RecordingExchange("COPY","/x","Origin","https://example.com");
        app.dispatcher().handle(ex);assertEquals(200,ex.status);assertEquals("copy",text(ex));
        assertEquals(Collections.singletonList("*"),ex.committed("Access-Control-Allow-Origin"));
    }
    @Test public void extendedMethodFileAndStaticHelpersRetainTheirExplicitSemantics() throws Exception { // FR-HTTP-028 AC-1 AC-5; CON-APP-010 AC-4
        java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("http-method-file-");
        java.nio.file.Path file=directory.resolve("a.txt");
        java.nio.file.Files.write(file,"file".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            DefaultRestApp app=new DefaultRestApp();app.staticFiles("/files",directory.toFile());
            on(app,"COPY","/download",(q,s,n)->s.file(file.toFile()));
            assertEquals("file",send(app,"COPY","/download").text());
            assertEquals(404,send(app,"COPY","/outside").status());
            // Static-files is an identified NaruServe extension: non-GET file access retains its explicit405.
            InMemoryHttpAdapter.ClientResponse staticMethod=send(app,"COPY","/files/a.txt");
            assertEquals(405,staticMethod.status());assertEquals("GET, HEAD",staticMethod.header("Allow"));
        }finally{java.nio.file.Files.deleteIfExists(file);java.nio.file.Files.deleteIfExists(directory);}
    }
    @Test public void headFallbackIsLocalToEarlierRouteNotGlobalLaterHeadPreference() { // FR-HTTP-028 AC-3
        DefaultRestApp app=new DefaultRestApp();List<String> trace=new ArrayList<>();
        app.get("/x",(q,s)->{assertEquals(Method.HEAD,q.method());trace.add("get");s.header("X-Route","get").send("hello");});
        app.head("/x",(q,s)->{trace.add("later-head");s.header("X-Route","head").send("later");});
        RecordingExchange ex=dispatch(app,"HEAD","/x");
        assertEquals(200,ex.status);assertEquals(Collections.singletonList("get"),ex.committed("X-Route"));
        assertEquals(Collections.singletonList("5"),ex.committed("Content-Length"));assertEquals(0,ex.body.size());assertEquals(Collections.singletonList("get"),trace);
    }
    @Test public void explicitHeadOnSameRouteSuppressesItsGetCallbacks() { // FR-HTTP-028 AC-3
        DefaultRestApp app=new DefaultRestApp();RouteBuilder builder=app.route("/x");
        builder.get((q,s)->fail("GET callback used despite same-Route HEAD"));
        builder.head((q,s)->s.status(202).header("X-Route","head").send("body"));
        RecordingExchange ex=dispatch(app,"HEAD","/x");assertEquals(202,ex.status);assertEquals(0,ex.body.size());assertEquals(Collections.singletonList("4"),ex.committed("Content-Length"));
    }
    @Test public void optionsAccumulatesOnlyMatchingRoutesWithAlphabeticalDedupAndPlainBody() { // FR-HTTP-028 AC-4
        DefaultRestApp app=new DefaultRestApp();
        app.post("/x",(q,s)->fail());app.get("/x",(q,s)->fail());app.get("/x",(q,s)->fail());
        on(app,"COPY","/x",(q,s,n)->fail());app.put("/other",(q,s)->fail());
        RecordingExchange ex=dispatch(app,"OPTIONS","/x");String allow="COPY, GET, HEAD, POST";
        assertEquals(200,ex.status);assertEquals(Collections.singletonList(allow),ex.committed("Allow"));assertEquals(allow,text(ex));
        // Pinned router sendOptionsResponse writes exactly text/plain, without a charset parameter.
        assertEquals(Collections.singletonList("text/plain"),ex.committed("Content-Type"));
        assertEquals(Collections.singletonList("nosniff"),ex.committed("X-Content-Type-Options"));
        assertEquals(Collections.singletonList(Integer.toString(allow.length())),ex.committed("Content-Length"));
    }
    @Test public void optionsStayRouterLocalAndOnlyIncludeActuallyTraversedRouteLayers() { // FR-HTTP-028 AC-4
        for(boolean exit:Arrays.asList(false,true)) {
            DefaultRestApp app=new DefaultRestApp();Object child=router(false);List<String> trace=new ArrayList<>();
            Middleware first=(q,s,n)->{trace.add("child-use");if(exit)signal(n,"router");else n.run();};
            invoke(child,"use",new Class<?>[]{Middleware.class},first);
            on(child,"GET","/x",(q,s,n)->fail("OPTIONS ran child GET callback"));
            Middleware after=(q,s,n)->{trace.add("child-after");n.run();};
            invoke(child,"use",new Class<?>[]{Middleware.class},after);
            mount(app,"/api",child);app.post("/api/x",(q,s)->fail("OPTIONS ran parent POST callback"));
            app.use((q,s,n)->{trace.add("parent-use");n.run();});
            RecordingExchange ex=dispatch(app,"OPTIONS","/api/x");String expected=exit?"POST":"GET, HEAD";
            assertEquals(200,ex.status);assertEquals(expected,text(ex));assertEquals(Collections.singletonList(expected),ex.committed("Allow"));
            assertEquals(exit?Arrays.asList("child-use","parent-use"):Arrays.asList("child-use","child-after"),trace);
        }
    }
    @Test public void downstreamMiddlewareCanAnswerOptionsBeforeAutomaticResponse() { // FR-HTTP-028 AC-4
        DefaultRestApp app=new DefaultRestApp();app.get("/x",(q,s)->fail());
        app.use((q,s,n)->{assertEquals(Method.OPTIONS,q.method());s.status(203).send("intercept");});
        RecordingExchange ex=dispatch(app,"OPTIONS","/x");assertEquals(203,ex.status);assertEquals("intercept",text(ex));assertEquals(Collections.emptyList(),ex.committed("Allow"));
    }
    @Test public void explicitOptionsContinuationDoesNotInventAnOptionsMethodInAutomaticAllow() { // FR-HTTP-028 AC-4
        DefaultRestApp app=new DefaultRestApp();List<String> trace=new ArrayList<>();
        on(app,"OPTIONS","/x",(q,s,n)->{trace.add("explicit");n.run();});app.get("/x",(q,s)->fail());
        RecordingExchange ex=dispatch(app,"OPTIONS","/x");assertEquals("GET, HEAD",text(ex));assertEquals(Collections.singletonList("explicit"),trace);
    }
    @Test public void defaultNoRouteAndMethodMismatchUseCommonHtmlFinalhandler() { // FR-HTTP-028 AC-5; FR-APP-024 AC-3
        DefaultRestApp app=new DefaultRestApp();app.post("/x",(q,s)->fail());
        RecordingExchange missing=dispatch(app,"GET","/missing");assertEquals(404,missing.status);assertEquals(NOT_FOUND,text(missing));
        assertEquals("text/html; charset=utf-8",missing.committed("Content-Type").get(0).toLowerCase(Locale.ROOT));
        assertEquals(Collections.singletonList("default-src 'none'"),missing.committed("Content-Security-Policy"));
        assertEquals(Collections.singletonList("nosniff"),missing.committed("X-Content-Type-Options"));
        assertEquals(Collections.singletonList(Integer.toString(NOT_FOUND.length())),missing.committed("Content-Length"));
        RecordingExchange mismatch=dispatch(app,"GET","/x");assertEquals(404,mismatch.status);assertEquals(Collections.emptyList(),mismatch.committed("Allow"));assertTrue(text(mismatch),text(mismatch).contains("Cannot GET /x"));
    }
    @Test public void unhandledRuntimeExceptionUsesDeclaredJavaInformationInExact500Html() { // FR-APP-024 AC-3
        DefaultRestApp app=new DefaultRestApp();RuntimeException failure=new RuntimeException("boom<>&");
        // A fixed Java stack makes its declared representation deterministic on Java8/newer JVMs.
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("example.Handler","run","Handler.java",7)});
        app.get("/error",(q,s)->{throw failure;});
        String expected=NOT_FOUND.replace("Cannot GET /missing","java.lang.RuntimeException: boom&lt;&gt;&amp;<br>\tat example.Handler.run(Handler.java:7)<br>");
        RecordingExchange ex=dispatch(app,"GET","/error");assertEquals(500,ex.status);assertEquals(expected,text(ex));
        assertEquals(Collections.singletonList(Integer.toString(expected.length())),ex.committed("Content-Length"));
        assertEquals("text/html; charset=utf-8",ex.committed("Content-Type").get(0).toLowerCase(Locale.ROOT));
        assertEquals(Collections.singletonList("default-src 'none'"),ex.committed("Content-Security-Policy"));
        assertEquals(Collections.singletonList("nosniff"),ex.committed("X-Content-Type-Options"));
        assertEquals(1,ex.commits.get());assertEquals(1,ex.finishes.get());
    }
    @Test public void headFinalhandlerSuppressesMissAndErrorBytesWithCorrectRepresentationLengths() { // FR-HTTP-028 AC-3 AC-5; FR-APP-024 AC-3
        DefaultRestApp app=new DefaultRestApp();RuntimeException failure=new RuntimeException("boom");
        failure.setStackTrace(new StackTraceElement[0]);app.get("/error",(q,s)->{throw failure;});
        RecordingExchange getMiss=dispatch(app,"GET","/missing"),headMiss=dispatch(app,"HEAD","/missing");
        assertEquals(404,headMiss.status);assertEquals(0,headMiss.body.size());
        assertEquals(Collections.singletonList(Integer.toString(NOT_FOUND.length())),getMiss.committed("Content-Length"));
        // Express's hypothetical404 HEAD body says Cannot HEAD, adding one byte compared with GET.
        assertEquals(Collections.singletonList(Integer.toString(NOT_FOUND.replace("Cannot GET","Cannot HEAD").length())),headMiss.committed("Content-Length"));
        assertEquals(getMiss.committed("Content-Type"),headMiss.committed("Content-Type"));
        RecordingExchange getError=dispatch(app,"GET","/error"),headError=dispatch(app,"HEAD","/error");
        assertEquals(500,headError.status);assertEquals(0,headError.body.size());
        assertEquals(getError.committed("Content-Length"),headError.committed("Content-Length"));
        assertEquals(getError.committed("Content-Type"),headError.committed("Content-Type"));
        assertEquals(Collections.singletonList("nosniff"),headError.committed("X-Content-Type-Options"));
        assertEquals(1,headMiss.commits.get());assertEquals(1,headMiss.finishes.get());
        assertEquals(1,headError.commits.get());assertEquals(1,headError.finishes.get());
    }
    @Test public void final404UsesOriginalPathWithoutQueryAndEscapesUrlForHtml() { // FR-APP-024 AC-3
        DefaultRestApp app=new DefaultRestApp();
        InMemoryHttpAdapter.ClientResponse res=send(app,"GET","/a&b?q=1");
        assertEquals(404,res.status());assertTrue(res.text(),res.text().contains("Cannot GET /a&amp;b"));assertFalse(res.text(),res.text().contains("?q=1"));
        RecordingExchange encoded=dispatch(app,"GET","/x<y>");
        assertTrue(text(encoded),text(encoded).contains("/x%3Cy%3E"));
    }
    @Test public void thrownAndExplicitErrorsStartAtCorrectDownstreamErrorLayer() { // FR-APP-024 AC-1
        for(boolean thrown:Arrays.asList(true,false)) {
            DefaultRestApp app=new DefaultRestApp();Throwable failure=new IllegalArgumentException("boom");List<String> trace=new ArrayList<>();
            error(app,(e,q,s,n)->fail("earlier error layer restarted"));
            on(app,"GET","/x",(q,s,n)->{trace.add("route");if(thrown)throw (RuntimeException)failure;else n.error(failure);});
            app.use((q,s,n)->fail("normal middleware ran in error mode"));
            error(app,(e,q,s,n)->{assertSame(failure,e);trace.add("error");s.send("handled");});
            assertEquals("handled",text(dispatch(app,"GET","/x")));assertEquals(Arrays.asList("route","error"),trace);
        }
    }
    @Test public void errorNextRunClearsErrorAndPreservesSynchronousAfterNextOrder() { // FR-APP-024 AC-2; FR-APP-022 AC-4
        DefaultRestApp app=new DefaultRestApp();List<String> trace=new ArrayList<>();
        on(app,"GET","/x",(q,s,n)->{trace.add("before");n.error(new IllegalStateException("x"));trace.add("after");});
        error(app,(e,q,s,n)->{trace.add("error");n.run();trace.add("error-after");});
        app.use((q,s,n)->{trace.add("normal");s.send("ok");});
        assertEquals("ok",text(dispatch(app,"GET","/x")));assertEquals(Arrays.asList("before","error","normal","error-after","after"),trace);
    }
    @Test public void replacementAndThrownErrorAdvanceWithoutRestartOrDoubleFinalization() { // FR-APP-024 AC-2 AC-4
        for(boolean thrown:Arrays.asList(true,false)) {
            DefaultRestApp app=new DefaultRestApp();Throwable original=new IllegalStateException("first"),replacement=new IllegalArgumentException("second");AtomicInteger seen=new AtomicInteger();
            on(app,"GET","/x",(q,s,n)->n.error(original));
            error(app,(e,q,s,n)->{assertSame(original,e);if(thrown)throw (RuntimeException)replacement;else n.error(replacement);});
            error(app,(e,q,s,n)->{assertSame(replacement,e);seen.incrementAndGet();s.send("last");});
            RecordingExchange ex=dispatch(app,"GET","/x");assertEquals("last",text(ex));assertEquals(1,seen.get());assertEquals(1,ex.commits.get());assertEquals(1,ex.finishes.get());
        }
    }
    @Test public void prefixedErrorMiddlewareUsesActualTrimmedView() { // FR-APP-024 AC-1 AC-2; FR-HTTP-027 AC-3
        DefaultRestApp app=new DefaultRestApp();on(app,"GET","/api/x",(q,s,n)->n.error(new HttpException(409)));
        error(app,"/elsewhere",(e,q,s,n)->fail());
        error(app,"/api",(e,q,s,n)->{assertEquals("/x",q.path());assertEquals("/api",view(q,"baseUrl"));n.run();});
        app.get("/api/x",(q,s)->{assertEquals("",view(q,"baseUrl"));s.send("resumed");});
        assertEquals("resumed",text(dispatch(app,"GET","/api/x")));
    }
    @Test public void mountedErrorsRestoreParentUrlBeforeDownstreamErrorMiddleware() { // FR-APP-024 AC-1; FR-ROUTE-020 AC-4
        DefaultRestApp app=new DefaultRestApp();Object child=router(false);
        on(child,"GET","/:id",(q,s,n)->n.error(new HttpException(409)));
        mount(app,"/api",child);error(app,(e,q,s,n)->{
            assertEquals("/api/x",q.path());assertEquals("",view(q,"baseUrl"));assertEquals("/api/x",view(q,"originalUrl"));s.send("parent");
        });assertEquals("parent",text(dispatch(app,"GET","/api/x")));
    }
    @Test public void cachedParameterErrorIsReplayedAfterRecoveryWithoutRerunningHook() { // FR-ROUTE-021 AC-2; FR-APP-024 AC-2
        DefaultRestApp app=new DefaultRestApp();AtomicInteger hooks=new AtomicInteger(),errors=new AtomicInteger();
        hook(app,"id",(q,s,n,v,k)->{hooks.incrementAndGet();n.error(new HttpException(409,"param"));});
        on(app,"GET","/:id",(q,s,n)->fail());
        error(app,(e,q,s,n)->{errors.incrementAndGet();n.run();});
        on(app,"GET","/:id",(q,s,n)->fail("cached error was cleared from param result"));
        error(app,(e,q,s,n)->{errors.incrementAndGet();s.send("cached");});
        assertEquals("cached",text(dispatch(app,"GET","/x")));assertEquals(1,hooks.get());assertEquals(2,errors.get());
    }
    @Test public void malformedCaptureRetainsRawValueInErrorInformation() { // FR-HTTP-027 AC-2; FR-APP-024 AC-1 AC-3
        for(String raw:Arrays.asList("%ZZ","%C0%AF")) {
            DefaultRestApp app=new DefaultRestApp();app.get("/x/:id",(q,s)->fail());
            error(app,(e,q,s,n)->{
                assertTrue(e instanceof HttpException);assertEquals(400,((HttpException)e).status());
                assertEquals("Failed to decode param '"+raw+"'",e.getMessage());s.status(400).send("bad path");
            });assertEquals("bad path",text(dispatch(app,"GET","/x/"+raw)));
        }
    }
    @Test public void nullErrorIsRejectedWithoutConsumingContinuation() { // FR-APP-022 AC-6; FR-APP-024 AC-2
        DefaultRestApp app=new DefaultRestApp();on(app,"GET","/x",(q,s,n)->{
            try{n.error(null);fail();}catch(NullPointerException expected){}n.run();
        });app.get("/x",(q,s)->s.send("ok"));assertEquals("ok",text(dispatch(app,"GET","/x")));
    }
    @Test public void explicitOnErrorBridgeKeepsItsRegisteredPositionAndCanPropagate() { // FR-APP-024 AC-5
        DefaultRestApp app=new DefaultRestApp();List<String> trace=new ArrayList<>();
        app.onError((e,q,s)->fail("earlier extension restarted"));
        on(app,"GET","/x",(q,s,n)->n.error(new HttpException(409,"x")));
        app.onError((e,q,s)->trace.add("bridge"));error(app,(e,q,s,n)->{trace.add("ordered");s.send("ok");});
        assertEquals("ok",text(dispatch(app,"GET","/x")));assertEquals(Arrays.asList("bridge","ordered"),trace);
    }
    @Test public void errorAfterSendRunsDownstreamHandlerButCannotCommitAgain() { // FR-APP-024 AC-4
        DefaultRestApp app=new DefaultRestApp();AtomicInteger seen=new AtomicInteger();RecordingLogger log=new RecordingLogger();app.logger(log);
        Throwable failure=new IllegalStateException("late");
        on(app,"GET","/x",(q,s,n)->{s.send("first");n.error(failure);});
        error(app,(e,q,s,n)->{assertTrue(s.isCommitted());seen.incrementAndGet();n.error(e);});
        RecordingExchange ex=dispatch(app,"GET","/x");assertEquals(1,seen.get());assertEquals("first",text(ex));assertEquals(1,ex.commits.get());assertEquals(1,ex.finishes.get());
        int recorded=Collections.frequency(log.errors,failure),aborted=ex.aborts.get();
        assertEquals("late unhandled failure must be observed once by error log or transport abort",1,Math.max(recorded,aborted));
    }
    @Test public void defaultHttpExceptionPreservesStatusHeadersAndEscapesErrorDetails() { // FR-APP-024 AC-3 AC-5
        DefaultRestApp app=new DefaultRestApp();app.get("/x",(q,s)->{throw new HttpException(422,"<bad>&value").withHeader("Retry-After","3");});
        RecordingExchange ex=dispatch(app,"GET","/x");assertEquals(422,ex.status);assertEquals(Collections.singletonList("3"),ex.committed("Retry-After"));
        assertEquals("text/html; charset=utf-8",ex.committed("Content-Type").get(0).toLowerCase(Locale.ROOT));
        assertTrue(text(ex),text(ex).contains("&lt;bad&gt;&amp;value"));assertFalse(text(ex),text(ex).contains("<bad>"));
        assertEquals(Collections.singletonList("nosniff"),ex.committed("X-Content-Type-Options"));
    }
    @Test public void invalidErrorMiddlewareRegistrationDoesNotPublishALayer() throws Exception { // FR-APP-021 AC-3; FR-APP-024 AC-1
        DefaultRestApp app=new DefaultRestApp();Class<?> type=Class.forName("com.snoworca.naruserve.ErrorMiddleware");
        try{invoke(app,"use",new Class<?>[]{Object.class,type},"/x",null);fail();}catch(NullPointerException expected){}
        on(app,"GET","/x",(q,s,n)->n.error(new HttpException(409)));error(app,(e,q,s,n)->s.send("valid"));
        assertEquals("valid",text(dispatch(app,"GET","/x")));
    }
}
