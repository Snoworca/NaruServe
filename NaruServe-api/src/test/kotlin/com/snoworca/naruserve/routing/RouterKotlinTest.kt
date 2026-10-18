package com.snoworca.naruserve.routing

import com.snoworca.naruserve.Method
import org.jspecify.annotations.NullMarked
import org.jspecify.annotations.Nullable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterKotlinTest {

    @Test
    fun `SDS-AC-2 CON-ROUTE-001 AC-3 match is nullable and Router API has no platform types`() {
        val policy: RouterPolicy = RouterPolicy.builder().caseInsensitive(true).build()
        val router: Router<String> = Router(policy)
        router.add(Method.GET, "/users/:id", "h")
        router.add(Method.get().post(), "/items", "i")
        router.addWebSocket("/ws", "w")
        router.freeze()
        val segments: List<String> = Router.decodeSegments("/USERS/7?x=1", policy)
        val pattern: RoutePattern = RoutePattern.parse("/users/:id")
        val params: Map<String, String>? = pattern.match(segments, policy)
        assertEquals("7", params?.get("id"))
        val none: Map<String, String>? = pattern.match(listOf("other"), policy)
        assertNull(none)
        val key: String = pattern.conflictKey(policy)
        assertEquals("/users/:", key)
        val rank: Int = pattern.compareSpecificity(RoutePattern.parse("/users/me"))
        assertTrue(rank < 0)
        val valid: Boolean = PathValidator.isValid("/users/7")
        assertTrue(valid)
    }

    @Test
    fun `SDS-AC-2 CON-ROUTE-001 AC-3 routing package is NullMarked and match carries Nullable`() {
        assertTrue(RoutePattern::class.java.`package`.isAnnotationPresent(NullMarked::class.java))
        val match = RoutePattern::class.java.getMethod("match", List::class.java, RouterPolicy::class.java)
        assertTrue(match.annotatedReturnType.isAnnotationPresent(Nullable::class.java))
    }

    // CON-ROUTE-001 AC-3: RouteMatch as Kotlin callers consume it. With -Xjspecify-annotations=strict these are
    // real Kotlin types, so `m.handler().length` (no `?.`) would not compile; `m.params()["id"]` needs no `!!`.
    @Test
    fun `CON-ROUTE-001 AC-3 lookup and RouteMatch from Kotlin`() {
        val router: Router<String> = Router(RouterPolicy.strict())
        router.add(Method.GET, "/users/:id", "user")
        router.freeze()
        val m: RouteMatch<String> = router.lookup("GET", "/users/7", false)
        val kind: RouteMatch.Kind = m.kind()
        assertEquals(RouteMatch.Kind.MATCHED, kind)
        assertEquals(4, m.handler()?.length)
        val params: Map<String, String> = m.params()
        assertEquals("7", params["id"])
        val method: Method? = m.method()
        assertEquals(Method.GET, method)
        val routeKind: RouteKind? = m.routeKind()
        assertEquals(RouteKind.HTTP, routeKind)
        assertNull(m.allow())
        val mismatch = router.lookup("POST", "/users/7", false)
        assertEquals(true, mismatch.allow()?.contains(Method.GET))
        assertNull(mismatch.handler())
        assertTrue(mismatch.params().isEmpty())
    }

    // CON-ROUTE-001 AC-3: every public method of the routing API is NullMarked, and exactly the documented
    // nullable returns carry @Nullable, so no return reaches Kotlin as a platform type.
    @Test
    fun `CON-ROUTE-001 AC-3 nullable returns are exactly the documented ones`() {
        val expectedNullable = mapOf(
            RouteMatch::class.java to setOf("handler", "method", "routeKind", "allow"),
            Router::class.java to emptySet(),
            RoutePattern::class.java to setOf("match"),
            PathValidator::class.java to emptySet(),
            RouterPolicy::class.java to emptySet(),
        )
        for ((type, nullable) in expectedNullable) {
            assertTrue(type.name, type.`package`.isAnnotationPresent(NullMarked::class.java))
            for (m in type.methods) {
                if (m.declaringClass != type || m.returnType.isPrimitive) continue
                val annotated = m.annotatedReturnType.isAnnotationPresent(Nullable::class.java)
                assertEquals("${type.simpleName}.${m.name}", m.name in nullable, annotated)
            }
        }
    }
}
