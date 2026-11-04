package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RegistrationKind;
import org.jspecify.annotations.Nullable;

/** The one {@link Registration} implementation: immutable, created before the watcher sees it. */
// @req IR-APP-008
// @req FR-APP-025
// @req FR-APP-026
final class RegistrationRecord implements Registration {
    private final RegistrationKind kind;
    private final @Nullable String method;
    private final @Nullable String path;
    private final Object pathSource;
    private final Object target;
    private final @Nullable RegistrationRecord mount;
    /** The Router a ROUTER_MOUNT record mounts; null for every other kind. */
    final @Nullable DefaultRouter mounted;

    private RegistrationRecord(RegistrationKind kind, @Nullable String method, @Nullable String path, Object pathSource,
            Object target, @Nullable RegistrationRecord mount, @Nullable DefaultRouter mounted) {
        this.kind = kind;
        this.method = method;
        this.path = path;
        this.pathSource = pathSource;
        this.target = target;
        this.mount = mount;
        this.mounted = mounted;
    }

    /**
     * A record of {@code kind} for a call that passed {@code source} as its path, under {@code mount} (null at app
     * level). The path is the mount path joined with {@code source} when both are strings; a middleware kind is
     * MIDDLEWARE for the path {@code ""} or {@code "/"} and PATH_MIDDLEWARE for any other.
     */
    // @req FR-APP-025
    // @req FR-APP-026
    static RegistrationRecord of(RegistrationKind kind, @Nullable String method, Object source, Object target,
            @Nullable RegistrationRecord mount, @Nullable DefaultRouter mounted) {
        String prefix = mount == null ? "" : mount.path;
        String path = prefix == null || !(source instanceof String) ? null : join(prefix, (String) source);
        RegistrationKind k = kind;
        if (k == RegistrationKind.MIDDLEWARE || k == RegistrationKind.PATH_MIDDLEWARE) {
            k = path != null && (path.isEmpty() || path.equals("/")) ? RegistrationKind.MIDDLEWARE
                    : RegistrationKind.PATH_MIDDLEWARE;
        }
        return new RegistrationRecord(k, method, path, source, target, mount, mounted);
    }

    /** {@code prefix} and {@code path} with one slash between them; never throws. */
    static String join(String prefix, String path) {
        if (prefix.isEmpty()) {
            return path;
        }
        if (path.isEmpty()) {
            return prefix;
        }
        String p = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return path.startsWith("/") ? p + path : p + "/" + path;
    }

    @Override
    public RegistrationKind getKind() {
        return kind;
    }

    @Override
    public @Nullable String getMethod() {
        return method;
    }

    @Override
    public @Nullable String getPath() {
        return path;
    }

    @Override
    public boolean isStringPath() {
        return path != null;
    }

    @Override
    public Object getPathSource() {
        return pathSource;
    }

    @Override
    public Object getTarget() {
        return target;
    }

    @Override
    public @Nullable Registration getMount() {
        return mount;
    }

    @Override
    public String toString() {
        return kind + (method != null ? " " + method : "") + " " + (path != null ? path : "<" + pathSource + ">");
    }
}
