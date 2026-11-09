package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.testing.InMemoryHttpAdapter
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
import com.snoworca.naruserve.testing.TestApp
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore
import com.snoworca.naruserve.utils.auth.password.HashCost
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy
import com.snoworca.naruserve.utils.auth.rbac.RoleStore
import com.snoworca.naruserve.utils.auth.testing.AuthTesting
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** The Kotlin blocks of the "More examples" section of the auth guide (OPS-DX-006 AC-3), compiled and run. */
class AuthMoreExamplesKotlinTest {

    /** A JDBC stand-in answering {@code SELECT scope FROM role_scopes WHERE role = ?} from {@code table}. */
    private fun dataSource(table: Map<String, List<String>>): DataSource {
        fun <T> proxy(type: Class<T>, answer: (String, Array<out Any?>?) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, args -> answer(m.name, args) })

        return proxy(DataSource::class.java) { name, _ ->
            check(name == "getConnection") { name }
            proxy(Connection::class.java) { cName, cArgs ->
                when (cName) {
                    "prepareStatement" -> {
                        assertEquals("SELECT scope FROM role_scopes WHERE role = ?", cArgs!![0])
                        var role = ""
                        proxy(PreparedStatement::class.java) { sName, sArgs ->
                            when (sName) {
                                "setString" -> { role = sArgs!![1] as String; null }
                                "executeQuery" -> {
                                    val rows = (table[role] ?: emptyList()).iterator()
                                    var current: String? = null
                                    proxy(ResultSet::class.java) { rName, _ ->
                                        when (rName) {
                                            "next" -> if (rows.hasNext()) { current = rows.next(); true } else false
                                            "getString" -> current
                                            "close" -> null
                                            else -> throw UnsupportedOperationException(rName)
                                        }
                                    }
                                }
                                "close" -> null
                                else -> throw UnsupportedOperationException(sName)
                            }
                        }
                    }
                    "close" -> null
                    else -> throw UnsupportedOperationException(cName)
                }
            }
        }
    }

    // OPS-DX-006 AC-3: what the RoleStore example's prose says a throwing scopesOf does: 503 on a protected path,
    // guard or not, anonymous on a public path; with .statelessAccess(true) a guarded route answers 503, an unguarded
    // one keeps working and auth.loadUser(req) returns null. It sits before the first marker, outside every extent.
    @Test
    fun `OPS-DX-006 AC-3 a throwing RoleStore answers as the prose says`() {
        var failing = false
        val roles = RoleStore { role ->
            if (failing) throw java.sql.SQLException("the database is down")
            if (role == "editor") setOf("posts:write") else emptySet()
        }

        // userCacheTtl(0): every request reads the store, so no principal cached before the failure hides it
        val auth = AuthTestAccess.fastBuilder(AuthTestSupport.SECRET).roles(roles).permit("/open").userCacheTtl(0).build()
        val t = TestApp.create()
        t.app().use(auth)
        t.app().get("/plain") { _, res -> res.send("plain") }
        t.app().post("/posts", auth.require("posts:write")) { _, res -> res.status(201).send("created") }
        t.app().get("/open") { req, res -> res.send(if (auth.userOrNull(req) == null) "anonymous" else "signed in") }
        val bearer = "Bearer " + AuthTesting.tokenForUserId(auth,
            auth.users().create("ed", "ed-password-1".toCharArray(), "editor").id!!)

        val stateless = AuthTestAccess.fastBuilder(AuthTestSupport.SECRET).roles(roles).statelessAccess(true)
            .userCacheTtl(0).build()
        val s = TestApp.create()
        s.app().use(stateless)
        s.app().get("/plain") { req, res -> res.send("loaded " + (stateless.loadUser(req) != null)) }
        s.app().post("/posts", stateless.require("posts:write")) { _, res -> res.status(201).send("created") }
        val statelessBearer = "Bearer " + AuthTesting.tokenForUserId(stateless,
            stateless.users().create("ed", "ed-password-1".toCharArray(), "editor").id!!)

        // while the store answers: the guard passes, the public path sees the user, loadUser loads
        assertEquals(201, t.send("POST", "/posts", "Authorization", bearer).status())
        assertEquals("plain", t.send("GET", "/plain", "Authorization", bearer).text())
        assertEquals("signed in", t.send("GET", "/open", "Authorization", bearer).text())
        assertEquals("loaded true", s.send("GET", "/plain", "Authorization", statelessBearer).text())

        failing = true
        val plain = t.send("GET", "/plain", "Authorization", bearer)
        assertEquals(plain.text(), 503, plain.status())
        val guarded = t.send("POST", "/posts", "Authorization", bearer)
        assertEquals(guarded.text(), 503, guarded.status())
        val open = t.send("GET", "/open", "Authorization", bearer)
        assertEquals(open.text(), 200, open.status())
        assertEquals("anonymous", open.text())

        val unguarded = s.send("GET", "/plain", "Authorization", statelessBearer)
        assertEquals(unguarded.text(), 200, unguarded.status())
        assertEquals("loaded false", unguarded.text())
        val statelessGuarded = s.send("POST", "/posts", "Authorization", statelessBearer)
        assertEquals(statelessGuarded.text(), 503, statelessGuarded.status())
    }

    // OPS-DX-006 AC-3: auth.authenticated() reads the user on one route of a stateless app
    @Test
    fun `OPS-DX-006 AC-3 authenticated reads the user on one route`() {
        // docs-example: auth.md#52
        // adapted: a fixed test secret instead of JWT_SECRET and cheap hashing; a TestApp drives the routes
        val auth = AuthTestAccess.fastBuilder(AuthTestSupport.SECRET).statelessAccess(true).build()
        val t = TestApp.create()
        val app = t.app()
        app.use(auth)
        app.get("/feed") { req, res -> res.send("feed for " + auth.user(req).id) }
        app.post("/orders", auth.authenticated()) { req, res -> res.status(201).send("order by " + auth.user(req).username) }

        val token = AuthTesting.tokenFor(auth, "alice")
        val id = auth.users().findByUsername("alice")!!.id!!
        val feed = t.send("GET", "/feed", "Authorization", "Bearer $token")
        assertEquals(feed.text(), 200, feed.status())
        assertEquals("feed for $id", feed.text())
        val order = t.send("POST", "/orders", "Authorization", "Bearer $token")
        assertEquals(order.text(), 201, order.status())
        assertEquals("order by alice", order.text())

        assertTrue(auth.users().delete(id))
        val staleFeed = t.send("GET", "/feed", "Authorization", "Bearer $token")
        assertEquals(staleFeed.text(), 200, staleFeed.status())
        val refused = t.send("POST", "/orders", "Authorization", "Bearer $token")
        assertEquals(refused.text(), 401, refused.status())
    }

    // OPS-DX-006 AC-3: auth.loadUser(req) loads the user in a handler and returns null for a deleted one
    @Test
    fun `OPS-DX-006 AC-3 loadUser returns the user or null`() {
        // docs-example: auth.md#53
        // adapted: a fixed test secret instead of JWT_SECRET and cheap hashing; a TestApp drives the route
        val auth = AuthTestAccess.fastBuilder(AuthTestSupport.SECRET).statelessAccess(true).build()
        val t = TestApp.create()
        val app = t.app()
        app.use(auth)
        app.get("/greeting") { req, res ->
            val p = auth.loadUser(req)
            res.send(if (p == null) "hello" else "hello " + p.username)
        }

        val token = AuthTesting.tokenFor(auth, "alice")
        assertEquals("hello alice", t.send("GET", "/greeting", "Authorization", "Bearer $token").text())
        assertTrue(auth.users().delete(auth.users().findByUsername("alice")!!.id!!))
        val gone = t.send("GET", "/greeting", "Authorization", "Bearer $token")
        assertEquals(gone.text(), 200, gone.status())
        assertEquals("hello", gone.text())
    }

    // OPS-DX-006 AC-3: a RoleStore over a SQL table grants the scopes of its rows
    @Test
    fun `OPS-DX-006 AC-3 a database RoleStore`() {
        // docs-example: auth.md#54
        // adapted: a JDBC stand-in for the app's DataSource, a fixed test secret and cheap hashing; the block's
        // imports sit at the top of this file (Auth is in this test's package)
        class SqlRoles(private val ds: DataSource) : RoleStore {
            override fun scopesOf(role: String): Set<String> {
                val scopes = HashSet<String>()
                ds.connection.use { c ->
                    c.prepareStatement("SELECT scope FROM role_scopes WHERE role = ?").use { s ->
                        s.setString(1, role)
                        s.executeQuery().use { r ->
                            while (r.next()) scopes.add(r.getString(1))
                        }
                    }
                }
                return scopes
            }
        }

        val dataSource = dataSource(mapOf("editor" to listOf("posts:read", "posts:write")))
        val auth = AuthTestAccess.fastBuilder(AuthTestSupport.SECRET).roles(SqlRoles(dataSource)).build()
        assertEquals(setOf("posts:read", "posts:write"), SqlRoles(dataSource).scopesOf("editor"))
        assertEquals(emptySet<String>(), SqlRoles(dataSource).scopesOf("nobody"))
        val t = TestApp.create()
        val app = t.app()
        app.use(auth)
        app.post("/posts", auth.require("posts:write")) { _, res -> res.status(201).send("created") }
        val editor = auth.users().create("ed", "ed-password-1".toCharArray(), "editor")
        val viewer = auth.users().create("vi", "vi-password-1".toCharArray(), "viewer")
        val ok = t.send("POST", "/posts", "Authorization", "Bearer " + AuthTesting.tokenForUserId(auth, editor.id!!))
        assertEquals(ok.text(), 201, ok.status())
        val no = t.send("POST", "/posts", "Authorization", "Bearer " + AuthTesting.tokenForUserId(auth, viewer.id!!))
        assertEquals(no.text(), 403, no.status())
    }

    // OPS-DX-006 AC-3: auth.users().rename normalizes, keeps the id and refuses a taken name
    @Test
    fun `OPS-DX-006 AC-3 rename`() {
        // docs-example: auth.md#55
        // adapted: a fixed test secret instead of JWT_SECRET and cheap hashing; asserts follow the printed values
        val auth = AuthTestAccess.fastBuilder(AuthTestSupport.SECRET).build()
        val ann = auth.users().create("ann", "ann-password-1".toCharArray())
        auth.users().create("bob", "bob-password-1".toCharArray())
        val renamed = auth.users().rename(ann.id!!, "Annie")
        println(renamed?.username)
        var taken = false
        try {
            auth.users().rename(ann.id!!, "bob")
        } catch (e: DuplicateUserException) {
            println("taken: " + e.message)
            taken = true
        }
        assertEquals("annie", renamed?.username)
        assertEquals(ann.id, renamed?.id)
        assertTrue(taken)
        assertEquals(ann.id, auth.users().findByUsername("annie")?.id)
    }

    // OPS-DX-006 AC-3: PATCH /auth/me changes only the attributes .allowedAttributes names
    @Test
    fun `OPS-DX-006 AC-3 allowedAttributes and PATCH auth me`() {
        // docs-example: auth.md#56
        // adapted: a fixed test secret instead of JWT_SECRET and cheap hashing; an in-memory app sends the PATCH calls
        val auth = AuthTestAccess.fastBuilder(AuthTestSupport.SECRET)
            .allowedAttributes("displayName", "locale")
            .build()
        val adapter = InMemoryHttpAdapter("127.0.0.1", true)
        val app = RestApp.builder().httpAdapter(adapter).jsonCodec(AuthTestAccess.json()).build()
        app.use(auth)
        app.get("/hello") { req, res ->
            val p = auth.user(req)
            res.send("hello " + (p.attributes["displayName"] ?: p.username))
        }
        app.listen(0)
        try {
            val token = AuthTesting.tokenFor(auth, "alice")
            val id = auth.users().findByUsername("alice")!!.id!!
            auth.users().update(id) { it.toBuilder().attribute("locale", "ko").build() }
            fun patch(body: String) = adapter.send(ClientRequest.json("PATCH", "/auth/me", body)
                .header("Authorization", "Bearer $token"))
            fun hello() = adapter.send(ClientRequest.of("GET", "/hello").header("Authorization", "Bearer $token")).text()
            assertEquals("hello alice", hello())

            val ok = patch("{\"attributes\":{\"displayName\":\"Al\",\"locale\":null}}")
            assertEquals(ok.text(), 200, ok.status())
            val stored = auth.users().findById(id)!!
            assertEquals("Al", stored.attributes["displayName"])
            assertTrue(stored.attributes.toString(), !stored.attributes.containsKey("locale"))
            assertEquals("hello Al", hello())

            val refused = patch("{\"attributes\":{\"plan\":\"pro\"}}")
            assertEquals(refused.text(), 400, refused.status())
            assertTrue(refused.text(), refused.text().contains("\"BAD_REQUEST\""))
            assertTrue(!auth.users().findById(id)!!.attributes.containsKey("plan"))
        } finally {
            app.shutdown(5, TimeUnit.SECONDS)
        }
    }

    // OPS-DX-006 AC-3: AuthTesting.apiKeyFor issues a real key a guarded route accepts
    @Test
    fun `OPS-DX-006 AC-3 apiKeyFor`() {
        // docs-example: auth.md#57
        // adapted: a fixed test secret instead of JWT_SECRET and cheap hashing; a TestApp sends the key; the block's
        // imports sit at the top of this file (Auth is in this test's package)
        val auth = AuthTestAccess.fastBuilder(AuthTestSupport.SECRET)
            .roles(RolePolicy.builder().role("reporter", "reports:read").build())
            .apiKeys(InMemoryApiKeyStore())
            .build()
        val t = TestApp.create()
        val app = t.app()
        app.use(auth)
        app.get("/reports", auth.require("reports:read")) { _, res -> res.send("reports") }
        val bot = auth.users().create("report-bot", "bot-password-1".toCharArray(), "reporter")
        val key = AuthTesting.apiKeyFor(auth, bot.id!!, "reports:read")
        val r = t.send("GET", "/reports", "Authorization", "ApiKey $key")
        assertEquals(r.text(), 200, r.status())
        assertEquals("reports", r.text())
        assertEquals(401, t.send("GET", "/reports").status())
    }

    // OPS-DX-006 AC-3: HashCost.fixed hashes new passwords at exactly that count
    @Test
    fun `OPS-DX-006 AC-3 HashCost fixed`() {
        // docs-example: auth.md#58
        // adapted: a fixed test secret instead of JWT_SECRET; asserts follow the printed values
        val cost = HashCost.fixed(600_000)
        val auth = Auth.builder(AuthTestSupport.SECRET).hashCost(cost).build()
        val ann = auth.users().create("ann", "ann-password-1".toCharArray())
        val stored = auth.userStore().findById(ann.id!!)?.passwordHash
        println("fixed=${cost.isFixed} iterations=${cost.iterations} " + stored?.startsWith("pbkdf2-sha256\$600000\$"))
        assertTrue(cost.isFixed)
        assertEquals(600_000, cost.iterations)
        assertNotNull(stored)
        assertTrue(stored, stored!!.startsWith("pbkdf2-sha256\$600000\$"))
    }
}
