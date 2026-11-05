package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.AsyncRouteHandler;
import com.snoworca.naruserve.Deferred;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Next;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.common.MediaTypes;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.group.GroupAdminStore;
import com.snoworca.naruserve.utils.auth.group.GroupStore;
import com.snoworca.naruserve.utils.auth.internal.AuthArgumentException;
import com.snoworca.naruserve.utils.auth.internal.EffectiveGrants;
import com.snoworca.naruserve.utils.auth.internal.EventCalls;
import com.snoworca.naruserve.utils.auth.internal.GrantRule;
import com.snoworca.naruserve.utils.auth.internal.GroupEdges;
import com.snoworca.naruserve.utils.auth.internal.GroupIds;
import com.snoworca.naruserve.utils.auth.internal.HashSlots;
import com.snoworca.naruserve.utils.auth.internal.Lockouts;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.internal.ThrottleKeys;
import com.snoworca.naruserve.utils.auth.internal.UserLocks;
import com.snoworca.naruserve.utils.auth.internal.Usernames;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.UserPage;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * The admin endpoints of {@code auth.adminRoutes()} (00 R45): A1–A6 (users), G1–G8 (groups),
 * {@code DELETE /users/:id/lockout} and, with {@code .apiKeys(store)}, the three key routes; G9 (group roles) is v2
 * and absent. Every route carries its own scope guard (00 R39): {@code auth-users:read} / {@code auth-users:write} /
 * {@code auth-groups:read} / {@code auth-groups:write} / {@code auth-apikeys:read} / {@code auth-apikeys:write},
 * answered 403 {@code insufficient_scope} before the handler reads anything; every write then passes the grant rule
 * (00 R31, {@link GrantRule}). The handlers that hash a password (A2, A4) are async, so a request waiting for a hash
 * slot holds no worker thread. Request bodies are read with the app's JSON codec; responses are written as JSON
 * text without it.
 */
// @req IR-IAM-005
// @req SEC-IAM-007
final class AdminEndpoints {

    static final String USERS_READ = "auth-users:read";
    static final String USERS_WRITE = "auth-users:write";
    static final String GROUPS_READ = "auth-groups:read";
    static final String GROUPS_WRITE = "auth-groups:write";
    static final String KEYS_READ = "auth-apikeys:read";
    static final String KEYS_WRITE = "auth-apikeys:write";
    /** The request attribute holding the failure of the key ban step's body read, answered by the handler. */
    static final String BODY_ERROR = "naru.auth.bodyError";

    /** The R31 (2) bound on the users a group delete or edge removal may enumerate (00 R31 5th edition K-7). */
    static final int MAX_AFFECTED = 1000;

