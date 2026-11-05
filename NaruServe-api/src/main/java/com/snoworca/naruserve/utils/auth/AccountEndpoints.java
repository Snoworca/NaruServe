package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.AsyncRouteHandler;
import com.snoworca.naruserve.Cookie;
import com.snoworca.naruserve.Deferred;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Next;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.internal.AuthCookies;
import com.snoworca.naruserve.utils.auth.internal.ConstantTime;
import com.snoworca.naruserve.utils.auth.internal.EventCalls;
import com.snoworca.naruserve.utils.auth.internal.FailureFloor;
import com.snoworca.naruserve.utils.auth.internal.HashSlots;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.internal.Origins;
import com.snoworca.naruserve.utils.auth.internal.TransportCredentials;
import com.snoworca.naruserve.utils.auth.internal.Usernames;
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The built-in account endpoints of {@code auth.routes()} (00 R45), mounted after the app's own routes on that router:
 * {@code POST /login}, {@code POST /refresh}, {@code POST /logout}, {@code POST /password}, {@code POST /setup},
 * {@code POST /register} with {@code .register(true)} and, with {@code .apiKeys(store)}, the three {@code /me/apikeys}
 * routes. The handlers that hash a password (login, password, register, setup) are async, so a request waiting for a
 * hash slot holds no worker thread. Request bodies are read with
 * the app's JSON codec; responses are JSON written without it. The transport check, the multiple-credential rule, the
 * key procedure and the JSON body rule run in the auth middleware before these handlers (FR-FLOW-001); login, register
 * and setup start with their Origin step, and logout checks CSRF and Origin on the cookie identity it uses.
 */
// @req FR-FLOW-004
// @req FR-FLOW-003
// @req SEC-FLOW-010
// @req FR-FLOW-007
// @req SEC-AUTH-003
// @req FR-FLOW-005
// @req SEC-FLOW-014
// @req FR-FLOW-001
final class AccountEndpoints {

    /** The device-token request header (HEADER and BOTH). */
    static final String DEVICE_HEADER = "Naru-Device";

    private final Auth auth;
    private final KeyIssuance issuance;
    private final java.util.concurrent.atomic.AtomicBoolean throttleWarned = new java.util.concurrent.atomic.AtomicBoolean();
    private final Object setupLock = new Object();

    AccountEndpoints(Auth auth) {
        this.auth = auth;
        this.issuance = new KeyIssuance(auth);
    }

    /**
     * Registers the account endpoints on {@code r} (IR-FLOW-002): {@code POST /login}, {@code POST /refresh},
     * {@code POST /logout}, {@code GET /me}, {@code PATCH /me}, {@code POST /password}, {@code POST /register} only
     * with {@code .register(true)}, {@code POST /setup}, and with {@code .apiKeys(store)} {@code GET /me/apikeys},
     * {@code POST /me/apikeys} and {@code DELETE /me/apikeys/:id}. The handlers that hash are async.
     */
    // @req FR-APIKEY-001
    // @req IR-FLOW-002
    // @req FR-APIKEY-003
    // @req FR-FLOW-006
    void register(Router r, boolean registration, @Nullable ApiKeyStore keys) {
        r.methodAsync("POST", "/login", (AsyncRouteHandler) this::login);
        r.post("/refresh", this::refresh);
        r.post("/logout", this::logout);
        r.get("/me", auth.authenticated(), this::me);
        r.patch("/me", auth.authenticated(), this::patchMe);
        r.methodAsync("POST", "/password", (AsyncRouteHandler) this::changePassword);
        if (registration) {
            r.methodAsync("POST", "/register", (AsyncRouteHandler) this::register);
        }
        r.methodAsync("POST", "/setup", (AsyncRouteHandler) this::setup);
        if (keys != null) {
            r.get("/me/apikeys", auth.authenticated(), this::listOwnKeys);
            r.methodAsync("POST", "/me/apikeys", (AsyncRouteHandler) this::issueOwnKey);
            r.delete("/me/apikeys/:id", auth.authenticated(), this::deleteOwnKey);
        }
    }

    // ------------------------------------------------------------------------------------------------- shared

    /**
     * The Origin step of login, register and setup (00 R29 (c), (e)): HEADER always delivers in the body; COOKIE
     * needs the request's own origin or a trusted one (else 403 {@code csrf_failed}, nothing planted) and delivers by
     * cookies; BOTH delivers by cookies when the Origin passes and in the body otherwise. No CSRF token is read.
     */
    // @req SEC-FLOW-007
    // @req FR-FLOW-002
    private boolean cookieDelivery(Request req) {
        TokenTransport t = auth.transport();
        if (t == TokenTransport.HEADER) {
            return false;
        }
        boolean origin = Origins.passes(req, auth.trustedOrigins());
        if (t == TokenTransport.COOKIE && !origin) {
            boolean none = req.header("Origin") == null && req.header("Referer") == null; // OPS-AUTH-022 AC-5
            throw new AuthRejection(AuthErrorCode.CSRF_FAILED.getStatus(), AuthErrorCode.CSRF_FAILED.getCode(), none
                    ? "no Origin or Referer header: COOKIE mode sends auth cookies only to a request from a trusted "
                    + "page (browsers send Origin); a curl or server client sends Origin itself, or the app builds "
                    + "with TokenTransport.HEADER or BOTH"
                    : "the Origin of this request may not receive auth cookies");
        }
        return origin;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Request req) {
        return (Map<String, Object>) jsonObject(parse(req));
    }

    /** OPS-AUTH-014 AC-1: one message, as a codec such as Gson reports an array decoded into a Map as a parse failure. */
    static final String NOT_AN_OBJECT = "the request body must be a JSON object";

    /** OPS-AUTH-012 AC-3: the parsed body as an object, else a 400 that says what is wrong with it. */
    // @req OPS-AUTH-012
    static Object jsonObject(@Nullable Object o) {
        if (!(o instanceof Map)) {
            throw new ShownError(400, NOT_AN_OBJECT);
        }
        return o;
    }

    /** OPS-AUTH-025 AC-3: the 415 message; JSON in another charset says so. */
    static String unsupportedMediaType(Request req) {
        String type = req.header("Content-Type");
        String lower = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
        int cs = lower.indexOf("charset=");
        if (lower.startsWith("application/json") && cs >= 0) {
            return "auth endpoints read JSON in UTF-8: send Content-Type: application/json (or charset=utf-8), not "
                    + type.substring(cs).trim();
        }
        return "auth endpoints accept only application/json bodies";
    }

    /** {@code req.body(Map.class)}, with an unparsable body named in its 400 (OPS-AUTH-012 AC-3). */
    // @req OPS-AUTH-012
    static @Nullable Object parse(Request req) {
        try {
            return req.body(Map.class);
        } catch (HttpException e) {
            if (e.status() == 400 && !(e instanceof ShownError)) {
                throw new ShownError(400, NOT_AN_OBJECT);
            }
            if (e.status() == 415 && !(e instanceof ShownError)) { // OPS-AUTH-016 AC-3: a bodiless POST
                throw new ShownError(415, unsupportedMediaType(req));
            }
            if (e.status() == 413 && !(e instanceof ShownError)) { // OPS-AUTH-025 AC-7
                throw new ShownError(413, "the request body is over the app's JSON body limit; raise it with "
                        + "app.jsonLimit(bytes)");
            }
            throw e;
        }
    }

