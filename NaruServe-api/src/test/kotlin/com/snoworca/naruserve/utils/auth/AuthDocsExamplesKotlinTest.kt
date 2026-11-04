package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.testing.TestApp
import com.snoworca.naruserve.utils.TimeSource
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore
import com.snoworca.naruserve.utils.auth.apikey.ApiKey
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStatus
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey
import com.snoworca.naruserve.utils.auth.jwt.Jwt
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys
import com.snoworca.naruserve.utils.auth.password.DigestEncoding
import com.snoworca.naruserve.utils.auth.password.HashCost
import com.snoworca.naruserve.utils.auth.password.PasswordHasher
import com.snoworca.naruserve.utils.auth.password.PasswordHashers
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher
import com.snoworca.naruserve.utils.auth.spi.ForwardingLoginThrottle
import com.snoworca.naruserve.utils.auth.spi.KeySource
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy
import com.snoworca.naruserve.utils.auth.user.AuthIds
import com.snoworca.naruserve.utils.auth.user.AuthUser
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin blocks of docs/api/auth.md, compiled and run (FR-KT-004 markers). The test JVM has no JWT_SECRET, so
 * the blocks that build an Auth use a fixed test secret; that is the adaptation they record.
 */
class AuthDocsExamplesKotlinTest {

    private val secret = AuthTestSupport.SECRET

    private fun configure(routes: (RestApp) -> Unit): TestApp {
        val t = TestApp.create()
        routes(t.app())
        return t
    }

    @Test
    fun `docs auth quick start`() {
        // docs-example: auth.md#1
        // adapted: a fixed test secret instead of JWT_SECRET, and a TestApp instead of RestApp.start(8080)
        val auth = Auth.builder(secret)
            .mode(AuthMode.fromEnv())
            .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
            .permit("/health")
            .build()
        val t = configure { app ->
            auth.install(app)
            app.get("/health") { _, res -> res.send("ok") }
            app.post("/posts", auth.require("posts:write")) { req, res -> res.status(201).send(auth.user(req).id) }
        }
        assertEquals("ok", t.send("GET", "/health").text())
        assertEquals(401, t.send("POST", "/posts").status())
    }

    @Test
    fun `docs auth guards`() {
        // docs-example: auth.md#4
        // adapted: a fixed test secret instead of JWT_SECRET, on a TestApp
        val auth = Auth.builder(secret).build()
        val app = TestApp.create().app()
        auth.install(app)
        app.get("/admin/stats", auth.requireRole("admin")) { _, res -> res.send("stats") }
        app.patch("/users/:id", auth.check { p, r -> p.id == r.param("id") || p.hasScope("users:write") }) { _, res ->
            res.send("updated")
        }
        assertTrue(app.registrations().size > 3)
    }

    @Test
    fun `docs auth responses`() {
        // docs-example: auth.md#5
        // adapted: a fixed test secret instead of JWT_SECRET
        val auth = Auth.builder(secret)
            .responses { _, res, _, code, _ -> res.type("application/json").send("{\"err\":\"$code\"}") }
            .build()
        val t = configure { auth.install(it) }
        assertEquals("{\"err\":\"unauthenticated\"}", t.send("GET", "/x").text())
    }

    @Test
    fun `docs auth forwarding store`() {
        // docs-example: auth.md#12
        // adapted: a fixed test secret instead of JWT_SECRET
        val store = object : ForwardingUserStore(InMemoryUserStore()) {
            override fun findById(id: String): AuthUser? = super.findById(id)
        }
        val auth = Auth.builder(secret).users(store).build()
        assertTrue(auth.userStore() === store)
    }

    @Test
    fun `docs auth users facade`() {
        // docs-example: auth.md#13
        // adapted: a fixed test secret instead of JWT_SECRET
        val auth = Auth.builder(secret).build()
        val alice = auth.users().create("Alice", "correct horse".toCharArray(), "editor")
        auth.users().update(alice.id!!) { it.toBuilder().attribute("nickname", "Al").build() }
        assertEquals("Al", auth.users().findById(alice.id!!)!!.getAttribute("nickname"))
    }

