package com.snoworca.naruserve.utils.auth.user;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * An immutable user as auth sees it: a String id, the normalized username, the password hash, the token version,
 * the enabled flag, roles, string attributes and the service-account flag. A store adapter maps its own rows to and
 * from this class in one place; numeric and UUID keys go through {@link AuthIds}. v1 users have no direct scopes:
 * scopes come from roles only. Change a user with {@link #toBuilder()}.
 *
 * <p>The password hash field is transient, so only a Gson with the default modifier settings (new GsonJsonCodec())
 * leaves it out of JSON. With a Gson built with excludeFieldsWithModifiers(STATIC) or another JsonCodec, the app must
 * remove passwordHash itself. {@link #toString()} never shows it.
 */
// @req DR-IAM-001
public final class AuthUser {

    private final @Nullable String id;
    private final String username;
    private final transient @Nullable String passwordHash;
    private final long tokenVersion;
    private final boolean enabled;
    private final Set<String> roles;
    private final Map<String, String> attributes;
    private final boolean serviceAccount;

    private AuthUser(Builder b) {
        this.id = b.id;
        this.username = Objects.requireNonNull(b.username);
        this.passwordHash = b.passwordHash;
        this.tokenVersion = b.tokenVersion;
        this.enabled = b.enabled;
        this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(b.roles));
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(b.attributes));
        this.serviceAccount = b.serviceAccount;
    }

    /** A builder for a new user: enabled, token version 0, no roles, no attributes, not a service account. */
    // @req DR-IAM-001
    public static Builder builder() {
        return new Builder();
    }

    /** A builder holding every value of this user. */
    // @req DR-IAM-001
    public Builder toBuilder() {
        Builder b = new Builder();
        b.id = id;
        b.username = username;
        b.passwordHash = passwordHash;
        b.tokenVersion = tokenVersion;
        b.enabled = enabled;
        b.roles.addAll(roles);
        b.attributes.putAll(attributes);
        b.serviceAccount = serviceAccount;
        return b;
    }

    /** The store's id of this user; null only before the store created it. Ids are never reused. */
    public @Nullable String getId() {
        return id;
    }

    /** The normalized login name (NFKC, lower case). */
    public String getUsername() {
        return username;
    }

    /** The password hash, or null for a user that cannot log in with a password. */
    public @Nullable String getPasswordHash() {
        return passwordHash;
    }

    /** The token version; raising it invalidates every token issued before. */
    public long getTokenVersion() {
        return tokenVersion;
    }

    /** False for a disabled user, who cannot log in or use tokens. */
    public boolean isEnabled() {
        return enabled;
    }

    /** The role names, unmodifiable. */
    public Set<String> getRoles() {
        return roles;
    }

    /** The app's extra fields, unmodifiable. */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /** One attribute, or null when it is not set. */
    public @Nullable String getAttribute(String name) {
        return attributes.get(name);
    }

    /** True for a service account: no password, authenticated by API keys only; set at creation and never changed. */
    public boolean isServiceAccount() {
        return serviceAccount;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuthUser)) {
            return false;
        }
        AuthUser u = (AuthUser) o;
        return tokenVersion == u.tokenVersion && enabled == u.enabled && serviceAccount == u.serviceAccount
                && Objects.equals(id, u.id) && username.equals(u.username)
                && Objects.equals(passwordHash, u.passwordHash) && roles.equals(u.roles)
                && attributes.equals(u.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, username, passwordHash, tokenVersion, enabled, roles, attributes, serviceAccount);
    }

    /** Every field except the password hash and the attribute values (apps keep secrets there); keys are shown. */
    @Override
    public String toString() {
        return "AuthUser{id=" + id + ", username=" + username + ", tokenVersion=" + tokenVersion + ", enabled="
                + enabled + ", roles=" + roles + ", attributes=" + attributes.keySet() + ", serviceAccount=" + serviceAccount
                + "}";
    }

    /** Builds an {@link AuthUser}; every setter returns this builder. */
    // @req DR-IAM-001
    public static final class Builder {
        private @Nullable String id;
        private @Nullable String username;
        private @Nullable String passwordHash;
        private long tokenVersion;
        private boolean enabled = true;
        private final Set<String> roles = new LinkedHashSet<>();
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private boolean serviceAccount;

        private Builder() {
        }

        /** The store id; null before create. */
        public Builder id(@Nullable String id) {
            this.id = id;
            return this;
        }

        /** The normalized username; required. */
        public Builder username(String username) {
            this.username = Objects.requireNonNull(username, "username");
            return this;
        }

        /** The password hash, or null. */
        public Builder passwordHash(@Nullable String passwordHash) {
            this.passwordHash = passwordHash;
            return this;
        }

        /** The token version; default 0. */
        public Builder tokenVersion(long tokenVersion) {
            this.tokenVersion = tokenVersion;
            return this;
        }

        /** The enabled flag; default true. */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** Replaces the roles. */
        public Builder roles(String... roles) {
            return roles(Arrays.asList(roles));
        }

        /** Replaces the roles. */
        public Builder roles(Collection<String> roles) {
            this.roles.clear();
            for (String r : roles) {
                this.roles.add(Objects.requireNonNull(r, "role"));
            }
            return this;
        }

        /** Sets one attribute; a null value removes it. */
        public Builder attribute(String name, @Nullable String value) {
            Objects.requireNonNull(name, "name");
            if (value == null) {
                attributes.remove(name);
            } else {
                attributes.put(name, value);
            }
            return this;
        }

        /** Replaces the attributes. */
        public Builder attributes(Map<String, String> attributes) {
            this.attributes.clear();
            for (Map.Entry<String, String> e : attributes.entrySet()) {
                attribute(e.getKey(), e.getValue());
            }
            return this;
        }

        /** Marks a service account; default false. */
        public Builder serviceAccount(boolean serviceAccount) {
            this.serviceAccount = serviceAccount;
            return this;
        }

        /** The user; throws {@link IllegalStateException} when no username was set. */
        public AuthUser build() {
            if (username == null) {
                throw new IllegalStateException("AuthUser needs a username");
            }
            return new AuthUser(this);
        }
    }
}