    /** The A2 attribute keys refused as the 00 R37 security field names (12th edition). */
    private static final Set<String> RESERVED_ATTRIBUTES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("passwordHash", "ver", "enabled", "roles")));

    private final Auth auth;
    private final KeyIssuance issuance;

    AdminEndpoints(Auth auth) {
        this.auth = auth;
        this.issuance = new KeyIssuance(auth);
    }

    /** Registers the endpoints on {@code r}: 15 routes, plus the 3 key routes when {@code keys} is set. */
    // @req IR-IAM-005
    void register(Router r, @Nullable ApiKeyStore keys) {
        r.get("/users", guard(USERS_READ), this::listUsers);
        r.methodAsync("POST", "/users", keyBodyFirst(true, guarded(USERS_WRITE, this::createUser)));
        r.get("/users/:id", guard(USERS_READ), this::getUser);
        r.methodAsync("PATCH", "/users/:id", keyBodyFirst(false, guarded(USERS_WRITE, this::patchUser)));
        r.delete("/users/:id", guard(USERS_WRITE), this::deleteUser);
        r.get("/users/:id/groups", guard(USERS_READ), this::userGroups);
        r.delete("/users/:id/lockout", guard(USERS_WRITE), this::releaseLockout);
        r.get("/groups", guard(GROUPS_READ), this::listGroups);
        r.post("/groups", guard(GROUPS_WRITE), this::createGroup);
        r.get("/groups/:id", guard(GROUPS_READ), this::getGroup);
        r.delete("/groups/:id", guard(GROUPS_WRITE), this::deleteGroup);
        r.put("/groups/:id/members/:userId", guard(GROUPS_WRITE), this::addMember);
        r.delete("/groups/:id/members/:userId", guard(GROUPS_WRITE), this::removeMember);
        r.put("/groups/:id/parents/:parentId", guard(GROUPS_WRITE), this::addParent);
        r.delete("/groups/:id/parents/:parentId", guard(GROUPS_WRITE), this::removeParent);
        if (keys != null) {
            r.get("/apikeys", guard(KEYS_READ), this::listKeys);
            r.methodAsync("POST", "/apikeys", guarded(KEYS_WRITE, this::issueKey));
            r.delete("/apikeys/:id", guard(KEYS_WRITE), this::deleteKey);
        }
    }

    // ------------------------------------------------------------------------------------------------- guards

    /**
     * The R39 guard of one route: 401 without a principal, the minimal principal promoted (401 when its token is no
     * longer valid), then 403 {@code insufficient_scope} unless the actor's effective scopes cover {@code scope}. An
     * API-key actor covers an {@code auth-*} scope only through a key scope that names that resource, never through
     * {@code *} (00 R31 (4), R46).
     */
    // @req SEC-IAM-007
    private Middleware guard(final String scope) {
        return auth.check((p, req) -> covers(p, scope), "the scope " + scope); // OPS-AUTH-018 AC-4
    }

    static boolean covers(AuthPrincipal p, String scope) {
        if (!p.hasScope(scope)) {
            return false;
        }
        Set<String> key = p.keyScopes();
        if (key == null) {
            return true;
        }
        String resource = scope.substring(0, scope.indexOf(':'));
        return key.contains(scope) || key.contains(resource + ":*");
    }

    /** An async handler behind the R39 guard; the guard runs first on the worker thread. */
    private interface AsyncWork {
        Deferred run(Request req, Response res) throws Exception;
    }

    /**
     * The body-level key-actor bans of A2 ({@code create}) and A4, before the R39 scope guard (step 10 before step 11,
     * the earlier step answers, 00 R47 (2) 26th edition, R47 (5)): an API-key request whose JSON body carries a
     * non-null {@code password}, an A2 without {@code "serviceAccount": true}, or an A4 with {@code "enabled": true}
     * for the key's own owner is 403 {@code api_key_not_allowed} whatever its scopes. A body that cannot be read as a
     * JSON object (another media type, malformed or not an object, empty, over the JSON limit) applies no ban and
     * leaves the answer to the guard and then the handler's body read (415, 400 or 413).
     */
    // @req FR-APIKEY-006
    // @req SEC-APIKEY-008
    private AsyncRouteHandler keyBodyFirst(final boolean create, final AsyncRouteHandler next) {
        return (req, res, n) -> {
            AuthPrincipal p = auth.userOrNull(req);
            if (p != null && p.getApiKeyId() != null && MediaTypes.isJsonUtf8(req.header("Content-Type"))) {
                Object body;
                try {
                    body = AccountEndpoints.parse(req);
                } catch (RuntimeException unreadable) {
                    // the body can be read once: the handler answers this failure after the guard passes
                    req.attribute(BODY_ERROR, unreadable);
                    body = null;
                }
                if (body instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) body;
                    String why = null;
                    if (m.get("password") != null) {
                        why = "an API key cannot set a password";
                    } else if (create && !Boolean.TRUE.equals(m.get("serviceAccount"))) {
                        why = "an API key can create only service accounts";
                    } else if (!create && Boolean.TRUE.equals(m.get("enabled")) && p.getId().equals(req.param("id"))) {
                        why = "an API key cannot enable its own owner";
                    }
                    if (why != null) {
                        return Deferred.rejected(new AuthRejection(AuthErrorCode.API_KEY_NOT_ALLOWED.getStatus(),
                                AuthErrorCode.API_KEY_NOT_ALLOWED.getCode(), why));
                    }
                }
            }
            return next.handle(req, res, n);
        };
    }

    private AsyncRouteHandler guarded(final String scope, final AsyncWork work) {
        final Middleware g = guard(scope);
        return (req, res, next) -> {
            final boolean[] passed = new boolean[1];
            final Throwable[] failed = new Throwable[1];
            g.handle(req, res, new Next() {
                @Override
                public void run() {
                    passed[0] = true;
                }

                @Override
                public void error(Throwable error) {
                    failed[0] = error;
                }
            });
            if (failed[0] != null) {
                return Deferred.rejected(failed[0]);
            }
            if (!passed[0]) {
                return Deferred.resolved();
            }
            return work.run(req, res);
        };
    }

    private String actor(Request req) {
        return auth.user(req).getId();
    }

    /** The actor's key scopes for an API-key actor, null for a login token (00 R31 (4)). */
    private @Nullable Set<String> key(Request req) {
        return auth.user(req).keyScopes();
    }

    // ------------------------------------------------------------------------------------------------- users

    /** A1: one page of users, {@code ?offset=&limit=&search=}. */
    // @req IR-IAM-005
    private void listUsers(Request req, Response res, Next next) throws Exception {
        int offset = Math.max(0, queryInt(req, "offset", 0));
        int limit = Math.max(1, Math.min(200, queryInt(req, "limit", 50)));
        UserPage page;
        try {
            page = auth.userStore().list(offset, limit, req.query("search"));
            if (page == null) { // OPS-AUTH-029 AC-3
                throw new IllegalStateException("UserStore.list returned null; return a UserPage (an empty one for no "
                        + "users)");
            }
        } catch (UnsupportedOperationException none) { // OPS-AUTH-021 AC-11
            throw new ShownError(501, none.getMessage() != null ? none.getMessage()
                    : "the UserStore does not implement UserStore.list, so GET /users is not served");
        }
        List<Object> items = new ArrayList<>();
        for (AuthUser u : page.getItems()) {
            items.add(userJson(u));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", page.getTotal());
        out.put("offset", (long) page.getOffset());
        out.put("limit", (long) page.getLimit());
        json(res, 200, out);
    }

    private static final List<String> A2_FIELDS = Arrays.asList("username", "password", "roles", "attributes",
            "serviceAccount");
    private static final List<String> A4_FIELDS = Arrays.asList("username", "password", "enabled", "roles",
            "attributes");

    /** OPS-AUTH-021 AC-2: a field the endpoint does not read is refused, never ignored. */
    private static void knownFields(Map<String, Object> body, List<String> known) {
        for (String k : body.keySet()) {
            if (!known.contains(k)) {
                StringBuilder all = new StringBuilder();
                for (String f : known) {
                    all.append(all.length() == 0 ? "" : ", ").append(f);
                }
                throw new ShownError(400, "unknown field " + k + "; the fields are " + all);
            }
        }
    }

    /** OPS-AUTH-021 AC-6: the 404 of an unknown user id says ids, not usernames, are expected. */
    private static ShownError noUser(String id) {
        return new ShownError(404, "no user with id " + id + "; the admin endpoints take the user id, not the username");
    }

    /** {@code req.queryInt} with Core's 400 message kept for the client (OPS-AUTH-020 AC-2). */
    private static int queryInt(Request req, String name, int defaultValue) {
        try {
            return req.queryInt(name, defaultValue);
        } catch (HttpException e) {
            throw new ShownError(400, e.getMessage());
        }
    }

    /**
     * A2: {@code {"username", "password", "roles", "attributes", "serviceAccount"}}; the reserved attribute keys are
     * refused with 400 before anything is created, then R31 (1) over the roles' scopes, the hash in a slot and the
     * create; 201 with the user and its Location.
     */
    // @req IR-IAM-005
    // @req SEC-IAM-001
    // @req FR-APIKEY-006
    private Deferred createUser(final Request req, final Response res) throws Exception {
        Map<String, Object> body = body(req);
        knownFields(body, A2_FIELDS); // OPS-AUTH-021 AC-2
        final String username = string(body, "username", true);
        final String password = string(body, "password", false);
        refuseKeyPasswordWrite(req, password);
        final List<String> roles = strings(body, "roles");
        auth.warnUndeclaredRoles(roles); // OPS-AUTH-018 AC-7
        final Map<String, String> attributes = attributes(body);
        final boolean service = bool(body, "serviceAccount", false);
        for (String k : attributes.keySet()) {
            if (RESERVED_ATTRIBUTES.contains(k)) {
                throw new ShownError(400, "attribute " + k + " is a security field name");
            }
        }
        final String name;
        try {
            name = Usernames.normalize(username);
        } catch (IllegalArgumentException bad) {
            throw new ShownError(400, "invalid username: " + bad.getMessage()); // OPS-AUTH-025 AC-4
        }
        if (service && password != null) {
            throw serviceAccountNoPassword();
        }
        if (!service && (password == null || password.isEmpty())) {
            throw new ShownError(400, "password must be a non-empty JSON string"); // OPS-AUTH-008 AC-5
        }
        final String actorId = actor(req);
        auth.grantRule().checkGrant(req, actorId, key(req), null, "CREATE", auth.grants().scopesOfRoles(
                new LinkedHashSet<>(roles)));
        if (auth.userStore().findByUsername(name) != null) {
            throw new DuplicateUserException("the username is taken");
        }
        final Deferred done = Deferred.pending();
        final AuthUser candidate = AuthUser.builder().username(name).roles(roles).attributes(attributes)
                .serviceAccount(service).build();
        if (password == null) {
            finishCreate(req, res, candidate, actorId, done);
            return done;
        }
        final char[] pw = password.toCharArray();
        newPassword(pw); // FR-TOKEN-004: the policy and breach check before the slot
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
                final AuthUser row = candidate.toBuilder().passwordHash(hash).build();
                continueLater(done, () -> finishCreate(req, res, row, actorId, done));
            }
        });
        return done;
    }

    /** The part of an async handler that runs after its hash, off the hash thread; abandoned work answers 503. */
    static final class Continuation implements Runnable {
        private final Deferred done;
        private final Runnable work;

        Continuation(Deferred done, Runnable work) {
            this.done = done;
            this.work = work;
        }

        @Override
        public void run() {
            work.run();
        }

        /** Rejects the request whose continuation will never run (the executor stopped or its queue was full). */
        void abandon() {
            done.reject(new AuthUnavailableException("the auth background executor is stopped or busy; retry later"));
        }
    }

    private void continueLater(Deferred done, Runnable work) {
        Continuation c = new Continuation(done, work);
        try {
            auth.adminExecutor().execute(c);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            c.abandon();
        }
    }

    private void finishCreate(Request req, Response res, AuthUser row, String actorId, Deferred done) {
        try {
            final AuthUser seen = EventCalls.copy(row);
            final AuthEventContext ctx = ctx(req, actorId);
            for (final AuthEvents l : auth.eventListeners()) {
                EventCalls.before(auth.log(), "beforeUserCreate", () -> l.beforeUserCreate(seen, ctx));
            }
            AuthUser created = auth.userStore().create(row);
            final AuthUser out = EventCalls.copy(created);
            for (final AuthEvents l : auth.eventListeners()) {
                EventCalls.after(auth.log(), "onUserCreated", () -> l.onUserCreated(out, ctx));
            }
            res.location(auth.adminMount() + "/users/" + created.getId());
            json(res, 201, userJson(out));
            done.resolve();
        } catch (Throwable e) {
            done.reject(e);
        }
    }

    /** A3. */
    // @req IR-IAM-005
    private void getUser(Request req, Response res, Next next) throws Exception {
        AuthUser u = auth.userStore().findById(req.param("id"));
        if (u == null) {
            throw noUser(req.param("id"));
        }
        json(res, 200, userJson(u));
    }

    /** A5: R31 (DELETE), then the FR-IAM-008 order (memberships, keys, row) with origin ADMIN; 204. */
    // @req IR-IAM-005
    private void deleteUser(Request req, Response res, Next next) throws Exception {
        String id = req.param("id");
        if (auth.userStore().findById(id) == null) {
            throw noUser(id);
        }
        String actorId = actor(req);
        auth.grantRule().check(req, actorId, key(req), id, GrantRule.Action.DELETE, Collections.<String>emptySet());
        auth.adminUsers().delete(id, ctx(req, actorId));
        auth.userRevoked(id);
        res.sendStatus(204);
    }

    /** A6: the user's direct groups. */
    // @req IR-IAM-005
    private void userGroups(Request req, Response res, Next next) throws Exception {
        String id = req.param("id");
        if (auth.userStore().findById(id) == null) {
            throw noUser(id);
        }
        GroupStore g = auth.groupStore();
        List<Object> out = new ArrayList<>();
        if (g != null) {
            List<String> sorted = new ArrayList<>(g.groupsOfUser(id));
            Collections.sort(sorted);
            out.addAll(sorted);
        }
        json(res, 200, out);
    }

    /**
     * {@code DELETE /users/:id/lockout}: R31 (MODIFY), then forgets the Lockout state and the user-only login
     * throttle key of that user; IP keys and (user, IP) keys stay. 204, also when no lock was set.
     */
    // @req FR-FLOW-009
    private void releaseLockout(Request req, Response res, Next next) throws Exception {
        String id = req.param("id");
        AuthUser u = auth.userStore().findById(id);
        if (u == null) {
            throw noUser(id);
        }
        auth.grantRule().check(req, actor(req), key(req), id, GrantRule.Action.MODIFY, Collections.<String>emptySet());
        Lockouts l = auth.lockouts();
        if (l != null) {
            l.release(id);
        }
        auth.loginThrottle().reset(ThrottleKeys.loginUser(u.getUsername()));
        res.sendStatus(204);
    }

    // ------------------------------------------------------------------------------------------------- A4

    /** One A4 failure answered by the handler itself, with what was applied before it. */
    private static final class StepFailure extends Exception {
        final int status;
        final String code;
        final @Nullable String auditType;

        StepFailure(int status, String code, @Nullable String auditType, Throwable cause) {
            super(cause);
            this.status = status;
            this.code = code;
            this.auditType = auditType;
        }
    }

    /**
     * A4 (00 R37 13th–14th editions): R31 once over all changed fields, then username (rename path), password
     * ({@code changePassword} with the re-read version, no retry), enabled, roles and attributes in that order; a
     * failing step answers 409 or 503 with the top-level {@code applied} list of what was already applied, and one
     * {@code onUserUpdated} (origin ADMIN) reports the applied changes.
     */
    // @req FR-IAM-014
    // @req SEC-IAM-003
    // @req REL-APIKEY-003
    // @req FR-APIKEY-006
    private Deferred patchUser(final Request req, final Response res) throws Exception {
        final String id = req.param("id");
        Map<String, Object> body = body(req);
        knownFields(body, A4_FIELDS); // OPS-AUTH-021 AC-2
        final String newName = body.get("username") != null ? string(body, "username", true) : null;
        final String password = body.get("password") != null ? string(body, "password", true) : null;
        refuseKeyPasswordWrite(req, password);
        final Boolean enabled = body.get("enabled") != null ? bool(body, "enabled", false) : null;
        final List<String> roles = body.get("roles") != null ? strings(body, "roles") : null;
        auth.warnUndeclaredRoles(roles); // OPS-AUTH-018 AC-7
        final Map<String, String> attributes = body.get("attributes") != null ? attributes(body) : null;
        if (password != null && password.isEmpty()) {
            throw new ShownError(400, "password must be a non-empty JSON string"); // OPS-AUTH-008 AC-5
        }
        final UserStore store = auth.userStore();
        final AuthUser target = store.findById(id);
        if (target == null) {
            throw noUser(id);
        }
        if (password != null && target.isServiceAccount()) {
            throw serviceAccountNoPassword(); // FR-APIKEY-006: a service account authenticates only by API key
        }
        final String name;
        if (newName != null) {
            try {
                name = Usernames.normalize(newName);
            } catch (IllegalArgumentException bad) {
                throw new ShownError(400, "invalid username: " + bad.getMessage()); // OPS-AUTH-025 AC-4
            }
        } else {
            name = null;
        }
        final boolean rename = name != null && !name.equals(target.getUsername());
        final boolean enabling = enabled != null && enabled && !target.isEnabled();
        final boolean disabling = enabled != null && !enabled && target.isEnabled();
        final Set<String> newRoles = roles == null ? null : new LinkedHashSet<>(roles);
        final boolean rolesChange = newRoles != null && !newRoles.equals(target.getRoles());
        final String actorId = actor(req);
        checkPatch(req, actorId, target, rename || attributes != null || enabling, password != null, disabling,
                newRoles, rolesChange);
        final AuthUser before = EventCalls.copy(target);
        AuthUser.Builder ab = target.toBuilder();
        if (rename) {
            ab.username(name);
        }
        if (enabled != null) {
            ab.enabled(enabled);
        }
        if (rolesChange) {
            ab.roles(newRoles);
        }
        if (attributes != null) {
            ab.attributes(merged(target.getAttributes(), attributes));
        }
        final AuthUser planned = EventCalls.copy(ab.build());
        final AuthEventContext ctx = ctx(req, actorId);
        for (final AuthEvents l : auth.eventListeners()) {
            EventCalls.before(auth.log(), "beforeUserUpdate", () -> l.beforeUserUpdate(before, planned, ctx));
        }
        final Deferred done = Deferred.pending();
        final boolean modify = rename || attributes != null || enabling;
        final boolean resetting = password != null;
        // the hash wait can be long: the grant rule runs again on the row re-read under the user lock
        final Step recheck = () -> {
            AuthUser fresh = store.findById(id);
            if (fresh == null) {
                throw new HttpException(404);
            }
            checkPatch(req, actorId, fresh, modify, resetting, disabling, newRoles, rolesChange);
            return Boolean.TRUE;
        };
        final Plan plan = new Plan(id, rename ? name : null, enabled != null && (enabling || disabling) ? enabled
                : null, rolesChange ? newRoles : null, attributes, before, ctx, recheck);
        if (password == null) {
            applyPatch(req, res, plan, null, done);
            return done;
        }
        final char[] pw = password.toCharArray();
        newPassword(pw); // FR-TOKEN-004: the policy and breach check before the slot
        auth.hashSlots().submit(new Callable<String>() {
            @Override
            public String call() {
                return auth.passwords().hasher().hash(pw);
            }
        }, new HashSlots.Callback<String>() {
            @Override
            public void done(@Nullable final String hash, @Nullable Throwable error) {
                Arrays.fill(pw, '\0');
                if (error != null || hash == null) {
                    done.reject(error != null ? error : new IllegalStateException("no hash"));
                    return;
                }
                continueLater(done, () -> applyPatch(req, res, plan, hash, done));
            }
        });
        return done;
    }

    /** The A4 changes still to apply. */
    private static final class Plan {
        final String id;
        final @Nullable String username;
        final @Nullable Boolean enabled;
        final @Nullable Set<String> roles;
        final @Nullable Map<String, String> attributes;
        final AuthUser before;
        final AuthEventContext ctx;
        final Step recheck;

        Plan(String id, @Nullable String username, @Nullable Boolean enabled, @Nullable Set<String> roles,
                @Nullable Map<String, String> attributes, AuthUser before, AuthEventContext ctx, Step recheck) {
            this.recheck = recheck;
            this.id = id;
            this.username = username;
            this.enabled = enabled;
            this.roles = roles;
            this.attributes = attributes;
            this.before = before;
            this.ctx = ctx;
        }
    }

    /** R31 once over every changed field, before anything is written (00 R37 7th edition Q-9). */
    private void checkPatch(Request req, String actorId, AuthUser target, boolean modify, boolean password,
            boolean disable, @Nullable Set<String> newRoles, boolean rolesChange) throws Exception {
        GrantRule rule = auth.grantRule();
        String id = target.getId();
        if (id == null) {
            throw new HttpException(404);
        }
        if (modify) {
            rule.check(req, actorId, key(req), id, GrantRule.Action.MODIFY, Collections.<String>emptySet());
        }
        if (password) {
            rule.check(req, actorId, key(req), id, GrantRule.Action.PASSWORD_RESET, Collections.<String>emptySet());
        }
        if (disable) {
            rule.check(req, actorId, key(req), id, GrantRule.Action.DISABLE, Collections.<String>emptySet());
        }
        if (rolesChange && newRoles != null) {
            Set<String> removed = new LinkedHashSet<>(target.getRoles());
            removed.removeAll(newRoles);
            Set<String> added = new LinkedHashSet<>(newRoles);
            added.removeAll(target.getRoles());
            if (!removed.isEmpty()) {
                rule.check(req, actorId, key(req), id, GrantRule.Action.REMOVE_ROLES, Collections.<String>emptySet());
            }
            if (!added.isEmpty()) {
                EffectiveGrants g = auth.grants();
                Set<String> after = new LinkedHashSet<>(newRoles);
                after.addAll(groupRoles(id));
                Set<String> gained = new LinkedHashSet<>(g.scopesOfRoles(after));
                gained.removeAll(g.scopesOf(target));
                rule.check(req, actorId, key(req), id, GrantRule.Action.ADD_ROLES, gained);
            }
        }
    }

    private Set<String> groupRoles(String userId) throws Exception {
        GroupStore g = auth.groupStore();
        if (g == null) {
            return Collections.emptySet();
        }
        Set<String> groups = auth.grants().groupsOf(userId);
        return groups.isEmpty() ? Collections.<String>emptySet() : g.rolesOfGroups(groups);
    }

    private void applyPatch(Request req, Response res, Plan plan, @Nullable String newHash, Deferred done) {
        final UserStore store = auth.userStore();
        final List<String> applied = new ArrayList<>();
        final Set<UserChange> changes = EnumSet.noneOf(UserChange.class);
        boolean passwordApplied = false;
        StepFailure failure = null;
        ReentrantLock lock = UserLocks.of(plan.id);
        lock.lock();
        try {
            if (newHash != null) {
                plan.recheck.run();
            }
            try {
                if (plan.username != null) {
                    step(false, () -> {
                        AuthUser holder = store.findByUsername(plan.username);
                        if (holder != null && !plan.id.equals(holder.getId())) {
                            throw new DuplicateUserException("the username is taken");
                        }
                        AuthUser fresh = store.findById(plan.id);
                        if (fresh == null) {
                            throw new HttpException(404);
                        }
                        if (store.update(fresh.toBuilder().username(plan.username).build()) == null) {
                            throw new HttpException(404);
                        }
                        return Boolean.TRUE;
                    });
                    applied.add("username");
                    changes.add(UserChange.USERNAME);
                }
                if (newHash != null) {
                    step(false, () -> {
                        AuthUser fresh = store.findById(plan.id);
                        if (fresh == null) {
                            throw new HttpException(404);
                        }
                        // the ver++ of a reset runs behind the future-key deletion, in the key owner lock
                        // (REL-APIKEY-003), which is taken inside the user write lock held here
                        if (!auth.apiKeyOwners().bump(plan.id, () -> store.changePassword(plan.id,
                                fresh.getPasswordHash(), fresh.getTokenVersion(), newHash))) {
                            throw new WriteConflict();
                        }
                        return Boolean.TRUE;
                    });
                    applied.add("password");
                    changes.add(UserChange.PASSWORD);
                    passwordApplied = true;
                }
                final boolean after = passwordApplied;
                if (plan.enabled != null) {
                    final boolean en = plan.enabled;
                    step(after, () -> {
                        if (!store.setEnabled(plan.id, en)) {
                            throw new HttpException(404);
                        }
                        return Boolean.TRUE;
                    });
                    applied.add("enabled");
                    changes.add(UserChange.ENABLED);
                    if (!en && !passwordApplied) { // a password change already raised ver once (SEC-IAM-003)
                        step(after, () -> {
                            if (!auth.apiKeyOwners().bump(plan.id, () -> store.bumpTokenVersion(plan.id))) {
                                throw new HttpException(404);
                            }
                            return Boolean.TRUE;
                        });
                    }
                }
                if (plan.roles != null) {
                    final Set<String> r = plan.roles;
                    step(after, () -> {
                        if (!store.setRoles(plan.id, r)) {
                            throw new HttpException(404);
                        }
                        return Boolean.TRUE;
                    });
                    applied.add("roles");
                    changes.add(UserChange.ROLES);
                }
                if (plan.attributes != null) {
                    final Map<String, String> a = plan.attributes;
                    step(after, () -> {
                        AuthUser fresh = store.findById(plan.id);
                        if (fresh == null) {
                            throw new HttpException(404);
                        }
                        store.update(fresh.toBuilder().attributes(merged(fresh.getAttributes(), a)).build());
                        return Boolean.TRUE;
                    });
                    applied.add("attributes");
                    changes.add(UserChange.ATTRIBUTES);
                }
            } catch (StepFailure f) {
                failure = f;
            }
        } catch (Throwable e) {
            lock.unlock();
            afterPatch(plan, changes, passwordApplied);
            done.reject(e);
            return;
        }
        lock.unlock();
        afterPatch(plan, changes, passwordApplied);
        if (failure != null) {
            if (failure.auditType != null) {
                AuthPrincipal p = auth.userOrNull(req);
                auth.audit().emit(auth.audit().event(failure.auditType, "PATCH user", req, p == null ? null
                        : p.getId(), p == null ? null : p.getApiKeyId(), p != null && auth.isServiceAccount(p.getId()))
                        .targetId(plan.id)
                        .outcome(AuditEvent.Outcome.FAILURE).detail("applied", joined(applied))
                        .reason("an admin user update failed after a partial write").build());
            }
            if (failure.status == 503) {
                auth.log().error("auth: PATCH user " + plan.id + " failed after applying " + applied, failure.getCause());
            }
            writeApplied(req, res, failure.status, failure.code, applied);
            done.resolve();
            return;
        }
        try {
            AuthUser now = store.findById(plan.id);
            json(res, 200, now == null ? userJson(plan.before) : userJson(now));
            done.resolve();
        } catch (Throwable e) {
            done.reject(e);
        }
    }

    /**
     * A key actor never sets a password (00 R47 (5), the key-actor rule of SEC-APIKEY-008): an A2 or A4 carrying a
     * non-null {@code password} from an API-key request is 403 {@code api_key_not_allowed}, before the
     * service-account 400 and before any hash. The rest of the key-actor restrictions arrive with SEC-APIKEY-008.
     */
    // @req FR-APIKEY-006
    private void refuseKeyPasswordWrite(Request req, @Nullable String password) {
        if (password != null && auth.user(req).getApiKeyId() != null) {
            throw new AuthRejection(AuthErrorCode.API_KEY_NOT_ALLOWED.getStatus(),
                    AuthErrorCode.API_KEY_NOT_ALLOWED.getCode(), "an API key cannot set a password");
        }
    }

    /** 400 {@code service_account_no_password} (00 R47 (10)). */
    // @req FR-APIKEY-006
    private static AuthRejection serviceAccountNoPassword() {
        return new AuthRejection(AuthErrorCode.SERVICE_ACCOUNT_NO_PASSWORD.getStatus(),
                AuthErrorCode.SERVICE_ACCOUNT_NO_PASSWORD.getCode(), "a service account has no password");
    }

    /** Raised by the password step when changePassword returned false (a lost race; never retried). */
    private static final class WriteConflict extends Exception {
        WriteConflict() {
            super("the password write lost a race");
        }
    }

    /** One A4 store step. */
    private interface Step {
        Object run() throws Exception;
    }

    /** Runs a step; its failure becomes a {@link StepFailure} (answered with applied) or propagates unchanged. */
    private static void step(boolean afterPassword, Step s) throws Exception {
        try {
            s.run();
        } catch (DuplicateUserException e) {
            throw new StepFailure(409, AuthErrorCode.DUPLICATE_USER.getCode(), null, e);
        } catch (WriteConflict e) {
            throw new StepFailure(409, AuthErrorCode.WRITE_CONFLICT.getCode(), null, e);
        } catch (AuthUnavailableException e) {
            boolean conflict = AuthErrorCode.SECURITY_WRITE_CONFLICT.getCode().equals(e.getCode());
            throw new StepFailure(503, e.getCode(), conflict ? AuditEvent.SECURITY_WRITE_CONFLICT
                    : afterPassword ? AuditEvent.USER_UPDATE_FAILED : null, e);
        } catch (IllegalArgumentException | HttpException e) { // HttpException covers AuthRejection
            if (!afterPassword) {
                throw e;
            }
            throw new StepFailure(503, AuthUnavailableException.SERVICE_UNAVAILABLE, AuditEvent.USER_UPDATE_FAILED, e);
        } catch (Exception e) {
            throw new StepFailure(503, AuthUnavailableException.SERVICE_UNAVAILABLE,
                    afterPassword ? AuditEvent.USER_UPDATE_FAILED : null, e);
        }
    }

    /** One onUserUpdated (origin ADMIN) for what was applied, onPasswordChanged when the password was, and the cache. */
    private void afterPatch(Plan plan, final Set<UserChange> changes, boolean passwordApplied) {
        if (changes.isEmpty()) {
            return;
        }
        if (changes.contains(UserChange.ROLES)) {
            auth.rolesChanged();
        }
        if (passwordApplied || (plan.enabled != null && !plan.enabled)) {
            auth.userRevoked(plan.id);
        } else {
            auth.userChanged(plan.id);
        }
        final AuthUser after;
        try {
            AuthUser now = auth.userStore().findById(plan.id);
            after = EventCalls.copy(now != null ? now : plan.before);
        } catch (Exception e) {
            auth.log().error("auth: re-reading user " + plan.id + " after PATCH failed; events carry the old row", e);
            return;
        }
        final Set<UserChange> applied = Collections.unmodifiableSet(EnumSet.copyOf(changes));
        final AuthEventContext ctx = plan.ctx;
        final AuthUser before = plan.before;
        for (final AuthEvents l : auth.eventListeners()) {
            EventCalls.after(auth.log(), "onUserUpdated", () -> l.onUserUpdated(before, after, applied, ctx));
            if (passwordApplied) {
                EventCalls.after(auth.log(), "onPasswordChanged", () -> l.onPasswordChanged(after, ctx));
            }
        }
    }

    private static String joined(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            b.append(b.length() == 0 ? "" : ",").append(p);
        }
        return b.toString();
    }

    private static Map<String, String> merged(Map<String, String> current, Map<String, String> patch) {
        Map<String, String> m = new LinkedHashMap<>(current);
        for (Map.Entry<String, String> e : patch.entrySet()) {
            if (e.getValue() == null) {
                m.remove(e.getKey());
            } else {
                m.put(e.getKey(), e.getValue());
            }
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------------- groups

    private @Nullable GroupAdminStore admin() {
        GroupStore g = auth.groupStore();
        return g instanceof GroupAdminStore ? (GroupAdminStore) g : null;
    }

    private GroupAdminStore adminOr404() {
        GroupAdminStore g = admin();
        if (g == null) { // OPS-AUTH-020 AC-1
            throw new ShownError(404, "group administration is off: the group endpoints need auth built with"
                    + " .groups(store) where the store is a GroupAdminStore");
        }
        return g;
    }

    private GroupAdminStore existing(String groupId) throws Exception {
        GroupAdminStore g = adminOr404();
        if (!GroupIds.isValid(groupId) || !g.groupExists(groupId)) {
            throw new ShownError(404, "no group " + groupId); // OPS-AUTH-021 AC-6
        }
        return g;
    }

    /** G1: every group id, sorted. */
    // @req IR-IAM-005
    private void listGroups(Request req, Response res, Next next) throws Exception {
        GroupAdminStore g = admin();
        json(res, 200, g == null ? Collections.emptyList() : new ArrayList<Object>(g.listGroups()));
    }

    /** G2: {@code {"id"}}; 201 with Location; an existing id is 409 {@code CONFLICT}, an invalid one 400. */
    // @req IR-IAM-005
    private void createGroup(Request req, Response res, Next next) throws Exception {
        GroupAdminStore g = adminOr404();
        String id = string(body(req), "id", true);
        if (!GroupIds.isValid(id)) {
            throw new ShownError(400, "a group id is [a-z0-9._-]{1,64}");
        }
        if (!g.createGroup(id)) {
            auth.respond(req, res, 409, "CONFLICT", "the group exists", null);
            return;
        }
        res.location(auth.adminMount() + "/groups/" + id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        json(res, 201, out);
    }

    /** G3: {@code {"id", "members", "parents", "children"}}. */
    // @req IR-IAM-005
    private void getGroup(Request req, Response res, Next next) throws Exception {
        String id = req.param("id");
        GroupAdminStore g = existing(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("members", sorted(g.membersOf(id)));
        out.put("parents", sorted(g.parentsOf(Collections.singleton(id))));
        out.put("children", sorted(g.childrenOf(id)));
        json(res, 200, out);
    }

    /**
     * G4 (00 R31 (2)): when the actor covers every scope the group and its ancestors confer, the group is deleted
     * without enumerating its members; otherwise the affected users (direct and indirect members) are enumerated up to
     * 1,001, more than 1,000 answer 409 {@code group_too_large}, and each must be covered by the actor and not be the
     * actor. The actor among the affected users is refused in both cases (00 R31 (3)), found through its own groups.
     */
    // @req SEC-IAM-005
    private void deleteGroup(Request req, Response res, Next next) throws Exception {
        String id = req.param("id");
        GroupAdminStore g = existing(id);
        protectAffected(req, g, id, Collections.singleton(id), "DELETE group");
        g.deleteGroup(id);
        auth.rolesChanged();
        res.sendStatus(204);
    }

    /**
     * The R31 checks of a write that takes away what {@code conferring} (and their ancestors) confer from every user
     * at or below {@code top}: the skip when the actor covers the conferred scopes, else the bounded enumeration.
     */
    private void protectAffected(Request req, GroupAdminStore g, String top, Set<String> conferring, String action)
            throws Exception {
        String actorId = actor(req);
        GrantRule rule = auth.grantRule();
        Set<String> actorScopes = rule.actorScopes(req, actorId, top, action);
        AuthPrincipal p = auth.user(req);
        if (auth.grants().groupsOf(actorId).contains(top)) {
            rule.selfDenied(req, actorId, action);
        }
        Set<String> conferred = conferredBy(g, conferring);
        boolean skip = true;
        for (String s : conferred) {
            if (!GrantRule.covers(actorScopes, p.keyScopes(), s)) {
                skip = false;
                break;
            }
        }
        if (skip) {
            return;
        }
        Set<String> affected = affectedUsers(g, top);
        if (affected.size() > MAX_AFFECTED) {
            throw new AuthRejection(AuthErrorCode.GROUP_TOO_LARGE.getStatus(),
                    AuthErrorCode.GROUP_TOO_LARGE.getCode(), "the change reaches more than " + MAX_AFFECTED
                    + " users; split it into smaller changes");
        }
        for (String u : affected) {
            rule.checkAffected(req, actorId, actorScopes, p.keyScopes(), u, action);
        }
    }

    /** The scopes the roles of {@code groups} and all their ancestors confer. */
    private Set<String> conferredBy(GroupAdminStore g, Set<String> groups) throws Exception {
        Set<String> all = new LinkedHashSet<>(groups);
        Set<String> level = new LinkedHashSet<>(groups);
        int depth = 0;
        while (!level.isEmpty() && depth++ <= auth.groupLimitsDepth()) {
            Set<String> next = new LinkedHashSet<>(g.parentsOf(level));
            next.removeAll(all);
            all.addAll(next);
            level = next;
        }
        return auth.grants().scopesOfRoles(g.rolesOfGroups(all));
    }

    /** The direct and indirect members of {@code top}, stopping at {@link #MAX_AFFECTED} + 1. */
    private static Set<String> affectedUsers(GroupAdminStore g, String top) throws Exception {
        Set<String> users = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>();
        todo.add(top);
        seen.add(top);
        while (!todo.isEmpty() && users.size() <= MAX_AFFECTED) {
            String group = todo.poll();
            for (String m : g.membersOf(group)) {
                users.add(m);
                if (users.size() > MAX_AFFECTED) {
                    return users;
                }
            }
            for (String c : g.childrenOf(group)) {
                if (seen.add(c)) {
                    todo.add(c);
                }
            }
        }
        return users;
    }

    /** G5: R31 (1) over what the group and its ancestors confer, and the self rule; 204, idempotent. */
    // @req IR-IAM-005
    private void addMember(Request req, Response res, Next next) throws Exception {
        String id = req.param("id");
        String userId = req.param("userId");
        GroupAdminStore g = existing(id);
        AuthUser u = auth.userStore().findById(userId);
        if (u == null) {
            throw noUser(userId);
        }
        Set<String> gained = new LinkedHashSet<>(conferredBy(g, Collections.singleton(id)));
        gained.removeAll(auth.grants().scopesOf(u));
        auth.grantRule().check(req, actor(req), key(req), userId, GrantRule.Action.ADD_TO_GROUP, gained);
        g.addMember(id, userId);
        auth.rolesChanged();
        res.sendStatus(204);
    }

    /** G6: R31 (2) target protection and the self rule; 204, idempotent. */
    // @req IR-IAM-005
    private void removeMember(Request req, Response res, Next next) throws Exception {
        String id = req.param("id");
        String userId = req.param("userId");
        GroupAdminStore g = adminOr404();
        if (!GroupIds.isValid(id) || !g.groupExists(id)) {
            res.sendStatus(204);
            return;
        }
        if (auth.userStore().findById(userId) != null) {
            auth.grantRule().check(req, actor(req), key(req), userId, GrantRule.Action.REMOVE_FROM_GROUP,
                    Collections.<String>emptySet());
        }
        g.removeMember(id, userId);
        auth.rolesChanged();
        res.sendStatus(204);
    }

    /**
     * G7: the cycle and depth check, then R31 (1): every user at or below the child gains what the parent and its
     * ancestors confer, so the actor must cover it when the child has any member; 204.
     */
    // @req IR-IAM-005
    // @req SEC-IAM-004
    private void addParent(Request req, Response res, Next next) throws Exception {
        String child = req.param("id");
        String parent = req.param("parentId");
        GroupAdminStore g = existing(child);
        if (!GroupIds.isValid(parent) || !g.groupExists(parent)) {
            throw new ShownError(404, "no group " + parent); // OPS-AUTH-021 AC-6
        }
        GroupEdges.checkChecked(GroupEdges.of(g), child, parent, auth.groupLimits());
        Set<String> affected = affectedUsers(g, child);
        if (!affected.isEmpty()) {
            Set<String> gained = conferredBy(g, Collections.singleton(parent));
            auth.grantRule().checkGrant(req, actor(req), key(req), child, "ADD group parent", gained);
        }
        String actorId = actor(req);
        if (auth.grants().groupsOf(actorId).contains(child)) { // the actor below the child: its own groups change
            auth.grantRule().selfDenied(req, actorId, "ADD group parent");
        }
        g.addParent(child, parent);
        auth.rolesChanged();
        res.sendStatus(204);
    }

    /** G8: the users below the child lose what the parent confers, checked as for a group delete; 204, idempotent. */
    // @req IR-IAM-005
    // @req SEC-IAM-005
    private void removeParent(Request req, Response res, Next next) throws Exception {
        String child = req.param("id");
        String parent = req.param("parentId");
        GroupAdminStore g = adminOr404();
        if (!GroupIds.isValid(child) || !GroupIds.isValid(parent) || !g.groupExists(child)
                || !g.parentsOf(Collections.singleton(child)).contains(parent)) {
            res.sendStatus(204);
            return;
        }
        protectAffected(req, g, child, Collections.singleton(parent), "REMOVE group parent");
        g.removeParent(child, parent);
        auth.rolesChanged();
        res.sendStatus(204);
    }

    // ------------------------------------------------------------------------------------------------- keys

    /**
     * {@code GET /apikeys?ownerUserId=}: the owner's keys with their {@code status} and without their secret hashes,
     * disabled owners included; 400 {@code BAD_REQUEST} without {@code ownerUserId}, 404 for an unknown owner.
     */
    // @req IR-IAM-005
    // @req FR-APIKEY-004
    private void listKeys(Request req, Response res, Next next) throws Exception {
        String owner = req.query("ownerUserId");
        if (owner == null || owner.isEmpty()) {
            throw new ShownError(400, "the ownerUserId query parameter is required");
        }
        if (auth.userStore().findById(owner) == null) {
            throw noUser(owner);
        }
        List<Object> out = new ArrayList<>();
        for (com.snoworca.naruserve.utils.auth.apikey.ApiKey k : auth.apiKeys().list(owner)) {
            out.add(KeyIssuance.keyJson(k));
        }
        json(res, 200, out);
    }

    /**
     * {@code POST /apikeys} {@code {"ownerUserId", "name", "scopes", "expiresInDays"?, "password"}} (00 R47 (6) 28th–30th
     * editions), decided in this order: the body checks (400 {@code BAD_REQUEST}), an unknown owner (404), the grant
     * rule on the owner (the new key's scopes must be covered by the caller, and the owner's scopes too: 403
     * {@code insufficient_scope}), a disabled owner (400 {@code BAD_REQUEST}), and last the confirmation of the
     * caller's own password (429, 401 after the failure floor), so a refused request spends no hash slot and no
     * attempt. The owner row read here becomes the key's ownerVer and the caller its {@code issuedByUserId}. API keys
     * never reach it (403 {@code api_key_not_allowed} in the middleware).
     */
    // @req FR-APIKEY-004
    // @req FR-APIKEY-005
    private Deferred issueKey(final Request req, final Response res) throws Exception {
        final String actorId = actor(req);
        if (key(req) != null) { // also refused by the middleware on the mounted path: a key never makes a key
            throw new AuthRejection(AuthErrorCode.API_KEY_NOT_ALLOWED.getStatus(),
                    AuthErrorCode.API_KEY_NOT_ALLOWED.getCode(), "API keys cannot issue API keys");
        }
        KeyIssuance.Spec spec = issuance.spec(body(req), true);
        AuthUser owner;
        try {
            String ownerId = spec.ownerUserId;
            owner = ownerId == null ? null : auth.userStore().findById(ownerId);
            if (owner == null) {
                throw ownerId == null ? new HttpException(404) : noUser(ownerId);
            }
            auth.grantRule().check(req, actorId, key(req), owner.getId(), GrantRule.Action.MODIFY, spec.scopes);
            if (!owner.isEnabled()) {
                throw new ShownError(400, "the owner is disabled; enable it before issuing it a key");
            }
        } catch (Exception e) {
            Arrays.fill(spec.password, '\0');
            throw e;
        }
        return issuance.confirmThenIssue(req, res, actorId, owner, spec, "POST /auth/admin/apikeys");
    }

    /**
     * {@code DELETE /apikeys/:id}: the grant rule protects the key's owner (a smaller actor cannot revoke a bigger
     * user's key), then the key is deleted; 204, 404 for an unknown id.
     */
    // @req IR-IAM-005
    private void deleteKey(Request req, Response res, Next next) throws Exception {
        ApiKeyStore keys = auth.apiKeyStore();
        String id = req.param("id");
        StoredApiKey k = keys == null ? null : keys.findById(id);
        if (keys == null || k == null) {
            throw new ShownError(404, "no API key with id " + id); // OPS-AUTH-021 AC-6
        }
        if (auth.userStore().findById(k.getOwnerUserId()) != null) {
            auth.grantRule().check(req, actor(req), key(req), k.getOwnerUserId(), GrantRule.Action.MODIFY,
                    Collections.<String>emptySet());
        }
        if (!keys.deleteById(id)) {
            throw new HttpException(404);
        }
        ((DefaultAuthApiKeys) auth.apiKeys()).revokedThrough(req, "DELETE /auth/admin/apikeys/:id", actor(req), id,
                k.getOwnerUserId(), k.getIssuedByUserId());
        res.sendStatus(204);
    }

    // ------------------------------------------------------------------------------------------------- JSON

    /** FR-TOKEN-004: 422 for a new password the policy or the breach checker refuses; the array is wiped then. */
    // @req FR-TOKEN-004
    private void newPassword(char[] pw) {
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

    private AuthEventContext ctx(Request req, String actorId) {
        return new AuthEventContext(UserOrigin.ADMIN, req, actorId, auth.time().nowMillis());
    }

    /** The hash-free JSON of a user. */
    static Map<String, Object> userJson(AuthUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("enabled", u.isEnabled());
        m.put("roles", sorted(u.getRoles()));
        m.put("attributes", new LinkedHashMap<String, Object>(u.getAttributes()));
        m.put("serviceAccount", u.isServiceAccount());
        return m;
    }

    private static List<Object> sorted(Set<String> values) {
        List<String> l = new ArrayList<>(values);
        Collections.sort(l);
        return new ArrayList<Object>(l);
    }

    private static void json(Response res, int status, Object value) {
        res.status(status).type("application/json; charset=utf-8").send(MiniJson.write(value));
    }

    /**
     * An A4 failure answer: the auth error body plus the top-level {@code applied} array, or, with a custom
     * {@code AuthResponses}, its body (which cannot carry the list).
     */
    private void writeApplied(Request req, Response res, int status, String code, List<String> applied) {
        if (auth.responses() != null) {
            auth.respond(req, res, status, code, null, null);
            return;
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", AuthErrorCode.DUPLICATE_USER.getCode().equals(code) ? "the username is taken" // OPS-AUTH-028 AC-6
                : status == 409 ? "Conflict" : "Service Unavailable");
        String rid;
        try {
            rid = req.requestId();
        } catch (UnsupportedOperationException none) {
            rid = "";
        }
        error.put("requestId", rid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", error);
        out.put("applied", new ArrayList<Object>(applied));
        json(res, status, out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Request req) {
        RuntimeException failed = req.attributeAs(BODY_ERROR, RuntimeException.class);
        if (failed != null) {
            throw failed; // the body read of the key ban step failed: the same 400 or 413
        }
        return (Map<String, Object>) AccountEndpoints.jsonObject(AccountEndpoints.parse(req));
    }

    private static @Nullable String string(Map<String, Object> body, String name, boolean required) {
        Object v = body.get(name);
        if (v == null) {
            if (required) {
                throw new ShownError(400, name + " is required");
            }
            return null;
        }
        if (!(v instanceof String)) {
            throw new ShownError(400, name + " must be a string");
        }
        return (String) v;
    }

    private static boolean bool(Map<String, Object> body, String name, boolean dflt) {
        Object v = body.get(name);
        if (v == null) {
            return dflt;
        }
        if (!(v instanceof Boolean)) {
            throw new ShownError(400, name + " must be true or false");
        }
        return (Boolean) v;
    }

    private static List<String> strings(Map<String, Object> body, String name) {
        Object v = body.get(name);
        if (v == null) {
            return Collections.emptyList();
        }
        if (!(v instanceof List)) {
            throw new ShownError(400, name + " must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) v) {
            if (!(o instanceof String)) {
                throw new ShownError(400, name + " must be an array of strings");
            }
            out.add((String) o);
        }
        return out;
    }

    private static Map<String, String> attributes(Map<String, Object> body) {
        Object v = body.get("attributes");
        if (v == null) {
            return Collections.emptyMap();
        }
        if (!(v instanceof Map)) {
            throw new ShownError(400, "attributes must be an object of strings");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
            Object val = e.getValue();
            if (!(e.getKey() instanceof String) || (val != null && !(val instanceof String))) {
                throw new ShownError(400, "attributes must be an object of strings");
            }
            out.put((String) e.getKey(), (String) val);
        }
        return out;
    }
}
