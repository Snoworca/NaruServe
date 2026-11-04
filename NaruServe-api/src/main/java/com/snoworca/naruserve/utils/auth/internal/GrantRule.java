package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.utils.auth.AuthErrorCode;
import com.snoworca.naruserve.utils.auth.AuthRejection;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.Collections;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The write-time decision of every admin write (00 R31): "you can only grant what you have, and you cannot touch
 * what is bigger than you". The actor and the target are re-read from the store, never from the principal cache.
 * (1) every scope the target newly gains must be covered by the actor's effective scopes A; (2) for modify, password
 * reset, disable, delete, member removal and role removal the target's current effective scopes must be covered by A;
 * (3) the actor may not change its own roles or groups nor disable or delete itself. (1) and (2) answer 403
 * {@code insufficient_scope}; (3) answers 403 {@code insufficient_scope} too and leaves a
 * {@code self_change_denied} audit event naming the actor as actor and target. An actor the re-read finds missing or
 * disabled is refused with 403 {@code insufficient_scope} and a grant_denied event before any of them. Not applied to
 * the app's own paths ({@code auth.users()}, {@code auth.apiKeys()}).
 */
// @req SEC-IAM-004
public final class GrantRule {

    /** What an admin write does to its target. */
    public enum Action {
        /** A profile change. */
        MODIFY(true, false),
        /** A password reset. */
        PASSWORD_RESET(true, false),
        /** Disabling the user. */
        DISABLE(true, true),
        /** Deleting the user. */
        DELETE(true, true),
        /** Adding roles. */
        ADD_ROLES(false, true),
        /** Removing roles. */
        REMOVE_ROLES(true, true),
        /** Adding the user to a group. */
        ADD_TO_GROUP(false, true),
        /** Removing the user from a group. */
        REMOVE_FROM_GROUP(true, true);

        final boolean protectsTarget;
        final boolean forbiddenOnSelf;

        Action(boolean protectsTarget, boolean forbiddenOnSelf) {
            this.protectsTarget = protectsTarget;
            this.forbiddenOnSelf = forbiddenOnSelf;
        }
    }

    private final UserStore users;
    private final EffectiveGrants grants;
    private final AuditTrail audit;

    /** The rule over the store, the grant computation and the audit trail. */
    // @req SEC-IAM-004
    public GrantRule(UserStore users, EffectiveGrants grants, AuditTrail audit) {
        this.users = users;
        this.grants = grants;
        this.audit = audit;
    }

    /** The effective scopes of {@code userId} read now from the store; empty for a missing or disabled user. */
    // @req SEC-IAM-004
    public Set<String> scopesOf(String userId) throws Exception {
        AuthUser u = users.findById(userId);
        if (u == null || !u.isEnabled()) {
            return Collections.emptySet();
        }
        return grants.scopesOf(u);
    }

    /**
     * The effective scopes of the actor {@code actorId} re-read now from the store for a write; an actor that is
     * missing or disabled there (deleted or disabled while the principal cache still admits it) is refused with 403
     * {@code insufficient_scope} and a grant_denied event, before any coverage test.
     */
    // @req SEC-IAM-004
    public Set<String> actorScopes(@Nullable Request req, String actorId, @Nullable String targetId, String action)
            throws Exception {
        AuthUser u = users.findById(actorId);
        if (u == null || !u.isEnabled()) {
            String why = "the actor is no longer an enabled user";
            audit.emit(actorEvent(AuditEvent.GRANT_DENIED, action, req, actorId).targetId(targetId)
                    .outcome(AuditEvent.Outcome.DENIED).reason(why).build());
            throw refusal(why);
        }
        return grants.scopesOf(u);
    }

    /**
     * The scopes a target holds for target protection: its roles' and groups' scopes even while it is disabled, so a
     * disabled admin stays protected from a smaller actor; empty only for a missing user.
     */
    private Set<String> targetScopesOf(String userId) throws Exception {
        AuthUser u = users.findById(userId);
        return u == null ? Collections.<String>emptySet() : grants.scopesOf(u);
    }

    /**
     * Allows {@code action} by {@code actorId} on {@code targetId}, whose effective scopes newly gain {@code gained}
     * (empty when nothing is granted), or throws the refusal {@link AuthRejection}.
     */
    // @req SEC-IAM-004
    public void check(@Nullable Request req, String actorId, String targetId, Action action, Set<String> gained)
            throws Exception {
        check(req, actorId, null, targetId, action, gained);
    }

    /**
     * {@link #check(Request, String, String, Action, Set)} for an actor that may be an API key: with
     * {@code actorKeyScopes} not null, a scope counts as the actor's only when the owner's scopes and the key's scopes
     * both cover it, and a key covers neither {@code *} nor an {@code auth-*} scope through {@code *} (00 R31 (4)).
     */
    // @req SEC-IAM-004
    public void check(@Nullable Request req, String actorId, @Nullable Set<String> actorKeyScopes, String targetId,
            Action action, Set<String> gained) throws Exception {
        if (action.forbiddenOnSelf && actorId.equals(targetId)) {
            audit.emit(actorEvent(AuditEvent.SELF_CHANGE_DENIED, action.name(), req, actorId)
                    .targetId(targetId).outcome(AuditEvent.Outcome.DENIED)
                    .reason("an actor cannot change its own roles or groups, or disable or delete itself").build());
            throw refusal("an actor cannot change its own roles or groups, or disable or delete itself");
        }
        Set<String> actor = actorScopes(req, actorId, targetId, action.name());
        for (String g : gained) {
            if (!covers(actor, actorKeyScopes, g)) {
                denied(req, actorId, targetId, action, "the actor does not hold a scope it grants", g);
            }
        }
        if (action.protectsTarget) {
            for (String t : targetScopesOf(targetId)) {
                if (!covers(actor, actorKeyScopes, t)) {
                    denied(req, actorId, targetId, action, "the target holds a scope the actor does not hold", t);
                }
            }
        }
    }

