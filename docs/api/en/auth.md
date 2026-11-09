# Auth module (utils.auth)

[한국어](../auth.md) | [API index](README.md)

> **Status:** the auth/RBAC v1 contract of 0.11.0. One `app.use(auth)` line attaches the built-in login, refresh, logout, password, account and admin endpoints, the guards, groups and roles, and API key authentication. Every name and behaviour on this page is final. **Trying the examples:** the code fragments on this page read `JWT_SECRET` and build in PROD, the default mode, which refuses a missing or weak secret. Set `JWT_SECRET` to an `openssl rand -base64 32` value, or add `.mode(AuthMode.DEV)` to try a fragment locally. The quick start below already runs in DEV.

**Task guide:** find what you want to do and go to its section.

| I want to | Section |
|---|---|
| Log in and get a token | [Quick start](#quick-start), [Account endpoints](#account-endpoints-authroutes) |
| Protect a route (login required, scope checks) | [Guards and the principal](#guards-and-the-principal), [The protected range](#the-protected-range-protectonly-and-upgrades) |
| Define roles and scopes | [Roles and scopes](#roles-and-scopes-rolepolicy-rolestore-scopes) |
| Create users from code | [The app user facade](#the-app-user-facade-authusers), [The first account](#the-first-account-user) |
| React to user changes (keep an app table in sync) | [AuthEvents](#authevents) |
| Give roles through groups | [Groups](#groups-groupstore-and-grouplimits) |
| Keep users in my own database | [Connecting your own database](#connecting-your-own-database) |
| Prepare PROD files and keys | [AuthMode](#authmode), [Configuration file](#configuration-file-authconfig), [Key sets and rotation](#key-sets-and-rotation-keysource-jwtkeys-keysources), [Encrypting file secrets](#encrypting-file-secrets-authfilekey) |
| Call the server from a LAN or a device | [The secure-transport rule](#the-secure-transport-rule), [Plain-text transport](#plain-text-transport-allowinsecuretransport) |
| Write tests | [Test kit](#test-kit-authtesting), [HTTP test recipe](#http-test-recipe) |
| Use the admin API | [Admin endpoints](#admin-endpoints-authadminroutes) |
| Issue and use API keys | [Issuing API keys](#issuing-api-keys-authapikeys), [API key authentication](#api-key-authentication) |
| Look up a term | [Glossary](#glossary) |

Express has no built-in authentication, so apps usually pick middleware such as `passport` or `express-jwt`. NaruServe ships the same job as the package `com.snoworca.naruserve.utils.auth` inside `NaruServe-api`: no new Gradle module and no new dependency. It uses the Core public API only and runs as Java 8 bytecode on Android 5.0 (API 21).

| Package | What it holds (now) |
|---|---|
| `utils` | `TimeSource` |
| `utils.auth` | `Auth`, `Auth.Builder`, `AuthPrincipal`, `AccessRule`, `AuthConfig`, `AuthConfigException`, `AuthStateException`, `AuthProfile`, `Deployment`, `TokenTransport`, `KeySources`, `AuthFileKey`, `InMemoryLoginThrottle`, `InMemoryLoginThrottle.Builder`, `InMemoryRefreshTokenStore`, `FileRefreshTokenStore`, `AuthMode`, `AuthRejection`, `AuthUnavailableException`, `AuthErrorCode`, `AuthEvents`, `AuthEventContext`, `UserOrigin`, `UserChange`, `TokenService`, `TokenPair`, `Lockout`, `Lockout.After`, `PasswordPolicy`. Package-private, made by auth beside a `FileUserStore` (no public constructor; see [File defaults](#login-throttling-store-loginthrottle)): `FileLoginThrottle`, `FileDeviceTrust` |
| `utils.auth.jwt` | `Jwt`, `Jwt.Builder`, `JwtClaims`, `JwtClaims.Builder`, `JwtException`, `JwtException.Reason`, `JwtKeys`, `JwtKeys.Builder` |
| `utils.auth.password` | `PasswordHasher`, `Pbkdf2PasswordHasher`, `BcryptPasswordHasher`, `BcryptPasswordHasher.Builder`, `PasswordHashers`, `HashCost`, `DigestEncoding`, `SaltOrder` |
| `utils.auth.user` | `AuthUser`, `AuthUser.Builder`, `AuthIds`, `UserStore`, `UserPage`, `ForwardingUserStore`, `InMemoryUserStore`, `FileUserStore`, `DuplicateUserException`, `AuthUsers`, `UserMutator` |
| `utils.auth.group` | `GroupStore`, `GroupAdminStore`, `InMemoryGroupStore`, `FileGroupStore`, `GroupLimits` |
| `utils.auth.rbac` | `RolePolicy`, `RolePolicy.Builder`, `RoleStore`, `Scopes` |
| `utils.auth.spi` | `KeySource`, `AuthResponses`, `LoginThrottle`, `ForwardingLoginThrottle`, `RefreshTokenStore`, `RefreshRecord`, `RefreshRecord.Builder`, `TokenCodec`, `DeviceTrust`, `DeviceToken`, `Authenticator`, `BreachedPasswordChecker` |
| `utils.auth.audit` | `AuditEvent`, `AuditEvent.Builder`, `AuditEvent.Outcome`, `AuditSink`, `AuditSinks` |
| `utils.auth.apikey` | `ApiKeyStore`, `StoredApiKey`, `StoredApiKey.Builder`, `ApiKey`, `IssuedApiKey`, `ApiKeyStatus`, `ApiKeyPrefixCollisionException`, `AuthApiKeys`, `InMemoryApiKeyStore` |
| `utils.auth.testing` | `AuthTesting` |
| `utils.auth.internal` | auth-private helpers; not part of the API contract and may change in any release |

## Quick start

Express equivalent: `app.use(cors())` → `app.use(expressjwt({ secret, algorithms: ['HS256'] }).unless({ path: ['/health'] }))` → `expressJwtAuthz(['posts:write'])` on the route → the error handler last.

A complete program that runs as it is (dependency `com.snoworca.naruserve:naruserve`, see [Install](../../../README.md#install)).

```java
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.AuthMode;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;

public class AuthQuickStart {
    public static void main(String[] args) {
        Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
            .mode(AuthMode.DEV)                                   // local development: a temporary key when no secret is set; see deployment below
            .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
            .user("alice", "alice-password-1", "editor")          // created on start when missing (8+ characters: PROD refuses a shorter one, DEV warns)
            .permit("/health")                                    // only GET/HEAD /health is public; everything else needs a login
            .build();

        RestApp.start(8080, app -> {
            app.use(auth);                                        // same as auth.install(app); before any route, else IllegalStateException
            app.get("/health", (req, res) -> res.send("ok"));
            app.get("/me", (req, res) -> res.send(auth.user(req).getUsername()));
            app.post("/posts", auth.require("posts:write"), (req, res) ->
                res.status(201).send("created by " + auth.user(req).getUsername()));
            app.onError(auth.errors());                           // last: auth exceptions become JSON
        });
    }
}
```

```kotlin
import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.utils.auth.Auth
import com.snoworca.naruserve.utils.auth.AuthMode
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy

fun main() {
    val auth = Auth.builder(System.getenv("JWT_SECRET"))
        .mode(AuthMode.DEV)
        .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
        .user("alice", "alice-password-1", "editor")
        .permit("/health")
        .build()

    RestApp.start(8080) { app ->
        app.use(auth)
        app.get("/health") { _, res -> res.send("ok") }
        app.get("/me") { req, res -> res.send(auth.user(req).username) }
        app.post("/posts", auth.require("posts:write")) { req, res ->
            res.status(201).send("created by " + auth.user(req).username)
        }
        app.onError(auth.errors())
    }
}
```

Run it, then try it from another terminal.

```bash
curl http://localhost:8080/health                       # ok
curl -i http://localhost:8080/me                        # 401 unauthenticated
curl -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"alice","password":"alice-password-1"}'
# {"accessToken":"eyJ...","tokenType":"Bearer","expiresIn":900,"refreshToken":"...",...}
curl http://localhost:8080/me -H "Authorization: Bearer eyJ..."      # alice
curl -X POST http://localhost:8080/posts -H "Authorization: Bearer eyJ..."   # 201 created by alice
```

> Login is the built-in `POST /auth/login` (`{"username","password"}` → `accessToken` and `refreshToken`, [Account endpoints](#account-endpoints-authroutes)). A protected request without credentials gets 401 `unauthenticated` (`WWW-Authenticate: Bearer`), and one carrying a token that does not verify gets 401 `invalid_token` (`WWW-Authenticate: Bearer error="invalid_token"`). Send the token as the `Authorization: Bearer <accessToken>` header.

> **A wrong URL is 401 too:** default deny runs before the router, so a path no route serves, such as `POST /login` (the login is `POST /auth/login`) or `GET /auth/login` (login takes POST), gets 401 `unauthenticated` before any 404. A wrong URL therefore looks like a missing login: check the method and path before the token.

> **Public paths:** `.permit("/health")` makes only GET and HEAD of exactly `/health` public; `POST /health`, `/health/` and `/Health` still need a token (the doctor WARNs `permit method` for a route whose method the rule leaves protected). For another method write `.permit(Method.POST, "/health")` (`import com.snoworca.naruserve.Method;`); for a whole subtree use `.permitPrefix("/public")`.

> **Logout:** `POST /auth/logout` ends the refresh token of this device, but an access token already issued stays valid until it expires (15 minutes by default). `POST /auth/logout?all=true` ends every token of the user on every device, access tokens included; a client should also drop its access token on logout.

> **A 403 you did not expect:** a mistyped scope or role in `auth.require(...)`, `auth.requireRole(...)` or `.user(...)` answers 403 at run time; the startup log names it in an `auth doctor WARN undeclared scope` or `undeclared role` line.

**When you deploy:** replace `.mode(AuthMode.DEV)` with `.mode(AuthMode.fromEnv())` (DEV only when `NARU_ENV=dev`) and put an `openssl rand -base64 32` value in `JWT_SECRET`. Keep account passwords out of the code: read them from the environment, as in `System.getenv("ALICE_PASSWORD")`. PROD answers only HTTPS or local requests, so to call it from another device on your network see the LAN and device notes under [The secure-transport rule](#the-secure-transport-rule).


## The Auth builder

| API | Meaning | Express equivalent |
|---|---|---|
| `Auth.builder(@Nullable String secret)` | Short for `Auth.builder(KeySources.of(secret))`. Pass a String-typed expression such as `System.getenv("JWT_SECRET")`. In Java a bare `null` is ambiguous between the two overloads, so `Auth.builder(null)` is a compile error; write `Auth.builder((String) null)`. Kotlin accepts `Auth.builder(null)`, because the `KeySource` overload takes no null | `expressjwt({ secret })` |
| `Auth.builder(KeySource keys)` | Gives the key source directly | a `secret` callback |
| `KeySources.of(secret)`, `fromBytes(bytes)`, `fromEnv(name)`, `fromFile(file)`, `rotating(JwtKeys)` | The string's UTF-8 bytes / a copy of the bytes / an environment variable (read now; unset or empty means "not set") / a key file (made once when missing) / a kid key set ([Key sets and rotation](#key-sets-and-rotation-keysource-jwtkeys-keysources)) | `process.env.JWT_SECRET` |
| `spi.KeySource` (`@FunctionalInterface`, `byte[] signingKey() throws Exception`, default `@Nullable String signingKid()`, default `JwtKeys keys() throws Exception`) | An app implementation such as a vault | — |
| `.mode(AuthMode)` | Default `PROD` | `NODE_ENV` |
| `.profile(AuthProfile)` | `BASELINE`, `STANDARD`, `STRICT`; `STANDARD` when not called. It changes policy defaults only, and a value set explicitly wins | — |
| `.deployment(Deployment)` | `SINGLE_INSTANCE` (default) or `MULTI_INSTANCE` | — |
| `.transport(TokenTransport)` | `HEADER` (default), `COOKIE` or `BOTH` | — |
| `.users(UserStore)`, `.groups(GroupStore)`, `.groupLimits(GroupLimits)`, `.roles(RoleStore)` | The stores. Omitted: `InMemoryUserStore`, no groups, depth 8 and size 256, the built-in `RolePolicy` | passport `deserializeUser` |
| `.events(AuthEvents)` | Adds a listener (run in the order added) | `app.on(...)` |
| `.permit(path)`, `.permit(Method, path)`, `.permitPrefix(prefix)`, `.protectOnly(prefix...)` | Public paths. `.permit` matches GET and HEAD of exactly the raw, undecoded path; `.permitPrefix` that path and everything below `prefix + "/"`; `.protectOnly` protects only the listed prefixes (plus the auth mounts and every upgrade; see [The protected range](#the-protected-range-protectonly-and-upgrades)). Paths are ASCII only (write others as `%XX`) | `.unless({ path })` of `express-jwt` |
| `.permitWebSocket(path)` | Lets WebSocket upgrades to exactly `path` open without a token; every other upgrade is protected whatever `.permit` and `.protectOnly` say | — |
| `.refreshTokens(RefreshTokenStore)` | The refresh-token store; when not called, the default that follows the user store ([Refresh-token storage](#refresh-token-storage-refreshtokenstore)) | the session store of `express-session` |
| `.apiKeys(ApiKeyStore)` | Turns API keys on; off when not called. Deleting a user then deletes its keys, and the 10-minute cleanup deletes expired keys ([API key storage](#api-key-storage-apikeystore-and-the-key-types)) | — |
| `.audit(AuditSink)` | Where audit events go; nowhere when not called ([Audit events](#audit-events-auditevent-auditsink-auditsinks)) | — |
| `.cookies(Configurer<CookieOptions>)` | Shared attributes (for example `c -> c.domain("example.com")`) of the cookies auth sets ([Auth cookies](#auth-cookies)) | `res.cookie` options |
| `.mountPath(path)` | The account endpoint mount, default `/auth`. The admin endpoints follow it at `/admin` below (default `/auth/admin`) | `app.use("/auth", router)` |
| `.apiKeyScheme(name)` | The API key scheme name, default `ApiKey`. `Bearer`, `Basic`, `Digest`, `DPoP`, `Negotiate` (any case) and names that are not HTTP tokens are refused | — |
| `.maxKeysPerUser(int)`, `.maxApiKeyDays(int)`, `.allowAdminScopesOnApiKeys(boolean)`, `.apiKeyFailureLimit(int max, long windowMillis)` | Live keys per user (default 20) / longest lifetime in days of a key issued through the key endpoints (default 365; `auth.apiKeys().issue` is not limited) / admin scopes `auth-*` on keys (default false) / failed key authentications per IP (default 100 per 15 minutes, 900000 ms). [API key authentication](#api-key-authentication) | — |
| `.register(boolean)` | Self-registration `POST /auth/register`. Default false (no route). [Account endpoints](#account-endpoints-authroutes) | a signup route |
| `.allowedAttributes(String... names)` | The attribute names a user may change on itself with `PATCH /auth/me` (for example `"displayName"`). None when not called (every attribute is 400). Repeated calls add names; the security field names (`passwordHash`, `ver`, `enabled`, `roles`) throw `IllegalArgumentException` | an allow-list over `req.body` (`_.pick`) |
| `.passwordPolicy(PasswordPolicy)`, `.breachedPasswordChecker(BreachedPasswordChecker)` | The rule of new passwords (default `PasswordPolicy.of(profile)`) / a breached-password check (none by default). [New-password policy](#new-password-policy-passwordpolicy-and-breachedpasswordchecker) | an `express-validator` length check |
| `.authenticator(Authenticator)` | Replaces the password check of login (for example with a directory). [Password authenticator](#password-authenticator-spiauthenticator) | the passport `LocalStrategy` verify |
| `.trustedOrigins(origin...)` | Origins besides the request's own that may receive and use auth cookies. [Origin check](#origin-check-and-trustedorigins) | not `cors({origin})` |
| `.recoveryFromEnv()` | `.recovery(true)` when the environment variable `NARU_AUTH_RECOVERY` is `1` or `true`; reads the environment only when the app calls it | — |
| `.statelessAccess(true)` | Unguarded routes see a minimal principal without a store read (`getId()` only; `getUsername()` is empty). Apps use only `getId()` there | — |
| `.timeSource(TimeSource)`, `.logger(Logger)`, `.responses(AuthResponses)`, `.config(AuthConfig)` | The clock / the log (default: the app's logger after install) / the error bodies / a configuration file | — |
| `.hashCost(HashCost)`, `.hashConcurrency(int)`, `.passwordHasher(PasswordHasher)` | The PBKDF2 cost of new hashes (default `HashCost.defaults()`, calibrated to 500 ms) / how many hashes run at once (default `max(1, cores / 2)`) / the hasher (default a `Pbkdf2PasswordHasher` at that cost). See [Password hashing](#password-hashing-passwordhasher-hashcost) | `bcrypt.hash(pw, rounds)` |
| `.user(name, @Nullable password, roles...)`, `.recovery(boolean)` | An account created on start when missing / opens the one-time setup token of a password-less admin `.user` (default false). See [The first account](#the-first-account-user) | a seed script |
| `.allowPreAuthMiddleware(path...)` | The exact paths of path middleware that may be registered before install | — |
| `.allowInsecureTransport(reason)` | Turns the PROD secure-transport check off (a device server without its TLS adapter yet); the reason goes into a doctor WARN on every start. Use TLS when you can | — |
| `build()` | Checks every rule and throws all violations **at once** as one `AuthConfigException`. Every call returns a new, independent instance | — |

`AuthConfigException` (unchecked): `getViolations()` (one entry per violation, unmodifiable) and a `getMessage()` holding every entry. Messages never carry a secret value. `build()` refuses:

- In PROD a missing secret (`JWT_SECRET is not set. Generate one with: openssl rand -base64 32 — or, for a temporary key, build with .mode(AuthMode.DEV), or with .mode(AuthMode.fromEnv()) with NARU_ENV=dev.`), fewer than 32 UTF-8 bytes (the message states the byte count and the minimum), fewer than 8 distinct byte values, and known examples (`secret`, `changeme`, `change-me-now`, `your-256-bit-secret` and the docs sample constant `naru-docs-sample-secret-do-not-use-in-production`, padded or not). In **DEV** a missing secret becomes a temporary key with one WARN saying that a restart invalidates all tokens, and a short or example key becomes a WARN.
- `.permitPrefix("/")`, `.permit("")` or `.permitPrefix("")`, and non-ASCII `.permit`, `.permitPrefix` or `.protectOnly` paths.
- Under `MULTI_INSTANCE`, stores not shared between nodes: `InMemoryUserStore`, `FileUserStore`, `InMemoryGroupStore`, `FileGroupStore`, an `InMemoryRefreshTokenStore` or `FileRefreshTokenStore` given to `.refreshTokens(...)`, and the in-memory defaults of the unset refresh token, login throttle and device trust stores (a custom user store never falls back to an in-memory refresh store there). `MULTI_INSTANCE` passes only when the app gives stores shared between nodes (`.refreshTokens(...)`, `.loginThrottle(...)`, `.deviceTrust(...)` and the user and group stores).
- `.mountPath("/")` with transport `COOKIE` or `BOTH`, an invalid `.apiKeyScheme`, and the violations of an `AuthConfig`.

A reserved `auth-*` scope in a `RolePolicy` is not in this list: `RolePolicy.Builder.role()` refuses it with `IllegalArgumentException` before the `Auth` is built.

| AuthProfile | Policy defaults it sets |
|---|---|
| `BASELINE` | Token lifetimes: access 15 minutes / refresh 14 days. New passwords: at least 8 characters (at most 128) |
| `STANDARD` | Token lifetimes: access 15 minutes / refresh 1 day. New passwords: at least 8 characters (at most 128). The profile used when `.profile(...)` is not called |
| `STRICT` | Token lifetimes: access 5 minutes / refresh 12 hours. New passwords: at least 15 characters (at most 128). The doctor warns about hop-mode proxy trust |

```java
Auth auth = Auth.builder(KeySources.fromEnv("JWT_SECRET"))
    .mode(AuthMode.fromEnv())
    .profile(AuthProfile.STRICT)          // changes policy defaults only
    .transport(TokenTransport.BOTH)       // Bearer and the naru_at cookie
    .permitPrefix("/public")
    .build();                             // every violation in one AuthConfigException
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .profile(AuthProfile.STRICT)
    .transport(TokenTransport.BOTH)
    .permitPrefix("/public")
    .build()
```

## Install order

`auth.install(app)` reads the app's registration list (`app.registrations()`) and throws `IllegalStateException("auth.install(app) must come before routes; found GET /x registered before it")` when a route-family registration (`ROUTE`, `WEBSOCKET`, `STATIC`, `ROUTER_MOUNT`, `GROUP`) is already there. A second `auth.install(app)` or `app.use(auth)` throws `IllegalStateException("auth is already installed on this app; call app.use(auth) or auth.install(app) once, and build one Auth per app")`; on another app the message reads `auth is already installed on another app; ...`, because one `Auth` serves one app. Middleware without a path (`cors`, `accessLog`, `use(Middleware)`, `use(ErrorMiddleware)`, `param`) may come first.

**`app.use(auth)` is the same as `auth.install(app)`.** `Auth` is a Core [`Installable`](app.md#middleware-and-errors), so `app.use(mw)` calls `auth.install(app)` instead of adding it as middleware, with the same order check and exceptions. `app.use("/", auth)`, the Express habit, installs it the same way. A path use such as `app.use("/api", auth)` (any path other than `""` or `"/"`) throws `IllegalArgumentException` when it is registered: `app.use("/api", mw): Auth installs itself on the whole app; call app.use(mw) without a path (to protect only a prefix with auth, build it with .protectOnly("/api"))`. `app.use("", auth)`, `router.use(auth)` (a Router mounted under a prefix too) and a route middleware `app.get("/x", auth, h)` add auth without installing it, so auth has no endpoints, start-time accounts or checks; an auth attached only that way, never installed, answers every request with 500 `INTERNAL_ERROR` without calling `next`, and logs one ERROR naming the fix (`app.use(auth)` or `auth.install(app)`). To guard a single route, use `auth.require(...)`; to protect only a prefix, build with `.protectOnly("/api")`. The never-installed ERROR goes to the auth logger (`.logger(...)`, the console logger by default), not to `app.logger()`.

The install order is **`cors` → `auth.install(app)` → routes → `app.onError(auth.errors())` last**. A `cors` registered after install is a doctor FAIL (`cors must be registered before auth.install(app); found CORS registered after it, so auth answers preflight requests before CORS can`, DEV included). Auth never lets a CORS preflight through on its own: a preflight carrying `Origin` and `Access-Control-Request-Method` is answered without a token by the CORS registered before it, and an `OPTIONS` without `Origin` is authenticated like any request (401 without a credential). **Path middleware** before install (`use("/hook", mw)`, `use(Object, Middleware)`, `useAsync(path, ...)` with a path other than `""` or `"/"`) runs before authentication and can become an unauthenticated endpoint, so it is a doctor FAIL (DEV included); `.allowPreAuthMiddleware("/hook")` allows exactly that path. The doctor lists the middleware before install, pathless ones included, as INFO (`auth doctor: pre-auth middleware [...]`).

What install does: `app.use("", auth)` once (so it is in the WebSocket upgrade pipeline too, and an upgrade without a token gets 401 JSON before 101), `auth.routes()` mounted at `mountPath` and `auth.adminRoutes()` at `mountPath + "/admin"` (each inside a router whose last layer is the auth error middleware, so routes added after install get JSON errors too), a registration watcher, and doctor in `onStart`. `Auth` is a `Middleware`, so the first `MIDDLEWARE` record of the list is `auth`.

Express equivalent: the same order as `app.use(cors())` → `app.use(expressjwt(...))` → routes → the `app.use((err, req, res, next) => ...)` error handler last.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).build();
RestApp.start(8080, app -> {
    app.cors(c -> c.allowedOrigins("https://app.example.com"));   // 1. cors
    auth.install(app);                                            // 2. auth.install
    app.get("/me", (req, res) -> res.send(auth.user(req).getId())); // 3. routes
    app.onError(auth.errors());                                   // 4. last
});
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).build()
RestApp.start(8080) { app ->
    app.cors { c -> c.allowedOrigins("https://app.example.com") }
    auth.install(app)
    app.get("/me") { req, res -> res.send(auth.user(req).id) }
    app.onError(auth.errors())
}
```

## Guards and the principal

A guard answers 403 when the principal lacks the scope or role. A 403 you did not expect is often a mistyped scope or role: the startup log names it in an `auth doctor WARN undeclared scope` or `undeclared role` line.

| API | Meaning | Express equivalent |
|---|---|---|
| `auth.require(scope...)` | Principals holding every scope; otherwise 403 `insufficient_scope`, and 401 without a principal. A scope with `*` throws `IllegalArgumentException` when declared | `express-jwt-authz(['posts:write'])` |
| `auth.requireRole(role...)` | Principals holding **at least one** of the roles; otherwise 403 `insufficient_scope`. An API-key request gets 403 `api_key_not_allowed` (a key narrows scopes, not roles) | a `requireRole('editor')` middleware |
| `auth.authenticated()` | Only re-reads the principal from the store: 401 when disabled or at another token version, 503 when the store fails | `passport.authenticate()` |
| `auth.check(AccessRule)` | An app rule: true passes, false is 403 `insufficient_scope`, 401 without a principal; a throwing rule is 500 JSON | a hand-written `(req, res, next)` check |
| `AccessRule` (`@FunctionalInterface`, `boolean allows(AuthPrincipal, Request) throws Exception`) | Kotlin `auth.check { p, req -> p.hasScope("x") }` | — |
| `auth.user(req)` | The principal on a protected path (non-null); `AuthStateException` (a subclass of `IllegalStateException`) without one | `req.user` |
| `@Nullable auth.userOrNull(req)` | For public paths | `req.user` (undefined when absent) |
| `@Nullable auth.loadUser(req)` | Never throws. Promotes a minimal principal and returns null for a missing or disabled user, another version, or a store failure (logged); an already loaded principal is returned without a store call | — |
| `AuthPrincipal` | `getId()`, `getUsername()`, `getRoles()` (own roles plus group roles), `getScopes()`, `getAttributes()`, `isLoaded()`, `hasScope(scope)`, `hasRole(role)`, `@Nullable getApiKeyId()` (null for a JWT login). Getters never throw | `req.auth` |
| `auth.users()`, `auth.userStore()` | The app user facade / the original store | — |

Guards are Core `Middleware`. `RouteGroup` has no `(String, Middleware, RouteHandler)` overload, so write `g.route("/posts").use(auth.require("posts:read")).get(handler)`. For an API-key principal a scope is covered when the key's scopes cover it **and** the owner's current scopes cover it (not a set intersection of wildcards).

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).build();
auth.install(app);
app.get("/admin/stats", auth.requireRole("admin"), (req, res) -> res.send("stats"));
app.patch("/users/:id", auth.check((p, r) -> p.getId().equals(r.param("id")) || p.hasScope("users:write")),
    (req, res) -> res.send("updated"));
app.group("/api", g -> g.route("/posts").use(auth.require("posts:read")).get((req, res) -> res.send("[]")));
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).build()
auth.install(app)
app.get("/admin/stats", auth.requireRole("admin")) { _, res -> res.send("stats") }
app.patch("/users/:id", auth.check { p, r -> p.id == r.param("id") || p.hasScope("users:write") }) { _, res ->
    res.send("updated")
}
```

## The secure-transport rule

In PROD the auth middleware looks at the connection **before** it reads any token. It handles protected paths and the eight credential endpoints (`POST /auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/register`, `/auth/setup`, `/auth/password`, `/auth/me/apikeys`, `/auth/admin/apikeys`) only when `req.isSecure()` is true or the request is **local** (the socket address is loopback, `127.0.0.0/8` or `::1`, and no `X-Forwarded-*` or `Forwarded` header is present); otherwise it answers 403 `insecure_transport`. `.permitPrefix("/auth")` makes neither the credential endpoints nor the admin endpoints (below `/auth/admin`) public. The protected-range, mount and credential-endpoint decisions use the path as the default router compares it (case ignored, trailing and repeated slashes folded), while the public decisions of `.permit` and `.permitPrefix` match exactly, so a variant such as `/HEALTH` is protected. Public paths skip the check, and a request that fails it is handled as anonymous without reading its token. DEV turns the check off (with one WARN on the first non-local request).

**Calling it from a LAN or a device:** the default TinyHttpd engine has no HTTPS, so a PROD server called in plain text from another device on your network (a phone, another PC) answers login and protected paths with 403 `insecure_transport`. On that first refusal auth logs one WARN: `auth: POST /auth/login was refused with 403 insecure_transport: PROD serves protected paths and credential endpoints only over HTTPS or to local requests. Serve HTTPS through a TLS reverse proxy and set app.trustProxyHops(1) or app.trustProxyCidrs(...); for a LAN or device server without TLS, build with .allowInsecureTransport("reason"); for local testing, build with .mode(AuthMode.DEV). Logged once`. There are three ways out. 1. Put a TLS-terminating reverse proxy (nginx or similar) in front and call `app.trustProxyHops(1)` (or `app.trustProxyCidrs(...)`); recommended. 2. For a LAN or device server without TLS, give the builder `.allowInsecureTransport("office LAN demo, no TLS")`; tokens then travel in clear text and live shorter (access 5 minutes, refresh 24 hours). 3. For tests on your own machine, `.mode(AuthMode.DEV)`.

**The Core LAN warning:** when the bind address is not loopback (for example `app.server(o -> o.bindAddress("0.0.0.0"))`), `listen()` logs the Core WARN `listening on <address>, reachable beyond this device: the app must add its own authentication` (`<address>` is the bind address). Core does not look for auth, so the WARN appears even when auth is installed. When you see the line, check two things. 1. Auth is installed with `app.use(auth)` before the routes (the `protected: ...` line of `auth.describe()` shows the protected range). 2. In PROD the secure-transport rule above applies: a plain-text request from another device is 403 `insecure_transport`, so choose a TLS proxy or `.allowInsecureTransport(...)`. When both hold, the WARN can stay.

**The reverse-proxy configuration is a mandatory deployment step.** The proxy adds `X-Forwarded-For` and `X-Forwarded-Proto`, and the app trusts it with `app.trustProxyHops(1)` or `app.trustProxyCidrs("10.0.0.0/8")`. For nginx:

```text
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
```

Core does not read the `Forwarded` header. A proxy that sends only `Forwarded` therefore makes its requests non-local, but `req.isSecure()` stays false and every client has the proxy's address as its IP. **Residual risk:** a same-host proxy that adds none of these headers makes every request look local, so plain-text external requests pass the secure-transport check and every client shares the proxy's address as its IP, so the login IP limit applies to all users as one key (`/auth/setup` stays protected by its one-time setup token).

With no proxy trust configured, the first request carrying `X-Forwarded-For` logs one WARN saying that all users share the load balancer's IP and to set `trustProxyHops`. Under `SINGLE_INSTANCE` the first request carrying a proxy header logs one WARN to use `deployment(MULTI_INSTANCE)` with shared stores if several nodes serve the app. Undeclared multi-instance deployment is detected only partly, by two signals: the WARN above on the first request carrying a proxy header under `SINGLE_INSTANCE`, and one WARN, with the 401 `invalid_refresh_token`, when a refresh token arrives whose record this node's in-memory refresh store does not hold (issued before a restart or by another node; the token is not logged). Full detection is impossible.

## Error responses and AuthResponses

Every response of the auth middleware, the guards and the auth routers is JSON, and in PROD so is the answer to every exception an app route throws before start (see below; a path no route serves outside the auth mounts still gets Core's 404). The default body has the Core shape `{"error":{"code":…,"message":…,"requestId":…}}`, with `requestId` from that request's `req.requestId()`. No body carries a stack trace, an exception class name or the message of the thrown exception. A store failure in the middleware or a guard is 503 `SERVICE_UNAVAILABLE`.

Auth answers its own exceptions as JSON even when you forget the error layer: when no `auth.errors()` or `auth.errorMiddleware()` follows the routes at start, auth appends `auth.errorMiddleware()` after them (the doctor WARNs `errors placement`), so only a handler added after start without `auth.errorMiddleware()` gets Core's HTML error page with a Java stack trace. In PROD auth also appends, at start and after every layer registered by then (your own error layers answer first), a final error layer for every other exception: a Core `HttpException` keeps its status and, below 500, its message (for example `req.queryInt("page", 1)` with `?page=abc` is 400 `page must be a 32-bit integer`), and anything else, such as an app's `IllegalStateException`, is 500 `INTERNAL_ERROR` with no message, exception class or stack trace (Core logs the 500). DEV appends no such layer, so the stack page still helps while developing. A wrong method on an auth endpoint without a token is 401 `unauthenticated` first, because default deny runs before routing; its message names the method, for example `GET /auth/logout` gets `authentication is required; this endpoint takes POST /auth/logout`. With a token it reaches routing, and a request under the auth mounts that no route served gets JSON from a final layer auth appends at start: 405 `METHOD_NOT_ALLOWED` with an `Allow` header when a route of that path takes other methods (`GET /auth/logout` gets `this endpoint takes POST /auth/logout`, `PUT /auth/admin/users/1` gets `this endpoint takes GET, PATCH, DELETE /auth/admin/users/:id`), otherwise 404 `NOT_FOUND` `no auth endpoint serves <method> <path>`, which adds `API keys are off: build auth with .apiKeys(store)` for the key paths without `.apiKeys(...)` and names `grantRole` for `/auth/admin/groups/:id/roles` (a group gets a role in code in 0.11.0). A route the app adds under the auth mount after start comes after that layer and is not reached. `POST /auth/register` without `.register(true)` is 404 `NOT_FOUND` in auth JSON with the message `sign-up is off: POST /auth/register is served only when auth is built with .register(true)`, unless the app serves that path itself. A refresh without a token is 401 `invalid_refresh_token` with `no refresh token: send it as the "refreshToken" field of a JSON body` (COOKIE: `no refresh token: COOKIE mode reads it from the naru_rt cookie set at login`), and an auth-router body that does not parse, or is a JSON array or string, is 400 `BAD_REQUEST` with `the request body must be a JSON object`. A `POST /auth/login` without a body is 415 with `auth endpoints accept only application/json bodies`. An empty array `[]` is read as an empty object by the Gson codec, so it gets the endpoint's missing-field answer (for example `username must be a JSON string`).

Auth exceptions thrown by app handlers become the same JSON through `auth.errors()` or `auth.errorMiddleware()` placed after the routes ([The auth error layer](#the-auth-error-layer-autherrors-and-autherrormiddleware)). Every 401 carries `WWW-Authenticate: Bearer`; a 401 for a token that was read and does not verify (`invalid_token`) carries `Bearer error="invalid_token"`.

The error middleware of the auth routers: an invalid JSON body 400 `BAD_REQUEST`, an oversized body 413 `PAYLOAD_TOO_LARGE`, a non-JSON body 415 `UNSUPPORTED_MEDIA_TYPE` (Core `HttpException` statuses); an `AuthRejection` with its status and code (plus `Retry-After`); `DuplicateUserException` 409 `duplicate_user`; an `AuthUnavailableException` 503 with its code; any other store exception (`SQLException`, `IOException`) 503 `SERVICE_UNAVAILABLE`; anything else 500 `INTERNAL_ERROR`. The 503 and 500 cases are logged; the 4xx cases (`AuthRejection`, `DuplicateUserException`, a Core `HttpException`) are not.

`spi.AuthResponses` (`@FunctionalInterface`, `void error(Request req, Response res, int status, String code, String message) throws Exception`), set with `.responses(...)`, changes only the body shape; the status stays (auth sets the status and any `Retry-After` first). If it throws, auth logs it and answers with the default JSON body. Express equivalent: the error handler placed last, `(err, req, res, next) => res.status(...).json(...)`.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .responses((rq, rs, status, code, message) -> rs.type("application/json").send("{\"err\":\"" + code + "\"}"))
    .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .responses { _, res, _, code, _ -> res.type("application/json").send("{\"err\":\"$code\"}") }
    .build()
```

## Configuration file: AuthConfig

Operator values come from a `java.util.Properties` file: `Auth.builder((String) null).config(AuthConfig.load(new File("auth.properties")))`. `.config(...)` applies them as if their setters were called at that point, so later setters win. Objects (stores, sinks) are set in code only, and no key names a class. Unknown keys, bad values and unresolved secret references are reported by `build()` together with the builder's own violations. Express equivalent: `dotenv`.

| Key | Value |
|---|---|
| `naru.auth.secret` | A reference only: `env:NAME` (the environment variable, `KeySources.fromEnv`) or `file:/path` (the file content, surrounding whitespace removed). A secret written in the file is a violation |
| `naru.auth.mode` | `dev` or `prod` |
| `naru.auth.profile` | `baseline`, `standard` or `strict` |
| `naru.auth.deployment` | `single-instance` or `multi-instance` |
| `naru.auth.transport` | `header`, `cookie` or `both` |
| `naru.auth.mount-path` | for example `/auth` |
| `naru.auth.permit`, `naru.auth.permit-prefix`, `naru.auth.protect-only` | comma-separated paths |
| `naru.auth.stateless-access` | `true` or `false` |
| `naru.auth.api-key-scheme` | the scheme name |

`AuthConfig.from(Properties)` exists too; `AuthConfig.KEYS` lists the known keys.

```java
Properties p = new Properties();
p.setProperty("naru.auth.secret", "env:JWT_SECRET");   // the secret only by reference
p.setProperty("naru.auth.profile", "strict");
Auth auth = Auth.builder((String) null).config(AuthConfig.from(p)).permit("/health").build();
```

```kotlin
val props = java.util.Properties()
props.setProperty("naru.auth.profile", "strict")
val auth = Auth.builder(System.getenv("JWT_SECRET")).config(AuthConfig.from(props)).build()
println(AuthConfig.KEYS)
```

## Startup checks (doctor)

They run in `onStart`; when they pass, the `.user(...)` accounts are created next. Every finding is a FAIL or a WARN; one FAIL stops the start before the port is bound, and the exception message names every FAIL. Each WARN is logged once and the start continues. The main checks: store connectivity (`findByUsername("__naru_probe__")` throwing is a FAIL), the public path list (an INFO listing), the middleware before install (an INFO listing) and a FAIL for path middleware that is not allowed, the hash cost measured on this device (INFO `auth doctor: hash cost N PBKDF2 iterations, X ms per hash ...`), proxy trust (PROD `trustProxy(true)` FAIL, `MULTI_INSTANCE` without proxy trust WARN, hop mode under `MULTI_INSTANCE` or `STRICT` WARN recommending CIDR mode), a `cors` after install FAIL, under `SINGLE_INSTANCE` an app-written `UserStore` (not a shipped store) gives one WARN that a restart logs every user out, a WARN when no `auth.errors()` or `auth.errorMiddleware()` follows the last route (errors placement), under `MULTI_INSTANCE` a FAIL for a `UserStore` whose `supportsAtomicFieldWrites()` is false, a PROD FAIL / DEV WARN for `HashCost.fixed(n)` below 50,000 iterations, the password-less `.user` rules (FAIL), `undeclared role` (one WARN per role that a `.user(...)` or `auth.requireRole(...)` names but the `RolePolicy` does not declare), `undeclared scope` (one WARN per scope that `auth.require(...)` needs but no `RolePolicy` role grants: `scope '<s>' is required by auth.require(...) but no RolePolicy role grants it (admin's * aside); a typo makes the scope check fail with 403. Grant it with RolePolicy.builder().role("<role>", "<s>")`; both WARNs apply only when roles come from a `RolePolicy`, not from a custom `RoleStore`), and the `.allowInsecureTransport` WARN. The other checks are described in the sections of their features.

Four more items: `registration` (a WARN in PROD when `.register(true)` opens sign-up to anyone), `clock` (a WARN when the current time of the `TimeSource` is before the module build date floor, 2025-01-01; a device whose battery ran out returns to 2000 or 1970), `login throttle limits` (a WARN when `.apiKeys(...)` is set and the `LoginThrottle`'s `usesLimitArguments()` is false, because the `.apiKeyFailureLimit` values are then not used) and `file state` (a FAIL when the directory of a `FileUserStore`, `FileGroupStore` or `FileRefreshTokenStore` file, or of the login throttle and device trust files beside a `FileUserStore`, is not writable, and a WARN when a leftover `<name>.tmp` sits next to it, a write that stopped before its rename). With a `FileUserStore` or `KeySources.fromFile` there is also `plain secret values in auth files` (the count of secret values still stored plain, a WARN above zero; see [Encrypting file secrets](#encrypting-file-secrets-authfilekey)).

## AuthMode

| API | Meaning | Express equivalent |
|---|---|---|
| `AuthMode.PROD` | The default | `NODE_ENV=production` |
| `AuthMode.DEV` | Relaxes exactly eight things: a temporary key for a missing secret, the secure-transport check, hash-cost and key-strength failures downgraded to WARN, a `.user(...)` password that breaks the password policy (created with a WARN), a password-less admin `.user(...)` (a temporary password goes to the log), a file store without a file key (plain storage with a WARN), legacy `trustProxy(true)` (no doctor FAIL), and no final auth error layer (an app exception goes on to Core). Authorization is never relaxed | `NODE_ENV=development` |

The mode is passed in code. `AuthMode.fromEnv()` is `DEV` when the environment variable `NARU_ENV` is `dev` (any letter case) and `PROD` when it is unset, empty, `prod` or any other value. This explicit call by the app is the only environment read; the builder never reads the environment by itself. A builder without `.mode(...)` is `PROD`.

| What DEV relaxes (the whole list) | PROD |
|---|---|
| A missing secret becomes a temporary key (a restart invalidates all tokens; WARN) | `build()` throws `AuthConfigException` |
| The secure-transport check (plain-text non-local requests are served; one WARN on the first non-local request) | 403 `insecure_transport` |
| Short or example keys and hash-cost failures become WARNs | `build()` refuses / doctor FAIL |
| A `.user(...)` password that breaks the password policy becomes a WARN and the account is created | `build()` throws `AuthConfigException` |
| A password-less admin `.user(...)` gets a temporary password written to the log | doctor FAIL (a one-time setup token when started with `.recovery(true)`) |
| A file store without a file key becomes a WARN and stores plain data | `build()` throws `AuthConfigException` |
| Legacy `trustProxy(true)` is no doctor FAIL | doctor FAIL |
| No final auth error layer (an app exception no earlier layer answered reaches Core's page with its stack trace) | That exception also gets JSON in the Core shape |

Default deny, the install order, the public path rules, authorization and auth's own JSON error bodies are the same in DEV as in PROD.

```java
AuthMode mode = AuthMode.fromEnv();                 // DEV when NARU_ENV=dev, otherwise PROD
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).mode(mode).build();
System.out.println(auth.mode());
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).mode(AuthMode.fromEnv()).build()
if (auth.mode() == AuthMode.DEV) println("dev relaxations on")
```

## Errors: AuthRejection, AuthUnavailableException, AuthErrorCode

Every auth error response has the Core JSON shape: `{"error":{"code":…,"message":…,"requestId":…}}`.

- `new AuthRejection(int status, String code)`, `(status, code, @Nullable String message)`, `(status, code, message, @Nullable Long retryAfterSeconds)` — an unchecked exception that extends the Core `HttpException` (`status()` is its status), so Core treats it as a client error: it is not logged, and without an auth error layer Core still answers that status. `DuplicateUserException` is an `HttpException` with status 409 too. `status` must be 400–499 and `code` must match `[a-z][a-z0-9_]{0,63}`, otherwise `IllegalArgumentException`. A null message is answered with the status's default text (for example `Unprocessable Content`), and `retryAfterSeconds` adds a `Retry-After` header. `getStatus()`, `getCode()`, `@Nullable getRetryAfterSeconds()`.
- `new AuthUnavailableException(@Nullable String message)`, `(message, @Nullable Throwable cause)`, `(String code, message, cause)`, `(code, message, @Nullable Long retryAfterSeconds, cause)` — a 503 for a store, listener or hash-slot failure. A busy hash-slot pool carries `Retry-After` 1 second. Without a code it is the Core spelling `SERVICE_UNAVAILABLE`; an unconfirmed security write uses `security_write_conflict`. `getCode()`, `@Nullable getRetryAfterSeconds()`, `getStatus()` (always 503).
- `AuthErrorCode` — the fixed list of auth codes and statuses (`getCode()`, `getStatus()`). 400 `BAD_REQUEST`, 404 `NOT_FOUND`, 415 `UNSUPPORTED_MEDIA_TYPE` and 503 `SERVICE_UNAVAILABLE` are Core codes and not in this list. The input-validation 400 is raised as a Core `HttpException`; the 415 for a non-JSON auth body is answered directly by the global auth middleware in the same JSON shape (code `UNSUPPORTED_MEDIA_TYPE`), so it never reaches the app's `onError` and `AuthResponses` shapes it; 503 `SERVICE_UNAVAILABLE` is the default code of `AuthUnavailableException`.

| code | Status | Answer |
|---|---|---|
| `unauthenticated` | 401 | No credential on a protected path. The message is `authentication is required`; when the one `Authorization` header uses a scheme other than Bearer (for example `Token abc`, or a bare token) it is `authentication is required; send the access token as Authorization: Bearer <token>` (HEADER and BOTH; with keys on, the API key scheme is judged as a key instead) |
| `invalid_token` | 401 | A bearer token that does not verify or has expired |
| `invalid_api_key` | 401 | An API key that does not verify |
| `invalid_credentials` | 401 | A wrong username or password. **A wrong current password uses this code too** (`AuthErrorCode.WRONG_CURRENT_PASSWORD` is an alias of this constant) |
| `invalid_refresh_token` | 401 | A refresh token that does not verify, was revoked or was reused |
| `invalid_setup_token` | 401 | A setup token that differs from the logged one |
| `invalid_request` | 400 | More than one credential in one request (protected paths and credential endpoints only) |
| `service_account_no_password` | 400 | A password operation on a service account |
| `username_mismatch` | 400 | A setup body whose username is not the configured one |
| `insufficient_scope` | 403 | A missing scope. The refusal of `auth.requireRole`, a false `auth.check` rule and the self-rule refusal use this code too |
| `api_key_not_allowed` | 403 | An endpoint API keys may not call |
| `insecure_transport` | 403 | A plaintext non-local request in PROD |
| `csrf_failed` | 403 | A CSRF token or Origin failure in cookie mode |
| `setup_not_local` | 403 | A setup request that is not local |
| `api_key_limit` | 409 | The per-user API key limit |
| `duplicate_user` | 409 | A duplicate username on create or rename |
| `write_conflict` | 409 | A password change that lost a concurrent write |
| `group_cycle` | 409 | A group edge that would close a cycle or pass the depth limit |
| `group_too_large` | 409 | A group delete that would touch more than 1,000 users |
| `too_many_attempts` | 429 | Login, password-check or API key failures over their limit |
| `security_write_conflict` | 503 | A security write that a re-read does not confirm |
| `SERVICE_UNAVAILABLE` | 503 | A store, throttle, hash-slot or group-expansion-limit failure (Core spelling, the `AuthUnavailableException` default) |

Express equivalent: `throw createError(422, ...)` from `http-errors`, turned into JSON by the error handler placed last.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).build();
auth.install(app);
app.post("/orders", (req, res) -> {
    if (req.header("X-Tenant") == null) throw new AuthRejection(422, "tenant_required", "Send X-Tenant");
    res.status(201).send("ok");
});
app.onError(auth.errors());   // AuthRejection → 422 {"error":{"code":"tenant_required",...}}
```

```kotlin
app.post("/orders") { req, res ->
    if (req.header("X-Tenant") == null) throw AuthRejection(422, "tenant_required", "Send X-Tenant")
    res.status(201).send("ok")
}
println(AuthErrorCode.INVALID_TOKEN.status)
```

## AuthEvents

A listener whose methods are all default no-ops; override only what you need (Java `new AuthEvents() { … }`, Kotlin `object : AuthEvents { … }`). It is like Express's `app.on('event', …)`, except that some events can refuse.

- Refusable (synchronous, before the store write): `beforeLogin(user, ctx)`, `beforeUserCreate(candidate, ctx)`, `beforeUserUpdate(before, after, ctx)`, `beforeUserDelete(user, ctx)`. Throwing `AuthRejection` refuses with its status and code. Any other exception, an `HttpException` included, is logged and becomes a 503 `AuthUnavailableException`.
- Notifications (after a successful write, before the response): `onLogin`, `onUserCreated`, `onUserUpdated(before, after, changes, ctx)`, `onPasswordChanged`, `onUserDeleted`, `onTokenRefreshed`, `onLogout(userId, allDevices, ctx)`, `onRefreshTokenReused(userId, familyId, ctx)` (after the family of a reused refresh token was revoked). Failures are only logged; the response stays as it is.
- Every `AuthUser` a callback receives is a copy whose `passwordHash` is null.
- `AuthEventContext`: `@Nullable getOrigin()` (`UserOrigin`: `REGISTER`, `ADMIN`, `APP`, `SELF`, `SETUP`, `RECOVERY`, `BOOTSTRAP` — set only for user-change events), `@Nullable getRequest()`, `@Nullable getActorId()`, `getTimestamp()` (epoch milliseconds). `UserChange`: `USERNAME`, `ROLES`, `ENABLED`, `PASSWORD`, `ATTRIBUTES`.

Every method is `default void … throws Exception`; the signatures in the table leave that part out.

| Method and Java signature | When it fires | Can refuse |
|---|---|---|
| `beforeLogin(AuthUser user, AuthEventContext ctx)` | Before a login with a correct password completes | Yes |
| `onLogin(AuthUser user, AuthEventContext ctx)` | After a successful login | No |
| `beforeUserCreate(AuthUser candidate, AuthEventContext ctx)` | Before a register, an admin create or `auth.users().create`; `candidate` has no id yet | Yes |
| `onUserCreated(AuthUser user, AuthEventContext ctx)` | After a user was created (register, admin, app, setup, recovery, a `.user` seed) | No |
| `beforeUserUpdate(AuthUser before, AuthUser after, AuthEventContext ctx)` | Before a rename, an attribute change, an admin update or a password change, with the values before and after | Yes |
| `onUserUpdated(AuthUser before, AuthUser after, Set<UserChange> changes, AuthEventContext ctx)` | After a user was updated; `changes` names what changed | No |
| `onPasswordChanged(AuthUser user, AuthEventContext ctx)` | After a password was changed or reset | No |
| `beforeUserDelete(AuthUser user, AuthEventContext ctx)` | Before an admin delete or `auth.users().delete` | Yes |
| `onUserDeleted(AuthUser user, AuthEventContext ctx)` | After a user was deleted | No |
| `onTokenRefreshed(AuthUser user, AuthEventContext ctx)` | After a refresh token was rotated (`POST /auth/refresh`) | No |
| `onRefreshTokenReused(String userId, String familyId, AuthEventContext ctx)` | After a used refresh token came back outside the reuse grace window and its family was revoked; the client gets the ordinary refresh failure, so only this event tells that detection fired | No |
| `onLogout(String userId, boolean allDevices, AuthEventContext ctx)` | After a logout; `allDevices` is true for `?all=true` | No |

An event marked "Yes" refuses by throwing `AuthRejection`. An exception thrown by an event marked "No" is only logged. Attach the listener with the builder's `.events(listener)`.

Express equivalent: an `app.on('event', …)` listener, except that a `before*` method refuses by throwing.

```java
AuthEvents events = new AuthEvents() {
    @Override
    public void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) {
        if (ctx.getOrigin() == UserOrigin.REGISTER && !candidate.getUsername().endsWith("@example.com")) {
            throw new AuthRejection(422, "domain_not_allowed", "Use your company address");
        }
    }
};
```

```kotlin
val events = object : AuthEvents {
    override fun beforeUserCreate(candidate: AuthUser, ctx: AuthEventContext) {
        if (ctx.origin == UserOrigin.REGISTER && !candidate.username.endsWith("@example.com")) {
            throw AuthRejection(422, "domain_not_allowed", "Use your company address")
        }
    }
}
```

A name or attribute policy placed in `beforeUserCreate` belongs in `beforeUserUpdate` too, or a rename bypasses it.

**Keeping an app table in sync:** when the app keeps user data in its own table (profiles, statistics), the `on*` events keep it in sync. The example updates the app's maps on every create, update, delete and login. A created user always has an id. These events come after a successful write and their failures are only logged, so a failed app-table write does not undo the user write.

```java
Map<String, String> profiles = new ConcurrentHashMap<>();   // the app table: user id → name
Map<String, Long> lastLogin = new ConcurrentHashMap<>();    // user id → last login time
AuthEvents sync = new AuthEvents() {
    @Override
    public void onUserCreated(AuthUser user, AuthEventContext ctx) {
        profiles.put(user.getId(), user.getUsername());
    }

    @Override
    public void onUserUpdated(AuthUser before, AuthUser after, Set<UserChange> changes, AuthEventContext ctx) {
        if (changes.contains(UserChange.USERNAME)) {
            profiles.put(after.getId(), after.getUsername());
        }
    }

    @Override
    public void onUserDeleted(AuthUser user, AuthEventContext ctx) {
        profiles.remove(user.getId());
        lastLogin.remove(user.getId());
    }

    @Override
    public void onLogin(AuthUser user, AuthEventContext ctx) {
        lastLogin.put(user.getId(), ctx.getTimestamp());
    }
};
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).events(sync).build();
```

```kotlin
val profiles = ConcurrentHashMap<String, String>()   // the app table: user id → name
val lastLogin = ConcurrentHashMap<String, Long>()    // user id → last login time
val sync = object : AuthEvents {
    override fun onUserCreated(user: AuthUser, ctx: AuthEventContext) {
        profiles[user.id!!] = user.username
    }

    override fun onUserUpdated(before: AuthUser, after: AuthUser, changes: Set<UserChange>, ctx: AuthEventContext) {
        if (UserChange.USERNAME in changes) profiles[after.id!!] = after.username
    }

    override fun onUserDeleted(user: AuthUser, ctx: AuthEventContext) {
        profiles.remove(user.id!!)
        lastLogin.remove(user.id!!)
    }

    override fun onLogin(user: AuthUser, ctx: AuthEventContext) {
        lastLogin[user.id!!] = ctx.timestamp
    }
}
val auth = Auth.builder(System.getenv("JWT_SECRET")).events(sync).build()
```

## AuthUser and AuthIds

`AuthUser` is an immutable value class. A store adapter maps its rows in one place, `toAuthUser(row)`, not through annotations (the Android release-build code shrinker strips annotations).

| API | Meaning |
|---|---|
| `AuthUser.builder()` / `toBuilder()` | Create / create a changed copy. `build()` throws `IllegalStateException` without a username |
| `@Nullable getId()` | The store's String id; null only before create. Deleted ids are never reused |
| `getUsername()` | The normalized login name |
| `@Nullable getPasswordHash()` | The password hash; null for a user without password login |
| `getTokenVersion()` | The token version; raising it invalidates every token issued before |
| `isEnabled()`, `isServiceAccount()` | Enabled / a service account (no password, API keys only; set at creation and never changed) |
| `getRoles()`, `getAttributes()`, `@Nullable getAttribute(name)` | Roles and app attributes; the returned collections cannot be modified |
| `AuthIds.parseLong(id)`, `AuthIds.parseUuid(id)` | To numeric or UUID keys. `"7"` → 7, a lower-case canonical UUID → that UUID. `"abc"`, `"007"`, `"+7"` and `" 7"` give null (so `findById` answers 404, not 500) |

v1 `AuthUser` has no direct scopes; scopes come from roles only. `toString()` shows neither the hash nor attribute values (attribute names only).

`passwordHash` is `transient`, so only a Gson with the default modifier settings (`new GsonJsonCodec()`) leaves it out. With a Gson built with `excludeFieldsWithModifiers(STATIC)` or another `JsonCodec`, the app must remove `passwordHash` itself.

Express equivalent: a Mongoose or Sequelize user model object and a `parseInt(req.params.id, 10)` check.

```java
AuthUser u = AuthUser.builder().username("alice").roles("editor").attribute("team", "web").build();
AuthUser moved = u.toBuilder().attribute("team", "api").build();
Long numeric = AuthIds.parseLong("42");        // 42; null for "007"
UUID uuid = AuthIds.parseUuid("not-a-uuid");   // null
```

```kotlin
val u = AuthUser.builder().username("alice").roles("editor").attribute("team", "web").build()
val team: String? = u.toBuilder().attribute("team", "api").build().getAttribute("team")
val numeric: Long? = AuthIds.parseLong("42")
```

## User storage: UserStore

The app implements `UserStore` over its own table. Five methods are required, all synchronous and `throws Exception`; auth answers their exceptions 503. Express equivalent: passport's `deserializeUser` and the user model.

| Method | Meaning |
|---|---|
| `@Nullable findByUsername(String username)` | Receives the normalized name (NFKC, `Locale.ROOT` lower case). Whether it matches a name, an e-mail or an employee number is the store's choice |
| `@Nullable findById(String id)`, `create(AuthUser)`, `@Nullable update(AuthUser)`, `boolean delete(String id)` | create chooses the id and returns the stored user; update replaces the whole row (null when absent); delete is false when absent |
| default `updatePasswordHash(id, expectedOldHash, newHash)` | A rehash; skipped silently (false) when not confirmed |
| default `changePassword(id, expectedOldHash, expectedVer, newHash)` | Swaps the hash and raises `ver` by one in one write |
| default `setEnabled`, `bumpTokenVersion`, `setRoles` | Security field writes; when the re-read does not confirm them, three more tries and then 503 `security_write_conflict` (`changePassword` after one try) |
| default `UserPage list(int offset, int limit, @Nullable String search)` | The admin list; the default throws `UnsupportedOperationException`. `UserPage`: `getItems()`, `getTotal()` (-1 when unknown), `getOffset()`, `getLimit()` |
| default `supportsAtomicFieldWrites()` | True when the five field methods are atomic implementations; default false |

The store contract: ids are Strings and a deleted id is never reused; `ver` only increases; create and update throw `DuplicateUserException` (409, unchecked) on a unique-key violation; under `MULTI_INSTANCE` `ver` should be read-after-write consistent.

- `ForwardingUserStore` — decorators (cache, tenant, audit) must extend this abstract class. It forwards every method to the wrapped store, but the five field methods only when the wrapped store's `supportsAtomicFieldWrites()` is true; otherwise the `UserStore` default runs on the decorator and goes through its own `findById` and `update` (so a cache is invalidated). `protected delegate()`.
- `InMemoryUserStore` — a memory store: thread-safe, ids are decimal strings counting from 1, `list` returns users whose name contains `search`, in creation order.
- `FileUserStore(File)`, `FileUserStore(File, long maxFileBytes, Logger)` — users in one JSON file `{"format":"naru-users","version":1,"nextId":..,"users":[...]}`, read back on construction and rewritten atomically on every change through the store-file protocol ([Durability of the store files](#durability-of-the-store-files)); every row keeps its password hash. Ids come from a counter kept in the file, so a deleted id is never reused, even after a restart. A file of an unknown version or above the size limit (8 MB by default, `FileUserStore.DEFAULT_MAX_FILE_BYTES`) fails construction with a message starting with `FAIL` and is not overwritten. Every write rewrites the whole file, so it suits small installations; `MULTI_INSTANCE` refuses it. One file has one writer: a write that finds the file changed by another writer since this instance read or wrote it is refused (see [Durability of the store files](#durability-of-the-store-files)). `getFile()`. With a key, `FileUserStore(File, AuthFileKey)` and `FileUserStore(File, long maxFileBytes, Logger, AuthFileKey)` write the hashes as `enc(…)` and the file is format version 2 ([Encrypting file secrets](#encrypting-file-secrets-authfilekey)). All four constructors read the file and throw `IOException` (checked), so the method that builds the store declares or catches it.

```java
UserStore store = new ForwardingUserStore(new InMemoryUserStore()) {
    @Override
    public AuthUser findById(String id) throws Exception {
        return super.findById(id);            // where a cache goes
    }
};
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).users(store).build();
```

```kotlin
val store = object : ForwardingUserStore(InMemoryUserStore()) {
    override fun findById(id: String): AuthUser? = super.findById(id)
}
val auth = Auth.builder(System.getenv("JWT_SECRET")).users(store).build()
```

## The app user facade: auth.users()

`auth.users()` returns `AuthUsers`, not the `UserStore`. It normalizes names, hashes passwords (`pbkdf2-sha256$<iterations>$<salt>$<hash>`), emits the `before*`/`on*` events with origin `APP` and writes under the per-user lock. Every `AuthUser` it returns or passes to a mutator is a copy whose `passwordHash` is null, so `res.json(auth.users().findById(id))` carries no hash. It throws only `DuplicateUserException` (409), `AuthUnavailableException` (503, also for store exceptions), `IllegalArgumentException` (400) and `AuthRejection` (a refusing listener). A bad argument, for example a password that breaks the `PasswordPolicy` or an `update` that changes a field it may not, is refused with `IllegalArgumentException`; catch it by that type, because its runtime class is an internal subclass that may change. A password-policy or username refusal thrown from an app handler reaches `auth.errors()` as 400 `BAD_REQUEST` with its message (for example `the password must have at least 8 characters`); the other argument refusals are 400 without one. To answer differently, catch `IllegalArgumentException` in the handler (for example with 422).

| API | Meaning |
|---|---|
| `@Nullable findById(id)`, `@Nullable findByUsername(name)` | Copies without the hash |
| `create(name, char[] password, roles...)` | The user with its id, after hashing, normalizing and the events |
| `create(name, @Nullable char[] password, @Nullable UserMutator init, roles...)` | `init` sets attributes and the service-account flag; only a service account has a null password |
| `@Nullable update(id, UserMutator)` | Changes attributes only; changing the id, name, hash, ver, enabled flag, roles or service-account flag is an `IllegalArgumentException` |
| `@Nullable rename(id, newName)` | Normalizes `newName` (NFKC, `Locale.ROOT` lower case), stores it and emits `beforeUserUpdate`/`onUserUpdated` with `UserChange.USERNAME` and origin `APP`; a name equal to the current one returns without a write or an event; a name another user holds is a `DuplicateUserException` (409) and the row stays |
| `boolean delete(id)` | Removes the group memberships, then (with `.apiKeys(store)`) every key of the user, then the row. A failing step answers 503 and keeps the row, so a retry continues where it stopped |
| `boolean checkPassword(id, char[] password)` | Checks the current password; a missing user or a null hash is false after a dummy hash. Every current-password confirmation of one user id shares one limit of 5 failures per 15 minutes; over it the call throws `AuthRejection(429, "too_many_attempts")` with `getRetryAfterSeconds()` and computes no hash, and `auth.errors()` answers 429 with `Retry-After`. While another confirmation of the same user (an endpoint's included) runs, the call waits up to 5 seconds and then throws `AuthUnavailableException` (503). A success does not reset the count |

The login value is one opaque `username` string: auth applies only NFKC and `Locale.ROOT` lower case and has no e-mail, phone or login-key API. An app that logs users in by an attribute such as `email` must not list that attribute in `allowedAttributes` unless the app itself normalizes it and enforces its uniqueness, because auth applies neither. Every `char[]` password passed to `create` or `checkPassword` holds only `'\0'` after the call returns, so copy it first if you still need it.

Writes through auth run under one re-entrant per-user lock (the same lock the store's default field methods take), re-read the row inside the lock and change only their own fields. Two admins writing the same user at the same time is last-writer-wins: the later write replaces the earlier one.

`UserMutator` is `@FunctionalInterface AuthUser apply(AuthUser user)` (`java.util.function.Function` is API 24+). `auth.userStore()` returns the original store; never pass a copy from `AuthUsers` to its `update`, or the hash is stored as null.

Express equivalent: the app's user service that hashes and stores passwords (`User.create` with `bcrypt.hash`, and `bcrypt.compare` at login).

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).build();
AuthUser alice = auth.users().create("Alice", "correct horse".toCharArray(), "editor");
auth.users().update(alice.getId(), u -> u.toBuilder().attribute("nickname", "Al").build());
boolean ok = auth.users().checkPassword(alice.getId(), "correct horse".toCharArray());
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).build()
val alice = auth.users().create("Alice", "correct horse".toCharArray(), "editor")
auth.users().update(alice.id!!) { it.toBuilder().attribute("nickname", "Al").build() }
```

## Groups: GroupStore and GroupLimits

Groups form a DAG (a group may have several parents). Group ids are lower case `[a-z0-9._-]{1,64}` and memberships are keyed by user id, so a rename keeps them.

- Two methods to implement: `Set<String> groupsOfUser(String userId)` (direct memberships) and `Set<String> parentsOf(Set<String> groupIds)` (the union of direct parents). Both are `throws Exception`; there is no single-group `parentsOf(String)` (no N+1 queries).
- `default effectiveGroupsOfUser(userId)` — one `parentsOf` call per level upward. Diamonds and cycles count a group once. Past depth 8 or size 256 it fails with 503 `SERVICE_UNAVAILABLE` **instead of truncating** (the request is not allowed). Override it if the database can run a recursive CTE; auth still checks the size of the overridden result.
- `default rolesOfGroups(groupIds)` — the roles groups confer; the interface default is none, and the built-in `InMemoryGroupStore` and `FileGroupStore` return the union of the roles given with `grantRole`.
- `GroupLimits.defaults()` (depth 8, size 256), `GroupLimits.of(maxDepth, maxSize)`, `getMaxDepth()`, `getMaxSize()`.

### Group writes: GroupAdminStore, InMemoryGroupStore, FileGroupStore

`GroupAdminStore extends GroupStore` holds the writes of the admin group endpoints. A read-only source such as LDAP implements only `GroupStore`.

| Method | Meaning |
|---|---|
| `boolean createGroup(id)` | False when it exists |
| `boolean deleteGroup(id)` | Also removes its memberships and every edge it is child or parent of; false when absent |
| `listGroups()`, `groupExists(id)`, `membersOf(id)`, `childrenOf(id)` | Reads |
| `addMember`, `removeMember`, `addParent(child, parent)`, `removeParent` | Idempotent: the same call twice equals once, without an error |
| default `removeUser(userId)` | Removes every direct membership when the user is deleted; a database store overrides it with one `DELETE` |

The two built-in stores also give roles to groups (the admin HTTP route for this is v2).

| Method | Meaning |
|---|---|
| `grantRole(groupId, role)` | Gives the group a role; its members, direct or through child groups, get the role's scopes. A second grant equals one. An unknown group, an empty role or one holding a tab, CR or LF is an `IllegalArgumentException` and changes nothing. `FileGroupStore` writes the file, and a failed write is an `IOException` (memory unchanged too) |
| `revokeRole(groupId, role)` | Takes the role back; a no-op when the group does not hold it |
| `rolesOf(groupId)` | The roles the group itself holds (not those of its parents) |

`deleteGroup` removes the group's roles too.

**When a direct store write is seen:** every write made directly on the store after start, that is membership (`addMember`, `removeMember`), parent edges (`addParent`, `removeParent`), roles (`grantRole`, `revokeRole`) and group deletion (`deleteGroup`), reaches a signed-in user when that user's cached principal expires: 5 seconds by default (`userCacheTtl`, [Principal cache](#principal-cache-usercachettl)). A change made through the admin group endpoints ([Admin endpoints](#admin-endpoints-authadminroutes)) is seen at once on that node. A test that writes to the store and sends a request right away builds with `Auth.builder(...).groups(groups).userCacheTtl(0)`, so every request reads the store.

Before `addParent` auth checks cycles (the child is already an ancestor of the parent), self edges and depth (the longest path above the parent, plus one, plus the longest path below the child, more than 8); a refused edge never reaches `addParent` and answers 409 `group_cycle`. `InMemoryGroupStore` runs this check and the insert under one lock, so two concurrent edges can never close a cycle. `FileGroupStore(File)` rewrites its file atomically on every change and reads it in the constructor; the first line is the format version `naru-groups 1`, and an unknown version fails with an error naming it. The file is read and written within a size limit (8 MB by default, `FileGroupStore.DEFAULT_MAX_FILE_BYTES`) compared with its length before anything is read, so a huge or corrupt file fails the constructor with its `FAIL ... above the limit` message instead of exhausting the heap, and a change that would grow the file above the limit is refused with an `IOException`, memory and file keeping their previous state; set the limit with `new FileGroupStore(file, limits, maxFileBytes, logger)`. Neither is shared between nodes, so `MULTI_INSTANCE` refuses both. The edge check uses the `GroupLimits` given to the store constructor; pass the same value as the builder's `.groupLimits(...)`.

Express equivalent: the passport `deserializeUser` code that joins a groups table to collect roles.

```java
InMemoryGroupStore groups = new InMemoryGroupStore();
groups.createGroup("company");
groups.createGroup("eng");
groups.addParent("eng", "company");     // eng ⊂ company
groups.grantRole("company", "editor");  // members of company and its subgroups (eng) are editors
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).groups(groups)
    .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
    .build();
AuthUser bob = auth.users().create("bob", "bob-password-1".toCharArray());
groups.addMember("eng", bob.getId());   // bob passes auth.require("posts:write")
// a direct store write after start (groups.addMember(...)) reaches signed-in users within userCacheTtl
// (5 s by default; .userCacheTtl(0) in tests)
```

```kotlin
val groups = InMemoryGroupStore()
groups.createGroup("eng")
groups.grantRole("eng", "editor")
val auth = Auth.builder(System.getenv("JWT_SECRET")).groups(groups)
    .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
    .build()
val bob = auth.users().create("bob", "bob-password-1".toCharArray())
groups.addMember("eng", bob.id!!)
```

## Connecting your own database

auth has no built-in database store. Persistence goes through the SPIs only: `UserStore` for users, `GroupStore` for groups (`GroupAdminStore` with the group admin endpoints), `RefreshTokenStore` for refresh tokens, `LoginThrottle` for the login limits, `DeviceTrust` for trusted devices and `ApiKeyStore` for API keys. The app connects the methods of these interfaces to its own database calls (JDBC, JPA, MyBatis, Room and so on) and owns the driver, the tables and the migrations. The only stores auth ships are the in-memory and file defaults, both for one node.

`deployment(MULTI_INSTANCE)` refuses the in-memory and file stores auth ships (`InMemoryUserStore`, `FileUserStore`, `InMemoryGroupStore`, `FileGroupStore`, `InMemoryRefreshTokenStore`, `FileRefreshTokenStore`, `InMemoryLoginThrottle`, `InMemoryApiKeyStore`), and a store left unset that would fall back to a default, with an `AuthConfigException` from `build()`. The check looks at the store's class, so a decorator around an in-memory store is not detected. So name every store the nodes share: `.users(..)`, `.refreshTokens(..)`, `.loginThrottle(..)`, `.deviceTrust(..)`, `.groups(..)` when groups are used and `.apiKeys(..)` when keys are on.

The atomicity declarations a `deployment(MULTI_INSTANCE)` store must make:

| Declaration | Store | Returns true when | Without it |
|---|---|---|---|
| `supportsAtomicFieldWrites()` | `UserStore` | see the paragraph below | doctor FAIL, the app does not start |
| `supportsAtomicLimitInsert()` | `ApiKeyStore` | `insertIfUnderLimit` counts the live keys and inserts under one lock (for example a transaction that locks the owner row with `SELECT … FOR UPDATE`) | doctor FAIL when `.apiKeys(..)` is on |
| `usesLimitArguments()` | `LoginThrottle` | the limit-argument methods `retryAfter(key, max, windowMillis)` and `recordFailure(key, max, windowMillis)` use the limits they are given (for example one upsert per failure doing `failures = failures + 1`) | the store ignores the limits auth passes (`.loginFailureLimits(..)`, `.apiKeyFailureLimit(..)`) and counts with its own; doctor WARN when API keys are on |

A `UserStore` returns `true` from `supportsAtomicFieldWrites()` only when each of the five field methods is one atomic write (for a SQL database, one conditional `UPDATE`) and `update()` writes none of `passwordHash`, `ver`, `enabled` and `roles`. The five field methods are `updatePasswordHash`, `changePassword`, `setEnabled`, `bumpTokenVersion` and `setRoles`. Success is decided by the update count, with no read-modify-write. `ver` only increases, a unique-key violation becomes a `DuplicateUserException`, and a deleted id is never handed out again. Check a store against the contract with `AuthTesting.verifyUserStore(store)` and `AuthTesting.verifyApiKeyStore(a, b)`.

Below is the skeleton of an app `UserStore` over a SQL database. It is example code for the app, not code the library ships (it compiles without a driver); the table `users(id, username, password_hash, ver, enabled, roles, attributes, service_account)` is created by the app's migration tool. `changePassword` and the other field methods are each one conditional write. With the admin endpoint `GET /auth/admin/users`, implement `list(offset, limit, search)` too, with `ORDER BY username LIMIT ? OFFSET ?` (the default throws `UnsupportedOperationException`).

```java
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import javax.sql.DataSource;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** App code: a UserStore over a SQL database. The users table is created by the app's migration tool. */
public abstract class SqlUserStore implements UserStore {
    private static final String COLUMNS = "id, username, password_hash, ver, enabled, roles, attributes, service_account";
    private final DataSource db;

    protected SqlUserStore(DataSource db) {
        this.db = db;
    }

    /** The app's JSON codec turns roles and attributes into TEXT columns and reads a row into an AuthUser. */
    protected abstract String rolesText(Set<String> roles);

    protected abstract String attributesText(Map<String, String> attributes);

    protected abstract AuthUser read(ResultSet row) throws SQLException;

    @Override
    public AuthUser findByUsername(String username) throws SQLException {
        return findOne("username", username);
    }

    @Override
    public AuthUser findById(String id) throws SQLException {
        return findOne("id", id);
    }

    @Override
    public AuthUser create(AuthUser user) throws SQLException {
        AuthUser row = user.toBuilder().id(UUID.randomUUID().toString()).build(); // never shared between nodes, and a deleted id is never handed out again
        write("INSERT INTO users (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)", row.getId(), row.getUsername(),
                row.getPasswordHash(), row.getTokenVersion(), row.isEnabled(), rolesText(row.getRoles()),
                attributesText(row.getAttributes()), row.isServiceAccount());
        return row;
    }

    @Override
    public AuthUser update(AuthUser user) throws SQLException {
        // Writes username and attributes only; password_hash, ver, enabled and roles change through the field methods only.
        int n = write("UPDATE users SET username=?, attributes=? WHERE id=?", user.getUsername(),
                attributesText(user.getAttributes()), user.getId());
        return n == 0 ? null : findById(user.getId());
    }

    @Override
    public boolean delete(String id) throws SQLException {
        return write("DELETE FROM users WHERE id=?", id) > 0;
    }

    @Override
    public boolean updatePasswordHash(String id, String expectedOldHash, String newHash) throws SQLException {
        if (expectedOldHash == null) {
            return write("UPDATE users SET password_hash=? WHERE id=? AND password_hash IS NULL", newHash, id) == 1;
        }
        return write("UPDATE users SET password_hash=? WHERE id=? AND password_hash=?", newHash, id, expectedOldHash) == 1;
    }

    @Override
    public boolean changePassword(String id, String expectedOldHash, long expectedVer, String newHash)
            throws SQLException {
        // Swaps the hash and ver in one statement; a null old hash matches null.
        return write("UPDATE users SET password_hash=?, ver=ver+1 WHERE id=? AND ver=? AND (password_hash=? OR (password_hash IS NULL AND ? IS NULL))",
                newHash, id, expectedVer, expectedOldHash, expectedOldHash) == 1;
    }

    @Override
    public boolean setEnabled(String id, boolean enabled) throws SQLException {
        return write("UPDATE users SET enabled=? WHERE id=?", enabled, id) == 1;
    }

    @Override
    public boolean bumpTokenVersion(String id) throws SQLException {
        return write("UPDATE users SET ver=ver+1 WHERE id=?", id) == 1;
    }

    @Override
    public boolean setRoles(String id, Set<String> roles) throws SQLException {
        return write("UPDATE users SET roles=? WHERE id=?", rolesText(roles), id) == 1;
    }

    // True: each of the five field methods is one conditional UPDATE.
    @Override
    public boolean supportsAtomicFieldWrites() {
        return true;
    }

    private AuthUser findOne(String column, String value) throws SQLException {
        try (Connection c = db.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM users WHERE " + column + "=?")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    /** Runs one statement in autocommit and returns the update count; a unique-key violation (PostgreSQL SQLState 23505, MySQL error 1062; other databases use their own code) is a DuplicateUserException. */
    private int write(String sql, Object... args) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) {
                    ps.setNull(i + 1, Types.VARCHAR);
                } else {
                    ps.setObject(i + 1, args[i]);
                }
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState()) || e.getErrorCode() == 1062) {
                throw new DuplicateUserException("the username is taken", e);
            }
            throw e;
        }
    }
}
```

The app implements the other stores the same way and hands them to the builder.

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .deployment(Deployment.MULTI_INSTANCE)
    .users(SqlUsers(dataSource))                  // the app's UserStore: supportsAtomicFieldWrites() is true
    .refreshTokens(SqlRefreshTokens(dataSource))  // the app's RefreshTokenStore
    .loginThrottle(SqlLoginThrottle(dataSource))  // the app's LoginThrottle: usesLimitArguments() is true
    .deviceTrust(SqlDeviceTrust(dataSource))      // the app's DeviceTrust
    .apiKeys(SqlApiKeys(dataSource))              // the app's ApiKeyStore: supportsAtomicLimitInsert() is true
    .build()
```

Express counterpart: as with Passport's `serializeUser`/`deserializeUser` and a Knex or Sequelize model, the app attaches the users table and keeps the schema in its own migration files.

## Time: TimeSource

`com.snoworca.naruserve.utils.TimeSource` is `@FunctionalInterface long nowMillis()` (`java.time` is API 26+). Every public auth time is an epoch-millisecond `long` or `Long` read from this source. `TimeSource.system()` is the system clock; the builder's `.timeSource(...)` fixes the clock in tests. Express equivalent: replacing `Date.now` in a test.

```java
TimeSource fixed = () -> 1_000L;
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).timeSource(fixed).build();
```

```kotlin
val fixed = TimeSource { 1_000L }
```

## Roles and scopes: RolePolicy, RoleStore, Scopes

A scope is `resource:action`, each part `[a-z0-9-]{1,32}`. Wildcards appear only on the grant side, as `resource:*` or `*`. `*` covers everything, `posts:*` covers `posts:*` and every `posts:<action>`, and `posts:read` covers only itself; `write` does not include `read`. Resources starting with `auth-` are reserved: only `auth-users`, `auth-groups` and `auth-apikeys` are accepted. Role names and scopes are case-sensitive: `Editor` and `editor` are two roles (the doctor WARNs `undeclared role` for the one the `RolePolicy` lacks), and `Posts:read` breaks the scope grammar.

| API | Meaning | Express equivalent |
|---|---|---|
| `RolePolicy.builder().role(name, scopes...).build()` | Roles declared in code, merged into the built-in `admin` (`*`). A malformed or reserved scope throws `IllegalArgumentException` right there | `accesscontrol`'s `grant(...)` |
| Declaring `admin` again | Becomes a union and `build()` logs one WARN | — |
| `.replaceBuiltinRoles()` | Drops the built-in roles (`admin` holds only what you grant) | — |
| `.logger(Logger)` | Where `build()` writes its warnings; a new `ConsoleLogger` by default | — |
| `RolePolicy.builtin()`, `scopesOf(role)`, `roles()`, `grantedScopes()` | The built-in policy / a role's scopes / role names / every granted scope | — |
| `RoleStore` (`@FunctionalInterface`, `scopesOf(role) throws Exception`) | Database roles. Reserved or malformed rows are dropped with one WARN per role; an exception fails closed with 503 | — |
| `Scopes.covers(grant, other)` | Whether the grant covers the other; both must be valid grants, otherwise `IllegalArgumentException` | — |
| `Scopes.isValidGrant/isValidRequired`, `validateGrant/validateRequired` | Grammar checks; a required scope cannot contain `*` | — |

```java
RolePolicy policy = RolePolicy.builder()
    .role("editor", "posts:read", "posts:write")   // the built-in admin (*) stays
    .build();
AuthUser user = AuthUser.builder().id("7").username("alice").roles("editor").build();
boolean canWrite = false;
for (String role : user.getRoles()) {
    for (String grant : policy.scopesOf(role)) {
        canWrite |= Scopes.covers(grant, "posts:write");
    }
}
Long numericId = AuthIds.parseLong("7");           // null for "abc"
```

```kotlin
val policy = RolePolicy.builder()
    .role("editor", "posts:read", "posts:write")
    .build()
val user = AuthUser.builder().username("alice").roles("editor").build()
val canWrite = user.roles.any { role -> policy.scopesOf(role).any { Scopes.covers(it, "posts:write") } }
println("${user.username} enabled=${user.isEnabled} canWrite=$canWrite")
```

## Durability of the store files

Auth file storage (the internal `AtomicFiles` protocol every file store will use) writes a temporary file, call `FileDescriptor.sync()`, rename the existing file to `.bak`, rename the temporary file to the main name and delete `.bak`. At start, a lone `.bak` is restored with a WARN; when both exist and the main file is readable, the main file is used and `.bak` deleted. An unreadable main file stops start without overwriting anything (a FAIL message says to inspect `.bak`). API 21 has no directory fsync, so rename durability depends on the file system and `ver` (the token version) may regress after power loss. The temporary file is created owner-only (read and write) inside an owner-only staging directory (`.<name>.tmp.new-<hex>`) and moved to its name before the content is written, so it is never visible with the umask permissions, not even while it is written, and a store file, which holds password hashes and token versions, is not readable by other local users whatever the umask (best effort: a file system without those permissions keeps its default). Each store's size limit is compared with the file length before anything is read, so a huge or corrupt file fails start with its `FAIL ... above the limit` message instead of exhausting the heap. Writes into one directory are serialized within the process, comparing the directory's canonical path and not the file name, so `dir/users.json`, `dir/./users.json`, a path through a symbolic link, `dir/USERS.json` (one file on a case-insensitive file system such as macOS or Windows) and the NFC and NFD spellings of one name (one file on a normalization-insensitive file system such as APFS or HFS+) never have two writers at once, whether the file or its directory exists yet or not. A directory that does not exist yet shares one process-wide lock whatever its spelling, a write into it fails with an `IOException` without writing anything, and a write prepared while the directory was missing that finds it created by then fails the same way, so a retry checks the file again under the directory's lock. Different files of one directory share that serialization but stay separate files. A `FileUserStore` remembers the file as it last read or wrote it: when another writer (a second `FileUserStore` over the same file, or another process) changed it since, the write is refused with an `IOException` (503 on an auth path) and one ERROR instead of rolling that change (a `ver`, a password hash) back; use one `FileUserStore` per file and restart to read the file again.

## Password hashing: PasswordHasher, HashCost

| API | Description | Express counterpart |
|---|---|---|
| `password.PasswordHasher` | `boolean accepts(String stored)`, `String hash(char[] password)`, `boolean verify(char[] password, String stored)`, default `boolean needsRehash(String stored)` (false). An SPI apps may implement; after the first public release it gains default methods only. The prefix of a stored string names its algorithm. Auth uses **one** configured hasher; a stored string it does not `accept` is checked against a dummy hash and fails. To read several formats (moving bcrypt accounts to PBKDF2), give `.passwordHasher(...)` a `PasswordHashers.chain(...)` | a `bcrypt` object |
| `new Pbkdf2PasswordHasher(iterations)` | The default hasher: PBKDF2-HMAC-SHA256 written over `javax.crypto.Mac` (Android 5.0 has no `PBKDF2WithHmacSHA256`). Format `pbkdf2-sha256$<iterations>$<salt>$<hash>` (16-byte salt, 32-byte key, unpadded base64url). An empty password is an `IllegalArgumentException`. The HMAC key is set once per hash, so a long password costs the same. `needsRehash` is true when the stored count is below 80 % or above 125 % of this hasher's count. A stored count above 2,000,000 is false without hashing (`MAX_STORED_ITERATIONS`). In auth's logins and password checks, a `pbkdf2-sha256$` stored string that is malformed or above that count runs one target-cost dummy PBKDF2 like an unknown user before it fails, so timing does not tell it apart. `getIterations()` | `bcrypt.hashSync` / `compareSync` |
| `PasswordHashers.bcrypt()`, `BcryptPasswordHasher.builder().maxCost(n).cost(n).logger(logger).build()` | The legacy bcrypt verifier, written in-house without a library. It reads `$2a$`, `$2b$` and `$2y$` and does not take `$2x$`. A stored cost above the limit (default 14) is false without running the key schedule, with one ERROR. Only the first 72 UTF-8 bytes of a password count. A salt that is not 22 characters, a hash part that is not 31, or a character outside `./A-Za-z0-9` is false. `needsRehash` is always true (PBKDF2 on the next successful login). `hash()` works too (`$2b$`, default cost 10), but PBKDF2 stays the default | `bcrypt.compare` |
| `PasswordHashers.chain(primary, legacy...)`, `PasswordHashers.chain(logger, primary, legacy...)` | One hasher over several stored formats. For each stored string the first of `primary`, then `legacy` in order, whose `accepts` is true runs `verify`. `hash` always uses `primary`. `needsRehash` is true when the accepting hasher is not `primary` or `primary.needsRehash` is true, so the next successful login moves the account to the `primary` format. A stored string no member accepts is false and logs one ERROR per prefix (at most its first 4 characters, never the whole string; once, whether `accepts` or `verify` meets it). Without a logger it logs to a `ConsoleLogger` (`chain(app.logger(), ...)` uses the app's). A `PasswordHasher` of your own (Java or Kotlin) is a member like any other | code that picks `bcrypt.compare` or another check per format |
| `PasswordHashers.digest(prefix, algorithm, encoding)` | A verifier of unsalted legacy MD5, SHA-1 and SHA-256. Stored format `<prefix>$<digest>` (for example `digest("legacy-md5", "MD5", DigestEncoding.HEX)` reads `legacy-md5$5f4dcc3b...`). `algorithm` is a `MessageDigest` name (`"MD5"`, `"SHA-1"`, `"SHA-256"`); an unknown one is an `IllegalArgumentException` when built. The password is digested as UTF-8 and compared through `ConstantTime`. `prefix` is a name without `$`; one trailing `$` is not doubled; an empty prefix is refused. A column without the prefix gets it from the store when read (`"legacy-md5$" + column`). `hash()` throws `UnsupportedOperationException` (no new weak hashes); `needsRehash` is always true | comparing `crypto.createHash('md5').update(pw).digest('hex')` |
| `PasswordHashers.saltedDigest(prefix, algorithm, encoding, order)` | A verifier of salted legacy digests. Stored format `<prefix>$<salt>$<digest>` (for example `legacy-sha256$Xy7q$9f86d0...`). The salt is the text from after the prefix to the **last** `$`, used as written (so a salt may hold `$`), joined to the password as UTF-8. `order` is `SaltOrder.SALT_THEN_PASSWORD` (`sha256(salt + pw)`) or `SaltOrder.PASSWORD_THEN_SALT` (`sha256(pw + salt)`); read it off the old code. A separate salt column is joined by the store when read. Otherwise as `digest` | comparing `crypto.createHash('sha256').update(salt + pw)` |
| `DigestEncoding.HEX`, `DigestEncoding.BASE64` | How `digest` and `saltedDigest` read the digest. `HEX` takes either case; `BASE64` is the standard alphabet (`+`, `/`), with or without `=` padding | `digest('hex')` / `digest('base64')` |
| `HashCost.calibrate(targetMillis)`, `HashCost.fixed(iterations)`, `HashCost.defaults()` | Measures this device once (a short warm-up and one timed run) and picks the iteration count of a hash taking about the target time (rounded down to 10,000, within 50,000..600,000) / a fixed count without calibration / `calibrate(500)`. `isFixed()`, `getTargetMillis()` (0 when fixed), `getIterations()` (0 when calibrated) | the rounds of `bcrypt.hash(pw, rounds)` |

Auth runs every `hash` and `verify` inside its hash slots (`.hashConcurrency(n)`, default `max(1, cores / 2)`, a wait queue of four times the slots); a configured hasher that handles both bcrypt and PBKDF2 shares the same slots for both. A slot not obtained within 5 seconds is an `AuthUnavailableException` (503, `Retry-After: 1`). The cost is measured once, on first use (usually the start-time doctor, or `build()` when a `.user` has a password). The calibration target is also the login timing base T; under `fixed(n)`, T is one timing of a hash at n iterations taken at start (n never changes). Decided: the default calibration target is 500 ms (the top of the recommended 250..500 ms range; it reaches the OWASP 600,000 iterations on current servers, and slow devices use `HashCost.calibrate(250)`). A calibration target outside 250..500 ms is not clamped: `build()` refuses it with `AuthConfigException` (DEV included). `HashCost.fixed(n)` below 50,000 iterations is a doctor FAIL in PROD and a WARN in DEV (the "hash-cost failures" of the AuthMode table).

Moving legacy formats: when a login succeeds through a hasher other than `primary` (or at a PBKDF2 cost outside the target), auth releases the hash slot and puts the rehash on a low-priority queue; the response does not wait for it. The queue holds at most 100 entries; a full queue drops the rehash at once (the login is never held up, and that user's next successful login queues it again). One daemon worker thread takes a slot of the same hash pool, hashes with `primary`, re-reads the row under the user lock and writes through `UserStore.updatePasswordHash(id, oldHash, newHash)` only when `passwordHash`, ver, enabled and roles are still those seen at login; a change in between (an admin disable, say) skips it silently. The queued plaintext password is held only as a `char[]` and wiped with `'\0'` as soon as it is processed or dropped. A hashing or store failure (a legacy `VARCHAR(32)` column too short for the PBKDF2 string, for example) is one WARN line and does not change the login result. The built-in password login (`POST /auth/login`) puts rehashes on this queue.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .hashCost(HashCost.calibrate(250))      // a slow Android device
    .hashConcurrency(2)
    .build();
PasswordHasher hasher = new Pbkdf2PasswordHasher(600_000);
String stored = hasher.hash("correct horse".toCharArray());
boolean ok = hasher.verify("correct horse".toCharArray(), stored);
boolean legacy = PasswordHashers.bcrypt().verify("rasmuslerdorf".toCharArray(),
    "$2y$10$.vGA1O9wmRjrwAVXD98HNOgsNpDczlqm3Jq7KnEd1rVAGv3Fykk1a");

// moving old accounts to PBKDF2: new hashes from the first (PBKDF2), old formats chosen by prefix
Auth migrating = Auth.builder(System.getenv("JWT_SECRET"))
    .passwordHasher(PasswordHashers.chain(new Pbkdf2PasswordHasher(600_000),
        PasswordHashers.bcrypt(),                                              // $2a$/$2b$/$2y$
        PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX),       // legacy-md5$<32 hex>
        PasswordHashers.saltedDigest("legacy-sha256", "SHA-256", DigestEncoding.HEX,
            SaltOrder.SALT_THEN_PASSWORD)))                                    // legacy-sha256$<salt>$<hex>
    .build();
```

```kotlin
val hasher: PasswordHasher = Pbkdf2PasswordHasher(600_000)
val stored = hasher.hash("correct horse".toCharArray())
val ok = hasher.verify("correct horse".toCharArray(), stored)
val cost = HashCost.calibrate(250)
println("ok=$ok rehash=${hasher.needsRehash(stored)} target=${cost.targetMillis} ms")
val chain: PasswordHasher = PasswordHashers.chain(hasher, PasswordHashers.bcrypt(),
    PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX))
val md5 = "legacy-md5$5f4dcc3b5aa765d61d8327deb882cf99"
println("legacy=${chain.verify("password".toCharArray(), md5)} rehash=${chain.needsRehash(md5)}")
```

## Key sets and rotation: KeySource, JwtKeys, KeySources

| API | Description | Express counterpart |
|---|---|---|
| `JwtKeys.builder().signingKey(kid, key).verificationKey(kid, key).build()` | A key set addressed by kid: exactly one signing key (zero or two is an `IllegalStateException`) and zero or more verification keys. `getSigningKid()`, `getSigningKey()` (a copy), `@Nullable verificationKey(kid)` (the signing key included, a copy), `getKids()`. `toString()` never shows a key | the key array of a JWKS |
| `JwtKeys.of(key)`, `JwtKeys.of(kid, key)` | A set of one key. Without a kid, the kid is the first 8 base64url characters of the key's SHA-256, so the same key has the same kid on every node and after every restart | — |
| `KeySources.rotating(JwtKeys)` | Rotation: new tokens are signed with the signing key under its kid, and older tokens whose kid is still in the set verify. Drop the old key from the set once the refresh lifetime has passed | — |
| `KeySources.fromFile(File)` | A missing file is created once with 32 bytes from `SecureRandom`, readable and writable by its owner only; an existing file is read and never overwritten (overwriting would log everyone out). Creators racing on one missing path (two `Auth` builds, two processes on one data directory) take turns through the sibling lock file `.<name>.lock` (left in place), so all of them use the one key left on disk. Read at `build()`. Exclude the file from Android auto backup, or two installations end up sharing a signing key. `KeySources.fromFile(File, AuthFileKey)` stores the key as `enc(…)` ([Encrypting file secrets](#encrypting-file-secrets-authfilekey)); PROD requires the key | — |
| `KeySource.signingKid()`, `KeySource.keys()` | Default methods. An app implementation names its kid with `signingKid()` or gives the whole set with `keys()`. A lambda `KeySource { ... }` is one key with a derived kid | — |

In PROD `build()` applies the secret-strength rules to every key of the set (a weak verification key is reported as `verification key <kid>: ...`). Decided: the rotation factory is `KeySources.rotating(JwtKeys)`, and the kid methods are `KeySource.signingKid()` and `keys()`. Auth signs new tokens with the signing key and verifies a token with the key of the set that its header kid names.

```java
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.AuthFileKey;
import com.snoworca.naruserve.utils.auth.KeySources;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import java.io.File;
import java.nio.charset.StandardCharsets;

JwtKeys keys = JwtKeys.builder()
    .signingKey("k2", "a-new-32-byte-or-longer-signing-key!".getBytes(StandardCharsets.UTF_8))
    .verificationKey("k1", "the-old-32-byte-or-longer-signing-key".getBytes(StandardCharsets.UTF_8))
    .build();
Auth rotating = Auth.builder(KeySources.rotating(keys)).build();
Auth fromFile = Auth.builder(KeySources.fromFile(new File("/var/lib/app/naru-auth.key"), AuthFileKey.fromEnv())).build();
```

```kotlin
val keys = JwtKeys.builder()
    .signingKey("k2", "a-new-32-byte-or-longer-signing-key!".toByteArray())
    .verificationKey("k1", "the-old-32-byte-or-longer-signing-key".toByteArray())
    .build()
val auth = Auth.builder(KeySources.rotating(keys)).build()
val vault = KeySource { "a-key-read-from-the-vault-32-bytes!!".toByteArray() }
```

## The auth error layer: auth.errors() and auth.errorMiddleware()

| API | Description | Express counterpart |
|---|---|---|
| `auth.errors()` | A Core `ErrorHandler`: `app.onError(auth.errors())` **last**, after the routes. It turns the auth exceptions an app handler throws into the auth JSON: `DuplicateUserException` 409 `duplicate_user`, `AuthUnavailableException` 503 with its code (for example `security_write_conflict`), the auth `IllegalArgumentException` subclass 400 `BAD_REQUEST`, `AuthRejection` with its status and code (plus `Retry-After`), `AuthStateException` 500 `INTERNAL_ERROR` (its message goes to the log, never the body: Core logs the failure once, as its `request <id> failed` ERROR whose stack trace carries the `AuthStateException` message, and auth adds no line of its own, so `auth.user(req)` on a public path gives one ERROR naming `auth.userOrNull(req)`). Any other exception (the app's own `RuntimeException`, an `IllegalArgumentException` not from auth) gets no answer and goes, as the same instance, to the next error layer; in PROD the final layer auth appends at start answers it as JSON (see [Error responses](#error-responses-and-authresponses)). The same instance per `Auth` | an `app.use((err, req, res, next) => ...)` placed last |
| `auth.errorMiddleware()` | An `ErrorMiddleware` doing the same conversion, attached with `app.use(...)`, which Core also accepts after start (Core refuses `onError` after start). Attach it again after every batch of routes added after start. Other exceptions go to `next.error(err)`. The same instance | the same |

When the very object of one of these two does not follow the last ROUTE, the doctor WARNs (even when the app's own `onError` sits there) and auth appends `auth.errorMiddleware()` after the routes at start, after the app's own error handlers. Routes added after start are checked at request time, which WARNs too: the first request after a registration change checks again, logging at most once a minute. A route added later to a router that is already mounted counts at its mount position. The auth routers (`auth.routes()`, `auth.adminRoutes()`) have their own error middleware and are not counted.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).build();
auth.install(app);
app.post("/users", (req, res) -> res.status(201).send(auth.users().create("neo", "pw-pw-pw-pw".toCharArray()).getId()));
app.onError(auth.errors());                 // last, after the routes
app.get("/late", (req, res) -> res.send("late"));   // a batch added after start
app.use(auth.errorMiddleware());            // again after every batch
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).build()
auth.install(app)
app.post("/users") { _, res -> res.status(201).send(auth.users().create("neo", "pw-pw-pw-pw".toCharArray()).id.orEmpty()) }
app.onError(auth.errors())
app.get("/late") { _, res -> res.send("late") }
app.use(auth.errorMiddleware())
```

## The first account: .user(...)

The password of `.user(name, @Nullable password, roles...)` is hashed once, in `build()`. In `onStart`, after the doctor passes, the account is created **only when no account of that name exists** (one `onUserCreated`, origin `UserOrigin.BOOTSTRAP`, no before event); an existing account is left unchanged with an INFO. **`.user` never changes the password of an existing account**, even when the password in the environment variable changes. When another node created it first and `create` throws `DuplicateUserException`, auth logs an INFO (`already exists`) and the start continues.

A null password: for an existing account it is ignored (INFO only, no doctor FAIL, no setup token). For a missing account the doctor checks it on start: only **one** `.user` holding an admin role (the built-in `admin` or a `RolePolicy` role granting `*`; roles of a database `RoleStore` and built-in roles replaced through `.replaceBuiltinRoles()` do not count) may omit its password; anything else (a non-admin role, two or more) is a FAIL, DEV included. When it passes, DEV makes a temporary password, logs it once as a WARN and creates the account; PROD logs a one-time setup token as a WARN and starts only with `.recovery(true)`, and is a doctor FAIL with recovery off. The setup token is used through `POST /auth/setup` ([Account endpoints](#account-endpoints-authroutes)); `.recoveryFromEnv()` reads `NARU_AUTH_RECOVERY`. Express counterpart: a seed script run at start.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .user("admin", System.getenv("NARU_ADMIN_PASSWORD"), "admin")   // created only when missing
    .roles(RolePolicy.builder().role("editor", "posts:read").build())
    .user("bob", System.getenv("BOB_PASSWORD"), "editor")
    .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .user("admin", System.getenv("NARU_ADMIN_PASSWORD"), "admin")
    .recovery(false)
    .build()
```

## Credential reading and auth bodies

| Transport mode | The access credential the auth middleware reads |
|---|---|
| `HEADER` (when `.transport(...)` is not called) | `Authorization: Bearer <token>` only; a `naru_at` cookie is not read and counts as absent |
| `COOKIE` | the `naru_at` cookie only; a `Bearer` is not read and counts as absent |
| `BOTH` | both; together they are multiple credentials |

Two or more `Authorization` headers (in every mode, whatever their schemes), two or more `naru_at` cookies, and under `BOTH` a Bearer together with the cookie are **multiple credentials**: 400 `invalid_request` on protected paths and credential endpoints, anonymous on public paths. The five anonymous-entry endpoints (`login`, `register`, `setup`, `refresh`, `logout`) treat duplicate `naru_at` cookies as no access cookie (two `Authorization` headers are still 400). The refresh cookie `naru_rt` is not an access credential. Without API keys enabled, an `Authorization` header of another scheme (for example `ApiKey ...`) is no credential.

On public paths (`.permit`, `.permitPrefix`) a valid token attaches the principal (`auth.userOrNull(req)`); an invalid or expired token or multiple credentials leave the request anonymous, the handler runs and the answer stays 200. A PROD request that fails the secure-transport check (secure connection, local request, DEV, `.allowInsecureTransport`) has no token read on a public path. The credential endpoints never become public through `.permit`.

A POST with a body (`Content-Length` above 0 or a `Transfer-Encoding` header) to an auth endpoint must pass the JSON media-type test of Core's `req.body` (`application/json` or `application/*+json`, with no charset or UTF-8); otherwise it is 415 `UNSUPPORTED_MEDIA_TYPE` before the body is read, logout included although it ignores its body. A bodiless refresh or logout has no `Content-Type` check. Decided: the global auth middleware makes this check, not a middleware of the auth router, and answers the 415 JSON itself (no `HttpException` is thrown). On the anonymous-entry endpoints it runs right after the secure-transport and duplicate-`Authorization` checks; on the other auth endpoints (`/auth/password`, the admin POSTs) it runs after authentication (no credential is a 401 first). So the auth router's shape in `app.registrations()` stays as it is and the check runs before every handler of the router.

Express equivalent: `getToken` of `express-jwt` (the header or `req.cookies` of `cookie-parser`) and the type check of `express.json()`.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .transport(TokenTransport.COOKIE)   // reads the naru_at cookie only
    .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).transport(TokenTransport.BOTH).build()
```

## Login throttling store: LoginThrottle

| API | Description | Express counterpart |
|---|---|---|
| `spi.LoginThrottle` | `long retryAfter(key)` (0 allows; a positive value is the remaining block in milliseconds), `long recordFailure(key)` (the new block in milliseconds, 0 while not blocked), `void reset(key)`. The defaults `retryAfter(key, max, windowMillis)` and `recordFailure(key, max, windowMillis)` go to the one-argument methods (the implementation's own limits), and the default `boolean usesLimitArguments()` is false. All synchronous and `throws Exception`. After the first public release it gains default methods only | the store of `rate-limiter-flexible` |
| `spi.ForwardingLoginThrottle` | The public abstract base class of decorators: every method, the defaults and `usesLimitArguments()` included, goes to the wrapped throttle, so overriding a few never makes the declaration lie | — |
| `InMemoryLoginThrottle` | The in-memory throttle (lost on restart). It honours the limit arguments (`usesLimitArguments()` is true); the one-argument methods allow 5 failures per 15 minutes (`builder().limits(max, windowMillis)`). The key cap is 100,000 on a JVM and 10,000 on Android (`builder().maxKeys(n)`); at the cap the key that failed longest ago is dropped, with one WARN the first time. `builder().timeSource(...)`, `builder().logger(...)` | the MemoryStore of `express-rate-limit` |

The builder's `.loginThrottle(throttle)` sets the throttle auth uses; without it the default follows the user store (the snapshot file beside a `FileUserStore`, otherwise in memory). With several nodes, name an app-provided shared store ([Connecting your own database](#connecting-your-own-database)). `MULTI_INSTANCE` refuses an in-memory throttle and the in-memory fallback in `build()`. auth counts failed password logins and failed API keys with it (API keys call only the limit-argument methods and never `reset` after a success).

**Login limits**: `POST /auth/login` counts three keys over the normalized login name (NFKC, then `toLowerCase(Locale.ROOT)`), counting an unknown name exactly like an existing one so a 429 never tells whether an account exists: `(user, IP)` 5 per 15 minutes, IP 20 per 15 minutes (requests carrying a valid trusted-device token are exempt) and user-only 10 per 15 minutes (requests without a trusted device only). The IP is `req.remoteAddress()` after proxy trust, and the IPv6 addresses of one `/64` share a key. Over a limit the login answers 429 `too_many_attempts` with `Retry-After` (seconds) at once, without hashing and without sleeping. Only password login is limited, so failed logins for a service account's name never block that account's API keys. The builder's `.loginFailureLimits(perUserAndIp, perIp, perUser, windowMillis)` replaces the three limits and the window together (each limit at least 1, the window at least one second, else `build()` throws `AuthConfigException`). Express counterpart: the "login brute force" example of `rate-limiter-flexible` (a name+IP and an IP limiter).

**File defaults**: with a `FileUserStore` and no `.loginThrottle(..)` or `.deviceTrust(..)`, auth keeps `<name>-login-throttle.json` and `<name>-device-trust.json` in the same directory (`users-login-throttle.json` and `users-device-trust.json` beside `users.json`). These two stores are `FileLoginThrottle` and `FileDeviceTrust`, package-private classes of `utils.auth` with no public constructor, so an app gets them only this way (auth builds them as `(File, TimeSource, Logger, AuthFileKey)`, and the constructor reads the file, so a corrupt one fails `build()`). A file appears only when its store has something to write, and only when something changed: the throttle file with the snapshot written every 60 seconds and at stop, the device-trust file at its first invalidation or snapshot, so a new install shows only `users.json` and `users-refresh-tokens.json` at first. They keep the login failure counters and the trusted-device state (failure counts and invalidations) in those files so that they survive a restart: the throttle reloads its last snapshot (a crash can lose the failures since that snapshot), and a device invalidation is on disk before the failing login is answered. The login counters are not written per failed login: a snapshot is written every 60 seconds when something changed (and once when auth stops). Keys are kept only as their SHA-256 (base64url), so neither memory nor the file holds a login name or an IP, and the 10-minute cleanup forgets keys whose last failure is older than 24 hours (or than the longest configured window when that is longer). The cleanup applies to the in-memory default too and never touches a throttle given with `.loginThrottle(..)`, in memory or app-provided (an app store deletes its own old rows). The device trust state, in memory and in the file default alike, has a token cap like the login throttle (100,000 on a JVM, 10,000 on Android): at the cap the expired tokens go first, then the token not invalidated whose window started longest ago, and the first drop logs one WARN; only when every entry is an invalidation is the one expiring first dropped. A device-token invalidation is rare and security-relevant, so it is on disk before that failed login is answered. The files are `{"format":"naru-login-throttle","version":1,"keys":[...]}` and `{"format":"naru-device-trust","version":1,"tokens":[...]}`, written by the `AtomicFiles` protocol; an unknown version or a corrupt file fails `build()` naming the version and is not overwritten. Every `Auth` built over one `FileUserStore` shares the two stores. These shared stores (the refresh default too) read the `.timeSource` and the logger (the app's logger when there is no `.logger`) of the `Auth` most recently built or installed (`install(app)`) over that `FileUserStore`, so they are never tied to the clock of an earlier `Auth` or to the logger of an app that has stopped. Both defaults honour the limit arguments (`usesLimitArguments()` is true).

```java
LoginThrottle counted = new ForwardingLoginThrottle(new InMemoryLoginThrottle()) {
    @Override
    public long recordFailure(String key, int max, long windowMillis) throws Exception {
        System.out.println("failed: " + key);
        return super.recordFailure(key, max, windowMillis);
    }
};
long blockedFor = counted.retryAfter("alice|203.0.113.7", 5, 15 * 60_000L);
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
        .loginThrottle(counted)
        .loginFailureLimits(3, 20, 10, 15 * 60_000L)
        .build();
```

```kotlin
val counted = object : ForwardingLoginThrottle(InMemoryLoginThrottle()) {
    override fun recordFailure(key: String, max: Int, windowMillis: Long): Long {
        println("failed: $key")
        return super.recordFailure(key, max, windowMillis)
    }
}
val blockedFor = counted.retryAfter("alice|203.0.113.7", 5, 15 * 60_000L)
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .loginThrottle(counted)
    .loginFailureLimits(3, 20, 10, 15 * 60_000L)
    .build()
```

## Standalone JWT: Jwt, JwtClaims, JwtException

`utils.auth.jwt` signs and verifies HS256 JSON Web Tokens without an `Auth` (device pairing, signed links). Express counterpart: `jwt.sign(payload, key)` / `jwt.verify(token, key, { algorithms: ['HS256'] })` of `jsonwebtoken`.

| API | Meaning |
|---|---|
| `Jwt.builder(JwtKeys)` → `Jwt.Builder` | `issuer(@Nullable String)`, `audience(@Nullable String)` (written into tokens without one and required on verification), `leeway(long millis)` (default 60 s; below 0 or above 5 minutes is an `IllegalArgumentException`), `maxTokenBytes(int)` (default 8 KiB), `timeSource(TimeSource)`, `build()` |
| `String sign(JwtClaims)` | The header is exactly `alg` (`HS256`), `typ` (the claims' type, `JWT` when none) and `kid` (the set's signing kid). `iat` is now unless set. A token above the size limit is an `IllegalArgumentException` |
| `JwtClaims verify(token)`, `JwtClaims verify(token, @Nullable expectedType)` | In order: size, three canonical base64url parts, a strict JSON header whose `alg` is exactly `HS256` (never chosen from the header) and that has no `crit`, the key of its `kid`, the constant-time signature, then the claims: `exp` required and an integer, `nbf`/`iat` not further ahead than the leeway, `typ` equal to `expectedType` when given, and the configured issuer and audience. A token without `kid` is accepted only when the set has one key. `jku`, `jwk` and `x5u` are ignored and never fetched |
| `JwtClaims.builder()` → `JwtClaims.Builder` | `subject`, `type`, `issuer`, `audience(String...)`, `issuedAt`, `notBefore`, `expiresAt` (required), `tokenId`, `claim(name, String / long / boolean / List<String>)`. Getters: `getSubject()`, `getType()`, `getIssuer()`, `getAudience()`, `getIssuedAt()`, `getNotBefore()`, `getExpiresAt()`, `getTokenId()`, `getString(name)`, `getLong(name)`, `getBoolean(name)`, `getStringList(name)`, `getClaimNames()` |
| `JwtException` (checked) | `getReason()`: `JwtException.Reason` `MALFORMED`, `BAD_ALGORITHM`, `UNKNOWN_KEY`, `BAD_SIGNATURE`, `EXPIRED`, `NOT_YET_VALID`, `WRONG_TYPE`, `WRONG_ISSUER`, `WRONG_AUDIENCE`, `TOO_LARGE`. The message never holds the token or a key |

Times are epoch milliseconds like every auth timestamp; a token stores whole seconds, so a time is rounded down to the second when it is signed. Auth's own access tokens are such JWTs with header `typ` `at+jwt` and the claims `sub`, `ver`, `fam`, `iat`, `exp`, `jti`; a protected request whose access token does not verify gets 401 `invalid_token`.

```java
Jwt jwt = Jwt.builder(JwtKeys.of("k1", "0123456789abcdef0123456789abcdef".getBytes())).issuer("hub").build();
String t = jwt.sign(JwtClaims.builder().subject("device-7").type("device")
        .expiresAt(System.currentTimeMillis() + 3_600_000L).build());
try {
    JwtClaims c = jwt.verify(t, "device");
    System.out.println(c.getSubject());
} catch (JwtException e) {
    System.out.println("refused: " + e.getReason());
}
```

```kotlin
// `key` (a ByteArray of 32 bytes or more) and `now` (System.currentTimeMillis()) as in the Java block above
val jwt = Jwt.builder(JwtKeys.of("k1", key)).issuer("hub").build()
val t = jwt.sign(JwtClaims.builder().subject("device-7").type("device").expiresAt(now + 3_600_000L).build())
val subject: String? = jwt.verify(t, "device").subject
```

## The protected range: protectOnly and upgrades

With `.protectOnly(prefix...)`, default deny applies only below the listed prefixes and below the auth mounts (the mount path, default `/auth`, which holds the admin routes). The range is judged on a path normalized wider than the router: percent-decoded, lower case, repeated slashes merged; a `.` or `..` segment or an undecodable escape counts as protected. Paths outside the range get optional authentication like `.permitPrefix` paths: a valid token gives a principal, an invalid or expired one leaves the request anonymous, and a guard there still answers 401 without a principal.

- Every protocol upgrade is protected, whatever `.permit` and `.protectOnly` say: an `Upgrade` header, or an `upgrade` token on any `Connection` header line (a WebSocket or an h2c upgrade). `.permitWebSocket(path)` opens WebSocket upgrades to exactly that path (compared with the decoded WebSocket `req.path()`).
- Under `.protectOnly`, a registration whose path is a regular expression or an array cannot be judged: before start it is a doctor FAIL (`protectOnly range`), after start the registering call (`app.get(Pattern, ...)`, a route on a mounted router, `app.use(path, router)`) throws `IllegalStateException` and nothing is served. Without `.protectOnly` nothing is refused.
- The doctor WARNs once for every route and WebSocket route outside the range, and the start logs `protected: /api, /auth` (the configured mount path in place of `/auth`).

Express equivalent: an authentication middleware mounted on one prefix only, as in `app.use('/api', expressjwt(...))`.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).protectOnly("/api").permitWebSocket("/live").build();
auth.install(app);
app.staticFiles("/", new File("dist"));   // public: outside the range
app.get("/api/users", (req, res) -> res.json(Collections.emptyList()));   // protected
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).protectOnly("/api").permitWebSocket("/live").build()
```

## describe() and the startup log

`auth.describe()` returns a human-readable, multi-line summary of the effective configuration: mode, profile, deployment, transport, the mount and admin paths, the permitted paths with their methods, the protected range (`protected: ...`), the WebSocket paths opened by `.permitWebSocket`, and the user, refresh-token and API key stores. It never holds a key, password or token. The text is for people and may change between releases; do not parse it.

The start also logs: that `.user` accounts are only created when missing and never overwrite an existing account or its password; that a DEV temporary signing key is lost on restart; one WARN per start while `.recovery(true)` is on; and the `.allowInsecureTransport` reason as a WARN on every start. The one-time setup token and the DEV temporary password each appear in exactly one startup log line and nowhere else.

Express equivalent: the configuration summary an app prints with `console.log` on start.

```java
System.out.println(Auth.builder(System.getenv("JWT_SECRET")).protectOnly("/api").build().describe());
```

```kotlin
// `auth`: the Auth built above
println(auth.describe())
```

## Audit events: AuditEvent, AuditSink, AuditSinks

| API | Meaning |
|---|---|
| `AuditSink` (`@FunctionalInterface`, `void record(AuditEvent) throws Exception`) | Set with `.audit(sink)`; called on the request thread. A sink that throws never changes the response; auth logs the failure |
| `AuditEvent` (immutable, final) | `getTimestampMillis()`, `getType()`, `getOutcome()` (`AuditEvent.Outcome` `SUCCESS`, `DENIED`, `FAILURE`), `@Nullable getActorId()`, `@Nullable getTargetId()`, `getAction()`, `@Nullable getSourceIp()` (`req.remoteAddress()`), `@Nullable getRequestId()` (`req.requestId()`), `@Nullable getReason()`, `getDetails()` (for example `apiKeyId` for a key actor, `serviceAccount`). It holds ids only, never a username, e-mail, password, token, cookie value, key secret or `Authorization` value. `AuditEvent.builder(type, action)` → `AuditEvent.Builder` |
| Types | `AuditEvent.RECOVERY_USED` (`recovery_used`), `GRANT_DENIED`, `SELF_CHANGE_DENIED`, `SECURITY_WRITE_CONFLICT`, `USER_UPDATE_FAILED` (`user_update_failed`), `API_KEY_ISSUED`, `API_KEY_REVOKED`; more may be added |
| `AuditSinks.logger(Logger)`, `AuditSinks.all(AuditSink...)`, `AuditSinks.none()` | One INFO line per event / every sink in order (one failing sink does not stop the others) / drop everything (the default) |

A security write the store did not apply (503 `security_write_conflict`) on an auth-router path emits one `security_write_conflict` event; a store default called directly outside auth emits none.

Express equivalent: app code that writes security events to a logger such as `winston`.

```java
import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.audit.AuditSinks;

Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
        .audit(AuditSinks.logger(new ConsoleLogger()))
        .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).audit { e -> println("${e.type} ${e.actorId}") }.build()
```

## Refresh-token storage: RefreshTokenStore

`spi.RefreshTokenStore` (user-implementable, synchronous `throws Exception`) keeps one `RefreshRecord` per refresh token so a token is rotated once, its reuse is detected and its family revoked: `void save(RefreshRecord)`, `@Nullable RefreshRecord find(tokenId)`, `boolean markUsed(tokenId, nowMillis)` (atomic: of concurrent calls for one unused, unrevoked record exactly one is true), `int revokeFamily(familyId)` and the default `int purgeExpired(nowMillis)` (deletes nothing by default). `RefreshRecord.builder()` → `RefreshRecord.Builder`: `tokenId`, `familyId`, `userId`, `tokenHash`, `createdAt`, `expiresAt`, `@Nullable usedAt`, `revoked`.

| Store | When it is used |
|---|---|
| `InMemoryRefreshTokenStore` | The default beside `InMemoryUserStore`, and beside any other user store under `SINGLE_INSTANCE` (the doctor then WARNs once that a restart logs every user out) |
| `FileRefreshTokenStore(File)` | The default beside a `FileUserStore`: `<name>-refresh-tokens.json` in the same directory (`users-refresh-tokens.json` for `users.json`), one store shared by every `Auth` over that `FileUserStore`, `{"format":"naru-refresh-tokens","version":1,...}`; an unknown version fails `build()` naming it and is not overwritten. So a long-lived session does not grow the file, a family keeps its current record and its 32 most recently used ones; an older used record is dropped when the next one is saved. Presenting a kept record again still revokes the whole family; an older token is refused with a plain 401, like an unknown one. Saving a record also drops every revoked record (a logged-out family or one revoked for reuse) and every record already expired, which are refused with the same 401 either way, so login and logout churn does not grow the file. Every session the file holds can always refresh again: when the limit would no longer leave room for one more rotation of every live family, each family keeps only its most recently used record (the predecessor of its current token, whose reuse still revokes the family) instead of 32, so a rotation needs no new bytes. A new sign-in (login, register, setup or password change, which start a new family) is refused with 503 until sessions expire or log out when, even in that compact form, the size limit (8 MB by default) would not hold one more rotation of every session including the new one, or the file would come into the last eighth of its limit, while existing sessions keep refreshing; raise the limit with `new FileRefreshTokenStore(file, maxFileBytes, logger)` or use an app-provided `RefreshTokenStore` |
| An app-provided `RefreshTokenStore` | The store the nodes share under `MULTI_INSTANCE`, named with `.refreshTokens(..)` ([Connecting your own database](#connecting-your-own-database)) |

Auth writes records only when tokens are issued, rotated or revoked and when the cleanup removes expired ones, never on a failed login. Every 10 minutes a daemon thread calls `purgeExpired(now)` and, with `.apiKeys(store)`, `ApiKeyStore.deleteExpired(now)` on the same run; it stops in `onStop`.

Express equivalent: the session store of `express-session` (`connect-redis`, `connect-pg-simple`).

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
        .users(new FileUserStore(new File("data/users.json"), AuthFileKey.fromEnv()))   // users-refresh-tokens.json goes beside it
        .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).refreshTokens(InMemoryRefreshTokenStore()).build()
```

## API key storage: ApiKeyStore and the key types

`apikey.ApiKeyStore` (user-implementable, synchronous `throws Exception`, arguments never null) is fixed before the first release: `@Nullable findByPrefix(prefix)`, `@Nullable findById(keyId)`, `listByOwner(ownerUserId)` (dead keys too), `insertIfUnderLimit(StoredApiKey, int maxLiveKeys, long nowMillis)` (only while the owner has fewer live keys, that is unexpired and with ownerVer at least the new key's; an existing prefix throws `ApiKeyPrefixCollisionException`), `deleteByIdAndOwner`, `deleteById`, `deleteAllByOwner`, `deleteStaleByOwner(owner, v)` (ownerVer < v), `deleteExpired(nowMillis)`, and the defaults `deleteFutureByOwner(owner, v)` (ownerVer > v, through `listByOwner` and `deleteById`), `updateLastUsed(keyId, lastUsedAtMillis)` (no-op), `supportsAtomicLimitInsert()` (false) and `countKeysWithAdminScopes()` (-1: cannot count).

| Type | Meaning |
|---|---|
| `StoredApiKey` | The store row, the only type with the secret hash: `builder()` / `toBuilder()` → `StoredApiKey.Builder` with id, prefix, secretHash (64 lower-case hex), ownerUserId, ownerVer (`long`), name, scopes and createdAt required (`build()` throws `IllegalStateException` naming a missing one) and `@Nullable` expiresAt, lastUsedAt, issuedByUserId. `toString()` shows `nk_<prefix>_***` and never the hash |
| `ApiKey` | The app-facing copy without any secret or hash: `ApiKey.of(stored, status)`, `getCreatedAt()` (`long`), `@Nullable getExpiresAt()`, `@Nullable getLastUsedAt()`, `@Nullable getIssuedByUserId()`, `getStatus()` |
| `IssuedApiKey` | `getKey()` and, this one time, `getPlainKey()` (`nk_<prefix8>_<secret32>`); `toString()` shows `nk_<prefix8>_***` |
| `ApiKeyStatus` | `ACTIVE`, `EXPIRED`, `STALE`, computed from the owner's `ver` read from the store (not the cache); a key whose ownerVer is above that `ver` is `STALE` too. Values may be added, so a Kotlin `when` needs an `else` branch. Under `MULTI_INSTANCE` a stale key whose ownerVer exceeds the read ver still counts toward `maxKeysPerUser` (a late replica read may hide a valid key); a stale key with ownerVer below the ver does not. Auth endpoints write it as `active`, `expired`, `stale`; an app's own codec writes the enum (Gson: `ACTIVE`) |

A key is referenced in logs only by its prefix. `.apiKeys(store)` turns keys on: deleting a user deletes its keys between the memberships and the row, and the cleanup deletes expired keys. Key authentication on requests is in [API key authentication](#api-key-authentication); the app facade `auth.apiKeys()` and `InMemoryApiKeyStore` are in [Issuing API keys: auth.apiKeys()](#issuing-api-keys-authapikeys).

Express equivalent: the app's API key table that the verify callback of `passport-headerapikey` reads.

```java
StoredApiKey row = StoredApiKey.builder().id("k1").prefix("Ab3dE5gH")
        .secretHash("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
        .ownerUserId("42").ownerVer(0L).name("ci-bot").scopes(Collections.singleton("posts:read"))
        .createdAt(System.currentTimeMillis()).build();
ApiKey shown = ApiKey.of(row, ApiKeyStatus.ACTIVE);   // no hash
```

```kotlin
// `row`: the StoredApiKey built in the Java block above
val expires: Long? = ApiKey.of(row, ApiKeyStatus.ACTIVE).expiresAt
```

## Auth cookies

In `COOKIE` and `BOTH` modes auth uses `naru_at` (the access token: HttpOnly, Path=/), `naru_rt` (the refresh token: HttpOnly, Path = the mount path, default `/auth`, read only by refresh and logout and never as an access credential), `naru_csrf` (readable by scripts, Path=/) and `naru_device`; all are SameSite=Strict and Secure exactly when the request is secure. `.mountPath("/")` is refused in those modes, two or more `naru_rt` cookies count as none, and a cookie is cleared with the same Path and Domain it was set with. `.cookies(c -> c.domain("example.com"))` sets shared attributes. Changing Path or Domain through `.cookies()` requires the app to send `Max-Age=0` for the old Path and Domain during `refreshTtl`, because auth cannot detect or clear the old cookies. Cookie values never reach a log, an exception message or an audit event.

Express equivalent: the options of `res.cookie(name, value, { httpOnly: true, sameSite: 'strict', secure: true, domain })`.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .transport(TokenTransport.COOKIE)
    .cookies(c -> c.domain("example.com"))   // shared by naru_at, naru_rt, naru_csrf and naru_device
    .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .transport(TokenTransport.COOKIE)
    .cookies { c -> c.domain("example.com") }
    .build()
```

## The grant rule of admin writes

Every admin write re-reads the actor and the target from the store and allows the write only when every scope the target newly gains is covered by the actor's effective scopes and, for a modify, password reset, disable, delete, member removal or role removal, the target's current scopes are covered too; otherwise 403 `insufficient_scope`. An actor that changes its own roles or groups, or disables or deletes itself, is refused with 403 `insufficient_scope` as well, and the refusal leaves a `self_change_denied` audit event naming the actor as actor and target. The app's own paths (`auth.users()`) are trusted and not checked. The admin endpoints below apply it to every write.

## Tokens: TokenService and TokenPair

`auth.tokens()` is the `TokenService` of this auth. `issue(user)` makes an access and refresh token pair (`TokenPair`) in a new refresh family and saves the refresh record (login, registration, setup and password change take this path). `refresh(refreshToken)` checks the refresh token, the user's current `ver` and `enabled` flag and the stored record, marks the presented record used (an atomic compare-and-set), and returns a new pair in the same family. Presenting a refresh token that was already used revokes its whole family (reuse detection, RFC 9700 4.14). Every refusal is `AuthRejection(401, "invalid_refresh_token")` and issues nothing. `verifyAccess(token)` returns the claims (`JwtClaims`) of an access token, or 401 `invalid_token`. Store failures are `AuthUnavailableException` (503). Express counterpart: the `jwt.sign` calls of a passport-jwt login route plus a refresh-token table.

| Token | Header | Claims | Signing key |
|---|---|---|---|
| access | `alg` HS256, `typ` `at+jwt`, `kid` | exactly `sub`, `ver`, `fam`, `iat`, `exp`, `jti` (no scope or roles claim: scopes are read per request) | HMAC(secret of the kid, `"naru-access"`) |
| refresh | `typ` `rt+jwt` | `sub`, `ver`, `fam`, `jti`, `iat`, `exp` | HMAC(secret of the kid, `"naru-refresh"`) |
| device token / device-name fingerprint | — | — | HMAC(secret, `"naru-device"`) / HMAC(secret, `"naru-device-fp"`) |

Each purpose has its own key, so a token of one purpose never verifies as another, even with a forged `typ`. Keys are derived for every kid of the key set (verification-only kids included), so tokens signed before a rotation still verify under their old kid. `TokenPair`: `getAccessToken()`, `getExpiresIn()` (seconds), `getRefreshToken()`, `getRefreshExpiresIn()` (seconds), `getTokenType()` (`"Bearer"`). The JSON field names are those of the OAuth 2.0 token response in camel case (`accessToken`, `expiresIn`, `refreshToken`, `refreshExpiresIn`, `tokenType`), and `toString()` holds no token. Set the lifetimes with the builder's `.accessTtl(millis)` and `.refreshTtl(millis)` (at least one second); unset, they are the values in the AuthProfile table above. Access tokens are verified with a 60-second clock leeway (`Jwt.DEFAULT_LEEWAY_MILLIS`, not configurable on `Auth`), so a token still answers 200 for up to 60 seconds after its expiry; a test of expiry moves its clock past `accessTtl` plus 60 seconds.

Refresh reuse detection and the token-format seam (`TokenCodec`) are in [Refresh reuse detection and TokenCodec](#refresh-reuse-detection-and-tokencodec) below.

ver only increases. A logout of all devices, a password change, an admin password reset, a disable and a recovery raise `ver` by exactly 1, and that one bump revokes every access, refresh and device token, WebSocket connection and API key of the user. Role, group and profile changes and the rehash at login never change `ver`; their effect reaches requests within the principal cache TTL below. Reissue API keys after a password change.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).accessTtl(10 * 60_000L).build();
AuthUser alice = auth.users().create("alice", "correct horse".toCharArray());
TokenPair pair = auth.tokens().issue(alice);
TokenPair next = auth.tokens().refresh(pair.getRefreshToken());   // the first refresh token is now used
JwtClaims claims = auth.tokens().verifyAccess(next.getAccessToken());
```

```kotlin
// `auth`: the Auth built above
val alice = auth.users().create("alice", "correct horse".toCharArray())
val pair = auth.tokens().issue(alice)
val claims = auth.tokens().verifyAccess(pair.accessToken)
println("${claims.subject} expires in ${pair.expiresIn} s")
```

## Principal cache: userCacheTtl

The auth middleware builds the principal from the user row, the expanded groups, roles and scopes, and caches it per user id for `userCacheTtl` (5 seconds by default; `.userCacheTtl(millis)`; 0 reads the stores on every request). A token whose `ver` or `enabled` flag no longer matches gets 401 `invalid_token`. A change made through auth (the admin endpoints, `auth.users()`, a password change) is seen at once on that node: a disable, a delete or a `ver` bump forgets that user, and a role or group change clears the whole cache. A change written straight to the store (another node's included) is seen within the TTL. The grant rule of admin writes never reads this cache; it re-reads the store. With `.statelessAccess(true)`, a route without a guard gets a minimal principal and no store read (only `getId()` is meaningful: the name is `""`, the roles, scopes and attributes are empty, `isLoaded()` is false), and a guard (`auth.require(...)` and the others) promotes it through the cache and checks `ver` and `enabled`. Such routes therefore see a revocation only after the access token lifetime; doctor reports this as a WARN.

Express equivalent: an `lru-cache` that keeps the `deserializeUser` result for a few seconds.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .userCacheTtl(2_000)        // default 5000 ms; 0 reads the store on every request
    .statelessAccess(true)      // unguarded routes use getId() only
    .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).userCacheTtl(0).statelessAccess(true).build()
app.get("/feed") { req, res -> res.send(auth.userOrNull(req)?.id ?: "anonymous") }
```

## WebSocket: auth.webSocket(handler)

`app.ws("/ws", auth.webSocket(handler))` wraps the handler. Authentication and the Origin check still happen before the 101 response (an upgrade without a token gets 401 JSON and the handler never runs). After 101, a connection opened with an access token is closed with `close(1008, "token expired")` at the token's `exp`. Every connection is closed with `close(1008, "access revoked")` when its principal, re-read every `userCacheTtl`, is gone, disabled, at another `ver`, or has other roles or scopes, and at once on this node when auth itself disables or deletes the user or changes its password. A connection opened with an API key is closed with `close(1008, "api key expired")` at the key's `expiresAt` (never for a key without expiry), and every recheck also re-reads the key row: a key that was deleted, has expired, is stale, has an ownerVer above the re-read `ver`, or belongs to an owner with a pending marker closes the connection with `close(1008, "access revoked")` (a key whose ownerVer is above is deleted off the request path under `SINGLE_INSTANCE` and kept under `MULTI_INSTANCE`). When auth deletes a key on this node (`auth.apiKeys().revoke`, a key endpoint's delete), the connections opened with it close at once; a key deleted on another node closes them at the next recheck, within `userCacheTtl`. The minimal principal of `.statelessAccess(true)` is promoted at the first recheck, which does not count as a change. The wrapped handler reads the principal with `auth.user(req)`. Doctor warns when any `WEBSOCKET` registration has a handler that `auth.webSocket` did not wrap (it counts the routes registered with a wrapped handler, not the `auth.webSocket` calls: a wrapper never registered does not count, and one wrapper registered on two routes wraps both), and, with `COOKIE` or `BOTH` transport and WebSocket routes, asks you to check that the Core WebSocket `allowedOrigins` is not a wildcard (Core has no public reader for that setting yet, so this never fails the start). Express counterpart: an express-ws handler that checks the token on connect and sets a timer for its expiry.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).build();
auth.install(app);
app.ws("/ws", auth.webSocket((req, ws) -> ws.sendText("hi " + auth.user(req).getId())));
```

```kotlin
// `auth`: the Auth built above
app.ws("/ws", auth.webSocket { req, ws -> ws.sendText("hi " + auth.user(req).id) })
```

## Account lockout: Lockout

`.lockout(Lockout.after(5).forMinutes(15))` turns on the optional hard lockout. After that many failed password logins of one account, its password login is refused for that long and answered exactly like a wrong password, 401 `invalid_credentials`, so the refusal does not tell whether the account exists or is locked. It applies to password login only: trusted-device logins and API-key authentication are never locked. An admin releases a lock with `DELETE /auth/admin/users/:id/lockout` (204), which clears the lock and the user's user-only login throttle key and keeps the IP keys and the (user, IP) keys. It is off by default because a lockout lets anyone lock a victim out on purpose; the default protection is the login throttle. `POST /auth/login` counts the failures (one per wrong password of an existing account), and a correct password of a locked account still runs the hash step and the failure floor and gets the same 401. A successful login clears the consecutive failures; a login carrying a trusted device token ignores the lock. Express counterpart: the `rate-limiter-flexible` "consecutive failures by username" recipe.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .lockout(Lockout.after(5).forMinutes(15))   // off by default
    .build();
```

```kotlin
val lockout = Lockout.after(5).forMinutes(15)
val auth = Auth.builder(System.getenv("JWT_SECRET")).lockout(lockout).build()
println("${lockout.failures} failures, ${lockout.durationMillis} ms")
```

## API key usage time

After a successful key authentication, auth calls `ApiKeyStore.updateLastUsed(keyId, lastUsedAtMillis)` at most once per key per minute of `TimeSource` time, on a background thread off the request path. A failure or a full queue only logs a WARN and never affects the request (`lastUsedAt` is just a little older). The default does nothing. Every key-authenticated request calls this recorder.

## Admin endpoints: auth.adminRoutes()

`auth.install(app)` mounts `auth.adminRoutes()` at the mount path plus `/admin` (`/auth/admin` by default). v1 has 15 admin endpoints, plus 3 key routes with `.apiKeys(store)`. The Name column of the table below gives each endpoint a short name: A1–A6 are the user endpoints and G1–G9 the group endpoints, and the rest of this page uses these names too (for example admin A2 is `POST /auth/admin/users`). The group-role route (G9 `PUT /groups/:id/roles`) is v2 and is not registered. Every route carries its own scope guard, which answers 403 `insufficient_scope` before the handler reads the target (401 without a credential); every write that passes the guard then passes the grant rule above. `auth-users:*` covers read and write, and the built-in `admin` (`*`) passes every guard. Bodies are read with the app's JSON codec (Gson in `NaruServe`). The user body is the hash-free `{"id","username","enabled","roles","attributes","serviceAccount"}`. Express counterpart: an admin `express.Router()` with a `requireScope(...)` middleware on each route.

| Name | Method and path | Scope | Success |
|---|---|---|---|
| A1 | `GET /users?offset=&limit=&search=` | `auth-users:read` | 200 `{"items","total","offset","limit"}` (limit 50 by default, at most 200) |
| A2 | `POST /users` `{"username","password","roles","attributes","serviceAccount"}` | `auth-users:write` | 201 user + `Location`; an empty `password` (`""`) is 400 `BAD_REQUEST` with the message `password must be a non-empty JSON string`; `serviceAccount` true with a `password` is 400 `service_account_no_password`; an API-key actor sending a `password`, or creating without `"serviceAccount": true`, is 403 `api_key_not_allowed` first (before the scope guard too); assigning a role the `RolePolicy` does not declare logs a WARN |
| A3 | `GET /users/:id` | `auth-users:read` | 200 user (`roles` is the user's own roles; the roles its groups confer show in `auth.user(req).getRoles()`, and its groups in A6) |
| A4 | `PATCH /users/:id` `{"username","password","enabled","roles","attributes"}` | `auth-users:write` | 200 user; an empty `password` (`""`) is 400 `BAD_REQUEST` with the message `password must be a non-empty JSON string`; a `password` for a service account is 400 `service_account_no_password`; an API-key actor sending a `password`, or `"enabled": true` for the key's own owner, is 403 `api_key_not_allowed` first (before the scope guard too) |
| A5 | `DELETE /users/:id` | `auth-users:write` | 204 |
| A6 | `GET /users/:id/groups` | `auth-users:read` | 200 array of direct group ids |
| — | `DELETE /users/:id/lockout` | `auth-users:write` | 204 |
| G1 | `GET /groups` | `auth-groups:read` | 200 array of group ids |
| G2 | `POST /groups` `{"id"}` | `auth-groups:write` | 201 + `Location`; an existing id is 409 `CONFLICT`, an invalid one 400 |
| G3 | `GET /groups/:id` | `auth-groups:read` | 200 `{"id","members","parents","children"}` |
| G4 | `DELETE /groups/:id` | `auth-groups:write` | 204 |
| G5 / G6 | `PUT` / `DELETE /groups/:id/members/:userId` | `auth-groups:write` | 204, the same on repeat |
| G7 / G8 | `PUT` / `DELETE /groups/:id/parents/:parentId` | `auth-groups:write` | 204 (G8 the same on repeat); a cycle or a chain that is too deep is 409 `group_cycle` |
| keys | `GET /apikeys?ownerUserId=` | `auth-apikeys:read` | 200 array of keys (no hashes, each with its `status`, disabled owners included); no `ownerUserId` is 400 `BAD_REQUEST`, an unknown owner 404 |
| keys | `POST /apikeys` `{"ownerUserId","name","scopes","expiresInDays"?,"password"}` | `auth-apikeys:write` | 201 key plus the plaintext `key` (shown once); the check order is in [Admin key issuance](#api-key-issuance-endpoints-pats-and-admin-issuance) below |
| keys | `DELETE /apikeys/:id` | `auth-apikeys:write` | 204; the grant rule protects the key's owner (target protection, 403 `insufficient_scope`); an unknown id is 404 |

Unknown user and group ids answer 404 `NOT_FOUND`. A2 and A4, which hash a password, and `POST /apikeys`, which checks the caller's password, are registered with `methodAsync`, so a request waiting for a hash slot holds no worker thread. An A2 `attributes` map holding a security field name (`passwordHash`, `ver`, `enabled`, `roles`) is 400 `BAD_REQUEST` before anything is created.

A4 checks the grant rule once over every changed field first. It then applies the username (the rename path: normalized, and the same name is a no-op), the password (`changePassword` with the re-read `ver`, never retried), `enabled` (false is `setEnabled(false)` then `bumpTokenVersion`), `roles` and `attributes` (merged; a `null` value removes the key), in that order. It emits one `onUserUpdated` (origin `ADMIN`) with what was applied, plus `onPasswordChanged` when the password was applied. When a step fails, A4 answers itself with the auth error body plus a top-level `applied` array: the A4 request field names already applied, in application order. A taken name is 409 `duplicate_user`, a lost password race 409 `write_conflict`, an `AuthUnavailableException` 503 with its own code (`security_write_conflict` for an unconfirmed security write), and any other store exception 503 `SERVICE_UNAVAILABLE`. An `AuthRejection` or `IllegalArgumentException` from a step after the password is also 503 `SERVICE_UNAVAILABLE`. A `security_write_conflict` leaves that audit event; any other failure after the password leaves a `user_update_failed` audit event. Example: `{"error":{"code":"write_conflict","message":"Conflict","requestId":"…"},"applied":["username"]}`. With `.responses(AuthResponses)` that body is used instead and carries no `applied`.

G4 (group delete) and G8 (edge removal): when the actor covers every scope the group and its ancestors confer, the write goes ahead without counting members (a key actor never covers `*`). Otherwise the direct and indirect members are counted up to 1,001: more than 1,000 is 409 `group_too_large`, and otherwise the actor must cover each one's current scopes. An actor that is itself affected (through its own groups) is refused under the self rule. G5 and G7 need the actor to cover what the group and its ancestors confer (G7 when the child group has members).

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
        .lockout(Lockout.after(5).forMinutes(15))
        .userCacheTtl(5_000)
        .build();
auth.install(app);
AuthUser alice = auth.users().create("alice", "alice-password-1".toCharArray());
TokenPair pair = auth.tokens().issue(alice);
app.ws("/ws", auth.webSocket((rq, ws) -> ws.sendText("hi " + auth.user(rq).getId())));
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .lockout(Lockout.after(5).forMinutes(15))
    .userCacheTtl(5_000)
    .build()
val alice = auth.users().create("alice", "alice-password-1".toCharArray())
val pair: TokenPair = auth.tokens().issue(alice)
app.ws("/ws", auth.webSocket { req, ws -> ws.sendText("hi " + auth.user(req).id) })
```

## Refresh reuse detection and TokenCodec

**Refresh reuse detection:** when a refresh token that was already used arrives again outside the reuse grace window (`.reuseGrace(millis)`, default 0), `TokenService` revokes its whole family through `RefreshTokenStore.revokeFamily`, calls `AuthEvents.onRefreshTokenReused(userId, familyId, ctx)` once, and answers with the same 401 `invalid_refresh_token` as any other invalid refresh token (the response never shows that detection fired). With a grace of 0 a second use 1 ms later is already reuse. A repeat inside the grace window (for example two tabs refreshing at once) is refused with 401 and revokes nothing. Other families of the same user are not touched. Reading a token's identity for logout does not consume it, so used or revoked tokens give no identity and never count as reuse. A store error while handling reuse is an `AuthUnavailableException` (503), neither a success nor an invalid-token answer. Express counterpart: the "reuse detection" option of a refresh-token rotation library.

**Token format: `TokenCodec`.** `spi.TokenCodec` is the token-format seam with `String encode(JwtClaims claims)` and `JwtClaims decode(String token, String expectedType)` (both `throws Exception`, nothing null). auth issues and reads every access and refresh token only through the configured codec; replace it with the builder's `.tokenCodec(codec)`. The default is the HS256 codec over the key source (header `kid`, `typ` `at+jwt` / `rt+jwt`, purpose-derived keys), which refuses a token of another `expectedType` (`TokenCodec.ACCESS` or `TokenCodec.REFRESH`). A throwing `decode` answers the same 401 `invalid_token` as a bad signature, and the reason goes to the DEBUG log only. 0.11.0 ships no other codec (RS256, ES256 and JWKS come later). After the first public release the interface gains default methods only. Express counterpart: the `sign` / `verify` pair handed to `express-jwt`.

```java
TokenCodec wrapped = new TokenCodec() {
    @Override
    public String encode(JwtClaims claims) throws Exception {
        return Jwt.builder(JwtKeys.of(new byte[32])).build().sign(claims); // for example another key or format
    }

    @Override
    public JwtClaims decode(String token, String expectedType) throws Exception {
        return Jwt.builder(JwtKeys.of(new byte[32])).build().verify(token, expectedType);
    }
};
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).tokenCodec(wrapped).reuseGrace(0).build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .reuseGrace(0)
    .events(object : AuthEvents {
        override fun onRefreshTokenReused(userId: String, familyId: String, ctx: AuthEventContext) {
            println("refresh reuse: $userId / $familyId") // raise an alert
        }
    })
    .build()
```

## CSRF: cookie-mode writes

In `COOKIE` and `BOTH` modes a POST, PUT, PATCH or DELETE authenticated by the `naru_at` cookie (protected paths, and `POST /auth/password`, `POST /auth/me/apikeys`, `POST /auth/admin/apikeys`) passes only when the `X-CSRF-Token` header equals, in constant time, a `naru_csrf` cookie value whose HMAC matches the `sub` and `fam` of the presented access token; otherwise it is 403 `csrf_failed`. On a public path (`.permitPrefix`, or outside the `.protectOnly` range) a cookie write without a matching CSRF token or Origin is treated as anonymous, so a guard there (`auth.authenticated()`, `auth.require(...)` and so on) answers 401 `unauthenticated` and an unguarded handler sees `auth.userOrNull(req)` as `null`. A token is `base64url(random16) + "." + base64url(HMAC(csrf key, sub + "." + fam + "." + random16))`, with the key derived from the key-set secret with the label `"naru-csrf"` (a value made under an old kid still in rotation passes). Because it is bound to the family, a value of an old family planted from another path or domain never passes; with two or more `naru_csrf` cookies the request passes when any value equals the header and matches this request's family. GET, HEAD and OPTIONS, Bearer requests and API-key requests are not checked. Every cookie-delivered token response sets a new `naru_csrf` for the new pair's `sub` and `fam` and puts only `csrfToken` in the body; the browser app sends that value (or reads the `naru_csrf` cookie) as `X-CSRF-Token` on writes. Express counterpart: the signed double-submit cookie of `csrf-csrf`.

```java
// the browser side (sketch): fetch("/api/posts", {method: "POST", credentials: "include",
//   headers: {"Content-Type": "application/json", "X-CSRF-Token": csrfToken}, body: "{}"})
Auth guard = Auth.builder(System.getenv("JWT_SECRET")).transport(TokenTransport.COOKIE).build();
guard.install(app);
app.post("/api/posts", guard.require("posts:write"), (rq, rs) -> rs.status(201).send("created"));
```

```kotlin
// `auth`: the Auth built above
app.post("/api/posts", auth.require("posts:write")) { _, rs -> rs.status(201).send("created") }
```

## Trusted devices: DeviceTrust

A successful password login issues a device token: an HS256 token (header `kid`, `typ` `dt+jwt`) holding the user id (`sub`), the `ver` at issue, the fingerprint `fp` = base64url(HMAC(fingerprint key, normalized login name)), a random `jti` and an `exp` 90 days later, and never the login name itself. The signing key is HMAC(secret, `"naru-device"`) and the fingerprint key HMAC(secret, `"naru-device-fp"`), both derived for the kid in the token header. A later login trusts it only when signature, expiry (no leeway), fingerprint, user id and `ver` all match and its failure limit has not invalidated it. So a logout of all devices or a password change (`ver++`), a rename, another user taking the name, or finding the same user by another name (an email, say) all end the trust. A key rotation keeps the trust while the old kid stays in the verification list. Failed logins carrying a trusted device token count against that token in a fixed window of 15 minutes, and the 5th failure invalidates it (a thief cannot rotate IPs with a stolen token).

`spi.DeviceTrust` is the state behind it. auth signs, verifies and fingerprints itself (no key reaches this interface) and calls it at three points of a login: `default void issued(DeviceToken token)` after a successful login issued a token, `boolean isInvalidated(DeviceToken token)` when step 3 of the check order decides trust, and `boolean recordFailure(DeviceToken token, int max, long windowMillis)` for every failure carrying a trusted token (auth passes 5 per 15 minutes; true when the token is invalidated after the call), plus `default int purgeExpired(long nowMillis)`, which the 10-minute cleanup calls to forget expired tokens (the default removes nothing). All are synchronous and `throws Exception`; a failure answers 503. `spi.DeviceToken` is the value of a verified token (`getTokenId()`, `getUserId()`, `getTokenVersion()`, `getExpiresAt()`). Replace it with the builder's `.deviceTrust(trust)`; without it the default follows the user store (in memory, or `<name>-device-trust.json` beside a `FileUserStore`, where an invalidation is written at once). After the first public release it gains default methods only. Express counterpart: the device-cookie table of the OWASP "device cookies" pattern.

The `naru_device` cookie (cookie delivery): HttpOnly, SameSite=Strict, Path = the mount path plus `/login` (`/auth/login` by default, `/api/auth/login` with `.mountPath("/api/auth")`), Secure exactly when `req.isSecure()`, Max-Age the 90-day device-token lifetime (7776000 seconds). HEADER mode returns it as body `deviceToken` and reads only the `Naru-Device` request header. The login endpoint (`POST /auth/login`) runs this order: 1) a device token passing signature, expiry and fingerprint defers the IP key, otherwise the IP key is checked first; 2) the `(user, IP)` key; 3) `findByUsername`, then trust from the id, `ver` and `isInvalidated`, exempting a trusted device from the user-only and IP keys and checking the deferred IP key and the user-only key otherwise; 4) the hash. A limit hit in steps 1 to 3 answers 429 at once without hashing. An attempt that reaches the hash runs exactly one PBKDF2 computation in one hash slot whatever the account (a legacy hash: the legacy verify plus a target-cost dummy PBKDF2; a malformed PBKDF2 stored hash: a target-cost dummy PBKDF2), and its failure response (the 503 of a full slot queue included) is never sent earlier than max(1.25T, elapsed) plus 0–50 ms random jitter after the request started (T is the login timing base fixed at start). The wait holds no worker thread.

```java
DeviceTrust audited = new DeviceTrust() {
    final java.util.Set<String> dead = new java.util.HashSet<>();

    @Override
    public synchronized boolean isInvalidated(DeviceToken token) {
        return dead.contains(token.getTokenId());
    }

    @Override
    public synchronized boolean recordFailure(DeviceToken token, int max, long windowMillis) {
        return false; // for example count failures elsewhere and add the id to dead at max
    }
};
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).deviceTrust(audited).build();
```

```kotlin
class LoggingTrust : DeviceTrust {
    override fun isInvalidated(token: DeviceToken) = false
    override fun recordFailure(token: DeviceToken, max: Int, windowMillis: Long): Boolean {
        println("device ${token.tokenId} failed")
        return false
    }
}
val auth = Auth.builder(System.getenv("JWT_SECRET")).deviceTrust(LoggingTrust()).build()
```

## Cross-origin clients and CORS

When a web app on another origin, or an app such as Capacitor, calls auth, the browser sends a preflight first. Every header the client sends must be listed in the `allowedHeaders` of `app.cors(...)`.

| Header | Needed when |
|---|---|
| `Authorization` | sending a Bearer access token, and sending an API key with the key scheme (`ApiKey` by default, `.apiKeyScheme(..)`) |
| `Content-Type` | sending a JSON body (`application/json`: login, refresh and every auth body) |
| `X-CSRF-Token` | a client that got cookie delivery sends cookie-authenticated writes (POST, PUT, PATCH, DELETE) and cookie refreshes |
| `Naru-Device` | sending the device token by header (HEADER mode, or a device token received in the body under BOTH) |

A cross-origin request carrying a header that `allowedHeaders` lacks (for example `Naru-Device`) is blocked at preflight and **never reaches the server**, so nothing shows in the auth log either. Register `app.cors(...)` before `auth.install(app)` (registering it later makes the doctor fail the start). In BOTH mode, origins that should get body delivery (other-site SPAs, Capacitor apps) belong in `allowedOrigins` only, not in `trustedOrigins` (the builder's `.trustedOrigins(...)`, [Origin check](#origin-check-and-trustedorigins)). Adding them to `trustedOrigins` switches them to cookie delivery, yet SameSite=Strict cookies are not sent on cross-site requests, so such a client could never send the cookies back. `allowedOrigins` only lets the page read responses; `trustedOrigins` decides cookie delivery.

A BOTH-mode client checks the body of every token-issuing response: `accessToken` present means body delivery (keep the tokens, send Bearer); only `csrfToken` present means cookie delivery (send `X-CSRF-Token`). The same client may receive either shape depending on Origin and `trustedOrigins`, so it never assumes one.

`allowedOrigins` accepts `scheme://host[:port]` origins, `null` and `*`. iOS Capacitor uses `capacitor://localhost` (the old Ionic WebView `ionic://localhost`); Android Capacitor defaults to `https://localhost`, and only an older app with `androidScheme: "http"` uses `http://localhost`. The comparison is an exact string match, so list the value the app really sends. This origin is not an app identity: every Capacitor app on its default settings sends the same value, so another Capacitor app's web view can read responses too, and without the user's token it gets only public responses. A custom-scheme origin cannot go in `trustedOrigins`, so it is always body delivery. A request sent through CapacitorHttp carries no `Origin`, so it is body delivery too and needs no CORS setting. Express counterpart: `app.use(cors({origin: [...], allowedHeaders: [...], credentials: true}))` placed before the auth middleware.

```java
RestApp web = RestApp.create();
web.cors(c -> c
        .allowedOrigins("https://spa.example", "capacitor://localhost", "https://localhost")
        .allowedMethods(Method.GET, Method.POST, Method.PUT, Method.DELETE)
        .allowedHeaders("Authorization", "Content-Type", "X-CSRF-Token", "Naru-Device")
        .credentials(true));
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).transport(TokenTransport.BOTH).build();
auth.install(web); // after CORS
```

```kotlin
val web = RestApp.create()
web.cors { c ->
    c.allowedOrigins("https://spa.example", "capacitor://localhost", "https://localhost")
        .allowedHeaders("Authorization", "Content-Type", "X-CSRF-Token", "Naru-Device")
        .credentials(true)
}
Auth.builder(System.getenv("JWT_SECRET")).transport(TokenTransport.BOTH).build().install(web)
```

## Test kit: AuthTesting

`utils.auth.testing.AuthTesting` ships in the `NaruServe-api` main jar and needs no test framework. It issues tokens and keys through the normal issue paths (`auth.tokens()`, the API key store) and never copies or exposes the signing key.

| API | Description |
|---|---|
| `AuthTesting.tokenFor(auth, name, roles...)` | Creates the user in the `InMemoryUserStore`, or overwrites its roles, and returns an access token at its current `ver` (no refresh record, a synthetic `fam`). Every role must be declared in the `RolePolicy` (`IllegalArgumentException` otherwise); another store throws `IllegalStateException`. `tokenFor(auth, "alice")` is a token without roles |
| `AuthTesting.tokenForUserId(auth, id)` | A token at the user's current `ver` for any store; the user is not modified. An unknown id throws `IllegalArgumentException("no user with id …")` |
| `AuthTesting.csrfTokenFor(auth, accessToken)` | The CSRF value bound to that token's `sub` and `fam`; send it as the `naru_csrf` cookie and the `X-CSRF-Token` header in cookie-mode write tests |
| `AuthTesting.apiKeyFor(auth, ownerUserId, scopes...)` | Stores a real non-expiring API key in `.apiKeys(store)` and returns the plaintext key. It goes through the normal `auth.apiKeys().issue` path, so any `ApiKeyStore` works and the same `.maxKeysPerUser` limit applies (`AuthRejection(409, "api_key_limit")` over it). An unknown owner throws `IllegalArgumentException`, no key store `AuthStateException`. Send it as `Authorization: <scheme> <key>` |
| `AuthTesting.verifyUserStore(store)` | Checks a custom `UserStore` against the contract: CRUD (including that a deleted id is never handed to the next user, so a `MAX(id)+1` or SQLite rowid store fails), `DuplicateUserException` for an existing username, the password-hash round trip after save and reload through the same instance (a store that caches rows in memory and loses the hash when it saves them still passes; use the `reopen` form below), `changePassword` with a null stored hash and with a ver mismatch, the field-method results, and for a store returning true from `supportsAtomicFieldWrites()` that `update()` never overwrites passwordHash, ver, enabled or roles. A broken clause throws `AssertionError` (message `UserStore contract (<clause>)`). Each call uses random `contract-` names and deletes the users it created, so one store can be verified repeatedly |
| `AuthTesting.verifyUserStore(store, reopen)` | The checks above, plus the hash round trip after a restart: `reopen` (a `Callable<? extends UserStore>`, a lambda in Java and Kotlin) opens a fresh instance over the same storage, and the hash written by `create()` and kept through `update()` must read back from it by id and by name. Pass `() -> new FileUserStore(file)` for a file store, `() -> store` for a store that reads its storage on every call (JDBC). A `reopen` that throws or returns null fails the contract; a reopened instance other than `store` is closed when it is `AutoCloseable`. Express counterpart: restarting the app inside a jest contract suite |
| `AuthTesting.verifyApiKeyStore(store)` / `verifyApiKeyStore(a, b)` | Checks the `ApiKeyStore` method table (field round trip with null `expiresAt`, `lastUsedAt` and `issuedByUserId`; null for an absent prefix; `ApiKeyPrefixCollisionException` for an existing one; `aB…` and `Ab…` as separate rows; the limit count without expired keys and keys of an older `ownerVer`; `deleteByIdAndOwner` false for another owner; `deleteAllByOwner`; `deleteExpired`; `deleteStaleByOwner` and `deleteFutureByOwner`). The two-argument form also checks, over two instances of one storage, that exactly one of 8 concurrent limited inserts wins. A broken rule throws `AssertionError` (message starting `ApiKeyStore contract:` and naming the method) |

Express counterpart: the supertest helper that calls the login route for a token, and the jest contract suite copied for every store.

A test that needs PROD, with a real secret and file key, can make both from random bytes on the JVM (`java.util.Base64` is Android API 26+):

```java
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.AuthFileKey;
import com.snoworca.naruserve.utils.auth.AuthMode;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import java.io.File;
import java.security.SecureRandom;
import java.util.Base64;

byte[] raw = new byte[32];
new SecureRandom().nextBytes(raw);
String secret = Base64.getEncoder().encodeToString(raw);   // for Auth.builder(secret)
new SecureRandom().nextBytes(raw);
AuthFileKey fileKey = AuthFileKey.of(Base64.getEncoder().encodeToString(raw));
Auth prod = Auth.builder(secret).mode(AuthMode.PROD)
        .users(new FileUserStore(new File("build/test-users.json"), fileKey)).build();
```

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
        .roles(RolePolicy.builder().role("editor", "posts:write").build()).build();
String access = com.snoworca.naruserve.utils.auth.testing.AuthTesting.tokenFor(auth, "alice", "editor");
String csrf = com.snoworca.naruserve.utils.auth.testing.AuthTesting.csrfTokenFor(auth, access);
// Cookie: naru_at=<access>; naru_csrf=<csrf>   X-CSRF-Token: <csrf>
com.snoworca.naruserve.utils.auth.testing.AuthTesting.verifyUserStore(new InMemoryUserStore());
```

```kotlin
import org.junit.Test

// `MyUserStore` is your UserStore class and `ds` what it opens (a DataSource, a file); `auth` as above
class MyStoreTest {
    @Test fun myStoreKeepsTheContract() = AuthTesting.verifyUserStore(MyUserStore(ds))
    @Test fun bearerWrite() {
        val token = AuthTesting.tokenFor(auth, "alice", "editor")
        // Authorization: Bearer $token
    }
}
```

### HTTP test recipe

A JUnit 4 test of the whole app over real HTTP. It creates the account with DEV mode and `.user(...)`, removes the cache wait with `.userCacheTtl(0)` and starts on a free port with `app.listen(0)`. It calls `POST /auth/login` with `HttpURLConnection` to get a token, calls a protected route with `Authorization: Bearer <token>`, and stops the app with `app.shutdown(...)` in `@After`. The dependencies are `com.snoworca.naruserve:naruserve` and JUnit 4; any other HTTP client (OkHttp, say) works too. When a test needs only a token and does not test the login path, `AuthTesting.tokenFor` above is faster. The `call` helper writes a body only when `json` is not null, because a body turns `HttpURLConnection`'s GET into POST: a GET passes `null`. `HttpURLConnection` cannot send `PATCH` (`setRequestMethod("PATCH")` throws `ProtocolException`); to test `PATCH /auth/me` or the admin A4 update, use `java.net.http.HttpClient` (Java 11+) or OkHttp. `java.net.http.HttpClient` needs Java 11, so a test compiled for Java 8 or Android uses OkHttp (`com.squareup.okhttp3:okhttp`). Five failed logins of one name from one IP within 15 minutes answer 429 `too_many_attempts`, the right password included, so a test that tries wrong passwords builds a fresh app per test or sets `.loginFailureLimits(100, 100, 100, 60_000L)`.

```java
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.AuthMode;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PostsApiTest {
    private RestApp app;
    private int port;

    @Before
    public void start() {
        Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
            .mode(AuthMode.DEV)                                   // tests: a temporary key without a secret
            .userCacheTtl(0)                                      // store writes are seen by the next request
            .roles(RolePolicy.builder().role("editor", "posts:write").build())
            .user("alice", "alice-password-1", "editor")
            .build();
        app = RestApp.create();
        app.use(auth);
        app.post("/posts", auth.require("posts:write"), (req, res) -> res.status(201).send("created"));
        app.onError(auth.errors());
        port = app.listen(0).port();                              // 0 picks a free port
    }

    @After
    public void stop() {
        app.shutdown(5, TimeUnit.SECONDS);
    }

    @Test
    public void editorCanPost() throws Exception {
        String login = call("POST", "/auth/login", null, "{\"username\":\"alice\",\"password\":\"alice-password-1\"}");
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(login);
        assertTrue(login, m.find());
        assertEquals("201 created", call("POST", "/posts", m.group(1), "{}"));
        assertTrue(call("POST", "/posts", null, "{}").startsWith("401"));
    }

    private String call(String method, String path, String token, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (json != null) { // a body turns HttpURLConnection's GET into POST
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = c.getResponseCode();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            byte[] buf = new byte[1024];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                body.write(buf, 0, n);
            }
        }
        c.disconnect();
        return status + " " + new String(body.toByteArray(), StandardCharsets.UTF_8);
    }
}
```

```kotlin
import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.utils.auth.Auth
import com.snoworca.naruserve.utils.auth.AuthMode
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PostsApiTest {
    private lateinit var app: RestApp
    private var port = 0

    @Before
    fun start() {
        val auth = Auth.builder(System.getenv("JWT_SECRET"))
            .mode(AuthMode.DEV)
            .userCacheTtl(0)
            .roles(RolePolicy.builder().role("editor", "posts:write").build())
            .user("alice", "alice-password-1", "editor")
            .build()
        app = RestApp.create()
        app.use(auth)
        app.post("/posts", auth.require("posts:write")) { _, res -> res.status(201).send("created") }
        app.onError(auth.errors())
        port = app.listen(0).port()
    }

    @After
    fun stop() {
        app.shutdown(5, TimeUnit.SECONDS)
    }

    @Test
    fun editorCanPost() {
        val login = call("POST", "/auth/login", null, """{"username":"alice","password":"alice-password-1"}""")
        val token = Regex("\"accessToken\":\"([^\"]+)\"").find(login)?.groupValues?.get(1)
        assertTrue(login, token != null)
        assertEquals("201 created", call("POST", "/posts", token, "{}"))
        assertTrue(call("POST", "/posts", null, "{}").startsWith("401"))
    }

    private fun call(method: String, path: String, token: String?, json: String?): String {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (json != null) { // a body turns HttpURLConnection's GET into POST
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        val status = c.responseCode
        val body = (if (status >= 400) c.errorStream else c.inputStream).use { it.readBytes().toString(Charsets.UTF_8) }
        c.disconnect()
        return "$status $body"
    }
}
```

## Plain-text transport: allowInsecureTransport

`.allowInsecureTransport(reason)` is the only switch that turns the PROD secure-transport check off (DEV turns it off by itself). In exchange it forces short token lifetimes: access tokens 5 minutes and refresh tokens 24 hours, overriding `.accessTtl`, `.refreshTtl` and the profile. Every start logs one WARN line holding the reason, and a cookie delivery over plain text, whose auth cookies lack Secure, is logged as a WARN. An empty reason is refused by `build()`. Remove it once a TLS adapter exists.

Express equivalent: an in-house device server started with `http.createServer(app)` and no `https`; Express does not block plain text, so it has no such switch.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .allowInsecureTransport("LAN device server; TLS adapter pending")   // access 5 minutes, refresh 24 hours
    .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .allowInsecureTransport("LAN device server; TLS adapter pending")
    .build()
println(auth.tokens().accessTtl)
```

## Account endpoints: auth.routes()

`auth.install(app)` puts the account endpoints at the mount path (default `/auth`). An app route registered on `auth.routes()` at the same path runs first. Every auth POST body is JSON (415 otherwise) and is read with the app's JSON codec. Login, password, register and setup hash, and so does `POST /auth/me/apikeys` with keys on, so they are registered with `methodAsync` and hold no worker thread while they wait for a hash slot. The account endpoints registered are the eight of the table below (`POST /auth/register` only with `.register(true)`; `POST /auth/setup` whatever the setup state, answering 404 while closed), plus `GET` and `POST /auth/me/apikeys` and `DELETE /auth/me/apikeys/:id` with `.apiKeys(store)`. There is no `/signup`, `/password/forgot`, `/password/reset`, `/verify-email/request` or `/verify-email` (v2). Every token-issuing response has `Cache-Control: no-store` and follows the [delivery rule](#cross-origin-clients-and-cors): HEADER puts the tokens in the body (`accessToken`, `tokenType`, `expiresIn`, `refreshToken`, `refreshExpiresIn`, and `deviceToken` on login), COOKIE sets cookies (`naru_at`, `naru_rt`, `naru_csrf`, and `naru_device` on login) with only `csrfToken` in the body, and BOTH uses cookies when the Origin (else the Referer) is the request's own origin or in `trustedOrigins`, the body otherwise.

| Endpoint | Body | Success | Failure | Express counterpart |
|---|---|---|---|---|
| `POST /auth/login` | `{"username","password"}` | 200, a token pair in a new family plus a device token | Unknown name, wrong password, disabled user, service account and locked account all get the same 401 `invalid_credentials` (one hash, after the failure floor). A missing, non-string or `""` `password` is 400 `BAD_REQUEST` without hashing. A login limit gives 429 `too_many_attempts` with `Retry-After`, without hashing | a passport-local login route |
| `POST /auth/refresh` | `{"refreshToken"}` (HEADER, BOTH), else the `naru_rt` cookie (COOKIE, BOTH) | 200, a new pair in the same family; a body token is answered in the body, a cookie token by cookies | 401 `invalid_refresh_token` without a refresh token or for an invalid, revoked, expired or reused one; 403 `csrf_failed` for a cookie token without its `X-CSRF-Token` or with a foreign Origin; a body `refreshToken` in COOKIE mode is 400 `BAD_REQUEST` | the refresh route of a JWT setup |
| `POST /auth/password` | `{"currentPassword","newPassword"}` | 200, a new pair in a **new** family plus a new device token (and a new CSRF token by cookie) | 401 `invalid_credentials` for a wrong current password (counts toward the per-user limit; over it 429 `too_many_attempts`); 401 `invalid_token` when the session was cut (another `ver`, disabled); 409 `write_conflict` when another write changed the password first; 422 for a policy violation; 400 `BAD_REQUEST` for a missing field | a change-password route |
| `POST /auth/logout[?all=true]` | optional `{"refreshToken"}` (HEADER, BOTH) | 204; COOKIE and BOTH clear the auth cookies with `Max-Age=0`. A plain logout revokes only the refresh-token family: the access token already issued stays valid until it expires (`expiresIn`, 15 minutes under the default profile); to end it now, send `?all=true`, which raises the user's `ver` and so ends every access token of the user on every device | `?all=true` without an identity: 401 `invalid_token` when a token was read, 401 `unauthenticated` when none was. A body `refreshToken` in COOKIE mode is 400 | `req.logout()` |
| `POST /auth/register` (only with `.register(true)`) | `{"username","password"}` | 201, a token pair | 409 `duplicate_user` for a taken name; 422 for a policy violation | a signup route |
| `GET /auth/me` | — | 200 `{"id","username","enabled","roles","attributes","serviceAccount"}` (no hash, no token version; `roles` is the user's own roles only, so a role from a group is not listed: `auth.user(req).getRoles()` holds those too); API keys may call it | 401 `unauthenticated` without a credential | a `/me` route returning `req.user` |
| `PATCH /auth/me` | `{"attributes":{"displayName":"Al"}}` | 200 with the changed profile | an attribute not in `.allowedAttributes` is 400 `BAD_REQUEST` (nothing changes); a `beforeUserUpdate` refusal answers its status and code (for example 422 `name_policy`); an API key is 403 `api_key_not_allowed`; 401 `unauthenticated` without a credential | a profile update route |
| `POST /auth/setup` | `{"token","username","password"}` | 201 (created) / 200 (recovered) plus a token pair | 404 while closed; 403 `setup_not_local` when not local; 400 `BAD_REQUEST` for a missing or non-string `token`; 401 `invalid_setup_token` for a wrong one | — |

**Login**: the name is normalized (NFKC plus `Locale.ROOT` lower case), so `ALICE` logs in `alice`. The check order is steps 1–4 of [Trusted devices](#trusted-devices-devicetrust); `beforeLogin` receives the hash-free `AuthUser` only after the password verified for an enabled user. A listener's `AuthRejection` (for example 403 `blocked`) is sent after the failure floor, and no listener runs for a wrong password or an unknown name, so a refusal never reveals that an account exists. A success fires `onLogin`. The device token is read from the `Naru-Device` request header only in HEADER, from the `naru_device` cookie only in COOKIE, and header first in BOTH. An access token sent to login (expired or valid) is ignored and no principal is attached.

**Logout** finds its identity with this table. When an earlier candidate **was sent**, only it is consulted, and a sent but invalid one means no identity (later candidates are not read). Valid means signature, expiry and the user's current `ver`, and for a refresh token a stored record that is neither used nor revoked. Logout never marks a token used, so it never triggers reuse detection.

| Step | Candidate | CSRF and Origin |
|---|---|---|
| 1 | a body `refreshToken` (HEADER, BOTH) | none; in BOTH the same user's `naru_rt` family is revoked too |
| 2 | exactly one `naru_rt` (COOKIE, BOTH; two or more count as none) | `X-CSRF-Token` equal to a `naru_csrf` matching that token's `sub` and `fam`, and a same-origin or trusted Origin |
| 3 | a valid Bearer (HEADER, BOTH) / exactly one `naru_at` (COOKIE, BOTH) | none for Bearer / the step-2 check for `naru_at` |
| 4 | none | — |

With an identity, logout revokes that token's family with `revokeFamily` (with `?all=true` it raises the user's `ver` by exactly 1 instead, cutting every device) and calls `onLogout(userId, allDevices, ctx)`. A Bearer token together with a `naru_at` in BOTH mode is 400 `invalid_request` before the table. A store error while finding the identity is 503. A token from `AuthTesting.tokenFor` has a synthetic family, so its logout revokes nothing.

**Decision order**: every auth endpoint judges a request in one fixed order and the earliest failing step answers: the secure-transport check (403 `insecure_transport`), multiple credentials and the API-key procedure (400 `invalid_request`, 401 `invalid_api_key`, 403 `api_key_not_allowed`), the body format (415), a body `refreshToken` in COOKIE mode (400), CSRF and Origin (403 `csrf_failed`; login, register and setup choose the delivery in BOTH mode instead), then the handler. A cookie refresh token is verified before its CSRF check, so a revoked or expired `naru_rt` is 401 whether or not `X-CSRF-Token` came. A closed setup is a route that is not there: 404 before its Origin step.

**Refresh** takes the JSON body `refreshToken` first (HEADER and BOTH; no CSRF, body delivery), else exactly one `naru_rt` cookie (COOKIE and BOTH; CSRF and Origin, cookie delivery). HEADER never reads the cookie, and COOKIE refuses a body token with 400. With both in BOTH mode the body token is used and `naru_rt` is neither checked nor rotated. Rotation keeps the family; presenting a rotated token again outside the `.reuseGrace(..)` window revokes the whole family (`onRefreshTokenReused`). An expired Bearer or `naru_at` sent with refresh is ignored. With API keys on, a key header is judged first (a wrong key 401 `invalid_api_key`, a valid one 403 `api_key_not_allowed`) and `naru_rt` is not read. A success fires `onTokenRefreshed`.

**Password change** is a subject-required endpoint judged like a protected path (an expired token is 401 `invalid_token`, a cookie session needs its `X-CSRF-Token` and a passing Origin, an API key is 403 `api_key_not_allowed`). The policy check (422) and the per-user limit (5 wrong current passwords per 15 minutes, shared with `auth.users().checkPassword`) run before any hash. In one hash slot the user is re-read (another `ver` than the request token's, or disabled: 401 `invalid_token`), the current password is verified and the new one hashed. `beforeUserUpdate` (origin `SELF`; it sees no hash and may refuse, for example 403 `password_change_locked`) runs before the write. The write is one `changePassword(id, oldHash, tokenVer, newHash)`, the atomic hash swap with `ver + 1`; when another write won, it is retried once only if the re-read hash still verifies the current password (a rehash), else 409. Then `onUserUpdated` and `onPasswordChanged`, and the new pair is delivered the way the credential came: by body for a Bearer token, by cookies for `naru_at`.

**Your profile**: `GET /auth/me` is the caller's public profile, re-read from the store. `PATCH /auth/me` reads only the `attributes` object: a string value sets that attribute, `null` removes it, and any name not listed in `.allowedAttributes(...)` makes the whole request 400. Any other field, such as `displayName` outside `attributes`, `username`, `roles`, `enabled` or `password`, makes the request 400 `unknown field <name>; PATCH /auth/me reads only {"attributes":{...}}` and changes nothing: a password changes through `POST /auth/password`, a name only through `auth.users().rename` or admin A4, `PATCH /auth/admin/users/:id`. The write takes the same per-user locked path as `auth.users().update` and raises `beforeUserUpdate` and `onUserUpdated` (`ATTRIBUTES`) once each with origin `SELF`. A password change (`POST /auth/password`) raises `beforeUserUpdate`, `onUserUpdated` (`PASSWORD`) and `onPasswordChanged`, also with origin `SELF`.

**Register** runs `beforeUserCreate` (origin `REGISTER`; a refusal answers its status and code, for example 422 `domain_not_allowed`) before the hash, hashes in a hash slot, creates the user and fires `onUserCreated`. COOKIE mode checks only the Origin (cross-origin: 403 `csrf_failed`). The user has no roles.

**Setup and recovery**: setup opens in one of two ways only. 1) In PROD, a null-password admin `.user` whose account is missing, with recovery on (the body must name that `.user`, else 400 `username_mismatch`; it gets that `.user`'s roles). 2) `.recovery(true)`, or `.recoveryFromEnv()` with `NARU_AUTH_RECOVERY=1` (each start logs a new one-time token in its one WARN line). Recovery creates an unknown name with the built-in `admin` role, and for an existing name (a null stored hash included) enables it, replaces its password, adds `admin` and raises its `ver` exactly once (old tokens rejected; a service account is 400 `service_account_no_password`); it fires only `onUserUpdated` with the changed fields and `onPasswordChanged`, origin `RECOVERY`. Only a local request is accepted (loopback socket address and no `X-Forwarded-*` or `Forwarded` header), judged separately from the secure-transport check ([The secure-transport rule](#the-secure-transport-rule)): a secure but non-local request is 403 `setup_not_local` and the token stays unused. No before-event runs; `onUserCreated` / `onUserUpdated` run with origin `SETUP` or `RECOVERY`, and each recovery leaves a `recovery_used` audit event. After one success setup is closed for the process (404). COOKIE mode checks the Origin.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).register(true).recoveryFromEnv()
        .events(new AuthEvents() {
            @Override
            public void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) {
                if (ctx.getOrigin() == UserOrigin.REGISTER && !candidate.getUsername().endsWith("@corp.example")) {
                    throw new AuthRejection(422, "domain_not_allowed", "use your company address");
                }
            }
        }).build();
auth.install(app);
// POST /auth/login {"username":"alice","password":"..."} -> 200 {"accessToken":...,"refreshToken":...}
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).register(true).build()
auth.install(app)
// fetch("/auth/logout?all=true", {method: "POST", headers: {Authorization: "Bearer " + token}}) -> 204
```

## Origin check and trustedOrigins

In `COOKIE` and `BOTH` mode, every response that plants auth cookies and every request that uses a token that came by cookie (a cookie-authenticated protected-path write, logout steps 2 and 3) needs its `Origin` (or the origin of its `Referer` when there is no `Origin`) to be the request's own origin (from `req.isSecure()` and `Host`) or listed in `.trustedOrigins(...)`. `Origin: null` and a request with neither header fail. Login, register and setup check only the Origin, never a CSRF token. A failure is 403 `csrf_failed` in COOKIE mode; in BOTH mode login, register and setup choose body delivery instead of the 403. Origins compare as `scheme://host[:port]` (lower case, default port dropped), and `build()` refuses a `.trustedOrigins` value with a path. Express counterpart: the Origin-check middleware used alongside `csurf`.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).transport(TokenTransport.COOKIE)
        .trustedOrigins("https://admin.example.com").build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).transport(TokenTransport.BOTH)
    .trustedOrigins("https://spa.example").build()
```

## API key authentication

`.apiKeys(store)` turns keys on. It registers `/auth/me/apikeys` (`GET` lists the caller's keys with their `status` written as `active`, `expired` or `stale`, `POST` issues a PAT after re-checking the password, `DELETE /:id` deletes one of the caller's keys) and the three `/auth/admin/apikeys` routes ([API key issuance endpoints](#api-key-issuance-endpoints-pats-and-admin-issuance)). Off (the default), there are no key routes and `Authorization: ApiKey ...` is no credential (401 `unauthenticated`).

A key is read only from `Authorization: <scheme> nk_<prefix8>_<secret32>`. The scheme is `.apiKeyScheme(..)` (default `ApiKey`, compared ignoring case) and the value must be exactly one RFC 9110 `token68` (`ApiKey a=b` is 401 `invalid_api_key`). Keys and JWTs are told apart by the **scheme**, not by the value's shape, so `Bearer nk_...` is a bad JWT (401 `invalid_token`). A key in the query string or a cookie is never read. In COOKIE and BOTH mode, a key together with a `naru_at` is 400 `invalid_request` on a protected path and anonymous on a public one (`naru_rt` is not counted as a credential). Two `Authorization` headers are 400 on a protected path or a credential endpoint and are not read (anonymous) on other public paths; a value a proxy merged into one line is one malformed credential judged by its first scheme.

Verification runs in this order, and when several conditions apply the earliest step answers: the secure-transport check, multiple credentials (400), the scheme, the key-only per-IP limit (429), parsing, the prefix lookup (a dummy hash compare when the prefix is unknown, and a byte-wise prefix re-compare, so a case-insensitive database collation cannot return another key), one constant-time compare of the secret's SHA-256, the expiry (`now >= expiresAt`, no leeway; `expiresAt` null never expires), the pending marker below, the owner read through the principal cache, the owner's `enabled` flag and `ver`, the endpoint restriction (403 `api_key_not_allowed`) and the scope guard (403 `insufficient_scope`). The owner is read only after the secret matched. Every failure is 401 `invalid_api_key`; a store error is 503 on a protected path and anonymous on a public one (logged at most once a minute). No hash slot and no failure floor are used. The key row is read from the store on every request (never cached), so a deleted, revoked or expired key stops on every node at the next request; the owner's `enabled`, `ver` and scopes go through the principal cache and reach other nodes within `userCacheTtl` (5 s).

**Key-only per-IP limit**: every 401 `invalid_api_key` (malformed, unknown, wrong secret, expired, stale, disabled owner, invariant violation, pending marker), on protected and public paths alike, counts once per client IP (IPv6 by `/64`) in a counter of its own, `.apiKeyFailureLimit(max, windowMillis)` (default 100 per 15 minutes). Over it a key request is 429 `too_many_attempts` with `Retry-After` before any parsing or lookup on a protected path, and anonymous without a lookup on a public one. A success never resets it, and it is independent of the login limits (login failures never block keys, key failures never spend the login budget). Residual risk: the key-only IP limit also blocks valid keys sent from the same IP (a NAT or a proxy) once an attacker there exhausted it. A `LoginThrottle` failure during a key request is 503 on a protected path.

**Stale keys**: a key carries the owner's `ver` at issuance (ownerVer). ownerVer below the owner's `ver` is a stale key (the owner's tokens were revoked since): 401, and `deleteStaleByOwner(owner, ver)` is scheduled off the request path. The same deletion is scheduled after an auth-triggered `ver` raise that left the owner with stale keys. At most one deletion is pending per owner; it runs with the largest `ver` observed while it waited, and the owner leaves the pending set when the deletion starts, so a stale key met after that schedules a new one (a burst of key requests never piles up deletion jobs). ownerVer above the cached `ver` re-reads the owner once; still above is an invariant violation (the user store's `ver` went back, or a replica lags): 401 with one ERROR a minute, and under `SINGLE_INSTANCE` the key is deleted off the request path (under `MULTI_INSTANCE` it is kept, so it authenticates again once the replica catches up). Before every auth-triggered `ver` raise (logout `?all=true`, password change, admin reset and disable, recovery) and before every issuance, `SINGLE_INSTANCE` deletes the owner's keys whose ownerVer is above the stored `ver` (`deleteFutureByOwner`), inside an in-node per-owner key lock taken inside the user write lock. When that deletion fails before a `ver` raise, the raise still happens, an ERROR is logged and an in-memory pending marker rejects those keys until a retry succeeds (the next request, issuance, raise or 10-minute cleanup run); the marker is not persisted, so a restart forgets it.

A key request authenticates as its owner, with the effective scopes "the key covers it and the owner's current scopes cover it"; with `.allowAdminScopesOnApiKeys` off, an `auth-*` scope left on a key is not judged. A valid key sent to a credential endpoint (login, refresh, logout, register, setup, `POST /auth/password`, `POST /auth/me/apikeys`, `POST /auth/admin/apikeys`) is 403 `api_key_not_allowed`, decided before any scope guard. A lockout or a login limit of a service account's name never stops its key authentication, and key requests need no CSRF token. The audit events of a key-authenticated action carry the key id (`apiKeyId`), and a service-account actor is marked (`serviceAccount`).

**Admin scopes on keys**: a key covers an `auth-*` scope only by naming it (or its `resource:*`), never through `*`, with or without `.allowAdminScopesOnApiKeys(true)`, and no key covers a granted or held `*` (so a key never grants `admin`, never acts on a `*` holder and never adds someone to a `*` group). With the opt-in off, `auth-*` scopes left on a key are ignored, not deleted. The doctor WARNs at every start while the opt-in is on (role grants and reactivations made by a key persist after the key is deleted; review audit events by apiKeyId), and with it off WARNs with the number of keys that still hold `auth-*` scopes, or, when the store cannot count them (`countKeysWithAdminScopes()` is -1), WARNs at request time with the key id, at most once a minute. Under `MULTI_INSTANCE` an `ApiKeyStore` whose `supportsAtomicLimitInsert()` is false is a doctor FAIL (the app does not start).

**401 and 403 challenges**: every auth 401 carries one `WWW-Authenticate` header. It is `Bearer`, with `error="invalid_token"` only when a JWT that was read is invalid or expired, followed with keys on by the key scheme without attributes (`Bearer, ApiKey`; with `.apiKeyScheme("ak")`, `Bearer error="invalid_token", ak`). A 403 `insufficient_scope` of a JWT request carries `Bearer error="insufficient_scope"`; a 403 of a key request (`insufficient_scope`, `api_key_not_allowed`) carries no `WWW-Authenticate`. `invalid_api_key` is signalled only by the body code.

Express equivalent: `HeaderAPIKeyStrategy` of `passport-headerapikey` (an `Authorization: ApiKey ...` header).

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).apiKeys(new InMemoryApiKeyStore()).apiKeyScheme("ApiKey")
        .maxKeysPerUser(20).build();
auth.install(app);
app.get("/reports", auth.require("reports:read"), (req, res) -> res.send(auth.user(req).getId()));
// curl -H "Authorization: ApiKey nk_Ab3dE5gH_..." https://api.example.com/reports
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).apiKeys(InMemoryApiKeyStore()).build()
auth.install(app)
app.get("/reports", auth.require("reports:read")) { req, res -> res.send(auth.user(req).id) }
```

## Issuing API keys: auth.apiKeys()

`auth.apiKeys()` returns `AuthApiKeys` (package `apikey`). It handles keys from app code, such as an operator script or a service account, and is the only way to create a key that never expires (`expiresAt` null). **This facade performs no actor checks** (neither the admin grant rule nor the key-actor restrictions). An app that builds a delegated management screen checks the actor itself or uses the admin endpoints.

| API | Description |
|---|---|
| `IssuedApiKey issue(String ownerUserId, String name, @Nullable Long expiresAt, String... scopes)` | A new key; the plaintext is in the result this one time. The key is bound to the owner's current `ver` read from the store (a later `ver` raise makes it stale), and `issuedByUserId` is null. `insertIfUnderLimit` runs under an in-node per-owner lock; a prefix collision is retried with a new key up to 3 times, then `AuthUnavailableException` (503). An unknown or disabled owner, a scope breaking the scope grammar ([Roles and scopes](#roles-and-scopes-rolepolicy-rolestore-scopes)), an `auth-*` scope without the opt-in and an `expiresAt` not in the future are `IllegalArgumentException`; over `.maxKeysPerUser` it is `AuthRejection(409, "api_key_limit")` |
| `List<ApiKey> list(String ownerUserId)` | Every key of the owner with its status (`ACTIVE`, `EXPIRED`, `STALE`), no secret and no hash; an unknown owner is `IllegalArgumentException` |
| `boolean revoke(String keyId)` | Deletes the key; false without an exception when there is none |
| `IssuedApiKey issue(Request req, String ownerUserId, String name, @Nullable Long expiresAt, String... scopes)` | `issue` called from a route. It behaves the same; the request's user is recorded as the key's `issuedByUserId`, and the audit event carries the actor, request id and IP and, for a request authenticated by an API key, that key's `apiKeyId`. This form runs no actor check either |
| `boolean revoke(Request req, String keyId)` | `revoke` called from a route. It behaves the same, and the audit event carries the actor, request id and IP and, for a request authenticated by an API key, that key's `apiKeyId` |

Inside a route, use the forms that take the request, for example `app.delete("/my-keys/:id", (req, res) -> res.send(String.valueOf(auth.apiKeys().revoke(req, req.param("id")))))`. Without `.apiKeys(store)` every call throws `AuthStateException`. The exceptions are those of `auth.users()`, and `auth.errors()` turns them into JSON. `apikey.InMemoryApiKeyStore` is the in-memory store for a single instance and tests (thread-safe, the prefix a case-sensitive unique key, passing `AuthTesting.verifyApiKeyStore`); `MULTI_INSTANCE` refuses it in `build()`. Express counterpart: the service layer behind a personal-access-token page.

```java
Auth keyed = Auth.builder(System.getenv("JWT_SECRET")).apiKeys(new InMemoryApiKeyStore()).build();
String botId = keyed.users().create("ci-bot", null, u -> u.toBuilder().serviceAccount(true).build()).getId();
IssuedApiKey k = keyed.apiKeys().issue(botId, "ci-bot", null, "posts:read");
System.out.println(k.getPlainKey()); // nk_<prefix8>_<secret32>, never shown again
for (ApiKey key : keyed.apiKeys().list(botId)) {
    System.out.println(key.getPrefix() + " " + key.getStatus());
}
```

```kotlin
// `auth`: the Auth built above
// `botId`: the service account created in the Java block above
val issued = auth.apiKeys().issue(botId, "ci-bot", null, "posts:read")
val expires: Long? = issued.key.expiresAt   // null: never expires
auth.apiKeys().revoke(issued.key.id)
```

## New-password policy: PasswordPolicy and BreachedPasswordChecker

Every new password (register, setup, admin A2 and A4, `auth.users().create`, the password change) passes the `PasswordPolicy` and, when configured, the `spi.BreachedPasswordChecker` before a hash slot is taken. Login is not checked, so an existing password still logs in under a stricter policy. `PasswordPolicy.of(profile)` (the default): at least 8 characters under `BASELINE` and `STANDARD`, 15 under `STRICT`, at most 128 for all (Unicode code points). `PasswordPolicy.length(min, max)` sets them (minimum at least 1, maximum at most 128). There are no composition rules (digits, symbols), following NIST SP 800-63B. A violation is 422 `UNPROCESSABLE_ENTITY` on the HTTP endpoints (the reason in the message, never the password) and `IllegalArgumentException` from `auth.users().create`; an empty password on admin A2 and A4 ([Admin endpoints](#admin-endpoints-authadminroutes)) stays 400. A `BreachedPasswordChecker` (`@FunctionalInterface`, `boolean isBreached(char[] password) throws Exception`) returning true is the same violation; when it throws, nothing is stored or hashed and the answer is 503 (the exception text is logged, never sent). Auth passes a copy and wipes it. Express counterpart: the `express-validator` length check and a `hibp` lookup in a signup route.

```java
Set<String> commonPasswords = new HashSet<>(Arrays.asList("Password123!", "qwertyuiop12"));
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
        .passwordPolicy(PasswordPolicy.length(12, 128))
        .breachedPasswordChecker(pw -> commonPasswords.contains(new String(pw)))
        .build();
```

```kotlin
// `commonPasswords`: the Set<String> of the Java block above
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .breachedPasswordChecker { pw -> String(pw) in commonPasswords }
    .build()
```

## Password authenticator: spi.Authenticator

`Authenticator` (package `spi`, `@FunctionalInterface`, `@Nullable AuthUser authenticate(String username, char[] password, AuthEventContext ctx) throws Exception`) is the only public seam that replaces login's password check (the user store plus the hasher). The Bearer and API key authenticators are internal to auth and cannot be replaced. Return null for every failure (do not tell an unknown name, a wrong password and a disabled user apart); a returned user must exist in the auth user store (tokens are issued for its id and every request reloads it). It is called inside a hash slot and failures wait for the failure floor, so a custom check cannot shorten the timing of a failure. An exception is a 503 JSON answer, never a login. The login limits, device trust and lockout stay as they are. Express counterpart: the verify callback of a passport `LocalStrategy`.

```java
UserStore users = new InMemoryUserStore();
Map<String, String> directory = new HashMap<>(); // stands in for an LDAP bind
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).users(users)
        .authenticator((name, password, ctx) -> new String(password).equals(directory.get(name))
                ? users.findByUsername(name) : null)
        .build();
```

```kotlin
// `store`: your UserStore; `directory`: the Map<String, String> standing in for an LDAP bind, as in the Java block above
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .users(store)
    .authenticator { name, password, _ -> if (String(password) == directory[name]) store.findByUsername(name) else null }
    .build()
```

## API key issuance endpoints: PATs and admin issuance

With `.apiKeys(store)` a user issues personal access tokens (PATs) for itself, and an admin issues keys for another user (usually a service account). Both are subject-required credential endpoints that accept JWT logins only (an expired token is 401 `invalid_token` with `WWW-Authenticate: Bearer error="invalid_token", ApiKey`; a cookie session needs a matching `X-CSRF-Token` and Origin, else 403 `csrf_failed`; an API key is 403 `api_key_not_allowed`). A key never makes a key.

| Endpoint | Body | Success |
|---|---|---|
| `POST /auth/me/apikeys` | `{"name","scopes","expiresInDays"?,"password"}` | 201 `{"id","prefix","name","ownerUserId","scopes","expiresAt","createdAt","lastUsedAt","issuedByUserId","status","key"}`; `key` is the plaintext key and is never shown again (`Cache-Control: no-store`) |
| `POST /auth/admin/apikeys` | `{"ownerUserId","name","scopes","expiresInDays"?,"password"}` | the same 201; `password` is **the caller's own** current password |
| `GET /auth/me/apikeys` / `DELETE /auth/me/apikeys/:id` | — | 200 the caller's keys (the 201 shape without `key`) / 204; another user's key id and an unknown id are both 404 `NOT_FOUND` (existence is not revealed); deleting needs no password |

**Decision order** (the earliest step answers; a refused request spends no hash slot and no attempt):

1. Body checks (all a Core `HttpException(400)`, code `BAD_REQUEST`): for admin issuance a string `ownerUserId`; a string `name` of 1–200 characters; a non-empty array of strings `scopes` in the scope grammar (`auth-*` only with `.allowAdminScopesOnApiKeys(true)`); an optional `expiresInDays` that is a JSON integer from 1 to `.maxApiKeyDays` (the maximum when absent; `"30"`, `1.5` and `0` are 400); a non-empty string `password` (absent or `null` is 400).
2. Admin issuance only: an unknown owner 404 → the grant rule (the caller must cover the new key's scopes and the owner's scopes, 403 `insufficient_scope`) → a disabled owner 400. A PAT needs the caller to hold every requested scope (403 `insufficient_scope`).
3. Password check: the per-user limit shared with `POST /auth/password` and `auth.users().checkPassword` (5 failures per 15 minutes; over it 429 `too_many_attempts` with `Retry-After` and no hash), then in one hash slot the caller is re-read and one PBKDF2 runs (an SSO user whose hash is null does the same work against a dummy hash). A wrong password is 401 `invalid_credentials`, sent after the failure floor (1.25T plus jitter); a right one is not delayed. A right password from a caller that was deleted or disabled meanwhile is 401 `invalid_token`. One user's password confirmations (`POST /auth/password`, both issuance endpoints, `auth.users().checkPassword`) run one at a time: while one runs, another endpoint request of the same user is 429 `too_many_attempts` with `Retry-After: 1`, and `checkPassword` waits up to 5 seconds (so parallel requests cannot outrun the limit).
4. Issuance: `insertIfUnderLimit` under the in-node per-owner lock. At `.maxKeysPerUser` (default 20) live keys the answer is 409 `api_key_limit` (expired keys and stale keys whose ownerVer is below the current `ver` do not count; under `MULTI_INSTANCE` a key that looks stale because its ownerVer is above the read `ver` still counts). The key's ownerVer is the `ver` of the caller row the password check read (PAT) or of the owner row step 2 read (admin issuance); the caller is the `issuedByUserId` and the actor of the `api_key_issued` audit event. `expiresAt` is the issuance time plus `expiresInDays` days.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).apiKeys(new InMemoryApiKeyStore()).maxApiKeyDays(90).build();
auth.install(app);
// POST /auth/me/apikeys  Authorization: Bearer <login token>
// {"name":"ci","scopes":["posts:read"],"expiresInDays":30,"password":"<my password>"}
// -> 201 {"id":"...","prefix":"Ab3dE5gH","status":"active","key":"nk_Ab3dE5gH_..."}
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).apiKeys(InMemoryApiKeyStore()).build()
auth.install(app)
// fetch("/auth/admin/apikeys", {method: "POST", headers: {Authorization: "Bearer " + adminToken},
//   body: JSON.stringify({ownerUserId: botId, name: "ci-bot", scopes: ["posts:read"], password: myPassword})})
```

A delete through a key endpoint (`DELETE /auth/me/apikeys/:id`, `DELETE /auth/admin/apikeys/:id`) and `auth.apiKeys().revoke` leave an `api_key_revoked` audit event: the deleted key is `revokedApiKeyId`, the target is the owner, a key with a recorded issuer also leaves `issuedByUserId`, and when an API-key actor deleted it, that actor's key is `apiKeyId`. When the key cannot be read before the delete, the revoke still goes on and its event has no `issuedByUserId`; for `auth.apiKeys().revoke` it has no target either. The event of `auth.apiKeys().revoke(keyId)`, which takes no request, has no actor, request id, IP or `apiKeyId`, even inside a route. To record them, use `auth.apiKeys().revoke(req, keyId)` (likewise for `issue`).

Express counterpart: an issuance route that asks for the password again, like GitHub's "generate a personal access token".

## Encrypting file secrets: AuthFileKey

Give `FileUserStore` and `KeySources.fromFile` an `AuthFileKey` (the master key) and the secret values those two file stores write are stored as `enc(<payload>)`. The payload is unpadded base64url of a format version byte (1), a random 96-bit IV from `SecureRandom`, the AES-256-GCM ciphertext and its 128-bit tag. The encryption key is derived from the master key per purpose with HMAC-SHA256, and the purpose and the record (the user id, the key file name) are bound in as additional authenticated data, so a value moved to another record or purpose does not open. Only `javax.crypto` is used, so it runs as is on Java 8 and Android API 21 and adds no dependency. Express counterpart: a session store that encrypts its content with a secret read from `process.env` (the `secret` of an `express-session` store).

- **Encrypted values:** each `passwordHash` of the `FileUserStore` users file (an offline cracking target) and the signing key in the `KeySources.fromFile` key file (whoever has it can forge tokens).
- **Values kept plain, and why:** the `tokenHash` of refresh records (the SHA-256 of a 256-bit random token: it can be neither reversed nor presented), the login throttle keys (SHA-256 identifiers of user and IP plus failure times, not credentials), the device trust `tokenId` and counters (identifiers of a signed token; forging one needs the signing key), the group file (no secret), and the user row fields `id`, `username`, `ver`, `enabled`, `roles` and `serviceAccount` (identifiers and state). `attributes` are values the app defines and are written as they are; do not put secrets in `attributes`.

| API | Description |
|---|---|
| `AuthFileKey.of(byte[])` | Copies its argument; fewer than 32 bytes is an `IllegalArgumentException` |
| `AuthFileKey.of(String)` | Standard or URL-safe base64 (padding optional, surrounding whitespace ignored); anything that does not decode to at least 32 bytes is an `IllegalArgumentException` stating the rule without echoing the input |
| `AuthFileKey.fromEnv()` | Reads the environment variable `NARU_AUTH_FILE_KEY` (`AuthFileKey.ENV_VARIABLE`) now: `null` when unset or empty, an `IllegalArgumentException` naming the variable (never its value) when invalid |
| `new FileUserStore(file, key)`, `new FileUserStore(file, maxFileBytes, logger, key)` | A `null` key is the same as `new FileUserStore(file)` and `new FileUserStore(file, maxFileBytes, logger)`. Like every `FileUserStore` constructor they read the file and throw `IOException` (checked), so the enclosing method declares or catches it |
| `KeySources.fromFile(file, key)` | A `null` key is the same as `KeySources.fromFile(file)` |

Making a key: 32 random bytes as base64, kept outside the data directory; for example put the output of `openssl rand -base64 32` in `NARU_AUTH_FILE_KEY`. `AuthFileKey.toString()` is a fixed text, the class is not `Serializable`, and neither the master key nor a derived key is written to a log, an exception message or a file.

The refresh, login throttle and device trust file stores that auth makes as defaults beside a keyed `FileUserStore` inherit the same key (there is no separate builder setting for it). None of those three files holds a secret value, so their fields and format versions stay as they are.

- **In PROD**, a `FileUserStore` without a key or a `KeySources.fromFile` without a key makes `build()` throw an `AuthConfigException` naming each option that lacks the key (`new FileUserStore(file, AuthFileKey)`, `KeySources.fromFile(file, AuthFileKey)`) and `NARU_AUTH_FILE_KEY`; when `KeySources.fromFile` itself has no key, its key file is then neither read nor created. An Auth that uses neither file store (for example `InMemoryUserStore` with `KeySources.fromEnv`) is not affected.
- A `FileUserStore` inside `ForwardingUserStore` decorators (any number of layers) is treated like a bare one: the PROD refusal, the DEV WARN, the doctor item `plain secret values in auth files` and the file state checks all apply to the wrapped `FileUserStore`. The refresh, login throttle and device trust file defaults are still made only for a bare `FileUserStore`.
- **In DEV** without a key, `build()` succeeds and logs one WARN per `build()` naming each file written plain and `NARU_AUTH_FILE_KEY`. The stores write what they always did: the users file is format version 1 with each `passwordHash` as the plain hash string, and the key file holds the raw 32 bytes.
- **Migration on first load:** when a keyed store first reads a plain file (a format version 1 users file, a raw key file), it rewrites every plain secret value of that file as `enc(…)` in one atomic write (the users file through the [store-file protocol](#durability-of-the-store-files), the key file through the same staging-directory-then-rename path it is created with; no `.bak` or temp file is left. On Windows, where a rename cannot replace an existing file, the key file moves to `.<name>.bak` for a moment before the new file is placed, so a stop in between makes the next read restore that backup instead of making a new key. When the raw `.bak` cannot be deleted after the new file is placed (the file is locked), `build()` does not fail: it uses the migrated key and logs one WARN naming the files, never the key; the next read of the encrypted key file deletes that `.bak`). The users file becomes format version 2 and a `null` hash stays `null`. The signing key bytes do not change, so tokens issued before keep verifying. The key file's value is bound to the file name, so keep, move or restore an encrypted key file under the same name (under another name it does not open). The users file logs one INFO with the number of hashes it encrypted when it migrates.
- Opening a migrated file without a key (DEV or PROD) fails with an `IOException` stating that the file holds encrypted values and that an `AuthFileKey` is needed, and the file is unchanged. A release that knows only users-file format version 1 refuses version 2 as an unknown version, so it never reads `enc(…)` as a hash.
- A value that does not open (a changed byte, another key, an unknown version, a malformed payload, a value moved from another record) makes the users file fail construction (and so the start) with a `FAIL` `IOException` naming the file and the field `passwordHash`, and the file is not overwritten; it is never treated as a wrong password. A key file that does not open fails `build()` with an error naming the file, and no new key is made.
- The doctor item `plain secret values in auth files` counts the non-null hashes of a `FileUserStore` without a key plus the raw key files: above zero it is a WARN, zero is an INFO line (`auth doctor: plain secret values in auth files: 0`), and an Auth that uses neither file store has no such item.
- Key rotation is not supported in 0.11.0: no public method re-encrypts the files under a new key, and opening them with another key fails as described above. The payload's version byte reserves room for a later rotation format.

```java
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.AuthFileKey;
import com.snoworca.naruserve.utils.auth.KeySources;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import java.io.File;

class FileSecrets {
    static Auth build() throws Exception {         // new FileUserStore(...) reads the file: IOException
        AuthFileKey key = AuthFileKey.fromEnv();   // NARU_AUTH_FILE_KEY, or null (PROD build() refuses that)
        File dataDir = new File("/var/lib/app");
        return Auth.builder(KeySources.fromFile(new File(dataDir, "naru-auth.key"), key))
                .users(new FileUserStore(new File(dataDir, "users.json"), key))
                .build();
    }
}
```

```kotlin
// `dataDir`: the data directory, File("/var/lib/app") in the Java block above
val key: AuthFileKey? = AuthFileKey.fromEnv()
val auth = Auth.builder(KeySources.fromFile(File(dataDir, "naru-auth.key"), key))
    .users(FileUserStore(File(dataDir, "users.json"), key))
    .build()
```

## Glossary

The auth terms this page uses. The last column names what Express and passport call something similar.

| Term | Meaning | In Express |
|---|---|---|
| **principal** | The authenticated user behind a request: the `AuthPrincipal` that `auth.user(req)` returns, with id, name, roles and scopes | `req.user` |
| **scope** | One thing a caller may do, written `resource:action` (for example `posts:write`) and checked by `auth.require(...)` | a scope of `express-jwt-authz` |
| **role** | A name for a set of scopes (for example `editor`), given to users and groups and resolved to scopes by `RolePolicy` or `RoleStore` | a role of `accesscontrol` |
| **grant** | A scope on the giving side, that is a scope a role gives; wildcards such as `posts:*` and `*` appear only here. [The grant rule of admin writes](#the-grant-rule-of-admin-writes) requires the actor's scopes to cover every scope the target gains | `accesscontrol`'s `grant(...)` |
| **family** | The chain of refresh tokens that starts at one login (the `fam` claim). Each refresh continues the family with a new token, and a used token coming back revokes the whole family | the token family of refresh-token rotation |
| **ver (token version)** | A number stored per user. A logout of all devices, a password change, a disable and a recovery raise it by 1, and a token with another `ver` is 401 `invalid_token` | a `tokenVersion` column of the user row |
| **kid (key id)** | The name of the signing key in a token header. After a key rotation the kid finds the key that verifies an older token ([Key sets and rotation](#key-sets-and-rotation-keysource-jwtkeys-keysources)) | the `kid` of a JWKS |
| **doctor (startup checks)** | The configuration checks run at start. A FAIL stops the start, a WARN is only logged ([Startup checks](#startup-checks-doctor)) | a config check in a start script |
| **DEV and PROD** | The two `AuthMode` modes. DEV relaxes checks for local development (a temporary key, plain text allowed); PROD enforces all of them ([AuthMode](#authmode)) | `NODE_ENV=development` / `production` |
| **DAG** | A directed acyclic graph. A group may have several parents, but a cycle is refused with 409 `group_cycle` ([Groups](#groups-groupstore-and-grouplimits)) | the parent edges of a group table |
| **CIDR** | The notation for an address range such as `10.0.0.0/8`; `app.trustProxyCidrs(...)` trusts proxies in the range | `app.set('trust proxy', '10.0.0.0/8')` |
| **hop** | One proxy a request passed through; `app.trustProxyHops(1)` trusts the one proxy right in front | `app.set('trust proxy', 1)` |
| **PBKDF2** | The default password hash; its iteration count (the cost) is fitted to the device speed ([Password hashing](#password-hashing-passwordhasher-hashcost)) | `crypto.pbkdf2` |
| **A1–A6, G1–G9** | The short names of the admin endpoints: A for user and G for group endpoints ([Admin endpoints](#admin-endpoints-authadminroutes)) | the routes of an admin `express.Router()` |

Express equivalent: the last column of the table. The code below shows the same terms in use.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())   // the two grants of the role editor
    .build();
app.use(auth);
app.get("/me", (req, res) -> {
    AuthPrincipal p = auth.user(req);                                                   // principal
    res.send(p.getUsername() + " " + p.hasRole("editor") + " " + p.hasScope("posts:write"));
});
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
    .build()
app.use(auth)
app.get("/me") { req, res ->
    val p = auth.user(req)
    res.send(p.username + " " + p.hasRole("editor") + " " + p.hasScope("posts:write"))
}
```
