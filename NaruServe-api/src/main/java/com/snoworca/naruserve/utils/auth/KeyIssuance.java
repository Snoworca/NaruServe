package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.Deferred;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.utils.auth.apikey.ApiKey;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.internal.AuthArgumentException;
import com.snoworca.naruserve.utils.auth.internal.FailureFloor;
import com.snoworca.naruserve.utils.auth.internal.HashSlots;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.internal.ThrottleKeys;
import com.snoworca.naruserve.utils.auth.rbac.Scopes;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The issuance step shared by {@code POST /auth/me/apikeys} and {@code POST /auth/admin/apikeys} (00 R47 (6), (10)):
 * the body checks (every input error is a Core {@link HttpException} 400 {@code BAD_REQUEST}, before any store read or
 * hash), then, after the endpoint's own owner and scope checks, the confirmation of the caller's current password
 * with constant work (the per-user limit of 5 failures per 15 minutes shared with {@code POST /auth/password}, one
 * target-cost PBKDF2 in one hash slot, a dummy for a caller without a hash, and the R38 failure floor before a 401),
 * and last the issuance core of {@link DefaultAuthApiKeys}: the owner row the endpoint read becomes the key's
 * ownerVer, and the caller is recorded as {@code issuedByUserId} and as the actor of the issue audit event.
 */
// @req FR-APIKEY-003
// @req FR-APIKEY-004
// @req FR-APIKEY-005
final class KeyIssuance {

    /** Milliseconds per day of {@code expiresInDays}. */
    static final long DAY_MILLIS = 86_400_000L;

    /** The longest key name, as for {@code auth.apiKeys().issue}. */
    static final int MAX_NAME = 200;

    /** A checked issuance body. */
    static final class Spec {
        final @Nullable String ownerUserId;
        final String name;
        final Set<String> scopes;
        final long lifetimeMillis;
        final char[] password;

        Spec(@Nullable String ownerUserId, String name, Set<String> scopes, long lifetimeMillis, char[] password) {
            this.ownerUserId = ownerUserId;
            this.name = name;
            this.scopes = scopes;
            this.lifetimeMillis = lifetimeMillis;
            this.password = password;
        }
    }

    /** What the hash slot found: the caller re-read from the store and whether its password matched. */
    private static final class Confirmed {
        final @Nullable AuthUser caller;
        final boolean matched;

        Confirmed(@Nullable AuthUser caller, boolean matched) {
            this.caller = caller;
            this.matched = matched;
        }
    }

    private final Auth auth;

    private static final java.util.List<String> KEY_FIELDS = java.util.Arrays.asList("name", "scopes",
            "expiresInDays", "password");

    KeyIssuance(Auth auth) {
        this.auth = auth;
    }

    // ------------------------------------------------------------------------------------------------- body

    /**
     * The body checks of an issuance, in this order and all 400 {@code BAD_REQUEST}: with {@code admin} a string
     * {@code ownerUserId}; a string {@code name} of 1 to 200 characters; a non-empty {@code scopes} array of strings in
     * the R46 grant grammar, {@code auth-*} only under {@code .allowAdminScopesOnApiKeys(true)}; an optional
     * {@code expiresInDays} that is a JSON integer from 1 to {@code maxApiKeyDays} (the default when absent or null);
     * and a non-empty string {@code password}. Nothing is read from a store and nothing is hashed.
     */
    // @req FR-APIKEY-005
    // @req SEC-APIKEY-009
    Spec spec(Map<String, Object> body, boolean admin) {
        for (String k : body.keySet()) { // OPS-AUTH-027 AC-2: a field the endpoint does not read is refused
            if (!(admin && k.equals("ownerUserId")) && !KEY_FIELDS.contains(k)) {
                throw new ShownError(400, "unknown field " + k + "; the fields are " + (admin ? "ownerUserId, " : "")
                        + "name, scopes, expiresInDays, password" + (k.equals("ownerUserId")
                        ? " (POST /auth/admin/apikeys takes ownerUserId)" : ""));
            }
        }
        String owner = null;
        if (admin) {
            Object o = body.get("ownerUserId");
            if (!(o instanceof String)) {
                throw new ShownError(400, "ownerUserId must be a JSON string");
            }
            owner = (String) o;
        }
        Object n = body.get("name");
        if (!(n instanceof String) || ((String) n).trim().isEmpty() || ((String) n).length() > MAX_NAME) {
            throw new ShownError(400, "name must be a JSON string of 1 to " + MAX_NAME + " characters");
        }
        Set<String> scopes = scopes(body.get("scopes"));
        long days = days(body.get("expiresInDays"));
        Object pw = body.get("password");
        if (!(pw instanceof String) || ((String) pw).isEmpty()) {
            throw new ShownError(400, "password must be a non-empty JSON string");
        }
        return new Spec(owner, (String) n, scopes, days * DAY_MILLIS, ((String) pw).toCharArray());
    }

