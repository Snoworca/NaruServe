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
 * Global and prefix middleware of one app, filled before freeze and read-only after. Every request runs global
 * and matching prefix middleware in their combined registration order, then the route middleware and terminal
 * step, through one {@link ChainNext} chain on the calling thread.
 */
// @req FR-APP-003 FR-DX-008
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
    private final List<Registration> registrations = new ArrayList<>();

    private static final class Registration {
        final PrefixMatcher prefix;
        final Middleware middleware;

        Registration(PrefixMatcher prefix, Middleware middleware) {
            this.prefix = prefix;
            this.middleware = middleware;
        }
    }

    /** {@code policy} is the app's, so prefixes compare like the router's patterns. */
    // @req FR-APP-003
    public MiddlewarePipeline(RouterPolicy policy) {
        this.policy = policy;
    }

    // @req FR-APP-003
    void addGlobal(Middleware mw) {
        registrations.add(new Registration(null, mw));
    }

    // @req FR-APP-003
    void addPrefix(String prefix, Middleware mw) {
        registrations.add(new Registration(new PrefixMatcher(prefix, policy), mw));
    }

    /**
     * Common HTTP prefixes have their own raw grammar. Only prefixes representable by the strict WS decoded
     * profile join its retained authentication pipeline: an undecodable prefix cannot match a valid WS target.
     * Existing representable prefixes retain the identical matcher and registration order.
     */
    // @req FR-HTTP-027 AC-2; FR-APP-021 AC-6; CON-APP-010 AC-1 AC-2
    void addCommonPrefix(String prefix,Middleware middleware) {
        PrefixMatcher matcher;
        try { matcher=new PrefixMatcher(prefix,policy); }
        catch(IllegalArgumentException unrepresentableDecodedPrefix) { return; }
        registrations.add(new Registration(matcher,middleware));
    }

    /**
     * Runs globals and matching prefixes in registration order, {@code route}, then {@code terminal}. An exception
     * propagates unchanged unless next.error(e) already routed this request. Every continuation of this request
     * throws once this returns.
     */
    // @req FR-APP-003
    void run(DefaultRequest req, DefaultResponse res, List<Middleware> route, RouteHandler terminal,
            ErrorPath errors, String requestId) {
        List<Middleware> steps = new ArrayList<>(registrations.size() + route.size());
        List<String> segments = null;
        for (Registration registration : registrations) {
            if (registration.prefix == null) {
                steps.add(registration.middleware);
            } else {
                if (segments == null) {
                    segments = Router.decodeSegments(req.rawPath(), policy);
                }
                if (registration.prefix.matches(segments)) {
                    steps.add(registration.middleware);
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
            // Still no second error path, but a different exception must not vanish without a trace.
            if (e != chain.routedError) {
                errors.logger.error("request " + requestId + ": exception after next.error(e) dropped", e);
            }
        } finally {
            root.end();
        }
    }
}
