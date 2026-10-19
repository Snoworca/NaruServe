package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Global and prefix middleware of one app, filled before freeze and read-only after. Every request runs the globals
 * in registration order, then the prefix middleware covering its path in registration order, then the route
 * middleware and the terminal step, through one {@link ChainNext} chain on the calling thread.
 */
// @req FR-APP-003
public final class MiddlewarePipeline {

    /** Router payload of a route registered with middleware: that middleware, in order, then the handler. */
    // @req FR-APP-003
    static final class RouteChain implements RouteHandler {
        final List<Middleware> middleware;
        final RouteHandler handler;

        RouteChain(List<Middleware> middleware, RouteHandler handler) {
            this.middleware = Collections.unmodifiableList(new ArrayList<>(middleware));
            this.handler = handler;
        }

        /** Never called: the Dispatcher unpacks a RouteChain into the pipeline. */
        @Override
        public void handle(Request req, Response res) {
            throw new UnsupportedOperationException("a RouteChain runs through MiddlewarePipeline.run");
        }
    }

    private final RouterPolicy policy;
    private final List<Middleware> globals = new ArrayList<>();
    private final List<PrefixMatcher> prefixes = new ArrayList<>();
    private final List<Middleware> prefixMiddleware = new ArrayList<>();

    /** {@code policy} is the app's, so prefixes compare like the router's patterns. */
    // @req FR-APP-003
    public MiddlewarePipeline(RouterPolicy policy) {
        this.policy = policy;
    }

    // @req FR-APP-003
    void addGlobal(Middleware mw) {
        globals.add(mw);
    }

    // @req FR-APP-003
    void addPrefix(String prefix, Middleware mw) {
        prefixes.add(new PrefixMatcher(prefix, policy));
        prefixMiddleware.add(mw);
    }

    /**
     * Runs globals, matching prefixes, {@code route}, then {@code terminal}; an exception of any step propagates
     * unchanged unless next.error(e) already routed this request. Every continuation of this request throws once
     * this returns.
     */
    // @req FR-APP-003
    void run(DefaultRequest req, DefaultResponse res, List<Middleware> route, RouteHandler terminal,
            ErrorPath errors, String requestId) {
        List<Middleware> steps = new ArrayList<>(globals.size() + prefixes.size() + route.size());
        steps.addAll(globals);
        if (!prefixes.isEmpty()) {
            List<String> segments = Router.decodeSegments(req.rawPath(), policy);
            for (int i = 0; i < prefixes.size(); i++) {
                if (prefixes.get(i).matches(segments)) {
                    steps.add(prefixMiddleware.get(i));
                }
            }
        }
        steps.addAll(route);
        ChainNext.Chain chain = new ChainNext.Chain(steps, terminal, req, res, errors, requestId);
        ChainNext root = new ChainNext(chain, 0);
        try {
            root.run();
        } catch (Exception e) {
            // A request already handed to the error path by next.error(e) does not reach it a second time.
            if (!chain.errorRouted) {
                throw e;
            }
        } finally {
            root.end();
        }
    }
}
