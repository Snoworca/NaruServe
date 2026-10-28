package com.snoworca.naruserve;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A first-settlement-wins asynchronous result; resolving never implicitly continues or ends a response.
 * Returning a pending result from {@link AsyncRouteHandler} retains that request's resources until settlement
 * or cancellation. Settle after the producer's last resource access. Observer failures are isolated.
 */
// @req FR-APP-023 AC-1 AC-2 AC-3 AC-7
public final class Deferred {
    @FunctionalInterface public interface FailureHandler { void rejected(Throwable error); }
    private final Object lock=new Object();
    private final List<FailureHandler> rejected=new ArrayList<>();
    private final List<Runnable> settled=new ArrayList<>();
    private boolean done,notifyingFailures;
    private @Nullable Throwable error;
    public Deferred() {}
    public static Deferred pending() { return new Deferred(); }
    public static Deferred resolved() { Deferred result=new Deferred();result.resolve();return result; }
    public static Deferred rejected(Throwable error) { Deferred result=new Deferred();result.reject(error);return result; }
    public boolean resolve() { return complete(null); }
    public boolean reject(Throwable error) { return complete(Objects.requireNonNull(error,"error")); }
    public boolean isDone() { synchronized(lock) { return done; } }
    /** Notify on rejection, preserving the original error; a late registration is notified immediately. */
    public void onRejected(FailureHandler listener) {
        Objects.requireNonNull(listener,"listener");Throwable failure;
        synchronized(lock) { if(!done){rejected.add(listener);return;}failure=error; }
        if(failure!=null)notifyFailure(listener,failure);
    }
    /**
     * Observe either first outcome once, outside internal locks. Observers registered before rejection run
     * after the rejection observers captured at settlement. Registration during that notification phase joins
     * its settlement queue; later registrations run immediately. A late rejection observer may run concurrently.
     */
    public void onSettled(Runnable listener) {
        Objects.requireNonNull(listener,"listener");
        synchronized(lock) { if(!done || notifyingFailures){settled.add(listener);return;} }
        notifySettled(listener);
    }
    private boolean complete(@Nullable Throwable failure) {
        List<FailureHandler> failures;List<Runnable> notifications;
        synchronized(lock) {
            if(done)return false;
            error=failure;done=true;notifyingFailures=failure!=null;failures=new ArrayList<>(rejected);
            rejected.clear();
        }
        if(failure!=null)for(FailureHandler listener:failures)notifyFailure(listener,failure);
        synchronized(lock) { notifyingFailures=false;notifications=new ArrayList<>(settled);settled.clear(); }
        for(Runnable listener:notifications)notifySettled(listener);
        return true;
    }
    private static void notifyFailure(FailureHandler listener,Throwable failure) {
        try{listener.rejected(failure);}catch(Throwable ignored){ /* observers cannot prevent release */ }
    }
    private static void notifySettled(Runnable listener) {
        try{listener.run();}catch(Throwable ignored){ /* independent cleanup observers still run */ }
    }
}