    @Test
    fun `docs auth group store`() {
        // docs-example: auth.md#14
        // adapted: a fixed test secret instead of JWT_SECRET
        val groups = InMemoryGroupStore()
        groups.createGroup("eng")
        groups.grantRole("eng", "editor")
        val auth = Auth.builder(secret).groups(groups)
            .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
            .build()
        val bob = auth.users().create("bob", "bob-password-1".toCharArray())
        groups.addMember("eng", bob.id!!)
        assertEquals(setOf("eng"), groups.groupsOfUser(bob.id!!))
        assertTrue(auth.toString().startsWith("Auth{"))
    }

    @Test
    fun `docs auth own database`() {
        // docs-example: auth.md#15
        // adapted: AppStores (test implementations of the SPIs over one shared backing store, no JDBC driver) stand in for the app's SQL stores, and a fixed test secret for JWT_SECRET
        val db = AppStores()
        val auth = Auth.builder(secret).hashIterations(1000)
            .deployment(Deployment.MULTI_INSTANCE)
            .users(db.users())
            .refreshTokens(db.refreshTokens())
            .loginThrottle(db.loginThrottle())
            .deviceTrust(db.deviceTrust())
            .apiKeys(db.apiKeys())
            .build()
        assertTrue(db.users().supportsAtomicFieldWrites())
        assertTrue(db.loginThrottle().usesLimitArguments())
        assertTrue(db.apiKeys().supportsAtomicLimitInsert())
        val alice = auth.users().create("alice", "a long enough passphrase".toCharArray())
        assertEquals("alice", db.users().findById(alice.id!!)?.username)
    }

