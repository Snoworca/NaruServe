package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.utils.auth.group.GroupStore
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy
import com.snoworca.naruserve.utils.auth.rbac.Scopes
import com.snoworca.naruserve.utils.auth.user.AuthIds
import com.snoworca.naruserve.utils.auth.user.AuthUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The auth value types and store SPI as Kotlin sees them: properties, non-null types, a Kotlin object store. */
class AuthFoundationKotlinTest {

    // DR-IAM-001 AC-1: Kotlin reads AuthUser through property syntax with non-null and nullable types
    @Test
    fun `DR-IAM-001 AC-1 AuthUser properties in Kotlin`() {
        val user = AuthUser.builder().username("alice").roles("editor").serviceAccount(true).build()
        val name: String = user.username
        val enabled: Boolean = user.isEnabled
        val service: Boolean = user.isServiceAccount
        val roles: Set<String> = user.roles
        val attributes: Map<String, String> = user.attributes
        val id: String? = user.id
        val hash: String? = user.passwordHash
        val ver: Long = user.tokenVersion
        assertEquals("alice", name)
        assertTrue(enabled)
        assertTrue(service)
        assertEquals(setOf("editor"), roles)
        assertTrue(attributes.isEmpty())
        assertNull(id)
        assertNull(hash)
        assertEquals(0L, ver)
        val parsed: Long? = AuthIds.parseLong("42")
        assertEquals(42L, parsed)
        assertNull(AuthIds.parseLong("x"))
    }

    // DR-IAM-002 AC-1: a GroupStore implementing only the two direct methods compiles as a Kotlin object
    @Test
    fun `DR-IAM-002 AC-1 GroupStore as a Kotlin object`() {
        val store = object : GroupStore {
            override fun groupsOfUser(userId: String): Set<String> = if (userId == "alice") setOf("backend") else setOf()

            override fun parentsOf(groupIds: Set<String>): Set<String> =
                if ("backend" in groupIds) setOf("eng") else setOf()
        }
        assertEquals(setOf("backend", "eng"), store.effectiveGroupsOfUser("alice"))
        assertEquals(setOf<String>(), store.rolesOfGroups(setOf("eng")))
        assertFalse(store.effectiveGroupsOfUser("bob").iterator().hasNext())
    }

    // The Kotlin example of docs/api/auth.md runs
    @Test
    fun `docs example of RolePolicy and Scopes`() {
        // docs-example: auth.md#17
        val policy = RolePolicy.builder()
            .role("editor", "posts:read", "posts:write")
            .build()
        val user = AuthUser.builder().username("alice").roles("editor").build()
        val canWrite = user.roles.any { role -> policy.scopesOf(role).any { Scopes.covers(it, "posts:write") } }
        println("${user.username} enabled=${user.isEnabled} canWrite=$canWrite")
        assertTrue(canWrite)
        assertTrue(policy.scopesOf("admin").contains("*"))
    }
}