    /**
     * Rule (1) alone, for a write whose target does not exist yet (an admin create) or for an edge or membership whose
     * gained scopes reach users through a group: every scope of {@code gained} must be covered by the actor's
     * effective scopes re-read from the store, else 403 {@code insufficient_scope} with a grant_denied event.
     */
    // @req SEC-IAM-004
    public void checkGrant(@Nullable Request req, String actorId, @Nullable Set<String> actorKeyScopes,
            @Nullable String targetId, String action, Set<String> gained) throws Exception {
        Set<String> actor = actorScopes(req, actorId, targetId, action);
        for (String g : gained) {
            if (!covers(actor, actorKeyScopes, g)) {
                audit.emit(actorEvent(AuditEvent.GRANT_DENIED, action, req, actorId).targetId(targetId)
                        .outcome(AuditEvent.Outcome.DENIED).reason("the actor does not hold a scope it grants")
                        .build());
                throw refusal("the actor does not hold a scope it grants: " + g); // OPS-AUTH-021 AC-7
            }
        }
    }

    /**
     * Rule (2) for one affected user of a group write (delete or edge removal), re-read from the store: the user's
     * current effective scopes must be covered by {@code actorScopes}, which the caller reads once with
     * {@link #actorScopes} (refusing a missing or disabled actor); the actor among the affected users is refused
     * under rule (3).
     */
    // @req SEC-IAM-005
    public void checkAffected(@Nullable Request req, String actorId, Set<String> actorScopes,
            @Nullable Set<String> actorKeyScopes, String userId, String action) throws Exception {
        if (actorId.equals(userId)) {
            selfDenied(req, actorId, action);
        }
        for (String t : targetScopesOf(userId)) {
            if (!covers(actorScopes, actorKeyScopes, t)) {
                audit.emit(actorEvent(AuditEvent.GRANT_DENIED, action, req, actorId).targetId(userId)
                        .outcome(AuditEvent.Outcome.DENIED)
                        .reason("an affected user holds a scope the actor does not hold").build());
                throw refusal("an affected user holds a scope the actor does not hold");
            }
        }
    }

    /** Rule (3): the refusal and audit event of an actor changing its own roles or groups, or itself. */
    // @req SEC-IAM-004
    public void selfDenied(@Nullable Request req, String actorId, String action) {
        audit.emit(actorEvent(AuditEvent.SELF_CHANGE_DENIED, action, req, actorId).targetId(actorId)
                .outcome(AuditEvent.Outcome.DENIED)
                .reason("an actor cannot change its own roles or groups, or disable or delete itself").build());
        throw refusal("an actor cannot change its own roles or groups, or disable or delete itself");
    }

    private void denied(@Nullable Request req, String actorId, String targetId, Action action, String why,
            String scope) {
        audit.emit(actorEvent(AuditEvent.GRANT_DENIED, action.name(), req, actorId).targetId(targetId)
                .outcome(AuditEvent.Outcome.DENIED).reason(why).build());
        throw refusal(why + ": " + scope); // OPS-AUTH-021 AC-7: the audit reason stays the rule
    }

    /** An event of {@code actorId}: its key id from the request, and the service-account mark of its row. */
    // @req FR-APIKEY-006
    // @req SEC-APIKEY-007
    private AuditEvent.Builder actorEvent(String type, String action, @Nullable Request req, String actorId) {
        boolean service = false;
        try {
            AuthUser u = users.findById(actorId);
            service = u != null && u.isServiceAccount();
        } catch (Exception e) {
            // the mark is best effort: the refusal itself does not depend on it
        }
        return audit.event(type, action, req, actorId, null, service);
    }

    private static AuthRejection refusal(String why) {
        return new AuthRejection(AuthErrorCode.INSUFFICIENT_SCOPE.getStatus(), AuthErrorCode.INSUFFICIENT_SCOPE.getCode(),
                why);
    }

    /**
     * {@link #covers(Set, String)} for an actor that may be an API key: the owner's scopes must cover {@code g} and,
     * when {@code key} is not null, the key's scopes too, where a key never covers {@code *} and covers an
     * {@code auth-*} scope only by naming it or its {@code resource:*}, never through {@code *} (00 R31 (4)).
     */
    // @req SEC-IAM-004
    // @req SEC-IAM-005
    public static boolean covers(Set<String> owner, @Nullable Set<String> key, String g) {
        if (!covers(owner, g)) {
            return false;
        }
        if (key == null) {
            return true;
        }
        if (g.equals("*")) {
            return false; // no key covers a conferred or held *
        }
        if (g.startsWith("auth-")) {
            int colon = g.indexOf(':');
            String resource = colon < 0 ? g : g.substring(0, colon);
            return key.contains(g) || key.contains(resource + ":*");
        }
        return covers(key, g);
    }

    /**
     * True when the actor's scopes {@code a} cover the granted scope {@code g}, which may itself be a wildcard: only
     * {@code *} covers {@code *}, and {@code resource:*} is covered by itself or {@code *}.
     */
    // @req SEC-IAM-004
    public static boolean covers(Set<String> a, String g) {
        if (a.contains("*")) {
            return true;
        }
        if (g.equals("*")) {
            return false;
        }
        if (g.endsWith(":*")) {
            return a.contains(g);
        }
        return ScopeMatcher.allows(a, g);
    }
}
