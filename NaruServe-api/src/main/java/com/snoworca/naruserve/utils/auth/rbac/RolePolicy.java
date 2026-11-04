package com.snoworca.naruserve.utils.auth.rbac;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Logger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Roles declared in code. The built-in policy has one role, {@code admin}, holding {@code *}; the roles of a builder
 * merge into it, so an app adds its own roles and keeps an all-powerful admin. Declaring {@code admin} again adds to
 * it (a union) and logs one WARN at {@link Builder#build()}. {@link Builder#replaceBuiltinRoles()} drops the built-in
 * roles. Scopes are checked by {@link Scopes} when a role is declared.
 */
// @req FR-IAM-006
public final class RolePolicy implements RoleStore {

    /** The built-in role that holds {@code *}. */
    public static final String ADMIN = "admin";

    private static final RolePolicy BUILTIN = new Builder().build();

    private final Map<String, Set<String>> roles;
    private final Set<String> granted;

    private RolePolicy(Map<String, Set<String>> roles) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        Set<String> all = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> e : roles.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
            all.addAll(e.getValue());
        }
        this.roles = Collections.unmodifiableMap(copy);
        this.granted = Collections.unmodifiableSet(all);
    }

    /** A builder starting from the built-in policy. */
    // @req FR-IAM-006
    public static Builder builder() {
        return new Builder();
    }

    /** The built-in policy alone: {@code admin} holds {@code *}. */
    // @req FR-IAM-006
    public static RolePolicy builtin() {
        return BUILTIN;
    }

    /** The scopes of {@code role}, unmodifiable; empty for an unknown role. */
    // @req FR-IAM-006
    @Override
    public Set<String> scopesOf(String role) {
        Set<String> s = roles.get(role);
        return s == null ? Collections.<String>emptySet() : s;
    }

    /** The declared role names, unmodifiable. */
    public Set<String> roles() {
        return roles.keySet();
    }

    /** Every granted scope of every role, unmodifiable. */
    public Set<String> grantedScopes() {
        return granted;
    }

    @Override
    public String toString() {
        return "RolePolicy" + roles;
    }

    /** Declares roles; {@link #build()} merges them into the built-in policy. */
    // @req FR-IAM-006
    public static final class Builder {
        private final Map<String, Set<String>> declared = new LinkedHashMap<>();
        private boolean replaceBuiltins;
        private @Nullable Logger logger;

        private Builder() {
        }

        /**
         * Grants {@code scopes} to {@code role}; declaring a role again adds to it. A scope that is not a valid
         * grant, or names a reserved {@code auth-*} resource, throws {@link IllegalArgumentException} here.
         */
        // @req FR-IAM-006
        public Builder role(String role, String... scopes) {
            if (role.isEmpty()) {
                throw new IllegalArgumentException("role name must not be empty");
            }
            Set<String> s = declared.get(role);
            if (s == null) {
                s = new LinkedHashSet<>();
                declared.put(role, s);
            }
            for (String scope : scopes) {
                s.add(Scopes.validateGrant(Objects.requireNonNull(scope, "scope")));
            }
            return this;
        }

        /** Drops the built-in roles, so {@code admin} has only what this builder grants it. */
        // @req FR-IAM-006
        public Builder replaceBuiltinRoles() {
            this.replaceBuiltins = true;
            return this;
        }

        /** Where {@link #build()} writes its warnings; default a new {@link ConsoleLogger}. */
        public Builder logger(Logger logger) {
            this.logger = Objects.requireNonNull(logger, "logger");
            return this;
        }

        /** The policy: the built-in roles (unless replaced) merged with the declared ones. */
        // @req FR-IAM-006
        public RolePolicy build() {
            Map<String, Set<String>> merged = new LinkedHashMap<>();
            if (!replaceBuiltins) {
                merged.put(ADMIN, new LinkedHashSet<>(Collections.singleton("*")));
            }
            for (Map.Entry<String, Set<String>> e : declared.entrySet()) {
                Set<String> s = merged.get(e.getKey());
                if (s == null) {
                    merged.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
                } else {
                    s.addAll(e.getValue());
                    Logger log = logger != null ? logger : new ConsoleLogger();
                    log.warn("RolePolicy: role '" + ADMIN + "' is built in and holds *; the declared scopes "
                            + e.getValue() + " are added to it. Use replaceBuiltinRoles() to redefine it.");
                }
            }
            return new RolePolicy(merged);
        }
    }
}
