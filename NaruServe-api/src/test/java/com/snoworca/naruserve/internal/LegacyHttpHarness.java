package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import java.util.*;

/**
 * Explicit retained pre-0.10 dispatcher profile for component tests of ChainNext/ErrorPath.
 * Public DefaultRestApp tests use the new common HTTP stack. This fixture never selects that stack.
 */
// @req CON-APP-010 AC-1 AC-3 AC-5
final class LegacyHttpHarness {
    private final com.snoworca.naruserve.routing.Router<RouteHandler> router=
            new com.snoworca.naruserve.routing.Router<>(RouterPolicy.strict());
    private final MiddlewarePipeline middleware=new MiddlewarePipeline(RouterPolicy.strict());
    private final List<ErrorHandler> errors=new ArrayList<>();
    private final InMemoryHttpAdapter adapter=new InMemoryHttpAdapter();
    private Logger logger=new ConsoleLogger();
    private boolean started;
    static LegacyHttpHarness create(){return new LegacyHttpHarness();}
    LegacyHttpHarness app(){return this;}
    LegacyHttpHarness use(Middleware callback){middleware.addGlobal(callback);return this;}
    LegacyHttpHarness use(String prefix,Middleware callback){middleware.addPrefix(prefix,callback);return this;}
    LegacyHttpHarness get(String path,RouteHandler callback){router.add(Method.GET,path,callback);return this;}
    LegacyHttpHarness get(String path,Middleware route,RouteHandler callback){
        router.add(Method.GET,path,new MiddlewarePipeline.RouteChain(Collections.singletonList(route),callback));return this;
    }
    LegacyHttpHarness webSocketRoute(String path){router.addWebSocket(path,(q,s)->{throw new AssertionError("legacy WS hook invoked as HTTP");});return this;}
    LegacyHttpHarness onError(ErrorHandler callback){errors.add(callback);return this;}
    LegacyHttpHarness logger(Logger logger){this.logger=logger;return this;}
    InMemoryHttpAdapter.ClientResponse send(String token,String target,String... headers){
        if(!started){
            router.freeze();
            adapter.bind("127.0.0.1",0,new Dispatcher(router,middleware,new ErrorPath(errors,new SafeLogger(logger)),new AccessLog()));
            started=true;
        }
        InMemoryHttpAdapter.ClientRequest request=InMemoryHttpAdapter.ClientRequest.of(token,target);
        for(int i=0;i<headers.length;i+=2)request.header(headers[i],headers[i+1]);
        return adapter.send(request);
    }
}