    private Set<String> scopes(@Nullable Object v) {
        if (!(v instanceof List) || ((List<?>) v).isEmpty()) {
            throw new ShownError(400, "scopes must be a non-empty JSON array of strings");
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object o : (List<?>) v) {
            if (!(o instanceof String) || !Scopes.isValidGrant((String) o)) {
                throw new ShownError(400, "scopes must hold scopes of the form resource:action, resource:* or *");
            }
            String s = (String) o;
            if (s.startsWith("auth-") && !auth.allowAdminScopesOnApiKeys()) {
                throw new ShownError(400, "the admin scope " + s + " needs .allowAdminScopesOnApiKeys(true)");
            }
            out.add(s);
        }
        return Collections.unmodifiableSet(out);
    }

    /** {@code expiresInDays}: absent or null is the maximum; else a JSON integer from 1 to the maximum. */
    private long days(@Nullable Object v) {
        long max = auth.maxApiKeyDays();
        if (v == null) {
            return max;
        }
        Long d = integer(v);
        if (d == null || d < 1 || d > max) {
            throw new ShownError(400, "expiresInDays must be a JSON integer from 1 to " + max);
        }
        return d;
    }

    /**
     * A JSON integer read by any codec: an integral boxed number, or a decimal one without a fraction (a codec that
     * reads every number as a double, like Gson into a Map); null for anything else (a string such as {@code "30"},
     * a boolean, a fraction such as 1.5).
     */
    static @Nullable Long integer(Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof BigInteger) {
            BigInteger b = (BigInteger) v;
            return b.bitLength() < 63 ? b.longValue() : null;
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            return !Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d) && Math.abs(d) < 1e15
                    ? (long) d : null;
        }
        if (v instanceof Number) { // BigDecimal and codec-specific lazily parsed numbers
            try {
                BigDecimal b = new BigDecimal(v.toString());
                return b.signum() == 0 || b.stripTrailingZeros().scale() <= 0 ? b.longValueExact() : null;
            } catch (ArithmeticException | NumberFormatException bad) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------- password

    /**
     * The password confirmation of {@code callerId}, then the issuance for {@code owner} (null: the caller re-read in
     * the slot, the personal key of {@code POST /me/apikeys}). Over the per-user limit: 429 {@code too_many_attempts}
     * with no hash. In one hash slot the caller is re-read and one constant-work verify runs (a dummy PBKDF2 for a
     * missing user, a service account or a null hash). A failure counts toward the limit and is answered with 401
     * {@code invalid_credentials} no earlier than the R38 floor; a caller that verified but is gone or disabled is
     * 401 {@code invalid_token}. On success the key is issued and the 201 written. The password array is wiped.
     */
    // @req FR-APIKEY-003
    // @req FR-APIKEY-004
    // @req SEC-FLOW-015
    Deferred confirmThenIssue(final Request req, final Response res, final String callerId,
            @Nullable final AuthUser owner, final Spec spec, final String action) {
        final String limitKey = ThrottleKeys.passwordConfirm(callerId);
        final FailureFloor floor;
        final long start;
        final Object hold;
        try {
            floor = auth.failureFloor();
            start = floor.now();
            // one confirmation per user at a time (shared with POST /auth/password and checkPassword), so parallel
            // guesses cannot all pass the limit check before any failure is counted; released when the answer settles
            hold = auth.confirmationHolds().tryHold(callerId);
        } catch (RuntimeException e) {
            Arrays.fill(spec.password, '\0');
            throw e;
        }
        if (hold == null) {
            Arrays.fill(spec.password, '\0');
            throw ConfirmationHolds.busy();
        }
        final Deferred done = Deferred.pending();
        // released once the outcome is known: before a 401 or 201 is written, and before the error layer writes a
        // rejection (rejection observers run first, in registration order); releasing twice is harmless
        final Runnable release = () -> auth.confirmationHolds().release(callerId, hold);
        done.onRejected(e -> release.run());
        done.onSettled(release);
        try {
            long blocked = call(() -> auth.loginThrottle().retryAfter(limitKey, DefaultAuthUsers.CONFIRM_MAX,
                    DefaultAuthUsers.CONFIRM_WINDOW_MILLIS));
            if (blocked > 0) {
                throw DefaultAuthUsers.tooMany(blocked);
            }
            submit(req, res, callerId, owner, spec, action, limitKey, floor, start, done, release);
        } catch (Throwable e) {
            Arrays.fill(spec.password, '\0');
            done.reject(e); // releases the hold; the error layer answers it
        }
        return done;
    }

    private void submit(final Request req, final Response res, final String callerId, @Nullable final AuthUser owner,
            final Spec spec, final String action, final String limitKey, final FailureFloor floor, final long start,
            final Deferred done, final Runnable release) {
        auth.hashSlots().submit(new Callable<Confirmed>() {
            @Override
            public Confirmed call() throws Exception {
                AuthUser c = auth.userStore().findById(callerId);
                String stored = c == null || c.isServiceAccount() ? null : c.getPasswordHash();
                return new Confirmed(c, auth.passwords().verifyForLogin(spec.password, stored).matched());
            }
        }, new HashSlots.Callback<Confirmed>() {
            @Override
            public void done(@Nullable final Confirmed confirmed, @Nullable Throwable error) {
                Arrays.fill(spec.password, '\0');
                if (error != null || confirmed == null) {
                    done.reject(storeError(error != null ? error : new IllegalStateException("no result")));
                    return;
                }
                continueLater(done, () -> {
                    try {
                        if (!confirmed.matched) {
                            call(() -> {
                                auth.loginThrottle().recordFailure(limitKey, DefaultAuthUsers.CONFIRM_MAX,
                                        DefaultAuthUsers.CONFIRM_WINDOW_MILLIS);
                                return Boolean.TRUE;
                            });
                            release.run(); // counted: the next attempt may run while this one waits for the floor
                            floor.send(start, () -> {
                                try {
                                    auth.respond(req, res, AuthErrorCode.INVALID_CREDENTIALS.getStatus(),
                                            AuthErrorCode.INVALID_CREDENTIALS.getCode(), "the password is wrong; "
                                            + "send the caller's own password, not the key owner's", null); // OPS-AUTH-028 AC-5
                                    done.resolve();
                                } catch (Throwable e) {
                                    done.reject(e);
                                }
                            });
                            return;
                        }
                        release.run();
                        AuthUser caller = confirmed.caller;
                        if (caller == null || !caller.isEnabled()) {
                            throw new AuthRejection(AuthErrorCode.INVALID_TOKEN.getStatus(),
                                    AuthErrorCode.INVALID_TOKEN.getCode(), "the session is no longer valid");
                        }
                        issue(req, res, caller, owner != null ? owner : caller, spec, action);
                        done.resolve();
                    } catch (Throwable e) {
                        done.reject(storeError(e));
                    }
                });
            }
        });
    }

    /** The insertion through the issuance core, the issue audit event and the 201 with the plaintext key. */
    private void issue(Request req, Response res, AuthUser caller, AuthUser owner, Spec spec, String action) {
        ApiKeyStore keys = auth.apiKeyStore();
        if (keys == null) {
            throw new HttpException(404);
        }
        IssuedApiKey issued;
        try {
            issued = ((DefaultAuthApiKeys) auth.apiKeys()).issueForLifetime(keys, owner, spec.name,
                    spec.lifetimeMillis, spec.scopes, caller.getId());
        } catch (AuthArgumentException gone) { // deleted while the issuance waited
            if (owner.getId() != null && owner.getId().equals(caller.getId())) {
                throw new AuthRejection(AuthErrorCode.INVALID_TOKEN.getStatus(), AuthErrorCode.INVALID_TOKEN.getCode(),
                        "the user no longer exists");
            }
            throw new HttpException(404, "the owner no longer exists");
        }
        auth.audit().emit(auth.audit().event(AuditEvent.API_KEY_ISSUED, action, req, caller.getId(), null,
                caller.isServiceAccount()).targetId(owner.getId()).detail("apiKeyId", issued.getKey().getId())
                .detail("issuedByUserId", caller.getId()).build());
        Map<String, Object> out = keyJson(issued.getKey());
        out.put("key", issued.getPlainKey());
        res.status(201).header("Cache-Control", "no-store").type("application/json; charset=utf-8")
                .send(MiniJson.write(out));
    }

    /** The JSON of a key's metadata: no secret and no hash. */
    static Map<String, Object> keyJson(ApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("prefix", k.getPrefix());
        m.put("name", k.getName());
        m.put("ownerUserId", k.getOwnerUserId());
        List<String> scopes = new ArrayList<>(k.getScopes());
        Collections.sort(scopes);
        m.put("scopes", new ArrayList<Object>(scopes));
        m.put("expiresAt", k.getExpiresAt());
        m.put("createdAt", k.getCreatedAt());
        m.put("lastUsedAt", k.getLastUsedAt());
        m.put("issuedByUserId", k.getIssuedByUserId());
        m.put("status", k.getStatus().name().toLowerCase(Locale.ROOT));
        return m;
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private interface StoreCall<T> {
        T run() throws Exception;
    }

    private static <T> T call(StoreCall<T> c) {
        try {
            return c.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthUnavailableException("the store failed", e);
        }
    }

    private void continueLater(Deferred done, Runnable work) {
        AdminEndpoints.Continuation c = new AdminEndpoints.Continuation(done, work);
        try {
            auth.adminExecutor().execute(c);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            c.abandon(); // rejects done, which releases the hold
        }
    }

    /** A checked store exception of a slot step is 503; the auth and Core answers keep their status. */
    private static Throwable storeError(Throwable e) {
        if (e instanceof Exception && !(e instanceof RuntimeException)) {
            return new AuthUnavailableException("the store failed", (Exception) e);
        }
        return e;
    }
}
