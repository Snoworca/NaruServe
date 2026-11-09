package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.utils.auth.apikey.ApiKey;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyPrefixCollisionException;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStatus;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.AuthApiKeys;
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.internal.AccessTokens;
import com.snoworca.naruserve.utils.auth.internal.ApiKeyFormat;
import com.snoworca.naruserve.utils.auth.internal.ApiKeyUsage;
import com.snoworca.naruserve.utils.auth.internal.AuthArgumentException;
import com.snoworca.naruserve.utils.auth.rbac.Scopes;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@link AuthApiKeys} over the configured key store, and the common issuance core of every issuance path (00 R47 (3),
 * (6)): the new key's ownerVer is the owner row the path's own check read, the limited insertion runs inside the
 * in-node per-owner lock (so a count-then-insert store is safe on one node), and a prefix collision regenerates the
 * prefix and secret up to 3 more times before answering 503.
 */
// @req IR-APIKEY-002
// @req SEC-APIKEY-010
// @req REL-APIKEY-003
final class DefaultAuthApiKeys implements AuthApiKeys {

    /** How many times a colliding prefix is regenerated after the first attempt. */
    static final int COLLISION_RETRIES = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Auth auth;
    private volatile KeyGenerator generator = () -> ApiKeyFormat.generate(RANDOM);

    DefaultAuthApiKeys(Auth auth) {
        this.auth = auth;
    }

    /** The source of new keys; a test seam for the collision retry. */
    @FunctionalInterface
    interface KeyGenerator {
        ApiKeyFormat.Key next();
    }

    void generator(KeyGenerator g) {
        this.generator = g;
    }

    private ApiKeyStore store() {
        ApiKeyStore keys = auth.apiKeyStore();
        if (keys == null) {
            throw new AuthStateException("auth.apiKeys() needs API keys: configure .apiKeys(store) on the builder");
        }
        return keys;
    }

    /** One store call: a store exception other than the auth ones is a 503. */
    private interface StoreCall<T> {
        T run() throws Exception;
    }

    private static <T> T call(StoreCall<T> c) {
        try {
            return c.run();
        } catch (AuthRejection | AuthUnavailableException | AuthStateException | AuthArgumentException e) {
            throw e;
        } catch (ApiKeyPrefixCollisionException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthUnavailableException("the API key store failed", e);
        }
    }

    // @req IR-APIKEY-002
    @Override
    public IssuedApiKey issue(final String ownerUserId, String name, @Nullable Long expiresAt, String... scopes) {
        return issueAs(null, ownerUserId, name, expiresAt, scopes);
    }

    // @req IR-APIKEY-002
    @Override
    public IssuedApiKey issue(Request req, String ownerUserId, String name, @Nullable Long expiresAt,
            String... scopes) {
        return issueAs(Objects.requireNonNull(req, "req"), ownerUserId, name, expiresAt, scopes);
    }

