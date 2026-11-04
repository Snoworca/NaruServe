package com.snoworca.naruserve.utils.auth

import com.snoworca.naruserve.utils.auth.testing.AuthTesting
import com.snoworca.naruserve.utils.auth.user.FileUserStore
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore
import java.io.File
import java.nio.file.Files
import org.junit.Test

/** Kotlin compile-and-run check of AuthTesting.verifyUserStore(store, reopen) (CLAUDE.md T11, FR-IAM-004). */
class AuthTestingReopenKotlinTest {

    // FR-IAM-004 AC-2: the reopen overload takes a plain Kotlin lambda
    @Test
    fun `verifyUserStore with a reopen lambda`() {
        val dir = Files.createTempDirectory("naru-reopen").toFile()
        try {
            val file = File(dir, "users.json")
            AuthTesting.verifyUserStore(FileUserStore(file)) { FileUserStore(file) }
            val memory = InMemoryUserStore()
            AuthTesting.verifyUserStore(memory) { memory }
        } finally {
            dir.deleteRecursively()
        }
    }
}