    /**
     * The JSON body of a request that may have none; null for a bodiless request: no {@code Transfer-Encoding} and a
     * {@code Content-Length} of 0, or neither a {@code Content-Length} nor a {@code Content-Type}.
     */
    private static @Nullable Map<String, Object> optionalBody(Request req) {
        String length = req.header("Content-Length");
        boolean chunked = req.header("Transfer-Encoding") != null;
        if (!chunked && (length != null ? length.trim().equals("0") : req.header("Content-Type") == null)) {
            return null;
        }
        return body(req);
    }

    private static String string(Map<String, Object> body, String name) {
        Object v = body.get(name);
        if (!(v instanceof String)) {
            throw new ShownError(400, name + " must be a JSON string");
        }
        return (String) v;
    }

    /** OPS-AUTH-006 AC-1: register and setup answer a missing password with login's text. */
    private static String passwordField(Map<String, Object> body) {
        Object v = body.get("password");
        if (!(v instanceof String)) {
            throw new ShownError(400, "password must be a non-empty JSON string");
        }
        return (String) v;
    }

    private static String normalized(String username) {
        try {
            return Usernames.normalize(username);
        } catch (IllegalArgumentException bad) {
            throw new ShownError(400, "invalid username: " + bad.getMessage()); // OPS-AUTH-025 AC-4
        }
    }

    /** FR-TOKEN-004: the policy and breach check of a new password, before any hash; 422 when refused. */
    // @req FR-TOKEN-004
    private void checkNewPassword(char[] pw) {
        String problem;
        try {
            problem = auth.newPasswords().problem(pw);
        } catch (RuntimeException e) {
            Arrays.fill(pw, '\0');
            throw e;
        }
        if (problem != null) {
            Arrays.fill(pw, '\0');
            throw new ShownError(422, problem);
        }
    }

    private void continueLater(Deferred done, Runnable work) {
        AdminEndpoints.Continuation c = new AdminEndpoints.Continuation(done, work);
        try {
            auth.adminExecutor().execute(c);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            c.abandon();
        }
    }

    /** A new hash of {@code pw} in a slot, then {@code then} with it off the hash thread; the array is wiped. */
    private void hashThen(final char[] pw, final Deferred done, final HashedWork then) {
        auth.hashSlots().submit(new Callable<String>() {
            @Override
            public String call() {
                return auth.passwords().hasher().hash(pw);
            }
        }, new HashSlots.Callback<String>() {
            @Override
            public void done(@Nullable String hash, @Nullable Throwable error) {
                Arrays.fill(pw, '\0');
                if (error != null || hash == null) {
                    done.reject(error != null ? error : new IllegalStateException("no hash"));
                    return;
                }
                continueLater(done, () -> {
                    try {
                        then.run(hash);
                    } catch (Throwable e) {
                        done.reject(e);
                    }
                });
            }
        });
    }

    /** Work that needs the new hash. */
    private interface HashedWork {
        void run(String hash) throws Exception;
    }

    private AuthEventContext ctx(@Nullable UserOrigin origin, Request req, @Nullable String actorId) {
        return new AuthEventContext(origin, req, actorId, auth.time().nowMillis());
    }

    // ------------------------------------------------------------------------------------------------- login

    /**
     * {@code POST /login} {@code {"username", "password"}} (00 R34, R38): the Origin step, the body checks (a missing,
     * non-string or empty password is 400 without hashing), then the password-login sequence (throttle and device
     * trust, one constant-work hash in a slot, the optional lockout), beforeLogin with the verified user, a new pair in
     * a new family plus a device token through the delivery rule. Every credential failure is the same 401
     * {@code invalid_credentials}, and every failure of an attempt that reached the hash waits for the failure floor.
     */
    // @req FR-FLOW-004
    // @req SEC-TOKEN-009
    private Deferred login(final Request req, final Response res, Next next) throws Exception {
        final boolean cookies = cookieDelivery(req);
        Map<String, Object> body = body(req);
        final String username = string(body, "username");
        Object pwValue = body.get("password");
        if (!(pwValue instanceof String) || ((String) pwValue).isEmpty()) {
            throw new ShownError(400, "password must be a non-empty JSON string");
        }
        final char[] pw = ((String) pwValue).toCharArray();
        final FailureFloor floor = auth.failureFloor();
        final long start = floor.now();
        final Deferred done = Deferred.pending();
        try {
            auth.passwordLogin().attempt(username, pw, req.remoteAddress(), deviceToken(req), ctx(null, req, null),
                    outcome -> {
                        try {
                            loginOutcome(req, res, outcome, cookies, floor, start, done);
                        } catch (Throwable e) {
                            done.reject(e);
                        }
                    });
        } catch (AuthRejection | AuthUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // a throttle, device trust or user store failure before the hash step: 503, not an attempt (no floor)
            throw new AuthUnavailableException("the login could not be checked", e);
        }
        return done;
    }

    private void loginOutcome(final Request req, final Response res, PasswordLogin.Outcome o, boolean cookies,
            FailureFloor floor, long start, final Deferred done) {
        switch (o.kind) {
            case THROTTLED: {
                long seconds = Math.max(1L, (o.retryAfterMillis + 999L) / 1000L);
                if (auth.mode() == AuthMode.DEV && throttleWarned.compareAndSet(false, true)) { // OPS-AUTH-021 AC-13
                    auth.log().warn("auth: a login was refused with 429 too_many_attempts (Retry-After " + seconds
                            + " s) after too many failed logins of one name; in tests, raise the limits with "
                            + ".loginFailureLimits(...) or build a fresh Auth per test. Logged once");
                }
                auth.respond(req, res, AuthErrorCode.TOO_MANY_ATTEMPTS.getStatus(),
                        AuthErrorCode.TOO_MANY_ATTEMPTS.getCode(), "too many login attempts; try again later", seconds);
                done.resolve();
                return;
            }
            case INVALID_CREDENTIALS:
                floored(floor, start, done, () -> invalidCredentials(req, res));
                return;
            case UNAVAILABLE: {
                final Throwable error = o.error;
                floored(floor, start, done, () -> {
                    auth.log().error("auth: a login could not be completed; answering 503", error);
                    AuthUnavailableException u = error instanceof AuthUnavailableException
                            ? (AuthUnavailableException) error : null;
                    auth.respond(req, res, 503, u != null ? u.getCode() : AuthUnavailableException.SERVICE_UNAVAILABLE,
                            null, u != null ? u.getRetryAfterSeconds() : null);
                });
                return;
            }
            default:
                break;
        }
        AuthUser user = o.user;
        if (user == null) {
            floored(floor, start, done, () -> invalidCredentials(req, res));
            return;
        }
        final AuthUser seen = EventCalls.copy(user);
        final AuthEventContext ctx = ctx(null, req, user.getId());
        TokenPair pair;
        try {
            for (final AuthEvents l : auth.eventListeners()) {
                EventCalls.before(auth.log(), "beforeLogin", () -> l.beforeLogin(seen, ctx));
            }
            pair = auth.tokens().issue(user);
        } catch (final AuthRejection r) {
            floored(floor, start, done, () -> auth.respond(req, res, r.getStatus(), r.getCode(), r.getMessage(),
                    r.getRetryAfterSeconds()));
            return;
        } catch (final RuntimeException e) {
            floored(floor, start, done, () -> {
                auth.log().error("auth: a login could not be completed; answering 503", e);
                AuthUnavailableException u = e instanceof AuthUnavailableException ? (AuthUnavailableException) e
                        : null;
                auth.respond(req, res, 503, u != null ? u.getCode() : AuthUnavailableException.SERVICE_UNAVAILABLE,
                        null, u != null ? u.getRetryAfterSeconds() : null);
            });
            return;
        }
        String device = o.deviceToken == null ? null : o.deviceToken.token();
        auth.tokenDelivery().deliver(req, res, 200, pair, device, cookies, auth.log());
        for (final AuthEvents l : auth.eventListeners()) {
            EventCalls.after(auth.log(), "onLogin", () -> l.onLogin(seen, ctx));
        }
        done.resolve();
    }

