package com.snoworca.naruserve;

/** Shared common HTTP registration surface. Paths may be strings or ordered nested arrays/lists. */
// @req FR-APP-021 AC-1 AC-2 AC-3; FR-ROUTE-019 AC-1 AC-5; FR-HTTP-028 AC-1
public interface HttpRoutes<T> {
    RouteBuilder route(Object path);
    T method(String methodToken,Object path,Middleware... callbacks);
    // @req FR-ROUTE-020 AC-1; FR-ROUTE-021 AC-1 AC-5
    default T use(Object path,Middleware callback) { throw new UnsupportedOperationException("prefix middleware"); }
    default T use(Middleware callback) { return use("",callback); }
    default T use(Object path,Router child) { throw new UnsupportedOperationException("mounted router"); }
    default T use(Router child) { return use("",child); }
    // @req FR-APP-024 AC-1 AC-2
    default T use(Object path,ErrorMiddleware callback) { throw new UnsupportedOperationException("error middleware"); }
    default T use(ErrorMiddleware callback) { return use("",callback); }
    default T param(String name,ParamHandler callback) { throw new UnsupportedOperationException("parameter callback"); }

    default T get(Object path,Middleware... callbacks) { return method("GET",path,callbacks); }
    default T post(Object path,Middleware... callbacks) { return method("POST",path,callbacks); }
    default T put(Object path,Middleware... callbacks) { return method("PUT",path,callbacks); }
    default T patch(Object path,Middleware... callbacks) { return method("PATCH",path,callbacks); }
    default T delete(Object path,Middleware... callbacks) { return method("DELETE",path,callbacks); }
    default T head(Object path,Middleware... callbacks) { return method("HEAD",path,callbacks); }
    default T options(Object path,Middleware... callbacks) { return method("OPTIONS",path,callbacks); }
    default T get(Object path,RouteHandler callback) { return method("GET",path,terminal(callback)); }
    default T post(Object path,RouteHandler callback) { return method("POST",path,terminal(callback)); }
    default T put(Object path,RouteHandler callback) { return method("PUT",path,terminal(callback)); }
    default T patch(Object path,RouteHandler callback) { return method("PATCH",path,terminal(callback)); }
    default T delete(Object path,RouteHandler callback) { return method("DELETE",path,terminal(callback)); }
    default T head(Object path,RouteHandler callback) { return method("HEAD",path,terminal(callback)); }
    default T options(Object path,RouteHandler callback) { return method("OPTIONS",path,terminal(callback)); }
    static Middleware terminal(RouteHandler callback) {
        java.util.Objects.requireNonNull(callback,"callback");
        return (request,response,next)->callback.handle(request,response);
    }
}
