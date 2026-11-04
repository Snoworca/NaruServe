package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.utils.auth.internal.ScopeMatcher;
import com.snoworca.naruserve.utils.auth.rbac.Scopes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Who made a request, as {@code auth.user(req)} returns it: the user id and name, the effective roles (own and from
 * groups) and the scopes of those roles. Getters never throw. A minimal principal ({@code .statelessAccess(true)}
 * on a route without a guard) carries only the id: its name is empty, its sets are empty and {@link #isLoaded()} is
 * false until a guard or {@code auth.loadUser(req)} promotes it. For an API-key request a scope is covered only when
 * both the key's scopes and the owner's current scopes cover it.
 */
// @req IR-AUTH-004
public final class AuthPrincipal {

    private final String id;
    private final String username;
    private final long tokenVersion;
    private final Set<String> roles;
    private final Set<String> scopes;
    private final Map<String, String> attributes;
    private final boolean loaded;
    private final @Nullable String apiKeyId;
    private final @Nullable Set<String> keyScopes;

    AuthPrincipal(String id, String username, long tokenVersion, Set<String> roles, Set<String> scopes,
            Map<String, String> attributes, boolean loaded, @Nullable String apiKeyId, @Nullable Set<String> keyScopes) {
        this.id = id;
        this.username = username;
        this.tokenVersion = tokenVersion;
        this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(roles));
        this.scopes = Collections.unmodifiableSet(new LinkedHashSet<>(scopes));
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        this.loaded = loaded;
        this.apiKeyId = apiKeyId;
        this.keyScopes = keyScopes == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(keyScopes));
    }

    static AuthPrincipal minimal(String id, long tokenVersion, @Nullable String apiKeyId,
            @Nullable Set<String> keyScopes) {
        return new AuthPrincipal(id, "", tokenVersion, Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String, String>emptyMap(), false, apiKeyId, keyScopes);
    }

    /** The user id. */
    public String getId() {
        return id;
    }

    /** The normalized username; empty for a minimal principal. */
    public String getUsername() {
        return username;
    }

    /** The effective roles: the user's own plus those its groups confer; empty for a minimal principal. */
    public Set<String> getRoles() {
        return roles;
    }

    /** The scopes of the effective roles (the owner's, for an API key); empty for a minimal principal. */
    public Set<String> getScopes() {
        return scopes;
    }

    /** The user's attributes; empty for a minimal principal. */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /** False for a minimal principal that has not been loaded from the user store. */
    public boolean isLoaded() {
        return loaded;
    }

    /** The API key that authenticated the request, or null for a JWT login. */
    public @Nullable String getApiKeyId() {
        return apiKeyId;
    }

    /**
     * True when the principal holds {@code scope}, a wildcard-free {@code resource:action}: some granted scope covers
     * it ({@code *} covers everything, {@code r:*} covers {@code r:a}); for an API key the key's scopes must cover it
     * too (the key's effective scopes are the key scopes intersected with the owner's current ones), and a key covers
     * an {@code auth-*} scope only by naming it or its {@code resource:*}, never through {@code *}. False for a
     * malformed scope.
     */
    // @req IR-AUTH-004
    // @req FR-IAM-009
    // @req SEC-APIKEY-007
    // @req SEC-APIKEY-009
    public boolean hasScope(String scope) {
        if (!Scopes.isValidRequired(scope)) {
            return false;
        }
        Set<String> key = keyScopes;
        if (!ScopeMatcher.allows(scopes, scope)) {
            return false;
        }
        if (key == null) {
            return true;
        }
        if (scope.startsWith("auth-")) {
            // a key covers an auth-* scope only by naming it or its resource:*, never through * (00 R47 (5))
            String resource = scope.substring(0, scope.indexOf(':'));
            return key.contains(scope) || key.contains(resource + ":*");
        }
        return ScopeMatcher.allows(key, scope);
    }

    /** True when the principal holds {@code role}. */
    // @req IR-AUTH-004
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    long tokenVersion() {
        return tokenVersion;
    }

    @Nullable Set<String> keyScopes() {
        return keyScopes;
    }

    @Override
    public String toString() {
        return "AuthPrincipal{id=" + id + ", username=" + username + ", roles=" + roles + ", loaded=" + loaded
                + (apiKeyId == null ? "" : ", apiKeyId=" + apiKeyId) + "}";
    }
}
