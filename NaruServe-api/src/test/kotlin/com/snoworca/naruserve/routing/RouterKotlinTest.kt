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
}
