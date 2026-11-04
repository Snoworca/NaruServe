package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.Configurer;
import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.CookieOptions;
import com.snoworca.naruserve.ErrorHandler;
import com.snoworca.naruserve.ErrorMiddleware;
import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Next;
import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RegistrationKind;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.WebSocketHandler;
import com.snoworca.naruserve.WebSocketSession;
import com.snoworca.naruserve.common.MediaTypes;
import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.apikey.AuthApiKeys;
import com.snoworca.naruserve.utils.auth.audit.AuditSink;
import com.snoworca.naruserve.utils.auth.audit.AuditSinks;
import com.snoworca.naruserve.utils.auth.group.FileGroupStore;
import com.snoworca.naruserve.utils.auth.group.GroupLimits;
import com.snoworca.naruserve.utils.auth.group.GroupStore;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import com.snoworca.naruserve.utils.auth.internal.AccessTokens;
import com.snoworca.naruserve.utils.auth.internal.ApiKeyUsage;
import com.snoworca.naruserve.utils.auth.internal.GrantRule;
import com.snoworca.naruserve.utils.auth.internal.Lockouts;
import com.snoworca.naruserve.utils.auth.internal.PrincipalCache;
import com.snoworca.naruserve.utils.auth.internal.RehashQueue;
import com.snoworca.naruserve.utils.auth.internal.AuditTrail;
import com.snoworca.naruserve.utils.auth.internal.AuthArgumentException;
import com.snoworca.naruserve.utils.auth.internal.AuthCookies;
import com.snoworca.naruserve.utils.auth.internal.Credential;
import com.snoworca.naruserve.utils.auth.internal.CsrfTokens;
import com.snoworca.naruserve.utils.auth.internal.DeviceTokens;
import com.snoworca.naruserve.utils.auth.internal.Hs256TokenCodec;
import com.snoworca.naruserve.utils.auth.internal.CredentialResolver;
import com.snoworca.naruserve.utils.auth.internal.Doctor;
import com.snoworca.naruserve.utils.auth.internal.EffectiveGrants;
import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import com.snoworca.naruserve.utils.auth.internal.ErrorBodies;
import com.snoworca.naruserve.utils.auth.internal.EventCalls;
import com.snoworca.naruserve.utils.auth.internal.FailureFloor;
import com.snoworca.naruserve.utils.auth.internal.LoginLimits;
import com.snoworca.naruserve.utils.auth.internal.HashSlots;
import com.snoworca.naruserve.utils.auth.internal.HashTiming;
import com.snoworca.naruserve.utils.auth.internal.Passwords;
import com.snoworca.naruserve.utils.auth.internal.SecretRules;
import com.snoworca.naruserve.utils.auth.internal.TokenVerifier;
import com.snoworca.naruserve.utils.auth.internal.TransportCredentials;
import com.snoworca.naruserve.utils.auth.internal.Usernames;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import com.snoworca.naruserve.utils.auth.password.HashCost;
import com.snoworca.naruserve.utils.auth.password.PasswordHasher;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.rbac.RoleStore;
import com.snoworca.naruserve.utils.auth.rbac.Scopes;
import com.snoworca.naruserve.utils.auth.spi.AuthResponses;
import com.snoworca.naruserve.utils.auth.spi.Authenticator;
import com.snoworca.naruserve.utils.auth.spi.BreachedPasswordChecker;
import com.snoworca.naruserve.utils.auth.spi.DeviceTrust;
import com.snoworca.naruserve.utils.auth.spi.TokenCodec;
import com.snoworca.naruserve.utils.auth.spi.KeySource;
import com.snoworca.naruserve.utils.auth.spi.LoginThrottle;
import com.snoworca.naruserve.utils.auth.spi.RefreshTokenStore;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.AuthUsers;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Authentication and authorization for one app: build it once with {@link #builder(String)}, call
 * {@link #install(RestApp)} before any route, and guard routes with {@link #require(String...)}. Every path is
 * protected unless the builder made it public ({@code .permit}, {@code .permitPrefix}, {@code .protectOnly}).
 * Every response auth writes is JSON. Express counterpart: {@code app.use(passport.initialize())} plus
 * {@code express-jwt} and a scope guard such as {@code express-jwt-authz}.
 *
 * <pre>{@code
 * Auth auth = Auth.builder(System.getenv("JWT_SECRET")).mode(AuthMode.fromEnv()).permit("/health").build();
 * auth.install(app);
 * app.post("/posts", auth.require("posts:write"), (req, res) -> res.status(201).json(...));
 * }</pre>
 *
 * <p>An Auth is the global auth middleware ({@code install} attaches it with {@code app.use(auth)}): in order it
 * decides whether the path is public, checks the secure-transport rule in PROD, reads the credential and loads the
 * user. {@link #toString()} never shows a key.
 */
// @req SEC-AUTH-001
// @req FR-AUTH-005
// @req IR-AUTH-004
// @req FR-AUTH-006
// @req SEC-FLOW-001
// @req IR-AUTH-002
// @req FR-AUTH-004
// @req SEC-AUTH-004
// @req SEC-AUTH-005
// @req SEC-FLOW-004
// @req SEC-FLOW-005
public final class Auth implements Middleware, com.snoworca.naruserve.Installable {

    /** The request attribute holding the {@link AuthPrincipal}. */
    static final String PRINCIPAL = AuditTrail.PRINCIPAL_ATTRIBUTE;

    static {
        com.snoworca.naruserve.utils.auth.internal.TestingBridge.install(new AuthTestingOps());
    }

    /** The default {@code .maxKeysPerUser}: 20 live keys per user. */
    static final int DEFAULT_MAX_KEYS_PER_USER = 20;
    /** The default {@code .maxApiKeyDays}: 365 days. */
    static final int DEFAULT_MAX_API_KEY_DAYS = 365;
    /** The default {@code .apiKeyFailureLimit}: 100 failures ... */
    static final int DEFAULT_API_KEY_FAILURES = 100;
    /** ... per 15 minutes. */
    static final long DEFAULT_API_KEY_FAILURE_WINDOW_MILLIS = 15 * 60_000L;
    /** The environment variable {@code Builder.recoveryFromEnv()} reads. */
    public static final String RECOVERY_ENV = "NARU_AUTH_RECOVERY";
    /** The request attribute marking a request whose credential was an API key (for the WWW-Authenticate shape). */
    static final String KEY_REQUEST = "naru.auth.keyRequest";

    /** How often the cleanup removes expired refresh records (and expired API keys): every 10 minutes. */
    static final long CLEANUP_INTERVAL_MILLIS = 600_000L;

    private static final AtomicInteger CLEANUP_THREADS = new AtomicInteger();

    /** The default refresh store of each FileUserStore, shared by every Auth built over that store. */
    private static final java.util.Map<FileUserStore, FileRefreshTokenStore> FILE_REFRESH_STORES =
            new java.util.WeakHashMap<>();
    /** The default login throttle of each FileUserStore, shared like the refresh store (one file, one writer). */
    private static final java.util.Map<FileUserStore, FileLoginThrottle> FILE_THROTTLES = new java.util.WeakHashMap<>();
    /** The default device trust of each FileUserStore, shared like the refresh store. */
    private static final java.util.Map<FileUserStore, FileDeviceTrust> FILE_DEVICE_TRUSTS =
            new java.util.WeakHashMap<>();

    /**
     * The clock and logger the shared file defaults of each FileUserStore read through (M2-F19, R2-M1-F08): every
     * build() and install() of an Auth over that store re-points them to that Auth, so the store never keeps the first
     * builder's clock or a stopped app's logger.
     */
    private static final java.util.Map<FileUserStore, SharedTargets> FILE_TARGETS = new java.util.WeakHashMap<>();

    /** How often the file-backed login throttle and device trust write their snapshot: every 60 seconds. */
    static final long SNAPSHOT_INTERVAL_MILLIS = 60_000L;

    private static final String[] PROXY_HEADERS = {"X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host",
        "X-Forwarded-Port", "X-Forwarded-Prefix", "X-Forwarded-Server", "X-Forwarded-Ssl", "Forwarded"};

    /** The security field names that are never user attributes (00 R37 12th edition). */
    private static final Set<String> RESERVED_ATTRIBUTES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("passwordHash", "ver", "enabled", "roles")));

    /** The account endpoints a request may reach without a principal; they read their own credentials. */
    private static final List<String> ANONYMOUS_ENTRY = Collections.unmodifiableList(Arrays.asList(
            "/login", "/refresh", "/logout", "/register", "/setup"));

    /** The auth endpoints that take only POST (OPS-AUTH-014 AC-2). */
    private static final List<String> POST_ONLY = Collections.unmodifiableList(Arrays.asList(
            "/login", "/refresh", "/logout", "/register", "/setup", "/password"));

    /** The account endpoints, below the mount path, whose POST is a credential endpoint (R33). */
    private static final List<String> CREDENTIAL = Collections.unmodifiableList(Arrays.asList(
            "/login", "/refresh", "/logout", "/register", "/setup", "/password", "/me/apikeys"));

    private final AuthMode mode;
    private final AuthProfile profile;
    private final Deployment deployment;
    private final TokenTransport transport;
    private final String mountPath;
    private final String adminPath;
    private final List<PathRule> permits;
    private final List<String> permitPrefixes;
    private final List<String> protectOnly;
    private final List<String> permitWebSockets;
    private final boolean statelessAccess;
    private final UserStore users;
    /** The key source's key file when it is {@code KeySources.fromFile}; null otherwise (FR-IAM-015 doctor count). */
    private final KeySources.@Nullable FileKey keyFile;
    private final RefreshTokenStore refreshTokens;
    private final @Nullable ApiKeyStore apiKeys;
    private final AuditTrail audit;
    private final LoginThrottle throttle;
    private final DeviceTrust deviceTrust;
    /** Whether the refresh store, login throttle and device trust were set on the builder (not defaults). */
    private final boolean explicitRefresh;
    private final boolean explicitThrottle;
    private final boolean explicitDeviceTrust;
    private final TokenCodec tokenCodec;
    private final CsrfTokens csrfTokens;
    private final DeviceTokens deviceTokens;
    private final AuthCookies cookies;
    private final long cleanupIntervalMillis;
    private final long snapshotIntervalMillis;
    private final long loginWindowMillis;
    private volatile @Nullable ScheduledExecutorService cleanup;
    private final @Nullable GroupStore groups;
    private final EffectiveGrants grants;
    private final List<AuthEvents> events;
    private final @Nullable AuthResponses responses;
    private final TimeSource time;
    private final boolean explicitLogger;
    private volatile Logger logger;
    /** The logger handed to every component built with this auth; {@link #install} points it at the app logger. */
    private final ComponentLogger componentLogger;
    /** The re-pointable clock and logger of the shared file defaults this auth uses; null when it uses none. */
    private volatile @Nullable SharedTargets sharedTargets;
    private final byte[] signingKey;
    private final JwtKeys keySet;
    private final AccessTokens accessTokens;
    private final boolean temporaryKey;
    private final CredentialResolver credentials;
    /** The API key procedure in front of the token reading; null without {@code .apiKeys(store)}. */
    private final @Nullable ApiKeyCredentials keyCredentials;
    private final Passwords passwords;
    private final HashSlots hashSlots;
    private final RoleStore roleStore;
    private final List<Seed> seeds;
    private final boolean recovery;
    private final boolean testHashCost;
    private final @Nullable String insecureReason;
    private final String insecureOverrides;
    private final long accessTtlMillis;
    private final long refreshTtlMillis;
    private final Set<String> preAuthAllowed;
    private final List<Registration> preInstall = Collections.synchronizedList(new ArrayList<Registration>());
    private final ErrorHandler errorsHandler = this::appError;
    private final ErrorMiddleware errorsMiddleware = this::appErrorMiddleware;
    private final Middleware mountFallback = this::mountFallback;
    private final ErrorMiddleware prodFinalError = this::prodFinalError;
    /** Bumped by every registration; the error-layer check runs when it moved since the last complete check. */
    private final AtomicLong registrationGeneration = new AtomicLong(1);
    private final AtomicLong errorLayerCheckedGeneration = new AtomicLong(0);
    private final AtomicBoolean errorLayerWarnPending = new AtomicBoolean();
    private final AtomicLong errorLayerWarnedAt = new AtomicLong(Long.MIN_VALUE);
    private volatile @Nullable PendingSetup pendingSetup;
    /** The general recovery setup of a start under {@code .recovery(true)} without a pending {@code .user} setup. */
    private volatile @Nullable PendingSetup recoverySetup;
    private final String apiKeyScheme;
    private final int maxKeysPerUser;
    private final int maxApiKeyDays;
    private final boolean allowAdminScopesOnApiKeys;
    private final int apiKeyFailureMax;
    private final long apiKeyFailureWindowMillis;
    private final boolean registerEnabled;
    private final Set<String> allowedAttributes;
    private final NewPasswords newPasswords;
    private final Set<String> trustedOrigins;
    private final DefaultAuthApiKeys apiKeysFacade;
    /** The per-owner key lock, the future-key deletions and the pending markers (REL-APIKEY-003, REL-APIKEY-004). */
    private final ApiKeyOwners apiKeyOwners = new ApiKeyOwners(this);
    /** The {@code countKeysWithAdminScopes()} the last doctor read: -1 unknown (request-time WARN, once a minute), -2 not read. */
    private volatile int adminScopeKeys = -2;
    private final AtomicLong ignoredScopeWarnedAt = new AtomicLong(Long.MIN_VALUE);
    /** The built-in account endpoints, mounted after {@link #routes()} so an app route on the same path wins. */
    private final Router accountRoutes = Router.create();
    private final Router routes = Router.create();
    private final Router adminRoutes = Router.create();
    /** The mounted routers: the account or admin router, then the auth error middleware, so it always runs last. */
    private final Router mountedRoutes = Router.create();
    private final Router mountedAdminRoutes = Router.create();
    private final DefaultAuthUsers usersFacade;
    private final ErrorMiddleware routerErrors = this::routerError;
    private final AtomicBoolean routerErrorsAttached = new AtomicBoolean();
    private volatile @Nullable RestApp app;
    private final AtomicBoolean untrustedProxyWarned = new AtomicBoolean();
    private final AtomicBoolean multiNodeWarned = new AtomicBoolean();
    private final AtomicBoolean devExposedWarned = new AtomicBoolean();
    private final AtomicLong anonymousStoreErrorLoggedAt = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong credentialErrorLoggedAt = new AtomicLong(Long.MIN_VALUE);
    private final List<String> lateCors = Collections.synchronizedList(new ArrayList<String>());
    /** Registrations whose path protectOnly cannot judge (a Pattern or an array), seen before start. */
    private final List<String> unjudgeable = Collections.synchronizedList(new ArrayList<String>());
    /**
     * Set (holding {@link #unjudgeable}'s lock) when auth's onStart begins, before the doctor reads the list: from then
     * on an unjudgeable registration throws, so none can land in the list after the doctor judged it.
     */
    private boolean rangeClosed;
    private final AtomicBoolean started = new AtomicBoolean();
    private final PrincipalCache principals;
    private final TokenService tokens;
    private final GrantRule grantRule;
    private final GroupLimits groupLimits;
    private final @Nullable Lockouts lockouts;
    private final @Nullable ApiKeyUsage apiKeyUsage;
    private final RehashQueue rehash;
    private final PasswordLogin passwordLogin;
    private final TokenDelivery tokenDelivery;
    private volatile @Nullable FailureFloor failureFloor;
    private volatile @Nullable ScheduledThreadPoolExecutor floorScheduler;
    private static final AtomicInteger FLOOR_THREADS = new AtomicInteger();
    /** Off-request work: admin completions after a hash, lastUsedAt updates. Daemon threads, bounded queue. */
    private final ThreadPoolExecutor background;
    /** The open auth.webSocket connections per user id, for the revocation close. */
    private final ConcurrentHashMap<String, Set<TrackedSocket>> sockets = new ConcurrentHashMap<>();
    private volatile @Nullable ScheduledExecutorService socketTicker;
    private static final AtomicInteger BACKGROUND_THREADS = new AtomicInteger();
    /** The request attribute holding the access token expiry (epoch milliseconds) for the WebSocket wrapper. */
    static final String TOKEN_EXPIRES = "naru.auth.exp";

    private Auth(Builder b, JwtKeys keySet, Passwords passwords, List<Seed> seeds, ComponentLogger logger,
            RefreshTokenStore refreshTokens, @Nullable LoginThrottle fileThrottle, @Nullable DeviceTrust fileTrust) {
        this.mode = b.mode;
        this.profile = b.profile;
        this.deployment = b.deployment;
        this.transport = b.transport;
        this.mountPath = canonical(b.mountPath);
        this.adminPath = this.mountPath.equals("/") ? "/admin" : this.mountPath + "/admin";
        this.permits = Collections.unmodifiableList(new ArrayList<>(b.permits));
        this.permitPrefixes = Collections.unmodifiableList(new ArrayList<>(b.permitPrefixes));
        List<String> protect = new ArrayList<>();
        for (String p : b.protectOnly) {
            protect.add(canonical(p));
        }
        this.protectOnly = Collections.unmodifiableList(protect);
        this.permitWebSockets = Collections.unmodifiableList(new ArrayList<>(b.permitWebSockets));
        this.statelessAccess = b.statelessAccess;
        this.users = b.users != null ? b.users : new InMemoryUserStore();
        this.keyFile = b.keys instanceof KeySources.FileKey ? (KeySources.FileKey) b.keys : null;
        this.refreshTokens = refreshTokens;
        this.apiKeys = b.apiKeys;
        this.cleanupIntervalMillis = b.cleanupIntervalMillis;
        this.snapshotIntervalMillis = b.snapshotIntervalMillis;
        this.loginWindowMillis = b.loginWindowMillis;
        this.audit = new AuditTrail(b.audit != null ? b.audit : AuditSinks.none(), b.time, logger);
        this.throttle = b.loginThrottle != null ? b.loginThrottle
                : fileThrottle != null ? fileThrottle : defaultThrottle(b.time, logger);
        this.deviceTrust = b.deviceTrust != null ? b.deviceTrust
                : fileTrust != null ? fileTrust : defaultDeviceTrust(b.time, logger);
        this.explicitRefresh = b.refreshTokens != null;
        this.explicitThrottle = b.loginThrottle != null;
        this.explicitDeviceTrust = b.deviceTrust != null;
        this.cookies = new AuthCookies(this.mountPath, b.cookies);
        this.groups = b.groups;
        this.events = Collections.unmodifiableList(new ArrayList<>(b.events));
        this.responses = b.responses;
        this.time = b.time;
        this.explicitLogger = b.logger != null;
        this.componentLogger = logger;
        this.logger = logger.target();
        this.keySet = keySet;
        this.signingKey = keySet.getSigningKey();
        this.tokenCodec = b.tokenCodec != null ? b.tokenCodec : new Hs256TokenCodec(keySet, b.time);
        this.accessTokens = new AccessTokens(this.tokenCodec, b.time, logger);
        this.csrfTokens = new CsrfTokens(keySet);
        this.deviceTokens = new DeviceTokens(keySet, b.time);
        this.temporaryKey = b.temporaryKey;
        TokenVerifier verifier = b.tokenVerifier != null ? b.tokenVerifier : accessTokens;
        CredentialResolver tokenCredentials = new TransportCredentials(b.transport, verifier);
        this.keyCredentials = b.apiKeys != null ? new ApiKeyCredentials(this, tokenCredentials) : null;
        ApiKeyCredentials keyed = this.keyCredentials;
        this.credentials = b.credentials != null ? b.credentials : keyed != null ? keyed : tokenCredentials;
        this.apiKeyScheme = b.apiKeyScheme;
        this.maxKeysPerUser = b.maxKeysPerUser;
        this.maxApiKeyDays = b.maxApiKeyDays;
        this.allowAdminScopesOnApiKeys = b.allowAdminScopesOnApiKeys;
        this.apiKeyFailureMax = b.apiKeyFailureMax;
        this.apiKeyFailureWindowMillis = b.apiKeyFailureWindowMillis;
        this.registerEnabled = b.register;
        this.allowedAttributes = Collections.unmodifiableSet(new LinkedHashSet<>(b.allowedAttributes));
        this.trustedOrigins = Collections.unmodifiableSet(new LinkedHashSet<>(b.trustedOrigins));
        this.newPasswords = new NewPasswords(b.passwordPolicy != null ? b.passwordPolicy
                : PasswordPolicy.of(b.profile), b.breachedPasswords, this);
        this.apiKeysFacade = new DefaultAuthApiKeys(this);
        this.passwords = passwords;
        HashSlots slots = passwords.slots();
        this.hashSlots = slots != null ? slots : new HashSlots(1);
        this.seeds = Collections.unmodifiableList(new ArrayList<>(seeds));
        this.recovery = b.recovery;
        this.testHashCost = b.testHashCost;
        this.insecureReason = b.insecureReason;
        this.insecureOverrides = (b.accessTtl > 0 ? ".accessTtl(" + b.accessTtl + ")" : "")
                + (b.accessTtl > 0 && b.refreshTtl > 0 ? " and " : "")
                + (b.refreshTtl > 0 ? ".refreshTtl(" + b.refreshTtl + ")" : "");
        this.preAuthAllowed = Collections.unmodifiableSet(new LinkedHashSet<>(b.preAuthAllowed));
        RoleStore roles = b.roles != null ? b.roles : RolePolicy.builtin();
        this.roleStore = roles;
        this.grants = new EffectiveGrants(roles, b.groups, b.groupLimits, logger);
        this.groupLimits = b.groupLimits;
        this.principals = new PrincipalCache(this::snapshot, b.time, b.userCacheTtl);
        long[] ttl = ttls(b);
        this.accessTtlMillis = ttl[0];
        this.refreshTtlMillis = ttl[1];
        this.tokens = new TokenService(this.users, refreshTokens, this.tokenCodec, b.time, ttl[0], ttl[1],
                b.reuseGraceMillis, this.events, logger);
        this.tokens.deviceTokens(this.deviceTokens); // OPS-AUTH-026 AC-3
        this.accessTokens.deviceTokens(this.deviceTokens);
        this.grantRule = new GrantRule(this.users, this.grants, this.audit);
        this.lockouts = b.lockout == null ? null
                : new Lockouts(b.lockout.getFailures(), b.lockout.getDurationMillis(), b.time);
        if (this.lockouts != null) { // OPS-AUTH-030 AC-5
            this.lockouts.onLock((userId, until) -> this.logger.warn("auth: user id " + userId + " is locked out until "
                    + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(until)) + " after "
                    + b.lockout.getFailures() + " failed logins; its correct password is refused like a wrong one "
                    + "until then. Release it with DELETE " + join(adminMount(), "/users/" + userId
                    + "/lockout")));
        }
        this.background = new ThreadPoolExecutor(4, 4, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1000),
                r -> {
                    Thread t = new Thread(r, "naru-auth-background-" + BACKGROUND_THREADS.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.background.allowCoreThreadTimeOut(true);
        this.apiKeyUsage = b.apiKeys == null ? null : new ApiKeyUsage(b.apiKeys, background, b.time, logger);
        this.rehash = new RehashQueue(this.users, passwords, logger);
        this.tokenDelivery = new TokenDelivery(this.cookies, this.csrfTokens, b.insecureReason != null);
        this.passwordLogin = new PasswordLogin(this.users, new LoginLimits(this.throttle, b.loginUserIpMax,
                b.loginIpMax, b.loginUserMax, b.loginWindowMillis), this.deviceTokens,
                this.deviceTrust, passwords, this.rehash, this.background, this.lockouts, b.authenticator);
        this.usersFacade = new DefaultAuthUsers(this.users, b.groups, b.apiKeys, this.events, passwords, b.time, logger,
                this.throttle, (id, revoked) -> {
                    if (revoked) {
                        userRevoked(id);
                    } else {
                        userChanged(id);
                    }
                });
        this.usersFacade.passwords(this.newPasswords);
        this.grants.groupRoleWarning(given -> warnUndeclaredRoles(given, "a group grant")); // OPS-AUTH-026 AC-2
        this.usersFacade.roleWarning(given -> warnUndeclaredRoles(given, "auth.users()")); // OPS-AUTH-022 AC-4
        this.usersFacade.keyOwners(this.apiKeyOwners);
        this.usersFacade.confirmationHolds(this.confirmationHolds);
        new AdminEndpoints(this).register(adminRoutes, b.apiKeys);
        new AccountEndpoints(this).register(accountRoutes, b.register, b.apiKeys);
    }

    /**
     * The effective token lifetimes of {@code b}, access then refresh: the configured or profile lifetimes, except
     * under {@code .allowInsecureTransport(reason)}, which forces 5 minutes and 24 hours (00 R33).
     */
    // @req SEC-FLOW-002
    static long[] ttls(Builder b) {
        if (b.insecureReason != null) {
            return new long[] {INSECURE_ACCESS_TTL_MILLIS, INSECURE_REFRESH_TTL_MILLIS};
        }
        long[] ttl = lifetimes(b.profile);
        return new long[] {b.accessTtl > 0 ? b.accessTtl : ttl[0], b.refreshTtl > 0 ? b.refreshTtl : ttl[1]};
    }

    /** The access token lifetime forced by {@code .allowInsecureTransport(reason)}: 5 minutes. */
    static final long INSECURE_ACCESS_TTL_MILLIS = 5 * 60_000L;
    /** The refresh token lifetime forced by {@code .allowInsecureTransport(reason)}: 24 hours. */
    static final long INSECURE_REFRESH_TTL_MILLIS = 24 * 3_600_000L;

    /**
     * R42: the login throttle used when none is set and the user store is not a FileUserStore: memory (the file
     * default beside a FileUserStore is made in {@code build()}, where its read can fail the build). Auth ships no
     * database store (CON-IAM-003); an app over its own database sets {@code .loginThrottle(..)}.
     */
    // @req REL-FLOW-001
    // @req CON-IAM-003
    static LoginThrottle defaultThrottle(TimeSource time, Logger logger) {
        return InMemoryLoginThrottle.builder().timeSource(time).logger(logger).build();
    }

    /** R42: the device trust used when none is set and the user store is not a FileUserStore: memory. */
    // @req REL-FLOW-001
    // @req SEC-FLOW-013
    // @req CON-IAM-003
    static DeviceTrust defaultDeviceTrust(TimeSource time, Logger logger) {
        return new InMemoryDeviceTrust(time, InMemoryLoginThrottle.defaultMaxKeys(System.getProperty("java.vm.name"),
                System.getProperty("java.vendor")), logger);
    }

    /**
     * The token lifetimes of a profile in milliseconds, access then refresh: BASELINE 15 minutes / 14 days,
     * STANDARD 15 minutes / 1 day, STRICT 5 minutes / 12 hours.
     */
    // @req FR-TOKEN-001
    static long[] lifetimes(AuthProfile profile) {
        switch (profile) {
            case BASELINE:
                return new long[] {15 * 60_000L, 14 * 24 * 3_600_000L};
            case STRICT:
                return new long[] {5 * 60_000L, 12 * 3_600_000L};
            default:
                return new long[] {15 * 60_000L, 24 * 3_600_000L};
        }
    }

    /** One bootstrap {@code .user(...)}: the normalized name, the hash made in build() (null: no password), roles. */
    static final class Seed {
        final String name;
        final @Nullable String hash;
        final List<String> roles;

        Seed(String name, @Nullable String hash, List<String> roles) {
            this.name = name;
            this.hash = hash;
            this.roles = roles;
        }
    }

    /**
     * An open setup: the one a null-password admin {@code .user} opened under recovery (its username and roles fixed),
     * or the general recovery of {@code .recovery(true)} ({@code username} null: any name, the built-in admin role).
     * The setup endpoint consumes it once; it then stays closed for the process.
     */
    static final class PendingSetup {
        final @Nullable String username;
        final List<String> roles;
        final String token;
        final AtomicBoolean used = new AtomicBoolean();

        PendingSetup(@Nullable String username, List<String> roles, String token) {
            this.username = username;
            this.roles = roles;
            this.token = token;
        }

        boolean recovery() {
            return username == null;
        }
    }

    /**
     * A builder signing with {@code secret} ({@code KeySources.of(secret)}); null, shorter than 32 bytes or a known
     * example makes {@link Builder#build()} throw {@link AuthConfigException} in PROD with instructions to generate
     * one. Pass a String-typed expression such as {@code System.getenv("JWT_SECRET")}.
     */
    // @req SEC-AUTH-001
    public static Builder builder(@Nullable String secret) {
        Builder b = new Builder(KeySources.of(secret));
        b.secretWhitespace = secret != null && !secret.isEmpty() && !secret.equals(secret.trim()); // OPS-AUTH-023 AC-5
        return b;
    }

    /** A builder signing with the key of {@code keys}. */
    // @req SEC-AUTH-001
    public static Builder builder(KeySource keys) {
        return new Builder(Objects.requireNonNull(keys, "keys"));
    }

    // ---------------------------------------------------------------------------------------------- install

    /**
     * Attaches auth to {@code app}; call it before any route. Throws {@link IllegalStateException} when the app
     * already holds a route, WebSocket route, static mount, router mount or group (a second install is one of those).
     * Middleware without a path ({@code cors}, {@code accessLog}, {@code use(mw)}, error middleware, {@code param})
     * may come first. {@code app.use(auth)} does the same: Core sees an {@link com.snoworca.naruserve.Installable} and
     * calls this method (FR-AUTH-010). It attaches this auth middleware once with {@code app.use("", auth)}, which also
     * runs for WebSocket upgrades; mounts {@link #routes()} at the mount path (default {@code /auth}) and {@link #adminRoutes()} at the
     * mount path plus {@code /admin}, each inside a router whose last layer is the auth error middleware (so routes
     * added to them later are covered too); and runs the startup checks in
     * {@code onStart}, where any FAIL stops the start before the port is bound.
     */
    // @req FR-AUTH-005
    // @req FR-AUTH-010
    @Override
    public void install(RestApp app) {
        Objects.requireNonNull(app, "app");
        if (this.app != null) { // OPS-AUTH-008 AC-2
            throw new IllegalStateException("auth is already installed" + (this.app == app ? " on this app" : " on "
                    + "another app") + "; call app.use(auth) or auth.install(app) once, and build one Auth per app");
        }
        boolean otherAuth = false;
        for (Registration r : app.registrations()) {
            if (r.getKind() == RegistrationKind.ROUTER_MOUNT && mountPath.equals(r.getPath())) {
                otherAuth = true; // OPS-AUTH-024 AC-7: named below unless an app route is in the way too
            }
        }
        for (Registration r : app.registrations()) {
            RegistrationKind k = r.getKind();
            String at = r.getPath();
            if (otherAuth && at != null && (at.equals(mountPath) || at.startsWith(mountPath + "/"))) {
                continue; // the other Auth's own endpoints
            }
            if (k == RegistrationKind.ROUTE || k == RegistrationKind.WEBSOCKET || k == RegistrationKind.STATIC
                    || k == RegistrationKind.ROUTER_MOUNT || k == RegistrationKind.GROUP) {
                throw new IllegalStateException("auth.install(app) must come before routes; found " + describe(r)
                        + " registered before it");
            }
        }
        if (otherAuth) {
            throw new IllegalStateException("another Auth is already installed on this app (its endpoints are mounted "
                    + "at " + mountPath + "); build one Auth per app and install it once");
        }
        if (app.state() != AppState.NEW) { // OPS-AUTH-021 AC-5
            throw new IllegalStateException("auth.install(app) must run before app.listen(...); the app is "
                    + app.state() + ", so install auth right after RestApp.create() and before the routes");
        }
        if (!explicitLogger) {
            logger = app.logger();
            componentLogger.target(logger); // every component built with this auth logs to the app from now on
            rehash.logger(logger);
            tokens.logger(logger);
        }
        SharedTargets shared = sharedTargets;
        if (shared != null) {
            shared.repoint(componentLogger, time); // the shared file defaults now log to this app and read this clock
        }
        this.app = app;
        for (Registration r : app.registrations()) {
            if (r.getKind() == RegistrationKind.MIDDLEWARE || r.getKind() == RegistrationKind.PATH_MIDDLEWARE) {
                preInstall.add(r);
            }
        }
        app.onRegistration(r -> {
            watchRange(r);
            if (r.getKind() == RegistrationKind.CORS) {
                lateCors.add(describe(r));
            }
            registrationGeneration.incrementAndGet();
        });
        app.use("", this); // not use(this): that would call install again (FR-AUTH-010)
        if (routerErrorsAttached.compareAndSet(false, true)) {
            mountedRoutes.use(routes);
            mountedRoutes.use(accountRoutes);
            mountedRoutes.use(routerErrors);
            mountedAdminRoutes.use(adminRoutes);
            mountedAdminRoutes.use(routerErrors);
        }
        app.use(mountPath, mountedRoutes);
        app.use(adminPath, mountedAdminRoutes);
        app.onStart(ctx -> {
            synchronized (unjudgeable) {
                rangeClosed = true; // SEC-AUTH-006: a registration racing the rest of onStart is refused, not missed
            }
            doctor(app).conclude(logger);
            appServesRegister = appRoute(app, join(mountPath, "/register"));
            app.use(mountPath, mountFallback); // OPS-AUTH-033 AC-2: after every route, JSON instead of Core's HTML 404
            if (!underPrefix(adminPath, mountPath)) {
                app.use(adminPath, mountFallback);
            }
            if (errorLayerMissing(app)) {
                app.use(errorsMiddleware); // OPS-AUTH-012 AC-1: auth exceptions of app handlers stay JSON, never Core HTML
            }
            if (mode == AuthMode.PROD) {
                app.use(prodFinalError); // OPS-AUTH-033 AC-1: last, so the app's own error layers answer first
            }
            startupFacts();
            seed();
            recoveryFacts();
            startCleanup();
            started.set(true);
        });
        app.onStop(ctx -> {
            stopSocketTicker();
            rehash.stop();
            ScheduledThreadPoolExecutor floor = floorScheduler;
            if (floor != null) {
                floor.shutdown(); // queued failure answers still go out; no new ones are accepted
            }
            abandonAll(background.shutdownNow(), logger);
            hashSlots.shutdown();
            boolean interrupted = false;
            try {
                // REL-FLOW-001: a hash or login completion still running records its failure before the final snapshot
                if (!hashSlots.awaitTermination(5, TimeUnit.SECONDS)
                        || !background.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("auth: a login still running did not finish within 5 seconds; its failure count may "
                            + "miss the final snapshot");
                }
            } catch (InterruptedException e) {
                interrupted = true;
                logger.warn("auth: the stop was interrupted while a login was still running; its failure count may "
                        + "miss the final snapshot");
            }
            // FR-TOKEN-002: an interrupt never skips the cleanup stop and the final snapshot; it is restored after
            try {
                stopCleanup();
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Answers every task a stopping background pool dropped: admin continuations and login completions are abandoned
     * (each answers 503); a failing answer is logged and the remaining tasks are still abandoned.
     */
    // @req SEC-TOKEN-009
    static void abandonAll(List<Runnable> dropped, Logger log) {
        for (Runnable r : dropped) {
            try {
                if (r instanceof AdminEndpoints.Continuation) {
                    ((AdminEndpoints.Continuation) r).abandon();
                } else if (r instanceof PasswordLogin.Completion) {
                    ((PasswordLogin.Completion) r).abandon();
                }
            } catch (RuntimeException e) {
                log.error("auth: abandoning a queued task at stop failed; the other tasks are still abandoned", e);
            }
        }
    }

    /**
     * R28 under protectOnly: a registration whose path is a Pattern or an array cannot be judged against the range,
     * so before start it fails the start and after start the registration call throws (the watcher runs before the
     * registration applies, so it is not served). Without protectOnly nothing is vetoed.
     */
    // @req SEC-AUTH-006
    private void watchRange(Registration r) {
        if (protectOnly.isEmpty() || r.isStringPath()) {
            return;
        }
        RegistrationKind k = r.getKind();
        if (k != RegistrationKind.ROUTE && k != RegistrationKind.WEBSOCKET && k != RegistrationKind.STATIC
                && k != RegistrationKind.ROUTER_MOUNT && k != RegistrationKind.PATH_MIDDLEWARE
                && k != RegistrationKind.GROUP) {
            return;
        }
        String what = describe(r);
        synchronized (unjudgeable) {
            if (rangeClosed || started.get()) {
                throw new IllegalStateException("protectOnly cannot judge the path of " + what + " (a regular "
                        + "expression or an array); register string paths under .protectOnly(...)");
            }
            unjudgeable.add(what);
        }
    }

    /**
     * R42: a daemon thread that every 10 minutes removes the expired refresh records and, with {@code .apiKeys(store)},
     * the expired API keys on the same run; a failure is logged. It stops in onStop.
     */
    // @req FR-TOKEN-002
    private void startCleanup() throws InterruptedException {
        stopCleanup();
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "naru-auth-cleanup-" + CLEANUP_THREADS.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        ex.scheduleWithFixedDelay(this::cleanupRun, cleanupIntervalMillis, cleanupIntervalMillis,
                TimeUnit.MILLISECONDS);
        if (throttle instanceof FileLoginThrottle || deviceTrust instanceof FileDeviceTrust) {
            ex.scheduleWithFixedDelay(this::scheduledSnapshot, snapshotIntervalMillis, snapshotIntervalMillis,
                    TimeUnit.MILLISECONDS);
        }
        cleanup = ex;
    }

    private void stopCleanup() throws InterruptedException {
        ScheduledExecutorService ex = cleanup;
        cleanup = null;
        if (ex != null) {
            ex.shutdownNow();
            InterruptedException interrupted = null;
            try {
                if (!ex.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("auth: the cleanup thread did not stop within 5 seconds");
                }
            } catch (InterruptedException e) {
                interrupted = e; // the thread was told to stop; the final snapshot still runs
            }
            snapshotRun("auth is stopping, so the counters since the last snapshot are not kept"); // survive a restart
            if (interrupted != null) {
                throw interrupted;
            }
        }
    }

    /**
     * The throttle retention of the cleanup: 24 hours, or the longest window auth counts with (the login limits, the
     * API key limit, the password confirmation limit) when that is longer. The in-memory and file defaults measure it
     * from a key's last failure. An app-provided throttle keeps its own retention; auth never purges it.
     */
    // @req REL-FLOW-001
    long throttleRetentionMillis() {
        long r = DEFAULT_THROTTLE_RETENTION_MILLIS;
        r = Math.max(r, loginWindowMillis);
        r = Math.max(r, apiKeyFailureWindowMillis);
        return Math.max(r, DefaultAuthUsers.CONFIRM_WINDOW_MILLIS);
    }

    /** The shortest retention of the default throttle counters: 24 hours. */
    static final long DEFAULT_THROTTLE_RETENTION_MILLIS = 24 * 3_600_000L;

    /**
     * REL-FLOW-001: the 60-second snapshot of the file-backed login throttle and device trust (beside a FileUserStore);
     * each writes only when its counters changed. A failure is logged and retried at the next tick.
     */
    // @req REL-FLOW-001
    void snapshotRun() {
        snapshotRun("it is tried again at the next snapshot, in " + snapshotIntervalMillis / 1000 + " s");
    }

    private final AtomicLong snapshotTicks = new AtomicLong();

    /** One tick of the snapshot scheduler: {@link #snapshotRun()}, then the tick is counted. */
    private void scheduledSnapshot() {
        try {
            snapshotRun();
        } finally {
            snapshotTicks.incrementAndGet();
        }
    }

    /** How many ticks of the snapshot scheduler have finished; for the tests. */
    long snapshotTicks() {
        return snapshotTicks.get();
    }

    private void snapshotRun(String retry) {
        if (throttle instanceof FileLoginThrottle) {
            try {
                ((FileLoginThrottle) throttle).snapshot();
            } catch (IOException | RuntimeException e) {
                logger.error("auth: the login throttle snapshot could not be written; " + retry, e);
            }
        }
        if (deviceTrust instanceof FileDeviceTrust) {
            try {
                ((FileDeviceTrust) deviceTrust).snapshot();
            } catch (IOException | RuntimeException e) {
                logger.error("auth: the device trust snapshot could not be written; " + retry, e);
            }
        }
    }

    // @req FR-IAM-013
    // @req FR-APIKEY-002
    void cleanupRun() {
        long now = time.nowMillis();
        try {
            deviceTrust.purgeExpired(now);
        } catch (Exception e) {
            logger.error("auth: the cleanup of expired device tokens failed; it runs again in 10 minutes", e);
        }
        // REL-FLOW-001: the default counters forget a key once no limit window can still count its last failure
        long cutoff = now - throttleRetentionMillis();
        if (!explicitThrottle && throttle instanceof FileLoginThrottle) {
            ((FileLoginThrottle) throttle).purgeOlderThan(cutoff);
        } else if (!explicitThrottle && throttle instanceof InMemoryLoginThrottle) {
            ((InMemoryLoginThrottle) throttle).purgeOlderThan(cutoff);
        }
        try {
            refreshTokens.purgeExpired(now);
        } catch (Exception e) {
            logger.error("auth: the cleanup of expired refresh tokens failed; it runs again in 10 minutes", e);
        }
        ApiKeyStore keys = apiKeys;
        if (keys != null) {
            try {
                keys.deleteExpired(now);
            } catch (Exception e) {
                logger.error("auth: the cleanup of expired API keys failed; it runs again in 10 minutes", e);
            }
            try {
                apiKeyOwners.cleanupRun(); // REL-APIKEY-004: every pending marker is retried, re-read inside its lock
            } catch (RuntimeException e) {
                logger.error("auth: the retry of pending API key markers failed; it runs again in 10 minutes", e);
            }
        }
    }

    /** True while the cleanup scheduler runs (between onStart and onStop); for the tests. */
    boolean cleanupScheduled() {
        ScheduledExecutorService ex = cleanup;
        return ex != null && !ex.isShutdown();
    }

    private static String describe(Registration r) {
        String path = r.getPath();
        String where = path != null ? path : String.valueOf(r.getPathSource());
        if (r.getKind() == RegistrationKind.ROUTE) {
            String m = r.getMethod();
            return (m != null ? m : "ROUTE") + " " + where;
        }
        return where.isEmpty() ? r.getKind().name() : r.getKind().name() + " " + where; // OPS-AUTH-008 AC-6
    }

    /** The account endpoints, mounted by {@link #install(RestApp)} at the mount path. */
    // @req FR-AUTH-005
    public Router routes() {
        return routes;
    }

    /** The admin endpoints, mounted by {@link #install(RestApp)} at the mount path plus {@code /admin}. */
    // @req FR-AUTH-005
    public Router adminRoutes() {
        return adminRoutes;
    }

    /** The startup checks; package-private for the doctor tests. */
    // @req OPS-AUTH-001
    Doctor doctor(RestApp app) {
        Doctor d = new Doctor();
        try {
            users.findByUsername("__naru_probe__");
        } catch (Exception e) {
            d.fail("store connectivity", "the user store failed a probe read (" + e.getClass().getSimpleName()
                    + "); check its connection");
        }
        logger.info("auth doctor: public paths " + publicPathListing() + "; mount " + mountPath + ", admin "
                + adminPath + ", mode " + mode + ", profile " + profile + ", deployment " + deployment);
        boolean legacyTrust = app.trustProxy();
        boolean hopTrust = app.trustProxyHops() > 0;
        boolean anyTrust = legacyTrust || hopTrust || !app.trustProxyCidrs().isEmpty();
        if (legacyTrust && mode == AuthMode.PROD) {
            d.fail("proxy trust", "trustProxy(true) takes the left-most X-Forwarded-For entry, which any client "
                    + "sets; use trustProxyHops(n) or trustProxyCidrs(...)");
        }
        if (!anyTrust && deployment == Deployment.MULTI_INSTANCE) {
            d.warn("proxy trust", "MULTI_INSTANCE without proxy trust sees the load balancer as every client; set "
                    + "trustProxyHops(n) or trustProxyCidrs(...)");
        }
        if (hopTrust && (deployment == Deployment.MULTI_INSTANCE || profile == AuthProfile.STRICT)) {
            d.warn("proxy trust", "hop mode trusts whatever reaches this node; prefer trustProxyCidrs(...) with "
                    + "the load balancer addresses");
        }
        if (deployment == Deployment.SINGLE_INSTANCE && !isShippedUserStore(users)
                && !(explicitRefresh && explicitThrottle && explicitDeviceTrust)) {
            d.warn("restart logout", "with a custom UserStore the unset refresh token, login throttle or device trust "
                    + "stores fall back to memory, so a restart logs every user out; set .refreshTokens(..), "
                    + ".loginThrottle(..) and .deviceTrust(..) to keep sessions (new FileRefreshTokenStore(file) "
                    + "keeps refresh tokens in a file; implement LoginThrottle and DeviceTrust over your database, "
                    + "or with a FileUserStore auth keeps all three in files)");
        }
        undeclaredRoles(d);
        undeclaredScopes(d);
        permitMethods(app, d);
        for (String cors : lateCors) {
            d.fail("middleware order", "cors must be registered before auth.install(app); found " + cors
                    + " registered after it, so auth answers preflight requests before CORS can");
        }
        preInstallItems(d);
        rangeItems(app, d);
        if (insecureReason != null) {
            d.warn("secure transport", "the secure-transport check is off (.allowInsecureTransport: " + insecureReason
                    + "); tokens and passwords travel in clear text; access tokens last 5 minutes and refresh tokens "
                    + "24 hours under it" + (insecureOverrides.isEmpty() ? "" : " (it overrides " + insecureOverrides
                    + ")")); // OPS-AUTH-023 AC-2
        }
        if (refreshTtlMillis < accessTtlMillis) { // OPS-AUTH-024 AC-8
            d.warn("token lifetimes", "the refresh lifetime (" + refreshTtlMillis + " ms) is below the access lifetime ("
                    + accessTtlMillis + " ms), so a refresh token ends before the access token it renews; set "
                    + ".refreshTtl(...) above .accessTtl(...)");
        }
        Set<String> routePaths = new LinkedHashSet<>();
        for (Registration r : app.registrations()) {
            if (r.getKind() == RegistrationKind.ROUTE && r.getPath() != null) {
                routePaths.add(r.getPath());
            }
        }
        for (PathRule rule : permits) { // OPS-AUTH-028 AC-3
            if (routePaths.contains(rule.path)) {
                continue;
            }
            String bare = rule.path.length() > 1 && rule.path.endsWith("/")
                    ? rule.path.substring(0, rule.path.length() - 1) : rule.path;
            for (String route : routePaths) {
                if (route.equalsIgnoreCase(bare) || route.equalsIgnoreCase(bare + "/")) {
                    d.warn("permit path", ".permit(\"" + rule.path + "\") matches no route, but the route " + route
                            + " differs only by a slash or letter case; a permit is exact, so write .permit(\"" + route
                            + "\")");
                    break;
                }
            }
        }
        for (String p : permitPrefixes) { // OPS-AUTH-023 AC-4
            if (mountPath.equals(p) || mountPath.startsWith(p + "/")) {
                d.warn("permit prefix", ".permitPrefix(\"" + p + "\") covers the auth mount " + mountPath + ", but the "
                        + "auth endpoints keep their own rules: login, refresh and logout are public anyway, and "
                        + join(mountPath, "/me") + " and the admin endpoints still need a token");
                break;
            }
        }
        HashCost cost = passwords.cost();
        if (cost.isFixed() && cost.getIterations() < HashCost.MIN_ITERATIONS && !testHashCost) {
            String msg = ".hashCost(" + cost + ") is below " + HashCost.MIN_ITERATIONS + " PBKDF2 iterations; use "
                    + "HashCost.calibrate(...) or at least HashCost.fixed(" + HashCost.MIN_ITERATIONS + ")";
            if (mode == AuthMode.DEV) {
                d.warn("hash cost", msg + " (a WARN in DEV, a FAIL in PROD)");
            } else {
                d.fail("hash cost", msg);
            }
        }
        HashTiming ht = passwords.timing();
        passwords.prepare();
        logger.info("auth doctor: hash cost " + ht.iterations() + " PBKDF2 iterations, " + millis(ht.measuredNanosPerHash())
                + " ms per hash on this device (" + passwords.cost() + ", login timing base T "
                + millis(ht.timingNanos()) + " ms)");
        if (deployment == Deployment.MULTI_INSTANCE && !users.supportsAtomicFieldWrites()) {
            d.fail("atomic field writes", "deployment(MULTI_INSTANCE) needs a UserStore whose "
                    + "supportsAtomicFieldWrites() returns true: " + users.getClass().getSimpleName() + " does not "
                    + "declare atomic field writes (decorators extend ForwardingUserStore)");
        }
        if (errorLayerMissing(app)) {
            d.warn("errors placement", START_LAYER_ADVICE);
        }
        nullPasswordItems(d);
        if (statelessAccess) {
            d.warn("stateless access", ".statelessAccess(true): routes without a guard see a revocation only after "
                    + "the access token lifetime, and their minimal principals carry only the user id; use only "
                    + "getId() there, or add a guard");
        }
        webSocketItems(app, d);
        apiKeyItems(d);
        startupItems(d);
        fileStateItems(d);
        plainSecretItems(d);
        return d;
    }

    /** The doctor item of FR-IAM-015 AC-7. */
    static final String PLAIN_SECRETS_ITEM = "plain secret values in auth files";

    /**
     * FR-IAM-015 AC-7: with a FileUserStore or a {@code KeySources.fromFile} key, the count of secret values stored
     * plain: each non-null password hash of a FileUserStore without an AuthFileKey, plus a raw key file. A count above
     * zero is a WARN; zero is an INFO line; an Auth with neither file store reports nothing.
     */
    // @req FR-IAM-015
    private void plainSecretItems(Doctor d) {
        FileUserStore fileUsers = fileUserStoreIn(users); // a decorated one counts too (R2-M1-F09)
        KeySources.FileKey kf = keyFile;
        if (fileUsers == null && kf == null) {
            return;
        }
        int hashes = 0;
        List<String> where = new ArrayList<>();
        if (fileUsers != null && com.snoworca.naruserve.utils.auth.internal.FileStoreKeys.keyOf(fileUsers) == null) {
            FileUserStore fu = fileUsers;
            for (AuthUser u : fu.list(0, Integer.MAX_VALUE, null).getItems()) {
                if (u.getPasswordHash() != null) {
                    hashes++;
                }
            }
            if (hashes > 0) {
                where.add(hashes + (hashes == 1 ? " password hash in " : " password hashes in ")
                        + fu.getFile().getName());
            }
        }
        int keysPlain = kf != null && kf.fileKey() == null && kf.file().isFile() ? 1 : 0;
        if (keysPlain > 0 && kf != null) {
            where.add("the signing key in " + kf.file().getName());
        }
        int count = hashes + keysPlain;
        StringBuilder places = new StringBuilder();
        for (String w : where) {
            places.append(places.length() == 0 ? "" : ", ").append(w); // String.join is API 26
        }
        if (count == 0) {
            logger.info("auth doctor: " + PLAIN_SECRETS_ITEM + ": 0");
            return;
        }
        d.warn(PLAIN_SECRETS_ITEM, count + (count == 1 ? " secret value is" : " secret values are")
                + " stored unencrypted (" + places + "); set " + AuthFileKey.ENV_VARIABLE + " and pass "
                + "AuthFileKey.fromEnv() to new FileUserStore(file, AuthFileKey) and KeySources.fromFile(file, "
                + "AuthFileKey): the next start rewrites them as enc(...)");
    }

    /**
     * The module build date floor, 2025-01-01T00:00:00Z, a date before every build of this module: a clock before it
     * is wrong (a device whose battery ran out returns to 2000 or 1970), and tokens then misbehave.
     */
    static final long MODULE_BUILT_MILLIS = 1_735_689_600_000L;

    /**
     * Registration in PROD, the clock and, with API keys on, a LoginThrottle that does not use the limit arguments
     * (00 §4.2 doctor list; 04 §6.2 and §7.2; R47 (2)).
     */
    // @req OPS-AUTH-001
    // @req IR-FLOW-003
    private void startupItems(Doctor d) {
        if (registerEnabled && mode == AuthMode.PROD) {
            d.warn("registration", "sign-up is open (.register(true)): anyone who reaches " + join(mountPath, "/register")
                    + " can create an account; keep it on only when the app wants open sign-up");
        }
        long now = time.nowMillis();
        if (now < MODULE_BUILT_MILLIS) {
            d.warn("clock", "the clock reads " + now + " ms since 1970, before this module was built (2025-01-01); "
                    + "tokens will misbehave until the device clock is set");
        }
        if (apiKeys != null && !throttle.usesLimitArguments()) {
            d.warn("login throttle limits", "the .apiKeyFailureLimit values are not used: the LoginThrottle "
                    + throttle.getClass().getSimpleName() + " does not declare usesLimitArguments(), so API key "
                    + "failures are counted with its own limits; override the limit-argument methods and "
                    + "usesLimitArguments(), or extend ForwardingLoginThrottle");
        }
    }

    /**
     * R42: for each shipped file store in use, a directory that is not writable is a FAIL (every write would fail)
     * and a leftover {@code <name>.tmp} is a WARN (a write stopped before its rename; the main file is used).
     */
    // @req OPS-AUTH-001
    // @req REL-IAM-004
    private void fileStateItems(Doctor d) {
        List<java.io.File> files = new ArrayList<>();
        FileUserStore fileUsers = fileUserStoreIn(users);
        if (fileUsers != null) {
            files.add(fileUsers.getFile());
        }
        if (refreshTokens instanceof FileRefreshTokenStore) {
            files.add(((FileRefreshTokenStore) refreshTokens).getFile());
        }
        if (throttle instanceof FileLoginThrottle) {
            files.add(((FileLoginThrottle) throttle).getFile());
        }
        if (deviceTrust instanceof FileDeviceTrust) {
            files.add(((FileDeviceTrust) deviceTrust).getFile());
        }
        if (groups instanceof com.snoworca.naruserve.utils.auth.group.FileGroupStore) {
            files.add(((com.snoworca.naruserve.utils.auth.group.FileGroupStore) groups).getFile());
        }
        Set<java.io.File> missing = new LinkedHashSet<>();
        for (java.io.File f : files) {
            java.io.File dir = f.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.exists()) { // OPS-AUTH-021 AC-10: one FAIL per missing directory
                if (missing.add(dir)) {
                    d.fail("file state", "the directory " + dir + " of " + f.getName() + " does not exist; create it "
                            + "before start (the stores create their files, not the directory)");
                }
                continue;
            }
            if (dir == null || !dir.isDirectory() || !dir.canWrite()) {
                d.fail("file state", "the directory of " + f.getName() + " is not writable, so every write of the store "
                        + "would fail; give the app write access to " + dir);
            }
            java.io.File tmp = new java.io.File(dir, f.getName() + ".tmp");
            if (tmp.exists()) {
                d.warn("file state", tmp.getName() + " is left over from a write that stopped before its rename; "
                        + f.getName() + " is used, and the next write replaces the temp file");
            }
        }
    }

    /** The lasting-effects WARN of {@code .allowAdminScopesOnApiKeys(true)} (00 R47 (5)). */
    static final String ADMIN_SCOPES_WARNING = "API keys may hold auth-* admin scopes; role grants and reactivations "
            + "made by a key persist after the key is deleted. Review audit events by apiKeyId.";

    /**
     * OPS-APIKEY-001: under {@code MULTI_INSTANCE} a key store without {@code supportsAtomicLimitInsert()} is a FAIL
     * (only an atomic store keeps {@code maxKeysPerUser} without a cross-node lock); the admin-scope opt-in WARNs at
     * every start; with it off, keys still holding {@code auth-*} scopes are counted (WARN with the count), or, when the
     * store cannot count them (-1), each ignored scope WARNs at request time with the key id, once a minute.
     */
    // @req OPS-APIKEY-001
    private void apiKeyItems(Doctor d) {
        ApiKeyStore keys = apiKeys;
        if (keys == null) {
            return;
        }
        if (deployment == Deployment.MULTI_INSTANCE && !keys.supportsAtomicLimitInsert()) {
            d.fail("atomic limit insert", "deployment(MULTI_INSTANCE) needs an ApiKeyStore whose "
                    + "supportsAtomicLimitInsert() returns true: " + keys.getClass().getSimpleName() + " does not, so "
                    + "two nodes could each insert past maxKeysPerUser");
        }
        if (allowAdminScopesOnApiKeys) {
            d.warn("admin scopes on keys", ADMIN_SCOPES_WARNING);
            return;
        }
        int count;
        try {
            count = keys.countKeysWithAdminScopes();
        } catch (Exception e) {
            count = -1;
            logger.warn("auth: counting the API keys with auth-* scopes failed (" + e.getClass().getSimpleName()
                    + "); each ignored scope is reported when it is used");
        }
        adminScopeKeys = count;
        if (count > 0) {
            d.warn("admin scopes on keys", count + (count == 1 ? " API key holds" : " API keys hold") + " auth-* "
                    + "scopes, which are ignored while .allowAdminScopesOnApiKeys is off; revoke and reissue them");
        }
    }

    /**
     * OPS-APIKEY-001 AC-4: an {@code auth-*} scope of key {@code keyId} was ignored (opt-in off); WARNs with the key id
     * when the store could not count such keys at start, at most once a minute.
     */
    // @req OPS-APIKEY-001
    void ignoredAdminScope(String keyId) {
        if (adminScopeKeys != -1) {
            return;
        }
        long now = time.nowMillis();
        long last = ignoredScopeWarnedAt.get();
        if ((last == Long.MIN_VALUE || now - last >= 60_000L || now < last)
                && ignoredScopeWarnedAt.compareAndSet(last, now)) {
            logger.warn("auth: the auth-* scopes of API key " + keyId + " were ignored (.allowAdminScopesOnApiKeys is "
                    + "off); revoke and reissue keys that carry them (logged at most once a minute)");
        }
    }

    /**
     * R40: a WEBSOCKET registration without an {@code auth.webSocket} wrapper gets no expiry or revocation close;
     * COOKIE transport needs a non-wildcard WebSocket origin policy, which Core does not expose for reading yet.
     */
    // @req FR-FLOW-008
    private void webSocketItems(RestApp a, Doctor d) {
        int routes = 0;
        int wrapped = 0; // WEBSOCKET registrations whose handler is this auth's webSocket wrapper (M2-F12)
        for (Registration r : a.registrations()) {
            if (r.getKind() == RegistrationKind.WEBSOCKET) {
                routes++;
                Object target = r.getTarget();
                if (target instanceof WrappedWebSocket && ((WrappedWebSocket) target).auth == this) {
                    wrapped++;
                }
            }
        }
        if (routes > wrapped) {
            d.warn("websocket wrapper", routes + " WebSocket routes but " + wrapped + " use of "
                    + "auth.webSocket(handler); without it a connection is authenticated only at the upgrade and is "
                    + "never closed at token expiry or revocation");
        }
        if (routes > 0 && transport != TokenTransport.HEADER) {
            d.warn("websocket origin", "cookie transport with WebSocket routes: check that the WebSocket "
                    + "allowedOrigins is not a wildcard, or another site can open a connection with the user's "
                    + "cookies");
        }
    }

    private static final String ERROR_LAYER_ADVICE = "a route added after start is registered after the last auth "
            + "error layer, so an auth exception thrown by its handler gets the Core error page (HTML with a Java stack "
            + "trace) instead of JSON; add app.use(auth.errorMiddleware()) after the routes added after start (Core "
            + "refuses app.onError after start)";

    /** OPS-AUTH-010 AC-2, OPS-AUTH-012 AC-1: the doctor's errors-placement text; start appends the layer itself. */
    private static final String START_LAYER_ADVICE = "a route is registered after the last auth error layer, so Core "
            + "would answer an auth exception thrown by an app handler with its HTML error page and a Java stack trace; "
            + "auth therefore appends auth.errorMiddleware() after the routes at start, where your own error handlers "
            + "run before it. To place it yourself, add app.onError(auth.errors()) after the routes";

    /** True once start found an app route at {@code <mount>/register} (OPS-AUTH-012 AC-4). */
    private volatile boolean appServesRegister;

    // shortcut: only routes registered before start count; a route added at <mount>/register after start stays hidden.
    // With sign-up off auth registers nothing at that path, so any route there, on the app or on auth.routes(), is the app's
    private boolean appRoute(RestApp app, String path) {
        for (Registration r : app.registrations()) {
            if (r.getKind() == RegistrationKind.ROUTE && path.equals(r.getPath())
                    && (r.getMethod() == null || "POST".equals(r.getMethod()))) {
                return true;
            }
        }
        return false;
    }

    private static String millis(long nanos) {
        long tenths = Math.round(nanos / 100_000d);
        return (tenths / 10) + "." + (tenths % 10);
    }

    /** R28: path middleware before install runs before authentication; cors is tagged separately. */
    // @req SEC-AUTH-004
    private void preInstallItems(Doctor d) {
        List<String> listed = new ArrayList<>();
        for (Registration r : new ArrayList<>(preInstall)) {
            String path = r.getPath();
            String shown = path == null ? String.valueOf(r.getPathSource()) : path.isEmpty() ? "(all paths)" : path;
            listed.add(shown);
            if (r.getKind() == RegistrationKind.PATH_MIDDLEWARE && (path == null || !preAuthAllowed.contains(path))) {
                d.fail("pre-auth middleware", "app.use(" + shown + ", ...) is registered before auth.install(app), so "
                        + "it runs before authentication and can act as an unauthenticated endpoint; register it after "
                        + "install, or allow it with .allowPreAuthMiddleware(\"" + shown + "\")");
            }
        }
        logger.info("auth doctor: pre-auth middleware " + listed);
    }

    /**
     * R28: under protectOnly, every path a registration could not judge is a FAIL, each route or WebSocket route
     * outside the range is a WARN (it is public with optional authentication), and the range is logged.
     */
    // @req SEC-AUTH-006
    private void rangeItems(RestApp a, Doctor d) {
        if (protectOnly.isEmpty()) {
            return;
        }
        for (String what : new ArrayList<>(unjudgeable)) {
            d.fail("protectOnly range", what + " has a regular-expression or array path, which protectOnly cannot "
                    + "judge; register string paths");
        }
        logger.info("auth: " + protectedListing() + " (every other path is public with optional authentication)");
        for (Registration r : a.registrations()) {
            if ((r.getKind() != RegistrationKind.ROUTE && r.getKind() != RegistrationKind.WEBSOCKET)
                    || underAuthMount(r) || !r.isStringPath()) {
                continue;
            }
            String path = r.getPath();
            if (path != null && !inProtectedRange(path)) {
                d.warn("protectOnly range", describe(r) + " is outside the protected range " + protectedPrefixes()
                        + ", so it is public (optional authentication)");
            }
        }
    }

    /** {@code protected: <prefixes>}, the mount path and an admin mount outside it included. */
    private String protectedListing() {
        StringBuilder b = new StringBuilder("protected: ");
        List<String> all = protectedPrefixes();
        for (int i = 0; i < all.size(); i++) {
            b.append(i == 0 ? "" : ", ").append(all.get(i));
        }
        return b.toString();
    }

    private List<String> protectedPrefixes() {
        List<String> all = new ArrayList<>(protectOnly);
        if (!all.contains(mountPath)) {
            all.add(mountPath);
        }
        if (!underPrefix(adminPath, mountPath) && !all.contains(adminPath)) {
            all.add(adminPath);
        }
        return all;
    }

    /** The facts operators need from every start (00 R41, R46). */
    // @req OPS-AUTH-002
    private void startupFacts() {
        if (!seeds.isEmpty()) {
            logger.info("auth: .user accounts are created only when missing; .user never overwrites an existing "
                    + "account or its password");
        }
        if (temporaryKey) {
            logger.info("auth: DEV uses a temporary signing key; restarting the app invalidates all tokens");
        }
    }

    /**
     * R31 (6): one WARN per start while recovery is on. Without a setup a null-password {@code .user} opened (its own
     * token is already logged), the general recovery setup opens here with a new one-time token in that same line.
     */
    // @req SEC-AUTH-003
    // @req OPS-AUTH-002
    private void recoveryFacts() {
        if (!recovery) {
            return;
        }
        PendingSetup pending = pendingSetup;
        if (pending != null) {
            logger.warn("auth: recovery is enabled (.recovery(true)); turn it off once the account is recovered");
            return;
        }
        String token = randomToken(24);
        recoverySetup = new PendingSetup(null, Collections.singletonList("admin"), token);
        logger.warn("auth: recovery is enabled (.recovery(true)); one-time setup token: " + token + " (POST it with "
                + "{token, username, password} to " + join(mountPath, "/setup") + " from this machine to create or "
                + "reactivate an admin); turn recovery off once the account is recovered");
    }

    /** R41: the null-password {@code .user} rules, judged in onStart for accounts that do not exist yet. */
    // @req FR-AUTH-004
    private void nullPasswordItems(Doctor d) {
        int admins = 0;
        String adminName = null;
        for (Seed seed : seeds) {
            if (seed.hash != null) {
                continue;
            }
            try {
                if (users.findByUsername(seed.name) != null) {
                    continue; // an existing account: the null password is ignored
                }
            } catch (Exception e) {
                d.fail("null-password user", "could not check whether .user(\"" + seed.name + "\") exists ("
                        + e.getClass().getSimpleName() + "); check the user store");
                continue;
            }
            if (!grantsEverything(seed.roles)) {
                d.fail("null-password user", ".user(\"" + seed.name + "\", null, ...) has no password and no admin "
                        + "role; only one .user holding admin (a RolePolicy role granting *) may omit its password. "
                        + "If the password comes from System.getenv(...), is the environment variable that holds its "
                        + "password set?"); // OPS-AUTH-026 AC-4
            } else {
                admins++;
                adminName = seed.name;
            }
        }
        if (admins > 1) {
            d.fail("null-password user", admins + " admin .user calls have no password; at most one may omit it");
        } else if (admins == 1 && mode == AuthMode.PROD && !recovery) {
            d.fail("null-password user", ".user(\"" + adminName + "\", null, ...) has no password and the account "
                    + "does not exist; set its password (for example NARU_ADMIN_PASSWORD), or start once with "
                    + ".recovery(true) to log a one-time setup token");
        }
    }

    /** True when a role of {@code roles} grants {@code *} through a RolePolicy; database roles never count. */
    private boolean grantsEverything(List<String> roles) {
        if (!(roleStore instanceof RolePolicy)) {
            return false;
        }
        for (String role : roles) {
            if (((RolePolicy) roleStore).scopesOf(role).contains("*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * R41: creates each {@code .user} account that does not exist, never touching an existing one. A null password
     * becomes a logged temporary password in DEV and a logged one-time setup token in PROD under recovery.
     */
    // @req FR-AUTH-004
    private void seed() throws Exception {
        for (Seed seed : seeds) {
            if (users.findByUsername(seed.name) != null) {
                logger.info("auth: .user(\"" + seed.name + "\") already exists; .user never changes an existing "
                        + "account or its password");
                continue;
            }
            String hash = seed.hash;
            if (hash == null) {
                if (mode == AuthMode.DEV) {
                    String temporary = randomToken(12);
                    hash = passwords.hash(temporary.toCharArray());
                    logger.warn("auth: DEV created .user(\"" + seed.name + "\") with the temporary password "
                            + temporary + " (shown once; set a password before real use)");
                } else {
                    String token = randomToken(24);
                    pendingSetup = new PendingSetup(seed.name, seed.roles, token);
                    logger.warn("auth: no account for .user(\"" + seed.name + "\") and no password is set; "
                            + "one-time setup token: " + token + " (POST it to " + join(mountPath, "/setup")
                            + " from this machine)");
                    continue;
                }
            }
            AuthUser row = AuthUser.builder().username(seed.name).roles(seed.roles).passwordHash(hash).build();
            AuthUser created;
            try {
                created = users.create(row);
            } catch (DuplicateUserException taken) {
                logger.info("auth: .user(\"" + seed.name + "\") already exists (another node created it); start "
                        + "continues");
                continue;
            }
            final AuthUser out = EventCalls.copy(created);
            final AuthEventContext ctx = new AuthEventContext(UserOrigin.BOOTSTRAP, null, null, time.nowMillis());
            for (final AuthEvents l : events) {
                EventCalls.after(logger, "onUserCreated", () -> l.onUserCreated(out, ctx));
            }
            logger.info("auth: created .user(\"" + seed.name + "\") with roles " + seed.roles);
        }
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64Url.encode(b);
    }

    /** The last app ROUTE outside the auth routers is not followed by {@link #errors()} or {@link #errorMiddleware()}. */
    // @req IR-AUTH-002
    private boolean errorLayerMissing(RestApp a) {
        List<Registration> rs = a.registrations();
        List<Integer> layers = new ArrayList<>();
        for (int i = 0; i < rs.size(); i++) {
            Registration r = rs.get(i);
            if (r.getKind() == RegistrationKind.ERROR_MIDDLEWARE
                    && (r.getTarget() == errorsHandler || r.getTarget() == errorsMiddleware)) {
                layers.add(i);
            }
        }
        for (int i = 0; i < rs.size(); i++) {
            Registration route = rs.get(i);
            if (route.getKind() != RegistrationKind.ROUTE || underAuthMount(route)) {
                continue;
            }
            boolean covered = false;
            for (int l : layers) {
                Registration layer = rs.get(l);
                boolean appWide = layer.getMount() == null
                        && ("".equals(layer.getPath()) || "/".equals(layer.getPath()));
                boolean sameRouter = layer.getMount() != null && topMount(layer) == topMount(route);
                if ((appWide || sameRouter) && position(rs, l) > position(rs, i)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Registration topMount(Registration r) {
        Registration top = r.getMount();
        while (top != null && top.getMount() != null) {
            top = top.getMount();
        }
        return top;
    }

    /**
     * Where record {@code i} runs: a record expanded under a router mount runs at the position of its outermost
     * mount (a route added later to a mounted router counts there), then in its own order.
     */
    private static long position(List<Registration> rs, int i) {
        Registration top = rs.get(i);
        while (top.getMount() != null) {
            top = top.getMount();
        }
        int at = i;
        if (top != rs.get(i)) {
            for (int j = 0; j < rs.size(); j++) {
                Registration c = rs.get(j);
                if (c == top || (c.getMount() == null && c.getKind() == top.getKind() && c.getTarget() == top.getTarget()
                        && java.util.Objects.equals(c.getPath(), top.getPath()))) {
                    at = j;
                    break;
                }
            }
        }
        return ((long) at << 32) | i;
    }

    private boolean underAuthMount(Registration r) {
        for (Registration m = r.getMount(); m != null; m = m.getMount()) {
            if (m.getTarget() == mountedRoutes || m.getTarget() == mountedAdminRoutes) {
                return true;
            }
        }
        return false;
    }

    /** R30: after a registration change, the next request re-checks the error layer and WARNs at most once a minute. */
    // @req IR-AUTH-002
    private void errorLayerCheck() {
        RestApp a = app;
        if (a == null) {
            return;
        }
        long gen = registrationGeneration.get();
        long checked = errorLayerCheckedGeneration.get();
        long now = time.nowMillis();
        long last = errorLayerWarnedAt.get();
        boolean minutePassed = last == Long.MIN_VALUE || now - last >= 60_000L;
        boolean recheck = errorLayerWarnPending.get() && minutePassed;
        if (gen == checked && !recheck) {
            return;
        }
        if (!errorLayerCheckedGeneration.compareAndSet(checked, gen)) {
            return; // another request checks this generation
        }
        if (!errorLayerMissing(a)) {
            errorLayerWarnPending.set(false);
            return;
        }
        if (minutePassed && errorLayerWarnedAt.compareAndSet(last, now)) {
            errorLayerWarnPending.set(false);
            logger.warn("auth: " + ERROR_LAYER_ADVICE + " (logged at most once a minute)");
        } else {
            errorLayerWarnPending.set(true); // rate-limited: WARN once the minute is over
        }
    }


    /**
     * FR-IAM-015 (R2-M1-F09): the FileUserStore {@code store} is, or wraps through {@link
     * com.snoworca.naruserve.utils.auth.user.ForwardingUserStore} decorators; null when there is none.
     */
    // @req FR-IAM-015
    static @Nullable FileUserStore fileUserStoreIn(@Nullable UserStore store) {
        Object s = store;
        for (int depth = 0; s != null && depth < 64; depth++) {
            if (s instanceof FileUserStore) {
                return (FileUserStore) s;
            }
            s = com.snoworca.naruserve.utils.auth.internal.UserStoreDelegates.delegateOf(s);
        }
        return null;
    }

    private static boolean isShippedUserStore(UserStore store) {
        String n = store.getClass().getName();
        return store instanceof InMemoryUserStore || n.equals("com.snoworca.naruserve.utils.auth.user.FileUserStore");
    }

    private String publicPathListing() {
        List<String> out = new ArrayList<>();
        for (PathRule p : permits) {
            out.add(p.toString());
        }
        for (String p : permitPrefixes) {
            out.add(p + "/**");
        }
        if (!protectOnly.isEmpty()) {
            out.add("everything outside " + protectOnly);
        }
        return out.toString();
    }

    /**
     * The logger every component built with an auth holds (audit trail, token and grant checks, the user facade, the
     * default throttle and device trust, ...): it forwards to the builder's logger, or, when {@code .logger(...)} was
     * not called, to the app's logger from {@link #install} on (FR-AUTH-005, Builder.logger).
     */
    /** The clock and logger the shared file defaults of one FileUserStore read through; see {@link #FILE_TARGETS}. */
    // @req REL-FLOW-001
    static final class SharedTargets {
        final ComponentLogger log;
        final SwitchedTime time;

        SharedTargets(Logger log, TimeSource time) {
            this.log = new ComponentLogger(log);
            this.time = new SwitchedTime(time);
        }

        void repoint(Logger nextLog, TimeSource nextTime) {
            log.target(nextLog);
            time.target(nextTime);
        }

        /** The targets of {@code store}, made over {@code log} and {@code time} on first use. */
        static SharedTargets of(FileUserStore store, Logger log, TimeSource time) {
            synchronized (FILE_TARGETS) {
                SharedTargets t = FILE_TARGETS.get(store);
                if (t == null) {
                    t = new SharedTargets(log, time);
                    FILE_TARGETS.put(store, t);
                }
                return t;
            }
        }
    }

    /** A TimeSource that reads whichever source it was last pointed at. */
    static final class SwitchedTime implements TimeSource {
        private volatile TimeSource target;

        SwitchedTime(TimeSource target) {
            this.target = Objects.requireNonNull(target, "target");
        }

        void target(TimeSource next) {
            this.target = Objects.requireNonNull(next, "next");
        }

        @Override
        public long nowMillis() {
            return target.nowMillis();
        }
    }

    static final class ComponentLogger implements Logger {
        private volatile Logger target;

        ComponentLogger(Logger target) {
            this.target = Objects.requireNonNull(target, "target");
        }

        Logger target() {
            return target;
        }

        void target(Logger next) {
            this.target = Objects.requireNonNull(next, "next");
        }

        @Override
        public void trace(String message) {
            target.trace(message);
        }

        @Override
        public void debug(String message) {
            target.debug(message);
        }

        @Override
        public void info(String message) {
            target.info(message);
        }

        @Override
        public void warn(String message) {
            target.warn(message);
        }

        @Override
        public void error(String message) {
            target.error(message);
        }

        @Override
        public void error(String message, Throwable error) {
            target.error(message, error);
        }
    }

    // ---------------------------------------------------------------------------------------------- middleware

    private final AtomicBoolean notInstalledLogged = new AtomicBoolean();
    /** The roles named by auth.requireRole guards, for the undeclared-role doctor item. */
    private final Set<String> guardRoles = Collections.synchronizedSet(new LinkedHashSet<String>());

    /** OPS-AUTH-006 AC-4: one WARN per .user or requireRole role that the RolePolicy does not declare. */
    // @req OPS-AUTH-006
    private void undeclaredRoles(Doctor d) {
        if (!(roleStore instanceof RolePolicy)) {
            return; // a database RoleStore may hold any name
        }
        Set<String> used = new LinkedHashSet<>();
        for (Seed s : seeds) {
            used.addAll(s.roles);
        }
        synchronized (guardRoles) {
            used.addAll(guardRoles);
        }
        for (String role : used) {
            if (!declaresRole(role)) {
                d.warn("undeclared role", "role '" + role + "' is used by a .user(...) or auth.requireRole(...) but "
                        + "the RolePolicy does not declare it; a typo makes the role check fail with 403. Declare it "
                        + "with RolePolicy.builder().role(\"" + role + "\", ...)");
            }
        }
    }
    /** The scopes named by auth.require guards, for the undeclared-scope doctor item. */
    private final Set<String> guardScopes = Collections.synchronizedSet(new LinkedHashSet<String>());

    /** OPS-AUTH-008 AC-1: one WARN per auth.require scope that no RolePolicy role grants (admin's * not counted). */
    // @req OPS-AUTH-008
    private void undeclaredScopes(Doctor d) {
        if (!(roleStore instanceof RolePolicy)) {
            return; // a database RoleStore may grant any scope
        }
        Set<String> grants = new LinkedHashSet<>(((RolePolicy) roleStore).grantedScopes());
        grants.remove("*");
        List<String> required;
        synchronized (guardScopes) {
            required = new ArrayList<>(guardScopes);
        }
        for (String scope : required) {
            if (!com.snoworca.naruserve.utils.auth.internal.ScopeMatcher.allows(grants, scope)) {
                d.warn("undeclared scope", "scope '" + scope + "' is required by auth.require(...) but no RolePolicy "
                        + "role grants it (admin's * aside); a typo makes the scope check fail with 403. Grant it "
                        + "with RolePolicy.builder().role(\"<role>\", \"" + scope + "\")");
            }
        }
    }

    /** OPS-AUTH-010 AC-1: one WARN per app route whose method the .permit rule of its exact path leaves protected. */
    // @req OPS-AUTH-010
    private void permitMethods(RestApp app, Doctor d) {
        for (Registration r : app.registrations()) {
            String path = r.getPath();
            if (r.getKind() != RegistrationKind.ROUTE || r.getMethod() == null || path == null) {
                continue;
            }
            Method m;
            try {
                m = Method.valueOf(r.getMethod());
            } catch (IllegalArgumentException e) {
                continue; // a custom method token: no .permit rule names it
            }
            PathRule rule = null;
            boolean covered = false;
            for (PathRule p : permits) {
                if (p.path.equals(path)) {
                    rule = rule == null ? p : rule;
                    covered |= p.methods.contains(m);
                }
            }
            if (rule == null || covered || inPublicPrefixOrOutsideRange(path)) {
                continue;
            }
            d.warn("permit method", r.getMethod() + " " + path + " is registered, but .permit(\"" + path + "\") makes only "
                    + rule.methodList() + " of exactly that path public, so this route answers 401 without a token; "
                    + "add .permit(Method." + m.name() + ", \"" + path + "\") if it should be public");
        }
    }

    private boolean inPublicPrefixOrOutsideRange(String path) {
        for (String prefix : permitPrefixes) {
            if (underPrefix(path, prefix)) {
                return true;
            }
        }
        return !protectOnly.isEmpty() && !inProtectedRange(path);
    }

    /** OPS-AUTH-008 AC-3: the 401 names the Bearer scheme when an Authorization header used another one. */
    // @req OPS-AUTH-008
    private String unauthenticatedMessage(Request req) {
        List<String> authorization = req.headers("Authorization");
        if (authorization.size() == 1) { // OPS-AUTH-022 AC-1..AC-3
            String a = authorization.get(0);
            boolean scheme = ApiKeyCredentials.isScheme(a, apiKeyScheme);
            boolean keyLike = a.contains("nk_") || scheme && apiKeys == null;
            if (keyLike && apiKeys == null) {
                return "authentication is required; API keys are off: build auth with .apiKeys(store) to accept "
                        + "Authorization: " + apiKeyScheme + " <key>";
            }
            if (keyLike && !scheme && !ApiKeyCredentials.isScheme(a, "Bearer")) {
                return "authentication is required; an API key goes in the Authorization header as " + apiKeyScheme
                        + " <key>";
            }
            if (transport == TokenTransport.COOKIE && ApiKeyCredentials.isScheme(a, "Bearer")) {
                return "authentication is required; this app reads tokens from the naru_at cookie "
                        + "(TokenTransport.COOKIE), not from the Authorization header; log in from the page to get the "
                        + "cookie, or build with .transport(TokenTransport.BOTH)";
            }
        }
        if (transport != TokenTransport.COOKIE && authorization.size() == 1
                && !ApiKeyCredentials.isScheme(authorization.get(0), "Bearer")
                && !(apiKeys != null && ApiKeyCredentials.isScheme(authorization.get(0), apiKeyScheme))) {
            return "authentication is required; send the access token as Authorization: Bearer <token>";
        }
        if (req.method() == Method.OPTIONS && req.header("Origin") != null
                && req.header("Access-Control-Request-Method") != null) { // OPS-AUTH-025 AC-1, before the method hint (OPS-AUTH-029 AC-2)
            String why = "this is a CORS preflight that no app.cors(...) answered; register app.cors(...) before "
                    + "app.use(auth)";
            if (preflightWarned.compareAndSet(false, true)) {
                logger.warn("auth: " + req.method() + " " + req.path() + " is a CORS preflight that reached auth, so "
                        + "the browser sees a 401 and a CORS error; " + why.substring(why.indexOf("register"))
                        + ". Logged once");
            }
            return "authentication is required; " + why;
        }
        if (authorization.isEmpty() && req.method() != Method.POST) {
            String path = canonical(req.path());
            for (String e : POST_ONLY) {
                if (path.equals(join(mountPath, e))) { // OPS-AUTH-014 AC-2: a documented endpoint, no app route revealed
                    return "authentication is required; this endpoint takes POST " + path;
                }
            }
        }
        if (authorization.isEmpty()) {
            String elsewhere = misplacedCredential(req); // OPS-AUTH-021 AC-9
            if (elsewhere != null) {
                return "authentication is required; " + elsewhere;
            }
            String near = nearPermit(req); // OPS-AUTH-024 AC-5
            if (near != null) {
                return "authentication is required; " + near;
            }
            if (req.method() == Method.POST) {
                String endpoint = loginLikeEndpoint(canonical(req.path())); // OPS-AUTH-021 AC-4
                if (endpoint != null) {
                    return "authentication is required; auth's " + endpoint + " endpoint is POST "
                            + join(mountPath, "/" + endpoint) + (endpoint.equals("register") && !registerEnabled
                            ? ", and sign-up is off (build with .register(true))" : ""); // OPS-AUTH-023 AC-6
                }
            }
        }
        if (mode == AuthMode.DEV && authorization.isEmpty() && (req.method() == Method.GET
                || req.method() == Method.HEAD) && routeAt(req.path(), "GET")
                && !underPrefix(canonical(req.path()), mountPath) && !isAdminPath(req) // OPS-AUTH-031 AC-1
                && protectedRouteWarned.compareAndSet(false, true)) { // OPS-AUTH-030 AC-3
            logger.warn("auth: " + req.method() + " " + req.path() + " is an app route that default deny protects, so "
                    + "it answered 401 without a token; only if it is meant to be public, build with .permit(\""
                    + req.path() + "\"); a route that should need a login needs no change. Logged once (DEV)"); // OPS-AUTH-032 AC-2
        }
        return "authentication is required";
    }

    private boolean routeAt(String path, String method) {
        RestApp a = app;
        if (a == null) {
            return false;
        }
        for (Registration r : a.registrations()) {
            if (r.getKind() == RegistrationKind.ROUTE && path.equals(r.getPath())
                    && (r.getMethod() == null || method.equals(r.getMethod()))) {
                return true;
            }
        }
        return false;
    }

    /** OPS-AUTH-021 AC-9: where a request put a credential that auth does not read, or null. */
    private @Nullable String misplacedCredential(Request req) {
        String bearer = "the access token goes in the Authorization header as Bearer <token>";
        if (req.query("access_token") != null) {
            return bearer + ", not in ?access_token";
        }
        if (req.query("token") != null) { // OPS-AUTH-025 AC-5
            return bearer + ", not in ?token";
        }
        if (req.header("X-Access-Token") != null) {
            return bearer + ", not in X-Access-Token";
        }
        if (req.header("X-Auth-Token") != null) {
            return bearer + ", not in X-Auth-Token";
        }
        String key = "an API key goes in the Authorization header as " + apiKeyScheme + " <key>";
        if (req.query("api_key") != null) {
            return key + ", not in ?api_key";
        }
        if (req.header("X-API-Key") != null) {
            return key + ", not in X-API-Key";
        }
        if (transport == TokenTransport.HEADER && accessCookieCount(req) > 0) {
            return "this app reads tokens from the Authorization header (TokenTransport.HEADER), not from the naru_at "
                    + "cookie; send Authorization: Bearer <token> or build with .transport(...)";
        }
        return null;
    }

    /** OPS-AUTH-024 AC-5: the exact permit a request just missed (trailing slash or method), or null. */
    private @Nullable String nearPermit(Request req) {
        String path = req.path();
        String bare = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (PathRule r : permits) {
            if (r.path.equalsIgnoreCase(bare) // OPS-AUTH-025 AC-5: a case variant too
                    && (!r.path.equals(path) || !r.methods.contains(req.method()))) {
                String which = r.methods.size() == 2 && r.methods.contains(Method.GET) && r.methods.contains(Method.HEAD)
                        ? "GET and HEAD" : r.methodList();
                return ".permit(\"" + r.path + "\") makes only " + which + " of exactly " + r.path + " public";
            }
        }
        return null;
    }

    private static final List<String> LOGIN_LIKE = Arrays.asList("login", "signin", "sign-in", "signup", "sign-up",
            "register", "logout", "refresh", "token"); // "token" means login (OPS-AUTH-030 AC-4)

    /** OPS-AUTH-021 AC-4: the auth endpoint a login-like POST path outside the auth endpoints means, or null. */
    private @Nullable String loginLikeEndpoint(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int i = LOGIN_LIKE.indexOf(last);
        if (i < 0) {
            return null;
        }
        String endpoint = i < 3 || last.equals("token") ? "login" : i < 6 ? "register" : last;
        RestApp a = app;
        if (path.equals(join(mountPath, "/" + endpoint)) || a != null && appRoute(a, path)) {
            return null;
        }
        return endpoint;
    }

    private final AtomicBoolean insecureTransportWarned = new AtomicBoolean();
    private final AtomicBoolean preflightWarned = new AtomicBoolean();
    private final AtomicBoolean protectedRouteWarned = new AtomicBoolean();
    static final String NO_LONGER_VALID = "the token is no longer valid: since it was issued the user logged out "
            + "everywhere, changed the password, was disabled or was deleted; log in again";
    private final AtomicBoolean proxyProtoWarned = new AtomicBoolean();

    /** OPS-AUTH-004: the first insecure_transport refusal says why and how to get out, once per process. */
    // @req OPS-AUTH-004
    private void insecureTransportWarning(Request req) {
        if (req.header("X-Forwarded-For") != null && req.header("X-Forwarded-Proto") == null
                && proxyProtoWarned.compareAndSet(false, true)) { // OPS-AUTH-027 AC-3
            logger.warn("auth: " + req.method() + " " + req.path() + " came through a proxy (X-Forwarded-For) without "
                    + "X-Forwarded-Proto, so auth cannot see that the client used HTTPS; have the proxy send "
                    + "X-Forwarded-Proto (nginx: proxy_set_header X-Forwarded-Proto $scheme) and trust it with "
                    + "app.trustProxyHops(1). Logged once");
        }
        if (insecureTransportWarned.compareAndSet(false, true)) {
            logger.warn("auth: " + req.method() + " " + req.path() + " " + "was refused with 403 insecure_transport: "
                    + "PROD serves protected paths and credential endpoints only over HTTPS or to local requests. "
                    + "Serve HTTPS through a TLS reverse proxy and set app.trustProxyHops(1) or "
                    + "app.trustProxyCidrs(...); for a LAN or device server without TLS, build with "
                    + ".allowInsecureTransport(\"reason\"); for local testing, build with .mode(AuthMode.DEV). "
                    + "Logged once");
        }
    }

    /** FR-AUTH-010 AC-4: attached as a plain middleware without install, auth has no endpoints; it refuses loudly. */
    private void notInstalled(Request req, Response res, boolean guard) {
        if (notInstalledLogged.compareAndSet(false, true)) {
            logger.error("auth: " + req.method() + " " + req.baseUrl() + req.path() // the full path under a prefix
                    + " reached auth, but auth was never installed, so "
                    + "it has no /auth endpoints, startup account or checks, and " + (guard // OPS-AUTH-029 AC-1
                    ? "every route without a guard is public; " : "every request that reaches it answers 500; ")
                    + "attach it with app.use(auth) or auth.install(app) before the routes (app.use(\"\", auth), "
                    + "router.use(auth) and route middleware add it without installing it); "
                    + "answering 500"); // OPS-AUTH-028 AC-1
        }
        respond(req, res, 500, "INTERNAL_ERROR", null, null);
    }

    /** The global auth middleware; {@link #install(RestApp)} attaches it. */
    // @req SEC-FLOW-001
    // @req FR-AUTH-006
    @Override
    // @req FR-AUTH-010
    public void handle(Request req, Response res, Next next) {
        if (app == null) {
            notInstalled(req, res, false);
            return;
        }
        errorLayerCheck();
        proxyWarnings(req);
        boolean local = isLocal(req);
        if (mode == AuthMode.DEV && !local && devExposedWarned.compareAndSet(false, true)) {
            logger.warn("auth: DEV mode is serving a request that is not local; DEV relaxes the secure-transport "
                    + "check and key strength, so never expose it outside this machine");
        }
        boolean transportOk = mode == AuthMode.DEV || insecureReason != null || req.isSecure() || local;
        boolean credentialEndpoint = isCredentialEndpoint(req);
        if (!credentialEndpoint && isPublic(req)) {
            if (transportOk) {
                attachOptional(req);
            }
            next.run();
            return;
        }
        if (!transportOk) {
            insecureTransportWarning(req);
            reject(req, res, AuthErrorCode.INSECURE_TRANSPORT, "a secure connection (HTTPS) is required; the server "
                    + "log names the ways to serve this request"); // OPS-AUTH-014 AC-3
            return;
        }
        if (!registerEnabled && !appServesRegister && credentialEndpoint
                && canonical(req.path()).equals(join(mountPath, "/register"))) {
            // OPS-AUTH-012 AC-4: sign-up is off and no app route serves the path; say so instead of Core's HTML 404
            respond(req, res, 404, "NOT_FOUND", "sign-up is off: POST " + join(mountPath, "/register")
                    + " is served only when auth is built with .register(true)", null);
            return;
        }
        if (isAnonymousEntry(req)) {
            if (!anonymousEntryCredentials(req, res)) {
                return;
            }
            if (!jsonBodyOk(req, credentialEndpoint, res)) {
                return;
            }
            next.run();
            return;
        }
        Credential c;
        try {
            c = credentials.resolve(req);
        } catch (AuthRejection r) {
            respond(req, res, r.getStatus(), r.getCode(), r.getMessage(), r.getRetryAfterSeconds());
            return;
        } catch (Exception e) {
            credentialUnavailable(req, res, e);
            return;
        }
        if (c == null) {
            reject(req, res, AuthErrorCode.UNAUTHENTICATED, unauthenticatedMessage(req));
            return;
        }
        AuthPrincipal p;
        if (statelessAccess) {
            p = AuthPrincipal.minimal(c.getUserId(), c.getTokenVersion(), c.getApiKeyId(), c.getKeyScopes());
        } else {
            try {
                p = load(c.getUserId(), c.getTokenVersion(), c.getApiKeyId(), c.getKeyScopes());
            } catch (Exception e) {
                unavailable(req, res, "loading the user", e);
                return;
            }
            if (p == null) {
                reject(req, res, AuthErrorCode.INVALID_TOKEN, NO_LONGER_VALID); // OPS-AUTH-030 AC-2
                return;
            }
        }
        if (credentialEndpoint && c.getApiKeyId() != null) {
            // SEC-APIKEY-004 step 10 (R47 (5)): no key reaches a credential endpoint, before any scope guard
            reject(req, res, AuthErrorCode.API_KEY_NOT_ALLOWED, "this endpoint does not accept API keys");
            return;
        }
        // FR-FLOW-001: the body format (415) is judged before CSRF/Origin (403); the earliest step decides.
        // A key request on an admin route is judged by the route's step-10 body bans and step-11 scope guard before
        // the body format (00 R29 (f) 31st edition: the key procedure precedes the 415); the handler's body read then
        // answers Core's 415, so only a key that passes the guard learns the media type was wrong (SEC-APIKEY-008).
        // Key requests never need CSRF, so moving the body check ahead of it changes nothing for them.
        boolean keyOnAdmin = c.getApiKeyId() != null && !credentialEndpoint && isAdminPath(req);
        if (!keyOnAdmin && !jsonBodyOk(req, credentialEndpoint || isAdminPath(req), res)) {
            return;
        }
        if (!csrfPasses(req, c)) {
            reject(req, res, AuthErrorCode.CSRF_FAILED, csrfFailure(req, c.getUserId(), c.getFamilyId()));
            return;
        }
        req.attribute(PRINCIPAL, p);
        req.attribute(TOKEN_EXPIRES, c.getExpiresAt());
        next.run();
    }

    /** SEC-FLOW-006/007: true unless {@code c} is a cookie write whose CSRF token or Origin does not match. */
    // @req SEC-FLOW-006
    /** OPS-AUTH-025 AC-2: which check of a cookie request failed. */
    String csrfFailure(Request req, String userId, @Nullable String familyId) {
        if (req.headers(CsrfTokens.HEADER).isEmpty()) {
            return "no X-CSRF-Token header: a cookie request sends the csrfToken of the login or refresh answer as "
                    + "X-CSRF-Token";
        }
        if (!csrfTokens.passes(req, userId, familyId)) {
            return "the X-CSRF-Token does not match the naru_csrf cookie of this session; send the csrfToken of the "
                    + "latest login or refresh answer";
        }
        String origin = req.header("Origin");
        if (origin == null && req.header("Referer") == null) {
            return "no Origin or Referer header: a cookie request must come from a trusted page (browsers send "
                    + "Origin)";
        }
        return "the Origin " + (origin != null ? origin : req.header("Referer")) + " is not trusted; add it with "
                + ".trustedOrigins(...)";
    }

    private boolean csrfPasses(Request req, Credential c) {
        return !csrfRequired(req, c) || csrfTokens.passes(req, c.getUserId(), c.getFamilyId())
                && com.snoworca.naruserve.utils.auth.internal.Origins.passes(req, trustedOrigins);
    }

    /**
     * SEC-FLOW-003: the only credential judgments of an anonymous-entry endpoint (login, refresh, logout, register,
     * setup) that follow the protected-path rules: multiple credentials (two {@code Authorization} headers; in BOTH a
     * Bearer token with one {@code naru_at}; a key with one {@code naru_at}) are 400 {@code invalid_request}, and with
     * API keys on a key-scheme header is authenticated by the key procedure first (a failing key 401
     * {@code invalid_api_key}, a valid one 403 {@code api_key_not_allowed}). Expired or invalid access tokens are
     * ignored and no principal is attached; two or more {@code naru_at} cookies count as no access cookie. False when
     * the response was written.
     */
    // @req SEC-FLOW-003
    // @req SEC-APIKEY-002
    private boolean anonymousEntryCredentials(Request req, Response res) {
        List<String> authorization = req.headers("Authorization");
        if (authorization.size() >= 2) {
            reject(req, res, AuthErrorCode.INVALID_REQUEST, "the request carries more than one credential");
            return false;
        }
        int accessCookies = transport == TokenTransport.HEADER ? 0 : accessCookieCount(req);
        boolean key = apiKeys != null && authorization.size() == 1
                && ApiKeyCredentials.isScheme(authorization.get(0), apiKeyScheme);
        boolean bearer = transport != TokenTransport.COOKIE && authorization.size() == 1
                && ApiKeyCredentials.isScheme(authorization.get(0), "Bearer");
        if (accessCookies == 1 && (key || bearer && transport == TokenTransport.BOTH)) {
            reject(req, res, AuthErrorCode.INVALID_REQUEST, "the request carries more than one credential");
            return false;
        }
        if (!key) {
            return true;
        }
        req.attribute(KEY_REQUEST, Boolean.TRUE);
        ApiKeyCredentials keys = keyCredentials;
        try {
            if (keys == null) {
                throw new IllegalStateException("API keys are on without the key procedure");
            }
            keys.verifyHeader(req, authorization.get(0));
        } catch (AuthRejection r) {
            respond(req, res, r.getStatus(), r.getCode(), r.getMessage(), r.getRetryAfterSeconds());
            return false;
        } catch (Exception e) {
            credentialUnavailable(req, res, e);
            return false;
        }
        reject(req, res, AuthErrorCode.API_KEY_NOT_ALLOWED, "this endpoint does not accept API keys");
        return false;
    }

    /** How many {@code naru_at} cookies the request carries. */
    static int accessCookieCount(Request req) {
        int n = 0;
        com.snoworca.naruserve.Cookie[] all;
        try {
            all = req.getCookies();
        } catch (UnsupportedOperationException none) {
            return 0;
        }
        for (com.snoworca.naruserve.Cookie c : all) {
            if (TransportCredentials.ACCESS_COOKIE.equals(c.getName())) {
                n++;
            }
        }
        return n;
    }

    /**
     * R29 (e): a POST, PUT, PATCH or DELETE authenticated by the {@code naru_at} cookie (COOKIE and BOTH modes) needs
     * the signed double-submit CSRF token of its session; Bearer and API-key requests and safe methods do not.
     */
    // @req SEC-FLOW-006
    private boolean csrfRequired(Request req, Credential c) {
        if (transport == TokenTransport.HEADER || c.getApiKeyId() != null) {
            return false;
        }
        Method m = req.method();
        if (m != Method.POST && m != Method.PUT && m != Method.PATCH && m != Method.DELETE) {
            return false;
        }
        return TransportCredentials.hasAccessCookie(req);
    }

    /**
     * R29 (f): a POST to an auth endpoint carrying a body (Content-Length above 0 or a Transfer-Encoding header) must
     * be JSON by Core's own media-type test, before anything reads the body; a bodiless POST is not checked.
     */
    // @req SEC-FLOW-005
    private boolean jsonBodyOk(Request req, boolean authEndpoint, Response res) {
        if (!authEndpoint || req.method() != Method.POST) {
            return true;
        }
        String length = req.header("Content-Length");
        boolean body = req.header("Transfer-Encoding") != null;
        if (!body && length != null) {
            try {
                body = Long.parseLong(length.trim()) > 0;
            } catch (NumberFormatException malformed) {
                body = true;
            }
        }
        if (!body || MediaTypes.isJsonUtf8(req.header("Content-Type"))) {
            return true;
        }
        respond(req, res, 415, coreCode(415), AccountEndpoints.unsupportedMediaType(req), null);
        return false;
    }

    private boolean isAdminPath(Request req) {
        return underPrefix(canonical(req.path()), adminPath);
    }

    /**
     * A public path: a principal only for a credential that verifies; anything else stays anonymous. A cookie write
     * (SEC-FLOW-006) without the session's CSRF token or with a foreign Origin stays anonymous too, so a guard on a
     * public path answers 401 instead of trusting a cookie a cross-site page made the browser send.
     */
    // @req SEC-FLOW-006
    private void attachOptional(Request req) {
        try {
            Credential c = credentials.resolve(req);
            if (c != null && csrfPasses(req, c)) {
                AuthPrincipal p = statelessAccess
                        ? AuthPrincipal.minimal(c.getUserId(), c.getTokenVersion(), c.getApiKeyId(), c.getKeyScopes())
                        : load(c.getUserId(), c.getTokenVersion(), c.getApiKeyId(), c.getKeyScopes());
                if (p != null) {
                    req.attribute(PRINCIPAL, p);
                    req.attribute(TOKEN_EXPIRES, c.getExpiresAt());
                }
            }
        } catch (AuthRejection anonymous) {
            // an invalid credential on a public path is an anonymous request
        } catch (Exception e) {
            long now = time.nowMillis();
            long last = anonymousStoreErrorLoggedAt.get();
            if ((last == Long.MIN_VALUE || now - last >= 60_000L) && anonymousStoreErrorLoggedAt.compareAndSet(last, now)) {
                logger.error("auth: optional authentication on a public path failed; the request stays anonymous "
                        + "(logged at most once a minute)", e);
            }
        }
    }

    /** The loaded principal of a user, or null when the user is missing, disabled or the version differs. */
    // @req SEC-AUTH-007
    private @Nullable AuthPrincipal load(String id, long tokenVersion, @Nullable String apiKeyId,
            @Nullable Set<String> keyScopes) throws Exception {
        PrincipalCache.Snapshot s = principals.get(id);
        if (s != null && tokenVersion > s.getTokenVersion()) {
            principals.invalidate(id); // a token newer than the cached row: another node raised ver; read it once more
            s = principals.get(id);
        }
        if (s == null || !s.isEnabled() || s.getTokenVersion() != tokenVersion) {
            return null;
        }
        return new AuthPrincipal(id, s.getUsername(), tokenVersion, s.getRoles(), s.getScopes(), s.getAttributes(),
                true, apiKeyId, keyScopes);
    }

    /** What the principal cache stores for one user: the row and its effective roles and scopes, read now. */
    // @req SEC-AUTH-007
    private PrincipalCache.@Nullable Snapshot snapshot(String id) throws Exception {
        AuthUser u = users.findById(id);
        if (u == null) {
            return null;
        }
        Set<String> roles = grants.rolesOf(u);
        return new PrincipalCache.Snapshot(u.getUsername(), u.getTokenVersion(), u.isEnabled(), roles,
                grants.scopesOfRoles(roles), u.getAttributes(), time.nowMillis());
    }

    /**
     * The path as the default router compares it: lower case, repeated slashes merged and a trailing slash dropped.
     * The protected-range, mount and credential-endpoint checks use it, so a case or slash variant that the router
     * would still route never escapes them; the public rules ({@code .permit}, {@code .permitPrefix}) stay exact,
     * so such a variant of a public path is protected (fail closed).
     */
    static String canonical(String path) {
        StringBuilder b = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/' && b.length() > 0 && b.charAt(b.length() - 1) == '/') {
                continue;
            }
            b.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        if (b.length() > 1 && b.charAt(b.length() - 1) == '/') {
            b.setLength(b.length() - 1);
        }
        return b.toString();
    }

    private boolean isPublic(Request req) {
        if (isUpgrade(req)) {
            return isWebSocket(req) && permitWebSockets.contains(req.path());
        }
        String canonical = canonical(req.path());
        if (underPrefix(canonical, adminPath)) {
            return false;
        }
        String path = req.path();
        Method m = req.method();
        for (PathRule p : permits) {
            if (p.matches(m, path)) {
                return true;
            }
        }
        for (String prefix : permitPrefixes) {
            if (underPrefix(path, prefix)) {
                return true;
            }
        }
        if (!protectOnly.isEmpty()) {
            return !inProtectedRange(path);
        }
        return false;
    }

    /**
     * True when {@code path} lies in the protectOnly range: judged on a path normalized wider than the router
     * (percent-decoded, lower case, repeated slashes merged), where a dot segment or an undecodable escape counts as
     * protected (a wider range closes more).
     */
    // @req SEC-AUTH-006
    private boolean inProtectedRange(String path) {
        String wide = widePath(path);
        if (wide == null) {
            return true;
        }
        for (String prefix : protectedPrefixes()) {
            String p = widePath(prefix);
            if (p == null || underPrefix(wide, p)) {
                return true; // a prefix the builder could not normalize closes everything (never reached: refused)
            }
        }
        return false;
    }

    /** The path percent-decoded (as UTF-8), lower-cased and slash-merged; null for a dot segment or a bad escape. */
    // @req SEC-AUTH-006
    static @Nullable String widePath(String path) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%') {
                if (i + 2 >= path.length()) {
                    return null;
                }
                int hi = Character.digit(path.charAt(i + 1), 16);
                int lo = Character.digit(path.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                bytes.write(hi << 4 | lo);
                i += 2;
            } else if (c < 0x80) {
                bytes.write(c);
            } else {
                byte[] utf8 = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                bytes.write(utf8, 0, utf8.length);
            }
        }
        String decoded;
        try {
            decoded = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
        String lower = canonical(decoded.toLowerCase(Locale.ROOT));
        for (String segment : lower.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return null;
            }
        }
        return lower;
    }

    private static boolean underPrefix(String path, String prefix) {
        if (prefix.equals("/")) {
            return true;
        }
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * A protocol upgrade on any header line: an {@code Upgrade} header, or an {@code upgrade} token in any
     * {@code Connection} line (a WebSocket or an h2c upgrade alike); every line is read, not only the first.
     */
    // @req SEC-AUTH-006
    private static boolean isUpgrade(Request req) {
        for (String up : req.headers("Upgrade")) {
            if (!up.trim().isEmpty()) {
                return true;
            }
        }
        for (String line : req.headers("Connection")) {
            for (String token : line.split(",")) {
                if (token.trim().equalsIgnoreCase("upgrade")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isWebSocket(Request req) {
        for (String up : req.headers("Upgrade")) {
            for (String token : up.split(",")) {
                if (!token.trim().equalsIgnoreCase("websocket")) {
                    return false;
                }
            }
        }
        return !req.headers("Upgrade").isEmpty();
    }

    private boolean isCredentialEndpoint(Request req) {
        if (req.method() != Method.POST) {
            return false;
        }
        String path = canonical(req.path());
        for (String e : CREDENTIAL) {
            if (path.equals(join(mountPath, e))) {
                return true;
            }
        }
        return path.equals(join(adminPath, "/apikeys"));
    }

    private boolean isAnonymousEntry(Request req) {
        if (req.method() != Method.POST) {
            return false;
        }
        String path = canonical(req.path());
        for (String e : ANONYMOUS_ENTRY) {
            if (path.equals(join(mountPath, e))) {
                return true;
            }
        }
        return false;
    }

    private static String join(String mount, String tail) {
        return mount.equals("/") ? tail : mount + tail;
    }

    /** Loopback socket address and no proxy header at all (R33); no name is resolved. */
    static boolean isLocal(Request req) {
        String peer;
        try {
            peer = req.socketAddress();
        } catch (UnsupportedOperationException none) {
            return false;
        }
        if (!isLoopback(peer)) {
            return false;
        }
        for (String h : PROXY_HEADERS) {
            if (req.header(h) != null) {
                return false;
            }
        }
        return true;
    }

    static boolean isLoopback(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("[") && a.endsWith("]")) {
            a = a.substring(1, a.length() - 1);
        }
        if (a.startsWith("::ffff:")) {
            a = a.substring(7);
        }
        if (a.equals("::1") || a.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        String[] parts = a.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("127")) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                if (p.charAt(i) < '0' || p.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    /** The two runtime proxy warnings, each logged once per Auth (R32, R35). */
    private void proxyWarnings(Request req) {
        boolean xff = req.header("X-Forwarded-For") != null;
        boolean xfp = req.header("X-Forwarded-Proto") != null;
        if (!xff && !xfp) {
            return;
        }
        RestApp a = app;
        if (xff && a != null && !a.trustProxy() && a.trustProxyHops() == 0 && a.trustProxyCidrs().isEmpty()
                && untrustedProxyWarned.compareAndSet(false, true)) {
            logger.warn("auth: a request carries X-Forwarded-For but no proxy is trusted, so all users share the "
                    + "load balancer's IP for login limits; set app.trustProxyHops(n) or app.trustProxyCidrs(...)");
        }
        if (deployment == Deployment.SINGLE_INSTANCE && multiNodeWarned.compareAndSet(false, true)) {
            logger.warn("auth: requests arrive through a proxy while the refresh token store and login throttle are "
                    + "not shared; if several nodes serve this app, use deployment(MULTI_INSTANCE) with shared stores");
        }
    }

    // ---------------------------------------------------------------------------------------------- responses

    private void reject(Request req, Response res, AuthErrorCode code, String message) {
        respond(req, res, code.getStatus(), code.getCode(), message, null);
    }

    /**
     * A store or throttle failure while reading the credential of a protected request: 503, with the ERROR logged at
     * most once a minute (SEC-APIKEY-004 AC-5/AC-6, SEC-APIKEY-005 AC-7), so a client repeating a key while the store
     * is down cannot flood the log.
     */
    // @req SEC-APIKEY-004
    private void credentialUnavailable(Request req, Response res, Exception e) {
        String code = AuthUnavailableException.SERVICE_UNAVAILABLE;
        Long retry = null;
        if (e instanceof AuthUnavailableException) {
            code = ((AuthUnavailableException) e).getCode();
            retry = ((AuthUnavailableException) e).getRetryAfterSeconds();
        }
        long now = time.nowMillis();
        long last = credentialErrorLoggedAt.get();
        if ((last == Long.MIN_VALUE || now - last >= 60_000L || now < last)
                && credentialErrorLoggedAt.compareAndSet(last, now)) {
            logger.error("auth: reading the credential failed; answering 503 (logged at most once a minute)", e);
        }
        respond(req, res, 503, code, null, retry);
    }

    private void unavailable(Request req, Response res, String what, Exception e) {
        String code = AuthUnavailableException.SERVICE_UNAVAILABLE;
        Long retry = null;
        if (e instanceof AuthUnavailableException) {
            code = ((AuthUnavailableException) e).getCode();
            retry = ((AuthUnavailableException) e).getRetryAfterSeconds();
        }
        logger.error("auth: " + what + " failed; answering 503", e);
        respond(req, res, 503, code, null, retry);
    }

    /** Writes an auth error through the configured responses; a failing one falls back to the default body. */
    void respond(Request req, Response res, int status, String code, @Nullable String message,
            @Nullable Long retryAfterSeconds) {
        String text = message == null || message.isEmpty() ? ErrorBodies.reason(status) : message;
        String challenge = challenge(req, status, code);
        if (challenge != null) {
            res.header("WWW-Authenticate", challenge);
        }
        AuthResponses custom = responses;
        if (custom != null) {
            try {
                res.status(status);
                if (retryAfterSeconds != null) {
                    res.header("Retry-After", String.valueOf(retryAfterSeconds));
                }
                custom.error(req, res, status, code, text);
                return;
            } catch (Exception e) {
                logger.error("auth: the AuthResponses implementation failed; writing the default error body", e);
            }
        }
        ErrorBodies.write(req, res, status, code, text, retryAfterSeconds);
    }

    /**
     * IR-FLOW-001: the one {@code WWW-Authenticate} value of an auth error, or null. Every 401 carries the Bearer
     * challenge, with {@code error="invalid_token"} only for {@code invalid_token}, followed by the key scheme without
     * attributes when API keys are on; a 403 {@code insufficient_scope} of a JWT request carries
     * {@code Bearer error="insufficient_scope"}; a 403 of a key-authenticated request and every other status none.
     */
    // @req IR-FLOW-001
    @Nullable String challenge(Request req, int status, String code) {
        if (status == 401) {
            String bearer = AuthErrorCode.INVALID_TOKEN.getCode().equals(code) ? "Bearer error=\"invalid_token\""
                    : "Bearer";
            return apiKeys != null ? bearer + ", " + apiKeyScheme : bearer;
        }
        if (status == 403 && AuthErrorCode.INSUFFICIENT_SCOPE.getCode().equals(code) && !keyRequest(req)) {
            return "Bearer error=\"insufficient_scope\"";
        }
        return null;
    }

    /** True when the request's credential was an API key. */
    static boolean keyRequest(Request req) {
        if (Boolean.TRUE.equals(req.attributeAs(KEY_REQUEST, Boolean.class))) {
            return true;
        }
        AuthPrincipal p = req.attributeAs(PRINCIPAL, AuthPrincipal.class);
        return p != null && p.getApiKeyId() != null;
    }

    /** The error middleware of the auth routers (R30): every failure becomes a JSON error without exception text. */
    // @req FR-AUTH-006
    private void routerError(Throwable err, Request req, Response res, Next next) {
        if (err instanceof AuthUnavailableException
                && AuthErrorCode.SECURITY_WRITE_CONFLICT.getCode().equals(((AuthUnavailableException) err).getCode())) {
            auditWriteConflict(req);
        }
        if (authError(err, req, res)) {
            return;
        }
        if (err instanceof ShownError) {
            int status = ((ShownError) err).status(); // a message auth wrote for the client (FR-TOKEN-004, OPS-AUTH-006)
            respond(req, res, status, coreCode(status), err.getMessage(), null);
        } else if (err instanceof HttpException) {
            int status = ((HttpException) err).status();
            respond(req, res, status, coreCode(status), null, null);
        } else if (isStoreFailure(err)) {
            logger.error("auth: a store failed in " + req.method() + " " + req.path() + "; answering 503", err);
            respond(req, res, 503, AuthUnavailableException.SERVICE_UNAVAILABLE, null, null);
        } else {
            logger.error("auth: " + req.method() + " " + req.baseUrl() + req.path() + " failed; answering 500"
                    + (err.getMessage() != null && err.getMessage().startsWith("UserStore.") ? " (" + err.getMessage()
                    + ")" : ""), err); // OPS-AUTH-029 AC-3
            respond(req, res, 500, "INTERNAL_ERROR", null, null);
        }
    }

    /**
     * A checked store failure: an {@link IOException}, or the {@code java.sql.SQLException} of an app-provided store
     * over its own database. The SQL type is matched by class name, so no auth class references {@code java.sql}
     * (CON-IAM-003).
     */
    // @req FR-AUTH-006
    // @req CON-IAM-003
    static boolean isStoreFailure(Throwable err) {
        if (err instanceof IOException) {
            return true;
        }
        for (Class<?> c = err.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("java.sql.SQLException")) {
                return true;
            }
        }
        return false;
    }

    /**
     * R37: a security write the store did not apply, answered 503 on an auth-router path, leaves one
     * {@code security_write_conflict} audit event; the store default itself holds no sink and emits none.
     */
    // @req FR-AUTH-009
    // @req REL-IAM-001
    private void auditWriteConflict(Request req) {
        AuthPrincipal p = userOrNull(req);
        audit.emit(audit.event(com.snoworca.naruserve.utils.auth.audit.AuditEvent.SECURITY_WRITE_CONFLICT,
                req.method() + " " + req.path(), req, p == null ? null : p.getId(), p == null ? null : p.getApiKeyId(),
                p != null && isServiceAccount(p.getId())).outcome(com.snoworca.naruserve.utils.auth.audit.AuditEvent.Outcome.FAILURE)
                .reason("the user store did not apply a security write").build());
    }

    /**
     * The Core error handler for {@code app.onError(...)}, registered after the routes: it turns the auth exceptions an
     * app handler throws into the auth JSON body ({@code DuplicateUserException} 409, {@code AuthUnavailableException}
     * 503, an auth {@code IllegalArgumentException} 400 {@code BAD_REQUEST}, {@code AuthRejection} with its status and
     * {@code Retry-After}, {@code AuthStateException} 500 without its message) and leaves every other exception to the
     * next error layer. The same instance on every call. Express counterpart: an error handler
     * {@code app.use((err, req, res, next) => ...)} placed last.
     */
    // @req IR-AUTH-002
    public ErrorHandler errors() {
        return errorsHandler;
    }

    /**
     * {@link #errors()} as an {@link ErrorMiddleware}, for {@code app.use(...)}, which Core also accepts after start:
     * add it again after every batch of routes added at run time. The same instance on every call.
     */
    // @req IR-AUTH-002
    public ErrorMiddleware errorMiddleware() {
        return errorsMiddleware;
    }

    private void appError(Throwable err, Request req, Response res) {
        if (!res.isCommitted()) {
            authError(err, req, res, true);
        }
    }

    private void appErrorMiddleware(Throwable err, Request req, Response res, Next next) {
        if (res.isCommitted() || !authError(err, req, res, true)) { // OPS-AUTH-014 AC-4: Core logged the 500
            next.error(err);
        }
    }

    /** OPS-AUTH-033 AC-1: the PROD error layer auth appends last at start, after the app's own error layers. */
    private void prodFinalError(Throwable err, Request req, Response res, Next next) {
        if (res.isCommitted() || !authError(err, req, res, true) && !prodAnswer(err, req, res)) {
            next.error(err);
        }
    }

    /**
     * OPS-AUTH-033 AC-2: the last layer under the auth mounts. A request that no route answered gets JSON: 405 with
     * {@code Allow} when a route of that path takes other methods, else 404 naming what is off (API keys) or done in
     * code (group roles). OPTIONS passes on to Core, which answers it from the routes.
     */
    private void mountFallback(Request req, Response res, Next next) {
        if (req.method() == Method.OPTIONS || res.isCommitted()) {
            next.run();
            return;
        }
        String path = req.baseUrl() + req.path();
        Set<String> methods = new LinkedHashSet<>();
        String pattern = null;
        RestApp a = app;
        if (a != null) {
            for (Registration r : a.registrations()) {
                if (r.getKind() == RegistrationKind.ROUTE && r.getPath() != null && r.getMethod() != null
                        && patternMatches(r.getPath(), path)) {
                    methods.add(r.getMethod());
                    pattern = r.getPath();
                }
            }
        }
        if (!methods.isEmpty()) {
            StringBuilder allow = new StringBuilder();
            for (String m : methods) {
                allow.append(allow.length() == 0 ? "" : ", ").append(m);
            }
            res.header("Allow", allow.toString());
            respond(req, res, 405, "METHOD_NOT_ALLOWED", "this endpoint takes " + allow + " " + pattern, null);
            return;
        }
        String why = "no auth endpoint serves " + req.method() + " " + path;
        if (apiKeys == null && (underPrefix(path, join(mountPath, "/me/apikeys"))
                || underPrefix(path, join(adminPath, "/apikeys")))) {
            why += "; API keys are off: build auth with .apiKeys(store)";
        } else if (underPrefix(path, join(adminPath, "/groups")) && path.endsWith("/roles")) {
            why += "; a group gets a role in code, with the group store's grantRole (for example "
                    + "InMemoryGroupStore.grantRole)";
        }
        respond(req, res, 404, "NOT_FOUND", why, null);
    }

    /** A route path such as {@code /auth/admin/users/:id} against a request path; {@code :name} matches one segment. */
    private static boolean patternMatches(String pattern, String path) {
        String[] p = pattern.split("/", -1);
        String[] q = path.split("/", -1);
        if (p.length != q.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (p[i].startsWith(":") ? q[i].isEmpty() : !p[i].equals(q[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * OPS-AUTH-033 AC-1: in PROD the auth error layer is the last word, so an app exception never reaches Core's HTML
     * page and its stack trace; a Core HttpException keeps its status and, below 500, its message. Core has already
     * logged a 500. DEV leaves it to the next layer, where the stack trace helps.
     */
    private boolean prodAnswer(Throwable err, Request req, Response res) {
        if (mode != AuthMode.PROD || res.isCommitted()) {
            return false;
        }
        int status = err instanceof HttpException ? ((HttpException) err).status() : 500;
        if (status < 400 || status > 599) {
            status = 500;
        }
        respond(req, res, status, coreCode(status), err instanceof HttpException && status < 500 ? err.getMessage()
                : null, null);
        return true;
    }

    /** Answers an auth exception and returns true; false for any other exception, which it leaves untouched. */
    // @req IR-AUTH-002
    private boolean authError(Throwable err, Request req, Response res) {
        return authError(err, req, res, false);
    }

    /** {@code coreLogged}: the error came through Core's onError path, which already logged a 500 (OPS-AUTH-008 AC-4). */
    // @req OPS-AUTH-008
    private boolean authError(Throwable err, Request req, Response res, boolean coreLogged) {
        if (err instanceof AuthRejection) {
            AuthRejection r = (AuthRejection) err;
            respond(req, res, r.getStatus(), r.getCode(), r.getMessage(), r.getRetryAfterSeconds());
        } else if (err instanceof DuplicateUserException) {
            respond(req, res, AuthErrorCode.DUPLICATE_USER.getStatus(), AuthErrorCode.DUPLICATE_USER.getCode(),
                    "the username is taken", null);
        } else if (err instanceof AuthUnavailableException) {
            AuthUnavailableException u = (AuthUnavailableException) err;
            String m = u.getMessage();
            if (m == null || !m.startsWith("auth event ")) { // OPS-AUTH-024 AC-1: EventCalls logged that failure
                logger.error("auth: " + req.method() + " " + req.baseUrl() + req.path() + " is unavailable; answering "
                        + "503", u);
            }
            respond(req, res, 503, u.getCode(), null, u.getRetryAfterSeconds());
        } else if (err instanceof AuthArgumentException) {
            AuthArgumentException a = (AuthArgumentException) err;
            respond(req, res, 400, "BAD_REQUEST", a.isShown() ? a.getMessage() : null, null); // OPS-AUTH-018 AC-1
        } else if (err instanceof AuthStateException) {
            if (!coreLogged) {
                logger.error("auth: " + req.method() + " " + req.path() + " used auth in a wrong state; answering 500",
                        err);
            }
            respond(req, res, 500, "INTERNAL_ERROR", null, null);
        } else {
            return false;
        }
        return true;
    }

    /** The Core error code of a status. */
    static String coreCode(int status) {
        switch (status) {
            case 400: return "BAD_REQUEST";
            case 404: return "NOT_FOUND";
            case 405: return "METHOD_NOT_ALLOWED";
            case 413: return "PAYLOAD_TOO_LARGE";
            case 415: return "UNSUPPORTED_MEDIA_TYPE";
            case 422: return "UNPROCESSABLE_ENTITY";
            case 500: return "INTERNAL_ERROR";
            case 501: return "NOT_IMPLEMENTED";
            case 503: return "SERVICE_UNAVAILABLE";
            default: return "HTTP_" + status;
        }
    }

    // ---------------------------------------------------------------------------------------------- principal

    /**
     * The principal of a request on a protected path; {@link AuthStateException} when there is none (a public path:
     * use {@link #userOrNull(Request)} there).
     */
    // @req IR-AUTH-004
    public AuthPrincipal user(Request req) {
        AuthPrincipal p = userOrNull(req);
        if (p == null) {
            throw new AuthStateException("auth.user(req) found no principal for " + req.method() + " " + req.path()
                    + (app == null ? "; auth is not installed on this app, so no request carries a principal: add "
                    + "app.use(auth) before the routes" // OPS-AUTH-026 AC-1
                    : "; on a public path use auth.userOrNull(req)"));
        }
        return p;
    }

    /** The principal of a request, or null (an anonymous request on a public path). */
    // @req IR-AUTH-004
    public @Nullable AuthPrincipal userOrNull(Request req) {
        return req.attributeAs(PRINCIPAL, AuthPrincipal.class);
    }

    /**
     * The loaded principal, never throwing: null without a principal, or when promoting a minimal principal finds the
     * user missing, disabled, at another token version, or the store failing (logged). A loaded principal is
     * returned as it is, without a store call.
     */
    // @req IR-AUTH-004
    public @Nullable AuthPrincipal loadUser(Request req) {
        AuthPrincipal p = userOrNull(req);
        if (p == null || p.isLoaded()) {
            return p;
        }
        try {
            AuthPrincipal loaded = load(p.getId(), p.tokenVersion(), p.getApiKeyId(), p.keyScopes());
            if (loaded != null) {
                req.attribute(PRINCIPAL, loaded);
            }
            return loaded;
        } catch (Exception e) {
            logger.error("auth: loadUser could not read the user store; returning null", e);
            return null;
        }
    }

    /** What a guard decides about a loaded principal. */
    private interface Decision {
        boolean allows(AuthPrincipal p, Request req) throws Exception;
    }

    private Middleware guard(final Decision decision) {
        return guard(decision, null);
    }

    /** The names separated by single spaces ({@code String.join} is API 26). */
    private static String spaced(Iterable<String> names) {
        StringBuilder b = new StringBuilder();
        for (String n : names) {
            b.append(b.length() == 0 ? "" : " ").append(n);
        }
        return b.toString();
    }

    /** {@code need}: what the route requires, named in its 403 (OPS-AUTH-016 AC-1); null keeps the plain message. */
    private Middleware guard(final Decision decision, @Nullable final String need) {
        final String refused = "the principal lacks a required scope or role" + (need == null ? "" : "; this route requires "
                + need);
        return (req, res, next) -> {
            if (app == null) { // OPS-AUTH-023 AC-1: a guard of an auth never installed refuses loudly too
                notInstalled(req, res, true);
                return;
            }
            AuthPrincipal p = userOrNull(req);
            if (p == null) {
                reject(req, res, AuthErrorCode.UNAUTHENTICATED, isPublic(req) // OPS-AUTH-030 AC-1
                        ? "authentication is required; a guard on this route needs a login (.permit opens default "
                        + "deny only, not the guards on a route)" : unauthenticatedMessage(req));
                return;
            }
            if (!p.isLoaded()) {
                AuthPrincipal loaded;
                try {
                    loaded = load(p.getId(), p.tokenVersion(), p.getApiKeyId(), p.keyScopes());
                } catch (Exception e) {
                    unavailable(req, res, "loading the user", e);
                    return;
                }
                if (loaded == null) {
                    reject(req, res, AuthErrorCode.INVALID_TOKEN, NO_LONGER_VALID); // OPS-AUTH-030 AC-2
                    return;
                }
                req.attribute(PRINCIPAL, loaded);
                p = loaded;
            }
            boolean allowed;
            try {
                allowed = decision.allows(p, req);
            } catch (Exception e) {
                logger.error("auth: an access rule failed; answering 500", e);
                respond(req, res, 500, "INTERNAL_ERROR", null, null);
                return;
            }
            if (!allowed) {
                reject(req, res, AuthErrorCode.INSUFFICIENT_SCOPE, p.getApiKeyId() == null ? refused
                        : refused + "; an API key acts only with the scopes its owner also holds"); // OPS-AUTH-027 AC-1
                return;
            }
            next.run();
        };
    }

    /**
     * A guard passing principals that hold every one of {@code scopes} (wildcard-free {@code resource:action});
     * otherwise 403 {@code insufficient_scope}, and 401 without a principal. An invalid scope throws
     * {@link IllegalArgumentException} here, when the route is declared.
     */
    // @req IR-AUTH-004
    public Middleware require(String... scopes) {
        if (scopes.length == 0) {
            throw new IllegalArgumentException("auth.require needs at least one scope");
        }
        final List<String> required = new ArrayList<>();
        for (String s : scopes) {
            required.add(Scopes.validateRequired(Objects.requireNonNull(s, "scope")));
        }
        guardScopes.addAll(required); // the doctor warns about a scope no RolePolicy role grants (OPS-AUTH-008 AC-1)
        return guard((p, req) -> {
            for (String s : required) {
                if (!p.hasScope(s)) {
                    return false;
                }
            }
            return true;
        }, "the scope " + spaced(required));
    }

    /**
     * A guard passing principals that hold at least one of {@code roles}; otherwise 403 {@code insufficient_scope},
     * and 401 without a principal. An API-key request is refused with 403 {@code api_key_not_allowed}: a key narrows
     * scopes, not roles, so a role guard cannot tell what the key may do.
     */
    // @req IR-AUTH-004
    public Middleware requireRole(String... roles) {
        if (roles.length == 0) {
            throw new IllegalArgumentException("auth.requireRole needs at least one role");
        }
        final Set<String> any = new LinkedHashSet<>();
        for (String r : roles) {
            if (Objects.requireNonNull(r, "role").trim().isEmpty()) { // OPS-AUTH-027 AC-6
                throw new IllegalArgumentException("auth.requireRole got a blank role name \"" + r + "\"; name a role "
                        + "the RolePolicy declares");
            }
            any.add(r);
        }
        guardRoles.addAll(any); // the doctor warns about a name no RolePolicy declares (OPS-AUTH-006 AC-4)
        final Middleware byRole = guard((p, req) -> {
            for (String r : any) {
                if (p.hasRole(r)) {
                    return true;
                }
            }
            return false;
        }, "one of the roles " + spaced(any));
        return (req, res, next) -> {
            AuthPrincipal p = userOrNull(req);
            if (p != null && p.getApiKeyId() != null) {
                reject(req, res, AuthErrorCode.API_KEY_NOT_ALLOWED, "role guards do not accept API keys");
                return;
            }
            byRole.handle(req, res, next);
        };
    }

    /**
     * A guard that only requires a loaded principal: it promotes a minimal one and answers 401 when the user is
     * disabled or the token version differs, 503 when the store fails.
     */
    // @req IR-AUTH-004
    public Middleware authenticated() {
        return guard((p, req) -> true);
    }

    /** A guard deciding with {@code rule}: true continues, false answers 403, and 401 without a principal. */
    // @req IR-AUTH-004
    public Middleware check(final AccessRule rule) {
        Objects.requireNonNull(rule, "rule");
        return guard(rule::allows, "its auth.check rule to allow the principal"); // OPS-AUTH-029 AC-7
    }

    /** {@link #check(AccessRule)} whose 403 names {@code need} (the admin endpoints, OPS-AUTH-018 AC-4). */
    // @req OPS-AUTH-018
    Middleware check(final AccessRule rule, String need) {
        return guard(rule::allows, need);
    }

    private final Set<String> warnedAdminRoles = Collections.synchronizedSet(new LinkedHashSet<String>());

    /** OPS-AUTH-018 AC-7: one WARN per role name an admin assigns that the RolePolicy does not declare. */
    // @req OPS-AUTH-018
    void warnUndeclaredRoles(@Nullable Iterable<String> roles) {
        warnUndeclaredRoles(roles, "an admin");
    }

    /** OPS-AUTH-022 AC-4: the same WARN for a role given by {@code who}. */
    void warnUndeclaredRoles(@Nullable Iterable<String> roles, String who) {
        if (roles == null) {
            return;
        }
        for (String r : roles) {
            if (!declaresRole(r) && warnedAdminRoles.add(r)) {
                logger.warn("auth: " + who + " assigned the role '" + r + "', which the RolePolicy does not declare, so "
                        + "it grants nothing; a typo? Declare it with RolePolicy.builder().role(\"" + r + "\", ...)");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- users

    /** The app's user facade: normalized names, hashed passwords, events, and copies without hashes. */
    // @req IR-IAM-003
    public AuthUsers users() {
        return usersFacade;
    }

    /**
     * The user store passed to {@code .users(store)}, for code that needs the hash. Never pass a user returned by
     * {@link #users()} to its {@code update}: that copy has no hash, so the store would save a null hash. The users it
     * returns carry their hash: the transient passwordHash is left out of JSON only by a Gson with the default
     * modifier settings (new GsonJsonCodec()); with a Gson built with excludeFieldsWithModifiers(STATIC) or another
     * JsonCodec, the app must remove passwordHash itself.
     */
    // @req IR-IAM-003
    public UserStore userStore() {
        return users;
    }

    /** The mode this auth runs in. */
    public AuthMode mode() {
        return mode;
    }

    /** The account endpoint mount path (default {@code /auth}). */
    public String mountPath() {
        return mountPath;
    }

    @Override
    public String toString() {
        return "Auth{mode=" + mode + ", profile=" + profile + ", deployment=" + deployment + ", transport=" + transport
                + ", mount=" + mountPath + ", users=" + users.getClass().getSimpleName() + ", signingKey=<"
                + signingKey.length + " bytes>}";
    }

    String apiKeyScheme() {
        return apiKeyScheme;
    }

    /**
     * The app's API key facade: issue, list and revoke keys of any user. Every call throws {@link AuthStateException}
     * when the builder had no {@code .apiKeys(store)}. It performs no actor checks (see {@link AuthApiKeys}).
     */
    // @req IR-APIKEY-002
    public AuthApiKeys apiKeys() {
        return apiKeysFacade;
    }

    DefaultAuthApiKeys apiKeysCore() {
        return apiKeysFacade;
    }

    int maxKeysPerUser() {
        return maxKeysPerUser;
    }

    /** True when {@code userId} is a service account; false when it is not, is gone, or the store fails. */
    // @req FR-APIKEY-006
    boolean isServiceAccount(String userId) {
        try {
            AuthUser u = users.findById(userId);
            return u != null && u.isServiceAccount();
        } catch (Exception e) {
            return false; // an audit mark is best effort
        }
    }

    /** The per-owner key side of this auth. */
    ApiKeyOwners apiKeyOwners() {
        return apiKeyOwners;
    }

    /** The built-in key procedure, or null without {@code .apiKeys(store)}. */
    @Nullable ApiKeyCredentials keyCredentials() {
        return keyCredentials;
    }

    Deployment deployment() {
        return deployment;
    }

    int maxApiKeyDays() {
        return maxApiKeyDays;
    }

    boolean allowAdminScopesOnApiKeys() {
        return allowAdminScopesOnApiKeys;
    }

    int apiKeyFailureMax() {
        return apiKeyFailureMax;
    }

    long apiKeyFailureWindowMillis() {
        return apiKeyFailureWindowMillis;
    }

    boolean registerEnabled() {
        return registerEnabled;
    }

    /** The running current-password confirmations, one per user id (SEC-FLOW-015). */
    private final ConfirmationHolds confirmationHolds = new ConfirmationHolds();

    ConfirmationHolds confirmationHolds() {
        return confirmationHolds;
    }

    /** The attributes {@code PATCH /auth/me} may change ({@code .allowedAttributes}). */
    Set<String> allowedAttributes() {
        return allowedAttributes;
    }

    NewPasswords newPasswords() {
        return newPasswords;
    }

    /** The {@code .trustedOrigins(...)}, normalized ({@code scheme://host[:port]}). */
    Set<String> trustedOrigins() {
        return trustedOrigins;
    }

    /** The key set auth signs and verifies with; package-private until the token codec exists. */
    JwtKeys keySet() {
        return keySet;
    }

    /** The effective refresh-token store: the configured one or the default that follows the user store. */
    RefreshTokenStore refreshTokens() {
        return refreshTokens;
    }

    /** The auth cookies of this auth (names, attributes, the shared {@code .cookies(...)} configurer). */
    AuthCookies cookies() {
        return cookies;
    }

    /** The audit trail over the builder's sink; package-private for the auth paths and tests. */
    AuditTrail audit() {
        return audit;
    }

    /** A real access token of {@code userId} at {@code ver}, valid for {@code ttlMillis}; for the tests. */
    String accessToken(String userId, long ver, long ttlMillis) {
        return accessTokens.issue(userId, ver, AccessTokens.random(), ttlMillis);
    }

    /**
     * A human-readable summary of the effective configuration, one fact per line: mode, profile, deployment,
     * transport, mount paths, the permitted paths with their methods, the protected range, the WebSocket paths opened
     * by {@code .permitWebSocket} and the stores. It never contains a key, password or token. The format is for
     * people and may change between releases; do not parse it.
     */
    // @req OPS-AUTH-002
    // @req SEC-AUTH-006
    public String describe() {
        StringBuilder b = new StringBuilder("auth\n");
        b.append("  mode: ").append(mode).append('\n');
        b.append("  profile: ").append(profile).append('\n');
        b.append("  deployment: ").append(deployment).append('\n');
        b.append("  transport: ").append(transport).append('\n');
        b.append("  mount: ").append(mountPath).append('\n');
        b.append("  admin: ").append(adminPath).append('\n');
        b.append("  permitted:");
        if (permits.isEmpty() && permitPrefixes.isEmpty()) {
            b.append(" none");
        }
        for (PathRule p : permits) {
            b.append("\n    ").append(p.methodList()).append(' ').append(p.path);
        }
        for (String p : permitPrefixes) {
            b.append("\n    * ").append(p).append("/**");
        }
        b.append('\n');
        if (protectOnly.isEmpty()) {
            b.append("  protected: every path that is not permitted\n");
        } else {
            b.append("  ").append(protectedListing()).append('\n');
        }
        if (!permitWebSockets.isEmpty()) {
            b.append("  websocket permitted: ").append(permitWebSockets).append('\n');
        }
        b.append("  users: ").append(users.getClass().getSimpleName()).append('\n');
        b.append("  refresh tokens: ").append(refreshTokens.getClass().getSimpleName()).append('\n');
        b.append("  api keys: ").append(apiKeys == null ? "off" : apiKeys.getClass().getSimpleName() + " (scheme "
                + apiKeyScheme + ", at most " + maxKeysPerUser + " live keys per user, at most " + maxApiKeyDays
                + " days, admin scopes " + (allowAdminScopesOnApiKeys ? "allowed" : "refused") + ", failure limit "
                + apiKeyFailureMax + " per " + apiKeyFailureWindowMillis + " ms)").append('\n');
        b.append("  register: ").append(registerEnabled ? "on" : "off").append('\n');
        b.append("  signing kid: ").append(keySet.getSigningKid());
        return b.toString();
    }

    /** The token service of this auth: issue, refresh and verify access and refresh tokens. */
    // @req FR-TOKEN-001
    public TokenService tokens() {
        return tokens;
    }

    /**
     * Wraps {@code handler} for {@code app.ws(path, auth.webSocket(handler))} (00 R40): authentication and the Origin
     * check stay before the 101 response (an upgrade without a valid token gets 401 JSON and the handler never runs);
     * after it, a connection opened with an access token is closed with {@code close(1008, "token expired")} at the
     * token's expiry, every connection is closed with {@code close(1008, "access revoked")} when its user's principal,
     * re-read every {@code userCacheTtl}, is gone, disabled, at another token version or has other roles or scopes,
     * and at once on this node when auth itself disables or deletes the user or changes its password. The
     * wrapped handler reads the principal with {@code auth.user(req)}. Express counterpart: an express-ws handler that
     * checks the token on connect and sets a timer for its expiry.
     */
    // @req FR-FLOW-008
    public WebSocketHandler webSocket(final WebSocketHandler handler) {
        Objects.requireNonNull(handler, "handler");
        return new WrappedWebSocket(this, handler);
    }

    /** The handler {@link #webSocket} returns; the doctor counts the WEBSOCKET registrations whose target is one. */
    // @req FR-FLOW-008
    private static final class WrappedWebSocket implements WebSocketHandler {
        final Auth auth;
        private final WebSocketHandler handler;

        WrappedWebSocket(Auth auth, WebSocketHandler handler) {
            this.auth = auth;
            this.handler = handler;
        }

        @Override
        public void handle(Request req, WebSocketSession ws) throws Exception {
            AuthPrincipal p = auth.userOrNull(req);
            if (p != null) {
                Long exp = req.attributeAs(TOKEN_EXPIRES, Long.class);
                auth.track(new TrackedSocket(p, ws, exp == null ? Long.MAX_VALUE : exp, auth.time.nowMillis()));
            }
            handler.handle(req, ws);
        }
    }

    /** One wrapped connection: its user, token version, expiry, last recheck and the roles and scopes it opened with. */
    static final class TrackedSocket {
        final String userId;
        final long tokenVersion;
        final @Nullable String apiKeyId;
        final WebSocketSession ws;
        final long expiresAt;
        volatile long checkedAt;
        volatile @Nullable Set<String> baseline;

        TrackedSocket(AuthPrincipal p, WebSocketSession ws, long expiresAt, long now) {
            this.userId = p.getId();
            this.tokenVersion = p.tokenVersion();
            this.apiKeyId = p.getApiKeyId();
            this.ws = ws;
            this.expiresAt = expiresAt;
            this.checkedAt = now;
            this.baseline = p.isLoaded() ? grantsKey(p.getRoles(), p.getScopes()) : null;
        }
    }

    private static Set<String> grantsKey(Set<String> roles, Set<String> scopes) {
        Set<String> k = new LinkedHashSet<>();
        for (String r : roles) {
            k.add("r:" + r);
        }
        for (String sc : scopes) {
            k.add("s:" + sc);
        }
        return k;
    }

    /** True when {@code t} is in the per-user index (and so reached by expiry and revocation); for the tests. */
    boolean isTracked(TrackedSocket t) {
        Set<TrackedSocket> set = sockets.get(t.userId);
        return set != null && set.contains(t);
    }

    // @req FR-FLOW-008
    void track(TrackedSocket t) {
        synchronized (sockets) { // against the empty-set removal of webSocketTick
            Set<TrackedSocket> set = sockets.get(t.userId);
            if (set == null) {
                set = Collections.newSetFromMap(new ConcurrentHashMap<TrackedSocket, Boolean>());
                sockets.put(t.userId, set);
            }
            set.add(t);
        }
        startSocketTicker();
    }

    private synchronized void startSocketTicker() {
        if (socketTicker != null) {
            return;
        }
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "naru-auth-websocket-" + CLEANUP_THREADS.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        ex.scheduleWithFixedDelay(() -> {
            try {
                webSocketTick();
            } catch (RuntimeException e) {
                logger.error("auth: the WebSocket expiry check failed; it runs again in a second", e);
            }
        }, 1000L, 1000L, TimeUnit.MILLISECONDS);
        socketTicker = ex;
    }

    private synchronized void stopSocketTicker() {
        ScheduledExecutorService ex = socketTicker;
        socketTicker = null;
        if (ex != null) {
            ex.shutdownNow();
        }
    }

    /**
     * One pass over the wrapped connections at the {@link TimeSource} time: closes expired ones and rechecks those
     * whose last check is {@code userCacheTtl} old. Runs every second; package-private so tests drive it.
     */
    // @req FR-FLOW-008
    void webSocketTick() {
        long now = time.nowMillis();
        for (Iterator<java.util.Map.Entry<String, Set<TrackedSocket>>> it = sockets.entrySet().iterator();
                it.hasNext();) {
            java.util.Map.Entry<String, Set<TrackedSocket>> e = it.next();
            for (TrackedSocket t : e.getValue()) {
                if (!t.ws.isOpen()) {
                    e.getValue().remove(t);
                } else if (now >= t.expiresAt) {
                    close(t, t.apiKeyId != null ? "api key expired" : "token expired");
                } else if (now - t.checkedAt >= principals.ttlMillis()) {
                    t.checkedAt = now;
                    recheck(t);
                }
            }
            if (e.getValue().isEmpty()) {
                synchronized (sockets) {
                    if (e.getValue().isEmpty()) {
                        sockets.remove(e.getKey(), e.getValue());
                    }
                }
            }
        }
    }

    // @req FR-FLOW-008
    // @req SEC-APIKEY-011
    private void recheck(TrackedSocket t) {
        PrincipalCache.Snapshot s;
        try {
            // the recheck re-reads the principal (00 R40): a cache entry an ordinary request refreshed just before a
            // change made outside auth would otherwise hide that change for one more userCacheTtl
            s = principals.refresh(t.userId);
            if (t.apiKeyId != null) {
                if (!keyStillValid(t)) {
                    close(t, "access revoked");
                    return;
                }
                if (s != null && s.getTokenVersion() < t.tokenVersion) {
                    // the invariant violation of SEC-APIKEY-006 (the owner was just re-read): deleted off the request
                    // path under SINGLE_INSTANCE (the job re-checks the store inside the owner lock), kept under
                    // MULTI_INSTANCE
                    apiKeyOwners.scheduleDelete(t.apiKeyId, t.userId, t.tokenVersion);
                }
            }
        } catch (Exception e) {
            logger.error("auth: rechecking a WebSocket user failed; the connection stays open until the next check", e);
            return;
        }
        if (s == null || !s.isEnabled() || s.getTokenVersion() != t.tokenVersion
                || t.apiKeyId != null && apiKeyOwners.rejects(t.userId, t.tokenVersion)) {
            close(t, "access revoked"); // a key's ownerVer is the ver it authenticated at (REL-APIKEY-004 AC-6)
            return;
        }
        Set<String> key = grantsKey(s.getRoles(), s.getScopes());
        Set<String> base = t.baseline;
        if (base == null) {
            t.baseline = key; // a minimal principal promoted at its first recheck is not a change
        } else if (!base.equals(key)) {
            close(t, "access revoked");
        }
    }

    private void close(TrackedSocket t, String reason) {
        Set<TrackedSocket> set = sockets.get(t.userId);
        if (set != null) {
            set.remove(t);
        }
        try {
            t.ws.close(1008, reason);
        } catch (RuntimeException e) {
            logger.warn("auth: closing a WebSocket connection failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * The key row of a connection opened with an API key, re-read at its recheck (00 R40 23rd edition): false when the
     * key was deleted, has expired, or no longer carries the ownerVer the connection authenticated at.
     */
    // @req SEC-APIKEY-011
    private boolean keyStillValid(TrackedSocket t) throws Exception {
        ApiKeyStore keys = apiKeys;
        String id = t.apiKeyId;
        if (keys == null || id == null) {
            return false;
        }
        StoredApiKey k = keys.findById(id);
        if (k == null || !k.getOwnerUserId().equals(t.userId) || k.getOwnerVer() != t.tokenVersion) {
            return false;
        }
        Long exp = k.getExpiresAt();
        return exp == null || time.nowMillis() < exp;
    }

    /** A key auth deleted on this node (revoke, a key endpoint's delete): its wrapped connections close at once. */
    // @req SEC-APIKEY-011
    void keyRevoked(String keyId) {
        for (Set<TrackedSocket> set : sockets.values()) {
            for (TrackedSocket t : set) {
                if (keyId.equals(t.apiKeyId)) {
                    close(t, "access revoked");
                }
            }
        }
    }

    /** Closes every wrapped connection of {@code userId} on this node at once. */
    // @req FR-FLOW-008
    void revokeConnections(String userId) {
        Set<TrackedSocket> set = sockets.get(userId);
        if (set != null) {
            for (TrackedSocket t : set) {
                close(t, "access revoked");
            }
        }
    }

    /** A change of one user through auth that keeps its sessions: the cache forgets the user. */
    // @req SEC-AUTH-007
    void userChanged(String userId) {
        principals.invalidate(userId);
    }

    /** A revocation through auth (disable, delete, ver++): the cache forgets the user and its connections close. */
    // @req SEC-AUTH-007
    // @req SEC-IAM-003
    void userRevoked(String userId) {
        principals.invalidate(userId);
        revokeConnections(userId);
    }

    /** A role or group change through auth, which may reach any user: the whole cache is cleared. */
    // @req SEC-AUTH-007
    void rolesChanged() {
        principals.clear();
    }

    PrincipalCache principalCache() {
        return principals;
    }

    GrantRule grantRule() {
        return grantRule;
    }

    EffectiveGrants grants() {
        return grants;
    }

    @Nullable GroupStore groupStore() {
        return groups;
    }

    GroupLimits groupLimits() {
        return groupLimits;
    }

    int groupLimitsDepth() {
        return groupLimits.getMaxDepth();
    }

    TimeSource timeSource() {
        return time;
    }

    /** True when {@code role} is declared: a RolePolicy role (the built-in admin included), or any role of a custom RoleStore. */
    boolean declaresRole(String role) {
        return !(roleStore instanceof RolePolicy) || ((RolePolicy) roleStore).roles().contains(role);
    }

    @Nullable ApiKeyStore apiKeyStore() {
        return apiKeys;
    }

    @Nullable ApiKeyUsage apiKeyUsage() {
        return apiKeyUsage;
    }

    List<AuthEvents> eventListeners() {
        return events;
    }

    Logger log() {
        return logger;
    }

    TimeSource time() {
        return time;
    }

    DefaultAuthUsers adminUsers() {
        return usersFacade;
    }

    Executor adminExecutor() {
        return background;
    }

    String adminMount() {
        return adminPath;
    }

    @Nullable Lockouts lockouts() {
        return lockouts;
    }

    LoginThrottle loginThrottle() {
        return throttle;
    }

    DeviceTrust deviceTrust() {
        return deviceTrust;
    }

    DeviceTokens deviceTokens() {
        return deviceTokens;
    }

    CsrfTokens csrfTokens() {
        return csrfTokens;
    }

    TokenCodec tokenCodec() {
        return tokenCodec;
    }

    /** The writer of every token-issuing response (00 R29 (e)). */
    TokenDelivery tokenDelivery() {
        return tokenDelivery;
    }

    AuthCookies authCookies() {
        return cookies;
    }

    TokenTransport transport() {
        return transport;
    }

    /** The password-login sequence the POST /auth/login endpoint runs (00 R34 (2), R38). */
    PasswordLogin passwordLogin() {
        return passwordLogin;
    }

    /** Replaces the failure floor (an injected scheduler, clock and jitter); for the tests. */
    void failureFloor(FailureFloor floor) {
        this.failureFloor = floor;
    }

    /** The failure floor when a login already started it, else null; for the tests. */
    @Nullable FailureFloor failureFloorIfStarted() {
        return failureFloor;
    }

    /** The R38 failure floor over the start-time T, on its own daemon scheduler thread (created on first use). */
    // @req SEC-TOKEN-009
    FailureFloor failureFloor() {
        FailureFloor f = failureFloor;
        if (f == null) {
            synchronized (this) {
                f = failureFloor;
                if (f == null) {
                    ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
                        Thread t = new Thread(r, "naru-auth-floor-" + FLOOR_THREADS.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
                    ex.setRemoveOnCancelPolicy(true);
                    floorScheduler = ex;
                    f = new FailureFloor(passwords.timing(), ex);
                    failureFloor = f;
                }
            }
        }
        return f;
    }

    @Nullable AuthResponses responses() {
        return responses;
    }

    RehashQueue rehashQueue() {
        return rehash;
    }

    /** The password hashing of this auth; package-private for the tests. */
    Passwords passwords() {
        return passwords;
    }

    /** The hash slot pool of this auth; package-private for the tests. */
    HashSlots hashSlots() {
        return hashSlots;
    }

    /** The token of the open setup (a null-password admin .user's, else the recovery one), or null; for the tests. */
    @Nullable String pendingSetupToken() {
        PendingSetup p = openSetup();
        return p == null ? null : p.token;
    }

    /** The setup the setup endpoint may still consume, or null when setup is closed (404). */
    // @req SEC-AUTH-003
    @Nullable PendingSetup openSetup() {
        PendingSetup p = pendingSetup;
        if (p != null) {
            return p.used.get() ? null : p;
        }
        PendingSetup r = recoverySetup;
        return r == null || r.used.get() ? null : r;
    }

    // ---------------------------------------------------------------------------------------------- builder

    /** One {@code .permit} rule: a method set and an exact raw path. */
    static final class PathRule {
        final Set<Method> methods;
        final String path;

        PathRule(Set<Method> methods, String path) {
            this.methods = methods;
            this.path = path;
        }

        boolean matches(Method m, String p) {
            return methods.contains(m) && path.equals(p);
        }

        String methodList() {
            StringBuilder b = new StringBuilder();
            for (Method m : methods) {
                b.append(b.length() == 0 ? "" : ",").append(m.name());
            }
            return b.toString();
        }

        @Override
        public String toString() {
            return methods + " " + path;
        }
    }

    /**
     * Configures an {@link Auth}; every setter returns this builder and {@link #build()} checks every rule at once,
     * reporting all violations in one {@link AuthConfigException}. {@link #toString()} never shows the key.
     */
    // @req FR-AUTH-001
    public static final class Builder {
        private KeySource keys;
        private AuthMode mode = AuthMode.PROD;
        private AuthProfile profile = AuthProfile.STANDARD;
        private Deployment deployment = Deployment.SINGLE_INSTANCE;
        private TokenTransport transport = TokenTransport.HEADER;
        private String mountPath = "/auth";
        private final List<PathRule> permits = new ArrayList<>();
        private final List<String> permitPrefixes = new ArrayList<>();
        private final List<String> protectOnly = new ArrayList<>();
        private final List<String> permitWebSockets = new ArrayList<>();
        private boolean temporaryKey;
        private final List<String> pathViolations = new ArrayList<>();
        private final List<String> configViolations = new ArrayList<>();
        private int rolesCalls;
        private boolean secretWhitespace;
        private boolean statelessAccess;
        private long userCacheTtl = PrincipalCache.DEFAULT_TTL_MILLIS;
        private long accessTtl;
        private long refreshTtl;
        private @Nullable Lockout lockout;
        private @Nullable UserStore users;
        private @Nullable RefreshTokenStore refreshTokens;
        private @Nullable LoginThrottle loginThrottle;
        private @Nullable DeviceTrust deviceTrust;
        private @Nullable TokenCodec tokenCodec;
        private long reuseGraceMillis;
        private @Nullable ApiKeyStore apiKeys;
        private @Nullable AuditSink audit;
        private @Nullable Configurer<CookieOptions> cookies;
        private long cleanupIntervalMillis = CLEANUP_INTERVAL_MILLIS;
        private long snapshotIntervalMillis = SNAPSHOT_INTERVAL_MILLIS;
        private int loginUserIpMax = LoginLimits.USER_IP_MAX;
        private int loginIpMax = LoginLimits.IP_MAX;
        private int loginUserMax = LoginLimits.USER_MAX;
        private long loginWindowMillis = LoginLimits.WINDOW_MILLIS;
        private @Nullable GroupStore groups;
        private GroupLimits groupLimits = GroupLimits.defaults();
        private @Nullable RoleStore roles;
        private final List<AuthEvents> events = new ArrayList<>();
        private @Nullable AuthResponses responses;
        private TimeSource time = TimeSource.system();
        private @Nullable Logger logger;
        private String apiKeyScheme = "ApiKey";
        private int maxKeysPerUser = DEFAULT_MAX_KEYS_PER_USER;
        private int maxApiKeyDays = DEFAULT_MAX_API_KEY_DAYS;
        private boolean allowAdminScopesOnApiKeys;
        private int apiKeyFailureMax = DEFAULT_API_KEY_FAILURES;
        private long apiKeyFailureWindowMillis = DEFAULT_API_KEY_FAILURE_WINDOW_MILLIS;
        private boolean register;
        private final Set<String> allowedAttributes = new LinkedHashSet<>();
        private @Nullable PasswordPolicy passwordPolicy;
        private @Nullable BreachedPasswordChecker breachedPasswords;
        private @Nullable Authenticator authenticator;
        private final List<String> trustedOrigins = new ArrayList<>();
        private @Nullable CredentialResolver credentials;
        private @Nullable TokenVerifier tokenVerifier;
        private HashCost hashCost = HashCost.defaults();
        private int hashConcurrency;
        private @Nullable PasswordHasher passwordHasher;
        private final List<String[]> userSeeds = new ArrayList<>();
        private boolean recovery;
        private boolean testHashCost;
        private @Nullable String insecureReason;
        private final List<String> preAuthAllowed = new ArrayList<>();

        private Builder(KeySource keys) {
            this.keys = keys;
        }

        /** {@link AuthMode#PROD} (default) or {@link AuthMode#DEV}; usually {@code .mode(AuthMode.fromEnv())}. */
        // @req FR-AUTH-001
        public Builder mode(AuthMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /** The security preset; {@link AuthProfile#STANDARD} when not called. */
        // @req FR-AUTH-003
        public Builder profile(AuthProfile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /** {@link Deployment#SINGLE_INSTANCE} (default) or {@link Deployment#MULTI_INSTANCE}. */
        // @req FR-AUTH-003
        public Builder deployment(Deployment deployment) {
            this.deployment = Objects.requireNonNull(deployment, "deployment");
            return this;
        }

        /** Where the access token travels; {@link TokenTransport#HEADER} when not called. */
        // @req FR-AUTH-001
        public Builder transport(TokenTransport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            return this;
        }

        /** The user store; an {@link InMemoryUserStore} when not called. */
        // @req FR-AUTH-001
        public Builder users(UserStore store) {
            this.users = Objects.requireNonNull(store, "store");
            return this;
        }

        /**
         * The refresh-token store; when not called, the default that follows the user store (memory for
         * {@link InMemoryUserStore}, {@code <name>-refresh-tokens.json} beside a {@link FileUserStore}'s file, memory
         * with a doctor WARN for another store under {@code SINGLE_INSTANCE}, and a {@code build()} failure under
         * {@code MULTI_INSTANCE}, where an app-provided store over the shared database is required).
         */
        // @req FR-TOKEN-002
        public Builder refreshTokens(RefreshTokenStore store) {
            this.refreshTokens = Objects.requireNonNull(store, "store");
            return this;
        }

        /**
         * Turns API keys on with {@code store}; off (no key is accepted) when not called. Deleting a user then also
         * deletes its keys, and the 10-minute cleanup deletes expired keys.
         */
        // @req FR-IAM-008
        public Builder apiKeys(ApiKeyStore store) {
            this.apiKeys = Objects.requireNonNull(store, "store");
            return this;
        }

        /** Where auth sends its audit events; nowhere when not called. */
        // @req FR-AUTH-009
        public Builder audit(AuditSink sink) {
            this.audit = Objects.requireNonNull(sink, "sink");
            return this;
        }

        /**
         * Shared attributes of the cookies auth sets ({@code naru_at}, {@code naru_rt}, {@code naru_csrf}), for example
         * {@code c -> c.domain("example.com")}; applied after auth's defaults. Changing Path or Domain later means the
         * app must expire the old cookies itself (see the auth cookies section of the docs).
         */
        // @req SEC-FLOW-009
        public Builder cookies(Configurer<CookieOptions> configurer) {
            this.cookies = Objects.requireNonNull(configurer, "configurer");
            return this;
        }

        /** The cleanup interval; package-private, for the tests. */
        Builder cleanupInterval(long millis) {
            this.cleanupIntervalMillis = millis;
            return this;
        }

        /** The snapshot interval of the file-backed throttle and device trust; package-private, for the tests. */
        Builder snapshotInterval(long millis) {
            this.snapshotIntervalMillis = millis;
            return this;
        }

        /** The group store; no groups when not called. */
        // @req FR-AUTH-001
        public Builder groups(GroupStore store) {
            this.groups = Objects.requireNonNull(store, "store");
            return this;
        }

        /** The group expansion limits; depth 8 and 256 groups when not called. */
        public Builder groupLimits(GroupLimits limits) {
            this.groupLimits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /** The roles: a {@link RolePolicy} or a database {@link RoleStore}; the built-in policy when not called. */
        // @req FR-AUTH-001
        public Builder roles(RoleStore roles) {
            this.roles = Objects.requireNonNull(roles, "roles");
            rolesCalls++;
            return this;
        }

        /** Adds an event listener; listeners run in the order added. */
        // @req FR-AUTH-001
        public Builder events(AuthEvents listener) {
            this.events.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /** Makes GET and HEAD of exactly {@code path} (the raw, undecoded path) public. */
        // @req FR-AUTH-001
        public Builder permit(String path) {
            return permit(EnumSetOf.getHead(), path);
        }

        /** Makes {@code method} of exactly {@code path} public. */
        // @req FR-AUTH-001
        public Builder permit(Method method, String path) {
            return permit(Collections.singleton(Objects.requireNonNull(method, "method")), path);
        }

        private Builder permit(Set<Method> methods, String path) {
            Objects.requireNonNull(path, "path");
            String bad = pathProblem(".permit", path, false);
            if (bad != null) {
                pathViolations.add(bad);
            } else {
                permits.add(new PathRule(methods, path));
            }
            return this;
        }

        /** Makes every method of {@code prefix} and everything below {@code prefix + "/"} public. */
        // @req FR-AUTH-001
        public Builder permitPrefix(String prefix) {
            Objects.requireNonNull(prefix, "prefix");
            String bad = pathProblem(".permitPrefix", prefix, true);
            if (bad != null) {
                pathViolations.add(bad);
            } else {
                permitPrefixes.add(trimSlash(prefix));
            }
            return this;
        }

        /**
         * Protects only paths at or below the given prefixes (and the auth mounts, and every WebSocket upgrade);
         * everything else is public.
         */
        // @req FR-AUTH-001
        public Builder protectOnly(String... prefixes) {
            for (String p : prefixes) {
                Objects.requireNonNull(p, "prefix");
                String bad = pathProblem(".protectOnly", p, false);
                if (bad == null && widePath(p) == null) {
                    bad = ".protectOnly paths must not hold a . or .. segment or an invalid %XX escape: " + quote(p);
                }
                if (bad != null) {
                    pathViolations.add(bad);
                } else {
                    protectOnly.add(trimSlash(p));
                }
            }
            return this;
        }

        /**
         * Lets WebSocket upgrades to exactly {@code path} (compared with the decoded WebSocket {@code req.path()})
         * open without a token; every other upgrade is protected, whatever {@code .permit} and {@code .protectOnly}
         * say. A token on such an upgrade is still read (optional authentication).
         */
        // @req SEC-AUTH-006
        public Builder permitWebSocket(String path) {
            Objects.requireNonNull(path, "path");
            String bad = pathProblem(".permitWebSocket", path, false);
            if (bad != null) {
                pathViolations.add(bad);
            } else {
                permitWebSockets.add(path);
            }
            return this;
        }

        /** The mount path of the account endpoints; {@code /auth} when not called. Admin endpoints go below it. */
        // @req FR-AUTH-005
        public Builder mountPath(String path) {
            Objects.requireNonNull(path, "path");
            String bad = pathProblem(".mountPath", path, false);
            if (bad == null && widePath(path) == null) {
                bad = ".mountPath must not hold a . or .. segment or an invalid %XX escape: " + quote(path);
            }
            if (bad != null) {
                pathViolations.add(bad);
            } else {
                mountPath = trimSlash(path);
            }
            return this;
        }

        /**
         * The API key scheme of the {@code Authorization} header, compared ignoring case: {@code Authorization:
         * <scheme> nk_...}; {@code ApiKey} when not called. Exactly one RFC 9110 token, not {@code Bearer},
         * {@code Basic}, {@code Digest}, {@code DPoP} or {@code Negotiate} ({@link #build()} refuses anything else).
         * The 401 challenge then names it: {@code WWW-Authenticate: Bearer, <scheme>}.
         */
        // @req FR-AUTH-001
        // @req FR-APIKEY-001
        // @req CON-APIKEY-001
        public Builder apiKeyScheme(String scheme) {
            this.apiKeyScheme = Objects.requireNonNull(scheme, "scheme");
            return this;
        }

        /** The most live API keys one user may hold (at least 1); 20 when not called. */
        // @req FR-APIKEY-001
        public Builder maxKeysPerUser(int max) {
            if (max < 1) {
                configViolations.add(".maxKeysPerUser(" + max + ") needs at least 1");
            } else {
                this.maxKeysPerUser = max;
            }
            return this;
        }

        /**
         * The longest lifetime, in days, of a key issued through the key endpoints (at least 1); 365 when not called.
         * {@code auth.apiKeys().issue} is not limited by it and is the only way to issue a key that never expires.
         */
        // @req FR-APIKEY-001
        public Builder maxApiKeyDays(int days) {
            if (days < 1) {
                configViolations.add(".maxApiKeyDays(" + days + ") needs at least 1 day");
            } else {
                this.maxApiKeyDays = days;
            }
            return this;
        }

        /**
         * True lets API keys carry the admin scopes ({@code auth-users:*}, {@code auth-groups:*},
         * {@code auth-apikeys:*}); false (the default) refuses them at issuance and ignores them on a key. A key scope
         * {@code *} never covers an {@code auth-*} scope, with or without this option, and no key covers a required
         * {@code *}. Lasting effects: role grants and reactivations made by a key persist after the key is deleted;
         * review audit events by apiKeyId. The doctor WARNs about this at every start while it is on.
         */
        // @req FR-APIKEY-001
        // @req SEC-APIKEY-009
        // @req OPS-APIKEY-001
        public Builder allowAdminScopesOnApiKeys(boolean allow) {
            this.allowAdminScopesOnApiKeys = allow;
            return this;
        }

        /**
         * The per-IP limit of failed API key authentications: {@code max} failures within {@code windowMillis};
         * 100 per 15 minutes (900,000 ms) when not called.
         */
        // @req FR-APIKEY-001
        public Builder apiKeyFailureLimit(int max, long windowMillis) {
            if (max < 1 || windowMillis < 1000) {
                configViolations.add(".apiKeyFailureLimit(" + max + ", " + windowMillis + ") needs at least 1 failure "
                        + "and a window of at least one second");
            } else {
                this.apiKeyFailureMax = max;
                this.apiKeyFailureWindowMillis = windowMillis;
            }
            return this;
        }

        /**
         * The password-login limits, each counted over the normalized login name within {@code windowMillis}:
         * {@code perUserAndIp} failures of one name from one IP, {@code perIp} failures from one IP (requests with a
         * trusted-device token are exempt) and {@code perUser} failures of one name (only requests without a trusted
         * device). Over a limit the login answers 429 {@code too_many_attempts} with {@code Retry-After}. 5, 20 and 10
         * per 15 minutes (900,000 ms) when not called.
         */
        // @req SEC-FLOW-011
        public Builder loginFailureLimits(int perUserAndIp, int perIp, int perUser, long windowMillis) {
            if (perUserAndIp < 1 || perIp < 1 || perUser < 1 || windowMillis < 1000) {
                configViolations.add(".loginFailureLimits(" + perUserAndIp + ", " + perIp + ", " + perUser + ", "
                        + windowMillis + ") needs at least 1 failure per limit and a window of at least one second");
            } else {
                this.loginUserIpMax = perUserAndIp;
                this.loginIpMax = perIp;
                this.loginUserMax = perUser;
                this.loginWindowMillis = windowMillis;
            }
            return this;
        }

        /**
         * True serves {@code POST /auth/register} (self-registration with a username and password, answering 201
         * with tokens); false (the default) does not register the route.
         */
        // @req FR-FLOW-007
        public Builder register(boolean enabled) {
            this.register = enabled;
            return this;
        }

        /**
         * The user attributes a user may change on itself with {@code PATCH /auth/me}
         * ({@code {"attributes": {"displayName": "Al"}}}); none when not called, so {@code PATCH /auth/me} then
         * refuses every attribute with 400. Repeated calls add names. The security field names {@code passwordHash},
         * {@code ver}, {@code enabled} and {@code roles} are refused. An app that logs users in by an attribute (an
         * e-mail address) must not list it unless it normalizes it and keeps it unique itself.
         */
        // @req FR-FLOW-006
        public Builder allowedAttributes(String... names) {
            Objects.requireNonNull(names, "names");
            for (String n : names) {
                Objects.requireNonNull(n, "attribute name");
                if (n.trim().isEmpty() || RESERVED_ATTRIBUTES.contains(n)) {
                    throw new IllegalArgumentException("not a user attribute that PATCH /auth/me may change: " + n);
                }
                allowedAttributes.add(n);
            }
            return this;
        }

        /** The rule every new password must meet; {@code PasswordPolicy.of(profile)} when not called. */
        // @req FR-TOKEN-004
        public Builder passwordPolicy(PasswordPolicy policy) {
            this.passwordPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /** The check of new passwords against known breaches; none when not called. */
        // @req FR-TOKEN-004
        public Builder breachedPasswordChecker(BreachedPasswordChecker checker) {
            this.breachedPasswords = Objects.requireNonNull(checker, "checker");
            return this;
        }

        /**
         * Replaces the built-in password check of {@code POST /auth/login} (the user store and the password hasher)
         * with {@code authenticator}; the login limits, the device trust and the failure floor stay as they are.
         */
        // @req IR-TOKEN-001
        public Builder authenticator(Authenticator authenticator) {
            this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
            return this;
        }

        /**
         * Origins besides the request's own that may receive auth cookies and use them, for example an admin SPA on
         * another host: {@code .trustedOrigins("https://admin.example.com")}. Each is an absolute
         * {@code http(s)://host[:port]} origin. Only {@code COOKIE} and {@code BOTH} transports read it.
         */
        // @req SEC-FLOW-007
        public Builder trustedOrigins(String... origins) {
            for (String o : origins) {
                Objects.requireNonNull(o, "origin");
                String t = o.trim();
                String norm = com.snoworca.naruserve.utils.auth.internal.Origins.of(t);
                int authority = t.indexOf("://");
                String rest = authority < 0 ? "" : t.substring(authority + 3);
                int slash = rest.indexOf('/');
                boolean pathless = slash < 0 || slash == rest.length() - 1;
                if (norm == null || !pathless || rest.indexOf('?') >= 0 || rest.indexOf('#') >= 0) {
                    configViolations.add(".trustedOrigins needs absolute http(s) origins such as "
                            + "https://app.example.com, without a path: " + quote(o));
                } else {
                    trustedOrigins.add(norm);
                }
            }
            return this;
        }

        /**
         * {@link #recovery(boolean)} from the environment variable {@code NARU_AUTH_RECOVERY}: {@code 1} or
         * {@code true} turns recovery on; anything else, or no variable, leaves the current setting. The only auth
         * method besides {@code AuthMode.fromEnv()} and {@code KeySources.fromEnv(...)} that reads the environment,
         * and only when the app calls it.
         */
        // @req SEC-AUTH-003
        public Builder recoveryFromEnv() {
            return recoveryFromEnvValue(System.getenv(RECOVERY_ENV));
        }

        /** {@link #recoveryFromEnv()} for one value of the variable; the testable half. */
        Builder recoveryFromEnvValue(@Nullable String value) {
            if (value != null && (value.trim().equals("1") || value.trim().equalsIgnoreCase("true"))) {
                this.recovery = true;
            }
            return this;
        }

        /**
         * True: unguarded routes see a minimal principal (only {@code getId()}) without a store read; guards and
         * {@code auth.loadUser(req)} load the user. Default false: every protected request loads the user.
         */
        // @req IR-AUTH-004
        public Builder statelessAccess(boolean stateless) {
            this.statelessAccess = stateless;
            return this;
        }

        /**
         * How long a request may reuse the user, roles and scopes another request of the same user loaded, in
         * milliseconds; 5,000 when not called, 0 reads the stores on every request. A change made through auth is
         * seen at once on this node; one written straight to the store (or on another node) after at most this long.
         */
        // @req SEC-AUTH-007
        public Builder userCacheTtl(long millis) {
            if (millis < 0) {
                configViolations.add(".userCacheTtl(" + millis + ") must not be negative");
            } else {
                this.userCacheTtl = millis;
            }
            return this;
        }

        /**
         * The access token lifetime in milliseconds; when not called, the profile's: BASELINE and STANDARD 15
         * minutes, STRICT 5 minutes.
         */
        // @req FR-TOKEN-001
        public Builder accessTtl(long millis) {
            if (millis < 1000) {
                configViolations.add(".accessTtl(" + millis + ") is below one second; the lifetime is in "
                        + "milliseconds (15 minutes is 900000)"); // OPS-AUTH-025 AC-6
            } else {
                this.accessTtl = millis;
            }
            return this;
        }

        /**
         * The refresh token lifetime in milliseconds; when not called, the profile's: BASELINE 14 days, STANDARD 1
         * day, STRICT 12 hours.
         */
        // @req FR-TOKEN-001
        public Builder refreshTtl(long millis) {
            if (millis < 1000) {
                configViolations.add(".refreshTtl(" + millis + ") is below one second; the lifetime is in "
                        + "milliseconds (15 minutes is 900000)"); // OPS-AUTH-025 AC-6
            } else {
                this.refreshTtl = millis;
            }
            return this;
        }

        /**
         * The format of the access and refresh tokens; the built-in HS256 codec over the key source when not called
         * (header {@code kid}, {@code typ} {@code at+jwt} / {@code rt+jwt}, purpose-derived keys).
         */
        // @req IR-TOKEN-002
        public Builder tokenCodec(TokenCodec codec) {
            this.tokenCodec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * The counter store of the password-login limits; when not called it follows the user store
         * ({@code <name>-login-throttle.json} beside a {@link FileUserStore}'s file, written as a snapshot every 60
         * seconds, otherwise in memory). MULTI_INSTANCE needs an app-provided one shared by every node.
         */
        // @req FR-IAM-013
        public Builder loginThrottle(LoginThrottle throttle) {
            this.loginThrottle = Objects.requireNonNull(throttle, "throttle");
            return this;
        }

        /**
         * The trusted-device state (the per-device-token failure limit); when not called it follows the user store
         * ({@code <name>-device-trust.json} beside a {@link FileUserStore}'s file, where an invalidation is written at
         * once, otherwise in memory). MULTI_INSTANCE needs an app-provided one shared by every node.
         */
        // @req SEC-FLOW-013
        public Builder deviceTrust(DeviceTrust trust) {
            this.deviceTrust = Objects.requireNonNull(trust, "trust");
            return this;
        }

        /**
         * How long after its first use a refresh token may arrive again without counting as reuse, in milliseconds;
         * 0 (the default) makes every second use a reuse that revokes the family. A repeat inside the window is
         * refused with {@code invalid_refresh_token} but revokes nothing (for example two tabs refreshing at once).
         */
        // @req SEC-TOKEN-006
        public Builder reuseGrace(long millis) {
            if (millis < 0) {
                configViolations.add(".reuseGrace(" + millis + ") is negative");
            } else {
                this.reuseGraceMillis = millis;
            }
            return this;
        }

        /** The optional hard lockout of password login, for example {@code Lockout.after(5).forMinutes(15)}. */
        // @req FR-FLOW-009
        public Builder lockout(Lockout lockout) {
            this.lockout = Objects.requireNonNull(lockout, "lockout");
            return this;
        }

        /** The clock of every auth timestamp; {@link TimeSource#system()} when not called. */
        // @req IR-AUTH-001
        public Builder timeSource(TimeSource time) {
            this.time = Objects.requireNonNull(time, "time");
            return this;
        }

        /** Where auth logs; the app's logger from {@code install} on when not called. */
        public Builder logger(Logger logger) {
            this.logger = Objects.requireNonNull(logger, "logger");
            return this;
        }

        /** The writer of auth error bodies; the Core JSON shape when not called. */
        // @req FR-AUTH-006
        public Builder responses(AuthResponses responses) {
            this.responses = Objects.requireNonNull(responses, "responses");
            return this;
        }

        /**
         * Applies the operator values of {@code config} as if their setters were called here: later setters win.
         * Its problems (unknown keys, unresolved secret references) are reported by {@link #build()}.
         */
        // @req FR-AUTH-002
        public Builder config(AuthConfig config) {
            configViolations.addAll(config.applyTo(this));
            return this;
        }

        void keys(KeySource keys) {
            this.keys = keys;
        }

        /** The test seam for the credential verifiers; package-private. */
        Builder credentials(CredentialResolver resolver) {
            this.credentials = resolver;
            return this;
        }

        /** The test seam for the access-token verifier behind the transport reading; package-private. */
        Builder tokenVerifier(TokenVerifier verifier) {
            this.tokenVerifier = verifier;
            return this;
        }

        /** {@code hashCost(HashCost.fixed(iterations))}; package-private, for fast tests. */
        Builder hashIterations(int iterations) {
            hashCost(HashCost.fixed(iterations));
            testHashCost = true; // a test cost below the production floor, exempt from the hash-cost item
            return this;
        }

        /**
         * The cost of new PBKDF2 hashes and the login timing base; {@code HashCost.defaults()} (calibrated to 500 ms)
         * when not called. A calibration target outside 250..500 ms is refused by {@link #build()}.
         */
        // @req PERF-TOKEN-001
        public Builder hashCost(HashCost cost) {
            this.hashCost = Objects.requireNonNull(cost, "cost");
            this.testHashCost = false;
            return this;
        }

        /** How many password hashes run at once; {@code max(1, cores / 2)} when not called. */
        // @req REL-TOKEN-001
        public Builder hashConcurrency(int slots) {
            this.hashConcurrency = slots;
            if (slots < 1) {
                configViolations.add(".hashConcurrency(" + slots + ") needs at least 1 slot");
            }
            return this;
        }

        /**
         * The hasher of new and stored passwords; a {@code Pbkdf2PasswordHasher} at the {@link #hashCost} when not
         * called. Every hash and verify of it runs inside the hash concurrency limit.
         */
        // @req SEC-TOKEN-007
        public Builder passwordHasher(PasswordHasher hasher) {
            this.passwordHasher = Objects.requireNonNull(hasher, "hasher");
            return this;
        }

        /**
         * An account to create on start when no account of that name exists; an existing account (and its password)
         * is never changed. The password is hashed once, in {@link #build()}. A null password is allowed for at most
         * one {@code .user} holding an admin role (the built-in {@code admin} or a {@code RolePolicy} role granting
         * {@code *}), checked on start for a missing account: DEV then logs a generated temporary password, PROD needs
         * {@link #recovery(boolean)} and logs a one-time setup token, otherwise the start fails. Typical use:
         * {@code .user("admin", System.getenv("NARU_ADMIN_PASSWORD"), "admin")}.
         */
        // @req FR-AUTH-004
        public Builder user(String username, @Nullable String password, String... roles) {
            Objects.requireNonNull(username, "username");
            String[] entry = new String[2 + roles.length];
            entry[0] = username;
            entry[1] = password;
            for (int i = 0; i < roles.length; i++) {
                entry[2 + i] = Objects.requireNonNull(roles[i], "role");
            }
            userSeeds.add(entry);
            return this;
        }

        /**
         * Opens account recovery for this start: a missing null-password admin {@code .user} gets a one-time setup
         * token in the log instead of a failed start. Default false.
         */
        // @req FR-AUTH-004
        public Builder recovery(boolean recovery) {
            this.recovery = recovery;
            return this;
        }

        /**
         * Turns the PROD secure-transport check off for a deployment without TLS yet (an Android device server before
         * its TLS adapter); {@code reason} is logged on every start. Prefer TLS: without it tokens travel in clear text.
         * It forces short token lifetimes, overriding {@code .accessTtl}, {@code .refreshTtl} and the profile: access
         * tokens live 5 minutes and refresh tokens 24 hours. Auth cookies sent over plain text lack Secure, which is
         * logged as a WARN.
         */
        // @req SEC-AUTH-005
        // @req SEC-FLOW-002
        public Builder allowInsecureTransport(String reason) {
            Objects.requireNonNull(reason, "reason");
            if (reason.trim().isEmpty()) {
                configViolations.add(".allowInsecureTransport(reason) needs a reason, logged on every start");
            }
            this.insecureReason = reason;
            return this;
        }

        /**
         * Allows path middleware registered before {@code auth.install(app)} at exactly these paths; any other path
         * middleware before install fails the start, because it runs before authentication.
         */
        // @req SEC-AUTH-004
        public Builder allowPreAuthMiddleware(String... paths) {
            for (String p : paths) {
                preAuthAllowed.add(Objects.requireNonNull(p, "path"));
            }
            return this;
        }

        private static String trimSlash(String p) {
            return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        }

        /** The violation of a configured path, or null. */
        private static @Nullable String pathProblem(String setter, String path, boolean prefix) {
            if (path.isEmpty()) {
                return setter + "(\"\") is not a path; paths start with /";
            }
            for (int i = 0; i < path.length(); i++) {
                char c = path.charAt(i);
                if (c < 0x21 || c > 0x7e) {
                    return setter + " paths must be ASCII without spaces (write non-ASCII as %XX): " + quote(path);
                }
            }
            if (path.charAt(0) != '/') {
                return setter + " paths start with /: " + quote(path);
            }
            if (prefix && path.equals("/")) {
                return ".permitPrefix(\"/\") would make every path public; list the public paths instead";
            }
            if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) { // OPS-AUTH-024 AC-2
                return setter + " paths have no query or fragment: " + quote(path) + "; a permit matches the path "
                        + "alone, whatever the query";
            }
            if (path.indexOf('*') >= 0 || path.contains("/:")) { // OPS-AUTH-021 AC-3
                int cut = path.length();
                int star = path.indexOf('*');
                int colon = path.indexOf("/:");
                cut = Math.min(star < 0 ? cut : star, colon < 0 ? cut : colon);
                String base = path.substring(0, cut);
                while (base.length() > 1 && base.endsWith("/")) {
                    base = base.substring(0, base.length() - 1);
                }
                return setter + " takes a literal path, not a route pattern: " + quote(path) + "; write each "
                        + "public path, or use .permitPrefix(\"" + (base.isEmpty() ? "/users" : base) + "\") for "
                        + "everything below a prefix"; // OPS-AUTH-024 AC-3
            }
            return null;
        }

        private static String quote(String s) {
            StringBuilder b = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                b.append(c >= 0x20 && c < 0x7f ? String.valueOf(c) : String.format("\\u%04x", (int) c));
            }
            return b.append('"').toString();
        }

        /**
         * The {@link Auth}, after checking every rule; all violations are reported at once in one
         * {@link AuthConfigException}. Each call returns a new, independent instance.
         */
        // @req FR-AUTH-001
        // @req SEC-AUTH-001
        private boolean configSecretRefused() {
            for (String c : configViolations) {
                if (c.contains("naru.auth.secret must be a reference")) {
                    return true;
                }
            }
            return false;
        }

        public Auth build() {
            ComponentLogger log = new ComponentLogger(logger != null ? logger : new ConsoleLogger());
            if (secretWhitespace) {
                log.warn("auth: the secret passed to Auth.builder(...) has leading or trailing whitespace (a newline "
                        + "from a file or an env file?); it is used as given, so a node that trims it signs with "
                        + "another key. Trim it once where the app reads it");
            }
            if (rolesCalls > 1) { // OPS-AUTH-021 AC-14
                log.warn("auth: .roles(...) was called " + rolesCalls + " times; the last RoleStore replaces the "
                        + "earlier ones, so only its roles exist. Build one RolePolicy with every role");
            }
            List<String> v = new ArrayList<>();
            List<String[]> unkeyed = unkeyedFileStores();
            boolean plainKeyFile = false;
            if (mode == AuthMode.PROD && !unkeyed.isEmpty()) {
                v.add(prodFileKeyViolation(unkeyed));
                plainKeyFile = keys instanceof KeySources.FileKey && ((KeySources.FileKey) keys).fileKey() == null;
            }
            // FR-IAM-015 AC-3: PROD does not read (and so never creates) a key file it would keep unencrypted
            JwtKeys key = plainKeyFile ? null : keySet(v, log);
            v.addAll(pathViolations);
            if (mountPath.equals("/") && transport != TokenTransport.HEADER) {
                v.add(".mountPath(\"/\") with transport " + transport + " would scope the refresh cookie to every "
                        + "path; mount the auth endpoints below a prefix such as /auth");
            }
            String scheme = schemeProblem(apiKeyScheme);
            if (scheme != null) {
                v.add(scheme);
            }
            if (deployment == Deployment.MULTI_INSTANCE) {
                multiInstanceProblems(v);
            }
            if (!hashCost.isFixed() && (hashCost.getTargetMillis() < HashCost.MIN_TARGET_MILLIS
                    || hashCost.getTargetMillis() > HashCost.MAX_TARGET_MILLIS)) {
                v.add(".hashCost(" + hashCost + ") is outside " + HashCost.MIN_TARGET_MILLIS + ".."
                        + HashCost.MAX_TARGET_MILLIS + " ms; a calibration target must lie in that range");
            }
            List<String> seedNames = new ArrayList<>();
            for (String[] e : userSeeds) {
                try {
                    String n = Usernames.normalize(e[0]);
                    if (seedNames.contains(n)) {
                        v.add(".user(\"" + n + "\") is given twice");
                    }
                    seedNames.add(n);
                } catch (IllegalArgumentException bad) {
                    v.add(".user(...) has an invalid username: " + bad.getMessage());
                }
            }
            seedPasswordPolicy(v, log);
            v.addAll(configViolations);
            RefreshTokenStore refresh = refreshStore(v, log);
            LoginThrottle fileThrottle = fileThrottle(v, log);
            DeviceTrust fileTrust = fileDeviceTrust(v, log);
            if (!v.isEmpty() || key == null || refresh == null) {
                throw new AuthConfigException(v);
            }
            int slots = hashConcurrency > 0 ? hashConcurrency
                    : HashSlots.defaultSlots(Runtime.getRuntime().availableProcessors());
            Passwords passwords = new Passwords(passwordHasher, hashCost, new HashSlots(slots));
            List<Seed> seeds = new ArrayList<>();
            for (String[] e : userSeeds) {
                String pw = e[1];
                seeds.add(new Seed(Usernames.normalize(e[0]), pw == null ? null : passwords.hash(pw.toCharArray()),
                        Collections.unmodifiableList(new ArrayList<>(Arrays.asList(e).subList(2, e.length)))));
            }
            if (mode == AuthMode.DEV && !unkeyed.isEmpty()) {
                log.warn(devFileKeyWarning(unkeyed));
            }
            Auth made = new Auth(this, key, passwords, seeds, log, refresh, fileThrottle, fileTrust);
            UserStore u = users;
            boolean sharedRefresh = refreshTokens == null && refresh instanceof FileRefreshTokenStore;
            if (u instanceof FileUserStore && (sharedRefresh || fileThrottle != null || fileTrust != null)) {
                SharedTargets shared = SharedTargets.of((FileUserStore) u, log, time);
                shared.repoint(log, time); // M2-F19, R2-M1-F08: the shared defaults follow the Auth built last
                made.sharedTargets = shared;
            }
            return made;
        }

        /**
         * FR-AUTH-004 AC-7: a non-null {@code .user} password meets the password policy like any new password; PROD
         * refuses it, DEV warns once per {@code .user}. The text names the user and the rule, never the password.
         */
        // @req FR-AUTH-004
        private void seedPasswordPolicy(List<String> v, ComponentLogger log) {
            PasswordPolicy policy = passwordPolicy != null ? passwordPolicy : PasswordPolicy.of(profile);
            for (String[] e : userSeeds) {
                if (e[1] == null) {
                    continue;
                }
                char[] pw = e[1].toCharArray();
                String problem = policy.violation(pw);
                java.util.Arrays.fill(pw, '\0');
                if (problem == null) {
                    continue;
                }
                String text = ".user(\"" + e[0] + "\") password: " + problem;
                if (mode == AuthMode.DEV) {
                    log.warn("auth: " + text + "; DEV creates the account anyway, PROD refuses it");
                } else {
                    v.add(text);
                }
            }
        }

        /**
         * FR-IAM-015: the shipped file stores of this build that would write secrets without an AuthFileKey, each as
         * {option, file}: a FileUserStore made without a key and a {@code KeySources.fromFile} source without one.
         */
        // @req FR-IAM-015
        private List<String[]> unkeyedFileStores() {
            List<String[]> out = new ArrayList<>();
            FileUserStore u = fileUserStoreIn(users); // a FileUserStore inside decorators too (R2-M1-F09)
            if (u != null && com.snoworca.naruserve.utils.auth.internal.FileStoreKeys.keyOf(u) == null) {
                out.add(new String[] {"new FileUserStore(file, AuthFileKey)", u.getFile().toString()});
            }
            KeySource k = keys;
            if (k instanceof KeySources.FileKey && ((KeySources.FileKey) k).fileKey() == null) {
                out.add(new String[] {"KeySources.fromFile(file, AuthFileKey)",
                        ((KeySources.FileKey) k).file().toString()});
            }
            return out;
        }

        /** FR-IAM-015 AC-3: the PROD refusal naming each option without a key and the environment variable. */
        private static String prodFileKeyViolation(List<String[]> unkeyed) {
            StringBuilder b = new StringBuilder("PROD keeps no secret unencrypted in an auth file: pass an AuthFileKey "
                    + "to ");
            for (int i = 0; i < unkeyed.size(); i++) {
                b.append(i == 0 ? "" : " and to ").append(unkeyed.get(i)[0]).append(" for ").append(unkeyed.get(i)[1]);
            }
            return b.append("; AuthFileKey.fromEnv() reads it from ").append(AuthFileKey.ENV_VARIABLE)
                    .append(" (32 random bytes as base64, for example openssl rand -base64 32); AuthMode.DEV runs "
                            + "without one and writes these secrets plain").toString();
        }

        /** FR-IAM-015 AC-4: the one DEV WARN of a build naming each unencrypted file and the environment variable. */
        private static String devFileKeyWarning(List<String[]> unkeyed) {
            StringBuilder files = new StringBuilder();
            StringBuilder options = new StringBuilder();
            for (int i = 0; i < unkeyed.size(); i++) {
                files.append(i == 0 ? "" : " and ").append(unkeyed.get(i)[1]);
                options.append(i == 0 ? "" : " and ").append(unkeyed.get(i)[0]);
            }
            return "auth: DEV writes the secrets of " + files + " unencrypted (password hashes as plain hash strings, "
                    + "the signing key as raw bytes); set " + AuthFileKey.ENV_VARIABLE + " and pass "
                    + "AuthFileKey.fromEnv() to " + options + " to store them as enc(...)";
        }

        /**
         * R42: the configured refresh store, else the default that follows the user store; null after a violation
         * (a refresh file beside a FileUserStore that cannot be read).
         */
        // @req FR-TOKEN-002
        private @Nullable RefreshTokenStore refreshStore(List<String> v, Logger log) {
            RefreshTokenStore configured = refreshTokens;
            if (configured != null) {
                return configured;
            }
            UserStore u = users;
            if (u instanceof FileUserStore) {
                FileUserStore fu = (FileUserStore) u;
                synchronized (FILE_REFRESH_STORES) {
                    FileRefreshTokenStore known = FILE_REFRESH_STORES.get(fu);
                    if (known != null) {
                        return known; // one store per user store: every Auth over it shares one lock and cache
                    }
                    File f = refreshFileOf(fu.getFile());
                    try {
                        FileRefreshTokenStore made = new FileRefreshTokenStore(f,
                                com.snoworca.naruserve.utils.auth.internal.MiniJson.DEFAULT_FILE_BYTES,
                                SharedTargets.of(fu, log, time).log,
                                com.snoworca.naruserve.utils.auth.internal.FileStoreKeys.keyOf(fu));
                        FILE_REFRESH_STORES.put(fu, made);
                        return made;
                    } catch (IOException e) {
                        v.add("the refresh token file beside the FileUserStore failed: " + e.getMessage());
                        return null;
                    }
                }
            }
            return new InMemoryRefreshTokenStore();
        }

        /** {@code users.json} -> {@code users-refresh-tokens.json} in the same directory. */
        static File refreshFileOf(File usersFile) {
            return besideUsers(usersFile, "-refresh-tokens.json");
        }

        /** {@code users.json} -> {@code users<suffix>} in the same directory. */
        static File besideUsers(File usersFile, String suffix) {
            String name = usersFile.getName();
            String stem = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
            return new File(usersFile.getAbsoluteFile().getParentFile(), stem + suffix);
        }

        /**
         * R42 (REL-FLOW-001 AC-5): with a FileUserStore and no {@code .loginThrottle(..)}, the snapshot-file throttle
         * beside the users file, shared by every Auth over that store; else null (the store-following default). A file
         * that cannot be read is a violation.
         */
        // @req REL-FLOW-001
        private @Nullable LoginThrottle fileThrottle(List<String> v, Logger log) {
            UserStore u = users;
            if (loginThrottle != null || !(u instanceof FileUserStore)) {
                return null;
            }
            FileUserStore fu = (FileUserStore) u;
            synchronized (FILE_THROTTLES) {
                FileLoginThrottle known = FILE_THROTTLES.get(fu);
                if (known != null) {
                    return known;
                }
                try {
                    SharedTargets shared = SharedTargets.of(fu, log, time);
                    FileLoginThrottle made = new FileLoginThrottle(besideUsers(fu.getFile(), "-login-throttle.json"),
                            shared.time, shared.log,
                            com.snoworca.naruserve.utils.auth.internal.FileStoreKeys.keyOf(fu));
                    FILE_THROTTLES.put(fu, made);
                    return made;
                } catch (IOException e) {
                    v.add("the login throttle file beside the FileUserStore failed: " + e.getMessage());
                    return null;
                }
            }
        }

        /** As {@link #fileThrottle} for the device trust ({@code <name>-device-trust.json}). */
        // @req REL-FLOW-001
        private @Nullable DeviceTrust fileDeviceTrust(List<String> v, Logger log) {
            UserStore u = users;
            if (deviceTrust != null || !(u instanceof FileUserStore)) {
                return null;
            }
            FileUserStore fu = (FileUserStore) u;
            synchronized (FILE_DEVICE_TRUSTS) {
                FileDeviceTrust known = FILE_DEVICE_TRUSTS.get(fu);
                if (known != null) {
                    return known;
                }
                try {
                    SharedTargets shared = SharedTargets.of(fu, log, time);
                    FileDeviceTrust made = new FileDeviceTrust(besideUsers(fu.getFile(), "-device-trust.json"),
                            shared.time, shared.log,
                            com.snoworca.naruserve.utils.auth.internal.FileStoreKeys.keyOf(fu));
                    FILE_DEVICE_TRUSTS.put(fu, made);
                    return made;
                } catch (IOException e) {
                    v.add("the device trust file beside the FileUserStore failed: " + e.getMessage());
                    return null;
                }
            }
        }

        /** The key set of the key source, its strength checked key by key; null after a violation was added. */
        // @req SEC-TOKEN-004
        private @Nullable JwtKeys keySet(List<String> v, Logger log) {
            KeySource source = keys;
            String origin = source instanceof KeySources.Fixed ? ((KeySources.Fixed) source).origin()
                    : source instanceof KeySources.FileKey ? ((KeySources.FileKey) source).origin() : "the key source";
            JwtKeys set;
            try {
                if (source instanceof KeySources.Fixed) {
                    byte[] raw = ((KeySources.Fixed) source).read();
                    set = raw == null || raw.length == 0 ? null : JwtKeys.of(raw);
                } else if (source instanceof KeySources.FileKey) {
                    set = JwtKeys.of(((KeySources.FileKey) source).signingKey(log)); // its WARNs go to auth's log
                } else {
                    set = source.keys();
                }
            } catch (Exception e) {
                // only the shipped file source's messages are known to hold no key material
                String why = source instanceof KeySources.FileKey && e.getMessage() != null
                        ? e.getClass().getSimpleName() + ": " + e.getMessage() : e.getClass().getSimpleName();
                v.add(origin + " failed to provide a signing key (" + why + ")");
                return null;
            }
            if (set == null) {
                if (mode == AuthMode.DEV) {
                    byte[] temp = new byte[32];
                    new SecureRandom().nextBytes(temp);
                    log.warn("auth: " + origin + " is not set; DEV uses a temporary signing key, and restarting the "
                            + "app invalidates all tokens");
                    temporaryKey = true;
                    return JwtKeys.of(temp);
                }
                if (origin.equals("the secret") && configSecretRefused()) {
                    return null; // OPS-AUTH-021 AC-12: the config's own violation names the secret problem
                }
                String what = origin.equals("the secret") // OPS-AUTH-018 AC-6: a mistyped variable name is not hidden
                        ? "the secret passed to Auth.builder(...) is null or empty (JWT_SECRET is not set, or the app read "
                        + "another variable)" : origin + " is not set";
                v.add(what + ". " + SecretRules.HOW_TO);
                return null;
            }
            boolean ok = true;
            for (String kid : set.getKids()) {
                byte[] k = set.verificationKey(kid);
                String weak = k == null ? "key " + kid + " is missing" : SecretRules.weakness(k);
                if (weak == null) {
                    continue;
                }
                if (!kid.equals(set.getSigningKid())) {
                    weak = "verification key " + kid + ": " + weak;
                }
                if (mode == AuthMode.DEV) {
                    log.warn("auth: " + weak + " (a WARN in DEV, refused in PROD)");
                } else {
                    v.add(weak);
                    ok = false;
                }
            }
            return ok ? set : null;
        }

        private static @Nullable String schemeProblem(String scheme) {
            if (scheme.isEmpty()) {
                return ".apiKeyScheme(\"\") is not a scheme name";
            }
            for (int i = 0; i < scheme.length(); i++) {
                char c = scheme.charAt(i);
                boolean token = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                        || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
                if (!token) {
                    return ".apiKeyScheme must be an HTTP token (letters, digits, !#$%&'*+-.^_`|~)";
                }
            }
            for (String reserved : new String[] {"Bearer", "Basic", "Digest", "DPoP", "Negotiate"}) {
                if (reserved.equalsIgnoreCase(scheme)) {
                    return ".apiKeyScheme(\"" + scheme + "\") is reserved by another HTTP authentication scheme";
                }
            }
            return null;
        }

        private void multiInstanceProblems(List<String> v) {
            List<String> local = new ArrayList<>();
            UserStore u = users;
            if (u == null) {
                local.add("the default InMemoryUserStore (user store)");
            } else if (isNotShared(u)) {
                local.add(u.getClass().getSimpleName() + " (user store)");
            }
            GroupStore g = groups;
            if (g instanceof InMemoryGroupStore || g instanceof FileGroupStore) {
                local.add(g.getClass().getSimpleName() + " (group store)");
            }
            RefreshTokenStore r = refreshTokens;
            List<String> unset = new ArrayList<>();
            if (r == null) {
                unset.add("refresh token store");
            } else if (r instanceof InMemoryRefreshTokenStore || r instanceof FileRefreshTokenStore) {
                local.add(r.getClass().getSimpleName() + " (refresh token store)");
            }
            LoginThrottle t = loginThrottle;
            if (t == null) {
                unset.add("login throttle");
            } else if (t instanceof InMemoryLoginThrottle) {
                local.add(t.getClass().getSimpleName() + " (login throttle)");
            }
            DeviceTrust dt = deviceTrust;
            if (dt == null) {
                unset.add("device trust store");
            } else if (dt instanceof InMemoryDeviceTrust) {
                local.add(dt.getClass().getSimpleName() + " (device trust)");
            }
            if (apiKeys instanceof com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore) {
                local.add(apiKeys.getClass().getSimpleName() + " (API key store)");
            }
            if (!unset.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < unset.size(); i++) {
                    names.append(i == 0 ? "" : i == unset.size() - 1 ? " and " : ", ").append(unset.get(i));
                }
                local.add("the in-memory defaults of the unset " + names + " (set them to app-provided stores "
                        + "over the shared database)");
            }
            if (local.isEmpty()) {
                return;
            }
            v.add("deployment(MULTI_INSTANCE) needs stores shared by every node; these are in memory or in a local "
                    + "file: " + joined(local));
        }

        private static String joined(List<String> parts) {
            StringBuilder b = new StringBuilder();
            for (String p : parts) {
                b.append(b.length() == 0 ? "" : ", ").append(p);
            }
            return b.toString();
        }

        private static boolean isNotShared(UserStore u) {
            return u instanceof InMemoryUserStore
                    || u.getClass().getName().equals("com.snoworca.naruserve.utils.auth.user.FileUserStore");
        }

        @Override
        public String toString() {
            return "Auth.Builder{mode=" + mode + ", profile=" + profile + ", deployment=" + deployment + ", transport="
                    + transport + ", mount=" + mountPath + ", keys=" + keys.getClass().getSimpleName() + "}";
        }
    }

    /** The default method set of {@code .permit(path)}. */
    private static final class EnumSetOf {
        private EnumSetOf() {
        }

        static Set<Method> getHead() {
            return Collections.unmodifiableSet(java.util.EnumSet.of(Method.GET, Method.HEAD));
        }
    }
}
