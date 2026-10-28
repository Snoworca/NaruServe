package com.snoworca.naruserve;

import com.snoworca.naruserve.internal.DefaultRouter;

/** Independently ordered, live HTTP routes mountable on apps or other routers. */
// @req FR-ROUTE-020 AC-1 AC-3 AC-5
public interface Router extends HttpRoutes<Router> {
    static Router create() { return create(false); }
    static Router create(boolean mergeParams) { return create(mergeParams,false,false); }
    /** Options are local to this router; common defaults are case-insensitive and non-strict. */
    static Router create(boolean mergeParams,boolean caseSensitive,boolean strict) {
        return new DefaultRouter(mergeParams,caseSensitive,strict);
    }
}