    @Test
    fun `docs auth time source`() {
        // docs-example: auth.md#16
        val fixed = TimeSource { 1_000L }
        assertEquals(1000L, fixed.nowMillis())
    }
    @Test
    fun `docs auth password hashing`() {
        // docs-example: auth.md#18
        val hasher: PasswordHasher = Pbkdf2PasswordHasher(600_000)
        val stored = hasher.hash("correct horse".toCharArray())
        val ok = hasher.verify("correct horse".toCharArray(), stored)
        val cost = HashCost.calibrate(250)
        println("ok=$ok rehash=${hasher.needsRehash(stored)} target=${cost.targetMillis} ms")
        assertTrue(ok)
        val chain: PasswordHasher = PasswordHashers.chain(hasher, PasswordHashers.bcrypt(),
            PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX))
        val md5 = "legacy-md5$5f4dcc3b5aa765d61d8327deb882cf99"
        println("legacy=${chain.verify("password".toCharArray(), md5)} rehash=${chain.needsRehash(md5)}")
        assertTrue(chain.verify("password".toCharArray(), md5))
        assertTrue(chain.needsRehash(md5))
    }

    @Test
    fun `docs auth key sets`() {
        // docs-example: auth.md#19
        val keys = JwtKeys.builder()
            .signingKey("k2", "a-new-32-byte-or-longer-signing-key!".toByteArray())
            .verificationKey("k1", "the-old-32-byte-or-longer-signing-key".toByteArray())
            .build()
        val auth = Auth.builder(KeySources.rotating(keys)).build()
        val vault = KeySource { "a-key-read-from-the-vault-32-bytes!!".toByteArray() }
        assertEquals("k2", auth.keySet().signingKid)
        assertEquals(1, vault.keys().kids.size)
    }

    @Test
    fun `docs auth error layer`() {
        // docs-example: auth.md#20
        // adapted: a fixed test secret instead of JWT_SECRET, a TestApp, and the late batch added after start
        val auth = Auth.builder(secret).build()
        val t = configure { app ->
            auth.install(app)
            app.post("/users") { _, res -> res.status(201).send(auth.users().create("neo", "pw-pw-pw-pw".toCharArray()).id.orEmpty()) }
            app.onError(auth.errors())
        }
        t.start()
        t.app().get("/late") { _, res -> res.send("late") }
        t.app().use(auth.errorMiddleware())
        assertEquals(401, t.send("POST", "/users").status())
    }

    @Test
    fun `docs auth first account`() {
        // docs-example: auth.md#21
        // adapted: a fixed test secret instead of JWT_SECRET
        val auth = Auth.builder(secret)
            .user("admin", System.getenv("NARU_ADMIN_PASSWORD"), "admin")
            .recovery(false)
            .build()
        assertTrue(auth.toString().startsWith("Auth{"))
    }

    @Test
    fun `docs auth login throttle`() {
        // docs-example: auth.md#23
        val counted = object : ForwardingLoginThrottle(InMemoryLoginThrottle()) {
            override fun recordFailure(key: String, max: Int, windowMillis: Long): Long {
                println("failed: $key")
                return super.recordFailure(key, max, windowMillis)
            }
        }
        val blockedFor = counted.retryAfter("alice|203.0.113.7", 5, 15 * 60_000L)
        assertEquals(0L, blockedFor)
        // adapted: the docs read JWT_SECRET from the environment; the test passes the test secret
        val auth = Auth.builder(AuthTestSupport.SECRET)
            .loginThrottle(counted)
            .loginFailureLimits(3, 20, 10, 15 * 60_000L)
            .build()
        assertEquals(counted, auth.loginThrottle())
    }

    @Test
    fun `docs auth standalone jwt`() {
        // docs-example: auth.md#24
        // adapted: a fixed key and clock instead of the app's own
        val key = ByteArray(32) { it.toByte() }
        val now = System.currentTimeMillis()
        val jwt = Jwt.builder(JwtKeys.of("k1", key)).issuer("hub").build()
        val t = jwt.sign(JwtClaims.builder().subject("device-7").type("device").expiresAt(now + 3_600_000L).build())
        val subject: String? = jwt.verify(t, "device").subject
        assertEquals("device-7", subject)
    }

    @Test
    fun `docs auth protect only`() {
        // docs-example: auth.md#25
        // adapted: a fixed test secret instead of JWT_SECRET
        val auth = Auth.builder(secret).protectOnly("/api").permitWebSocket("/live").build()
        assertTrue(auth.describe().contains("protected: /api, /auth"))
    }

    @Test
    fun `docs auth describe`() {
        // docs-example: auth.md#26
        // adapted: the auth built here instead of the app's
        val auth = Auth.builder(secret).build()
        println(auth.describe())
        assertTrue(auth.describe().contains("mode: PROD"))
    }

    @Test
    fun `docs auth audit sink`() {
        // docs-example: auth.md#27
        // adapted: a fixed test secret instead of JWT_SECRET
        val auth = Auth.builder(secret).audit { e -> println("${e.type} ${e.actorId}") }.build()
        assertTrue(auth.toString().startsWith("Auth{"))
    }

    @Test
    fun `docs auth refresh store`() {
        // docs-example: auth.md#28
        // adapted: a fixed test secret instead of JWT_SECRET
        val auth = Auth.builder(secret).refreshTokens(InMemoryRefreshTokenStore()).build()
        assertTrue(auth.describe().contains("refresh tokens: InMemoryRefreshTokenStore"))
    }

    @Test
    fun `docs auth api key types`() {
        // docs-example: auth.md#29
        // adapted: the row of the Java example built here
        val row = StoredApiKey.builder().id("k1").prefix("Ab3dE5gH")
            .secretHash("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
            .ownerUserId("42").ownerVer(0L).name("ci-bot").scopes(setOf("posts:read"))
            .createdAt(System.currentTimeMillis()).build()
        val expires: Long? = ApiKey.of(row, ApiKeyStatus.ACTIVE).expiresAt
        assertEquals(null, expires)
    }

    @Test
    fun `docs auth tokens lockout websocket`() {
        // docs-example: auth.md#35
        // adapted: a fixed test secret and a test app
        val auth = Auth.builder(secret)
            .lockout(Lockout.after(5).forMinutes(15))
            .userCacheTtl(5_000)
            .build()
        val alice = auth.users().create("alice", "alice-password-1".toCharArray())
        val pair: TokenPair = auth.tokens().issue(alice)
        val app = TestApp.create().app()
        auth.install(app)
        app.ws("/ws", auth.webSocket { req, ws -> ws.sendText("hi " + auth.user(req).id) })
        assertEquals("Bearer", pair.tokenType)
    }

    // CON-AUTH-005 AC-3 AC-4: the Kotlin blocks added to the API entries that had none, compiled and run

    @Test
    fun `docs auth builder options`() {
        // docs-example: auth.md#2
        // adapted: a fixed test secret instead of JWT_SECRET
        val auth = Auth.builder(secret)
            .profile(AuthProfile.STRICT)
            .transport(TokenTransport.BOTH)
            .permitPrefix("/public")
            .build()
        assertEquals(5 * 60_000L, auth.tokens().accessTtl)
        assertTrue(auth.describe().contains("transport: BOTH"))
    }

    @Test
    fun `docs auth install order`() {
        // docs-example: auth.md#3
        // adapted: a fixed test secret instead of JWT_SECRET, and a TestApp instead of RestApp.start(8080)
        val auth = Auth.builder(secret).build()
        val t = configure { app ->
            app.cors { c -> c.allowedOrigins("https://app.example.com") }
            auth.install(app)
            app.get("/me") { req, res -> res.send(auth.user(req).id) }
            app.onError(auth.errors())
        }
        assertEquals(401, t.send("GET", "/me").status())
    }

    @Test
    fun `docs auth config file`() {
        // docs-example: auth.md#6
        // adapted: a fixed test secret instead of JWT_SECRET
        val props = java.util.Properties()
        props.setProperty("naru.auth.profile", "strict")
        val auth = Auth.builder(secret).config(AuthConfig.from(props)).build()
        println(AuthConfig.KEYS)
        assertEquals(5 * 60_000L, auth.tokens().accessTtl)
    }

    @Test
    fun `docs auth mode`() {
        // docs-example: auth.md#7
        // adapted: a fixed test secret instead of JWT_SECRET
        val auth = Auth.builder(secret).mode(AuthMode.fromEnv()).build()
        if (auth.mode() == AuthMode.DEV) println("dev relaxations on")
        assertEquals(AuthMode.fromEnv(), auth.mode())
    }

    @Test
    fun `docs auth rejection`() {
        val auth = Auth.builder(secret).build()
        val t = configure { app ->
            // docs-example: auth.md#8
            app.post("/orders") { req, res ->
                if (req.header("X-Tenant") == null) throw AuthRejection(422, "tenant_required", "Send X-Tenant")
                res.status(201).send("ok")
            }
            println(AuthErrorCode.INVALID_TOKEN.status)
            app.onError(auth.errors())
        }
        val r = t.send("POST", "/orders")
        assertEquals(422, r.status())
        assertTrue(r.text(), r.text().contains("tenant_required"))
    }

    @Test
    fun `docs auth events`() {
        // docs-example: auth.md#9
        val events = object : AuthEvents {
            override fun beforeUserCreate(candidate: AuthUser, ctx: AuthEventContext) {
                if (ctx.origin == UserOrigin.REGISTER && !candidate.username.endsWith("@example.com")) {
                    throw AuthRejection(422, "domain_not_allowed", "Use your company address")
                }
            }
        }
        val ctx = AuthEventContext(UserOrigin.REGISTER, null, null, 0L)
        val refused = try {
            events.beforeUserCreate(AuthUser.builder().username("bob@other.org").build(), ctx)
            null
        } catch (e: AuthRejection) {
            e
        }
        assertEquals("domain_not_allowed", refused?.code)
        events.beforeUserCreate(AuthUser.builder().username("ann@example.com").build(), ctx)
    }

    @Test
    fun `docs auth user value and ids`() {
        // docs-example: auth.md#11
        val u = AuthUser.builder().username("alice").roles("editor").attribute("team", "web").build()
        val team: String? = u.toBuilder().attribute("team", "api").build().getAttribute("team")
        val numeric: Long? = AuthIds.parseLong("42")
        assertEquals("api", team)
        assertEquals(42L, numeric)
        assertEquals(null, AuthIds.parseLong("007"))
    }

    @Test
    fun `docs auth transport`() {
        // docs-example: auth.md#22
        // adapted: a fixed test secret instead of JWT_SECRET
        val auth = Auth.builder(secret).transport(TokenTransport.BOTH).build()
        assertTrue(auth.describe().contains("transport: BOTH"))
    }

    @Test
    fun `docs auth cookies`() {
        // docs-example: auth.md#30
        // adapted: a fixed test secret instead of JWT_SECRET
        val auth = Auth.builder(secret)
            .transport(TokenTransport.COOKIE)
            .cookies { c -> c.domain("example.com") }
            .build()
        assertTrue(auth.describe().contains("transport: COOKIE"))
    }

    @Test
    fun `docs auth token service`() {
        val auth = Auth.builder(secret).build()
        // docs-example: auth.md#31
        val alice = auth.users().create("alice", "correct horse".toCharArray())
        val pair = auth.tokens().issue(alice)
        val claims = auth.tokens().verifyAccess(pair.accessToken)
        println("${claims.subject} expires in ${pair.expiresIn} s")
        assertEquals(alice.id, claims.subject)
        assertEquals(900L, pair.expiresIn)
    }

    @Test
    fun `docs auth principal cache`() {
        // docs-example: auth.md#32
        // adapted: a fixed test secret instead of JWT_SECRET, /feed permitted, on a TestApp with auth installed
        val auth = Auth.builder(secret).userCacheTtl(0).statelessAccess(true).permit("/feed").build()
        val t = configure { app ->
            auth.install(app)
            app.get("/feed") { req, res -> res.send(auth.userOrNull(req)?.id ?: "anonymous") }
        }
        assertEquals("anonymous", t.send("GET", "/feed").text())
    }

    @Test
    fun `docs auth websocket`() {
        val auth = Auth.builder(secret).build()
        val app = TestApp.create().app()
        auth.install(app)
        // docs-example: auth.md#33
        app.ws("/ws", auth.webSocket { req, ws -> ws.sendText("hi " + auth.user(req).id) })
        assertTrue(app.registrations().size > 3)
    }

    @Test
    fun `docs auth lockout`() {
        // docs-example: auth.md#34
        // adapted: a fixed test secret instead of JWT_SECRET
        val lockout = Lockout.after(5).forMinutes(15)
        val auth = Auth.builder(secret).lockout(lockout).build()
        println("${lockout.failures} failures, ${lockout.durationMillis} ms")
        assertEquals(5, lockout.failures)
        assertEquals(15 * 60_000L, lockout.durationMillis)
        assertTrue(auth.toString().startsWith("Auth{"))
    }

    @Test
    fun `docs auth insecure transport`() {
        // docs-example: auth.md#42
        // adapted: a fixed test secret instead of JWT_SECRET
        val auth = Auth.builder(secret)
            .allowInsecureTransport("LAN device server; TLS adapter pending")
            .build()
        println(auth.tokens().accessTtl)
        assertEquals(5 * 60_000L, auth.tokens().accessTtl)
        assertEquals(24 * 3_600_000L, auth.tokens().refreshTtl)
    }
}