    private void invalidCredentials(Request req, Response res) {
        auth.respond(req, res, AuthErrorCode.INVALID_CREDENTIALS.getStatus(),
                AuthErrorCode.INVALID_CREDENTIALS.getCode(), "the username or password is wrong", null);
    }

    /** Sends a failure of an attempt that reached the hash no earlier than the failure floor. */
    private static void floored(FailureFloor floor, long start, final Deferred done, final Runnable send) {
        floor.send(start, () -> {
            try {
                send.run();
                done.resolve();
            } catch (Throwable e) {
                done.reject(e);
            }
        });
    }

    /**
     * The device token of a login (SEC-FLOW-013 AC-7): HEADER reads only the {@code Naru-Device} header, COOKIE only
     * one {@code naru_device} cookie, BOTH the header and else the cookie.
     */
    // @req SEC-FLOW-013
    private @Nullable String deviceToken(Request req) {
        TokenTransport t = auth.transport();
        String header = t == TokenTransport.COOKIE ? null : req.header(DEVICE_HEADER);
        if (header != null && !header.trim().isEmpty()) {
            return header.trim();
        }
        return t == TokenTransport.HEADER ? null : single(req, AuthCookies.DEVICE);
    }

    private static @Nullable String single(Request req, String name) {
        String value = null;
        int n = 0;
        Cookie[] all;
        try {
            all = req.getCookies();
        } catch (UnsupportedOperationException none) {
            return null;
        }
        for (Cookie c : all) {
            if (name.equals(c.getName())) {
                n++;
                value = c.getValue();
            }
        }
        return n == 1 ? value : null;
    }

    // ------------------------------------------------------------------------------------------------- refresh

    /**
     * {@code POST /refresh} (00 R29 (a), (e), R45): the refresh token comes from the JSON body {@code refreshToken} in
     * HEADER and BOTH modes, else from the one {@code naru_rt} cookie in COOKIE and BOTH modes (body first; a body token
     * in COOKIE mode is 400 before any CSRF check, and HEADER never reads the cookie). A body token is rotated without
     * CSRF and answered in the body. A cookie token is verified first (signature, expiry, an unrevoked record: a bad one
     * is 401 before any CSRF check), then needs the {@code X-CSRF-Token} of its sub and fam and a passing Origin (else
     * 403 {@code csrf_failed}, nothing used), then is rotated and answered by cookies. Rotation keeps the family; a
     * reused token revokes it. No refresh token is 401 {@code invalid_refresh_token}. An API-key header never gets here:
     * the key procedure answers first in the middleware (FR-FLOW-001).
     */
    // @req FR-FLOW-005
    // @req SEC-FLOW-008
    // @req FR-FLOW-002
    private void refresh(Request req, Response res, Next next) throws Exception {
        TokenTransport t = auth.transport();
        String bodyToken = null;
        Map<String, Object> body = optionalBody(req);
        if (body != null && body.containsKey("refreshToken")) {
            if (t == TokenTransport.COOKIE) {
                throw new ShownError(400, "COOKIE mode reads the refresh token only from the naru_rt cookie");
            }
            bodyToken = string(body, "refreshToken");
        }
        TokenPair pair;
        boolean byCookie;
        if (bodyToken != null) {
            pair = auth.tokens().refresh(bodyToken);
            byCookie = false;
        } else {
            String rt = t == TokenTransport.HEADER ? null : AuthCookies.refreshToken(req);
            if (rt == null) {
                throw missingRefresh(t); // OPS-AUTH-012 AC-2
            }
            JwtClaims c = auth.tokens().refreshIdentity(rt);
            String sub = c == null ? null : c.getSubject();
            if (c == null || sub == null) {
                throw invalidRefresh();
            }
            requireCsrf(req, new Identity(sub, c.getString("fam")));
            pair = auth.tokens().refresh(rt);
            byCookie = true;
        }
        boolean cookies = TokenDelivery.byCookies(t, req, auth.trustedOrigins(), byCookie);
        auth.tokenDelivery().deliver(req, res, 200, pair, null, cookies, auth.log());
        refreshed(req, pair.userId());
    }

    /** onTokenRefreshed (origin null) after a rotation; a failing read or listener leaves the answer as it is. */
    private void refreshed(Request req, String userId) {
        if (auth.eventListeners().isEmpty()) {
            return;
        }
        AuthUser u;
        try {
            u = auth.userStore().findById(userId);
        } catch (Exception e) {
            auth.log().error("auth: reading the user for onTokenRefreshed failed; the response is unchanged", e);
            return;
        }
        if (u == null) {
            return;
        }
        final AuthUser seen = EventCalls.copy(u);
        final AuthEventContext ctx = ctx(null, req, userId);
        for (final AuthEvents l : auth.eventListeners()) {
            EventCalls.after(auth.log(), "onTokenRefreshed", () -> l.onTokenRefreshed(seen, ctx));
        }
    }

    /** OPS-AUTH-012 AC-2: no refresh token arrived; the message says where it goes. */
    // @req OPS-AUTH-012
    private static AuthRejection missingRefresh(TokenTransport t) {
        return new AuthRejection(AuthErrorCode.INVALID_REFRESH_TOKEN.getStatus(),
                AuthErrorCode.INVALID_REFRESH_TOKEN.getCode(), t == TokenTransport.COOKIE
                        ? "no refresh token: COOKIE mode reads it from the naru_rt cookie set at login"
                        : "no refresh token: send it as the \"refreshToken\" field of a JSON body");
    }

    private static AuthRejection invalidRefresh() {
        return new AuthRejection(AuthErrorCode.INVALID_REFRESH_TOKEN.getStatus(),
                AuthErrorCode.INVALID_REFRESH_TOKEN.getCode(), "the refresh token is not valid");
    }

    // ------------------------------------------------------------------------------------------------- password

    /** What the slot step of a password change found. */
    private static final class Checked {
        final @Nullable AuthUser user;
        final @Nullable String newHash;

        Checked(@Nullable AuthUser user, @Nullable String newHash) {
            this.user = user;
            this.newHash = newHash;
        }
    }

