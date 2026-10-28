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
    private boolean terminal,cleaned;
    private final RouterCursor root;

    HttpExecution(HttpRouter router,DefaultRequest request,DefaultResponse response,AppLifecycle lifecycle,
                  ErrorPath errors,Runnable finalHandler,Runnable terminalLog) {
        this.request=request; this.response=response; this.lifecycle=lifecycle; this.errors=errors;
        this.finalHandler=finalHandler; this.terminalLog=terminalLog; this.root=new RouterCursor(router);
        response.onDone(this::responseDone);
    }
    void start() { submit(()->root.advance(null)); }
    private void responseDone() {
        synchronized(state) { if(terminal)return; terminal=true; }
        try { terminalLog.run(); } finally { cleanupIfIdle(); }
    }
    private void cleanupIfIdle() {
        synchronized(state) {
            if(!terminal || owner!=null || !queued.isEmpty() || cleaned)return;
            cleaned=true;
        }
        request.releaseUploads();
    }
    private void submit(Runnable task) {
        boolean nested;
        synchronized(state) {
            if(response.state()==ResponseState.ABORTED)return;
            if(owner!=null && owner!=Thread.currentThread()) { queued.add(task);return; }
            nested=owner!=null;
            if(!nested)owner=Thread.currentThread();
        }
        if(nested) { lifecycle.withRequest(task);return; }
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
        try { callback.handle(request,response,next); }
        catch(Exception failure) { next.error(failure); }
    }
    private final class RouterCursor {
        final HttpRouter router;
        int index;
        RouterCursor(HttpRouter router) { this.router=router; }
        void advance(@Nullable Throwable error) {
            if(error!=null) { errors.handle(error,request,response,request.requestId());return; }
            for(;;) {
                HttpRouter.Layer[] layers=router.layers;
                if(index>=layers.length) { finalHandler.run();return; }
                HttpRouter.Layer layer=layers[index++];
                HttpPathSpec.Match matched;
                try { matched=layer.path.match(request.path()); }
                catch(Exception failure) { advance(failure); return; }
                if(matched==null)continue;
                if(layer.route!=null && !layer.route.handles(request.methodToken()))continue;
                request.applyView(new RequestView(request.path(),request.baseUrl(),matched.params));
                if(layer.route!=null) { new RouteCursor(this,layer.route).advance(null);return; }
                callback(layer.middleware,new Continue(this,null));return;
            }
        }
    }
    private final class RouteCursor {
        final RouterCursor parent;
        final HttpRouter.Route route;
        int index;
        RouteCursor(RouterCursor parent,HttpRouter.Route route) { this.parent=parent;this.route=route; }
        void advance(@Nullable Throwable error) {
            if(error!=null) { parent.advance(error);return; }
            HttpRouter.Handler[] handlers=route.handlers;
            while(index<handlers.length) {
                HttpRouter.Handler handler=handlers[index++];
                if(!handler.method.isEmpty() && !handler.method.equals("*") && !handler.method.equals(request.methodToken()))continue;
                try { request.decodeQuery(); }
                catch(Exception failure) { parent.advance(failure);return; }
                callback(handler.callback,new Continue(parent,this));return;
            }
            parent.advance(null);
        }
    }
    private final class Continue implements Next {
        final RouterCursor router;
        final @Nullable RouteCursor route;
        Continue(RouterCursor router,@Nullable RouteCursor route) { this.router=router;this.route=route; }
        @Override public void run() { submit(()->{if(route==null)router.advance(null);else route.advance(null);}); }
        @Override public void error(Throwable error) {
            Objects.requireNonNull(error,"error");submit(()->{if(route==null)router.advance(error);else route.advance(error);});
        }
        @Override public void route() { submit(()->router.advance(null)); }
        @Override public void router() { submit(finalHandler); }
    }
}
