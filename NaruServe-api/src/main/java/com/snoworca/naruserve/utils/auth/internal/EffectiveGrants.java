package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.auth.group.GroupLimits;
import com.snoworca.naruserve.utils.auth.group.GroupStore;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.rbac.RoleStore;
import com.snoworca.naruserve.utils.auth.rbac.Scopes;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * What a user may do: its own roles plus the roles its groups confer, and the scopes of those roles. Groups come from
 * the configured limits ({@link GroupLimits}): the default expansion walks with them, and an overridden
 * {@code effectiveGroupsOfUser} has its result size checked against them. Rows of a {@link RoleStore} that are not
 * valid grants or name a reserved {@code auth-*} resource are dropped with one WARN per role. Store exceptions
 * propagate; the guard answers them 503.
 */
// @req FR-IAM-006
// @req FR-IAM-007
// @req DR-IAM-002
public final class EffectiveGrants {

    private final RoleStore roleStore;
    private final @Nullable GroupStore groupStore;
    private final GroupLimits limits;
    private final Logger logger;
    private final boolean trustedRoles;
    private final boolean customExpansion;
    private final ConcurrentMap<String, Boolean> warnedRoles = new ConcurrentHashMap<>();

    /** Grants over {@code roles} and, when not null, {@code groups}; warnings go to {@code logger}. */
    // @req FR-IAM-006
    public EffectiveGrants(RoleStore roles, @Nullable GroupStore groups, GroupLimits limits, Logger logger) {
        this.roleStore = roles;
        this.groupStore = groups;
        this.limits = limits;
        this.logger = logger;
        this.trustedRoles = roles instanceof RolePolicy;
        this.customExpansion = groups != null && overridesExpansion(groups);
    }

    private static boolean overridesExpansion(GroupStore groups) {
        try {
            return groups.getClass().getMethod("effectiveGroupsOfUser", String.class).getDeclaringClass()
                    != GroupStore.class;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The effective groups of {@code userId}; empty without a group store. */
    // @req FR-IAM-007
    public Set<String> groupsOf(String userId) throws Exception {
        GroupStore store = groupStore;
        if (store == null) {
            return Collections.emptySet();
        }
        if (!customExpansion) {
            return GroupExpander.expand(store, userId, limits);
        }
        Set<String> groups = nonNull(store.effectiveGroupsOfUser(userId), "effectiveGroupsOfUser");
        GroupExpander.checkSize(groups, userId, limits);
        return Collections.unmodifiableSet(new LinkedHashSet<>(groups));
    }

    /** OPS-AUTH-026 AC-2: hears the roles groups confer, to warn about undeclared ones. */
    public interface RoleWarning {
        void warn(Iterable<String> roles);
    }

    private volatile @Nullable RoleWarning groupRoleWarning;

    public void groupRoleWarning(RoleWarning w) {
        this.groupRoleWarning = w;
    }

    /** The user's own roles plus the roles its effective groups confer. */
    // @req DR-IAM-002
    public Set<String> rolesOf(AuthUser user) throws Exception {
        Set<String> roles = new LinkedHashSet<>(user.getRoles());
        GroupStore store = groupStore;
        String id = user.getId();
        if (store != null && id != null) {
            Set<String> groups = groupsOf(id);
            if (!groups.isEmpty()) {
                Set<String> conferred = nonNull(store.rolesOfGroups(groups), "rolesOfGroups");
                RoleWarning w = groupRoleWarning;
                if (w != null) {
                    w.warn(conferred);
                }
                roles.addAll(conferred);
            }
        }
        return Collections.unmodifiableSet(roles);
    }

    /** The granted scopes of {@code roles}, invalid and reserved store rows dropped. */
    // @req FR-IAM-006
    public Set<String> scopesOfRoles(Set<String> roles) throws Exception {
        Set<String> scopes = new LinkedHashSet<>();
        for (String role : roles) {
            Set<String> rows = nonNull(roleStore.scopesOf(role), "RoleStore.scopesOf");
            if (trustedRoles) {
                scopes.addAll(rows);
                continue;
            }
            Set<String> dropped = null;
            for (String row : rows) {
                if (row != null && Scopes.isValidGrant(row)) {
                    scopes.add(row);
                } else {
                    if (dropped == null) {
                        dropped = new LinkedHashSet<>();
                    }
                    dropped.add(String.valueOf(row));
                }
            }
            if (dropped != null && warnedRoles.putIfAbsent(role, Boolean.TRUE) == null) {
                logger.warn("RoleStore: role '" + role + "' grants " + dropped + ", which are not valid grants or "
                        + "name a reserved auth-* resource; they are ignored");
            }
        }
        return Collections.unmodifiableSet(scopes);
    }

    /** A store that answers a null set has failed: 503, never an empty grant. */
    private static <T> T nonNull(@Nullable T value, String method) {
        if (value == null) {
            throw new com.snoworca.naruserve.utils.auth.AuthUnavailableException(method + " returned null");
        }
        return value;
    }

    /** The granted scopes of {@code user}: the scopes of {@link #rolesOf(AuthUser)}. */
    // @req FR-IAM-006
    public Set<String> scopesOf(AuthUser user) throws Exception {
        return scopesOfRoles(rolesOf(user));
    }
}