    /**
     * {@code POST /password} {@code {"currentPassword", "newPassword"}} (00 R37 9th–10th editions, R10 32nd edition), a
     * subject-required endpoint the middleware has already judged like a protected path (principal, CSRF and Origin of
     * a cookie session, JSON body; an API key is 403 {@code api_key_not_allowed} there). The body checks (400), the
     * password policy (422) and the per-user attempt limit (429) come before any hash. In one hash slot the user is
     * re-read (401 {@code invalid_token} when it is gone, disabled or at another ver than the request token), the
     * current password is verified (a wrong one is 401 {@code invalid_credentials} and counts toward the limit) and
     * the new one hashed. Then beforeUserUpdate (origin SELF, may refuse), and under the user write lock and the key
     * owner lock one {@code changePassword(id, oldHash, tokenVer, newHash)}, the atomic hash swap with ver++. A lost
     * race is retried once only when the re-read hash still verifies the current password (a rehash); otherwise 409
     * {@code write_conflict}. On success onUserUpdated and onPasswordChanged, then a new pair in a new family with a new
     * device token, delivered the way the credential came (FR-FLOW-002). Both passwords are wiped.
     */
    // @req SEC-FLOW-014
    // @req REL-IAM-002
    // @req FR-FLOW-002
    private Deferred changePassword(final Request req, final Response res, Next next) throws Exception {
        final AuthPrincipal p = auth.user(req);
        Map<String, Object> body = body(req);
        Object cur = body.get("currentPassword");
        if (!(cur instanceof String) || ((String) cur).isEmpty()) {
            throw new ShownError(400, "currentPassword must be a non-empty JSON string"); // OPS-AUTH-016 AC-2
        }
        final char[] fresh = string(body, "newPassword").toCharArray();
        checkNewPassword(fresh);
        final char[] current = ((String) cur).toCharArray();
        final String id = p.getId();
        final long ver = p.tokenVersion();
        final String confirm = com.snoworca.naruserve.utils.auth.internal.ThrottleKeys.passwordConfirm(id);
        // one confirmation per user at a time, shared with the key issuance endpoints and checkPassword: parallel
        // guesses cannot all pass the limit check before a failure is counted; released when the answer settles
        final Object hold = auth.confirmationHolds().tryHold(id);
        if (hold == null) {
            wipe(current, fresh);
            throw ConfirmationHolds.busy();
        }
        final Deferred done = Deferred.pending();
        final Runnable release = () -> auth.confirmationHolds().release(id, hold);
        done.onRejected(e -> release.run()); // before the error layer writes the rejection
        done.onSettled(release);
        try {
            changePasswordHeld(req, res, id, ver, confirm, current, fresh, done, release);
        } catch (Throwable e) {
            wipe(current, fresh);
            done.reject(e);
        }
        return done;
    }

    /** {@link #changePassword} while the user's confirmation hold is taken; {@code done} settles the answer. */
    private void changePasswordHeld(final Request req, final Response res, final String id, final long ver,
            final String confirm, final char[] current, final char[] fresh, final Deferred done,
            final Runnable release) {
        long blocked;
        try {
            blocked = storeCall(() -> auth.loginThrottle().retryAfter(confirm, DefaultAuthUsers.CONFIRM_MAX,
                    DefaultAuthUsers.CONFIRM_WINDOW_MILLIS));
        } catch (RuntimeException e) {
            wipe(current, fresh);
            throw e;
        }
        if (blocked > 0) {
            wipe(current, fresh);
            throw DefaultAuthUsers.tooMany(blocked);
        }
        final boolean byCookie = auth.transport() == TokenTransport.COOKIE || auth.transport() == TokenTransport.BOTH
                && bearer(req) == null;
        checkInSlot(id, ver, current, fresh, true, true, done, checked -> {
            if (checked.user == null || checked.newHash == null) {
                storeCall(() -> {
                    auth.loginThrottle().recordFailure(confirm, DefaultAuthUsers.CONFIRM_MAX,
                            DefaultAuthUsers.CONFIRM_WINDOW_MILLIS);
                    return Boolean.TRUE;
                });
                release.run(); // counted
                throw new AuthRejection(AuthErrorCode.INVALID_CREDENTIALS.getStatus(),
                        AuthErrorCode.INVALID_CREDENTIALS.getCode(), "the current password is wrong");
            }
            release.run(); // the current password verified: nothing more to count
            final AuthUser before = EventCalls.copy(checked.user);
            final AuthEventContext ctx = ctx(UserOrigin.SELF, req, id);
            PasswordChanges.beforeUpdate(auth, before, ctx);
            final String newHash = checked.newHash;
            final String oldHash = checked.user.getPasswordHash();
            if (swap(id, oldHash, ver, newHash)) {
                wipe(current, fresh);
                changed(req, res, id, before, ctx, byCookie, done);
                return;
            }
            // a lost race: re-read in a slot; retry once only when the new hash still verifies the current password
            checkInSlot(id, ver, current, fresh, false, false, done, again -> {
                try {
                    AuthUser a = again.user;
                    if (a == null) {
                        throw conflict();
                    }
                    if (!swap(id, a.getPasswordHash(), ver, newHash)) {
                        throw conflict();
                    }
                    changed(req, res, id, before, ctx, byCookie, done);
                } finally {
                    wipe(current, fresh);
                }
            });
        });
    }

    /**
     * One slot step of a password change: the re-read of {@code id}, the session check against {@code ver} (enabled,
     * same ver; 401 otherwise), the verify of {@code current} and, with {@code hashNext}, the hash of {@code next}. The
     * user is null when the current password did not verify. On the first step the session is checked before the
     * verify; on the retry the verify comes first, so a hash another write replaced is 409 even if that write also
     * raised ver. {@code then} runs off the hash thread; its failure rejects {@code done} and wipes both arrays.
     */
    private void checkInSlot(final String id, final long ver, final char[] current, final char[] next,
            final boolean hashNext, final boolean sessionFirst, final Deferred done, final CheckedWork then) {
        auth.hashSlots().submit(new Callable<Checked>() {
            @Override
            public Checked call() throws Exception {
                AuthUser u = auth.userStore().findById(id);
                if (sessionFirst) {
                    requireSession(u, ver);
                }
                String stored = u == null || u.isServiceAccount() ? null : u.getPasswordHash();
                if (!auth.passwords().verifyForLogin(current, stored).matched()) {
                    if (!sessionFirst) {
                        throw conflict();
                    }
                    return new Checked(null, null);
                }
                if (!sessionFirst) {
                    requireSession(u, ver);
                }
                return new Checked(u, hashNext ? auth.passwords().hasher().hash(next) : null);
            }
        }, new HashSlots.Callback<Checked>() {
            @Override
            public void done(@Nullable final Checked checked, @Nullable Throwable error) {
                if (error != null || checked == null) {
                    wipe(current, next);
                    done.reject(storeError(error != null ? error : new IllegalStateException("no result")));
                    return;
                }
                continueLater(done, () -> {
                    try {
                        then.run(checked);
                    } catch (Throwable e) {
                        wipe(current, next);
                        done.reject(storeError(e));
                    }
                });
            }
        });
    }

    /** Work after a slot step. */
    private interface CheckedWork {
        void run(Checked checked) throws Exception;
    }

    /** A store exception of a slot step is 503; the auth answers keep their status. */
    private static Throwable storeError(Throwable e) {
        if (e instanceof Exception && !(e instanceof AuthRejection) && !(e instanceof AuthUnavailableException)
                && !(e instanceof HttpException) && !(e instanceof RuntimeException)) {
            return new AuthUnavailableException("the user store failed", (Exception) e);
        }
        return e;
    }

    /** The atomic hash swap with ver++ under the user write lock and the key owner lock (REL-IAM-002, REL-APIKEY-003). */
    private boolean swap(final String id, @Nullable final String oldHash, final long ver, final String newHash) {
        return verBump(id, () -> auth.userStore().changePassword(id, oldHash, ver, newHash));
    }

    /** The success of a password change: the events, then a new pair, device token and CSRF token. */
    private void changed(Request req, Response res, String id, AuthUser before, AuthEventContext ctx,
            boolean byCookie, Deferred done) {
        auth.userRevoked(id);
        AuthUser now = storeCall(() -> auth.userStore().findById(id));
        if (now == null) {
            throw new AuthRejection(AuthErrorCode.INVALID_TOKEN.getStatus(), AuthErrorCode.INVALID_TOKEN.getCode(),
                    "the user no longer exists");
        }
        PasswordChanges.afterUpdate(auth, before, now, ctx);
        TokenPair pair = auth.tokens().issue(now);
        com.snoworca.naruserve.utils.auth.internal.DeviceTokens.Issued device = auth.deviceTokens().issue(id,
                now.getTokenVersion(), now.getUsername());
        try {
            auth.deviceTrust().issued(device.content());
        } catch (Exception e) {
            auth.log().error("auth: recording the new device token failed; it is still sent", e);
        }
        boolean cookies = TokenDelivery.byCookies(auth.transport(), req, auth.trustedOrigins(), byCookie);
        auth.tokenDelivery().deliver(req, res, 200, pair, device.token(), cookies, auth.log());
        done.resolve();
    }

