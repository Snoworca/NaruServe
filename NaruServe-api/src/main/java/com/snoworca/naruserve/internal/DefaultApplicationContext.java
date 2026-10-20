package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.common.AttributeValues;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.ApplicationContext;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.ServiceNotFoundException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The one context of a {@link DefaultRestApp}, created once in its constructor. Registration and {@link #own} are
 * open only while the app is STARTING and before {@link #freeze()}; reads work in every state and become lock-free
 * once the frozen snapshot is published. Attributes are open in every state.
 */
// @req IR-APP-004
// @req FR-APP-007
// @req FR-APP-008
public final class DefaultApplicationContext implements ApplicationContext {

    private final AppLifecycle lifecycle;
    private final DefaultRestApp app;
    // Guarded by this until frozen.
    private final Map<Class<?>, Object> services = new LinkedHashMap<>();
    private volatile @Nullable Map<Class<?>, Object> frozen;
    private final OwnedResources owned = new OwnedResources();
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();

    // @req FR-APP-007
    DefaultApplicationContext(AppLifecycle lifecycle, DefaultRestApp app) {
        this.lifecycle = lifecycle;
        this.app = app;
    }

    /** Must hold this: throws unless STARTING and not frozen, before anything is touched. */
    private void requireOpen(String method) {
        AppState s = lifecycle.state();
        if (s != AppState.STARTING || frozen != null) {
            throw new IllegalStateException(method + " not allowed in state " + s
                    + (frozen != null ? " after the registry is frozen" : ""));
        }
    }

    // @req IR-APP-004
    // @req FR-APP-007
    // @req FR-APP-008
    @Override
    public synchronized <T> void register(Class<T> type, T instance) {
        put("register", type, instance);
        if (instance instanceof AutoCloseable) {
            owned.add((AutoCloseable) instance);
        }
    }

    // @req IR-APP-004
    // @req FR-APP-007
    // @req FR-APP-008
    @Override
    public synchronized <T> void registerExternal(Class<T> type, T instance) {
        put("registerExternal", type, instance);
    }

    private void put(String method, Class<?> type, Object instance) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(instance, "instance");
        requireOpen(method);
        if (services.containsKey(type)) {
            throw new IllegalStateException("service already registered: " + type.getName());
        }
        services.put(type, DefaultRequest.wrapper(type).cast(instance));
    }

    // @req IR-APP-004
    // @req FR-APP-007
    @Override
    public <T> T get(Class<T> type) {
        T found = getOrNull(type);
        if (found == null) {
            throw new ServiceNotFoundException(type);
        }
        return found;
    }

    // @req IR-APP-004
    // @req FR-APP-007
    @Override
    @SuppressWarnings("unchecked") // a wrapper class stands for its primitive T, which is the boxed type at runtime
    public <T> @Nullable T getOrNull(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return (T) DefaultRequest.wrapper(type).cast(lookup(type));
    }

    // @req FR-APP-007
    @Override
    public boolean contains(Class<?> type) {
        return lookup(Objects.requireNonNull(type, "type")) != null;
    }

    private @Nullable Object lookup(Class<?> type) {
        Map<Class<?>, Object> snapshot = frozen;
        if (snapshot != null) {
            return snapshot.get(type);
        }
        synchronized (this) {
            return services.get(type);
        }
    }

    // @req IR-APP-004
    // @req FR-APP-007
    @Override
    public void attribute(String key, @Nullable Object value) {
        Objects.requireNonNull(key, "key");
        AttributeValues.put(attributes, key, value);
    }

    // @req IR-APP-004
    // @req FR-APP-007
    @Override
    @SuppressWarnings("unchecked") // a wrapper class stands for its primitive T, which is the boxed type at runtime
    public <T> @Nullable T attributeAs(String key, Class<T> type) {
        return AttributeValues.get(attributes, key, type);
    }

    // @req FR-APP-008
    @Override
    public synchronized void own(AutoCloseable resource) {
        Objects.requireNonNull(resource, "resource");
        requireOpen("own");
        owned.add(resource);
    }

    /** The app logger read at call time: the raw logger, not the framework's SafeLogger. */
    // @req FR-APP-007
    @Override
    public Logger logger() {
        return app.logger();
    }

    /** Publishes the unmodifiable snapshot and closes registration; later calls do nothing. */
    // @req FR-APP-007
    synchronized void freeze() {
        if (frozen == null) {
            frozen = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        }
    }

    /** Closes the owned resources in reverse order, never {@code skip}; returns the close failures in order. */
    // @req FR-APP-008
    List<Exception> closeOwned(@Nullable Object skip) {
        synchronized (this) {
            // Barrier: called after the state left STARTING, so an own in progress finishes and later ones are refused.
        }
        return owned.closeAll(skip);
    }
}
