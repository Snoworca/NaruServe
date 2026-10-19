package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.ApplicationContext;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.ServiceNotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The one context of a {@link DefaultRestApp}.
 * shortcut: registration is open in every state and owned resources are only recorded, never closed; the
 * FR-APP-007/FR-APP-008 SDS adds the STARTING-only gate, the frozen snapshot and the reverse-order close.
 */
// @req IR-APP-004
public final class DefaultApplicationContext implements ApplicationContext {

    private final DefaultRestApp app;
    // Guarded by this.
    private final Map<Class<?>, Object> services = new LinkedHashMap<>();
    private final List<AutoCloseable> owned = new ArrayList<>();
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();

    DefaultApplicationContext(DefaultRestApp app) {
        this.app = app;
    }

    // @req IR-APP-004
    @Override
    public <T> void register(Class<T> type, T instance) {
        registerExternal(type, instance);
    }

    // @req IR-APP-004
    @Override
    public synchronized <T> void registerExternal(Class<T> type, T instance) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(instance, "instance");
        if (services.containsKey(type)) {
            throw new IllegalStateException("service already registered: " + type.getName());
        }
        services.put(type, type.cast(instance));
    }

    // @req IR-APP-004
    @Override
    public <T> T get(Class<T> type) {
        T found = getOrNull(type);
        if (found == null) {
            throw new ServiceNotFoundException(type);
        }
        return found;
    }

    // @req IR-APP-004
    @Override
    public synchronized <T> @Nullable T getOrNull(Class<T> type) {
        return type.cast(services.get(Objects.requireNonNull(type, "type")));
    }

    @Override
    public synchronized boolean contains(Class<?> type) {
        return services.containsKey(Objects.requireNonNull(type, "type"));
    }

    // @req IR-APP-004
    @Override
    public void attribute(String key, @Nullable Object value) {
        Objects.requireNonNull(key, "key");
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    // @req IR-APP-004
    @Override
    @SuppressWarnings("unchecked") // a wrapper class stands for its primitive T, which is the boxed type at runtime
    public <T> @Nullable T attribute(String key, Class<T> type) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        return (T) DefaultRequest.wrapper(type).cast(attributes.get(key));
    }

    @Override
    public synchronized void own(AutoCloseable resource) {
        owned.add(Objects.requireNonNull(resource, "resource"));
    }

    @Override
    public Logger logger() {
        return app.logger();
    }
}