    private static void requireSession(@Nullable AuthUser u, long tokenVersion) {
        if (u == null || !u.isEnabled() || u.getTokenVersion() != tokenVersion) {
            throw new AuthRejection(AuthErrorCode.INVALID_TOKEN.getStatus(), AuthErrorCode.INVALID_TOKEN.getCode(),
                    "the session is no longer valid");
        }
    }

    private static AuthRejection conflict() {
        return new AuthRejection(AuthErrorCode.WRITE_CONFLICT.getStatus(), AuthErrorCode.WRITE_CONFLICT.getCode(),
                "the password was changed by another write; try again");
    }

    private static void wipe(char[] a, char @Nullable [] b) {
        Arrays.fill(a, '\0');
        if (b != null) {
            Arrays.fill(b, '\0');
        }
    }

    // ------------------------------------------------------------------------------------------------- logout

    /** The identity a logout found: the user and the family of the token it came from. */
    private static final class Identity {
        final String userId;
        final @Nullable String familyId;

        Identity(String userId, @Nullable String familyId) {
            this.userId = userId;
            this.familyId = familyId;
        }
    }

    /**
     * {@code POST /logout[?all=true]} (00 R45 32nd edition): the identity comes from the decision table ① a body
     * refresh token (HEADER, BOTH), ② else exactly one {@code naru_rt} with CSRF and Origin on its sub and fam (COOKIE,
     * BOTH), ③ else a valid Bearer token without CSRF or a valid {@code naru_at} with CSRF and Origin, ④ else none. A
     * later candidate is read only when the earlier one was not sent; a sent invalid one ends the search with no
     * identity. Valid means signature, expiry and the user's current ver, and for a refresh token an unused, unrevoked
     * record (never marked used here). With an identity: revoke its family (① also revokes the same user's
     * {@code naru_rt} family), or with {@code ?all=true} raise the user's ver once; then onLogout and 204. Without:
     * 204, or for {@code ?all=true} 401 {@code invalid_token} when a token was read and 401 {@code unauthenticated}
     * when none was. Every 204 in COOKIE and BOTH clears the auth cookies. A store error is 503.
     */
    // @req SEC-FLOW-010
    // @req FR-FLOW-003
    private void logout(Request req, Response res, Next next) throws Exception {
        TokenTransport t = auth.transport();
        String allValue = req.query("all");
        if (allValue != null && !"true".equals(allValue) && !"false".equals(allValue)) { // OPS-AUTH-021 AC-1
            throw new ShownError(400, "all must be true or false; POST /auth/logout?all=true ends every token of the "
                    + "user on every device" + ("true".equalsIgnoreCase(allValue) || "false".equalsIgnoreCase(allValue)
                    ? " (lower-case true or false; got " + allValue + ")" : "")); // OPS-AUTH-030 AC-7
        }
        boolean all = "true".equals(allValue);
        String bodyToken = null;
        Map<String, Object> body = optionalBody(req);
        if (body != null && body.containsKey("all")) {
            throw new ShownError(400, "all is a query parameter: POST /auth/logout?all=true");
        }
        if (body != null) {
            for (String k : body.keySet()) { // OPS-AUTH-028 AC-2
                if (!k.equals("refreshToken")) {
                    throw new ShownError(400, "unknown field " + k + "; logout reads only refreshToken");
                }
            }
        }
        if (body != null && body.containsKey("refreshToken")) {
            if (t == TokenTransport.COOKIE) {
                throw new ShownError(400, "COOKIE mode reads the refresh token only from the naru_rt cookie");
            }
            bodyToken = string(body, "refreshToken");
        }
        String rtCookie = t == TokenTransport.HEADER ? null : AuthCookies.refreshToken(req);
        String bearer = t == TokenTransport.COOKIE ? null : bearer(req);
        String atCookie = t == TokenTransport.HEADER ? null : single(req, TransportCredentials.ACCESS_COOKIE);
        boolean read = bodyToken != null || rtCookie != null || bearer != null || atCookie != null;
        Identity id;
        Identity alsoRevoke = null;
        if (bodyToken != null) {
            id = refreshIdentity(bodyToken); // ①
            if (id != null && rtCookie != null) {
                Identity g = refreshIdentity(rtCookie);
                if (g != null && g.userId.equals(id.userId)) {
                    alsoRevoke = g; // the same user's cookie family, without CSRF (the body holder could ?all=true)
                }
            }
        } else if (rtCookie != null) {
            id = refreshIdentity(rtCookie); // ②
            if (id != null) {
                requireCsrf(req, id);
            }
        } else if (bearer != null) {
            id = accessIdentity(bearer); // ③ Bearer: no CSRF
        } else if (atCookie != null) {
            id = accessIdentity(atCookie); // ③ naru_at: CSRF and Origin
            if (id != null) {
                requireCsrf(req, id);
            }
        } else {
            id = null; // ④
        }
        if (id == null) {
            if (all) {
                AuthErrorCode code = read ? AuthErrorCode.INVALID_TOKEN : AuthErrorCode.UNAUTHENTICATED;
                auth.respond(req, res, code.getStatus(), code.getCode(), read ? "the token is not valid or has expired; renew it with the refresh endpoint (POST /auth/refresh by default) or log in again"
                        : "logout?all=true needs a token", null);
                return;
            }
            endSession(req, res);
            return;
        }
        final String userId = id.userId;
        if (all) {
            boolean bumped = verBump(userId, () -> auth.userStore().bumpTokenVersion(userId));
            if (!bumped) {
                throw new AuthUnavailableException("the token version of the user could not be raised");
            }
            auth.userRevoked(userId);
        } else {
            revokeFamily(id.familyId);
            if (alsoRevoke != null && !java.util.Objects.equals(alsoRevoke.familyId, id.familyId)) {
                revokeFamily(alsoRevoke.familyId);
            }
        }
        final boolean allDevices = all;
        final AuthEventContext ctx = ctx(null, req, userId);
        for (final AuthEvents l : auth.eventListeners()) {
            EventCalls.after(auth.log(), "onLogout", () -> l.onLogout(userId, allDevices, ctx));
        }
        endSession(req, res);
    }

    private void revokeFamily(@Nullable final String familyId) {
        if (familyId == null) {
            return;
        }
        storeCall(() -> {
            auth.refreshTokens().revokeFamily(familyId);
            return Boolean.TRUE;
        });
    }

    private void endSession(Request req, Response res) {
        if (auth.transport() != TokenTransport.HEADER) {
            auth.authCookies().clearSession(req, res);
        }
        res.header("Cache-Control", "no-store");
        res.sendStatus(204);
    }

    private void requireCsrf(Request req, Identity id) {
        if (!auth.csrfTokens().passes(req, id.userId, id.familyId) || !Origins.passes(req, auth.trustedOrigins())) {
            throw new AuthRejection(AuthErrorCode.CSRF_FAILED.getStatus(), AuthErrorCode.CSRF_FAILED.getCode(),
                    auth.csrfFailure(req, id.userId, id.familyId)); // OPS-AUTH-025 AC-2
        }
    }

