package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** app.use(auth) and group roles from Kotlin (CLAUDE.md T11). */
class AuthUseKotlinTest {

    // FR-AUTH-010 AC-5: app.use(auth) written in Kotlin installs auth; the login endpoint answers 200
    @Test
    fun `FR-AUTH-010 AC-5 app use auth from Kotlin`() {
        val w = W8()
        w.attach = java.util.function.BiConsumer { auth, app -> app.use(auth) }
        try {
            w.user("alice", W8.PW, "reader")
            w.start { it }
            val login = w.post("/auth/login", W8.login("alice", W8.PW))
            assertEquals(login.text(), 200, login.status())
        } finally {
            w.stop()
        }
    }

    // FR-IAM-016 AC-5: a group role granted and read back from Kotlin
    @Test
    fun `FR-IAM-016 AC-5 group roles from Kotlin`() {
        val groups = InMemoryGroupStore()
        groups.createGroup("editors")
        groups.grantRole("editors", "editor")
        assertEquals(setOf("editor"), groups.rolesOf("editors"))
        assertEquals(setOf("editor"), groups.rolesOfGroups(setOf("editors")))
        groups.revokeRole("editors", "editor")
        assertEquals(emptySet<String>(), groups.rolesOf("editors"))
    }
}
