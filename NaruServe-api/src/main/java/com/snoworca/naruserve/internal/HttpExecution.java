package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Per-request cursors and execution ownership. User callbacks and IO run outside the state monitor. */
// @req FR-APP-022 AC-1 AC-2 AC-3 AC-5; FR-APP-023 AC-1 AC-3 AC-4 AC-5
final class HttpExecution {
    private final DefaultRequest request;
    private final DefaultResponse response;
    private final AppLifecycle lifecycle;
    private final ErrorPath errors;
    private final Runnable finalHandler,terminalLog;
    private final Object state=new Object();
    private final Deque<Runnable> queued=new ArrayDeque<>();
    private @Nullable Thread owner;
    private boolean terminal,cleaned,cancelled;
    private int depth;
    private final Set<Object> leases=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
    private final RouterCursor root;

    HttpExecution(HttpRouter router,DefaultRequest request,DefaultResponse response,AppLifecycle lifecycle,
                  ErrorPath errors,Runnable finalHandler,Runnable terminalLog) {
        this.request=request; this.response=response; this.lifecycle=lifecycle; this.errors=errors;
        this.finalHandler=finalHandler; this.terminalLog=terminalLog; this.root=new RouterCursor(router,null,null);
        response.onDone(this::responseDone);
    }
    void start(com.snoworca.naruserve.spi.HttpExchange exchange) {
        if(!lifecycle.registerHttp(this)) { cancel();return; }
        exchange.onAbort(this::cancel);
        submit(()->root.advance(null));
    }
    /** Terminal cancellation revokes leases and queued callbacks; active user code keeps its ownership. */
    void cancel() {
        synchronized(state) { if(cancelled)return;cancelled=true;leases.clear();queued.clear(); }
        try { response.abort(null); } finally { cleanupIfIdle(); }
    }
    private void responseDone() {
        synchronized(state) {
            if(terminal)return;terminal=true;
            if(response.state()==ResponseState.ABORTED){cancelled=true;leases.clear();queued.clear();}
        }
        try { terminalLog.run(); } finally { cleanupIfIdle(); }
    }
    private void cleanupIfIdle() {
        synchronized(state) {
            if(!terminal || owner!=null || !queued.isEmpty() || !leases.isEmpty() || cleaned)return;
            cleaned=true;
        }
        try {
            request.releaseUploads();
            try { request.closeBody(); } catch(Exception failure) { errors.logger.error("request input close failed",failure); }
        } finally { lifecycle.releaseHttp(this); }
    }
    private void submit(Runnable task) {
        boolean nested;
        synchronized(state) {
            if(cancelled || response.state()==ResponseState.ABORTED)return;
            if(owner!=null && (owner!=Thread.currentThread() || depth>=64)) { queued.add(task);return; }
            nested=owner!=null;
            if(!nested)owner=Thread.currentThread();
        }
        if(nested) {
            depth++;
            try { lifecycle.withRequest(task); } finally { depth--; }
            return;
        }
        try {
            Runnable next=task;
            for(;;) {
                lifecycle.withRequest(next);
                synchronized(state) { next=queued.poll(); }
                if(next!=null)continue;
                response.callbackReturned();
                synchronized(state) {
                    next=queued.poll();
                    if(next==null) { owner=null; break; }
                }
            }
        } catch(Error fatal) {
            response.abort(fatal);
            synchronized(state) { queued.clear(); owner=null; }
            throw fatal;
        } finally {
            synchronized(state) { if(owner==Thread.currentThread())owner=null; }
            cleanupIfIdle();
        }
    }
    private void callback(Middleware callback,Next next) {
        try {
            if(callback instanceof AsyncMiddleware) {
                Deferred result=Objects.requireNonNull(((AsyncMiddleware)callback).handler.handle(request,response,next),"async result");
                Object lease=new Object();
                synchronized(state) { if(cancelled)return;leases.add(lease); }
                result.onRejected(next::error);
                result.onSettled(()->{
                    synchronized(state) { leases.remove(lease); }
                    cleanupIfIdle();
                });
            } else callback.handle(request,response,next);
        } catch(Exception failure) { next.error(failure); }
    }
    private void errorCallback(ErrorMiddleware callback,Throwable error,Next next) {
        try { callback.handle(error,request,response,next); }
        catch(Exception failure) { next.error(failure); }
    }
    /** Router completion is deferred like the pinned router's setImmediate(done). */
    private void defer(Runnable task) {
        synchronized(state) {
            if(owner!=null) { queued.add(task);return; }
        }
        submit(task);
    }
    private static final int NORMAL=0,ROUTE=1,ROUTER=2,ERROR=3;
    private static final class Flow {
        final int kind;final @Nullable Throwable error;
        Flow(int kind,@Nullable Throwable error) { this.kind=kind;this.error=error; }
    }
    private static final Flow RUN=new Flow(NORMAL,null),SKIP_ROUTE=new Flow(ROUTE,null),EXIT_ROUTER=new Flow(ROUTER,null);
    private static final class ParamRecord {
        final Object match;
        @Nullable Object value;
        Flow result=RUN;
        ParamRecord(Object match) { this.match=match;this.value=match; }
    }
    private final class RouterCursor {
        final HttpRouter router;
        final @Nullable RouterCursor parent;
        final @Nullable RequestView savedMount;
        final Map<String,Object> inherited;
        final Map<String,ParamRecord> paramCache=new HashMap<>();
        final Set<String> options=new TreeSet<>();
        int index;
        RouterCursor(HttpRouter router,@Nullable RouterCursor parent,@Nullable RequestView savedMount) {
            this.router=router;this.parent=parent;this.savedMount=savedMount;
            inherited=savedMount==null?Collections.emptyMap():savedMount.params;
        }
        void exit(@Nullable Throwable error) {
            defer(()->{
                if(error==null && request.methodToken().equals("OPTIONS") && !options.isEmpty() && response.state()==ResponseState.NEW) {
                    String allow=com.snoworca.naruserve.common.HttpHeaderValues.commaSeparated(options);
                    response.header("Allow",allow).header("Content-Type","text/plain").header("X-Content-Type-Options","nosniff");
                    response.send(allow.getBytes(java.nio.charset.StandardCharsets.UTF_8));return;
                }
                if(parent==null) {
                    if(error==null)finalHandler.run();else FinalHandler.finish(request,response,error,errors.logger);
                }else{
                    request.applyView(Objects.requireNonNull(savedMount));parent.advance(error);
                }
            });
        }
        void advance(@Nullable Throwable error) {
            if(error!=null)request.logFailure(error,errors.logger);
            Throwable pending=error;
            for(;;) {
                HttpRouter.Layer[] layers=router.layers;
                if(index>=layers.length) { exit(pending);return; }
                HttpRouter.Layer layer=layers[index++];HttpPathSpec.Match matched;
                try { matched=layer.path.match(request.path()); }
                catch(Exception failure) { if(pending==null){pending=failure;request.logFailure(failure,errors.logger);}continue; }
                if(matched==null)continue;
                if(pending!=null && layer.error==null || pending==null && layer.error!=null)continue;
                if(layer.route!=null && !layer.route.handles(request.methodToken())) {
                    if(request.methodToken().equals("OPTIONS"))options.addAll(layer.route.methods());
                    if(!request.methodToken().equals("HEAD"))continue;
                }
                // Search regex routes are allowed; a use/mount must still be an actual prefix.
                if(layer.route==null && (matched.start!=0 || matched.end<request.path().length() && request.path().charAt(matched.end)!='/'))continue;
                Map<String,Object> params=router.mergeParams?HttpParameters.merge(matched.params,inherited):matched.params;
                request.applyView(new RequestView(request.path(),request.baseUrl(),params));
                Throwable layerError=pending;
                new Parameters(this,new ArrayList<>(matched.params.keySet()),layerError,()->dispatch(layer,matched,layerError)).advance(RUN);
                return;
            }
        }
        void dispatch(HttpRouter.Layer layer,HttpPathSpec.Match matched,@Nullable Throwable layerError) {
            if(layer.route!=null) { new RouteCursor(this,layer.route).advance(null);return; }
            RequestView saved=request.view();RequestView prefix=null;
            if(!matched.matchedPath.isEmpty()) {
                String rest=saved.path.substring(matched.end);
                if(rest.isEmpty() || rest.charAt(0)!='/')rest="/"+rest;
                String consumed=matched.matchedPath;
                String base=saved.baseUrl+(consumed.endsWith("/")?consumed.substring(0,consumed.length()-1):consumed);
                request.applyView(new RequestView(rest,base,saved.params));prefix=saved;
            }
            if(layer.child!=null) {
                new RouterCursor(layer.child,this,saved).advance(null);return;
            }
            Next next=new Continue(this,null,prefix);
            if(layer.error!=null)errorCallback(layer.error,Objects.requireNonNull(layerError),next);
            else callback(layer.middleware,next);
        }
    }
    /** One layer's ordered preprocessing cursor. Its cache belongs to this router invocation. */
    private final class Parameters {
        final RouterCursor router;
        final List<String> keys;
        final Runnable complete;
        final @Nullable Throwable layerError;
        int keyIndex,hookIndex;
        String name="";
        @Nullable ParamHandler[] hooks;
        @Nullable ParamRecord record;
        Parameters(RouterCursor router,List<String> keys,@Nullable Throwable layerError,Runnable complete) {
            this.router=router;this.keys=keys;this.layerError=layerError;this.complete=complete;
        }
        void restore(@Nullable Object value) {
            if(value instanceof List<?>)request.paramValues(name,HttpParameters.values(value));
            else request.param(name,(String)value);
        }
        void advance(Flow flow) {
            if(record!=null) {
                record.value=request.pathParams().get(name);
                if(flow.kind!=NORMAL)record.result=flow;
            }
            if(flow.kind!=NORMAL) { finish(flow);return; }
            for(;;) {
                if(hooks!=null && hookIndex<hooks.length) {
                    ParamHandler hook=hooks[hookIndex++];ParamRecord active=Objects.requireNonNull(record);
                    Next next=new Next() {
                        public void run() { submit(()->advance(RUN)); }
                        public void route() { submit(()->advance(SKIP_ROUTE)); }
                        public void router() { submit(()->advance(EXIT_ROUTER)); }
                        public void error(Throwable failure) {
                            Objects.requireNonNull(failure,"error");submit(()->advance(new Flow(ERROR,failure)));
                        }
                    };
                    try { hook.handle(request,response,next,HttpParameters.values(active.match),name); }
                    catch(Exception failure) { next.error(failure); }
                    return;
                }
                record=null;hooks=null;
                if(keyIndex>=keys.size()) { complete.run();return; }
                name=keys.get(keyIndex++);Object value=request.pathParams().get(name);
                ParamHandler[] callbacks=router.router.paramHooks.get(name);
                if(value==null || callbacks==null)continue;
                ParamRecord prior=router.paramCache.get(name);
                boolean same=prior!=null && (value instanceof String?value.equals(prior.match):value==prior.match);
                if(prior!=null && (same || prior.result.kind!=NORMAL && prior.result.kind!=ROUTE)) {
                    restore(prior.value);
                    if(prior.result.kind!=NORMAL) { finish(prior.result);return; }
                    continue;
                }
                record=new ParamRecord(value);router.paramCache.put(name,record);
                hooks=callbacks;hookIndex=0;
            }
        }
        void finish(Flow flow) {
            if(layerError!=null)router.advance(layerError);
            else if(flow.kind==ROUTER)router.exit(null);
            else router.advance(flow.error);
        }
    }
    private final class RouteCursor {
        final RouterCursor parent;
        final HttpRouter.Route route;
        final String effectiveMethod;
        int index;
        RouteCursor(RouterCursor parent,HttpRouter.Route route) {
            this.parent=parent;this.route=route;
            effectiveMethod=request.methodToken().equals("HEAD") && !route.has("HEAD")?"GET":request.methodToken();
        }
        void advance(@Nullable Throwable error) {
            if(error!=null) { parent.advance(error);return; }
            HttpRouter.Handler[] handlers=route.handlers;
            while(index<handlers.length) {
                HttpRouter.Handler handler=handlers[index++];
                if(!handler.method.isEmpty() && !handler.method.equals("*") && !handler.method.equals(effectiveMethod))continue;
                try { request.decodeQuery(); }
                catch(Exception failure) { parent.advance(failure);return; }
                callback(handler.callback,new Continue(parent,this,null));return;
            }
            parent.advance(null);
        }
    }
    private final class Continue implements Next {
        final RouterCursor router;
        final @Nullable RouteCursor route;
        final @Nullable RequestView prefix;
        Continue(RouterCursor router,@Nullable RouteCursor route,@Nullable RequestView prefix) {
            this.router=router;this.route=route;this.prefix=prefix;
        }
        void restorePrefix() {
            if(prefix!=null)request.applyView(new RequestView(prefix.path,prefix.baseUrl,request.pathParams()));
        }
        @Override public void run() { submit(()->{restorePrefix();if(route==null)router.advance(null);else route.advance(null);}); }
        @Override public void error(Throwable error) {
            Objects.requireNonNull(error,"error");submit(()->{restorePrefix();if(route==null)router.advance(error);else route.advance(error);});
        }
        @Override public void route() { submit(()->{restorePrefix();router.advance(null);}); }
        @Override public void router() { submit(()->{restorePrefix();router.exit(null);}); }
    }
}