    /** The Bearer token of the one {@code Authorization} header, or null. */
    private static @Nullable String bearer(Request req) {
        List<String> h = req.headers("Authorization");
        if (h.size() != 1 || !ApiKeyCredentials.isScheme(h.get(0), "Bearer")) {
            return null;
        }
        String v = h.get(0).trim();
        int sp = v.indexOf(' ');
        return sp < 0 ? "" : v.substring(sp + 1).trim();
    }

    /** A refresh token's identity: verified, an unused and unrevoked record (never marked used), the current ver. */
    private @Nullable Identity refreshIdentity(String token) {
        JwtClaims c = auth.tokens().logoutIdentity(token);
        return c == null ? null : current(c);
    }

    /** An access token's identity: signature, expiry and the user's current ver. */
    private @Nullable Identity accessIdentity(String token) {
        JwtClaims c;
        try {
            c = auth.tokens().verifyAccess(token);
        } catch (AuthRejection invalid) {
            return null;
        }
        return current(c);
    }

    private @Nullable Identity current(JwtClaims c) {
        final String sub = c.getSubject();
        Long ver = c.getLong("ver");
        if (sub == null || ver == null) {
            return null;
        }
        AuthUser u = storeCall(() -> auth.userStore().findById(sub));
        if (u == null || u.getTokenVersion() != ver) {
            return null;
        }
        return new Identity(sub, c.getString("fam"));
    }

    /** One store call; a store exception other than the auth ones is 503. */
    private interface StoreCall<T> {
        T run() throws Exception;
    }

    private static <T> T storeCall(StoreCall<T> c) {
        try {
            return c.run();
        } catch (AuthUnavailableException | com.snoworca.naruserve.utils.auth.internal.AuthArgumentException
                | HttpException e) { // HttpException covers AuthRejection and DuplicateUserException
            throw e; // the auth answers (409 for a name the store found taken, 400) keep their status
        } catch (Exception e) {
            throw new AuthUnavailableException("a store failed", e);
        }
    }