    private IssuedApiKey issueAs(@Nullable Request req, final String ownerUserId, String name, @Nullable Long expiresAt,
            String[] scopes) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(name, "name");
        final ApiKeyStore keys = store();
        Set<String> granted = scopes(scopes);
        if (name.trim().isEmpty() || name.length() > 200) {
            throw new AuthArgumentException("an API key name is 1 to 200 characters");
        }
        long now = auth.time().nowMillis();
        if (expiresAt != null && expiresAt <= now) {
            throw new AuthArgumentException("expiresAt must lie in the future (epoch milliseconds, for example "
                    + "System.currentTimeMillis() + days * 86_400_000L; null for no expiry)"); // OPS-AUTH-022 AC-7
        }
        AuthUser owner = call(() -> auth.userStore().findById(ownerUserId));
        if (owner == null) {
            throw new AuthArgumentException("no user with id " + ownerUserId + "; pass the user id "
                    + "(auth.users().findByUsername(name).getId()), not the username"); // OPS-AUTH-030 AC-6
        }
        if (!owner.isEnabled()) {
            throw new AuthArgumentException("the user " + ownerUserId + " is disabled");
        }
        String actorId = actorId(req);
        IssuedApiKey issued = issueFor(keys, owner, name, expiresAt, granted, actorId);
        AuditEvent.Builder event = auth.audit().event(AuditEvent.API_KEY_ISSUED, "auth.apiKeys().issue", req, actorId,
                null, serviceAccount(actorId)).targetId(ownerUserId).detail("apiKeyId", issued.getKey().getId());
        if (actorId != null) {
            event.detail("issuedByUserId", actorId);
        }
        auth.audit().emit(event.build());
        return issued;
    }

    /** IR-APIKEY-002 AC-9: the request's user, null without a request or for an anonymous one. */
    private @Nullable String actorId(@Nullable Request req) {
        AuthPrincipal p = req == null ? null : auth.userOrNull(req);
        return p == null ? null : p.getId();
    }

    /** Whether {@code actorId} is a service account; best effort, so a store failure only drops the mark. */
    private boolean serviceAccount(@Nullable String actorId) {
        if (actorId == null) {
            return false;
        }
        try {
            AuthUser actor = auth.userStore().findById(actorId);
            return actor != null && actor.isServiceAccount();
        } catch (Exception e) {
            return false;
        }
    }

    /** OPS-AUTH-027 AC-1: a key scope the owner does not hold grants nothing; say so when the key is issued. */
    private void warnUnheld(AuthUser owner, String name, Set<String> scopes) {
        Set<String> held;
        try {
            held = auth.grants().scopesOfRoles(auth.grants().rolesOf(owner));
        } catch (Exception unreadable) {
            return; // the issuance itself reports a store failure
        }
        List<String> unheld = new ArrayList<>();
        for (String s : scopes) {
            boolean covered = false;
            for (String h : held) {
                covered |= Scopes.covers(h, s);
            }
            if (!covered) {
                unheld.add(s);
            }
        }
        if (!unheld.isEmpty()) {
            auth.log().warn("auth: the API key '" + name + "' of " + owner.getUsername() + " names scopes its owner "
                    + "does not hold " + unheld + "; a key acts only with the scopes its owner also holds, so these "
                    + "grant nothing until the owner gets them (give the owner a role)");
        }
    }

    /** The validated scope set of an issuance; admin scopes only under {@code .allowAdminScopesOnApiKeys(true)}. */
    private Set<String> scopes(String[] scopes) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : scopes) {
            Objects.requireNonNull(s, "scope");
            try {
                out.add(Scopes.validateGrant(s));
            } catch (IllegalArgumentException bad) {
                throw new AuthArgumentException(bad.getMessage() == null ? "invalid scope" : bad.getMessage());
            }
            if (s.startsWith("auth-") && !auth.allowAdminScopesOnApiKeys()) {
                throw new AuthArgumentException("the admin scope " + s + " needs .allowAdminScopesOnApiKeys(true)");
            }
        }
        if (out.isEmpty()) {
            throw new AuthArgumentException("an API key needs at least one scope");
        }
        return out;
    }

    /**
     * The issuance core shared by every path: {@code owner} is the row the caller's own check read (its ver becomes
     * the key's ownerVer), the insertion runs under the owner's in-node lock, and a prefix collision is retried.
     */
    // @req SEC-APIKEY-010
    IssuedApiKey issueFor(final ApiKeyStore keys, AuthUser owner, String name, @Nullable Long expiresAt,
            Set<String> scopes, @Nullable String issuedBy) {
        return insertKey(keys, owner, name, expiresAt, -1L, scopes, issuedBy);
    }

    /**
     * {@link #issueFor} for the key endpoints: the key expires {@code lifetimeMillis} after its own creation time, so
     * a key issued without {@code expiresInDays} stores {@code createdAt} plus exactly {@code maxApiKeyDays} days.
     */
    // @req FR-APIKEY-005
    // @req SEC-APIKEY-010
    IssuedApiKey issueForLifetime(ApiKeyStore keys, AuthUser owner, String name, long lifetimeMillis,
            Set<String> scopes, @Nullable String issuedBy) {
        return insertKey(keys, owner, name, null, lifetimeMillis, scopes, issuedBy);
    }

    private IssuedApiKey insertKey(final ApiKeyStore keys, AuthUser owner, String name, @Nullable Long expiresAt,
            long lifetimeMillis, Set<String> scopes, @Nullable String issuedBy) {
        final String ownerId = Objects.requireNonNull(owner.getId(), "owner id");
        warnUnheld(owner, name, scopes); // OPS-AUTH-027 AC-1
        ApiKeyOwners owners = auth.apiKeyOwners();
        owners.lock(ownerId); // the key owner lock alone: issuance never takes the user write lock inside it
        try {
            if (call(() -> auth.userStore().findById(ownerId)) == null) {
                throw new AuthArgumentException("no user with id " + ownerId); // deleted while this issuance waited
            }
            owners.beforeIssue(ownerId); // SINGLE_INSTANCE: a pending marker's retry, then the future-key deletion
            for (int attempt = 0; ; attempt++) {
                ApiKeyFormat.Key k = generator.next();
                final long now = auth.time().nowMillis();
                final StoredApiKey row = StoredApiKey.builder().id(AccessTokens.random()).prefix(k.prefix())
                        .secretHash(ApiKeyFormat.secretHash(k.secret())).ownerUserId(ownerId)
                        .ownerVer(owner.getTokenVersion()).name(name).scopes(scopes).createdAt(now)
                        .expiresAt(lifetimeMillis >= 0 ? Long.valueOf(now + lifetimeMillis) : expiresAt)
                        .issuedByUserId(issuedBy).build();
                final int limit = auth.maxKeysPerUser();
                boolean inserted;
                try {
                    inserted = call(() -> keys.insertIfUnderLimit(row, limit, now));
                } catch (ApiKeyPrefixCollisionException collision) {
                    if (attempt >= COLLISION_RETRIES) {
                        throw new AuthUnavailableException("the API key prefix collided " + (attempt + 1)
                                + " times; try again", collision);
                    }
                    continue;
                }
                if (!inserted) {
                    throw new AuthRejection(AuthErrorCode.API_KEY_LIMIT.getStatus(),
                            AuthErrorCode.API_KEY_LIMIT.getCode(), "the owner holds the maximum number of live API "
                            + "keys (" + limit + ")");
                }
                return new IssuedApiKey(ApiKey.of(row, ApiKeyStatus.ACTIVE), k.plain());
            }
        } finally {
            owners.unlock(ownerId);
        }
    }

    // @req IR-APIKEY-002
    @Override
    public List<ApiKey> list(final String ownerUserId) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        final ApiKeyStore keys = store();
        AuthUser owner = call(() -> auth.userStore().findById(ownerUserId));
        if (owner == null) {
            throw new AuthArgumentException("no user with id " + ownerUserId + "; pass the user id "
                    + "(auth.users().findByUsername(name).getId()), not the username"); // OPS-AUTH-030 AC-6
        }
        List<StoredApiKey> rows = call(() -> keys.listByOwner(ownerUserId));
        List<ApiKey> out = new ArrayList<>();
        long now = auth.time().nowMillis();
        for (StoredApiKey k : rows) {
            out.add(ApiKey.of(k, status(k, owner.getTokenVersion(), now)));
        }
        return out;
    }

    /** The status of {@code k} for an owner at {@code ownerVer} at {@code now}. */
    static ApiKeyStatus status(StoredApiKey k, long ownerVer, long now) {
        Long exp = k.getExpiresAt();
        if (exp != null && now >= exp) {
            return ApiKeyStatus.EXPIRED;
        }
        return k.getOwnerVer() == ownerVer ? ApiKeyStatus.ACTIVE : ApiKeyStatus.STALE;
    }

    // @req IR-APIKEY-002
    @Override
    public boolean revoke(final String keyId) {
        return revokeAs(null, keyId);
    }

    // @req IR-APIKEY-002
    @Override
    public boolean revoke(Request req, String keyId) {
        return revokeAs(Objects.requireNonNull(req, "req"), keyId);
    }

    private boolean revokeAs(@Nullable Request req, final String keyId) {
        Objects.requireNonNull(keyId, "keyId");
        final ApiKeyStore keys = store();
        StoredApiKey row = rowBeforeRevoke(keys, keyId);
        boolean deleted = call(() -> keys.deleteById(keyId));
        if (deleted) {
            ApiKeyUsage usage = auth.apiKeyUsage();
            if (usage != null) {
                usage.forget(keyId);
            }
            String actorId = actorId(req);
            auth.audit().emit(revokedEvent(auth.audit().event(AuditEvent.API_KEY_REVOKED, "auth.apiKeys().revoke", req,
                    actorId, null, serviceAccount(actorId)), keyId, row == null ? null : row.getOwnerUserId(),
                    row == null ? null : row.getIssuedByUserId()));
            auth.keyRevoked(keyId);
        }
        return deleted;
    }

    /** FR-AUTH-009 AC-2: the row a revoke audits, null when unreadable; this read only fills the event, so it never stops the revoke. */
    // @req FR-AUTH-009
    @Nullable StoredApiKey rowBeforeRevoke(ApiKeyStore keys, String keyId) {
        try {
            return keys.findById(keyId);
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** The {@code api_key_revoked} event: the deleted key, the owner as target and the issuer when the key has one. */
    private static AuditEvent revokedEvent(AuditEvent.Builder event, String keyId, @Nullable String ownerId,
            @Nullable String issuedBy) {
        event.targetId(ownerId).detail("revokedApiKeyId", keyId);
        if (issuedBy != null) {
            event.detail("issuedByUserId", issuedBy);
        }
        return event.build();
    }

    /**
     * After a key endpoint deleted {@code keyId} of {@code ownerId} for the request's {@code actorId} (00 R47, FR-AUTH-009
     * AC-2): the usage record is forgotten, an {@code api_key_revoked} audit event names the actor, the owner as target,
     * the deleted key as {@code revokedApiKeyId} and {@code issuedBy}, when known, as {@code issuedByUserId} (an API-key
     * actor's own key id is its {@code apiKeyId}), and the connections opened with the key on this node close.
     */
    // @req SEC-APIKEY-011
    // @req FR-APIKEY-004
    void revokedThrough(Request req, String action, String actorId, String keyId, String ownerId,
            @Nullable String issuedBy) {
        ApiKeyUsage usage = auth.apiKeyUsage();
        if (usage != null) {
            usage.forget(keyId);
        }
        auth.audit().emit(revokedEvent(auth.audit().event(AuditEvent.API_KEY_REVOKED, action, req, actorId, null,
                serviceAccount(actorId)), keyId, ownerId, issuedBy));
        auth.keyRevoked(keyId);
    }

    @Override
    public String toString() {
        return "AuthApiKeys";
    }
}
