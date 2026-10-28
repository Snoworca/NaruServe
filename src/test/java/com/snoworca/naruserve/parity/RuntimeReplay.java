package com.snoworca.naruserve.parity;

import com.google.gson.*;
import com.snoworca.naruserve.*;
import com.snoworca.naruserve.spi.*;
import com.snoworca.naruserve.testing.*;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real Java scenario runner. Reference data selects inputs; no expected outcome is used as an observation. */
final class RuntimeReplay {
    static final class Observation {
        final JsonObject value;
        final Throwable registrationFailure;
        final List<Throwable> errors;
        Observation(JsonObject value, Throwable registrationFailure, List<Throwable> errors) {
            this.value=value;this.registrationFailure=registrationFailure;this.errors=errors;
        }
    }
    private static final long WAIT_SECONDS=5;
    private final TrackingAdapter adapter=new TrackingAdapter();
    private final RestApp app=RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).logger(new RecordingLogger()).build();
    private final JsonArray trace=new JsonArray();
    private final List<Throwable> errors=new ArrayList<>();
    private final AtomicReference<Runnable> delayed=new AtomicReference<>();
    private final ExecutorService client=Executors.newSingleThreadExecutor();
    private final ExecutorService callbacks=Executors.newSingleThreadExecutor();

    static Observation run(JsonObject reference) throws Exception {
        RuntimeReplay runner=new RuntimeReplay();
        try {return runner.capture(reference);} finally {
            runner.app.shutdown(0,TimeUnit.SECONDS);
            runner.callbacks.shutdownNow();runner.client.shutdownNow();
            if(!runner.callbacks.awaitTermination(WAIT_SECONDS,TimeUnit.SECONDS)||!runner.client.awaitTermination(WAIT_SECONDS,TimeUnit.SECONDS))
                throw new AssertionError("Replay-owned worker failed to terminate");
        }
    }
    private Observation capture(JsonObject input) throws Exception {
        String id=input.get("id").getAsString();JsonObject value=new JsonObject(),registration=new JsonObject();
        value.addProperty("id",id);value.add("feature",input.get("feature").deepCopy());
        Throwable failure=null;
        try {setup(id);registration.addProperty("success",true);registration.add("error",JsonNull.INSTANCE);}
        catch(RuntimeException e){failure=e;registration.addProperty("success",false);registration.add("error",error(e));}
        value.add("registration",registration);
        if(failure!=null || input.get("request").isJsonNull()) {
            // Registration-only cases do not listen or manufacture a request, even if registration unexpectedly succeeds.
            value.add("request",JsonNull.INSTANCE);value.add("observation",JsonNull.INSTANCE);
            return new Observation(value,failure,new ArrayList<>(errors));
        }
        JsonObject request=input.getAsJsonObject("request");value.add("request",request.deepCopy());
        app.listen(0);
        Future<ClientResponse> response=client.submit(()->adapter.delegate.send(ClientRequest.of(request.get("method").getAsString(),request.get("rawPath").getAsString())));
        if(!adapter.returned.await(WAIT_SECONDS,TimeUnit.SECONDS))throw new AssertionError(id+": initial exchange callback did not return");
        Runnable continuation=delayed.get();
        if(continuation!=null)callbacks.submit(continuation).get(WAIT_SECONDS,TimeUnit.SECONDS);
        ClientResponse observed=response.get(WAIT_SECONDS+1,TimeUnit.SECONDS);
        JsonObject observation=new JsonObject(),headers=new JsonObject();
        for(String name:new String[]{"allow","content-type","content-length","location","x-handler"})
            headers.addProperty(name,ParityCorpus.canonicalHeader(name,observed.header(name)));
        observation.addProperty("status",observed.status());observation.add("headers",headers);observation.addProperty("body",observed.text());
        // Initial callback and every owned continuation have joined before the final snapshot, including after-send next().
        observation.add("trace",trace.deepCopy());JsonObject completion=new JsonObject();
        completion.addProperty("state",observed.abnormallyClosed()?"aborted":"finished");JsonArray recordedErrors=new JsonArray();
        for(Throwable e:errors)recordedErrors.add(error(e));completion.add("errors",recordedErrors);observation.add("completion",completion);
        value.add("observation",observation);return new Observation(value,null,new ArrayList<>(errors));
    }
    private static JsonObject error(Throwable e) {
        JsonObject json=new JsonObject();json.addProperty("name",e.getClass().getName());json.addProperty("message",e.getMessage());return json;
    }
    private void record(Request q,String handler) {
        JsonObject event=new JsonObject();event.addProperty("handler",handler);
        event.add("params",new Gson().toJsonTree(q.pathParams()));event.addProperty("path",q.path());
        event.addProperty("url",q.url());event.addProperty("baseUrl",q.baseUrl());event.addProperty("originalUrl",q.originalUrl());trace.add(event);
    }
    private void text(Request q,Response s,String handler,String body,int status) {record(q,handler);s.status(status).send(body);}
    private RouteHandler text(String handler,String body){return(q,s)->text(q,s,handler,body,200);}
    private void setup(String id) throws Exception {
        switch(id) {
        case "literal.get":app.get("/a",text("literal","literal"));break;
        case "literal.post":app.post("/a",(RouteHandler)(q,s)->text(q,s,"posted","posted",201));break;
        case "param.basic":app.get("/users/:id",(RouteHandler)(q,s)->text(q,s,"user","id="+q.param("id"),200));break;
        case "optional.absent":case "optional.present":app.get("/users{/:id}",text("optional","ok"));break;
        case "wildcard.list":app.get("/files/*parts",text("wildcard","ok"));break;
        case "param.duplicate":app.get("/:id/:id",text("duplicate","ok"));break;
        case "param.quoted":app.get("/:\"user-id\"",text("quoted","ok"));break;
        case "registration.unnamed-wildcard":app.get("/files/*",(RouteHandler)(q,s)->{});break;
        case "registration.inline-regex":app.get("/x/:id(\\d+)",(RouteHandler)(q,s)->{});break;
        case "array.alternative":app.get(new String[]{"/a/:a","/b/:b"},text("alternative","ok"));break;
        case "order.parameter-before-literal":app.get("/:id",text("parameter","parameter"));app.get("/fixed",text("fixed","fixed"));break;
        case "route.next":case "route.skip":
            app.route("/a").get((Middleware)(q,s,n)->{record(q,"one");if(id.equals("route.skip"))n.route();else n.run();}).get(text("two","two"));
            app.get("/a",text("three","three"));break;
        case "use.route-skip-continues":
            // The Java single-callback use overload maps the Express use callback sequence to adjacent prefix layers.
            app.use("/a",(Middleware)(q,s,n)->{record(q,"use-one");n.route();});
            app.use("/a",(Middleware)(q,s,n)->{record(q,"use-two");n.run();});app.get("/a",text("route","ok"));break;
        case "mount.layer-views":app.use("/api/:version",(Middleware)(q,s,n)->{record(q,"before");n.run();record(q,"after");});app.get("/api/:other/users",text("route","ok"));break;
        case "mount.merge-false":case "mount.merge-true":
            Router child=Router.create(id.equals("mount.merge-true"));child.get("/users/:id",text("child","ok"));app.use("/api/:version",child);break;
        case "router.exit":
            Router router=Router.create();router.get("/a",(Middleware)(q,s,n)->{record(q,"child");n.router();});
            router.get("/a",text("skipped","bad"));app.use(router);app.get("/a",text("parent","ok"));break;
        case "param.callback-cache":app.param("id",(q,s,n,v,name)->{record(q,"param:"+v.get(0));n.run();});
            app.get("/:id",(Middleware)(q,s,n)->{record(q,"one");n.run();});app.get("/:id",text("two","ok"));break;
        case "head.order-false":case "head.order-true":
            RouteHandler head=(q,s)->{s.header("X-Handler","head");text(q,s,"head","head",200);};
            if(id.equals("head.order-true"))app.head("/a",head);
            app.get("/a",(RouteHandler)(q,s)->{s.header("X-Handler","get");text(q,s,"get","GET",200);});
            if(id.equals("head.order-false"))app.head("/a",head);break;
        case "options.automatic":app.post("/a",(RouteHandler)(q,s)->s.end());app.get("/a",(RouteHandler)(q,s)->s.end());app.delete("/a",(RouteHandler)(q,s)->s.end());break;
        case "options.middleware-fallthrough":app.get("/a",(RouteHandler)(q,s)->s.end());app.use((Middleware)(q,s,n)->text(q,s,"fallback","custom",202));break;
        case "error.thrown":app.get("/a",(RouteHandler)(q,s)->{record(q,"throw");throw new RuntimeException("boom");});orderedError("error",false);break;
        case "async.delayed-next":app.get("/a",(Middleware)(q,s,n)->{record(q,"scheduled");delayed.set(()->{record(q,"continued");n.run();});},
                (Middleware)(q,s,n)->text(q,s,"final","ok",200));break;
        case "async.rejected":asyncRejected();orderedError("error",false);break;
        case "continuation.after-response":app.get("/a",(Middleware)(q,s,n)->{text(q,s,"respond","ok",200);n.run();});app.use((Middleware)(q,s,n)->{record(q,"after-response");n.run();});break;
        case "raw.encoded-slash":app.get("/x/:id",text("decoded","ok"));break;
        case "raw.malformed-escape":app.get("/x/:id",text("unexpected","bad"));orderedError("decode-error",true);break;
        default:throw new AssertionError("No actual Java scenario for reference id "+id);
        }
    }
    private void orderedError(String label,boolean decode) throws Exception {
        Class<?> type=required("com.snoworca.naruserve.ErrorMiddleware");
        Object handler=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            if(!method.getName().equals("handle"))throw new AssertionError("Unexpected ErrorMiddleware call "+method);
            Throwable e=(Throwable)args[0];errors.add(e);int status=decode&&e instanceof HttpException?((HttpException)e).status():500;
            text((Request)args[1],(Response)args[2],label,decode?"bad path":e.getMessage(),status);return null;
        });
        invoke(HttpRoutes.class.getMethod("use",type),app,handler);
    }
    private void asyncRejected() throws Exception {
        Class<?> handlerType=required("com.snoworca.naruserve.AsyncRouteHandler"),deferredType=required("com.snoworca.naruserve.Deferred");
        Object handler=Proxy.newProxyInstance(handlerType.getClassLoader(),new Class<?>[]{handlerType},(proxy,method,args)->{
            if(!method.getName().equals("handle"))throw new AssertionError("Unexpected AsyncRouteHandler call "+method);
            record((Request)args[0],"async");Object deferred=invoke(deferredType.getMethod("pending"),null);
            delayed.set(()->{try {invoke(deferredType.getMethod("reject",Throwable.class),deferred,new RuntimeException("rejected"));}
                catch(Exception e){throw new AssertionError("Actual Deferred.reject failed",e);}});return deferred;
        });
        invoke(HttpRoutes.class.getMethod("getAsync",Object.class,handlerType),app,"/a",handler);
    }
    private static Class<?> required(String name) {
        try{return Class.forName(name);}catch(ClassNotFoundException e){throw new AssertionError("Pending actual runtime API "+name,e);}
    }
    private static Object invoke(java.lang.reflect.Method method,Object receiver,Object... args) throws Exception {
        try{return method.invoke(receiver,args);}catch(InvocationTargetException e){
            Throwable cause=e.getCause();if(cause instanceof Exception)throw(Exception)cause;if(cause instanceof Error)throw(Error)cause;throw new AssertionError(cause);
        }
    }
    private static final class TrackingAdapter implements HttpAdapter {
        final InMemoryHttpAdapter delegate=new InMemoryHttpAdapter();final CountDownLatch returned=new CountDownLatch(1);
        private ExchangeHandler track(ExchangeHandler handler){return exchange->{try{handler.handle(exchange);}finally{returned.countDown();}};}
        @Override public void bind(String host,int port,ExchangeHandler handler)throws IOException{delegate.bind(host,port,track(handler));}
        @Override public void bind(ServerSettings settings,ExchangeHandler handler)throws IOException{delegate.bind(settings,track(handler));}
        @Override public int boundPort(){return delegate.boundPort();}
        @Override public void stop(long timeout,TimeUnit unit){delegate.stop(timeout,unit);}
        @Override public void release(){delegate.release();}
    }
}
