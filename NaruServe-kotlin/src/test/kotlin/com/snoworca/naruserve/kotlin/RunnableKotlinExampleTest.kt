package com.snoworca.naruserve.kotlin

import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.testing.common.RunnableExampleContract
import org.junit.Test

class RunnableKotlinExampleTest {
    @Test
    fun `OPS-DX-001 AC-1 AC-2 AC-3 AC-4 actual Kotlin source runs real HTTP from clean directory`() {
        val example = Class.forName("example.KotlinExample")
        RunnableExampleContract.verify { uploads ->
            example.getMethod("create", java.io.File::class.java).invoke(null, uploads) as RestApp
        }
    }
}
