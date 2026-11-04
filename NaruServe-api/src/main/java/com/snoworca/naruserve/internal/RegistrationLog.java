package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RegistrationKind;
import com.snoworca.naruserve.RegistrationListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One app's registration list and watchers, and the shared engine that runs every registration as validate, watcher,
 * apply, record. The caller validates and builds the unpublished layer first; {@link #admit} then calls each watcher
 * synchronously on the registering thread, runs the apply only when none threw, and appends the records. Registrations
 * made through a mounted {@link DefaultRouter} reach the watchers of every app that mounts it, with the mount path in
 * front. All bookkeeping of apps and Routers is guarded by {@link #LOCK}.
 */
// @req IR-APP-008
// @req FR-APP-025
// @req FR-APP-026
// @req SEC-APP-002
final class RegistrationLog {

    /** Guards every log's records and every Router's entries and mounts. */
    static final Object LOCK = new Object();

    private static final ThreadLocal<Boolean> WATCHING = new ThreadLocal<>();

    /** Hands out {@link #seq}: the creation order that fixes the monitor order of the apps mounting one Router. */
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong();

    /** A test seam that sees the steps "validate", "apply" and "record" of every registration of this log. */
    @FunctionalInterface
    interface Trace {
        void step(String name);
    }

    /** The app whose monitor guards its registrations; Router registrations take it before {@link #LOCK}. */
    final Object owner;
    private final AppLifecycle lifecycle;
    /** Unique per log and increasing in creation order; {@link #owners} locks the mounting apps in this order. */
    private final long seq = SEQUENCE.incrementAndGet();
    private volatile RegistrationListener[] listeners = new RegistrationListener[0];
    private volatile Registration[] records = new Registration[0];
    private volatile @Nullable Trace trace;

    RegistrationLog(Object owner, AppLifecycle lifecycle) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** A terminated app no longer takes part in the registrations of the Routers it mounted. */
    private boolean terminated() {
        return lifecycle.state() == com.snoworca.naruserve.AppState.TERMINATED;
    }

    /** For tests in this package: observe the registration steps. */
    void trace(@Nullable Trace trace) {
        this.trace = trace;
    }

    private void step(String name) {
        Trace t = trace;
        if (t != null) {
            t.step(name);
        }
    }

    /** Refuses a registration made by a watcher while it decides on another one. */
    // @req SEC-APP-002
    static void requireNotWatching() {
        if (WATCHING.get() != null) {
            throw new IllegalStateException("registering from inside a registration watcher is not allowed");
        }
    }

    /**
     * Refuses a call into another app made by a watcher: the watcher holds {@link #LOCK}, so waiting for that app's
     * monitor could deadlock against a registration there that holds the monitor and waits for {@link #LOCK}.
     */
    // @req SEC-APP-002
    static void requireNotWatchingElsewhere() {
        if (WATCHING.get() != null) {
            throw new IllegalStateException("a registration watcher must not call into another app");
        }
    }

    // @req IR-APP-008
    void addListener(RegistrationListener listener) {
        synchronized (LOCK) {
            RegistrationListener[] copy = Arrays.copyOf(listeners, listeners.length + 1);
            copy[copy.length - 1] = listener;
            listeners = copy;
        }
    }

    /** A call-time snapshot in registration order; iterating it never sees a later registration. */
    // @req IR-APP-008
    List<Registration> snapshot() {
        return Collections.unmodifiableList(Arrays.asList(records));
    }

    /** Each record to each watcher in order; the first exception stops and propagates unchanged. */
    private void watch(List<RegistrationRecord> batch) {
        RegistrationListener[] ls = listeners;
        if (ls.length == 0) {
            return;
        }
        WATCHING.set(Boolean.TRUE);
        try {
            for (RegistrationRecord r : batch) {
                for (RegistrationListener l : ls) {
                    l.onRegistration(r);
                }
            }
        } finally {
            WATCHING.remove();
        }
    }

    private void record(List<RegistrationRecord> batch) {
        Registration[] copy = Arrays.copyOf(records, records.length + batch.size());
        for (int i = 0; i < batch.size(); i++) {
            copy[records.length + i] = batch.get(i);
        }
        records = copy;
        for (RegistrationRecord r : batch) {
            DefaultRouter mounted = r.mounted;
            if (mounted != null) {
                mounted.mounts.add(new Mount(this, r));
            }
        }
    }

    /**
     * An app-level registration: {@code batch} (validated by the caller, which holds the app monitor) is watched, then
     * {@code apply} runs, then the records are appended. A watcher exception leaves nothing applied or recorded.
     */
    // @req SEC-APP-002
    void admit(List<RegistrationRecord> batch, Runnable apply) {
        synchronized (LOCK) {
            step("validate");
            watch(batch);
            apply.run();
            step("apply");
            record(batch);
            step("record");
        }
    }

    /** The records of mounting {@code router} under the app-level ROUTER_MOUNT record {@code mount}. Under LOCK. */
    // @req FR-APP-026
    static List<RegistrationRecord> mountBatch(RegistrationRecord mount, DefaultRouter router) {
        List<RegistrationRecord> out = new ArrayList<>();
        out.add(mount);
        expand(mount, router, out, Collections.newSetFromMap(new IdentityHashMap<DefaultRouter, Boolean>()));
        return out;
    }

    private static void expand(RegistrationRecord mount, DefaultRouter router, List<RegistrationRecord> out,
            Set<DefaultRouter> visiting) {
        if (!visiting.add(router)) {
            throw new IllegalArgumentException("a Router cannot be mounted inside itself");
        }
        for (Entry e : router.entries) {
            expandEntry(mount, e, out, visiting);
        }
        visiting.remove(router);
    }

    private static void expandEntry(RegistrationRecord mount, Entry e, List<RegistrationRecord> out,
            Set<DefaultRouter> visiting) {
        RegistrationRecord r = RegistrationRecord.of(e.kind, e.method, e.pathSource, e.target, mount, e.child);
        out.add(r);
        DefaultRouter child = e.child;
        if (child != null) {
            expand(r, child, out, visiting);
        }
    }

    /** Runs {@code attempt} holding the monitors from {@code i} on, in order. */
    private interface Attempt {
        boolean run();
    }

    private static boolean holding(List<Object> monitors, int i, Attempt attempt) {
        if (i == monitors.size()) {
            return attempt.run();
        }
        synchronized (monitors.get(i)) {
            return holding(monitors, i + 1, attempt);
        }
    }

    /**
     * The distinct live apps that mount {@code router}, in the creation order of their logs (unique, so two Routers
     * mounted in the same apps always lock them in one order); drops terminated ones. Under LOCK.
     */
    static List<Object> owners(DefaultRouter router) {
        List<RegistrationLog> logs = new ArrayList<>();
        for (java.util.Iterator<Mount> it = router.mounts.iterator(); it.hasNext(); ) {
            RegistrationLog log = it.next().log();
            if (log == null || log.terminated()) {
                it.remove();
                continue;
            }
            if (!logs.contains(log)) {
                logs.add(log);
            }
        }
        Collections.sort(logs, new java.util.Comparator<RegistrationLog>() {
            @Override
            public int compare(RegistrationLog a, RegistrationLog b) {
                return Long.compare(a.seq, b.seq);
            }
        });
        List<Object> out = new ArrayList<>(logs.size());
        for (RegistrationLog log : logs) {
            out.add(log.owner);
        }
        return out;
    }

    /**
     * A registration made through {@code router}: for each place the router is mounted, the expanded records go to that
     * app's watchers; only when none threw does {@code apply} run, and then every app records them. The monitors of the
     * mounting apps are taken before {@link #LOCK}, the order an app-level registration uses too, so a watcher that
     * calls its app cannot deadlock against a registration running on that app.
     */
    // @req FR-APP-026
    // @req SEC-APP-002
    static void admitRouter(DefaultRouter router, Entry entry, Runnable apply) {
        requireNotWatching();
        while (true) {
            List<Object> owners;
            synchronized (LOCK) {
                owners = owners(router);
            }
            List<Object> expected = owners;
            if (holding(owners, 0, () -> {
                synchronized (LOCK) {
                    if (!owners(router).equals(expected)) {
                        return false;
                    }
                    admitRouterLocked(router, entry, apply);
                    return true;
                }
            })) {
                return;
            }
        }
    }

    private static void admitRouterLocked(DefaultRouter router, Entry entry, Runnable apply) {
        DefaultRouter child = entry.child;
        if (child != null && (child == router || reaches(child, router))) {
            throw new IllegalArgumentException("a Router cannot be mounted inside itself");
        }
        List<RegistrationLog> logs = new ArrayList<>();
        List<List<RegistrationRecord>> batches = new ArrayList<>();
        for (Mount m : router.mounts) {
            RegistrationLog log = m.log();
            if (log == null) {
                continue; // its app was collected after owners() ran; nothing watches or records there
            }
            List<RegistrationRecord> batch = new ArrayList<>();
            expandEntry(m.record, entry, batch,
                    Collections.newSetFromMap(new IdentityHashMap<DefaultRouter, Boolean>()));
            logs.add(log);
            batches.add(batch);
        }
        for (RegistrationLog log : logs) {
            log.step("validate");
        }
        for (int i = 0; i < logs.size(); i++) {
            logs.get(i).watch(batches.get(i));
        }
        apply.run();
        router.entries.add(entry);
        for (int i = 0; i < logs.size(); i++) {
            RegistrationLog log = logs.get(i);
            log.step("apply");
            log.record(batches.get(i));
            log.step("record");
        }
    }

    private static boolean reaches(DefaultRouter from, DefaultRouter target) {
        for (Entry e : from.entries) {
            DefaultRouter c = e.child;
            if (c != null && (c == target || reaches(c, target))) {
                return true;
            }
        }
        return false;
    }

    /** The registered object a middleware record names: the handler inside useAsync's adapter, else the callback. */
    // @req FR-APP-025
    static Object target(Object callback) {
        if (callback instanceof AsyncMiddleware) {
            return ((AsyncMiddleware) callback).handler;
        }
        return callback;
    }

    /** The target of a route: its last callback, with the RouteHandler or AsyncRouteHandler inside unwrapped. */
    static Object routeTarget(Middleware[] callbacks) {
        Object last = callbacks[callbacks.length - 1];
        return last instanceof TerminalMiddleware ? ((TerminalMiddleware) last).handler : target(last);
    }

    /** One registration kept by a Router, expanded again whenever the Router is mounted. */
    // @req FR-APP-026
    static final class Entry {
        final RegistrationKind kind;
        final @Nullable String method;
        final Object pathSource;
        final Object target;
        final @Nullable DefaultRouter child;

        Entry(RegistrationKind kind, @Nullable String method, Object pathSource, Object target,
                @Nullable DefaultRouter child) {
            this.kind = kind;
            this.method = method;
            this.pathSource = Objects.requireNonNull(pathSource, "path");
            this.target = target;
            this.child = child;
        }
    }

    /**
     * One place a Router is mounted: the app log and the ROUTER_MOUNT record of that mount. The log is held weakly (the
     * app holds it strongly), so a Router shared between apps never keeps an app the caller dropped reachable;
     * {@link #owners} prunes cleared and terminated mounts.
     */
    // @req FR-APP-026
    static final class Mount {
        private final java.lang.ref.WeakReference<RegistrationLog> log;
        final RegistrationRecord record;

        Mount(RegistrationLog log, RegistrationRecord record) {
            this.log = new java.lang.ref.WeakReference<>(log);
            this.record = record;
        }

        /** The mounting app's log, or null once that app was collected. */
        @Nullable RegistrationLog log() {
            return log.get();
        }
    }
}