    /**
     * An auth-triggered {@code ver++} of {@code userId} (REL-APIKEY-003): the R37 user write lock, then inside it the
     * key owner lock with the future-key deletion before {@code write}; a store failure is 503.
     */
    // @req REL-APIKEY-003
    private <T> T verBump(final String userId, final StoreCall<T> write) {
        java.util.concurrent.locks.ReentrantLock lock = com.snoworca.naruserve.utils.auth.internal.UserLocks.of(userId);
        lock.lock();
        try {
            return storeCall(() -> auth.apiKeyOwners().bump(userId, write::run));
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------------- register

    /**
     * {@code POST /register} {@code {"username", "password"}}, served only with {@code .register(true)}: the Origin
     * step, the body checks, the password policy, 409 for a taken name, beforeUserCreate with origin
     * {@code REGISTER}, the hash in a slot, the create, onUserCreated, then 201 with a new pair through the delivery
     * rule.
     */
    // @req FR-FLOW-007
    private Deferred register(final Request req, final Response res, Next next) throws Exception {
        final boolean cookies = cookieDelivery(req);
        Map<String, Object> body = body(req);
        final String name = normalized(string(body, "username"));
        final char[] pw = passwordField(body).toCharArray();
        checkNewPassword(pw);
        final UserStore store = auth.userStore();
        AuthUser taken;
        try {
            taken = storeCall(() -> store.findByUsername(name));
        } catch (RuntimeException e) {
            Arrays.fill(pw, '\0');
            throw e;
        }
        if (taken != null) {
            Arrays.fill(pw, '\0');
            throw new DuplicateUserException("the username is taken");
        }
        final AuthUser candidate = AuthUser.builder().username(name).build();
        final AuthEventContext ctx = ctx(UserOrigin.REGISTER, req, null);
        try {
            for (final AuthEvents l : auth.eventListeners()) {
                EventCalls.before(auth.log(), "beforeUserCreate", () -> l.beforeUserCreate(candidate, ctx));
            }
        } catch (RuntimeException e) {
            Arrays.fill(pw, '\0');
            throw e;
        }
        final Deferred done = Deferred.pending();
        hashThen(pw, done, hash -> {
            AuthUser created = storeCall(() -> store.create(candidate.toBuilder().passwordHash(hash).build()));
            final AuthUser out = EventCalls.copy(created);
            for (final AuthEvents l : auth.eventListeners()) {
                EventCalls.after(auth.log(), "onUserCreated", () -> l.onUserCreated(out, ctx));
            }
            TokenPair pair = auth.tokens().issue(created);
            auth.tokenDelivery().deliver(req, res, 201, pair, null, cookies, auth.log());
            done.resolve();
        });
        return done;
    }

    // ------------------------------------------------------------------------------------------------- setup

    /**
     * {@code POST /setup} {@code {"token", "username", "password"}} (00 §4.4, R31 (6), R41): 404 unless a setup is
     * open (a null-password admin {@code .user} missing under recovery, or {@code .recovery(true)}), checked before the
     * handler's own Origin step (the middleware's transport, credential and body checks still come first); then the
     * Origin step, 403 {@code setup_not_local} for a request that is not local (R33: loopback socket, no forwarding header),
     * 400 for a missing or non-string field, 401 {@code invalid_setup_token} for a wrong token (compared in constant
     * time). A {@code .user} setup must name that user (400 {@code username_mismatch}) and gives its roles; a recovery
     * creates the user with the built-in admin role, or enables an existing one, replaces its password, grants admin
     * and raises its ver (400 {@code service_account_no_password} for a service account). No before-event runs;
     * onUserCreated or onUserUpdated runs with origin SETUP or RECOVERY and a recovery is audited. The first success
     * closes setup for the process; 201 (created) or 200 (recovered) with a new pair through the delivery rule.
     */
    // @req SEC-AUTH-003
    private Deferred setup(final Request req, final Response res, Next next) throws Exception {
        final Auth.PendingSetup open = auth.openSetup();
        if (open == null) { // OPS-AUTH-024 AC-6
            throw new ShownError(404, "no setup is open: POST " + auth.mountPath() + "/setup is served only while the "
                    + "setup token of the start log is pending (a PROD .user admin without a password, or a recovery start with .recovery(true) or "
                    + "NARU_AUTH_RECOVERY=1)"); // OPS-AUTH-029 AC-6
        }
        final boolean cookies = cookieDelivery(req);
        if (!Auth.isLocal(req)) {
            throw new AuthRejection(AuthErrorCode.SETUP_NOT_LOCAL.getStatus(), AuthErrorCode.SETUP_NOT_LOCAL.getCode(),
                    "setup accepts only a local request");
        }
        Map<String, Object> body = body(req);
        Object token = body.get("token");
        if (!(token instanceof String)) {
            throw new ShownError(400, "token must be a JSON string");
        }
        final String username = string(body, "username");
        final String password = passwordField(body);
        if (!ConstantTime.equals(((String) token).getBytes(StandardCharsets.UTF_8),
                open.token.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthRejection(AuthErrorCode.INVALID_SETUP_TOKEN.getStatus(),
                    AuthErrorCode.INVALID_SETUP_TOKEN.getCode(), "the setup token is wrong");
        }
        final String name = normalized(username);
        if (open.username != null && !open.username.equals(name)) {
            throw new AuthRejection(AuthErrorCode.USERNAME_MISMATCH.getStatus(),
                    AuthErrorCode.USERNAME_MISMATCH.getCode(), "this setup creates the user " + open.username);
        }
        final UserStore store = auth.userStore();
        AuthUser existing = open.recovery() ? storeCall(() -> store.findByUsername(name)) : null;
        if (existing != null && existing.isServiceAccount()) {
            throw new AuthRejection(AuthErrorCode.SERVICE_ACCOUNT_NO_PASSWORD.getStatus(),
                    AuthErrorCode.SERVICE_ACCOUNT_NO_PASSWORD.getCode(), "a service account has no password");
        }
        final char[] pw = password.toCharArray();
        checkNewPassword(pw);
        final Deferred done = Deferred.pending();
        hashThen(pw, done, hash -> {
            synchronized (setupLock) {
                if (open.used.get() || auth.openSetup() != open) {
                    throw new HttpException(404);
                }
                AuthUserResult result = open.recovery() ? recover(req, name, hash)
                        : createSetupUser(req, open, name, hash);
                open.used.set(true);
                TokenPair pair = auth.tokens().issue(result.row);
                auth.tokenDelivery().deliver(req, res, result.created ? 201 : 200, pair, null, cookies, auth.log());
            }
            done.resolve();
        });
        return done;
    }

    /** What a setup wrote: the stored user and whether it was created. */
    private static final class AuthUserResult {
        final AuthUser row;
        final boolean created;

        AuthUserResult(AuthUser row, boolean created) {
            this.row = row;
            this.created = created;
        }
    }

    private void createdEvent(Request req, AuthUser created, UserOrigin origin) {
        final AuthUser out = EventCalls.copy(created);
        final AuthEventContext ctx = ctx(origin, req, null);
        for (final AuthEvents l : auth.eventListeners()) {
            EventCalls.after(auth.log(), "onUserCreated", () -> l.onUserCreated(out, ctx));
        }
    }

    private AuthUserResult createSetupUser(Request req, Auth.PendingSetup open, String name, String hash) {
        final AuthUser row = AuthUser.builder().username(name).roles(open.roles).passwordHash(hash).build();
        AuthUser created = storeCall(() -> auth.userStore().create(row));
        createdEvent(req, created, UserOrigin.SETUP);
        auth.log().info("auth: setup created the user " + name + " with roles " + open.roles);
        return new AuthUserResult(created, true);
    }

    /**
     * R31 (6): create the user with admin, or enable it, replace its password (a null stored hash included), grant
     * admin and raise its ver exactly once; only on* events with origin RECOVERY, carrying the changed fields.
     */
    // @req SEC-AUTH-003
    // @req SEC-IAM-003
    // @req SEC-IAM-006
    private AuthUserResult recover(Request req, final String name, final String hash) {
        final UserStore store = auth.userStore();
        AuthUser current = storeCall(() -> store.findByUsername(name));
        AuthUserResult result;
        if (current == null) {
            final AuthUser row = AuthUser.builder().username(name).roles(Collections.singletonList("admin"))
                    .passwordHash(hash).build();
            AuthUser created = storeCall(() -> store.create(row));
            createdEvent(req, created, UserOrigin.RECOVERY);
            result = new AuthUserResult(created, true);
        } else {
            if (current.isServiceAccount()) {
                throw new AuthRejection(AuthErrorCode.SERVICE_ACCOUNT_NO_PASSWORD.getStatus(),
                        AuthErrorCode.SERVICE_ACCOUNT_NO_PASSWORD.getCode(), "a service account has no password");
            }
            final String id = current.getId();
            if (id == null) {
                throw new AuthUnavailableException("the stored user has no id");
            }
            final Set<String> roles = new LinkedHashSet<>(current.getRoles());
            roles.add("admin");
            java.util.concurrent.locks.ReentrantLock lock = com.snoworca.naruserve.utils.auth.internal.UserLocks.of(id);
            lock.lock();
            try {
                // the row re-read under the user write lock gives the expectation (REL-IAM-002: the re-read ver)
                AuthUser fresh = storeCall(() -> store.findById(id));
                if (fresh == null) {
                    throw new AuthUnavailableException("the recovered user disappeared");
                }
                current = fresh;
                final String oldHash = fresh.getPasswordHash();
                final long ver = fresh.getTokenVersion();
                roles.clear();
                roles.addAll(fresh.getRoles());
                roles.add("admin");
                // the one ver++ of a recovery, behind the future-key deletion (REL-APIKEY-003)
                boolean ok = verBump(id, () -> store.changePassword(id, oldHash, ver, hash));
                if (!ok) {
                    throw new AuthUnavailableException("the recovery password write lost a race; try again");
                }
                storeCall(() -> store.setEnabled(id, true));
                storeCall(() -> store.setRoles(id, roles));
            } finally {
                lock.unlock();
            }
            auth.userRevoked(id);
            final AuthUser before = EventCalls.copy(current);
            AuthUser after = storeCall(() -> store.findById(id));
            if (after == null) {
                throw new AuthUnavailableException("the recovered user disappeared");
            }
            final AuthUser out = EventCalls.copy(after);
            final Set<UserChange> changes = EnumSet.of(UserChange.PASSWORD);
            if (!current.isEnabled()) {
                changes.add(UserChange.ENABLED);
            }
            if (!current.getRoles().equals(roles)) {
                changes.add(UserChange.ROLES);
            }
            final AuthEventContext ctx = ctx(UserOrigin.RECOVERY, req, null);
            for (final AuthEvents l : auth.eventListeners()) {
                EventCalls.after(auth.log(), "onUserUpdated", () -> l.onUserUpdated(before, out, changes, ctx));
            }
            for (final AuthEvents l : auth.eventListeners()) {
                EventCalls.after(auth.log(), "onPasswordChanged", () -> l.onPasswordChanged(out, ctx));
            }
            result = new AuthUserResult(after, false);
        }
        auth.audit().emit(auth.audit().event(AuditEvent.RECOVERY_USED, "POST " + req.path(), req, null, null, false)
                .targetId(result.row.getId()).build());
        auth.log().warn("auth: recovery " + (result.created ? "created" : "reactivated") + " the admin " + name
                + "; turn recovery off");
        return result;
    }

    // ------------------------------------------------------------------------------------------------- /me/apikeys

    /** {@code GET /me/apikeys}: the caller's keys with their status, no secret; JWT logins only. */
    // @req FR-APIKEY-001
    private void listOwnKeys(Request req, Response res, Next next) throws Exception {
        AuthPrincipal p = auth.user(req);
        if (p.getApiKeyId() != null) {
            throw new AuthRejection(AuthErrorCode.API_KEY_NOT_ALLOWED.getStatus(),
                    AuthErrorCode.API_KEY_NOT_ALLOWED.getCode(), "API keys cannot list API keys");
        }
        List<Object> out = new ArrayList<>();
        for (com.snoworca.naruserve.utils.auth.apikey.ApiKey k : auth.apiKeys().list(p.getId())) {
            out.add(KeyIssuance.keyJson(k)); // the shape of the 201 and of the admin list
        }
        res.status(200).type("application/json; charset=utf-8").send(MiniJson.write(out));
    }

    /**
     * {@code POST /me/apikeys} {@code {"name", "scopes", "expiresInDays"?, "password"}} (00 R47 (6)): a subject-required
     * credential endpoint the middleware has already judged like a protected path (an expired token is 401
     * {@code invalid_token}, a cookie session needs CSRF and Origin, an API key is 403 {@code api_key_not_allowed}).
     * The body checks (400 {@code BAD_REQUEST}), then the caller must hold every requested scope (403
     * {@code insufficient_scope}), then the password confirmation (429 over the shared per-user limit, 401
     * {@code invalid_credentials} after the failure floor), then the issuance for the caller row the confirmation read:
     * 201 with the key metadata and the plaintext {@code key}, shown this once; 409 {@code api_key_limit} at the limit.
     */
    // @req FR-APIKEY-003
    // @req FR-APIKEY-005
    private Deferred issueOwnKey(final Request req, final Response res, Next next) throws Exception {
        final AuthPrincipal p = auth.user(req);
        if (p.getApiKeyId() != null) { // also refused by the middleware on the mounted path: a key never makes a key
            throw new AuthRejection(AuthErrorCode.API_KEY_NOT_ALLOWED.getStatus(),
                    AuthErrorCode.API_KEY_NOT_ALLOWED.getCode(), "API keys cannot issue API keys");
        }
        KeyIssuance.Spec spec = issuance.spec(body(req), false);
        try {
            auth.grantRule().checkGrant(req, p.getId(), null, p.getId(), "POST /auth/me/apikeys", spec.scopes);
        } catch (AuthRejection e) {
            Arrays.fill(spec.password, '\0');
            String m = e.getMessage();
            String prefix = "the actor does not hold a scope it grants: ";
            throw m != null && m.startsWith(prefix) // OPS-AUTH-030 AC-8: plain words for a personal key
                    ? new AuthRejection(e.getStatus(), e.getCode(), "you cannot put " + m.substring(prefix.length())
                    + " on your own key because you do not hold it") : e;
        } catch (RuntimeException e) {
            Arrays.fill(spec.password, '\0');
            throw e;
        } catch (Exception e) {
            Arrays.fill(spec.password, '\0');
            throw new AuthUnavailableException("the user store failed", e);
        }
        return issuance.confirmThenIssue(req, res, p.getId(), null, spec, "POST /auth/me/apikeys");
    }

    // ------------------------------------------------------------------------------------------------- /me

    /**
     * {@code GET /me}: the caller's public profile {@code {"id","username","enabled","roles","attributes",
     * "serviceAccount"}}, re-read from the store, without the password hash or the token version; API keys may call it.
     */
    // @req FR-FLOW-006
    private void me(Request req, Response res, Next next) throws Exception {
        final String id = auth.user(req).getId();
        AuthUser u = storeCall(() -> auth.userStore().findById(id));
        if (u == null) {
            throw new AuthRejection(AuthErrorCode.INVALID_TOKEN.getStatus(), AuthErrorCode.INVALID_TOKEN.getCode(),
                    "the user no longer exists");
        }
        res.status(200).type("application/json; charset=utf-8").send(MiniJson.write(AdminEndpoints.userJson(u)));
    }

    /**
     * {@code PATCH /me} {@code {"attributes": {name: value-or-null}}} (00 R37, R45): only the attributes named by
     * {@code .allowedAttributes(...)} may change (any other name is 400 {@code BAD_REQUEST} and nothing changes); a
     * null value removes the attribute; every other body field, {@code username} among them, is ignored. The write is
     * the {@code auth.users().update} path under the per-user lock with origin {@code SELF}: beforeUserUpdate (which
     * may refuse) and onUserUpdated with {@code ATTRIBUTES}. 200 with the profile. API keys are 403
     * {@code api_key_not_allowed}.
     */
    // @req FR-FLOW-006
    // @req SEC-APIKEY-008
    private void patchMe(Request req, Response res, Next next) throws Exception {
        AuthPrincipal p = auth.user(req);
        if (p.getApiKeyId() != null) {
            throw new AuthRejection(AuthErrorCode.API_KEY_NOT_ALLOWED.getStatus(),
                    AuthErrorCode.API_KEY_NOT_ALLOWED.getCode(), "API keys cannot change the profile");
        }
        Map<String, Object> body = body(req);
        for (String k : body.keySet()) { // OPS-AUTH-033 AC-3: a field outside attributes is refused, never ignored
            if (!k.equals("attributes")) {
                throw new ShownError(400, "unknown field " + k + "; PATCH /auth/me reads only {\"attributes\":{...}}"
                        + (k.equals("password") ? "; change the password with POST " + auth.mountPath() + "/password"
                        : k.equals("username") ? "; rename with admin A4 (PATCH " + auth.adminMount()
                        + "/users/:id) or auth.users().rename" : ""));
            }
        }
        Object raw = body.get("attributes");
        final Map<String, String> patch = new LinkedHashMap<>();
        if (raw != null) {
            if (!(raw instanceof Map)) {
                throw new ShownError(400, "attributes must be a JSON object of strings");
            }
            Set<String> allowed = auth.allowedAttributes();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                Object v = e.getValue();
                if (!(e.getKey() instanceof String) || v != null && !(v instanceof String)) {
                    throw new ShownError(400, "attributes must be a JSON object of strings");
                }
                if (!allowed.contains(e.getKey())) {
                    throw new ShownError(400, "the attribute " + e.getKey() + " may not be changed here; allow it with "
                            + ".allowedAttributes(\"" + e.getKey() + "\")"); // OPS-AUTH-018 AC-2
                }
                patch.put((String) e.getKey(), (String) v);
            }
        }
        final String id = p.getId();
        if (patch.isEmpty()) { // nothing to change: no write and no event
            me(req, res, next);
            return;
        }
        AuthUser updated = auth.adminUsers().update(id, u -> {
            Map<String, String> attrs = new LinkedHashMap<>(u.getAttributes());
            for (Map.Entry<String, String> e : patch.entrySet()) {
                if (e.getValue() == null) {
                    attrs.remove(e.getKey());
                } else {
                    attrs.put(e.getKey(), e.getValue());
                }
            }
            return u.toBuilder().attributes(attrs).build();
        }, ctx(UserOrigin.SELF, req, id));
        if (updated == null) {
            throw new AuthRejection(AuthErrorCode.INVALID_TOKEN.getStatus(), AuthErrorCode.INVALID_TOKEN.getCode(),
                    "the user no longer exists");
        }
        res.status(200).type("application/json; charset=utf-8").send(MiniJson.write(AdminEndpoints.userJson(updated)));
    }

    /** {@code DELETE /me/apikeys/:id}: deletes one of the caller's own keys; 204, 404 for any other id. */
    // @req FR-APIKEY-001
    private void deleteOwnKey(Request req, Response res, Next next) throws Exception {
        AuthPrincipal p = auth.user(req);
        if (p.getApiKeyId() != null) {
            throw new AuthRejection(AuthErrorCode.API_KEY_NOT_ALLOWED.getStatus(),
                    AuthErrorCode.API_KEY_NOT_ALLOWED.getCode(), "API keys cannot delete API keys");
        }
        final ApiKeyStore keys = auth.apiKeyStore();
        final String id = req.param("id");
        final String owner = p.getId();
        DefaultAuthApiKeys facade = (DefaultAuthApiKeys) auth.apiKeys();
        StoredApiKey row = keys == null ? null : facade.rowBeforeRevoke(keys, id);
        if (keys == null || !storeCall(() -> keys.deleteByIdAndOwner(id, owner))) {
            throw new ShownError(404, "no API key with id " + id + (id.length() == 8 // OPS-AUTH-024 AC-9
                    ? "; use the id field of the key, not its prefix" : " among your keys"));
        }
        facade.revokedThrough(req, "DELETE /auth/me/apikeys/:id", owner, id, owner,
                row == null ? null : row.getIssuedByUserId());
        res.sendStatus(204);
    }
}
